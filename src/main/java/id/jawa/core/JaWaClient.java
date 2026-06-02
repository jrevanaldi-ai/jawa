// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.core;

import com.google.protobuf.ByteString;
import id.jawa.binary.BinaryDecoder;
import id.jawa.binary.BinaryEncoder;
import id.jawa.binary.BinaryNode;
import id.jawa.noise.FrameSocket;
import id.jawa.noise.NoiseHandshake;
import id.jawa.noise.NoiseTransport;
import id.jawa.pair.ClientPayloadBuilder;
import id.jawa.pair.PairingHandler;
import id.jawa.proto.Wa;
import id.jawa.signal.InMemorySignalKeyStore;
import id.jawa.signal.PreKeyManager;
import id.jawa.signal.SignalKeyStore;
import id.jawa.store.AuthCreds;
import id.jawa.store.AuthStore;
import id.jawa.util.Bytes;
import id.jawa.util.crypto.Curve25519;
import id.jawa.util.crypto.KeyPair25519;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Top-level WhatsApp Web client.
 *
 * <p>Drives the connection lifecycle:
 * <ol>
 *   <li>Load (or generate) {@link AuthCreds}.
 *   <li>Open the WebSocket via {@link FrameSocket}.
 *   <li>Run the Noise XX handshake. On {@code clientFinish.payload}, send either
 *     a {@code RegisterPayload} (first pair) or {@code LoginPayload} (reconnect).
 *   <li>Enter the steady-state read loop. For first-pair, dispatch
 *     {@code <pair-device>} / {@code <pair-success>} IQs via {@link PairingHandler}.
 *   <li>Persist creds after pair-success.
 * </ol>
 */
