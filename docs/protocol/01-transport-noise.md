# 01 — Transport Layer: Noise XX Handshake + WebSocket Framing

> JaWa protocol spec, derived from Baileys (TypeScript) and whatsmeow (Go).
> Audience: Java implementer building the lowest transport tier of the WhatsApp Web client.

---

## Summary

WhatsApp Web speaks a length-prefixed binary framing protocol over a single WebSocket connection to `wss://web.whatsapp.com/ws/chat`. Before any application data flows, the client and server perform a Noise Protocol Framework `Noise_XX_25519_AESGCM_SHA256` handshake (three messages: client-hello → server-hello → client-finish). After `Finish`, two independent AES-256-GCM keys (one for sending, one for receiving) are derived via HKDF on the running chaining key, and every subsequent frame is AEAD-sealed with a monotonically increasing 32-bit counter packed into the GCM nonce. The very first bytes the client writes are a 4-byte "WA header" magic (`'W','A', 6, <dict_version>`) which prefixes the unencrypted ClientHello frame and is also mixed into the Noise handshake hash via `mixHash`.

---

## Wire format

### 1. WebSocket endpoint

```
URL:    wss://web.whatsapp.com/ws/chat
Origin: https://web.whatsapp.com
Sub-protocol: (none)
Binary frames only (text frames ignored / warned about in whatsmeow)
```

If `creds.routingInfo` is present (a `Buffer` previously returned by the server), Baileys appends it as a query parameter:

```ts
// Baileys src/Socket/socket.ts (validateConnection)
url.searchParams.append('ED', authState.creds.routingInfo.toString('base64url'))
```

whatsmeow does not (currently) plumb `routingInfo` into the dial URL — see Discrepancies.

### 2. WA intro header (the very first 4 bytes after WebSocket handshake)

Baileys:

```ts
// src/Defaults/index.ts
export const DICT_VERSION = 3
export const NOISE_WA_HEADER = Buffer.from([87, 65, 6, DICT_VERSION])  // 'W','A', 6, 3
```

whatsmeow:

```go
// socket/constants.go
const WAMagicValue = 6
var WAConnHeader = []byte{'W', 'A', WAMagicValue, token.DictVersion}  // token.DictVersion == 3
```

So the first 4 bytes the server sees on the WebSocket are exactly:

```
0x57 0x41 0x06 0x03    // "WA", 6, 3
```

These four bytes are emitted **once**, immediately before the first length-prefixed frame, and are also fed into the Noise handshake hash (`mixHash(header)`).

Baileys has an additional pre-header path used when `routingInfo` is set on the credentials. Inside `makeNoiseHandler` it builds an `introHeader` of:

```
'E' 'D'  <version-hi> <version-lo>  <len-hi> <len-mid> <len-lo>  <routingInfoBytes...>  NOISE_WA_HEADER...
```

(written verbatim in `noise-handler.ts`). For a *fresh* registration with no routingInfo, the intro header is simply `NOISE_WA_HEADER` (the 4 bytes above).

### 3. Length-prefixed frame format

Every WebSocket binary message after the intro header is one or more frames of the form:

```
+--------+--------+--------+============================+
| len_hi | len_md | len_lo |  payload (len bytes)       |
+--------+--------+--------+============================+

length = (b[0] << 16) | (b[1] << 8) | b[2]   // 24-bit big-endian
```

Source:

```go
// whatsmeow socket/framesocket.go (SendFrame)
wholeFrame[headerLength]   = byte(dataLength >> 16)
wholeFrame[headerLength+1] = byte(dataLength >> 8)
wholeFrame[headerLength+2] = byte(dataLength)
```

Constants:

```go
// whatsmeow socket/constants.go
const FrameMaxSize    = 1 << 24    // 16 MiB
const FrameLengthSize = 3
```

The 4-byte WA intro header is prepended *only* to the first outgoing buffer; whatsmeow sets `fs.Header = nil` after the first write, Baileys uses an `inIntro`/first-call flag inside `encodeFrame`.

A single WebSocket binary message may contain multiple frames concatenated; the parser must loop. whatsmeow's `processData` even handles the case where a length prefix is split across two WS messages (`partialHeader` stash).

### 4. Frame contents

