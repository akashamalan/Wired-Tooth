# iOS audio routing and deployment

Research notes for WP7. No iOS code exists yet; this documents the decisions
that WP7a-WP7d will implement, and corrects several assumptions that would
otherwise be built on.

## Lightning EarPods are not a USB audio device

This matters because it changes what the app checks for at runtime.

Lightning is not USB. Lightning EarPods are an MFi accessory containing their
own DAC and amplifier in the inline module; the phone sends them a digital
audio stream over the Lightning connector's own protocol, and the accessory is
authenticated by an MFi chip. There is no USB Audio Class involved.

What this means in code: the route shows up as
`AVAudioSession.Port.headphones`, **not** `.usbAudio`.

USB-C EarPods, on iPhone 15 and later, are genuinely different hardware — they
do enumerate as USB Audio Class 2.0 and surface as `.usbAudio`.

Since this project targets both, the check must accept either:

```swift
let wired: Set<AVAudioSession.Port> = [.headphones, .usbAudio]
let isWired = AVAudioSession.sharedInstance()
    .currentRoute.outputs.contains { wired.contains($0.portType) }
```

Assuming `.usbAudio` alone silently fails on every Lightning device, which is
iPhone 14 and earlier.

## Category selection

Use `.playback`, not `.playAndRecord`.

```swift
try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
try AVAudioSession.sharedInstance().setActive(true)
```

`.playback` is output only, which is all this app does. `.playAndRecord`
activates the input path, and if it is configured with `.allowBluetooth` it
enables the Bluetooth HFP profile. HFP is a narrowband bidirectional voice
profile: selecting it collapses output to 8 or 16 kHz mono and can pull the
route onto a Bluetooth headset. For a project whose entire premise is beating
Bluetooth latency, that is the worst possible failure, and it is caused by an
option that looks harmless.

So: category `.playback`, and do not pass `.allowBluetooth` or
`.allowBluetoothA2DP`.

## You cannot force output to the Lightning port

The requirement to "route output specifically to Lightning EarPods via
AVAudioSession.PortOverride" is not achievable, because that is not what the
API does.

`AVAudioSession.PortOverride` has exactly two cases, `.none` and `.speaker`.
Its only function is to force audio to the built-in speaker instead of the
receiver earpiece, and it applies to `.playAndRecord`. There is no value that
selects headphones, Lightning, or USB. `setPreferredInput` exists but governs
input only. iOS deliberately does not expose output route selection to apps —
that is the user's choice, made by plugging something in or picking a route in
Control Center.

What actually happens is route priority, which iOS decides: a connected wired
accessory outranks the built-in speaker automatically. Plugging the EarPods in
is the mechanism. There is no API call needed and none available.

If a Bluetooth A2DP device is already connected, it is a legitimate output
route for `.playback` and iOS may select it. The category choice above avoids
the far worse HFP case, but it does not give the app a veto over A2DP.

**Therefore the app detects and reports rather than forces.** Observe route
changes, and if the active output is not wired, say so on screen instead of
pretending the stream is going somewhere it is not:

```swift
NotificationCenter.default.addObserver(
    forName: AVAudioSession.routeChangeNotification,
    object: nil, queue: .main
) { _ in
    // Surface the real route in the UI. A latency claim measured over
    // Bluetooth would be meaningless, so the UI must never let the user
    // believe wired output is active when it is not.
}
```

This is a correction to the stated design, not an implementation detail. Any
"force the route" code would be writing calls that do not exist.

## Latency

Request a small IO buffer, then read back what was actually granted — the
request is a hint and the system may not honour it:

```swift
try session.setPreferredIOBufferDuration(0.005)   // 5 ms request
let granted = session.ioBufferDuration            // what you actually got
let outputLatency = session.outputLatency         // device output path
```

`outputLatency` is the term WP3's estimate needs on the iOS side, standing in
for `WaveOut.DesiredLatency` on Windows:

    estimated end-to-end = RTT/2 + jitter buffer depth + outputLatency

The EarPods' own DAC adds a small fixed delay on top, which no API reports.
That is one reason `docs/MEASUREMENT.md` treats the acoustic measurement as
the only honest end-to-end number.

## Background audio

Playback continues with the screen locked only with the audio background mode
declared in Info.plist:

```xml
<key>UIBackgroundModes</key>
<array><string>audio</string></array>
```

The session must be active and actually playing when the app backgrounds; an
idle engine gets suspended. Interruptions (a phone call, another app taking the
session) must be handled or playback stops permanently after the first one:

```swift
NotificationCenter.default.addObserver(
    forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
) { note in
    // On .ended with .shouldResume, reactivate the session and restart the
    // engine. Without this the stream dies silently after the first call.
}
```

## Local network permission

iOS 14 and later gate local network access behind a user prompt, and this
applies to plain unicast UDP to a private address — not just to multicast or
Bonjour. Without the Info.plist string the app is denied and the failure looks
like silence, not an error:

```xml
<key>NSLocalNetworkUsageDescription</key>
<string>Wired Tooth receives audio streamed from your PC over your local
Wi-Fi network.</string>
```

No entitlement file is needed. The `com.apple.developer.networking.multicast`
entitlement is deliberately not requested, which is why the design is unicast
only and pairing is by QR code (`CLAUDE.md` hard rule 3). Adding any broadcast,
multicast, or Bonjour browsing later would require that entitlement, which
Apple grants by application only.

The prompt appears once, on the first connection attempt. If the user declines,
it cannot be re-prompted from the app — it has to be re-enabled in Settings >
Privacy & Security > Local Network. Worth stating in the UI when connection
fails.

## Build and deployment

**Requires a Mac.** Xcode does not run on Windows, and there is no supported
path to compile, sign, or install an iOS app from a Windows machine. This is a
hard prerequisite for all of WP7.

Signing options:

| | Cost | Profile validity | Devices |
|---|---|---|---|
| Free Apple ID | none | **7 days**, then the app stops launching | registered device, re-sign weekly |
| Apple Developer Program | 99 USD/year | 1 year | 100 devices |

The free tier is sufficient to prove the project works. It is not sufficient
for a demo you want to still function next week, which matters if the point is
showing it to people.

Steps:

1. Xcode: File > New > Project > iOS > App. SwiftUI, Swift.
   Product name `WiredTooth`, bundle id `com.<you>.WiredTooth`.
2. Target > Signing & Capabilities: select your team. Enable "Automatically
   manage signing".
3. Add the Info.plist keys above: `NSLocalNetworkUsageDescription` and
   `UIBackgroundModes` = `audio`.
4. Connect the iPhone by USB, trust the computer, select it as the run
   destination.
5. First run on a free profile: the device rejects the untrusted developer.
   On the phone, Settings > General > VPN & Device Management > trust the
   developer certificate, then run again.
6. Both devices must be on the same network. Use a phone hotspot: campus and
   public Wi-Fi almost always enable AP client isolation, which blocks
   device-to-device traffic entirely and looks exactly like a broken app.

## Networking framework

Use `Network.framework` (`NWConnection`, `NWListener`), not BSD sockets. It
handles iOS network-interface changes, and it is the API that interacts
correctly with the local network permission model.

Audio arrives on UDP 5000, matching the sender. Control is UDP 5001. These are
the ports in `WtpPacket` and `NetworkClient`; there is no 6980 anywhere in this
project.

## Open dependency

WP7b requires the receiver to send `HELLO` keepalives to the sender's control
port. That handshake is WP2 and is not implemented — `PacketType.Hello` exists
in the protocol enum, but `NetworkClient` neither listens on 5001 nor responds.
An iOS receiver written before WP2 would be coding against a sender that cannot
answer it.
