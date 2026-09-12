using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using WiredTooth.Protocol;

namespace WiredTooth.Sender;

/// <summary>A connected receiver, as reported to a UI.</summary>
public readonly record struct ClientInfo(IPAddress Address, int SecondsConnected,
                                         int MillisecondsSinceLastSeen);

/// <summary>Snapshot of what the sender knows. Cheap enough to poll at 1 Hz.</summary>
public sealed record SenderStatus
{
    public bool Running { get; init; }
    public int SampleRate { get; init; }
    public int Channels { get; init; }
    public string CaptureFormat { get; init; } = "not started";
    public int AudioPort { get; init; }
    public int ControlPort { get; init; }
    public long PacketsSent { get; init; }
    public long BytesSent { get; init; }
    public double BitrateKbps { get; init; }
    public int CaptureRestarts { get; init; }
    public IReadOnlyList<ClientInfo> Clients { get; init; } = [];
}

/// <summary>
/// The Wired Tooth sender: WASAPI loopback capture, WTP1 packetisation, UDP
/// unicast, the control channel, and the device-change handling from WP5.
///
/// Extracted from NetworkClient's top-level statements in WP6 so that the
/// console tool and the tray app are the same sender with two front-ends,
/// rather than two implementations that drift. The logic is unchanged; the
/// only substantive edit is that every Console.WriteLine became a Log event,
/// because a library must not assume a console exists -- under WPF there
/// isn't one, and writing to it silently does nothing.
/// </summary>
public sealed class AudioSender : IDisposable
{
    public const int AudioPort = 5000;
    public const int ControlPort = 5001;
    private const int ClientTimeoutMs = 3000;

    /// <summary>Human-readable progress. Raised from background threads.</summary>
    public event Action<string>? Log;

    /// <summary>A client connected or disconnected. Raised off the UI thread.</summary>
    public event Action? ClientsChanged;

    private readonly IPAddress? _forced;
    private readonly ConcurrentDictionary<IPAddress, ClientEntry> _clients = new();
    private readonly TargetSet _targets = new();
    private readonly Lock _captureGate = new();

    private UdpClient? _audioUdp;
    private UdpClient? _controlUdp;
    private CancellationTokenSource? _cts;
    private Task? _controlTask, _reaperTask, _silenceTask;
    private MMDeviceEnumerator? _deviceEnum;
    private DefaultRenderWatcher? _deviceWatcher;

    private WasapiLoopbackCapture? _capture;
    private int _sampleRate, _channels, _maxPayload;
    private string _captureFormat = "not started";

    private int _sequenceNumber;
    private long _totalBytesSent;
    private int _packetsSent;
    private long _lastAudioTicks = Environment.TickCount64;
    private int _restarts;
    private uint _controlSeq;

    private byte[] _pcm16 = [];
    private readonly byte[] _packet = new byte[WtpPacket.MaxDatagramSize];

    private long _lastBytesSample;
    private long _lastBytesSampleTicks = Environment.TickCount64;
    private double _bitrateKbps;

    public bool IsRunning { get; private set; }

    /// <param name="forcedTarget">
    /// BUILD_PLAN WP2's debug fallback: stream to this address unconditionally,
    /// with no handshake and no timeout, so loopback testing works with no
    /// control channel at all.
    /// </param>
    public AudioSender(IPAddress? forcedTarget = null) => _forced = forcedTarget;

    public void Start()
    {
        if (IsRunning) return;

        _cts = new CancellationTokenSource();
        _audioUdp = new UdpClient();
        _controlUdp = new UdpClient(ControlPort);

        RebuildTargets();

        if (_forced is not null)
            Log?.Invoke($"Debug target : {_forced}:{AudioPort} (unconditional)");
        Log?.Invoke($"Control      : listening on UDP {ControlPort}");

        _controlTask = Task.Run(() => ControlLoop(_cts.Token));
        _reaperTask = Task.Run(() => ReaperLoop(_cts.Token));
        _silenceTask = Task.Run(() => SilenceLoop(_cts.Token));

        StartCapture();

        // WasapiLoopbackCapture binds one device at construction, so without
        // this the sender keeps capturing a device the user stopped listening
        // to and the stream goes silent with no error anywhere.
        _deviceEnum = new MMDeviceEnumerator();
        _deviceWatcher = new DefaultRenderWatcher(
            _ => RestartCapture("default render device changed"));
        _deviceEnum.RegisterEndpointNotificationCallback(_deviceWatcher);

        IsRunning = true;
    }