- **During handshake** (frames 1 and 2): the payload is a `protobuf` `HandshakeMessage` (clientHello → serverHello → clientFinish), with the `static`/`payload` AEAD ciphertexts already produced by the handshake's `Encrypt`.
- **After `Finish`**: payload is `NoiseSocket.writeKey.Seal(nonce, plaintext, aad=nil)` ciphertext. The plaintext is a binary-encoded `BinaryNode` (XMPP-like tree). Decoding that node is the job of the next protocol layer (binary XML), not this layer.

---

## Crypto

### Suite

```
Pattern: Noise_XX_25519_AESGCM_SHA256
DH:      X25519 (Curve25519)
Cipher:  AES-256-GCM
Hash:    SHA-256
```

Baileys constant:

```ts
// src/Defaults/index.ts
export const NOISE_MODE = 'Noise_XX_25519_AESGCM_SHA256\0\0\0\0'
```

whatsmeow constant:

```go
// socket/constants.go
const NoiseStartPattern = "Noise_XX_25519_AESGCM_SHA256\x00\x00\x00\x00"
```

Note the **four trailing NUL bytes** padding the string to 32 bytes — the Noise spec says "if the protocol_name length ≤ HASHLEN, use it directly as h; else SHA-256 it". Both libs special-case this: if exactly 32 bytes, copy as-is; otherwise SHA-256 it. Since the string + 4 NULs is exactly 32 bytes, `h` starts as the raw bytes `"Noise_XX_25519_AESGCM_SHA256\0\0\0\0"`.

```go
// whatsmeow socket/noisehandshake.go (Start)
if len(pattern) == 32 { copy(nh.hash, pattern) } else { nh.hash = sha256Slice(pattern) }
nh.salt = nh.hash
nh.key, err = gcmutil.Prepare(nh.hash)  // AES-256-GCM with hash as initial key
nh.Authenticate(header)                 // mixHash(WAConnHeader)
```

### HKDF

Both libs use HKDF-SHA256 to derive 64 bytes (two 32-byte halves) on every key update:

```go
// whatsmeow socket/noisehandshake.go (extractAndExpand)
h := hkdf.New(sha256.New, data, salt, nil)   // ikm=data, salt=salt, info=nil
io.ReadFull(h, write)  // first 32 bytes -> new chaining key (next salt)
io.ReadFull(h, read)   // next  32 bytes -> new AEAD key
```

Baileys equivalent (`localHKDF` in `noise-handler.ts`) calls a generic `hkdf(data, 64, { salt, info: '' })` and splits the 64 bytes.

> **Argument order trap**: in Go's `hkdf.New(hash, secret, salt, info)`, `data` is the IKM and `salt` is the HKDF salt. This matches Noise's `HKDF(ck, ikm)` where the current chaining key acts as HKDF salt. JaWa must pass `ck` as the HKDF *salt*, not as IKM.

### MixKey / counter reset

```go
// MixIntoKey: ck' || k' = HKDF(salt=ck, ikm=data)
// then counter = 0 and AEAD is re-keyed with k'
nh.counter = 0
write, read, _ := extractAndExpand(nh.salt, data)
nh.salt = write
nh.key, _  = gcmutil.Prepare(read)
```

### AEAD nonce (IV) layout — 12 bytes

```
+----+----+----+----+----+----+----+----+----+----+----+----+
|  0 |  0 |  0 |  0 |  0 |  0 |  0 |  0 | c3 | c2 | c1 | c0 |
+----+----+----+----+----+----+----+----+----+----+----+----+
                                          big-endian uint32 counter
```

```go
// whatsmeow socket/noisesocket.go
func generateIV(count uint32) []byte {
    iv := make([]byte, 12)
    binary.BigEndian.PutUint32(iv[8:], count)
    return iv
}
```

Baileys writes the counter via `iv.writeUInt32BE(counter, 8)` (per `noise-handler.ts` summary; `IV_LENGTH = 12`).

### AAD

- **During handshake** (`NoiseHandshake.Encrypt`/`Decrypt`): AAD is the **current handshake hash** `h`. After every successful encrypt/decrypt, the ciphertext is mixed into `h` via `Authenticate(ct)` (i.e. `h = SHA-256(h || ct)`).
- **After `Finish`** (`NoiseSocket.SendFrame`/`receiveEncryptedFrame`): AAD is `nil` / empty buffer. Only the counter and key change per frame.

