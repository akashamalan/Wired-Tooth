using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using NAudio.Wave;
using WiredTooth.Protocol;

Console.WriteLine("=== WIRED TOOTH - SENDER ===");
Console.WriteLine("Play audio on your laptop.");
Console.WriteLine("Press ENTER to stop streaming.");
Console.WriteLine();

using var capture = new WasapiLoopbackCapture();

int sampleRate = capture.WaveFormat.SampleRate;
int channels = capture.WaveFormat.Channels;

Console.WriteLine($"Capture: {sampleRate} Hz, {channels} ch, " +
    $"{capture.WaveFormat.BitsPerSample}-bit {capture.WaveFormat.Encoding}");
Console.WriteLine($"Wire   : {sampleRate} Hz, {channels} ch, 16-bit PCM (codec 0)");

// Payload must be a whole number of frames. A datagram that splits a frame
// would put the receiver permanently half a sample out of alignment, which
// swaps left and right and sounds like noise.
int frameSize = Pcm.Int16FrameSize(channels);
int framesPerPacket = WtpPacket.MaxAudioPayload / frameSize;
int maxPayload = framesPerPacket * frameSize;

Console.WriteLine($"Packet : {WtpPacket.AudioHeaderSize} B header + up to " +
    $"{maxPayload} B payload = {WtpPacket.AudioHeaderSize + maxPayload} B " +
    $"({framesPerPacket} frames, " +
    $"{framesPerPacket * 1000.0 / sampleRate:F2} ms)");

const int AudioPort = 5000;
const int ControlPort = 5001;
const int ClientTimeoutMs = 3000;

using UdpClient audioUdp = new UdpClient();

// The sender owns the well-known control port. The receiver initiates, which
// is what lets a phone connect without the PC knowing its address in advance:
// the client's address is learned from the source of its HELLO.
using UdpClient controlUdp = new UdpClient(ControlPort);

// Keyed by address, not endpoint: a client's control socket is on an ephemeral
// port, but its audio always goes to AudioPort. One entry per host.
var clients = new ConcurrentDictionary<IPAddress, ClientEntry>();

// Debug fallback required by BUILD_PLAN WP2: an explicit IP streams
// unconditionally, with no handshake and no timeout, so the loopback
// regression test still works with no receiver control channel at all.
IPAddress? forced = null;
if (args.Length > 0)
{
    if (IPAddress.TryParse(args[0], out var parsed))
        forced = parsed;
    else
        Console.WriteLine($"Ignoring argument '{args[0]}': not an IP address");
}

var targets = new TargetSet();

void RebuildTargets()
{
    var list = new List<IPEndPoint>();
    if (forced is not null)
        list.Add(new IPEndPoint(forced, AudioPort));
    foreach (var addr in clients.Keys)
    {
        if (forced is not null && addr.Equals(forced))
            continue;                       // already added, do not double-send
        list.Add(new IPEndPoint(addr, AudioPort));
    }
    // Published as one immutable array so the audio thread never sees a
    // half-updated list and never takes a lock on the hot path.
    targets.Current = list.ToArray();
}

RebuildTargets();

if (forced is not null)
    Console.WriteLine($"Debug target : {forced}:{AudioPort} (unconditional)");
Console.WriteLine($"Control      : listening on UDP {ControlPort}");
Console.WriteLine();
Console.WriteLine("Waiting for a client to say HELLO...");
Console.WriteLine();

var cts = new CancellationTokenSource();
uint controlSeq = 0;

// ---- control channel -------------------------------------------------
var controlTask = Task.Run(async () =>
{
    byte[] reply = new byte[WtpPacket.CommonHeaderSize];
    try
    {
        while (!cts.Token.IsCancellationRequested)
        {
            var result = await controlUdp.ReceiveAsync(cts.Token);

            // Same rule as the audio path: anything malformed is dropped
            // silently. A control socket is just as reachable as an audio one.
            if (!WtpPacket.TryParse(result.Buffer, out var header, out _))
                continue;

            IPAddress addr = result.RemoteEndPoint.Address;

            switch (header.Type)
            {
                case PacketType.Hello:
                {
                    bool isNew = !clients.ContainsKey(addr);
                    clients[addr] = new ClientEntry(result.RemoteEndPoint,
                                                    Environment.TickCount64);
                    if (isNew)
                    {
                        RebuildTargets();
                        Console.WriteLine($"Client connected: {addr}");
                    }

                    int len = WtpPacket.WriteControl(
                        reply, PacketType.HelloAck, controlSeq++,
                        WtpPacket.TimestampMicroseconds);
                    await controlUdp.SendAsync(reply.AsMemory(0, len),
                                               result.RemoteEndPoint,
                                               cts.Token);
                    break;
                }

                case PacketType.Bye:
                    if (clients.TryRemove(addr, out _))
                    {
                        RebuildTargets();
                        Console.WriteLine($"Client disconnected (bye): {addr}");
                    }
                    break;

                // Ping/Pong arrive in WP3. Ignored rather than rejected so an
                // early receiver probing for RTT does not look like an error.
            }
        }
    }
    catch (OperationCanceledException) { }
    catch (SocketException) { }              // socket closed during shutdown
});