    public void Stop()
    {
        if (!IsRunning) return;
        IsRunning = false;

        try
        {
            if (_deviceEnum is not null && _deviceWatcher is not null)
                _deviceEnum.UnregisterEndpointNotificationCallback(_deviceWatcher);
            _capture?.StopRecording();
        }
        catch (Exception) { }

        // Tell every client we are going away, so they show "reconnecting" at
        // once instead of after their own audio timeout.
        if (_clients.Count > 0 && _controlUdp is not null)
        {
            byte[] bye = new byte[WtpPacket.CommonHeaderSize];
            int byeLen = WtpPacket.WriteControl(
                bye, PacketType.Bye,
                (uint)Interlocked.Increment(ref _sequenceNumber),
                WtpPacket.TimestampMicroseconds);
            foreach (var entry in _clients.Values)
            {
                try { _controlUdp.Send(bye, byeLen, entry.ControlEndpoint); }
                catch (SocketException) { }
            }
            Log?.Invoke($"Sent BYE to {_clients.Count} client(s).");
        }

        _cts?.Cancel();
        _controlUdp?.Close();
        try { _controlTask?.Wait(2000); } catch { }
        try { _reaperTask?.Wait(2000); } catch { }
        try { _silenceTask?.Wait(2000); } catch { }

        _capture?.Dispose();
        _capture = null;
        _audioUdp?.Dispose();
        _audioUdp = null;
        _controlUdp?.Dispose();
        _controlUdp = null;

        _clients.Clear();
        RebuildTargets();
        ClientsChanged?.Invoke();

        Log?.Invoke($"Stopped. Total packets sent: {_sequenceNumber}");
        Log?.Invoke($"Total data sent: {_totalBytesSent / 1024} KB");
        Log?.Invoke($"Capture restarts: {_restarts}  (default render device changes)");
    }

    public SenderStatus GetStatus()
    {
        long now = Environment.TickCount64;

        // Bitrate is derived from the byte counter rather than tracked
        // continuously, so the audio path stays free of timing work.
        long elapsed = now - _lastBytesSampleTicks;
        if (elapsed >= 500)
        {
            long delta = Interlocked.Read(ref _totalBytesSent) - _lastBytesSample;
            _bitrateKbps = delta * 8.0 / elapsed;          // bytes/ms -> kbit/s
            _lastBytesSample = Interlocked.Read(ref _totalBytesSent);
            _lastBytesSampleTicks = now;
        }

        var clients = _clients
            .Select(kv => new ClientInfo(kv.Key,
                                         (int)((now - kv.Value.ConnectedMs) / 1000),
                                         (int)(now - kv.Value.LastSeenMs)))
            .OrderBy(c => c.Address.ToString())
            .ToList();

        return new SenderStatus
        {
            Running = IsRunning,
            SampleRate = _sampleRate,
            Channels = _channels,
            CaptureFormat = _captureFormat,
            AudioPort = AudioPort,
            ControlPort = ControlPort,
            PacketsSent = _sequenceNumber,
            BytesSent = Interlocked.Read(ref _totalBytesSent),
            BitrateKbps = _bitrateKbps,
            CaptureRestarts = _restarts,
            Clients = clients,
        };
    }

    // ---- targets ---------------------------------------------------------
    private void RebuildTargets()
    {
        var list = new List<IPEndPoint>();
        if (_forced is not null)
            list.Add(new IPEndPoint(_forced, AudioPort));
        foreach (var addr in _clients.Keys)
        {
            if (_forced is not null && addr.Equals(_forced))
                continue;                       // already added, do not double-send
            list.Add(new IPEndPoint(addr, AudioPort));
        }
        // Published as one immutable array so the audio thread never sees a
        // half-updated list and never takes a lock on the hot path.
        _targets.Current = list.ToArray();
    }

