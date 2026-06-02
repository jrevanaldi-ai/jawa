# WhatsApp Binary Node Encoding/Decoding

> **Scope.** WhatsApp's wire format for XML-like *stanzas* (`Node{Tag, Attrs, Content}`) after Noise decryption. Encoded payloads are what travels inside the framed Noise transport; this layer sits between the Noise framing (`docs/protocol/01-noise.md`) and any inner application-layer payloads (Signal session messages, app-state syncd patches, etc.).

## Summary

WhatsApp serializes its XMPP-like stanzas with a custom binary format that aggressively dictionary-compresses the most common tag/attr strings into 1- or 2-byte token references and packs digit/hex strings into nibble form. A `Node` is encoded as `<listStart(2*attrCount + 1 + hasContent)> <tagString> [<keyString> <valString>]* [<content>]?`. The full payload is prefixed with a single byte: `0x00` (raw) or `0x02` (zlib-compressed payload follows). Both Baileys (TypeScript) and whatsmeow (Go) implement the exact same tag table and algorithm; we mirror that algorithm verbatim in JaWa with `byte[]` buffers and a `BinaryNode` record.

## Wire format

### Top-level frame (one stanza per Noise plaintext)

The first byte of the *decrypted* Noise payload is a compression flag:

```
+--------+---------------------------+
|  0x00  |   raw node bytes          |   // no compression
+--------+---------------------------+
+--------+---------------------------+
|  0x02  |   zlib-compressed bytes   |   // inflate first
+--------+---------------------------+
```

Baileys `decompressingIfRequired` (decode.ts:9-18):

```ts
if (2 & buffer.readUInt8()) {
    buffer = await inflatePromise(buffer.slice(1))
} else {
    buffer = buffer.slice(1)
}
```

The encoder always seeds the buffer with `0x00` and never compresses on the outgoing side:
- Baileys: `encodeBinaryNode(..., buffer: number[] = [0])` (encode.ts:8)
- whatsmeow: `newEncoder() *binaryEncoder { return &binaryEncoder{[]byte{0}} }` (encoder.go:16-18)

So **outgoing stanzas are always sent uncompressed** (`0x00` prefix); compression is *only* observed on incoming. JaWa should follow suit.

### Tag bytes

Verbatim from whatsmeow `binary/token/token.go:71-94` and Baileys `WABinary/constants.ts:1-19`:

```
ListEmpty    = 0     // empty list / "absent string" marker
                     // + Baileys also exposes STREAM_END = 2 in older code; not present
                     //   in current constants.ts. See "Discrepancies".
Dictionary0  = 236
Dictionary1  = 237
Dictionary2  = 238
Dictionary3  = 239
InteropJID   = 245   // user:device@interop, with 16-bit integrator
FBJID        = 246   // user:device@msgr (Messenger interop)
ADJID        = 247   // agent.device@s.whatsapp.net (or lid/hosted variants)
List8        = 248   // list with uint8 length follows
List16       = 249   // list with uint16 big-endian length follows
JIDPair      = 250   // user@server (no device)
Hex8         = 251   // hex-packed string, len in next byte (high bit = odd-length flag)
Binary8      = 252   // utf8/raw bytes, uint8 length
Binary20     = 253   // utf8/raw bytes, 20-bit big-endian length (3 bytes, top nibble masked)
Binary32     = 254   // utf8/raw bytes, int32 big-endian length
Nibble8      = 255   // nibble-packed string (digits + '-' + '.')

PackedMax    = 127   // max length of a packed (hex/nibble) string
SingleByteMax = 256
```

Tag bytes `1..235` (i.e. up to but not including `Dictionary0=236`) are *single-byte token indices* into `SingleByteTokens`. Index `0` is reserved as `ListEmpty`/empty-string sentinel and the dictionary entry at index 0 is `""`.

### Single-byte token dictionary

A flat array of 235 strings (the table starts with `""` at index 0). Examples at well-known indices:

| idx | token |
| ---:| --- |
| 0   | `""` |
| 1   | `"xmlstreamstart"` |
| 2   | `"xmlstreamend"` |
| 3   | `"s.whatsapp.net"` |
| 8   | `"id"` |
| 14  | `"user"` |
| 22  | `"xmlns"` |
| 25  | `"iq"` |
| 28  | `"g.us"` |
| 30  | `"urn:xmpp:whatsapp:push"` |
| 42  | `"urn:xmpp:ping"` |
| 235 | `"screen_height"` |

(See Baileys `WABinary/constants.ts:1056-1293` and whatsmeow `binary/token/token.go:9` for the full ordered list. The tables are byte-identical between the two libs.)

### Double-byte token dictionary

Four sub-arrays accessed by tag `Dictionary0..Dictionary3` (`236..239`) followed by a uint8 index into the selected sub-array. Each sub-array has up to 256 entries. From whatsmeow's `DictVersion = 3` (`binary/token/token.go:20`) — this constant is sent to the server when opening the connection so it knows which token tables the client supports.

Examples:
- `("read-self", dict=0, idx=0)`
- `("active",    dict=0, idx=1)`
- `("reject",    dict=1, idx=0)`
- `("64",        dict=2, idx=0)`
- `("1724",      dict=3, idx=0)`

### JID encodings

There are four wire forms; the encoder picks based on the JID's server/device.

**`JIDPair` (0xFA = 250)** — `user@server` without device, e.g. `1234567890@s.whatsapp.net`, `xxx@g.us`, `status@broadcast`:

```
0xFA  <user-string>  <server-string>
```

If user is empty (e.g. `@g.us` for a server-targeted stanza), `user-string` is `LIST_EMPTY` (`0x00`). Both implementations agree (Baileys `encode.ts:71-79`, whatsmeow `encoder.go:180-187`).

**`ADJID` (0xF7 = 247)** — Agent/Device JID. Used when the JID has a `Device > 0` and a `Server in {s.whatsapp.net, lid, hosted, hosted.lid}`:

```
0xF7  <agent: 1 byte>  <device: 1 byte>  <user-string>
```

Agent byte values (whatsmeow `types/jid.go:51-55`, `ActualAgent()` 76-89):
- `0` (`WhatsAppDomain`)  → `s.whatsapp.net`
- `1` (`LIDDomain`)       → `lid`
- `128` (`HostedDomain`)  → `hosted`
- `129` (`HostedLIDDomain`) → `hosted.lid`

So on decode the agent byte alone selects the server string. Baileys mirrors this exactly via `WAJIDDomains` (`jid-utils.ts:20-25`).

**`FBJID` (0xF6 = 246)** — Messenger interop, server is `"msgr"`:

```
0xF6  <user-string>  <device: uint16 BE>  <server-string>
```

(whatsmeow `encoder.go:168-172`, decoder `readFBJID` `decoder.go:268-288`.)

**`InteropJID` (0xF5 = 245)** — server is `"interop"`, includes a 16-bit integrator id:

```
0xF5  <user-string>  <device: uint16 BE>  <integrator: uint16 BE>  <server-string>
```