### Counters

- During handshake: single `counter` field (atomic in whatsmeow), reset to 0 on every `MixIntoKey`. Each handshake AEAD call increments it by 1.
- After `Finish`: two independent `uint32` counters, `writeCounter` and `readCounter`, both start at 0.

### `Finish` derives transport keys

```go
// whatsmeow socket/noisehandshake.go (Finish)
write, read, err := extractAndExpand(nh.salt, nil)   // ikm = empty
writeKey, _ := gcmutil.Prepare(write)
readKey,  _ := gcmutil.Prepare(read)
return newNoiseSocket(fs, writeKey, readKey, frameHandler, disconnectHandler)
```

So the final transport keys are `HKDF(salt=ck, ikm="")` split into 32 bytes each, used as AES-256-GCM keys.

---

## Algorithm / flow

### Setup (before WebSocket open)

1. Generate a fresh ephemeral X25519 keypair `e = (e_priv, e_pub)`.
2. Have (or load) the long-term client static keypair `s = (noiseKey.private, noiseKey.public)`.
3. Initialize handshake state:
   - `h = "Noise_XX_25519_AESGCM_SHA256\0\0\0\0"` (32 bytes raw)
   - `ck = h`
   - `k = h` (AES-GCM key)
   - `counter = 0`
   - `mixHash(WAConnHeader)` — i.e. `h = SHA-256(h || [0x57,0x41,0x06,0x03])`

### WebSocket dial

```
GET /ws/chat HTTP/1.1
Host: web.whatsapp.com
Origin: https://web.whatsapp.com
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Version: 13
Sec-WebSocket-Key: <random>
```

No `Sec-WebSocket-Protocol`. User-Agent is library-default. If reconnecting with routing info, the URL becomes `wss://web.whatsapp.com/ws/chat?ED=<base64url(routingInfo)>`.

### Handshake — message 1 (client → server, plaintext)

```
[WAConnHeader 4 bytes]            // only on first send, NOT length-prefixed inside
[3-byte BE length] [payload]      // payload = proto.HandshakeMessage{ clientHello: { ephemeral: e_pub } }
```

`mixHash(e_pub)` after sending.

### Handshake — message 2 (server → client)

Server replies with one length-prefixed frame whose decrypted body is:

```proto
HandshakeMessage {
  serverHello {
    ephemeral: bytes,        // se_pub  (32 bytes)
    static:    bytes,        // AEAD-encrypted server static pubkey (32 + 16 tag)
    payload:   bytes,        // AEAD-encrypted CertChain
  }
}
```

Client processing (`processHandshake` in Baileys, equivalent code path in whatsmeow):

```
mixHash(se_pub)                                              // server ephemeral
mixSharedSecret(e_priv, se_pub)                              // ee — re-keys AEAD
serverStatic = decrypt(serverHello.static)                   // AAD=h, IV=counter=0 then 1...
mixSharedSecret(e_priv, serverStatic)                        // es — re-keys AEAD
certBytes = decrypt(serverHello.payload)                     // AAD=h
validate CertChain against WA_CERT_DETAILS  (see below)
```

`mixSharedSecret(priv, pub)` = X25519 DH → `MixIntoKey(shared)` (HKDF re-derive ck and k, counter←0).

### Certificate validation (Baileys-only — whatsmeow currently relies on transport-layer trust)

```ts
// src/Defaults/index.ts
export const WA_CERT_DETAILS = {
  SERIAL: 0,
  ISSUER: 'WhatsAppLongTerm1',
  PUBLIC_KEY: Buffer.from('142375574d0a587166aae71ebe516437c4a28b73e3695c6ce1f7f9545da8ee6b', 'hex'),
}
```

Steps Baileys performs:
1. Decode `CertChain` proto from decrypted payload.
2. Verify `chain.intermediate.details.issuerSerial == 0` else throw `"certification match failed"`.
3. Verify `chain.intermediate.details.signature` over `intermediate.details` using `WA_CERT_DETAILS.PUBLIC_KEY` (Curve25519 / Ed25519 sig).
4. Verify `chain.leaf.details.signature` over `leaf.details` using `intermediate.details.key`.
5. Confirm leaf's key matches the `serverStatic` we decrypted.

