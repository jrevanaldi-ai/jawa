# JaWa — Java WhatsApp Web library

[![build](https://github.com/jochris/JaWa/actions/workflows/build.yml/badge.svg)](https://github.com/jochris/JaWa/actions/workflows/build.yml)
[![license](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](LICENSE)
[![java](https://img.shields.io/badge/java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![JitPack](https://img.shields.io/badge/JitPack-v0.0.2-brightgreen)](https://jitpack.io/#jochris/JaWa)

> Unofficial **Java 21** library for the WhatsApp Web multi-device protocol,
> ported from [Baileys](https://github.com/WhiskeySockets/Baileys) (TypeScript) and
> [whatsmeow](https://github.com/tulir/whatsmeow) (Go).

> [!CAUTION]
> **Status: pre-alpha.** Multi-month build in progress — protocol surface incomplete
> (no media, no app-state sync, no persistent Signal store yet). **Do not use against
> your primary WhatsApp account.** WhatsApp explicitly forbids unofficial clients;
> using this library carries account-suspension risk.

# Disclaimer

This project is not affiliated, associated, authorized, endorsed by, or in any way
officially connected with WhatsApp or any of its subsidiaries or its affiliates.
"WhatsApp" and related marks are registered trademarks of their respective owners.

The maintainers do not condone use of this project in ways that violate WhatsApp's
Terms of Service. Use a dedicated test number, never your primary account. No bulk
messaging, no stalkerware, no spam.

##

- JaWa talks to WhatsApp Web directly over **WebSocket + Noise XX + libsignal**.
  No Selenium, no Chromium — saves you ~500 MB of RAM.
- Java 21+ only. Built on records, sealed classes, virtual threads, pattern matching.
- Source-of-truth references (Baileys + whatsmeow) live in-tree under `references/`
  for protocol disambiguation.

# Stack

- **JDK 21** (records, sealed classes, virtual threads, pattern matching)
- **Gradle** (Kotlin DSL)
- **BouncyCastle** — Curve25519 ECDH, AES-GCM, HKDF-SHA256, HMAC
- **signal-protocol-java** — XEdDSA sign/verify, X3DH, Double Ratchet, Sender Keys
- **protobuf-java** (pinned to `3.10.0` — see Gotchas)
- **nv-websocket-client** — WebSocket transport
- **ZXing** — terminal-rendered pairing QR

# Module layout

```
id.jawa.binary    — WhatsApp binary node encoder/decoder
id.jawa.noise     — Noise_XX_25519_AESGCM_SHA256 handshake + framed AEAD transport
id.jawa.signal    — Signal Protocol integration (pre-keys, sessions, sender keys)
id.jawa.pair      — Multi-device pairing (QR + phone-number code)
id.jawa.message   — Message stanza send/receive, encoder/decoder, group sender
id.jawa.media     — Media upload/download (HKDF-AES-CBC + HMAC) — TODO
id.jawa.appstate  — App-state sync (LT-Hash, mutations) — TODO
id.jawa.store     — Pluggable session/key persistence
id.jawa.proto     — Generated protobuf classes
id.jawa.event     — Event listener API (folded into core.JaWaClient.Listener)
id.jawa.core      — Client facade + public API
id.jawa.util      — JID, base64url, hex, crypto helpers
```

# Install

JaWa is published via [JitPack](https://jitpack.io). Every non-SNAPSHOT `version` bump on
`main` auto-creates a matching git tag + GitHub Release; JitPack resolves the tag on demand.

**Gradle (Kotlin DSL):**

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.jochris:JaWa:v0.0.2")
}
```

**Maven:**

```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>

<dependency>
    <groupId>com.github.jochris</groupId>
    <artifactId>JaWa</artifactId>
    <version>v0.0.2</version>
</dependency>
```

> [!NOTE]
> `logback-classic` is `runtimeOnly` in JaWa's build. Supply your own SLF4J binding
> (logback, log4j-slf4j, etc.) in the consumer project.

# Try the demo

```sh
# QR pairing
./gradlew run -PsessionFile=sessions/myphone.session --console=plain

# Phone-number pairing code (preferred when testing)
./gradlew run -PsessionFile=sessions/myphone.session -Djawa.phone=628xxxxxxxxx --console=plain
```

`build.gradle.kts` forwards every `-Djawa.*` system property on the Gradle CLI through to
the application JVM. Useful demo knobs: `jawa.session`, `jawa.phone`, `jawa.target`,
`jawa.text`, `jawa.target_group`, `jawa.list_groups`.

# Index

- [Connecting Account](#connecting-account)
    - [Connect with QR-CODE](#connect-with-qr-code)
    - [Connect with Pairing Code](#connect-with-pairing-code)
    - [Saving & Restoring Sessions](#saving--restoring-sessions)
- [Handling Events](#handling-events)
    - [The Listener Interface](#the-listener-interface)
    - [Minimal Echo Bot Example](#minimal-echo-bot-example)
- [Sending Messages](#sending-messages)
    - [Text Message (DM)](#text-message-dm)
    - [Text Message (Group)](#text-message-group)
    - [Send Arbitrary `Wa.Message`](#send-arbitrary-wamessage)
- [Modify Messages](#modify-messages)
    - [Reaction](#reaction)
    - [Reply / Quote](#reply--quote)
    - [Edit Message](#edit-message)
    - [Revoke (Delete for Everyone)](#revoke-delete-for-everyone)
- [Media](#media)
    - [Send Image](#send-image)
    - [Send Video](#send-video)
    - [Send Audio / Voice Note](#send-audio--voice-note)
    - [Send Document](#send-document)
    - [Download Received Media](#download-received-media)
    - [Low-level Media APIs](#low-level-media-apis)
- [Receipts](#receipts)
    - [Send Read / Played Receipt](#send-read--played-receipt)
    - [Observe Receipts from Peers](#observe-receipts-from-peers)
- [Receiving Messages](#receiving-messages)
- [WhatsApp IDs / JIDs Explained](#whatsapp-ids--jids-explained)
- [User & Device Queries](#user--device-queries)
    - [Query Devices for a User](#query-devices-for-a-user)
    - [Bootstrap Signal Sessions](#bootstrap-signal-sessions)
- [Groups](#groups)
    - [List Joined Groups](#list-joined-groups)
    - [Create Group](#create-group)
    - [Leave Group](#leave-group)
    - [Add / Remove / Promote / Demote Participants](#add--remove--promote--demote-participants)
    - [Change Subject](#change-subject)
    - [Set / Clear Description (Topic)](#set--clear-description-topic)
- [Low-level APIs](#low-level-apis)
    - [Send Raw Stanza](#send-raw-stanza)
    - [Send IQ with Response](#send-iq-with-response)
- [Status — what works today](#status--what-works-today)
- [Gotchas](#gotchas)
- [Contributing](#contributing)
- [License](#license)

## Connecting Account

WhatsApp's multi-device API lets JaWa authenticate as a linked device alongside your
phone. Two flows are supported.

> [!IMPORTANT]
> Sessions are persisted to a single file via `FileAuthStore`. The file holds the
> Noise static key, Signal identity key, and (post-pair) the signed device identity.
> **Treat it as a secret.** The path `sessions/*.session` is gitignored by default;
> never check one in.

### Connect with QR-CODE

```java
import id.jawa.core.JaWaClient;
import id.jawa.store.FileAuthStore;
import id.jawa.util.QrTerminal;

import java.nio.file.Path;
import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception {
        FileAuthStore store = new FileAuthStore(Path.of("sessions/mydev.session"));
        JaWaClient client = new JaWaClient(store);

        client.listener(new JaWaClient.Listener() {
            @Override public void onQr(List<String> qrs) {
                // Each entry is "ref,noisePub,identityPub,advSecret" — render as QR.
                // JaWa ships QrTerminal for ANSI half-block rendering.
                System.out.print(QrTerminal.render(qrs.get(0)));
            }
            @Override public void onPaired(String jid, String pushName, String platform) {
                System.out.println("Paired as " + jid);
            }
            @Override public void onConnected() {
                System.out.println("Steady state");
            }
        });

        client.connect();
        client.join();
    }
}
```

Refs rotate every ~30 s. If the first ref expires before the user scans, the next
`onQr` callback delivers fresh refs automatically.

### Connect with Pairing Code

Phone-number pairing skips the QR entirely. The user enters an 8-char code in
**WhatsApp → Settings → Linked Devices → Link with phone number**.

> [!IMPORTANT]
> Phone number must be in E.164 form **without** `+`, spaces, or dashes.
> E.g. `628123456789`, not `+62 812-345-6789`.

```java
client.listener(new JaWaClient.Listener() {
    @Override public void onQr(List<String> qrs) {
        // Server pushed QR refs, but we want pair-code instead — pivot now.
        client.requestPairingCode("628123456789", null).whenComplete((code, err) -> {
            if (err != null) { err.printStackTrace(); return; }
            // Format as XXXX-XXXX for display (the dash is cosmetic).
            String pretty = code.substring(0, 4) + "-" + code.substring(4);
            System.out.println("Enter on phone: " + pretty);
        });
    }
    @Override public void onPaired(String jid, String pushName, String platform) {
        System.out.println("Paired as " + jid);
    }
});

client.connect();
client.join();
```

The second argument to `requestPairingCode` is an optional fixed code (8 Crockford
chars); pass `null` for a server-generated random one.

### Saving & Restoring Sessions

You don't want to re-pair every run. `FileAuthStore` persists creds to a file. On
subsequent `connect()`, JaWa loads them and skips QR/pair-code entirely.

```java
Path session = Path.of("sessions/mydev.session");
Path signal  = Path.of("sessions/mydev.signal"); // optional, see below

FileAuthStore store = new FileAuthStore(session);
JaWaClient client = new JaWaClient(store, signal);
client.connect();   // first run: fresh keys + QR/pair-code
client.connect();   // second run: loaded creds → straight to login
```

The second constructor argument is an optional directory for **persistent Signal
state**. When set, libsignal sessions and one-time pre-keys survive process
restart, so the first message from a previously-paired peer no longer triggers
`NoSessionException` → retry-receipt round trip. Pass `null` (or use the
single-arg overload) to keep Signal state in memory.

```
sessions/
├── mydev.session                                # AuthCreds: Noise + identity keys
└── mydev.signal/
    ├── sessions/<base64name>__<dev>.session     # one libsignal SessionRecord per peer device
    ├── prekeys/<id>.prekey                      # one 64-byte priv||pub per one-time pre-key
    └── sender-keys/<b64group>__<b64sender>__<dev>.senderkey  # one SenderKeyRecord per (group, sender)
```

Implement your own `AuthStore` for SQLite / encrypted keystore / cloud-backed
storage:

```java
public interface AuthStore {
    AuthCreds load() throws Exception;
    void save(AuthCreds creds) throws Exception;
}
```


## Handling Events

JaWa uses a callback `Listener` (not an event bus). One listener per client; multiple
listeners aren't supported.

### The Listener Interface

```java
public interface Listener {
    /** Server sent QR refs. Each entry is "ref,noisePub,identityPub,advSecret". */
    default void onQr(List<String> qrStrings) {}

    /** Pairing completed; creds were persisted via AuthStore. */
    default void onPaired(String jid, String pushName, String platform) {}

    /** Steady-state connection up — handshake done, login complete. */
    default void onConnected() {}

    /** A <message> stanza was successfully decrypted. */
    default void onMessage(MessageReceiver.Decoded decoded) {}

    /** Inbound stanza after handshake/pairing (everything not handled internally). */
    default void onStanza(BinaryNode node) {}

    /** Fatal client error. */
    default void onError(Throwable t) {}
}
```

> [!CAUTION]
> **All listener callbacks fire on JaWa's reader thread.** Long work in any callback
> stalls all inbound stanzas. Offload to a virtual thread:
>
> ```java
> @Override public void onMessage(MessageReceiver.Decoded d) {
>     Thread.startVirtualThread(() -> handleHeavyWork(d));
> }
> ```
> `sendIq` / `sendText` themselves are safe from any thread — they enqueue.

### Minimal Echo Bot Example

```java
import id.jawa.core.JaWaClient;
import id.jawa.message.MessageReceiver.Decoded;
import id.jawa.store.FileAuthStore;

import java.nio.file.Path;

public class EchoBot {
    public static void main(String[] args) throws Exception {
        FileAuthStore store = new FileAuthStore(Path.of("sessions/echo.session"));
        JaWaClient client = new JaWaClient(store);

        client.listener(new JaWaClient.Listener() {
            @Override public void onConnected() {
                System.out.println("Echo bot online");
            }
            @Override public void onMessage(Decoded d) {
                if (d.text() == null) return;                  // skip non-text
                Thread.startVirtualThread(() -> {
                    // Reply target: group if present, else bare DM JID.
                    String to = d.groupJid() != null
                        ? d.groupJid()
                        : stripDevice(d.senderJid());
                    String reply = "echo: " + d.text();
                    if (d.groupJid() != null) {
                        client.sendGroupText(to, reply);
                    } else {
                        client.sendText(to, reply);
                    }
                });
            }
        });

        client.connect();
        client.join();
    }

    /** "628xxx:7@s.whatsapp.net" → "628xxx@s.whatsapp.net". */
    static String stripDevice(String jid) {
        int at = jid.indexOf('@'), colon = jid.indexOf(':');
        return (colon < 0 || colon > at) ? jid
            : jid.substring(0, colon) + jid.substring(at);
    }
}
```

A more polished base lives in [`jawa-bot`](../jawa-bot) (sibling repo) with command
dispatch, config file, and ping/menu/exec commands.

## Sending Messages

All send APIs return a `CompletableFuture<String>` resolving to the outbound message
id (uppercase hex).

### Text Message (DM)

```java
// toUser must be a bare JID (no device suffix).
String msgId = client.sendText("628xxxxxxxxx@s.whatsapp.net", "hello from JaWa").join();
System.out.println("sent id=" + msgId);
```

What happens under the hood:
1. **USync** query for the recipient's device list (`client.queryDevices` is reused).
2. **Pre-key bundle fetch** for any device we don't yet have a Signal session with.
3. **`SessionBuilder`** (libsignal X3DH) installs sessions for each device.
4. **Per-device encrypt** via `SessionCipher` → one `<enc>` per device.
5. **DSM (`DeviceSentMessage`) wrap** + fan-out to your own companion devices so the
   message appears in your own chat history (M5.D.1 + M5.D.2).

### Text Message (Group)

```java
String groupJid = "120363xxxxxxxxxxxx@g.us";
String msgId = client.sendGroupText(groupJid, "halo grup").join();
```

Group send is a Sender Keys protocol:
- Plain text is encrypted **once** with a `GroupCipher` keyed by your sender-key for
  the group → single `<enc type="skmsg">`.
- Your `SenderKeyDistributionMessage` (SKDM) is wrapped in a regular `Wa.Message` and
  encrypted **per-participant-device** with `SessionCipher` (one `<enc type="pkmsg|msg">`
  each) inside `<participants>`.
- Members that don't yet have your sender-key process the SKDM to derive the key for
  subsequent `skmsg` traffic.

### Send Arbitrary `Wa.Message`

`sendText` / `sendGroupText` are thin wrappers over the lower-level
`sendDmMessage` / `sendGroupMessage` overloads that accept any `Wa.Message` protobuf
directly. The reaction / reply / edit / revoke helpers below use these internally;
they're also useful for proto types JaWa doesn't yet expose a named helper for.

```java
import id.jawa.proto.Wa;

Wa.Message custom = Wa.Message.newBuilder()
    .setExtendedTextMessage(Wa.Message.ExtendedTextMessage.newBuilder()
        .setText("**bold** isn't a thing, but extendedText carries metadata"))
    .build();

client.sendDmMessage("628xxx@s.whatsapp.net", custom).join();
client.sendGroupMessage("120363...@g.us", custom).join();
```

## Modify Messages

All four helpers route DM vs group automatically based on the chat JID suffix
(`@g.us` → group, otherwise DM) and return the new outbound message's id.

### Reaction

Attach an emoji to an existing message.

```java
String reactionId = client.sendReaction(
    "120363...@g.us",                       // chat where the target lives
    "ACD57CB93B82719FD44D1F231C89F352",     // target message id
    "224983875903488@lid",                  // target sender device JID (group only; null for DMs)
    /* fromMe = */ false,                   // true if the target was your own message
    "🔥"                                     // emoji (empty string removes)
).join();
```

### Reply / Quote

Send a text reply that quotes an existing message. The recipient's UI renders a
block-quote preview using `quotedText`.

```java
String replyId = client.sendReply(
    "120363...@g.us",
    "ok di-reaction 🔥",                    // new reply text
    "ACD57CB93B82719FD44D1F231C89F352",     // quoted message id
    "224983875903488@lid",                  // quoted sender (group); null for DM
    "test reaction"                         // preview of the quoted text
).join();
```

### Edit Message

Replace the text of a message you previously sent. Subject to WhatsApp's ~15-minute
edit window — older messages are rejected server-side.

```java
String editId = client.sendEdit(
    "120363...@g.us",
    "E1DC8330D43C02D9",                     // the original message's id
    "hello from jawa (edited)"
).join();
```

### Revoke (Delete for Everyone)

Replaces the original with WhatsApp's "This message was deleted" placeholder for
every participant.

```java
// revoke your own message
client.sendRevoke(
    "120363...@g.us",
    "E1DC8330D43C02D9",
    /* targetParticipant = */ null,
    /* fromMe = */ true
).join();

// admin revoking someone else's message in a group you administer
client.sendRevoke(
    "120363...@g.us",
    "ACD57CB93B82719FD44D1F231C89F352",
    "224983875903488@lid",
    /* fromMe = */ false
).join();
```

## Media

WhatsApp media (images, videos, audio, documents) is end-to-end encrypted with a
per-message random 32-byte `mediaKey`. JaWa handles the crypto, the
{@code media_conn} auth refresh, and the HTTPS upload; the {@code mediaKey} rides
inside the Signal-encrypted `Wa.Message` envelope so only the recipient device can
derive the AES-CBC + HMAC keys.

### Send Image

```java
byte[] jpeg = Files.readAllBytes(Path.of("photo.jpg"));
String msgId = client.sendImage(
    "120363...@g.us",            // chat (DM bare JID or group @g.us)
    jpeg,
    "image/jpeg",
    "look at this 📸"            // caption — nullable
).join();
```

### Send Video

```java
byte[] mp4 = Files.readAllBytes(Path.of("clip.mp4"));
client.sendVideo(
    "120363...@g.us",
    mp4,
    "video/mp4",
    "first JaWa video send",     // caption — nullable
    /* seconds = */ 0,           // 0 if unknown — proto field stays unset
    /* width   = */ 0,
    /* height  = */ 0
).join();
```

### Send Audio / Voice Note

```java
byte[] opus = Files.readAllBytes(Path.of("voice.ogg"));
client.sendAudio(
    "120363...@g.us",
    opus,
    "audio/ogg; codecs=opus",
    /* seconds = */ 5,
    /* ptt     = */ true         // true = voice-note bubble, false = regular audio
).join();
```

### Send Document

```java
byte[] pdf = Files.readAllBytes(Path.of("invoice.pdf"));
client.sendDocument(
    "628xxx@s.whatsapp.net",
    pdf,
    "application/pdf",
    "invoice.pdf",               // fileName — what the recipient sees as the label
    "Invoice June 2026"          // title — optional richer display
).join();
```

Under the hood:
1. Generate a fresh random 32-byte `mediaKey`.
2. `MediaCrypto.encrypt` — HKDF-expand the key into iv/cipherKey/macKey,
   AES-CBC encrypt the bytes, append a 10-byte truncated HMAC.
3. `refreshMediaConn` — `<iq xmlns="w:m" type="set"><media_conn/></iq>` to get
   the auth token + host list (cached until TTL expires).
4. `MediaUploader.upload` — HTTPS POST `<ciphertext>||<mac10>` to
   `https://<host>/mms/image/<token>?auth=...&token=...`.
5. Build `Wa.Message{imageMessage{url, directPath, mediaKey, fileSha256,
   fileEncSha256, fileLength, mimetype, caption}}`.
6. Route through `sendDmMessage` / `sendGroupMessage` — Signal-encrypted per
   recipient device, same pipeline as text.

### Low-level Media APIs

For experimentation or sending media types that don't have a named helper yet
(video / audio / document — they share the same crypto + upload, only the
`MediaType` info string and the Wa.Message field differ):

```java
import id.jawa.media.MediaCrypto;
import id.jawa.media.MediaUploader;

byte[] mediaKey = id.jawa.util.Bytes.random(32);
var enc = MediaCrypto.encrypt(rawBytes, mediaKey, MediaCrypto.MediaType.VIDEO);

var mediaConn = client.refreshMediaConn().join();
var upload = MediaUploader.upload(mediaConn, enc, MediaCrypto.MediaType.VIDEO);

// Build your own Wa.Message.VideoMessage with upload.url() + upload.directPath(),
// mediaKey, enc.fileSha256(), enc.fileEncSha256(), etc., then:
client.sendGroupMessage(groupJid, customWaMessage).join();
```

### Download Received Media

`Wa.Message.imageMessage` (and the video / audio / document siblings) carries a
`url`, `directPath`, `mediaKey`, and `fileEncSha256`. Pass those to
`downloadMedia` and JaWa handles HTTPS GET, envelope integrity check, MAC
verification, and AES-CBC decrypt:

```java
@Override public void onMessage(MessageReceiver.Decoded d) {
    if (d.message() == null || !d.message().hasImageMessage()) return;
    var img = d.message().getImageMessage();

    client.downloadMedia(
        img.getUrl(),                       // prefer this when set
        img.getDirectPath(),                // fallback via mediaConn host
        img.getMediaKey().toByteArray(),
        img.getFileEncSha256().toByteArray(),
        MediaCrypto.MediaType.IMAGE
    ).thenAccept(plaintext ->
        Files.write(Path.of("downloads/" + d.msgId() + ".jpg"), plaintext)
    );
}
```

`downloadByUrl` is preferred when `url` is set (one round-trip); `downloadByDirectPath`
fetches a fresh `mediaConn` then tries each host until one returns 200.

## Receipts

WhatsApp's lifecycle ticks (delivered, read, played) ride on `<receipt>` stanzas.
JaWa auto-acks every inbound receipt to keep the offline queue clear; consumers
that care about peer-side state hook into them via `Listener.onReceipt`.

### Send Read / Played Receipt

```java
// DM: a peer sent us a message we just rendered
client.sendReadReceipt("628xxx@s.whatsapp.net", msgId, /* senderJid = */ null);

// Group: someone in the group sent a message — senderJid is the participant device JID
client.sendReadReceipt("120363...@g.us", msgId, "224983875903488@lid");

// Voice note we just played back
client.sendPlayedReceipt(chatJid, msgId, senderJid);

// Batched — first id in the receipt attr, the rest under <list><item id=.../>...
client.sendReadReceiptBatch(chatJid, List.of(id1, id2, id3), senderJid);
```

### Observe Receipts from Peers

```java
@Override public void onReceipt(Receipt r) {
    String kind = r.type() == null ? "delivered" : r.type();   // null = delivery (one tick)
    System.out.println(kind + " for " + r.msgIds() + " in " + r.chatJid()
        + (r.senderJid() != null ? " by " + r.senderJid() : ""));
}
```

Possible `Receipt.type()` values: `null` (delivery), `"read"`, `"played"`,
`"retry"` (peer couldn't decrypt — JaWa already re-encrypts automatically),
`"server-error"`, `"sender"` (server confirming our group fan-out).

## Receiving Messages

Implement `Listener.onMessage(MessageReceiver.Decoded)`:

```java
public record Decoded(
    String senderJid,   // sender's device-specific JID (or participant for groups)
    String groupJid,    // group JID for group messages, null for DMs
    String msgId,       // <message id=> value
    String encType,     // "pkmsg" | "msg" | "skmsg"
    Wa.Message message, // decrypted, DSM-unwrapped Wa.Message protobuf
    String text         // .conversation or .extendedTextMessage.text, else null
) {}
```

JaWa handles decrypt + ack + delivery receipt automatically. On decrypt failure,
a `<receipt type="retry">` is sent (M5.E.2) so the peer re-encrypts with fresh keys.

```java
@Override public void onMessage(Decoded d) {
    if (d.text() != null) {
        System.out.println("DM/group text from " + d.senderJid() + ": " + d.text());
    } else {
        System.out.println("Non-text message from " + d.senderJid()
            + " (encType=" + d.encType() + ")");
    }
}
```

## WhatsApp IDs / JIDs Explained

JaWa exposes `id.jawa.util.Jid` for parsing.

| JID form                                | Use                                       |
|-----------------------------------------|-------------------------------------------|
| `[country][number]@s.whatsapp.net`      | Bare DM user (no device)                  |
| `[country][number]:[device]@s.whatsapp.net` | Specific device of a user             |
| `[number]-[ts]@g.us`                    | Legacy group                              |
| `120363[xx]...@g.us`                    | Modern (Community-era) group              |
| `[number]@lid` / `[number]:[device]@lid`| Linked-ID (LID, alternate identity space) |
| `status@broadcast`                      | Status / stories                          |

- `client.sendText(...)` expects a **bare** DM JID (no `:device` suffix). Strip it
  with `Jid.parse(s).bare()` or the inline helper from the echo bot above.
- `client.sendGroupText(...)` expects a `@g.us` JID.

## User & Device Queries

### Query Devices for a User

USync query — what devices does this user have?

```java
String userBare = "628xxxxxxxxx@s.whatsapp.net";
client.queryDevices(List.of(userBare)).thenAccept(map -> {
    var devices = map.getOrDefault(userBare, List.of());
    for (var d : devices) {
        System.out.println("  device id=" + d.id() + " keyIndex=" + d.keyIndex()
            + (d.hosted() ? " (hosted)" : ""));
    }
});
```

### Bootstrap Signal Sessions

Pre-warm libsignal sessions to every device of a user. Useful before a send so the
first message doesn't pay the X3DH cost.

```java
client.bootstrapSessions("628xxxxxxxxx@s.whatsapp.net").thenAccept(addresses -> {
    System.out.println("Installed " + addresses.size() + " Signal session(s)");
    addresses.forEach(System.out::println);
});
```

## Groups

### List Joined Groups

Returns groups you participate in (`<iq xmlns="w:g2"><participating/></iq>`).

```java
client.queryJoinedGroups().thenAccept(groups -> {
    for (var g : groups) {
        System.out.println(g.jid()
            + "  subject=\"" + g.subject() + "\""
            + "  participants=" + g.participantJids().size());
    }
});
```

### Create Group

```java
String newGroupJid = client.createGroup(
    "My Cool Group",
    List.of("628aaa@s.whatsapp.net", "628bbb@s.whatsapp.net")
).join();
```

Don't include your own JID in the participants — the server adds it implicitly.
Subject limit is 25 characters server-side; longer will reject with `406 not acceptable`.

### Leave Group

```java
client.leaveGroup("120363...@g.us").join();
```

### Add / Remove / Promote / Demote Participants

All four actions go through the same API; pass the right `ParticipantChange` enum.
Requires admin rights on the target group.

```java
import id.jawa.message.GroupAction.ParticipantChange;

client.updateGroupParticipants(groupJid, ParticipantChange.ADD,     List.of("628xxx@s.whatsapp.net")).join();
client.updateGroupParticipants(groupJid, ParticipantChange.REMOVE,  List.of("628yyy@s.whatsapp.net")).join();
client.updateGroupParticipants(groupJid, ParticipantChange.PROMOTE, List.of("628zzz@s.whatsapp.net")).join();
client.updateGroupParticipants(groupJid, ParticipantChange.DEMOTE,  List.of("628zzz@s.whatsapp.net")).join();
```

### Change Subject

```java
client.setGroupSubject(groupJid, "New group name").join();
```

### Set / Clear Description (Topic)

```java
// set
client.setGroupDescription(groupJid, "Welcome to the chat", /* previousId = */ null).join();

// clear
client.setGroupDescription(groupJid, null, /* previousId = */ "<current-topic-id>").join();
```

`previousId` is the current description's id (you track it from a prior `setGroupDescription`
call or fetch from group metadata). Pass `null` when there is no prior description.

## Low-level APIs

For protocol experimentation or features JaWa doesn't expose yet.

### Send Raw Stanza

```java
import id.jawa.binary.BinaryNode;

// <chatstate from="..."><composing/></chatstate>
client.send(new BinaryNode("chatstate",
    Map.of("to", "628xxx@s.whatsapp.net"),
    List.of(BinaryNode.of("composing"))));
```

> [!NOTE]
> `<presence type="available">` is **emitted automatically** after every successful
> login (with `creds.pushName` as the display name), so peers see the device as
> online and the server delivers new `<message>` stanzas. You don't need to send
> it manually.

### Send IQ with Response

`sendIqAsync` returns a future that completes when the matching `<iq type="result|error">`
arrives. IQ IDs are 16-hex-char random; JaWa correlates the response by id.

```java
BinaryNode iq = new BinaryNode("iq", Map.of(
    "id",    "1234567890abcdef",
    "type",  "get",
    "xmlns", "w:profile:picture",
    "to",    "628xxxxxxxxx@s.whatsapp.net"
), List.of(BinaryNode.of("picture", Map.of("type", "image"))));

client.sendIqAsync(iq).thenAccept(response -> {
    System.out.println("got response: " + response);
});
```

> [!WARNING]
> IQ callbacks fire on the reader thread. Don't block in them.

## Status — what works today

- [x] **M0** — Gradle skeleton, JDK 21 toolchain, full dep graph
- [x] **M1** — Binary Node codec (encode/decode, 4 JID variants, packed nibble/hex, token dictionary) — 19 unit tests
- [x] **M2** — Noise XX handshake + WebSocket transport, server CertChain validation
- [x] **M3** — ClientPayload (register + login)
- [x] **M4** — QR pairing (live-verified end-to-end: scan → ADV chain verify → creds persist → login)
- [x] **M4.5** — Phone-number pairing code (PBKDF2 + AES-CTR wrap, X25519 × 2 + HKDF advSecret derivation; live-verified)
  - [x] **M4.5.1** — `companion_hello` wire-value fix (`platform_id="1"`, canonical display, nibble-packed nonce) + steady-state hardening (w:p keepalive, per-stanza error containment)
  - [x] **M4.5.2** — Post-pair auto-reconnect to login mode (`FrameSocket` disconnect sentinel + `JaWaClient` reconnect handler; closes the 401-revoke window)
- [x] **M5** — Send + receive text 1-on-1 (live-verified end-to-end against a real account: send, decode inbound text, ack + delivery receipt)
  - [x] Pre-key upload (`<iq xmlns=encrypt>`)
  - [x] USync device list query
  - [x] Signal session bootstrap (libsignal X3DH)
  - [x] Encrypt + send `<message>`
  - [x] **M5.D.1** — `DeviceSentMessage` wrap for own-companion devices (fixes silent-drop on send-to-self)
  - [x] **M5.D.2** — Fan out outgoing message to own companion devices on non-self send (sender's own phone now sees the outgoing message in chat history)
  - [x] **M5.E** — Receive + decrypt incoming `<enc>` (`MessageReceiver` + `<ack>` + delivery `<receipt>` + active-mode IQ on login)
  - [x] **M5.E.1** — Seed `creds.signedPreKey` into `JaWaProtocolStore` (unblocks first-contact `pkmsg` decrypt)
  - [x] **M5.E.2** — Retry receipt with `<retry count>` + `<registration>` reg-id so peer re-encrypts on decrypt failure
  - [x] **M5.E.3** — Mirror generated one-time pre-keys into the libsignal `protocolStore` (was only in the raw `SignalKeyStore`)
- [x] **M6** — Receipts, retries, ack flow
  - [x] auto-ack inbound `<notification>` and `<receipt>` (core fix, prevents offline queue stall)
  - [x] `<receipt type="retry">` for inbound decrypt failures (M5.E.2)
  - [x] `sendReadReceipt` / `sendPlayedReceipt` / `sendReadReceiptBatch` public APIs
  - [x] `Listener.onReceipt(Receipt)` callback so consumers see peer-side delivery / read / played lifecycle
- [x] **M7** — Group messaging (Sender Keys distribution + skmsg)
  - [x] **M7 (recv)** — group `skmsg` decrypt + `SenderKeyDistributionMessage` processing on inbound
  - [x] **M7.G1** — query joined groups via `<iq xmlns="w:g2"><participating/></iq>`
  - [x] **M7.G2** — send text message to a group (per-device SKDM fan-out + single `<enc type=skmsg>`)
  - [x] **M7.G3** — lifecycle ops via `<iq xmlns="w:g2" type="set">`: `createGroup`, `leaveGroup`, `updateGroupParticipants` (add / remove / promote / demote), `setGroupSubject`, `setGroupDescription`
- [x] **M8** — Media upload/download (HKDF-AES-CBC + HMAC, mediaConn)
  - [x] **M8.A** — media crypto primitives (AES-CBC + HKDF expand → iv/cipherKey/macKey + truncated HMAC, plus type-isolated info strings)
  - [x] **M8.B** — `<iq xmlns="w:m"><media_conn/></iq>` query + TTL-cached `MediaConn` record
  - [x] **M8.C** — HTTPS upload to `https://<host>/mms/<type>/<token>` via JDK HttpClient
  - [x] **M8.D** — `imageMessage` proto + `sendImage(chatJid, bytes, mimetype, caption)` API (DM + group)
  - [x] **M8.E** — `videoMessage` / `audioMessage` / `documentMessage` proto builders + `sendVideo` / `sendAudio` / `sendDocument` APIs (all reuse the M8.A-D crypto + upload)
  - [x] **M8.F** — receive-side `MediaDownloader` (URL or directPath via mediaConn host fan-out, envelope SHA-256 check, MAC verify, AES-CBC decrypt) + `JaWaClient.downloadMedia` async API
- [ ] **M9** — App-state sync (LT-Hash, mutations, contact list, chat sync)
- [ ] **M10** — Reconnect, error handling, ban detection
  - [x] **M10.A** — auto-reconnect on unexpected WS close with exponential back-off (2s → 60s cap); `<failure>` stanzas (e.g. `reason=401`) flag the session terminal so a revoked device doesn't loop forever. `client.autoReconnect(false)` opts out.
- [ ] **M11** — Misc message types (reactions, edits, polls, replies, lists)
  - [x] **M11.A** — send reaction to a message (DM + group)
  - [x] **M11.B** — send quoted reply (DM + group)
  - [x] **M11.C** — edit a previously-sent message (DM + group)
  - [x] **M11.D** — revoke (delete-for-everyone) a message (DM + group)
- [x] **M12** — Pluggable storage backends (in-memory, file, SQLite)
  - [x] **M12.A** — file-backed libsignal `SessionStore` (sessions survive restart, no `NoSessionException`/retry-receipt churn for previously-paired peers)
  - [x] **M12.B** — file-backed JaWa pre-key store (one-time pre-keys survive restart, re-mirrored into libsignal on connect)
  - [x] **M12.C** — file-backed sender-key store (group sender-chain state survives restart, no SKDM re-distribution on first outbound group message after reconnect)
- [x] **core** — `<presence type="available">` on login + ack `<notification>`/`<receipt>` (without these the server treats the device as offline and stops delivering `<message>` stanzas)

## Gotchas

- **Protobuf is pinned to 3.10.0** via `resolutionStrategy`. `signal-protocol-java:2.8.1`
  ships `protobuf-javalite:3.10.0`, which is incompatible with newer protobuf-java
  schema parsing. **Do not bump protobuf** without end-to-end testing libsignal.
- **License is GPL-3.0-or-later, non-negotiable** — cascades from `signal-protocol-java`.
  Every new source file must start with `// SPDX-License-Identifier: GPL-3.0-or-later`.
- **`WA_VERSION`** — if the server starts rejecting with stream errors mentioning an
  obsolete client, bump it in `WaConstants` from the latest Baileys
  `Defaults/baileys-version.json` or whatsmeow `store.WAVersion`.
- **Reader-thread reentrancy** — `sendText`/`sendIq` are safe from any thread (they
  enqueue), but `Listener.onMessage`/`onStanza`/IQ-callbacks run on the reader. Long
  work there stalls all inbound traffic — offload to a virtual thread.
- **`creds.account == null` is the "is pairing" signal** — used by `connect()` to
  choose register vs login payload, and by `requestPairingCode` to refuse if already
  paired. Don't repurpose that field.

## Contributing

Issues / PRs / architectural feedback welcome. Run `./gradlew build` before
submitting; CI will block merges with failing tests.

If you're porting a protocol feature, **always read the upstream first**. Baileys
and whatsmeow disagree about details often enough that picking the right reference
matters. The pattern that's worked so far: implement against whatsmeow's Go (cleanest
API), cross-check Baileys for any field the Go side omits.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).

Copyleft is inherited from `signal-protocol-java`. Any project that depends on JaWa
must comply with GPL-3.0-or-later terms.