(whatsmeow `decoder.go:241-266`, encoder `encoder.go:173-178`; Baileys decode reads it but doesn't write it — the `WABinary/encode.ts` JID writer only emits `ADJID` or `JIDPair`. See "Discrepancies".)

Note Baileys's `readInteropJID` returns the JID string `"{integrator}-{user}:{device}@{server}"` (`decode.ts:177-191`) while whatsmeow's stringer formats it as `"{user}:{device}@interop"`. Different string formats, **same wire bytes** — JaWa should pick a canonical Java string layout (we recommend whatsmeow's `user[.agent][:device]@server` since it round-trips through `ParseJID` cleanly).

### Strings

`writeString(s)` decides among four wire forms by trying in order:

1. **Single-byte token.** If `s` is in `SingleByteTokens[1..]`, push that 1 byte (`tokenIndex`).
2. **Double-byte token.** Else if `s` is in any `DoubleByteTokens[dict]`, push `Dictionary0 + dict` then 1-byte index.
3. **Nibble-packed.** Else if `validateNibble(s)` (only `0-9`, `-`, `.`, `len <= 127`), push `0xFF Nibble8` + length byte (high bit = odd) + packed bytes.
4. **Hex-packed.** Else if `validateHex(s)` (only `0-9`, `A-F`, `len <= 127`), push `0xFB Hex8` + length byte + packed bytes.
5. **(Baileys only)** Else try `jidDecode(s)` → if it parses, write JID.
6. **Raw.** Else `writeByteLength(len)` + UTF-8 bytes, where `writeByteLength` selects `Binary8`/`Binary20`/`Binary32` based on size.

`writeByteLength` thresholds (encode.ts:42-56, encoder.go:64-77):
- `len < 256`                              → `Binary8`  (1-byte length)
- `len < 1<<20` (= 1,048,576)              → `Binary20` (3 bytes, top nibble of first byte masked to 4 bits)
- `len < math.MaxInt32` (~2.15 GB)         → `Binary32` (4-byte BE length)
- else: panic / throw

### Nibble and hex packing

Length byte format: `roundedLength = ceil(len/2)`, with `0x80` set when `len` is odd (the last nibble in the final byte will be padding and must be trimmed on decode).

Nibble alphabet (encode.ts:82-97, encoder.go:268-283):
```
'0'..'9' -> 0..9
'-'      -> 10
'.'      -> 11
NUL      -> 15  (pad)
```

Hex alphabet:
```
'0'..'9' -> 0..9
'A'..'F' -> 10..15   (lowercase a-f also accepted on encode in Baileys; whatsmeow only accepts uppercase!)
NUL      -> 15  (pad)
```

Pair packing: `(packer(c1) << 4) | packer(c2)`. On decode: high nibble first, then low. Trim final char if length-byte high bit is set.

### Lists

```
LIST_EMPTY  = 0           // size 0
LIST_8      = 0xF8 248    // followed by uint8 size
LIST_16     = 0xF9 249    // followed by uint16 BE size
```

### Node layout

A `Node` becomes:

```
<list-start L>  <tag-string>  [<key-string> <val-string>]^attrCount  [<content>]?
```

where

```
L = 2 * attrCount + 1 + (hasContent ? 1 : 0)
```

(encode.ts:228, encoder.go:81-99). Attributes with `nil`/`""`/`undefined` values are filtered out before counting.

Content union, in `write(...)`:
- `nil`               → `LIST_EMPTY` byte
- `string`            → `writeString(...)`
- `[]byte`            → `writeByteLength(...) + raw bytes`
- `[]Node`            → `writeListStart(len) + writeNode...` recursively
- integer/bool        → string form (whatsmeow `encoder.go:109-122`) — Baileys does **not** auto-stringify and throws on non-string attrs

## Crypto

**None at this layer.** Binary-node encoding is plaintext serialization; encryption is handled exclusively by the Noise transport (see `docs/protocol/01-noise.md`). The only "encoding" trick here is the optional zlib compression bit (`0x02`) on the *first byte of the decoded Noise plaintext*, with `zlib.Inflate` (Baileys `decode.ts:7-18`). JaWa uses `java.util.zip.Inflater` for decode; we never compress on encode.

## Algorithm / flow

### Encode (Marshal)

```
encode(node):
    buf = [0x00]                              // compression flag (always raw outbound)
    writeNode(buf, node)
    return buf

writeNode(buf, node):
    validAttrs = [(k,v) for k,v in node.attrs if v != "" and v != null]
    hasContent = node.content != null
    L = 2*len(validAttrs) + 1 + (1 if hasContent else 0)
    writeListStart(buf, L)
    writeString(buf, node.tag)
    for (k, v) in validAttrs:
        writeString(buf, k)
        writeValue(buf, v)                    // strings + JID + numbers + bool
    if hasContent:
        writeContent(buf, node.content)       // string | bytes | List<Node>

writeListStart(buf, n):
    if n == 0:     push 0x00
    elif n < 256:  push 0xF8, push n
    else:          push 0xF9, push (n >> 8) & 0xFF, push n & 0xFF

writeString(buf, s):
    if s == "": writeByteLength(0); return            // Binary8 0x00
    if s in singleByteIndex: push singleByteIndex[s]; return
    if s in doubleByteIndex:
        push 0xEC + dict
        push idx
        return
    if isNibble(s): writePacked(buf, 0xFF, s); return
    if isHex(s):    writePacked(buf, 0xFB, s); return
    if jidDecode(s) is valid: writeJID(buf, decoded); return        // Baileys only
    writeStringRaw(buf, s)                              // Binary8/20/32 + UTF-8

writeByteLength(buf, n):
    if n < 256:       push 0xFC, push n
    elif n < 1<<20:   push 0xFD, push ((n>>16)&0x0F, (n>>8)&0xFF, n&0xFF)
    elif n < 2^31:    push 0xFE, push 4-byte BE
    else: throw

writeJID(buf, jid):
    if jid.device > 0 and jid.server in {s.whatsapp.net, lid, hosted, hosted.lid}:
        push 0xF7                                       // ADJID
        push agent_for_server(jid.server, jid.rawAgent)
        push jid.device (truncated to uint8)
        writeString(buf, jid.user)
    elif jid.server == "msgr":
        push 0xF6                                       // FBJID
        writeString(buf, jid.user)
        push2BE(jid.device)
        writeString(buf, jid.server)
    elif jid.server == "interop":
        push 0xF5
        writeString(buf, jid.user)
        push2BE(jid.device)
        push2BE(jid.integrator)
        writeString(buf, jid.server)
    else:
        push 0xFA                                       // JIDPair
        if jid.user == "": push 0x00 else writeString(jid.user)
        writeString(buf, jid.server)

writePacked(buf, tag, s):       # tag = Nibble8 0xFF or Hex8 0xFB
    push tag
    rounded = ceil(len(s)/2)
    if len(s) % 2 != 0: rounded |= 0x80
    push rounded
    for each pair (c1,c2): push (packer(c1)<<4) | packer(c2)
    if odd: push (packer(last)<<4) | packer('\0')
```

### Decode (Unmarshal)

```
decode(bytes):
    flag = bytes[0]
    if flag & 0x02: bytes = zlib.inflate(bytes[1:])
    else:           bytes = bytes[1:]
    return readNode(bytes, idx=0)

readNode(buf, idx):
    listSize = readListSize(buf, idx, readByte())     // expects 0xF8 / 0xF9 / 0x00
    tag = readString(buf, idx, readByte())
    if listSize == 0 or tag == "": error("invalid node")
    attrCount = (listSize - 1) >> 1
    attrs = {}
    for i in 0..attrCount:
        k = readString(buf, idx, readByte())
        v = readValue(buf, idx, readByte())           // any value; usually string/JID
        attrs[k] = v
    if listSize % 2 == 1:                             # no content
        return Node(tag, attrs, null)
    content = readValue(buf, idx, readByte())        # may be list / bytes / string / JID
    return Node(tag, attrs, content)

readValue(buf, idx, tag):
    case ListEmpty(0):        return null
    case List8/List16:        return [readNode() for _ in 0..size]
    case Binary8/20/32:       return readBytes(size)         # raw bytes
                              # in attribute-key context, must be UTF-8 string
    case Dictionary0..3:      return doubleByteTokens[tag-0xEC][readByte()]
    case JIDPair:             return readJIDPair()
    case ADJID:               return readADJID()
    case FBJID:               return readFBJID()
    case InteropJID:          return readInteropJID()
    case Nibble8/Hex8:        return readPacked()
    case 1..235:              return singleByteTokens[tag]
    else: error
```

A subtle but important rule from whatsmeow `decoder.go:168-223` and Baileys `decode.ts:273-296`: when a value is read as an *attribute* it must decode to a string (or a JID), so `Binary8/20/32` payloads are interpreted as UTF-8 strings in that position. When the same tag appears as *node content* it stays as raw `byte[]`. The whatsmeow `read(string bool)` parameter tracks this; JaWa should pass an equivalent boolean.

## Baileys references

- `src/WABinary/constants.ts:1-19` — `TAGS` object: every tag byte value used by encoder/decoder.
- `src/WABinary/constants.ts:21-1054` — `DOUBLE_BYTE_TOKENS` (4 sub-arrays).
- `src/WABinary/constants.ts:1056-1293` — `SINGLE_BYTE_TOKENS` (235 entries).
- `src/WABinary/constants.ts:1295-1305` — `TOKEN_MAP` reverse index (string → {dict?,index}).
- `src/WABinary/encode.ts:5-12` — `encodeBinaryNode` entry; seeds `[0]` prefix.
- `src/WABinary/encode.ts:41-56` — `writeByteLength` selects Binary8/20/32.
- `src/WABinary/encode.ts:64-80` — `writeJid` (AD vs JIDPair only — Baileys does **not** emit FBJID/InteropJID).
- `src/WABinary/encode.ts:82-147` — nibble/hex packing.
- `src/WABinary/encode.ts:179-209` — `writeString` dispatch order: token → token → nibble → hex → JID-decode → raw.
- `src/WABinary/encode.ts:211-220` — `writeListStart` (LIST_EMPTY / LIST_8 / LIST_16).
- `src/WABinary/encode.ts:222-255` — `writeNode` master assembly: list-start, tag, attrs, content (string | Buffer | Array).
- `src/WABinary/decode.ts:9-18` — `decompressingIfRequired`: bit 1 of byte 0 = zlib compressed.
- `src/WABinary/decode.ts:55-69` — `readInt` (big-endian by default) and `readInt20` (20-bit BE with top nibble masked to 4 bits).
- `src/WABinary/decode.ts:106-122` — `readPacked8` decode: trims final char if `startByte >> 7` set.
- `src/WABinary/decode.ts:141-191` — `readJidPair`, `readAdJid`, `readFbJid`, `readInteropJid` (decoder supports all four; encoder doesn't).
- `src/WABinary/decode.ts:193-226` — `readString` central dispatch on tag byte.
- `src/WABinary/decode.ts:228-303` — node reassembly, `(listSize - 1) >> 1` attribute count, parity check.
- `src/WABinary/jid-utils.ts:20-25` — `WAJIDDomains` enum (0/1/128/129).
- `src/WABinary/jid-utils.ts:51-53` — `jidEncode` template: `${user}${_agent}${:device}@${server}`.
- `src/WABinary/jid-utils.ts:55-85` — `jidDecode` splits on `@`, `_` (agent), `:` (device).

## whatsmeow references

- `binary/token/token.go:9` — `SingleByteTokens` array (identical content to Baileys).
- `binary/token/token.go:10-15` — `DoubleByteTokens` 4 sub-arrays (identical content to Baileys).
- `binary/token/token.go:20` — `DictVersion = 3` (sent to server in handshake).
- `binary/token/token.go:30-43` — `init()` builds reverse maps.
- `binary/token/token.go:46-68` — `GetDoubleToken`, `IndexOfSingleToken`, `IndexOfDoubleByteToken`.
- `binary/token/token.go:71-94` — tag-byte constants and `PackedMax = 127`.
- `binary/encoder.go:16-18` — `newEncoder` seeds `[]byte{0}`.
- `binary/encoder.go:44-46` — `pushInt20` matches Baileys.
- `binary/encoder.go:64-77` — `writeByteLength` (note: panics rather than throws).
- `binary/encoder.go:81-99` — `writeNode` master assembly. **Special case at line 82-86: `Tag == "0"` is emitted as `List8 ListEmpty` (size 1, empty tag) — used as a placeholder/keepalive**.
- `binary/encoder.go:101-133` — `write(interface{})`: implicit string conversion for numbers and bools.
- `binary/encoder.go:135-149` — `writeString` dispatch (no JID auto-decode here — JIDs must already be `types.JID` values).
- `binary/encoder.go:161-188` — `writeJID`: full 4-way dispatch (AD / FBJID / InteropJID / JIDPair).
- `binary/encoder.go:211-221` — `writeListStart`.
- `binary/encoder.go:223-309` — packed-byte encode + nibble/hex validators and packers.
- `binary/decoder.go:17-19` — `newDecoder` (no compression handling — caller decompresses).
- `binary/decoder.go:69-77` — `readInt20`.
- `binary/decoder.go:83-153` — `readPacked8`, `unpackNibble`, `unpackHex`.
- `binary/decoder.go:155-166` — `readListSize`.
- `binary/decoder.go:168-223` — `read(asString bool)` master dispatch; `asString` toggles whether Binary8/20/32 returns `string` or `[]byte`.
- `binary/decoder.go:225-304` — JID decoders for all 4 variants.
- `binary/decoder.go:352-384` — `readNode` reads `int8` list size header, then header, attrs, optional content.
- `binary/node.go:18-25` — `Node` struct; `Attrs = map[string]any`.
- `binary/node.go:122-139` — `Marshal` / `Unmarshal` entry points; `Unmarshal` errors if leftover bytes remain.
- `binary/attrs.go:22-219` — `AttrUtility` accessor helpers (GetJID, GetInt64, GetBool, etc.). Useful pattern to port.
- `types/jid.go:22-34` — server constants (`s.whatsapp.net`, `g.us`, `lid`, `msgr`, `interop`, `newsletter`, `hosted`, `hosted.lid`, `bot`, `c.us`, `broadcast`).
- `types/jid.go:51-55` — domain-type constants 0/1/128/129.
- `types/jid.go:68-89` — `JID` struct: `User, RawAgent uint8, Device uint16, Integrator uint16, Server string` and `ActualAgent()`.
- `types/jid.go:158-200` — `ParseJID` splits on `@`, then `.` for agent, `:` for device.
- `types/jid.go:210-226` — `String()` formats: `user.agent:device@server` / `user:device@server` / `user@server` / `server`.

## Discrepancies

| Topic | Baileys | whatsmeow | Resolution for JaWa |
|---|---|---|---|
| **Outbound zlib compression** | Always 0x00 prefix, no compression on send (`encode.ts:8`). | Same (`encoder.go:18`). | Match: outbound always uncompressed. |
| **STREAM_END tag (0x02)** | Not in current `constants.ts/TAGS`. | Not in current `token.go` either. The 0x02 byte you see is the zlib compression flag, **not** a stanza tag. | Old documentation referencing STREAM_END=2 is stale; treat 0x02 only as the decompression bit on the frame's first byte. |
| **JID auto-encoding in `writeString`** | If `writeString` cannot tokenize/pack a string, it tries `jidDecode(s)` and emits a JID frame (`encode.ts:202-207`). | whatsmeow never auto-decodes; the caller must pass an actual `types.JID` value. | Mirror whatsmeow's strict typing in Java: use a `Jid` value class; do **not** sniff strings. This avoids accidental misinterpretation of strings that happen to contain `@`. |
| **JID-write coverage** | Only `ADJID` and `JIDPair` are emitted by the encoder (`encode.ts:64-80`); `FBJID`/`InteropJID` are decode-only. | Encoder emits all four (`encoder.go:161-188`). | Implement all four on encode (whatsmeow path) — incoming server messages occasionally require us to round-trip these. |
| **AD-JID device width on the wire** | Single byte (`encode.ts:68`: `pushByte(device || 0)`). | Single byte (`encoder.go:166`: `uint8(jid.Device)` — truncates `Device uint16`). | Wire is **always 1 byte**. In Java, validate `device <= 0xFF` before emitting; store as `int` but range-check. |
| **AD-JID server selection on decode** | Switch on `domainType` byte → server string (`decode.ts:158-165`). | `types.NewADJID` switches on agent byte → fills `Server` and *clears* `RawAgent` for known domains (`types/jid.go:131-155`). | Use whatsmeow's pattern: domain byte determines server, raw agent kept only when domain byte is not in {0,1,128,129}. |
| **Numeric/bool attribute values** | Encoder throws on non-string attrs (`encode.ts:232`: `if typeof attrs[key] === 'string'`). | Implicitly stringifies `int/int32/int64/uint*/bool` via `strconv` (`encoder.go:109-122`). | Adopt whatsmeow's coercion — many higher-level callers benefit; in Java use `Object` attr values with `toString()`/`Long.toString()` per type. |
| **`Tag == "0"` placeholder** | Not special-cased. | Encoded as `List8 ListEmpty` (size 1, empty-tag list) — `encoder.go:82-86`. Used as a server-side keepalive ack. | Implement whatsmeow's special case so our encoder is byte-compatible with their keepalives. |
| **Hex case in `validateHex`** | Accepts `A-F` and `a-f` on encode (`encode.ts:164-176`). | Only accepts `A-F` (`encoder.go:285-294`). | Match whatsmeow (uppercase only) to avoid emitting an encoding the server may not recognize from hex-packed form; decode is symmetric in both. |
| **Interop JID string format** | `${integrator}-${user}:${device}@${server}` (`decode.ts:177-191`). | `{user}:{device}@interop` (string form drops integrator). | Choose Java: a structured `Jid` record stores `integrator` as a field; `toString()` matches whatsmeow's format. Round-trip via the record, never via string. |
| **`DictVersion`** | Not exposed as a constant; clients hard-code in connection handshake. | Exported as `token.DictVersion = 3`. | JaWa: expose `BinaryTokens.DICT_VERSION = 3` for the handshake layer to include. |
| **Trailing bytes after decode** | Not checked — decoder just returns the first parsed node. | `Unmarshal` returns an error if `r.index != len(r.data)` (`node.go:135-137`). | Adopt whatsmeow's strict check; surface as `BinaryDecodeException`. |

For everything not listed here, Baileys and whatsmeow are byte-identical.

## Java implementation notes

### Dependencies
- **Standard library only** for this layer: `java.nio.ByteBuffer`, `java.util.zip.Inflater`, `java.nio.charset.StandardCharsets.UTF_8`.
- No BouncyCastle or libsignal needed at the binary-node layer; both are required deeper (Noise, Signal).
- protobuf-java for the *content* of message stanzas, but those are opaque `byte[]` to this layer — keep them out of `BinaryNode`.

### Package & class skeleton

`id.jawa.protocol.binary`:

```
package id.jawa.protocol.binary;

public final class BinaryTokens {
    public static final int DICT_VERSION = 3;
    public static final int PACKED_MAX = 127;
    // Tag bytes
    public static final int LIST_EMPTY   = 0x00;
    public static final int DICTIONARY_0 = 0xEC;
    public static final int DICTIONARY_1 = 0xED;
    public static final int DICTIONARY_2 = 0xEE;
    public static final int DICTIONARY_3 = 0xEF;
    public static final int INTEROP_JID  = 0xF5;
    public static final int FB_JID       = 0xF6;
    public static final int AD_JID       = 0xF7;
    public static final int LIST_8       = 0xF8;
    public static final int LIST_16      = 0xF9;
    public static final int JID_PAIR     = 0xFA;
    public static final int HEX_8        = 0xFB;
    public static final int BINARY_8     = 0xFC;
    public static final int BINARY_20    = 0xFD;
    public static final int BINARY_32    = 0xFE;
    public static final int NIBBLE_8     = 0xFF;

    public static final String[] SINGLE_BYTE_TOKENS = { /* 235 entries */ };
    public static final String[][] DOUBLE_BYTE_TOKENS = { /* 4 sub-arrays */ };

    // Built in <clinit>:
    static final Map<String, Integer> SINGLE_INDEX;
    static final Map<String, int[]> DOUBLE_INDEX; // value = {dict, idx}
}

public record Jid(
    String user,
    int rawAgent,      // 0..255
    int device,        // 0..65535 (wire emits low 8 bits for AD JID)
    int integrator,    // 0..65535, only for interop server
    String server
) {
    public static final Jid EMPTY = new Jid("", 0, 0, 0, "");
    public static Jid parse(String s) { /* port of types.ParseJID */ }
    public int actualAgent() { /* port of ActualAgent */ }
    public Jid toNonAd() { return new Jid(user, 0, 0, integrator, server); }
    @Override public String toString() { /* user[.agent][:device]@server */ }
}

public record BinaryNode(String tag, Map<String, Object> attrs, Object content) {
    // content may be null | String | byte[] | List<BinaryNode> | Jid
}

public final class BinaryNodeEncoder {
    public byte[] encode(BinaryNode n) { /* port of encoder.go */ }
}

public final class BinaryNodeDecoder {
    public BinaryNode decode(byte[] frame) throws BinaryDecodeException {
        /* strip 0x00 / inflate 0x02, then readNode */
    }
}

public final class AttrUtil {
    // Port of binary/attrs.go for typed accessors with deferred error list.
    public AttrUtil(BinaryNode n) { ... }
    public Jid jid(String key) { ... }
    public long int64(String key) { ... }
    public boolean bool(String key) { ... }
    public Instant unixTime(String key) { ... }
    public List<Throwable> errors() { ... }
}
```

### API sketch

The codec is a pure, stateless pair of static methods on `BinaryNodeEncoder`/`BinaryNodeDecoder`, mirroring `Marshal`/`Unmarshal`. Use a `BinaryWriter` inner helper with a growable `byte[]` (initial capacity ~1024, doubling) seeded with `0x00`. Implement `writeString` via a single switch ladder identical to whatsmeow's; build `SINGLE_INDEX` and `DOUBLE_INDEX` lookup maps in a `static {}` block from the literal token arrays. The decoder uses a small mutable `Cursor` (just a `byte[]` + `int idx`) and `Inflater` for the compression branch. Keep all numeric reads big-endian (default `ByteBuffer.BIG_ENDIAN`). For attribute values, follow whatsmeow's coercion: `if (v instanceof Number n) writeString(n.toString()); else if (v instanceof Boolean b) writeString(b.toString()); else if (v instanceof Jid j) writeJID(j); else writeString((String) v)`.

Token tables: paste the literal arrays from `constants.ts` / `token.go` into a generated `BinaryTokensData.java`. Don't read them from a resource file at runtime — fixed at compile time keeps the encoder allocation-free for the dictionary path.

## Open questions

- Does the server ever send raw stanzas with a leading byte other than `0x00` or `0x02`? Both libs only check bit 1; the high bits appear reserved. Verify against a real connection log.
- Are there higher-numbered domain bytes beyond `0/1/128/129` in `ADJID`? whatsmeow's `NewADJID` falls through to keeping `RawAgent` and an empty server for unknown values — what does the server actually emit for newsletter/bot AD-JIDs (if any)?
- `binary_version` (single-byte token #221) is sent in attributes; what value does the server expect now that `DictVersion = 3`? Cross-check against the handshake layer's `<connect ...>` stanza.
- The `xmlstreamstart` (1) / `xmlstreamend` (2) tokens are used as standalone tags for stream-control stanzas. Confirm exactly when these are emitted — likely only on Noise stream framing, not inside normal IQs.
- Baileys's `attribute_padding` token (single-byte #209) suggests some attributes are padded — find where this is emitted in client code (likely the message-receipt or app-state layer) so JaWa can preserve byte-for-byte equivalence when needed.
- Confirm `Binary20` length field is exactly 20 bits (top 4 of first byte are `0000`) and not 24-bit truncated; both libs implement `(b0 & 0x0F) << 16 | b1 << 8 | b2`, so a value with the top nibble set would be misdecoded. Is there a guard server-side, or do we need to enforce `len < 1<<20` strictly on encode (Baileys/whatsmeow both already do).
