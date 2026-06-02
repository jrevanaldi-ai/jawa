// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.pair;

import com.google.protobuf.ByteString;
import id.jawa.core.WaConstants;
import id.jawa.proto.Wa;
import id.jawa.store.AuthCreds;
import id.jawa.util.Bytes;
import id.jawa.util.Jid;
import id.jawa.util.crypto.Curve25519;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Builds the {@code ClientPayload} that goes into {@code HandshakeMessage.clientFinish.payload}.
 *
 * <p>Two flavours:
 * <ul>
 *   <li>{@link #register(AuthCreds)} — first connect, server sends back QR refs
 *   <li>{@link #login(AuthCreds)} — reconnect with persisted creds
 * </ul>
 *
 * <p>Mirrors {@code generateRegistrationNode} and {@code generateLoginNode} in Baileys'
 * {@code Utils/validate-connection.ts}.
 */
public final class ClientPayloadBuilder {

    /** Browser identification: {OS-name, browser-name, version}. */
    public static final String[] DEFAULT_BROWSER = { "JaWa", "Chrome", "0.1.0" };
    public static final String DEFAULT_COUNTRY_CODE = "ID";
    public static final String DEFAULT_LANGUAGE_CODE = "id";

    private ClientPayloadBuilder() {}

    public static Wa.ClientPayload register(AuthCreds creds) {
        byte[] regIdBE = encodeUint32BE(creds.registrationId);
        // Some clients encode registrationId as 4-byte BE, others as 2-byte. WhatsApp's
        // wire schema expects 2 bytes for registrationId since it fits in uint16.
        // Match Baileys: encodeBigEndian(registrationId) defaults to 4 bytes.

        Wa.DeviceProps deviceProps = Wa.DeviceProps.newBuilder()
                .setOs(DEFAULT_BROWSER[0])
                .setPlatformType(Wa.DeviceProps.PlatformType.CHROME)
                .setRequireFullSync(false)
                .setVersion(Wa.DeviceProps.AppVersion.newBuilder()
                        .setPrimary(10).setSecondary(15).setTertiary(7).build())
                .build();

        Wa.ClientPayload.DevicePairingRegistrationData regData =
            Wa.ClientPayload.DevicePairingRegistrationData.newBuilder()
                .setERegid(ByteString.copyFrom(regIdBE))
                .setEKeytype(ByteString.copyFrom(new byte[] { Curve25519.KEY_BUNDLE_TYPE }))
                .setEIdent(ByteString.copyFrom(creds.signedIdentityKey.publicKey()))
                .setESkeyId(ByteString.copyFrom(encodeUint24BE(creds.signedPreKey.keyId())))
                .setESkeyVal(ByteString.copyFrom(creds.signedPreKey.keyPair().publicKey()))
                .setESkeySig(ByteString.copyFrom(creds.signedPreKey.signature()))
                .setBuildHash(ByteString.copyFrom(md5(joinVersion(WaConstants.WA_VERSION))))
                .setDeviceProps(deviceProps.toByteString())
                .build();

        return common()
                .setPassive(false)
                .setPull(false)
                .setDevicePairingData(regData)
                .build();
    }

    public static Wa.ClientPayload login(AuthCreds creds) {
        Jid me = Jid.parse(creds.meJid);
        if (me == null) throw new IllegalStateException("creds.meJid is invalid: " + creds.meJid);
        return common()
                .setPassive(true)
                .setPull(true)
                .setUsername(Long.parseLong(me.user()))
                .setDevice(me.device())
                .setLidDbMigrated(false)
                .build();
    }

    // ---- helpers ----

    private static Wa.ClientPayload.Builder common() {
        Wa.ClientPayload.UserAgent.AppVersion ver = Wa.ClientPayload.UserAgent.AppVersion.newBuilder()
                .setPrimary(WaConstants.WA_VERSION[0])
                .setSecondary(WaConstants.WA_VERSION[1])
                .setTertiary(WaConstants.WA_VERSION[2])
                .build();

        Wa.ClientPayload.UserAgent ua = Wa.ClientPayload.UserAgent.newBuilder()
                .setAppVersion(ver)
                .setPlatform(Wa.ClientPayload.UserAgent.Platform.WEB)
                .setReleaseChannel(Wa.ClientPayload.UserAgent.ReleaseChannel.RELEASE)
                .setOsVersion("0.1")
                .setDevice("Desktop")
                .setOsBuildNumber("0.1")
                .setLocaleLanguageIso6391(DEFAULT_LANGUAGE_CODE)
                .setMnc("000")
                .setMcc("000")
                .setLocaleCountryIso31661Alpha2(DEFAULT_COUNTRY_CODE)
                .build();

        Wa.ClientPayload.WebInfo webInfo = Wa.ClientPayload.WebInfo.newBuilder()
                .setWebSubPlatform(Wa.ClientPayload.WebInfo.WebSubPlatform.WEB_BROWSER)
                .build();

        return Wa.ClientPayload.newBuilder()
                .setUserAgent(ua)
                .setWebInfo(webInfo)
                .setConnectType(Wa.ClientPayload.ConnectType.WIFI_UNKNOWN)
                .setConnectReason(Wa.ClientPayload.ConnectReason.USER_ACTIVATED);
    }

    private static String joinVersion(int[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(v[i]);
        }
        return sb.toString();
    }

    private static byte[] md5(String s) {
        try {
            return MessageDigest.getInstance("MD5").digest(Bytes.utf8(s));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static byte[] encodeUint32BE(int v) {
        return new byte[] {
            (byte) ((v >> 24) & 0xFF),
            (byte) ((v >> 16) & 0xFF),
            (byte) ((v >>  8) & 0xFF),
            (byte) ( v        & 0xFF),
        };
    }

    private static byte[] encodeUint24BE(int v) {
        return new byte[] {
            (byte) ((v >> 16) & 0xFF),
            (byte) ((v >>  8) & 0xFF),
            (byte) ( v        & 0xFF),
        };
    }
}
