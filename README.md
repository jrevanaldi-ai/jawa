# JaWa — Java WhatsApp Web library

[![build](https://github.com/jrevanaldi-ai/jawa/actions/workflows/build.yml/badge.svg)](https://github.com/jrevanaldi-ai/jawa/actions/workflows/build.yml)
[![license](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](LICENSE)
[![java](https://img.shields.io/badge/java-21-orange)](https://openjdk.org/projects/jdk/21/)

Unofficial WhatsApp Web library for Java 21+, ported from [Baileys](https://github.com/WhiskeySockets/Baileys) (TypeScript) and [whatsmeow](https://github.com/tulir/whatsmeow) (Go).

**Status: pre-alpha.** Multi-month build in progress. Do not use against your primary WhatsApp account.

## License

GPL-3.0-or-later. Inherits copyleft from libsignal-java (Signal Protocol).

## Stack

- JDK 21 (records, sealed classes, virtual threads, pattern matching)
- Gradle (Kotlin DSL)
- BouncyCastle (Curve25519 ECDH, AES-GCM, HKDF-SHA256, HMAC)
- signal-protocol-java (XEdDSA sign/verify, future X3DH / Double Ratchet / Sender Keys)
- protobuf-java
- nv-websocket-client
- ZXing (terminal-rendered pairing QR)

## Module layout

```
id.jawa.binary    — WhatsApp binary node encoder/decoder
id.jawa.noise     — Noise_XX_25519_AESGCM_SHA256 handshake + framed AEAD transport
id.jawa.signal    — Signal Protocol integration (pre-keys, sessions, sender keys) — TODO
id.jawa.pair      — Multi-device pairing (QR + phone-number code)
id.jawa.message   — Message stanza send/receive, receipts, retries — TODO
id.jawa.media     — Media upload/download (HKDF-AES-CBC + HMAC) — TODO
id.jawa.appstate  — App-state sync (LT-Hash, mutations) — TODO
id.jawa.store     — Pluggable session/key persistence
id.jawa.proto     — Generated protobuf classes
id.jawa.event     — Event listener API — TODO
id.jawa.core      — Client facade + public API
id.jawa.util      — JID, base64url, hex, crypto helpers
```

## Build

Requires JDK 21+. The Gradle wrapper installs the right Gradle version automatically.

```
./gradlew build
```

Runs the full test suite (53 tests across binary codec, crypto primitives, pair-code helpers, USync, pre-key manager, and message-send shape).

## Use as a library (JitPack)

JaWa is published via [JitPack](https://jitpack.io) — no manual Maven Central step yet.
Each non-SNAPSHOT `version` bump on `main` auto-creates a matching `v<version>` git tag
and GitHub Release (see `.github/workflows/release.yml`), which JitPack resolves on demand.

Gradle (Kotlin DSL):

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.jrevanaldi-ai:jawa:<tag>")  // e.g. v0.1.0
}
```

Maven:

```xml
<repositories>
  <repository><id>jitpack.io</id><url>https://jitpack.io</url></repository>
</repositories>
<dependency>
  <groupId>com.github.jrevanaldi-ai</groupId>
  <artifactId>jawa</artifactId>
  <version><!-- tag, e.g. v0.1.0 --></version>
</dependency>
```

`logback-classic` is `runtimeOnly` — supply your own SLF4J binding.

## Try it — pair with WhatsApp

Two flows are supported on first run:

### Option A — phone-number pairing code (recommended)

```
./gradlew run -PsessionFile=sessions/myphone.session -Djawa.phone=628xxxxxxxxx --console=plain
```

JaWa prints an 8-character Crockford code formatted as `XXXX-XXXX`. On your phone:
**WhatsApp → Settings → Linked Devices → Link with phone number** → enter the code.
JaWa receives the server-forwarded primary device key, derives the ADV shared secret,
finalises the pair, and persists the session file.

### Option B — QR code

```
./gradlew run -PsessionFile=sessions/myphone.session --console=plain
```

Omit `-Djawa.phone` to fall back to the QR flow. JaWa:

1. Generates fresh Noise + identity keypairs and a registration id.
2. Connects to `wss://web.whatsapp.com/ws/chat`.
3. Runs the Noise XX handshake and validates the server CertChain.
4. Sends a register `ClientPayload` and waits for the server's `<pair-device>` reply.
5. **Renders the QR code directly in the terminal** (Unicode half-block + ANSI colour) — no external `qrencode` step needed.

```
== JaWa 0.0.1-SNAPSHOT ==
15:01:12 INFO  No creds found — generated fresh pair: regId=22919
15:01:13 DEBUG WS connected to wss://web.whatsapp.com/ws/chat
15:01:13 INFO  Noise handshake complete — steady state (pairing)
15:01:14 INFO  Got 6 QR refs

>>> Open WhatsApp → Settings → Linked Devices → Link a Device, then scan:

 █▀▀▀▀▀█ ▄█ ▀ █▀█▀ ▀██▀█▀█ ▀▀▄ ▄▄▀▀    ▄▀██▀▄▀▀▀█  █▀▀▀▀▀█
 █ ███ █ ▀▀▄▄▀█▄▀▀▀▄█  ▀ ▀  ▄█ ▀▀██▀▄▄▀ █▀ ▀ ▄▄▀█  █ ███ █
 █ ▀▀▀ █ ██▀▄ ▀▄  ▀ ▄  ▀▄▀██▀▀▀█▀███  ▀▀█▀▀█▀█▀▄▀  █ ▀▀▀ █
 ... (truncated)

>>> ref 1/6 — refs rotate every ~30 s; scan within window
```

Point your WhatsApp app at the QR (Settings → Linked Devices → Link a Device). JaWa verifies the ADV identity chain, writes `sessions/myphone.session`, and stays connected. Subsequent runs reuse the session file and skip the QR.

> Refs rotate every ~30 s. If the first ref expires before you scan, kill (Ctrl-C) and re-run for a fresh batch.
>
> If your terminal can't render the ANSI half-block QR (rare — most modern terminals do), grab the raw ref string from the listener output and feed it to any QR generator (e.g. `qrencode`).

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

## Docs

- [`docs/protocol/01-transport-noise.md`](docs/protocol/01-transport-noise.md) — Noise handshake + WebSocket spec
- [`docs/protocol/02-binary-node.md`](docs/protocol/02-binary-node.md) — WA binary encoding spec
- More specs added as features land.

## Contributing

Issues / PRs / architectural feedback welcome. Run `./gradlew build` before submitting; CI will block merges with failing tests.

For the protocol details that aren't documented here yet, the source of truth is the upstream code we ported from — both repos make excellent reading:

- [WhiskeySockets/Baileys](https://github.com/WhiskeySockets/Baileys) (TypeScript)
- [tulir/whatsmeow](https://github.com/tulir/whatsmeow) (Go)

## Disclaimer

JaWa talks to WhatsApp's web protocol over a reverse-engineered handshake. **Using it carries account-suspension risk** — WhatsApp explicitly forbids unofficial clients in its ToS. Use a dedicated test number, never your primary account.