### Handshake — message 3 (client → server)

```
keyEnc     = encrypt(noiseKey.public)              // AAD=h, then mixHash(ct)
mixSharedSecret(noiseKey.private, se_pub)          // se — re-keys AEAD
payloadEnc = encrypt(ClientPayload protobuf)       // AAD=h
send proto.HandshakeMessage{ clientFinish: { static: keyEnc, payload: payloadEnc } }
```

`ClientPayload` is the registration or login proto — built outside this layer by `generateRegistrationNode` / `generateLoginNode`.

### Split — transition to transport

```
write_k, read_k = HKDF(salt=ck, ikm=ε)   // 64 bytes split
NoiseSocket {
  writeKey   = AES-256-GCM(write_k)
  readKey    = AES-256-GCM(read_k)
  writeCounter = 0
  readCounter  = 0
}
// flush any frames buffered while waiting
```

### Steady-state send

```
ct = writeKey.Seal(nonce=BE32(writeCounter)<<0 in iv[8:12], plaintext, aad=nil)
writeCounter += 1
wsSend(frame_with_3byte_length_prefix(ct))
```

### Steady-state receive

```
on each complete frame (after length parser):
  pt = readKey.Open(nonce=BE32(readCounter), ct, aad=nil)
  readCounter += 1
  deliver pt to BinaryNode decoder
```

### Pseudocode (Java-ish)

```java
// One-time setup
byte[] h = "Noise_XX_25519_AESGCM_SHA256\0\0\0\0".getBytes(US_ASCII); // 32 bytes
byte[] ck = h.clone();
byte[] k  = h.clone();
int counter = 0;
h = sha256(concat(h, WA_HEADER));  // {0x57,0x41,0x06,0x03}

// Send ClientHello
byte[] ePub = x25519PubFrom(ePriv);
h = sha256(concat(h, ePub));
ws.send(prefix3(WA_HEADER, prefix3LengthOnly(handshakeMessage(clientHello(ePub)))));

// Receive ServerHello
ServerHello sh = parseProto(decode3LengthFrame(ws.recv()));
h = sha256(concat(h, sh.ephemeral));
mixIntoKey(x25519(ePriv, sh.ephemeral));        // resets counter, re-keys k
byte[] serverStatic = aeadOpen(k, iv(counter++), sh.staticCt, h);
h = sha256(concat(h, sh.staticCt));
mixIntoKey(x25519(ePriv, serverStatic));
byte[] payload = aeadOpen(k, iv(counter++), sh.payloadCt, h);
h = sha256(concat(h, sh.payloadCt));
validateCertChain(payload, serverStatic);

// Send ClientFinish
byte[] staticCt = aeadSeal(k, iv(counter++), noiseKey.pub, h);
h = sha256(concat(h, staticCt));
mixIntoKey(x25519(noiseKey.priv, sh.ephemeral));
byte[] payloadCt = aeadSeal(k, iv(counter++), clientPayloadProto, h);
h = sha256(concat(h, payloadCt));
ws.send(prefix3(handshakeMessage(clientFinish(staticCt, payloadCt))));

// Split
byte[][] kp = hkdf(ck, EMPTY, 64).split32();
this.writeKey = aesGcm(kp[0]);
this.readKey  = aesGcm(kp[1]);
this.writeCounter = 0;
this.readCounter  = 0;
```

---

## Baileys references

- `src/Defaults/index.ts`
  - `NOISE_MODE = 'Noise_XX_25519_AESGCM_SHA256\0\0\0\0'` — Noise protocol_name (already 32 bytes).
  - `DICT_VERSION = 3` — second-half of WA header.
  - `NOISE_WA_HEADER = Buffer.from([87, 65, 6, DICT_VERSION])` — the 4-byte intro `W A 6 3`.
  - `KEY_BUNDLE_TYPE = Buffer.from([5])` — used by libsignal prekey messages, not the noise layer.
  - `WA_CERT_DETAILS = { SERIAL: 0, ISSUER: 'WhatsAppLongTerm1', PUBLIC_KEY: <hex 142375...8ee6b> }` — root pubkey to verify CertChain.
  - `version = [2, 3000, 1035194821]` — WA web client version sent in ClientPayload (one layer up).
  - `DEFAULT_ORIGIN = 'https://web.whatsapp.com'` — Origin header.
  - WS URL: `'wss://web.whatsapp.com/ws/chat'`.
