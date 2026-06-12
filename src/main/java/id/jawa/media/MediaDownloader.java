// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/**
 * Download an encrypted media payload by URL or by {@code directPath}, verify the
 * envelope SHA-256, then decrypt via {@link MediaCrypto#decrypt}.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #downloadByUrl(String, byte[], byte[], MediaCrypto.MediaType)} —
 *       when the inbound {@code Wa.Message} has a non-empty {@code url} field, GET it
 *       directly.</li>
 *   <li>{@link #downloadByDirectPath(MediaConn, String, byte[], byte[],
 *       MediaCrypto.MediaType)} — when only {@code directPath} is set, compose
 *       {@code https://<host><directPath>&hash=<b64(fileEncSha256)>&mms-type=<type>&__wa-mms=}
 *       and try each {@link MediaConn} host in order until one returns 200.</li>
 * </ul>
 */
public final class MediaDownloader {

    private static final Logger LOG = LoggerFactory.getLogger(MediaDownloader.class);

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    private static final Map<MediaCrypto.MediaType, String> MMS_TYPE = Map.of(
        MediaCrypto.MediaType.IMAGE,    "image",
        MediaCrypto.MediaType.VIDEO,    "video",
        MediaCrypto.MediaType.AUDIO,    "audio",
        MediaCrypto.MediaType.DOCUMENT, "document"
    );

    private MediaDownloader() {}

    /**
     * GET {@code url}, verify {@code fileEncSha256}, decrypt with {@code mediaKey}
     * and return plaintext bytes.
     */
    public static byte[] downloadByUrl(String url,
                                       byte[] mediaKey,
                                       byte[] fileEncSha256,
                                       MediaCrypto.MediaType type)
            throws IOException, InterruptedException {
        byte[] payload = httpGet(url);
        return verifyAndDecrypt(payload, mediaKey, fileEncSha256, type);
    }

    /**
     * Try each {@link MediaConn} host in order until one returns 200, then verify
     * and decrypt as in {@link #downloadByUrl}.
     *
     * @param directPath the {@code direct_path} from the inbound message (must start
     *                   with {@code /}; whatsapp's value already includes query params,
     *                   so the constructed URL appends with {@code &} not {@code ?})
     */
    public static byte[] downloadByDirectPath(MediaConn mediaConn,
                                              String directPath,
                                              byte[] mediaKey,
                                              byte[] fileEncSha256,
                                              MediaCrypto.MediaType type)
            throws IOException, InterruptedException {
        if (directPath == null || !directPath.startsWith("/")) {
            throw new IllegalArgumentException("directPath must start with '/', got: " + directPath);
        }
        if (mediaConn.hosts().isEmpty()) {
            throw new IllegalStateException("mediaConn has no hosts");
        }
        String mmsType = MMS_TYPE.get(type);
        String encHashB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(fileEncSha256);

        IOException lastErr = null;
        for (String host : mediaConn.hosts()) {
            String url = "https://" + host + directPath
                + "&hash=" + encHashB64
                + "&mms-type=" + mmsType
                + "&__wa-mms=";
            try {
                byte[] payload = httpGet(url);
                return verifyAndDecrypt(payload, mediaKey, fileEncSha256, type);
            } catch (IOException e) {
                LOG.debug("Download from {} failed ({}), trying next host", host, e.toString());
                lastErr = e;
            }
        }
        throw new IOException("media download failed across all "
            + mediaConn.hosts().size() + " host(s); last error: " + lastErr, lastErr);
    }

    private static byte[] httpGet(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Origin", "https://web.whatsapp.com")
            .header("Referer", "https://web.whatsapp.com/")
            .timeout(Duration.ofMinutes(2))
            .GET()
            .build();
        HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IOException("media download failed: HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private static byte[] verifyAndDecrypt(byte[] payload,
                                           byte[] mediaKey,
                                           byte[] fileEncSha256,
                                           MediaCrypto.MediaType type) throws IOException {
        if (fileEncSha256 != null && fileEncSha256.length == 32) {
            byte[] actual = sha256(payload);
            if (!Arrays.equals(actual, fileEncSha256)) {
                throw new IOException("downloaded payload SHA-256 mismatch — "
                    + "expected " + hex(fileEncSha256) + ", got " + hex(actual));
            }
        }
        return MediaCrypto.decrypt(payload, mediaKey, type);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
