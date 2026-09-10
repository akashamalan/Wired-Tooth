# Wired Tooth

Wired Tooth streams a Windows PC's system audio over local Wi-Fi to a second device, so
that **wired** earbuds plugged into that device can play the PC's audio with far less
delay than Bluetooth. Audio is captured with WASAPI loopback, packetised, and sent as UDP
unicast; the receiver runs a jitter buffer and plays the stream back. The measured
end-to-end latency is the project's primary success metric, so every design decision is
made in service of it.

This repository is at the prototype stage: capture, transport, and playback work over
localhost. See `BUILD_PLAN.md` for the road to v1.0 and v2.0.

## Architecture (current prototype)

```
  Windows app audio
        |
        v
  +---------------------------+
  | WasapiLoopbackCapture     |   NetworkClient (sender)
  | 48 kHz, 2 ch, 32-bit float|
  +---------------------------+
        |  DataAvailable
        v
  +---------------------------+
  | float32 -> int16, clamped |
  +---------------------------+
        |
        v
  +---------------------------+
  | WTP1: 28-byte audio header|   magic | type | flags | payloadLen
  | + PCM16, 1400 B max       |   seq | timestampUs | rate | ch | bits | codec
  +---------------------------+
        |
        v
     UDP unicast :5000 audio, :5001 control
        |
        v
  +---------------------------+
  | BufferedWaveProvider      |   NetworkTest (receiver)
  | jitter buffer, 60 ms pre  |
  +---------------------------+
        |
        v
  +---------------------------+
  | WaveOutEvent, 50 ms       |
  +---------------------------+
        |
        v
     speakers / wired earbuds
```

Not yet built: control channel and handshake, latency measurement, clock-drift
correction, reconnection, tray UI, iOS receiver. Those are WP2 through WP7 in
`BUILD_PLAN.md`.

## Projects

| Path | Role |
|---|---|
| `AudioInspect/` | Diagnostic tool. Prints the capture format and raw sample values. Run this first when audio sounds wrong. |
| `NetworkClient/` | Sender. WASAPI loopback capture to UDP. Console. |
| `NetworkTest/` | Windows receiver. UDP to playback. Kept permanently as the regression test. |
| `docs/` | Protocol specification and engineering notes. |
| `tools/` | Measurement and plotting scripts. |

## Build

Requires the .NET 10 SDK on Windows. NAudio is restored from NuGet.

```
dotnet build WiredTooth.sln
```

## Run

Inspect the capture format and raw samples:

```
dotnet run --project AudioInspect
```

Loopback test on one machine. Start the receiver first, then the sender, then play audio
on the PC:

```
dotnet run --project NetworkTest
```

```
dotnet run --project NetworkClient
```

Stream to another machine by passing its IP address to the sender:

```
dotnet run --project NetworkClient -- 192.168.1.42
```

Press ENTER in either window to stop. Both print packet counts, loss, buffer depth in
milliseconds, and underruns.

## Notes

- Windows Firewall blocks the first run silently. Allow the app on private networks.
- Public and campus Wi-Fi usually block device-to-device traffic. Test on a phone hotspot.
- Audio is sent as uncompressed 16-bit PCM, roughly 192 KB/s per client. The `codec`
  header field stays 0 until a codec is added, if ever.
- The full wire format is specified in [docs/PROTOCOL.md](docs/PROTOCOL.md). Write any
  new receiver against that document, not against this diagram.
