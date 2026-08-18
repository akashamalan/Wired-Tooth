# Wired Tooth — Full Project Analysis & Implementation Plan

## 🎯 What Is This Project?

**Wired Tooth** is a real-time audio streaming application written in **C# (.NET 10)**. It captures audio playing on your Windows PC (system/loopback audio) and streams it over the network using **UDP** packets — like a wired version of Bluetooth audio.

Think of it as: **Your PC captures audio → sends it over UDP → another device receives & plays it in real-time.**

---

## 📁 Complete Folder & File Structure

```
Wired Tooth/                          ← Root project folder
│
├── AudioBridge/                      ← EMPTY — placeholder for future work
│
├── WiRED TOOTH/                      ← Audio inspection / debug tool
│   ├── Wired Tooth.csproj            ← .NET 10 project file (uses NAudio 2.3.0)
│   └── wired.cs                      ← Captures loopback audio & prints raw data
│
├── NetworkClient/                    ← SENDER — captures audio & sends via UDP
│   ├── NetworkClient.csproj          ← .NET 10 project file (uses NAudio 2.3.0)
│   └── Client.cs                     ← Captures loopback audio & streams over UDP
│
├── NetworkTest/                      ← RECEIVER — receives UDP audio & plays it
│   ├── NetworkTest.csproj            ← .NET 10 project file (uses NAudio 2.3.0)
│   └── Test.cs                       ← Receives UDP packets & plays audio via speakers
│
├── bin/Debug/net10.0/                ← Compiled output
│   ├── Wired Tooth.exe               ← Built executable
│   ├── Wired Tooth.dll               ← Main assembly
│   ├── NAudio.dll                    ← NAudio library
│   ├── NAudio.Core.dll               ← NAudio core
│   ├── NAudio.Wasapi.dll             ← NAudio WASAPI support
│   ├── NAudio.WinMM.dll              ← NAudio WinMM support
│   ├── NAudio.Asio.dll               ← NAudio ASIO support
│   └── NAudio.Midi.dll               ← NAudio MIDI support
│
└── obj/                              ← Build intermediary files (auto-generated)
```

---

## 📝 What Each File Does (Code Breakdown)

### 1. [wired.cs](file:///c:/Users/akash/Downloads/Wired%20Tooth/WiRED%20TOOTH/wired.cs) — Audio Inspector (Debug Tool)

**Purpose:** A diagnostic tool that captures your PC's system audio and prints raw audio data to the console so you can inspect the format.

**What it does step by step:**
1. Creates a `WasapiLoopbackCapture` — this hooks into Windows audio and captures whatever is playing on your speakers
2. Prints the audio format info (sample rate, channels, bits per sample, encoding)
3. When audio data arrives, it shows the first 3 buffers with:
   - Buffer size in bytes
   - Bytes per sample/frame
   - Number of frames
   - Duration in milliseconds
   - The first 5 frames with Left/Right channel float values
4. Waits for ENTER to stop

**Status:** ✅ **COMPLETE & WORKING** — This is a finished debug tool.

---

### 2. [Client.cs](file:///c:/Users/akash/Downloads/Wired%20Tooth/NetworkClient/Client.cs) — Audio Sender

**Purpose:** Captures system audio and streams it over UDP to a receiver.

**What it does step by step:**
1. Creates a `WasapiLoopbackCapture` to capture system audio
2. Opens a `UdpClient` for sending packets
3. When audio data arrives:
   - Builds a packet with a **12-byte custom header**:
     - Bytes 0-3: Sequence number (uint32) — to detect packet loss
     - Bytes 4-7: Timestamp in ms (uint32) — for timing
     - Bytes 8-11: Payload length (uint32) — size of audio data
   - Appends the raw audio bytes after the header
   - Sends the packet to `127.0.0.1:5000` (localhost)
4. Prints stats every 10 packets (packet count, total KB sent, time elapsed)
5. Waits for ENTER to stop

**Status:** ✅ **WORKING** — but only sends to localhost (`127.0.0.1`). Needs changes for real network use.

---

### 3. [Test.cs](file:///c:/Users/akash/Downloads/Wired%20Tooth/NetworkTest/Test.cs) — Audio Receiver

**Purpose:** Receives UDP audio packets and plays them through speakers in real-time.

**What it does step by step:**
1. Sets up audio playback with `WaveOutEvent` at 48000 Hz, 2 channels, 32-bit float
2. Creates a `BufferedWaveProvider` as a **jitter buffer** (500ms max)
3. Opens a `UdpClient` on port 5000 to listen for incoming packets
4. **Pre-buffering:** Waits until 60ms of audio is buffered before starting playback (prevents initial glitches)
5. For each received packet:
   - Parses the 12-byte header (sequence, timestamp, payload length)
   - Detects lost packets by checking sequence number gaps
   - Feeds audio data into the jitter buffer
   - Monitors for underruns (buffer emptied = audio gap)
6. Prints stats every 10 packets (packets received, lost, buffer level in ms, underruns)
7. On ENTER: stops and prints final stats (loss rate %)

**Status:** ✅ **WORKING** — receives and plays audio from the sender.

---

### 4. `AudioBridge/` — Empty Folder

**Purpose:** Placeholder — likely intended for a future component that bridges audio between devices.

**Status:** ❌ **EMPTY — NOT STARTED**

---