- `src/Utils/noise-handler.ts`
  - `IV_LENGTH = 12`; `generateIV(counter)` writes `counter` BE at offset 8.
  - `TransportState` — post-Finish state with `encrypt`/`decrypt` using `aesEncryptGCM`/`aesDecryptGCM` and empty AAD.
  - `makeNoiseHandler({ keyPair, NOISE_HEADER, logger, routingInfo })` — builds initial `hash`, `salt`, `encKey`, `decKey` from `NOISE_MODE`; builds `introHeader` (with optional `ED` routing-info wrapper).
  - `authenticate(data)` — `hash = SHA-256(hash || data)` (mixHash).
  - `localHKDF(data)` — HKDF-SHA256 of `data` with current `salt`, 64 bytes → 2×32.
  - `mixIntoKey(data)` — `[salt, k] = localHKDF(data); counter = 0`.
  - `finishInit()` — derives transport keys from empty input, constructs `TransportState`, flushes buffered frames, logs `"Noise handler transitioned to Transport state"`.
  - `processHandshake({ serverHello }, noiseKey)` — full message-2 + message-3 logic incl. CertChain validation; throws Boom 400 errors `"invalid noise leaf certificate"`, `"invalid noise certificate signature"`, `"certification match failed"`.
  - `encodeFrame(data)` — encrypts via transport if available, prepends `introHeader` on first call, then 3-byte BE length prefix.
  - `decodeFrame(newData, onFrame)` — buffers + parses length-prefixed frames.
- `src/Socket/socket.ts`
  - `validateConnection()` — orchestrates the handshake: `Curve.generateKeyPair()` → `makeNoiseHandler(...)` → builds clientHello → awaits server → `noise.processHandshake(handshake, creds.noiseKey)` → builds & encrypts `ClientPayload` (login or registration) → sends `clientFinish` → `noise.finishInit()` → `startKeepAliveRequest()`.
  - Adds `ED` query param: `url.searchParams.append('ED', authState.creds.routingInfo.toString('base64url'))`.
- `src/Socket/Client/websocket.ts`
  - Uses the `ws` npm library. `WebSocket(url, { origin: DEFAULT_ORIGIN, headers: this.config.options?.headers, handshakeTimeout, timeout, agent })`.

---

## whatsmeow references

- `socket/constants.go`
  - `Origin = "https://web.whatsapp.com"`
  - `URL    = "wss://web.whatsapp.com/ws/chat"`
  - `NoiseStartPattern = "Noise_XX_25519_AESGCM_SHA256\x00\x00\x00\x00"`
  - `WAMagicValue = 6`
  - `WAConnHeader = []byte{'W', 'A', WAMagicValue, token.DictVersion}` where `token.DictVersion = 3`.
  - `FrameMaxSize    = 1 << 24`
  - `FrameLengthSize = 3`
- `socket/framesocket.go`
  - `FrameSocket` struct holds parser state: `incomingLength`, `receivedLength`, `incoming`, `partialHeader`.
  - `NewFrameSocket` initializes with `Header = WAConnHeader`, `URL = URL`, `Origin = Origin`, unbuffered `Frames chan []byte`.
  - `Connect(ctx)` — `websocket.Dial`, sets `conn.SetReadLimit(FrameMaxSize)`, launches `readPump`.
  - `SendFrame(data)` — rejects if `len(data) >= FrameMaxSize`; writes `fs.Header` once, then 3-byte BE length, then payload as `MessageBinary`.
  - `processData` — parses length-prefixed frames, handles partial header (`Received partial header (report if this happens often)` warning), dispatches via `frameComplete → fs.Frames <- incoming`.
