# Wired Tooth — Android receiver

Receives the WTP1 stream from the Windows sender and plays it through
`AudioTrack`, so wired earbuds in the phone's USB-C port play the PC's audio.

Android 8.0 (API 26) and above. `minSdk = 26` because `AudioTrack.Builder`,
`PERFORMANCE_MODE_LOW_LATENCY` and `getTimestamp` all arrive there, and those
are what the playback path needs.

## Why Android and not iOS

`BUILD_PLAN.md` WP7 specifies a Swift/iOS receiver, and `docs/IOS_AUDIO.md`
is the research for it. This is a deliberate substitution, not a drift:
Android Studio runs on Windows, so this receiver could be built, unit-tested
and installed from the same machine as the sender. iOS cannot — Xcode is
macOS-only, so every line of a Swift receiver would have been unverifiable
until a Mac appeared.

The iOS research stands and nothing here invalidates it. If the Swift
receiver gets written later, this is the reference implementation to port
from, and the tests below are the ones it has to pass.

## Build

Needs the Android SDK and JDK 17+. Everything else comes from Gradle.

```
cd AndroidReceiver
./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk` (about 840 KB).

`local.properties` must point at the SDK and is gitignored, because it is a
machine-specific absolute path:

```
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

## Test

```
./gradlew :app:testDebugUnitTest
```

Ten tests covering the WTP1 parser. They are not a formality — the parser is
the second independent implementation of the wire format, and the fixture is
the worked example from `docs/PROTOCOL.md` byte for byte, so both this and the
C# parser are checked against the spec rather than against each other.

The malformed-input tests mirror the ones the C# parser was verified against:
15 hand-written bad cases, all 11,220 single-byte mutations of a valid packet,
and 20,000 random datagrams. The requirement is not that bad input is rejected
tidily — it is that parsing **never throws**, because this runs on data
straight off a socket and an exception would kill the stream.

## Install and run

Connect the phone with USB debugging enabled:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then:

1. Put the phone and the PC on the same network. A phone hotspot is the
   reliable option — public and campus Wi-Fi usually block device-to-device
   traffic entirely, which looks exactly like a broken app.
2. Start the sender on the PC (tray app or `dotnet run --project NetworkClient`).
3. Enter the PC's IP and tap Connect. Scanning the tray app's QR code fills
   the address in automatically — the app registers the `wiredtooth://` scheme,
   so the camera app hands the address straight over.

Plug wired earbuds into the phone. Android routes to them automatically when
they are connected; there is no API call and no permission involved.

## What the screen shows

| Field | Meaning |
|---|---|
| state | connecting / connected / streaming / reconnecting |
| rtt | median of the last 20 PING round trips, milliseconds |
| buffer | jitter buffer depth. Should sit near 60 ms and stay there |
| ratio | drift correction speed multiplier. Should settle near 1.00000 |
| bitrate | received kbit/s, around 1,566 for 48 kHz stereo PCM16 |
| received / lost / dropped | packet counts. `dropped` is malformed, not lost |
| underruns | the buffer ran empty during playback |

`buffer` and `ratio` are the two worth watching. A buffer that climbs or
drains steadily is clock drift the controller is failing to hold; a ratio
pinned at 0.99500 or 1.00500 means it is commanding maximum correction and
losing, which on Windows turned out to mean the output device itself was
running at the wrong rate. See `docs/ENGINEERING_NOTES.md`.

## Design

Ported from `NetworkTest` rather than designed fresh, so the two receivers
behave identically and a measurement on one says something about the other:

- **Same handshake.** HELLO every 1 s, PING every 500 ms, BYE on exit. The
  receiver initiates, so the PC learns the phone's address from the source of
  its own packet and never needs to be told it.
- **Same 60 ms jitter buffer**, same `RESYNC_THRESHOLD_MS` transient guard.
- **Same drift controller**: proportional, ±0.5% clamp, no integral term. The
  steady-state offset it settles at *is* the clock difference; an integrator
  would chase it to zero, wind up, and waver audibly.
- **Same concealment**: last frame faded over 5 ms, capped at 200 ms.
- **Same total parser**: malformed input is dropped and counted, never thrown.

The one genuinely Android-specific part is `AudioTrack` standing in for
`WasapiOut`. Its own buffer is kept near the device minimum, because that
buffer is latency this code cannot see or steer — the jitter buffer above it
is the one the drift loop controls.

## Known gaps

- **Not yet run on a physical device.** It compiles and the parser tests pass,
  but no phone was attached to this machine, so playback, routing to wired
  earbuds, and real latency are all unverified.
- **No background playback.** The screen is kept awake instead; surviving a
  screen-off needs a foreground service.
- **No QR scanner in-app.** Pairing relies on the camera app opening the
  `wiredtooth://` link. Manual IP entry is the fallback.
- **Plain views, no Compose.** Deliberate: the screen is a status line and
  five numbers, and every dependency is another thing that can fail to
  resolve.