public final class JaWaClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(JaWaClient.class);

    public interface Listener {
        /** Server sent QR refs. Each string is {@code ref,noisePub,identityPub,advSecret} — render as a QR. */
        default void onQr(List<String> qrStrings) {}
        /** Pairing completed; creds persisted. */
        default void onPaired(String jid, String pushName, String platform) {}
        /** Steady-state connection up. */
        default void onConnected() {}
        /** Inbound stanza after handshake/pairing. */
        default void onStanza(BinaryNode node) {}
        /** Fatal error. */
        default void onError(Throwable t) {}
    }

    private final AuthStore store;
    private final SignalKeyStore signalStore = new InMemorySignalKeyStore();
    private Listener listener = new Listener() {};
    private FrameSocket frame;
    private NoiseHandshake noise;
    private NoiseTransport transport;
    private AuthCreds creds;
    private Thread readerThread;
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private static final int PRE_KEY_UPLOAD_COUNT = 30;

    public JaWaClient(AuthStore store) { this.store = store; }

    public JaWaClient listener(Listener l) { this.listener = l != null ? l : new Listener() {}; return this; }

    /** Open the WebSocket, run the handshake, and start dispatching stanzas. Blocks until handshake completes. */
    public synchronized void connect() throws Exception {
        if (frame != null) throw new IllegalStateException("already connected");

        creds = store.load();
        boolean isPairing = (creds == null || creds.account == null);
        if (creds == null) {
            creds = AuthCreds.generate();
            LOG.info("No creds found — generated fresh pair: regId={}", creds.registrationId);
        } else if (isPairing) {
            LOG.info("Found unpaired creds (regId={}) — resuming QR pairing", creds.registrationId);
        } else {
            LOG.info("Found paired creds — login mode (jid={})", creds.meJid);
        }

        frame = new FrameSocket(null);
        frame.connect();

        // ---- Noise XX, client side ----

        KeyPair25519 ephemeral = Curve25519.generateKeyPair();
        noise = new NoiseHandshake(ephemeral, WaConstants.WA_HEADER);

        // Message 1: ClientHello
        Wa.HandshakeMessage clientHello = Wa.HandshakeMessage.newBuilder()
                .setClientHello(Wa.HandshakeMessage.ClientHello.newBuilder()
                        .setEphemeral(ByteString.copyFrom(ephemeral.publicKey()))
                        .build())
                .build();
        frame.send(clientHello.toByteArray());

        // Message 2: ServerHello
        byte[] serverHelloBytes = frame.receive(15_000);
        if (serverHelloBytes == null) throw new IllegalStateException("ServerHello timeout");
        Wa.HandshakeMessage serverFrame = Wa.HandshakeMessage.parseFrom(serverHelloBytes);
        Wa.HandshakeMessage.ServerHello serverHello = serverFrame.getServerHello();

        byte[] encStatic = noise.processServerHello(
            serverHello.getEphemeral().toByteArray(),
            serverHello.getStatic().toByteArray(),
            serverHello.getPayload().toByteArray(),
            creds.noiseKey
        );

        // Message 3: ClientFinish — encrypt ClientPayload with the NEW handshake key
        Wa.ClientPayload payload = isPairing
                ? ClientPayloadBuilder.register(creds)
                : ClientPayloadBuilder.login(creds);
        byte[] encPayload = noise.encrypt(payload.toByteArray());

        Wa.HandshakeMessage clientFinish = Wa.HandshakeMessage.newBuilder()
                .setClientFinish(Wa.HandshakeMessage.ClientFinish.newBuilder()
                        .setStatic(ByteString.copyFrom(encStatic))
                        .setPayload(ByteString.copyFrom(encPayload))
                        .build())
                .build();
        frame.send(clientFinish.toByteArray());

        transport = noise.finish();
        LOG.info("Noise handshake complete — steady state {}", isPairing ? "(pairing)" : "(login)");

        // ---- Start reader loop ----

        readerThread = Thread.ofVirtual().name("jawa-reader").start(this::readLoop);
    }

    /** Block the caller until the reader thread terminates (e.g. logout, disconnect). */
    public void join() throws InterruptedException {
        if (readerThread != null) readerThread.join();
    }

    private void readLoop() {
        PairingHandler pair = new PairingHandler(creds);
        try {
            while (!closing.get() && frame.isOpen()) {
                byte[] enc = frame.receive(60_000);
                if (enc == null) {
                    if (!frame.isOpen()) break;
                    continue;
                }
                byte[] plain = transport.decrypt(enc);
                BinaryNode node = BinaryDecoder.decode(plain);
                LOG.trace("recv: {}", node);

                if (handleStanza(node, pair)) continue;
                listener.onStanza(node);
            }
        } catch (Throwable t) {
            if (!closing.get()) listener.onError(t);
        } finally {
            close();
        }
    }

    private final java.util.Map<String, java.util.function.Consumer<BinaryNode>> pendingIqResults
        = new java.util.concurrent.ConcurrentHashMap<>();

    /** Random 16-char hex id for outgoing IQ stanzas. */
    private String newIqId() {
        return Bytes.toHex(Bytes.random(8));
    }

    /**
     * Send an IQ stanza and register a callback for the {@code <iq type=result|error>} response.
     * The callback runs on the reader thread.
     */
    public String sendIq(BinaryNode iq, java.util.function.Consumer<BinaryNode> onResponse) {
        String id = iq.attr("id");
        if (id == null) throw new IllegalArgumentException("iq has no id attribute");
        if (onResponse != null) pendingIqResults.put(id, onResponse);
        send(iq);
        return id;
    }

    /** Send an IQ stanza and return a future that completes with the response. */
    public java.util.concurrent.CompletableFuture<BinaryNode> sendIqAsync(BinaryNode iq) {
        var f = new java.util.concurrent.CompletableFuture<BinaryNode>();
        sendIq(iq, f::complete);
        return f;
    }

    /**
     * Query the device list for one or more JIDs (USync).
     * Returns {@code targetJid → ordered device list}.
     */
    public java.util.concurrent.CompletableFuture<java.util.Map<String, java.util.List<id.jawa.message.DeviceInfo>>>
            queryDevices(java.util.Collection<String> targetJids) {
        String iqId = newIqId();
        String sid = "jawa-" + Bytes.toHex(Bytes.random(4));
        BinaryNode q = id.jawa.message.UsyncQuery.buildDeviceListQuery(iqId, sid, targetJids);
        LOG.debug("Sent USync device-list query id={} sid={} for {}", iqId, sid, targetJids);
        return sendIqAsync(q).thenApply(resp -> {
            LOG.debug("USync response: {}", resp);
            return id.jawa.message.UsyncQuery.parseDeviceListResult(resp);
        });
    }

    /** Upload {@code PRE_KEY_UPLOAD_COUNT} one-time pre-keys to the server. */
    private void uploadPreKeys() {
        var preKeys = PreKeyManager.generate(creds, signalStore, PRE_KEY_UPLOAD_COUNT);
        if (preKeys.isEmpty()) {
            LOG.debug("No new pre-keys to upload");
            return;
        }
        String iqId = newIqId();
        BinaryNode iq = PreKeyManager.buildUploadStanza(iqId, creds, preKeys);
        sendIq(iq, response -> {
            if ("error".equals(response.attr("type"))) {
                LOG.warn("Pre-key upload rejected: {}", response);
                return;
            }
            PreKeyManager.markUploaded(creds, preKeys);
            try { store.save(creds); } catch (Exception e) { LOG.warn("Failed to save creds after pre-key upload", e); }
            LOG.info("Uploaded {} pre-keys (ids {}..{})",
                preKeys.size(),
                preKeys.keySet().iterator().next(),
                preKeys.keySet().stream().reduce((a, b) -> b).get());
        });
        LOG.debug("Sent pre-key upload IQ id={} ({} keys)", iqId, preKeys.size());
    }

    private boolean handleStanza(BinaryNode node, PairingHandler pair) throws Exception {
        if ("stream:error".equals(node.tag())) {
            String code = node.attr("code");
            if ("515".equals(code)) {
                // Documented post-pair signal — server wants us to reconnect with login creds.
                LOG.info("Server requested restart (code=515) — expected after pairing; reconnect to enter steady state");
            } else {
                LOG.warn("Stream error: {}", node);
            }
            return true;
        }
        if ("xmlstreamend".equals(node.tag())) {
            LOG.debug("Server sent stream end");
            return true;
        }
        if ("success".equals(node.tag())) {
            LOG.info("Login successful — meJid={} lid={} platform={}",
                node.attr("jid", creds.meJid),
                node.attr("lid", creds.meLid),
                node.attr("platform", creds.platform));
            listener.onConnected();
            uploadPreKeys();
            return true;
        }
        if (!"iq".equals(node.tag())) return false;

        // Result / error for an IQ we sent — fire the pending callback if any.
        String type = node.attr("type");
        if ("result".equals(type) || "error".equals(type)) {
            String id = node.attr("id");
            var cb = id == null ? null : pendingIqResults.remove(id);
            if (cb != null) { cb.accept(node); return true; }
        }

        BinaryNode pairDevice  = node.child("pair-device");
        BinaryNode pairSuccess = node.child("pair-success");

        if (pairDevice != null) {
            List<String> qrs = pair.qrRefsFrom(pairDevice);
            send(pair.ackPairDevice(node.attr("id")));
            LOG.info("Got {} QR refs", qrs.size());
            listener.onQr(qrs);
            return true;
        }
        if (pairSuccess != null) {
            BinaryNode reply = pair.handlePairSuccess(node);
            send(reply);
            store.save(creds);
            listener.onPaired(creds.meJid, creds.pushName, creds.platform);
            return true;
        }
        // Auto-reply to keepalive pings so the connection stays up
        if ("get".equals(node.attr("type"))
                && "urn:xmpp:ping".equals(node.attr("xmlns"))) {
            send(new BinaryNode("iq",
                java.util.Map.of("to", id.jawa.util.Jid.SERVER_WHATSAPP,
                                 "type", "result",
                                 "id", node.attr("id")),
                null));
            return true;
        }
        return false;
    }

    /** Send a binary stanza (encrypts + frames). */
    public void send(BinaryNode node) {
        byte[] plain = BinaryEncoder.encode(node);
        byte[] enc = transport.encrypt(plain);
        frame.send(enc);
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) return;
        if (frame != null) frame.close();
    }

    public AuthCreds creds() { return creds; }
}
