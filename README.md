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

Runs the full test suite (29 tests across binary codec and crypto primitives).

## Try it — pair with WhatsApp

```
./gradlew run -PsessionFile=sessions/myphone.session --console=plain
```

On the first run (no session file yet), JaWa:

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
- [x] **M4** — QR pairing (live-verified: server accepts the register payload and returns pair-device refs)
- [ ] **M5** — Send + receive text 1-on-1 (Signal session bootstrap, USync device fanout, `<enc type=pkmsg/msg>`)
- [ ] **M6** — Receipts, retries, ack flow
- [ ] **M7** — Group messaging (Sender Keys distribution + skmsg)
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