## ✅ What Has Been Done So Far

| Component | Status | Description |
|---|---|---|
| Audio Inspector (`wired.cs`) | ✅ Done | Captures & inspects raw audio format |
| UDP Sender (`Client.cs`) | ✅ Done | Captures audio, adds headers, sends via UDP |
| UDP Receiver (`Test.cs`) | ✅ Done | Receives packets, buffers, plays audio |
| Custom packet protocol | ✅ Done | 12-byte header with seq/timestamp/length |
| Jitter buffer | ✅ Done | 500ms buffer with overflow discard |
| Pre-buffering | ✅ Done | 60ms initial buffer before playback |
| Packet loss detection | ✅ Done | Sequence number gap tracking |
| Underrun detection | ✅ Done | Monitors when buffer empties |
| Localhost testing | ✅ Done | Works on `127.0.0.1:5000` |
| AudioBridge component | ❌ Not started | Empty folder |
| Real network streaming | ❌ Not done | Hardcoded to localhost |
| Audio compression | ❌ Not done | Sends raw PCM (high bandwidth) |
| UI/GUI | ❌ Not done | Console-only |
| Device discovery | ❌ Not done | Manual IP entry needed |
| Encryption/security | ❌ Not done | Audio sent in plain |

---

## ❌ What Still Needs To Be Done

### High Priority
1. **Real Network Support** — Change from `127.0.0.1` to configurable IP/discovery
2. **AudioBridge Component** — Build the bridge that connects sender & receiver logic
3. **Audio Compression** — Raw PCM at 48kHz/32-bit/stereo uses ~384 KB/s. Needs Opus or similar codec
4. **Error Handling** — No reconnection, no graceful failure

### Medium Priority
5. **Device Discovery** — Auto-find receivers on local network (mDNS/broadcast)
6. **Multi-device Support** — Stream to multiple receivers at once
7. **Volume Control** — Adjust volume on sender or receiver side
8. **Latency Optimization** — Tune buffer sizes, maybe use TCP fallback for reliability

### Low Priority
9. **GUI/UI** — Desktop app with controls instead of console
10. **Encryption** — Secure the audio stream
11. **Cross-platform Receiver** — Android/iOS receiver app

---

## 🔧 Tech Stack

| Technology | Version | Usage |
|---|---|---|
| C# | Latest | Programming language |
| .NET | 10.0 | Runtime framework |
| NAudio | 2.3.0 | Windows audio capture & playback |
| UDP (System.Net.Sockets) | Built-in | Network transport |
| WASAPI Loopback | via NAudio | System audio capture |

---

## 📋 Ready-To-Use Prompt for Antigravity

> [!IMPORTANT]
> Copy the prompt below and paste it into a new Antigravity conversation to continue building this project.

---

```
I have a C# .NET 10 project called "Wired Tooth" — a real-time audio streaming app.
It captures Windows system audio using NAudio's WasapiLoopbackCapture and streams it
over UDP to a receiver that plays it back.

Here is what ALREADY EXISTS and is WORKING:

PROJECT STRUCTURE:
- WiRED TOOTH/wired.cs — Audio inspector/debug tool (captures loopback audio, prints format info & raw samples)
- NetworkClient/Client.cs — SENDER: captures system audio, wraps it in a 12-byte header (seq/timestamp/length), sends via UDP to 127.0.0.1:5000
- NetworkTest/Test.cs — RECEIVER: listens on UDP port 5000, has a 500ms jitter buffer (BufferedWaveProvider), 60ms pre-buffer, plays audio via WaveOutEvent, tracks packet loss & underruns
- AudioBridge/ — EMPTY folder, not started yet

PACKET FORMAT (custom protocol):
- Bytes 0-3: uint32 sequence number
- Bytes 4-7: uint32 timestamp (ms)
- Bytes 8-11: uint32 payload length
- Bytes 12+: raw PCM audio data (48kHz, 2ch, 32-bit float)

AUDIO FORMAT:
- 48000 Hz sample rate
- 2 channels (stereo)
- 32-bit IEEE float
- Captured via WASAPI loopback

ALL PROJECTS USE:
- .NET 10.0
- NAudio 2.3.0 NuGet package
- Top-level statements (no Main method, no class wrappers)

WHAT I NEED YOU TO DO NEXT:
[INSERT YOUR SPECIFIC REQUEST HERE — for example:]
- "Build the AudioBridge component that allows streaming to a real network IP instead of localhost"
- "Add Opus audio compression to reduce bandwidth"
- "Create a WPF/WinUI GUI for the sender and receiver"
- "Add device discovery using UDP broadcast so receivers auto-find senders"
- "Make it work across my home network between two PCs"
- "Add volume control and audio device selection"

The workspace is at: c:\Users\akash\Downloads\Wired Tooth
```

---

## Open Questions

> [!IMPORTANT]
> Before I proceed with building anything, I need to know what you want to work on next:

1. **What is the AudioBridge supposed to do?** — Is it meant to replace the separate sender/receiver, or combine them into one unified app?
2. **Do you want real network streaming first** (between two PCs), or a **GUI** first?
3. **What device are you targeting as the receiver?** — Another Windows PC? An Android phone? Both?
4. **Do you want audio compression** (like Opus) to reduce bandwidth usage, or is raw PCM fine for now?

Please let me know which direction you want to go, and I'll build it!
