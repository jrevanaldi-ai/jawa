// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.noise;

import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;
import id.jawa.core.WaConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket transport that exchanges 24-bit-big-endian length-prefixed binary frames.
 *
 * <p>Maps to whatsmeow's {@code socket.FrameSocket} and the encoding half of Baileys'
 * {@code makeNoiseHandler}. The WA intro header ({@code 'W','A',6,3}) is prepended
 * once to the very first outbound frame and then dropped on subsequent sends.
 */
public final class FrameSocket implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(FrameSocket.class);

    private final BlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
    private final AtomicBoolean introSent = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final byte[] introHeader;
    private final byte[] readBuf = new byte[WaConstants.FRAME_MAX_SIZE];
    private int readBufLen = 0;
    private WebSocket ws;

    public FrameSocket(byte[] routingInfo) {
        this.introHeader = (routingInfo == null || routingInfo.length == 0)
            ? WaConstants.WA_HEADER
            : buildRoutedHeader(routingInfo);
    }

    public FrameSocket() { this(null); }

    /** Connect to {@code wss://web.whatsapp.com/ws/chat}. Blocks until the WS upgrade completes. */
    public void connect() throws IOException, WebSocketException {
        if (ws != null) throw new IllegalStateException("already connected");
        ws = new WebSocketFactory()
                .setConnectionTimeout(15_000)
                .createSocket(WaConstants.WS_URL)
                .addHeader("Origin", WaConstants.ORIGIN)
                .addListener(new Adapter());
        ws.connect();
        LOG.debug("WS connected to {}", WaConstants.WS_URL);
    }

    /** Send a single framed payload. {@code payload} is the post-header / post-length payload. */
    public synchronized void send(byte[] payload) {
        if (ws == null || closed.get()) throw new IllegalStateException("socket not open");
        if (payload.length > WaConstants.FRAME_MAX_SIZE) {
            throw new IllegalArgumentException("frame too large: " + payload.length);
        }
        int introLen = introSent.compareAndSet(false, true) ? introHeader.length : 0;
        byte[] frame = new byte[introLen + 3 + payload.length];
        if (introLen > 0) System.arraycopy(introHeader, 0, frame, 0, introLen);
        frame[introLen]     = (byte) ((payload.length >> 16) & 0xFF);
        frame[introLen + 1] = (byte) ((payload.length >> 8)  & 0xFF);
        frame[introLen + 2] = (byte) ( payload.length        & 0xFF);
        System.arraycopy(payload, 0, frame, introLen + 3, payload.length);
        ws.sendBinary(frame);
    }

    /** Block for the next complete frame payload (no length prefix). */
    public byte[] receive(long timeoutMs) throws InterruptedException {
        byte[] f = incoming.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (f == null && closed.get()) throw new IllegalStateException("socket closed");
        return f;
    }

    public boolean isOpen() { return ws != null && !closed.get(); }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && ws != null) ws.disconnect();
    }

    private static byte[] buildRoutedHeader(byte[] routingInfo) {
        // 'E','D', 0, 1, len24_be (3 bytes), routingInfo..., WA_HEADER...
        int rl = routingInfo.length;
        byte[] h = new byte[7 + rl + WaConstants.WA_HEADER.length];
        h[0] = 'E'; h[1] = 'D';
        h[2] = 0; h[3] = 1;
        h[4] = (byte) ((rl >> 16) & 0xFF);
        h[5] = (byte) ((rl >> 8)  & 0xFF);
        h[6] = (byte) ( rl        & 0xFF);
        System.arraycopy(routingInfo, 0, h, 7, rl);
        System.arraycopy(WaConstants.WA_HEADER, 0, h, 7 + rl, WaConstants.WA_HEADER.length);
        return h;
    }

    private final class Adapter extends WebSocketAdapter {
        @Override
        public void onBinaryMessage(WebSocket s, byte[] binary) {
            appendAndDispatch(binary);
        }
        @Override
        public void onTextMessage(WebSocket s, String text) {
            LOG.warn("unexpected WS text frame: {}", text);
        }
        @Override
        public void onError(WebSocket s, WebSocketException cause) {
            LOG.warn("WS error", cause);
        }
        @Override
        public void onDisconnected(WebSocket s, WebSocketFrame serverCloseFrame,
                                   WebSocketFrame clientCloseFrame, boolean closedByServer) {
            LOG.debug("WS disconnected (closedByServer={})", closedByServer);
            closed.set(true);
            // wake any blocking receive with a sentinel — null is reserved for timeout, so push empty.
            // Actually: just rely on isOpen()/closed flag; receive callers should also check.
        }
    }

    /**
     * Append incoming WebSocket binary data to the read buffer and emit every
     * complete framed payload. Handles cases where multiple frames are concatenated
     * in one WS message OR a single frame's 3-byte length prefix is split across
     * two WS messages.
     */
    private synchronized void appendAndDispatch(byte[] chunk) {
        if (readBufLen + chunk.length > readBuf.length) {
            // realloc by switching to ByteArrayOutputStream would be safer; for now, fail loudly.
            // 16 MiB frame max means this only triggers under attack or extreme history sync.
            throw new IllegalStateException("read buffer overflow");
        }
        System.arraycopy(chunk, 0, readBuf, readBufLen, chunk.length);
        readBufLen += chunk.length;

        int offset = 0;
        while (true) {
            if (readBufLen - offset < 3) break;
            int len = ((readBuf[offset] & 0xFF) << 16)
                    | ((readBuf[offset + 1] & 0xFF) << 8)
                    |  (readBuf[offset + 2] & 0xFF);
            if (readBufLen - offset - 3 < len) break;
            byte[] frame = new byte[len];
            System.arraycopy(readBuf, offset + 3, frame, 0, len);
            incoming.add(frame);
            offset += 3 + len;
        }
        if (offset > 0) {
            int remaining = readBufLen - offset;
            if (remaining > 0) System.arraycopy(readBuf, offset, readBuf, 0, remaining);
            readBufLen = remaining;
        }
    }
}