    // ---- control channel -------------------------------------------------
    private async Task ControlLoop(CancellationToken token)
    {
        // Sized for the largest control packet we send: PONG, which carries an
        // 8-byte echoed timestamp after the common header.
        byte[] reply = new byte[WtpPacket.CommonHeaderSize + sizeof(long)];
        try
        {
            while (!token.IsCancellationRequested)
            {
                var result = await _controlUdp!.ReceiveAsync(token);

                // Same rule as the audio path: anything malformed is dropped
                // silently. A control socket is just as reachable as an audio one.
                if (!WtpPacket.TryParse(result.Buffer, out var header, out _))
                    continue;

                IPAddress addr = result.RemoteEndPoint.Address;

                switch (header.Type)
                {
                    case PacketType.Hello:
                    {
                        bool isNew = !_clients.ContainsKey(addr);
                        _clients[addr] = _clients.TryGetValue(addr, out var prev)
                            ? prev with { LastSeenMs = Environment.TickCount64 }
                            : new ClientEntry(result.RemoteEndPoint,
                                              Environment.TickCount64,
                                              Environment.TickCount64);
                        if (isNew)
                        {
                            RebuildTargets();
                            Log?.Invoke($"Client connected: {addr}");
                            ClientsChanged?.Invoke();
                        }

                        int len = WtpPacket.WriteControl(
                            reply, PacketType.HelloAck, _controlSeq++,
                            WtpPacket.TimestampMicroseconds);
                        await _controlUdp.SendAsync(reply.AsMemory(0, len),
                                                    result.RemoteEndPoint, token);
                        break;
                    }

                    case PacketType.Ping:
                    {
                        // Echo the probe's own timestamp straight back in the
                        // payload. The two clocks share no origin, so the sender
                        // cannot compute anything useful from it -- the receiver
                        // subtracts, against its own clock. A PING also proves
                        // liveness, so it counts as a keepalive.
                        if (_clients.TryGetValue(addr, out var existing))
                            _clients[addr] = existing with
                            {
                                LastSeenMs = Environment.TickCount64
                            };

                        byte[] echo = new byte[sizeof(long)];
                        System.Buffers.Binary.BinaryPrimitives
                            .WriteInt64LittleEndian(echo, header.TimestampMicroseconds);

                        int plen = WtpPacket.WriteControl(
                            reply, PacketType.Pong, _controlSeq++,
                            WtpPacket.TimestampMicroseconds, echo);
                        await _controlUdp.SendAsync(reply.AsMemory(0, plen),
                                                    result.RemoteEndPoint, token);
                        break;
                    }

                    case PacketType.Bye:
                        if (_clients.TryRemove(addr, out _))
                        {
                            RebuildTargets();
                            Log?.Invoke($"Client disconnected (bye): {addr}");
                            ClientsChanged?.Invoke();
                        }
                        break;
                }
            }
        }
        catch (OperationCanceledException) { }
        catch (SocketException) { }              // socket closed during shutdown
        catch (ObjectDisposedException) { }
    }

