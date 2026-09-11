using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using NAudio.CoreAudioApi;
using NAudio.CoreAudioApi.Interfaces;
using NAudio.Wave;
using WiredTooth.Protocol;
using WiredTooth.Sender;

Console.WriteLine("=== WIRED TOOTH - SENDER ===");
Console.WriteLine("Play audio on your laptop.");
Console.WriteLine("Press ENTER to stop streaming.");
Console.WriteLine();

// Capture is rebuilt when the default render device changes (WP5 case 2), so
// it cannot be a `using` declaration and its format cannot be captured once
// into constants -- a new device may well have a different rate or channel
// count, and the audio sub-header has to carry the new values.
WasapiLoopbackCapture? capture = null;
int sampleRate = 0;
int channels = 0;
int maxPayload = 0;

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
    // Sized for the largest control packet we send: PONG, which carries an
    // 8-byte echoed timestamp after the common header.
    byte[] reply = new byte[WtpPacket.CommonHeaderSize + sizeof(long)];
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

                case PacketType.Ping:
                {
                    // Echo the probe's own timestamp straight back in the
                    // payload. The two processes' clocks share no origin, so
                    // the sender cannot compute anything useful from it -- it
                    // is the receiver that subtracts, against its own clock.
                    // A PING also proves liveness, so it counts as a keepalive.
                    if (clients.TryGetValue(addr, out var existing))
                        clients[addr] = existing with
                        {
                            LastSeenMs = Environment.TickCount64
                        };

                    Span<byte> echo = stackalloc byte[sizeof(long)];
                    System.Buffers.Binary.BinaryPrimitives
                        .WriteInt64LittleEndian(echo, header.TimestampMicroseconds);

                    int plen = WtpPacket.WriteControl(
                        reply, PacketType.Pong, controlSeq++,
                        WtpPacket.TimestampMicroseconds, echo);
                    await controlUdp.SendAsync(reply.AsMemory(0, plen),
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
// int, not uint, because the silence keepalive timer now emits packets from a
// second thread and Interlocked has no uint overload. Wrapping at 2^31 instead
// of 2^32 costs nothing: at ~140 packets/s that is 177 days of streaming.
int sequenceNumber = 0;
long totalBytesSent = 0;
int packetsSent = 0;
long lastAudioTicks = Environment.TickCount64;

// Reused across callbacks. DataAvailable fires on one capture thread, so a
// single scratch buffer per process is safe and keeps this allocation-free
// on the audio path.
byte[] pcm16 = [];
byte[] packet = new byte[WtpPacket.MaxDatagramSize];

void OnData(object? sender, WaveInEventArgs e)
{
    if (e.BytesRecorded == 0)
        return;

    // The point of the handshake: with nobody listening we do not encode and
    // do not transmit. Previously this fired packets into the void forever.
    var current = targets.Current;
    if (current.Length == 0)
        return;

    Volatile.Write(ref lastAudioTicks, Environment.TickCount64);

    int pcmBytes = Pcm.FloatToInt16(e.Buffer.AsSpan(0, e.BytesRecorded), pcm16);

    // One WASAPI buffer is typically several KB, which is well over the
    // datagram ceiling, so it is split rather than sent whole.
    for (int offset = 0; offset < pcmBytes; offset += maxPayload)
    {
        int chunk = Math.Min(maxPayload, pcmBytes - offset);

        int headerLen = WtpPacket.WriteAudioHeader(
            packet, (uint)Interlocked.Increment(ref sequenceNumber),
            WtpPacket.TimestampMicroseconds,
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
}

// ---- silence keepalive -----------------------------------------------
// WASAPI loopback does not fire DataAvailable at all while the PC is silent
// -- it does not deliver buffers of zeros, it simply stops. Without this the
// receiver's buffer drains to empty during any quiet passage, underruns, and
// then has to refill from scratch when audio returns. These packets carry no
// payload; the receiver expands each into WtpPacket.SilenceKeepaliveMs of
// silence, which is a wire contract both ends share.
var silenceTask = Task.Run(async () =>
{
    byte[] silence = new byte[WtpPacket.AudioHeaderSize];
    try
    {
        while (!cts.Token.IsCancellationRequested)
        {
            await Task.Delay(WtpPacket.SilenceKeepaliveMs / 2, cts.Token);

            var current = targets.Current;
            if (current.Length == 0)
                continue;
            if (Environment.TickCount64 - Volatile.Read(ref lastAudioTicks)
                < WtpPacket.SilenceKeepaliveMs)
                continue;

            int len = WtpPacket.WriteAudioHeader(
                silence, (uint)Interlocked.Increment(ref sequenceNumber),
                WtpPacket.TimestampMicroseconds,
                payloadLength: 0, sampleRate, channels, 16,
                PacketFlags.Silence);

            foreach (var target in current)
            {
                try { audioUdp.Send(silence, len, target); }
                catch (SocketException) { }
            }

            Volatile.Write(ref lastAudioTicks, Environment.TickCount64);
        }
    }
    catch (OperationCanceledException) { }
});

// ---- capture lifecycle ------------------------------------------------
var captureGate = new Lock();

void StartCapture()
{
    lock (captureGate)
    {
        capture = new WasapiLoopbackCapture();
        sampleRate = capture.WaveFormat.SampleRate;
        channels = capture.WaveFormat.Channels;

        // Payload must be a whole number of frames. A datagram that splits a
        // frame would leave the receiver permanently half a sample out of
        // alignment, which swaps left and right and sounds like noise.
        int frameSize = Pcm.Int16FrameSize(channels);
        int framesPerPacket = WtpPacket.MaxAudioPayload / frameSize;
        maxPayload = framesPerPacket * frameSize;

        int need = capture.WaveFormat.AverageBytesPerSecond;
        if (pcm16.Length < need) pcm16 = new byte[need];

        capture.DataAvailable += OnData;
        capture.StartRecording();

        Console.WriteLine($"Capture: {sampleRate} Hz, {channels} ch, " +
            $"{capture.WaveFormat.BitsPerSample}-bit {capture.WaveFormat.Encoding}");
        Console.WriteLine($"Wire   : {sampleRate} Hz, {channels} ch, 16-bit PCM (codec 0)");
        Console.WriteLine($"Packet : {WtpPacket.AudioHeaderSize} B header + up to " +
            $"{maxPayload} B payload = {WtpPacket.AudioHeaderSize + maxPayload} B " +
            $"({framesPerPacket} frames, " +
            $"{framesPerPacket * 1000.0 / sampleRate:F2} ms)");
    }
}

// WP5 case 2. Called from a COM notification thread, so it does the work on a
// worker rather than blocking the audio subsystem's callback.
int restarts = 0;
void RestartCapture(string why)
{
    Task.Run(() =>
    {
        lock (captureGate)
        {
            Console.WriteLine();
            Console.WriteLine($"[device] {why} - restarting capture");
            try
            {
                if (capture is not null)
                {
                    capture.DataAvailable -= OnData;
                    capture.StopRecording();
                    capture.Dispose();
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[device] teardown: {ex.GetType().Name}");
            }
            capture = null;
        }

        // Windows reports the default-device change before the new endpoint is
        // reliably openable; opening immediately throws or yields the old
        // device. Retry rather than give up, because failing here means the
        // stream is dead for good while the client stays happily connected.
        for (int attempt = 1; attempt <= 10; attempt++)
        {
            try
            {
                StartCapture();
                Interlocked.Increment(ref restarts);
                Console.WriteLine($"[device] capture restarted (attempt {attempt})");
                return;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[device] attempt {attempt} failed: " +
                                  $"{ex.GetType().Name}, retrying");
                Thread.Sleep(300);
            }
        }
        Console.WriteLine("[device] FAILED to restart capture after 10 attempts");
    });
}

StartCapture();

// Watch for the default render endpoint changing. WasapiLoopbackCapture binds
// one device at construction, so without this the sender keeps capturing a
// device the user has stopped listening to and the stream goes silent with no
// error anywhere.
var deviceEnum = new MMDeviceEnumerator();
var deviceWatcher = new DefaultRenderWatcher(id => RestartCapture($"default render device changed"));
deviceEnum.RegisterEndpointNotificationCallback(deviceWatcher);

// WP5 case 4: ENTER and Ctrl-C must run the SAME shutdown path. Ctrl-C used
// to kill the process outright, which skipped the BYE and left every
// connected client waiting out the full 3s timeout. e.Cancel=true takes the
// termination back so the cleanup below actually runs.
var stopping = new TaskCompletionSource();
Console.CancelKeyPress += (_, e) =>
{
    e.Cancel = true;
    Console.WriteLine();
    Console.WriteLine("Ctrl-C: shutting down...");
    stopping.TrySetResult();
};
_ = Task.Run(() =>
{
    // Returns immediately on EOF when stdin is not a console, which is how
    // the scripted test runs drive shutdown.
    Console.ReadLine();
    stopping.TrySetResult();
});
await stopping.Task;

try
{
    deviceEnum.UnregisterEndpointNotificationCallback(deviceWatcher);
    capture?.StopRecording();
}
catch (Exception) { }

// Tell every client we are going away, so they show "reconnecting" at once
// instead of after their own audio timeout.
var leaving = targets.Current;
if (leaving.Length > 0)
{
    byte[] bye = new byte[WtpPacket.CommonHeaderSize];
    int byeLen = WtpPacket.WriteControl(bye, PacketType.Bye,
                                        (uint)Interlocked.Increment(ref sequenceNumber),
                                        WtpPacket.TimestampMicroseconds);
    foreach (var entry in clients.Values)
    {
        try { controlUdp.Send(bye, byeLen, entry.ControlEndpoint); }
        catch (SocketException) { }
    }
    Console.WriteLine($"Sent BYE to {clients.Count} client(s).");
}

cts.Cancel();
controlUdp.Close();
try { await controlTask; } catch { }
try { await reaperTask; } catch { }
try { await silenceTask; } catch { }

Console.WriteLine();
Console.WriteLine($"Stopped. Total packets sent: {sequenceNumber}");
Console.WriteLine($"Total data sent: {totalBytesSent / 1024} KB");
Console.WriteLine($"Capture restarts: {restarts}  (default render device changes)");
capture?.Dispose();

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
