# Wired Tooth Protocol v1 (WTP1)

Wire format between the Windows sender and any receiver. This document is the
contract: the C# implementation in `WiredTooth.Protocol/` and the future Swift
receiver both parse these bytes, so neither may drift from what is written
here.

- **Transport:** UDP. Audio on port **5000**, control on port **5001**.
- **Byte order:** little-endian throughout, including the magic.
- **Unicast only.** No broadcast, no multicast, no Bonjour.

Little-endian is chosen because both ends are little-endian in practice (x86
and ARM), so neither side pays a byte swap per packet.

## Common header — 20 bytes, present on every packet

| Offset | Size | Field | Type | Notes |
|---|---|---|---|---|
| 0 | 4 | `magic` | bytes | `"WTP1"` = `57 54 50 31` |
| 4 | 1 | `type` | uint8 | See packet types below |
| 5 | 1 | `flags` | uint8 | bit0 = silence frame; other bits reserved, must be 0 |
| 6 | 2 | `payloadLen` | uint16 | Bytes after the header. Must equal actual remaining length |
| 8 | 4 | `sequence` | uint32 | Increments per packet sent. Wraps at 2^32 |
| 12 | 8 | `timestamp` | int64 | Microseconds since the sender process started |

### Audio sub-header — 8 further bytes, `AUDIO` packets only

| Offset | Size | Field | Type | Notes |
|---|---|---|---|---|
| 20 | 4 | `sampleRate` | uint32 | e.g. 48000. Must be non-zero |
| 24 | 2 | `channels` | uint16 | e.g. 2. Must be non-zero |
| 26 | 1 | `bitsPerSample` | uint8 | 16 for PCM16. Must be non-zero |
| 27 | 1 | `codec` | uint8 | 0 = PCM16. 1 = Opus, reserved and unused |
| 28 | … | `payload` | bytes | Interleaved PCM16 frames, little-endian |

So an audio packet has a 28-byte header, and a control packet has a 20-byte
header.

## Packet types

| Value | Name | Direction | Purpose |
|---|---|---|---|
| 1 | `AUDIO` | sender → receiver | One chunk of PCM16 audio |
| 2 | `HELLO` | receiver → sender | Announce/keepalive (WP2) |
| 3 | `HELLO_ACK` | sender → receiver | Handshake reply (WP2) |
| 4 | `PING` | receiver → sender | RTT probe carrying send time (WP3) |
| 5 | `PONG` | sender → receiver | Echoes the PING timestamp (WP3) |
| 6 | `BYE` | either | Clean disconnect (WP2) |

All six types are implemented as of WP3: `AUDIO` in WP1, `HELLO`/`HELLO_ACK`/`BYE`
in WP2, `PING`/`PONG` in WP3.

### PING / PONG

`PING` carries no payload. The probe's send time is the `timestamp` field of its
own common header.

`PONG` carries an **8-byte payload**: the `timestamp` copied verbatim out of the
`PING` that triggered it, little-endian int64.

The echo has to live in the payload because the `PONG`'s own header `timestamp`
is the *sender's* clock, and the two clocks share no origin — each is a
`Stopwatch` started when its own process started. Subtracting one from the other
measures nothing. Round trip time is therefore computed entirely on the
receiver, against its own clock:

    rtt = receiver_now_us - echoed_timestamp_us

which is why the sender never needs to know what the number means, and simply
copies it back.

## Timestamps

`timestamp` is **microseconds since the sender process started**, taken from a
monotonic `Stopwatch`.

Microseconds rather than milliseconds because at 48 kHz one millisecond is 48
frames, far too coarse to measure the end-to-end latency figure this project
exists to report. Monotonic rather than wall clock because an NTP correction
can step a wall clock backwards mid-stream, which would produce negative
deltas.

The value is an offset from an arbitrary origin, so it is only meaningful as a
difference between two packets from the same sender. It is not a date.

## Sizing

Datagrams are capped at **1400 bytes** total. Wi-Fi links typically carry a
1500-byte MTU, and a fragmented UDP datagram on a lossy wireless link loses the
whole frame if any one fragment drops.

That leaves **1372 bytes** of audio payload (`1400 - 28`). The payload must be
a whole number of frames: a datagram that split a frame would leave the
receiver permanently misaligned, swapping left and right. For 48 kHz stereo
PCM16 the frame size is 4 bytes, giving 343 frames = 1372 bytes = 7.15 ms per
packet.

One WASAPI buffer is several kilobytes, so the sender splits it across several
packets, each with its own sequence number.

## Receiver obligations

A receiver **must drop, and must not throw on**, any packet that:

- is shorter than 20 bytes, or shorter than 28 bytes when `type` is `AUDIO`
- does not begin with `WTP1`
- carries a `type` outside 1–6
- has `payloadLen` that does not equal the actual bytes remaining
- is an `AUDIO` packet with a zero `sampleRate`, `channels`, or `bitsPerSample`

This is data straight off a public socket. A crash on corrupt or hostile input
would take the whole stream down, so parsing is total: it returns a failure,
never an exception.

Verified in WP1: 17 hand-written malformed cases, all 11,220 single-byte
mutations of a valid packet, and 20,000 random datagrams — zero exceptions.

Note that most single-byte mutations are *legitimately* accepted, because a
mutated `payload`, `timestamp` or `sequence` byte is still a structurally valid
packet. The parser validates structure; whether a `codec` is one the receiver
can actually decode is the receiver's own check.

## Worked example

An `AUDIO` packet: sequence 1, timestamp 1,000,000 µs (1.0 s), 48 kHz stereo
PCM16, 4 frames of audio (16 bytes). Total 44 bytes.

```
57 54 50 31 01 00 10 00 01 00 00 00 40 42 0F 00 00 00 00 00
80 BB 00 00 02 00 10 00
00 00 00 00 E8 03 18 FC FF 7F 00 80 64 00 9C FF
```

Decoded:

| Bytes | Field | Value |
|---|---|---|
| `57 54 50 31` | magic | `"WTP1"` |
| `01` | type | 1 = `AUDIO` |
| `00` | flags | none (not a silence frame) |
| `10 00` | payloadLen | 0x0010 = 16 bytes |
| `01 00 00 00` | sequence | 1 |
| `40 42 0F 00 00 00 00 00` | timestamp | 0x0F4240 = 1,000,000 µs |
| `80 BB 00 00` | sampleRate | 0xBB80 = 48000 |
| `02 00` | channels | 2 |
| `10` | bitsPerSample | 0x10 = 16 |
| `00` | codec | 0 = PCM16 |

Payload, as four stereo frames of int16 (left, right):

| Frame | Bytes | Left | Right |
|---|---|---|---|
| 0 | `00 00 00 00` | 0 | 0 |
| 1 | `E8 03 18 FC` | 1000 | −1000 |
| 2 | `FF 7F 00 80` | 32767 | −32768 |
| 3 | `64 00 9C FF` | 100 | −100 |

## Sample format

WASAPI loopback capture produces 32-bit float. The wire carries 16-bit PCM,
which halves bandwidth for no audible loss and is directly playable by every
receiver without further conversion.

Conversion clamps to [−1, 1] before scaling by 32767. Clamping is not
optional: loopback can return samples slightly outside that range when an
application applies its own gain, and an unclamped cast wraps a sample just
over +1.0 into a large negative int16 — a full-scale discontinuity, audible as
a click on exactly the loudest passages. NaN is mapped to silence, since NaN
fails every comparison and would otherwise pass straight through the clamp.

`codec` stays 0 until Opus is added, if ever. The field exists so that adding
it later does not break compatibility.