- `socket/noisehandshake.go`
  - `NoiseHandshake { hash, salt []byte; key cipher.AEAD; counter uint32 }`.
  - `Start(pattern, header)` — `hash = pattern if len==32 else SHA256(pattern); salt = hash; key = GCM(hash); Authenticate(header)`.
  - `Authenticate(data)` — `hash = SHA-256(hash || data)` (mixHash).
  - `postIncrementCounter()` — `atomic.AddUint32(&nh.counter, 1) - 1`.
  - `Encrypt(pt)` — `ct = key.Seal(nil, generateIV(post++), pt, hash); Authenticate(ct); return ct`.
  - `Decrypt(ct)` — `pt = key.Open(nil, generateIV(post++), ct, hash); Authenticate(ct); return pt`.
  - `MixSharedSecretIntoKey(priv, pub [32]byte)` — `MixIntoKey(curve25519.X25519(priv, pub))`.
  - `MixIntoKey(data)` — `counter = 0; salt, k = extractAndExpand(salt, data); key = GCM(k)`.
  - `extractAndExpand(salt, data) → (write, read, err)` — `hkdf.New(sha256.New, ikm=data, salt=salt, info=nil)`, reads 32 + 32 bytes.
  - `Finish(ctx, fs, frameHandler, disconnectHandler)` — `extractAndExpand(salt, nil)`, build write/read GCM, return `newNoiseSocket(...)`.
- `socket/noisesocket.go`
  - `generateIV(count uint32) []byte` — 12-byte buffer with BE32 counter at offset 8.
  - `NoiseSocket { fs, onFrame, writeKey, readKey cipher.AEAD; writeCounter, readCounter uint32; writeLock sync.Mutex; destroyed atomic.Bool; stopConsumer chan struct{} }`.
  - `newNoiseSocket` — installs disconnect handler, starts `consumeFrames` goroutine.
  - `SendFrame(ctx, plaintext)` — locks, encrypts with `writeKey.Seal(nil, generateIV(writeCounter), plaintext, nil)`, increments counter, calls `fs.SendFrame`.
  - `receiveEncryptedFrame` — `readKey.Open(ciphertext[:0], generateIV(readCounter), ciphertext, nil)`, increments counter, invokes `onFrame(ctx, pt)`. On error logs `"Failed to decrypt frame: %v"`.
- `socket/dialopts.go`
  - `makeDialOptions` returns `&websocket.DialOptions{ HTTPClient, HTTPHeader }`. Uses `github.com/coder/websocket`.

---

## Discrepancies

| Topic | Baileys | whatsmeow | Resolution for JaWa |
|---|---|---|---|
| AAD on steady-state frames | Empty buffer (`EMPTY_BUFFER`) passed to AES-GCM | `nil` (Go) — semantically empty | Use empty byte array. They are equivalent on the wire. |
| Handshake AAD | Current `hash` | Current `hash` | Match — use current `hash`. |
| Counter type | JS number (treated as uint32) | `uint32` (atomic during handshake, plain in transport) | Use `int` widened to uint32, wrap-safe; in practice never overflows. |
| `mixHash(ct)` after Encrypt/Decrypt | Done explicitly inside `encrypt`/`decrypt` during handshake | Done inside `Encrypt`/`Decrypt` via `Authenticate(ct)` | Match — mix ciphertext into hash after each handshake AEAD op. |
| `routingInfo` (`ED` query) | Appended as `?ED=<base64url>` to WS URL when reconnecting; also wraps the intro header with an `ED`-prefixed extra block when present | Not handled in `socket` package | **Follow Baileys.** Without it, sticky-routing to the same WA front-end breaks for reconnects. JaWa must persist `routingInfo` from the server response and replay it on next connect. |
| Cert-chain validation | Explicit verification against `WA_CERT_DETAILS.PUBLIC_KEY` and serial==0 | Not present in the open-source socket layer | **Follow Baileys.** Otherwise a MITM with a forged static key cannot be detected by the Noise XX pattern alone. Use the same `WA_CERT_DETAILS` constants. |
| Origin & URL | `DEFAULT_ORIGIN` + `wss://.../ws/chat` | `Origin` + `URL` | Identical. |
| WA intro header | `[87,65,6, DICT_VERSION=3]` | `['W','A', WAMagicValue=6, token.DictVersion=3]` | Identical bytes `0x57 0x41 0x06 0x03`. |
| Noise protocol_name | `"Noise_XX_25519_AESGCM_SHA256\0\0\0\0"` | `"Noise_XX_25519_AESGCM_SHA256\x00\x00\x00\x00"` | Identical 32 raw bytes. |
| HKDF info | Empty string `''` | `nil` | Identical (no info). |
| Frame max size | (not explicit in noise-handler) | `1<<24` | Use `1<<24` (16 MiB). |
| First-write header | Prepended once to first encoded frame inside `encodeFrame` (then `inIntro=false`) | Prepended once in `FrameSocket.SendFrame`, then `fs.Header = nil` | Same outcome; pick one location. Recommend keeping it at the frame layer (whatsmeow-style) for separation of concerns. |
| Counter atomicity | None (single-threaded JS) | Atomic during handshake; lock-protected in transport (write side) | Java will need a lock or atomic on writes since multiple coroutines may post frames. |

