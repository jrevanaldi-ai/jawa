# JaWa — Java WhatsApp Web library

Unofficial WhatsApp Web library for Java 21+, ported from [Baileys](https://github.com/WhiskeySockets/Baileys) (TypeScript) and [whatsmeow](https://github.com/tulir/whatsmeow) (Go).

**Status: pre-alpha.** Multi-month build in progress. Do not use against your primary WhatsApp account.

## License

GPL-3.0-or-later. Inherits copyleft from libsignal-java (Signal Protocol).

## Stack

- JDK 21 (records, sealed classes, virtual threads, pattern matching)
- Gradle (Kotlin DSL)
- BouncyCastle (Curve25519, AES-GCM, HKDF, SHA256, HMAC)
- signal-protocol-java (X3DH, Double Ratchet, Sender Keys)
- protobuf-java
- nv-websocket-client

## Module layout

```
id.jawa.binary    — WhatsApp binary node encoder/decoder
id.jawa.noise     — Noise_XX_25519_AESGCM_SHA256 handshake + framed AEAD transport
id.jawa.signal    — Signal Protocol integration (pre-keys, sessions, sender keys)
id.jawa.pair      — Multi-device pairing (QR + phone-number code)
id.jawa.message   — Message stanza send/receive, receipts, retries
id.jawa.media     — Media upload/download (HKDF-AES-CBC + HMAC)
id.jawa.appstate  — App-state sync (LT-Hash, mutations)
id.jawa.store     — Pluggable session/key persistence
id.jawa.proto     — Generated protobuf classes
id.jawa.event     — Event listener API
id.jawa.core      — Client facade + public API
id.jawa.util      — JID, base64url, hex, etc.
```

## Build

```
./gradlew build
```

## Try it — pair with WhatsApp

```
./gradlew run -PsessionFile=sessions/myphone.session
```

Output (first run, no creds):

```
== JaWa 0.0.1-SNAPSHOT ==
14:45:57 INFO  No creds found — generated fresh pair: regId=9109
14:45:58 DEBUG WS connected to wss://web.whatsapp.com/ws/chat
14:45:59 INFO  Noise handshake complete — steady state (pairing)
14:45:59 INFO  Got 6 QR refs
>>> Scan one of these QR refs with WhatsApp (Settings → Linked Devices):
    [1/6] 2@CLk0...,SRoeu6...,yBncAv...,e6Lv8p...
    [2/6] 2@azCc...,SRoeu6...,yBncAv...,e6Lv8p...
    ...
```

1. Take the **first** QR string (the part before `(refs rotate every ~30s)`).
2. Paste into any QR generator (e.g., `qrencode "<string>" -o qr.png`).
3. On your phone: **WhatsApp → Settings → Linked Devices → Link a Device** → scan.
4. JaWa verifies the ADV identity chain, writes `sessions/myphone.session`, and stays connected.
5. Next runs reuse the session file (no QR).

> Refs rotate every ~30 s; if the first ref is stale by the time you scan, try the next.

## Status — what works today

- [x] M0 — Gradle skeleton, JDK 21 toolchain
- [x] M1 — Binary Node codec (encode/decode, 4 JID variants, packed nibble/hex)
- [x] M2 — Noise XX handshake + WebSocket transport, CertChain validation
- [x] M3 — ClientPayload (register + login)
- [x] M4 — QR pairing (verified live: server accepts our register and returns QR refs)
- [ ] Messaging (M5+) — send/receive, Signal sessions, USync, receipts
- [ ] Groups, media, app-state sync, etc.

## Docs

- `docs/protocol/01-transport-noise.md` — Noise handshake + WebSocket spec
- `docs/protocol/02-binary-node.md` — WA binary encoding spec
- More specs added as features land.

## References

Cloned alongside but not part of the build: `references/baileys` and `references/whatsmeow`. Source-of-truth for protocol details.