    // ---- liveness reaper -------------------------------------------------
    private async Task ReaperLoop(CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                await Task.Delay(500, token);
                long now = Environment.TickCount64;
                foreach (var kv in _clients)
                {
                    if (now - kv.Value.LastSeenMs <= ClientTimeoutMs)
                        continue;
                    if (_clients.TryRemove(kv.Key, out _))
                    {
                        RebuildTargets();
                        Log?.Invoke($"Client disconnected (timeout): {kv.Key}");
                        ClientsChanged?.Invoke();
                    }
                }
            }
        }
        catch (OperationCanceledException) { }
    }

    // ---- silence keepalive -----------------------------------------------
    // WASAPI loopback does not fire DataAvailable at all while the PC is silent
    // -- it does not deliver buffers of zeros, it simply stops. Without this the
    // receiver's buffer drains to empty during any quiet passage, underruns, and
    // then has to refill from scratch when audio returns. These packets carry no
    // payload; the receiver expands each into WtpPacket.SilenceKeepaliveMs of
    // silence, which is a wire contract both ends share.
    private async Task SilenceLoop(CancellationToken token)
    {
        byte[] silence = new byte[WtpPacket.AudioHeaderSize];
        try
        {
            while (!token.IsCancellationRequested)
            {
                await Task.Delay(WtpPacket.SilenceKeepaliveMs / 2, token);

                var current = _targets.Current;
                if (current.Length == 0 || _sampleRate == 0)
                    continue;
                if (Environment.TickCount64 - Volatile.Read(ref _lastAudioTicks)
                    < WtpPacket.SilenceKeepaliveMs)
                    continue;

                int len = WtpPacket.WriteAudioHeader(
                    silence, (uint)Interlocked.Increment(ref _sequenceNumber),
                    WtpPacket.TimestampMicroseconds,
                    payloadLength: 0, _sampleRate, _channels, 16,
                    PacketFlags.Silence);

                foreach (var target in current)
                {
                    try { _audioUdp?.Send(silence, len, target); }
                    catch (SocketException) { }
                    catch (ObjectDisposedException) { }
                }

                Volatile.Write(ref _lastAudioTicks, Environment.TickCount64);
            }
        }
        catch (OperationCanceledException) { }
    }

    // ---- audio -----------------------------------------------------------
    private void OnData(object? sender, WaveInEventArgs e)
    {
        if (e.BytesRecorded == 0)
            return;

        // The point of the handshake: with nobody listening we do not encode
        // and do not transmit.
        var current = _targets.Current;
        if (current.Length == 0)
            return;

        Volatile.Write(ref _lastAudioTicks, Environment.TickCount64);

        int pcmBytes = Pcm.FloatToInt16(e.Buffer.AsSpan(0, e.BytesRecorded), _pcm16);

        // One WASAPI buffer is typically several KB, which is well over the
        // datagram ceiling, so it is split rather than sent whole.
        for (int offset = 0; offset < pcmBytes; offset += _maxPayload)
        {
            int chunk = Math.Min(_maxPayload, pcmBytes - offset);

            int headerLen = WtpPacket.WriteAudioHeader(
                _packet, (uint)Interlocked.Increment(ref _sequenceNumber),
                WtpPacket.TimestampMicroseconds,
                chunk, _sampleRate, _channels, 16);

            _pcm16.AsSpan(offset, chunk).CopyTo(_packet.AsSpan(headerLen));

            foreach (var target in current)
            {
                try
                {
                    _audioUdp?.Send(_packet, headerLen + chunk, target);
                }
                catch (SocketException)
                {
                    // An ICMP port-unreachable from a client that just died
                    // surfaces here. Dropping this datagram is correct; the
                    // reaper removes the client three seconds later.
                }
                catch (ObjectDisposedException) { return; }
            }

            Interlocked.Add(ref _totalBytesSent,
                            (long)(headerLen + chunk) * current.Length);
            _packetsSent++;
        }
    }

    // ---- capture lifecycle ------------------------------------------------
    private void StartCapture()
    {
        lock (_captureGate)
        {
            _capture = new WasapiLoopbackCapture();
            _sampleRate = _capture.WaveFormat.SampleRate;
            _channels = _capture.WaveFormat.Channels;

            // Payload must be a whole number of frames. A datagram that splits
            // a frame would leave the receiver permanently half a sample out of
            // alignment, which swaps left and right and sounds like noise.
            int frameSize = Pcm.Int16FrameSize(_channels);
            int framesPerPacket = WtpPacket.MaxAudioPayload / frameSize;
            _maxPayload = framesPerPacket * frameSize;

            int need = _capture.WaveFormat.AverageBytesPerSecond;
            if (_pcm16.Length < need) _pcm16 = new byte[need];

            _capture.DataAvailable += OnData;
            _capture.StartRecording();

            _captureFormat = $"{_sampleRate} Hz, {_channels} ch, " +
                             $"{_capture.WaveFormat.BitsPerSample}-bit " +
                             $"{_capture.WaveFormat.Encoding}";

            Log?.Invoke($"Capture: {_captureFormat}");
            Log?.Invoke($"Wire   : {_sampleRate} Hz, {_channels} ch, 16-bit PCM (codec 0)");
            Log?.Invoke($"Packet : {WtpPacket.AudioHeaderSize} B header + up to " +
                        $"{_maxPayload} B payload = " +
                        $"{WtpPacket.AudioHeaderSize + _maxPayload} B " +
                        $"({framesPerPacket} frames, " +
                        $"{framesPerPacket * 1000.0 / _sampleRate:F2} ms)");
        }
    }

    /// <summary>
    /// WP5 case 2. Called from a COM notification thread, so the work happens
    /// on a worker rather than blocking the audio subsystem's callback.
    /// </summary>
    private void RestartCapture(string why)
    {
        Task.Run(() =>
        {
            lock (_captureGate)
            {
                Log?.Invoke($"[device] {why} - restarting capture");
                try
                {
                    if (_capture is not null)
                    {
                        _capture.DataAvailable -= OnData;
                        _capture.StopRecording();
                        _capture.Dispose();
                    }
                }
                catch (Exception ex)
                {
                    Log?.Invoke($"[device] teardown: {ex.GetType().Name}");
                }
                _capture = null;
            }

            // Windows reports the default-device change before the new endpoint
            // is reliably openable; opening immediately throws or yields the old
            // device. Retry rather than give up, because failing here leaves the
            // stream dead for good while the client stays happily connected.
            for (int attempt = 1; attempt <= 10; attempt++)
            {
                try
                {
                    StartCapture();
                    Interlocked.Increment(ref _restarts);
                    Log?.Invoke($"[device] capture restarted (attempt {attempt})");
                    return;
                }
                catch (Exception ex)
                {
                    Log?.Invoke($"[device] attempt {attempt} failed: " +
                                $"{ex.GetType().Name}, retrying");
                    Thread.Sleep(300);
                }
            }
            Log?.Invoke("[device] FAILED to restart capture after 10 attempts");
        });
    }

    public void Dispose()
    {
        Stop();
        _cts?.Dispose();
    }

    /// <summary>
    /// A connected receiver. ControlEndpoint keeps the ephemeral port that
    /// HELLO arrived from, so HELLO_ACK goes back to the right socket; audio
    /// goes to the same address on the fixed audio port instead.
    /// </summary>
    private sealed record ClientEntry(IPEndPoint ControlEndpoint,
                                      long LastSeenMs, long ConnectedMs);

    /// <summary>
    /// Single mutable reference to an immutable array. The audio callback reads
    /// one reference; the control and reaper threads publish a whole new array.
    /// Avoids locking on the audio path.
    /// </summary>
    private sealed class TargetSet
    {
        public volatile IPEndPoint[] Current = [];
    }
}
