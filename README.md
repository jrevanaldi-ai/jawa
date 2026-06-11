# JaWa — Java WhatsApp Web library

[![build](https://github.com/jrevanaldi-ai/jawa/actions/workflows/build.yml/badge.svg)](https://github.com/jrevanaldi-ai/jawa/actions/workflows/build.yml)
[![license](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](LICENSE)
[![java](https://img.shields.io/badge/java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![JitPack](https://img.shields.io/badge/JitPack-v0.0.1-brightgreen)](https://jitpack.io/#jrevanaldi-ai/jawa)

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
    implementation("com.github.jrevanaldi-ai:jawa:v0.0.1")
}
```

**Maven:**

```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>

<dependency>
    <groupId>com.github.jrevanaldi-ai</groupId>
    <artifactId>jawa</artifactId>
    <version>v0.0.1</version>
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
- [Receiving Messages](#receiving-messages)
- [WhatsApp IDs / JIDs Explained](#whatsapp-ids--jids-explained)
- [User & Device Queries](#user--device-queries)
    - [Query Devices for a User](#query-devices-for-a-user)
    - [Bootstrap Signal Sessions](#bootstrap-signal-sessions)
- [Groups](#groups)
    - [List Joined Groups](#list-joined-groups)
- [Low-level APIs](#low-level-apis)
    - [Send Raw Stanza](#send-raw-stanza)
    - [Send IQ with Response](#send-iq-with-response)
- [Status — what works today](#status--what-works-today)
- [Gotchas](#gotchas)
- [Docs](#docs)
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
FileAuthStore store = new FileAuthStore(Path.of("sessions/mydev.session"));
JaWaClient client = new JaWaClient(store);
client.connect();   // first run: fresh keys + QR/pair-code
client.connect();   // second run: loaded creds → straight to login
```

Implement your own `AuthStore` for SQLite / encrypted keystore / cloud-backed
storage:

```java
public interface AuthStore {
    AuthCreds load() throws Exception;
    void save(AuthCreds creds) throws Exception;
}
```

> [!WARNING]
> The Signal Protocol stores (session, pre-key, sender-key state) are currently
> **in-memory only** (`InMemorySignalKeyStore`, `InMemorySenderKeyStore`). They are
> rebuilt on every reconnect. Persistent Signal storage is tracked under **M12**.

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

> [!NOTE]
> Group create / add-remove / promote-demote / subject change are **not yet
> implemented**. Group send + receive + list works; lifecycle ops land in a future
> milestone.

## Low-level APIs

For protocol experimentation or features JaWa doesn't expose yet.

### Send Raw Stanza

```java
import id.jawa.binary.BinaryNode;

// <presence type="available" name="MyBot"/>
client.send(BinaryNode.of("presence", Map.of(
    "type", "available",
    "name", "MyBot"
)));
```

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
- [ ] **M6** — Receipts, retries, ack flow
- [ ] **M7** — Group messaging (Sender Keys distribution + skmsg)
  - [x] **M7 (recv)** — group `skmsg` decrypt + `SenderKeyDistributionMessage` processing on inbound
  - [x] **M7.G1** — query joined groups via `<iq xmlns="w:g2"><participating/></iq>`
  - [x] **M7.G2** — send text message to a group (per-device SKDM fan-out + single `<enc type=skmsg>`)
- [ ] **M8** — Media upload/download (HKDF-AES-CBC + HMAC, mediaConn)
- [ ] **M9** — App-state sync (LT-Hash, mutations, contact list, chat sync)
- [ ] **M10** — Reconnect, error handling, ban detection
- [ ] **M11** — Misc message types (reactions, edits, polls, replies, lists)
- [ ] **M12** — Pluggable storage backends (in-memory, file, SQLite)

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

## Docs

- [`docs/protocol/01-transport-noise.md`](docs/protocol/01-transport-noise.md) — Noise handshake + WebSocket spec
- [`docs/protocol/02-binary-node.md`](docs/protocol/02-binary-node.md) — WA binary encoding spec
- More specs land as features ship.

For protocol details not yet formalised here, the source of truth is the upstream
code in `references/`:

- [WhiskeySockets/Baileys](https://github.com/WhiskeySockets/Baileys) (TypeScript)
- [tulir/whatsmeow](https://github.com/tulir/whatsmeow) (Go)

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