**Where they disagree, JaWa follows Baileys for routing info and cert validation, and follows whatsmeow's cleaner two-layer split (`FrameSocket` ⊕ `NoiseSocket`) for code structure.**

---

## Java implementation notes

### Dependencies (Gradle `build.gradle.kts`)

```kotlin
dependencies {
    // WebSocket client. JDK 21's java.net.http.WebSocket works but doesn't expose
    // raw binary framing as cleanly. Prefer nv-websocket-client or Java-WebSocket
    // for explicit binary frame APIs:
    implementation("com.neovisionaries:nv-websocket-client:2.14")
    // (Alternative: implementation("org.java-websocket:Java-WebSocket:1.5.7"))

    // Crypto: BouncyCastle (X25519, HKDF, AES-GCM).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // libsignal-java for the Signal layer (next component). It also ships X25519
    // and HKDF — JaWa can reuse `org.signal.libsignal.protocol.ecc.Curve` and
    // `org.signal.libsignal.protocol.kdf.HKDF` instead of BC for consistency:
    implementation("org.signal:libsignal-client:0.55.0")

    // Protobuf for HandshakeMessage / ClientPayload / CertChain
    implementation("com.google.protobuf:protobuf-java:4.27.2")

    // SLF4J for logging
    implementation("org.slf4j:slf4j-api:2.0.13")
}
```

### Package layout

```
id.jawa.transport
    ├── WaTransport.java              // public facade: connect(), send(BinaryNode), Flow<BinaryNode>
    ├── FrameSocket.java              // WS + length-prefixed framing (no crypto)
    ├── NoiseHandshake.java           // h, ck, k, counter; mixHash/mixKey/encrypt/decrypt
    ├── NoiseSocket.java              // post-Finish AEAD wrapper around FrameSocket
    ├── WaCertValidator.java          // WA_CERT_DETAILS check against CertChain proto
    └── Constants.java                // URL, ORIGIN, WA_HEADER, NOISE_MODE, FRAME_MAX_SIZE
```

### Class skeleton

```java
public final class Constants {
    public static final URI WS_URL = URI.create("wss://web.whatsapp.com/ws/chat");
    public static final String ORIGIN = "https://web.whatsapp.com";
    public static final byte[] WA_HEADER = { 'W', 'A', 6, 3 };  // {0x57,0x41,0x06,0x03}
    public static final byte[] NOISE_MODE =
        "Noise_XX_25519_AESGCM_SHA256\0\0\0\0".getBytes(StandardCharsets.US_ASCII);
    public static final int    FRAME_MAX_SIZE = 1 << 24;
    public static final int    FRAME_LEN_BYTES = 3;
    public static final byte[] WA_CERT_PUBKEY = Hex.decode(
        "142375574d0a587166aae71ebe516437c4a28b73e3695c6ce1f7f9545da8ee6b");
}

public final class NoiseHandshake {
    private byte[] h, ck;
    private SecretKey k;
    private int counter;

    public void start(byte[] pattern, byte[] waHeader) { /* init h, ck, k */ }
    public void mixHash(byte[] data)         { h = sha256(concat(h, data)); }
    public void mixKey(byte[] ikm)           { /* HKDF, reset counter, re-key */ }
    public void mixSharedSecret(byte[] priv, byte[] pub) { mixKey(x25519(priv, pub)); }
    public byte[] encrypt(byte[] plaintext)  { /* AES-GCM with iv(counter++), AAD=h, then mixHash(ct) */ }
    public byte[] decrypt(byte[] ciphertext) { /* inverse, then mixHash(ct) */ }
    public NoiseSocket finish(FrameSocket fs) { /* HKDF(ck, ε) → write/read keys */ }
}

public final class NoiseSocket implements AutoCloseable {
    private final FrameSocket fs;
    private final SecretKey writeKey, readKey;
    private int writeCounter, readCounter;
    private final Object writeLock = new Object();

    public void sendFrame(byte[] plaintext) {
        synchronized (writeLock) {
            byte[] ct = aesGcmSeal(writeKey, iv(writeCounter), plaintext, EMPTY);
            writeCounter++;
            fs.sendFrame(ct);
        }
    }

    void onCipherFrame(byte[] ct) {
        byte[] pt = aesGcmOpen(readKey, iv(readCounter), ct, EMPTY);
        readCounter++;
        deliver(pt);
    }

    private static byte[] iv(int counter) {
        ByteBuffer bb = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
        bb.position(8).putInt(counter);
        return bb.array();
    }
}
```

