# Wired Tooth — project context for Claude Code

Read this before making any change. It exists to stop well-meaning refactors from
destroying a working baseline.

## What this project is

Low-latency system audio streaming from a Windows PC to a phone over local Wi-Fi, so
**wired** earbuds plugged into the phone can play the PC's audio with far less delay than
Bluetooth. Personal portfolio project. The measured latency number is the whole point of
the project — treat it as the primary success metric for every design decision.

## Architecture (do not change without being asked)

```
Windows app audio
  → WASAPI loopback capture (32-bit float)
  → convert to 16-bit PCM
  → chunk to <1400 bytes (avoid IP fragmentation on Wi-Fi)
  → WTP1 packet header
  → UDP unicast, audio on :5000, control on :5001
  → Wi-Fi
  → jitter buffer (60 ms target)
  → clock-drift correction (continuous resampling, ±0.5% clamp)
  → audio output (WasapiOut shared mode on Windows / AVAudioEngine on iOS)
  → wired earbuds
```

## Projects

| Path | Role | Notes |
|---|---|---|
| `AudioInspect/` | Diagnostic tool | Prints the capture format and raw samples. **Never delete.** First thing to run when audio sounds wrong. |
| `NetworkClient/` | Sender | WASAPI → UDP. Console. |
| `NetworkTest/` | Windows receiver | UDP → playback. Kept permanently as the regression test. |
| `MacReceiver/` | Python test receivers | `receiver_stats.py` (stdlib, counts packets, reports loss/RTT/jitter) exists and is the real-network test tool. `receiver_play.py` not written. |
| `WiredTooth.Protocol/` | Shared packet code | Created in WP1. |
| `WiredTooth.Sender/` | Shared sender | AudioSender + LocalAddress. Used by NetworkClient and the tray app. Created in WP6. |
| `WiredTooth.Windows/` | Tray app | WPF tray icon, status window, QR pairing, `--console`. Created in WP6. |
| `AndroidReceiver/` | Android receiver | Kotlin, AudioTrack playback. Built and unit-tested on Windows. Created in WP7. |
| `ios/` | Swift receiver | Not written. Xcode is macOS-only; Android was substituted. See docs/IOS_AUDIO.md. |

## Hard rules

1. **Follow `BUILD_PLAN.md`.** Work one WP at a time. Don't implement things from later
   packages early, even if they seem trivially easy while you're in the file.
2. **UDP, never TCP, for audio.** TCP retransmission stalls live audio. A lost 5 ms is
   better than a 200 ms freeze. Don't "improve reliability" by switching.
3. **Unicast only on the iOS side.** No broadcast, no multicast, no Bonjour service
   browsing — all of those require the `com.apple.developer.networking.multicast`
   entitlement, which we are deliberately not requesting. Pairing is by QR code.
4. **Don't merge the sender and receiver into one "AudioBridge" app.** Separate binaries
   until v1.0 acceptance passes.
5. **Don't add a codec.** Raw PCM16 until v1.0 ships. The `codec` header field exists so
   Opus can be added later without breaking compatibility. Leave it at 0.
6. **Don't add a GUI to `NetworkClient` or `NetworkTest`.** They stay as console tools.
   The GUI is a separate project (WP6).
7. **Keep packets under ~1400 bytes.** Fragmented UDP on Wi-Fi causes dropouts.
8. **Never throw on a malformed packet.** Drop it and continue. Hostile or corrupt input
   must not kill the stream.

## Known gotchas

- **WASAPI loopback goes silent when nothing is playing.** `DataAvailable` simply stops
  firing. The sender must emit silence-flagged keepalive packets so the receiver's buffer
  and RTT tracking don't starve during quiet passages.
- **Clock drift is the main enemy.** Sender and receiver sound cards are never at exactly
  the same rate. Without correction, the buffer drains or grows and it fails after ~20
  minutes, not immediately. Always test for 30 minutes, never 30 seconds.
- **Use `WasapiOut`, never `WaveOutEvent`, for playback.** Measured on this machine,
  same device and same format, with no networking involved at all:
  `WaveOutEvent` (WinMM) drained 39,977 frames/s, **-16.7%** of real time, while
  `WasapiOut` (shared mode) drained 48,219 frames/s, **+0.46%**. The default render
  endpoint here is a virtual device (FxSound Audio Enhancer) and WinMM runs it far
  slower than real time. No drift controller can close a 16.7% gap -- the clamp is
  +/-0.5% precisely so corrections stay inaudible -- so the jitter buffer fills to
  its ceiling and stays there no matter what the control loop commands. This cost a
  full work package to find, because every symptom pointed at the sender or the
  buffer rather than at the output device.
- **Lightning EarPods are iPhone 14 and earlier.** iPhone 15+ is USB-C.
- **Windows Firewall silently blocks the first run.** Private networks must be allowed.
- **Public/campus Wi-Fi usually blocks device-to-device traffic** (AP client isolation).
  Always test on a phone hotspot.

## Testing

Before any commit:

1. Loopback still works: `NetworkTest` then `NetworkClient` on the same PC, audio plays.
2. Real network still works: sender → hotspot → Mac receiver, loss under 1%.
3. For anything touching the buffer: a 30-minute run with the metrics CSV, and the
   buffer-depth line must be flat.

## Style

- C#: file-scoped namespaces, nullable enabled, top-level statements only in the console
  entry points.
- Comment the *why*, not the *what*. Especially for the DSP and buffering code — it's
  the part that will be unreadable in six months.
- No emoji in code, comments, or docs.