// ---- liveness reaper -------------------------------------------------
var reaperTask = Task.Run(async () =>
{
    try
    {
        while (!cts.Token.IsCancellationRequested)
        {
            await Task.Delay(500, cts.Token);
            long now = Environment.TickCount64;
            foreach (var kv in clients)
            {
                if (now - kv.Value.LastSeenMs <= ClientTimeoutMs)
                    continue;
                if (clients.TryRemove(kv.Key, out _))
                {
                    RebuildTargets();
                    Console.WriteLine($"Client disconnected (timeout): {kv.Key}");
                }
            }
        }
    }
    catch (OperationCanceledException) { }
});

// ---- audio -----------------------------------------------------------
uint sequenceNumber = 0;
long totalBytesSent = 0;
int packetsSent = 0;

// Reused across callbacks. DataAvailable fires on one capture thread, so a
// single scratch buffer per process is safe and keeps this allocation-free
// on the audio path.
byte[] pcm16 = new byte[capture.WaveFormat.AverageBytesPerSecond];
byte[] packet = new byte[WtpPacket.MaxDatagramSize];

capture.DataAvailable += (sender, e) =>
{
    if (e.BytesRecorded == 0)
        return;

    // The point of the handshake: with nobody listening we do not encode and
    // do not transmit. Previously this fired packets into the void forever.
    var current = targets.Current;
    if (current.Length == 0)
        return;

    int pcmBytes = Pcm.FloatToInt16(e.Buffer.AsSpan(0, e.BytesRecorded), pcm16);

    // One WASAPI buffer is typically several KB, which is well over the
    // datagram ceiling, so it is split rather than sent whole.
    for (int offset = 0; offset < pcmBytes; offset += maxPayload)
    {
        int chunk = Math.Min(maxPayload, pcmBytes - offset);

        int headerLen = WtpPacket.WriteAudioHeader(
            packet, sequenceNumber, WtpPacket.TimestampMicroseconds,
            chunk, sampleRate, channels, 16);

        pcm16.AsSpan(offset, chunk).CopyTo(packet.AsSpan(headerLen));

        foreach (var target in current)
        {
            try
            {
                audioUdp.Send(packet, headerLen + chunk, target);
            }
            catch (SocketException)
            {
                // An ICMP port-unreachable from a client that just died
                // surfaces here. Dropping this datagram is correct; the
                // reaper removes the client three seconds later.
            }
        }

        totalBytesSent += (long)(headerLen + chunk) * current.Length;
        sequenceNumber++;
        packetsSent++;
    }

    if (packetsSent % 100 == 0)
    {
        Console.WriteLine(
            $"Streaming... packets: {sequenceNumber} | " +
            $"clients: {current.Length} | " +
            $"sent: {totalBytesSent / 1024} KB | " +
            $"time: {WtpPacket.TimestampMicroseconds / 1_000_000}s");
    }
};

capture.StartRecording();

Console.ReadLine();

capture.StopRecording();
cts.Cancel();
controlUdp.Close();
try { await controlTask; } catch { }
try { await reaperTask; } catch { }

Console.WriteLine();
Console.WriteLine($"Stopped. Total packets sent: {sequenceNumber}");
Console.WriteLine($"Total data sent: {totalBytesSent / 1024} KB");

/// <summary>
/// A connected receiver. ControlEndpoint keeps the ephemeral port that HELLO
/// arrived from, so HELLO_ACK goes back to the right socket; audio is sent to
/// the same address on the fixed audio port instead.
/// </summary>
sealed record ClientEntry(IPEndPoint ControlEndpoint, long LastSeenMs);

/// <summary>
/// Single mutable reference to an immutable array. The audio callback reads
/// one reference; the control and reaper threads publish a whole new array.
/// Avoids locking on the audio path.
/// </summary>
sealed class TargetSet
{
    public volatile IPEndPoint[] Current = [];
}