### API sketch

`WaTransport.connect(noiseKeyPair, routingInfo?)` opens the WebSocket, runs the XX handshake (client-hello / server-hello / client-finish), validates the CertChain, and returns a connected transport whose `send(byte[] binaryNodeBytes)` and `frames(): Flow<byte[]>` (or a callback) hand off plaintext binary-node payloads to the next layer (XML/BinaryNode codec). All counter management, AAD selection, and HKDF re-derivation happen inside `NoiseHandshake` and `NoiseSocket`; consumers never see the wire bytes.

### Crypto picks (BouncyCastle specifics)

- **X25519**: `org.bouncycastle.crypto.agreement.X25519Agreement` + `X25519PrivateKeyParameters`/`X25519PublicKeyParameters`.
- **AES-256-GCM**: `Cipher.getInstance("AES/GCM/NoPadding", "BC")` with `GCMParameterSpec(128, iv)` (16-byte tag).
- **HKDF-SHA256**: `HKDFBytesGenerator(new SHA256Digest()).init(new HKDFParameters(ikm, salt, info))`, then `generateBytes(out, 0, 64)` and split.
- **SHA-256**: `MessageDigest.getInstance("SHA-256")` (JDK built-in, no BC needed).

> Tip: pre-create the `Cipher` instance only when re-keying (i.e. on `mixKey` and on `finish`), and reuse it across encrypt/decrypt calls within the same key generation by re-initing with each fresh `GCMParameterSpec(128, iv)`.

---

## Open questions

1. **Does the server still accept connections without `Sec-WebSocket-Protocol` set?** Both libs omit it; assume yes, but worth confirming with a live capture before launch.
2. **Exact wire encoding of `ClientPayload`** (registration vs login) is built in upper layers (`generateRegistrationNode`/`generateLoginNode`). That belongs in a separate spec; this layer just needs the encrypted payload bytes.
3. **CertChain proto definition** — we know Baileys uses `proto.NoiseCertificate.Details` + signature, but the exact `.proto` (and whether it's `NoiseCertificate` or `CertChain`) should be cross-checked against `WAProto`. May affect `Open questions` of the protobuf-layer spec.
4. **Whether `routingInfo` is rotated on every reconnect**, and whether it must be Base64URL without padding (`base64url` in Baileys `Buffer.toString` does strip padding by default — verify).
5. **`User-Agent` requirement** — Baileys uses the `ws` lib default, whatsmeow passes none. WhatsApp may or may not gate behavior on the UA string. Should we mimic Chrome on macOS UA (consistent with `Browsers.macOS('Chrome')` in `DEFAULT_CONNECTION_CONFIG`)? Likely no, since the *Browser* field is sent in `ClientPayload` not the HTTP header, but worth confirming.
6. **Counter overflow** — 32-bit counter on a single noise key. WA almost certainly closes the socket long before 2^32 frames. Worth adding a defensive abort at, say, 2^31 to be safe.
7. **TLS pinning** — both libs rely on system trust for `web.whatsapp.com`. Should JaWa pin the WhatsApp certificate? Out of scope for this layer but flag for security review.
8. **Frame fragmentation across WS messages** — whatsmeow handles partial length prefix; is it ever actually triggered in practice, or is it defensive? JaWa should implement it to be safe.
