using System.Buffers.Binary;
using System.Globalization;
using System.Net;
using System.Net.Sockets;
using NAudio.Wave;
using WiredTooth.Protocol;

Console.WriteLine("=== WIRED TOOTH - RECEIVER ===");
Console.WriteLine();

const int AudioPort = 5000;
const int ControlPort = 5001;
const int KeepaliveMs = 1000;
const int PingIntervalMs = 500;
const int RttWindow = 20;

string senderArg = args.Length > 0 ? args[0] : "127.0.0.1";
if (!IPAddress.TryParse(senderArg, out var senderAddress))
{
    Console.WriteLine($"'{senderArg}' is not an IP address. Usage: " +
                      $"dotnet run --project NetworkTest -- <sender-ip>");
    return;
}
var senderControl = new IPEndPoint(senderAddress, ControlPort);

// Playback is built lazily from the first valid AUDIO packet rather than
// hardcoded. The sender stamps its real format into every packet, so the
// receiver never has to assume 48kHz stereo and cannot be silently wrong
// when the capture device differs.
BufferedWaveProvider? buffer = null;
WaveOutEvent? waveOut = null;
WaveFormat? format = null;

int preBufferBytes = 0;
bool playbackStarted = false;

using UdpClient audioUdp = new UdpClient(AudioPort);

// Ephemeral local port. The receiver initiates, so it does not need a
// well-known control port of its own; the sender replies to whatever source
// port the HELLO came from.
using UdpClient controlUdp = new UdpClient(0);

string csvPath = $"metrics_{DateTime.Now:yyyyMMdd_HHmmss}.csv";

Console.WriteLine($"Audio  : listening on UDP {AudioPort}");
Console.WriteLine($"Control: HELLO every {KeepaliveMs} ms, PING every " +
                  $"{PingIntervalMs} ms, to {senderControl}");
Console.WriteLine($"Metrics: {csvPath}");
Console.WriteLine("Press ENTER to stop.");
Console.WriteLine();

uint lastSeq = 0;
bool haveSeq = false;
int packetsReceived = 0;
int packetsLost = 0;
int packetsDropped = 0;      // malformed or not ours
int underruns = 0;
bool acked = false;

// Written by the keepalive task and by the main thread's BYE, so `seq++`
// would be a torn read-modify-write. Harmless while nothing reads control
// sequence numbers, but WP3 matches PONG to PING by sequence, and a
// duplicated number there silently corrupts an RTT sample.
// Numbering starts at 1: Interlocked.Increment returns the new value.
int controlSeq = 0;
uint NextControlSeq() => (uint)Interlocked.Increment(ref controlSeq);

var rtt = new RttTracker(RttWindow);

// Transit offset = (our clock now) - (sender's timestamp on the packet).
// The absolute value is meaningless: the two Stopwatch clocks share no
// origin, so this offset contains an unknown constant. What IS meaningful is
// how much it MOVES, because the constant cancels out of any difference.
// Tracking the smallest offset ever seen gives a floor corresponding to the
// fastest transit observed; everything above it is queueing delay and jitter.
long minTransitOffsetUs = long.MaxValue;
long jitterPeakUs = 0;

var cts = new CancellationTokenSource();

// ---- control: HELLO keepalive ----------------------------------------
// Every second, not once. The sender drops a client it has not heard from for
// three seconds, so a single HELLO at startup would get us dropped mid-stream.
var keepaliveTask = Task.Run(async () =>
{
    byte[] hello = new byte[WtpPacket.CommonHeaderSize];
    try
    {
        while (!cts.Token.IsCancellationRequested)
        {
            int len = WtpPacket.WriteControl(hello, PacketType.Hello,
                                             NextControlSeq(),
                                             WtpPacket.TimestampMicroseconds);
            await controlUdp.SendAsync(hello.AsMemory(0, len), senderControl,
                                       cts.Token);
            await Task.Delay(KeepaliveMs, cts.Token);
        }
    }
    catch (OperationCanceledException) { }
    catch (SocketException) { }
});

// ---- control: PING probe ---------------------------------------------
var pingTask = Task.Run(async () =>
{
    byte[] ping = new byte[WtpPacket.CommonHeaderSize];
    try
    {
        while (!cts.Token.IsCancellationRequested)
        {
            // The send time is the packet's own header timestamp. The sender
            // copies it back verbatim, so no state has to be kept here to
            // match a reply to a probe.
            int len = WtpPacket.WriteControl(ping, PacketType.Ping,
                                             NextControlSeq(),
                                             WtpPacket.TimestampMicroseconds);
            await controlUdp.SendAsync(ping.AsMemory(0, len), senderControl,
                                       cts.Token);
            await Task.Delay(PingIntervalMs, cts.Token);
        }
    }
    catch (OperationCanceledException) { }
    catch (SocketException) { }
});

// ---- control: HELLO_ACK and PONG -------------------------------------
var controlTask = Task.Run(async () =>
{
    try
    {
        while (!cts.Token.IsCancellationRequested)
        {
            var result = await controlUdp.ReceiveAsync(cts.Token);
            if (!WtpPacket.TryParse(result.Buffer, out var header, out var payload))
                continue;

            switch (header.Type)
            {
                case PacketType.HelloAck when !acked:
                    acked = true;
                    Console.WriteLine($"Connected to sender {result.RemoteEndPoint}");
                    break;

                case PacketType.Pong:
                    // Both ends of this subtraction are our own clock, so the
                    // unknown offset between the two machines never enters it.
                    if (payload.Length >= sizeof(long))
                    {
                        long sentUs = BinaryPrimitives.ReadInt64LittleEndian(payload);
                        long rttUs = WtpPacket.TimestampMicroseconds - sentUs;
                        if (rttUs >= 0)
                            rtt.Add(rttUs / 1000.0);
                    }
                    break;
            }
        }
    }
    catch (OperationCanceledException) { }
    catch (SocketException) { }
});

// ---- audio -----------------------------------------------------------
var receiveTask = Task.Run(async () =>
{
    try
    {
        while (!cts.Token.IsCancellationRequested)
        {
            UdpReceiveResult result = await audioUdp.ReceiveAsync(cts.Token);

            // Anything that is not a well-formed WTP1 packet is discarded and
            // counted. It never reaches the audio path and never throws.
            if (!WtpPacket.TryParse(result.Buffer, out var header, out var payload))
            {
                Interlocked.Increment(ref packetsDropped);
                continue;
            }

            if (header.Type != PacketType.Audio)
                continue;

            if (header.Codec != WtpPacket.CodecPcm16)
            {
                Interlocked.Increment(ref packetsDropped);
                continue;
            }

            long offsetUs = WtpPacket.TimestampMicroseconds - header.TimestampMicroseconds;
            if (offsetUs < minTransitOffsetUs)
                minTransitOffsetUs = offsetUs;
            long jitterUs = offsetUs - minTransitOffsetUs;
            if (jitterUs > jitterPeakUs)
                jitterPeakUs = jitterUs;

            if (format is null)
            {
                format = new WaveFormat((int)header.SampleRate, header.BitsPerSample,
                                        header.Channels);
                buffer = new BufferedWaveProvider(format)
                {
                    // 500 ms ceiling. If audio arrives faster than it plays,
                    // discard the oldest rather than grow without bound.
                    BufferLength = format.AverageBytesPerSecond / 2,
                    DiscardOnBufferOverflow = true,
                };
                waveOut = new WaveOutEvent { DesiredLatency = 50 };
                waveOut.Init(buffer);

                preBufferBytes = format.AverageBytesPerSecond * 60 / 1000;   // 60 ms

                Console.WriteLine($"Stream: {header.SampleRate} Hz, " +
                    $"{header.Channels} ch, {header.BitsPerSample}-bit, codec " +
                    $"{header.Codec}");
                Console.WriteLine($"Output latency: {waveOut.DesiredLatency} ms, " +
                    $"pre-buffer {preBufferBytes} bytes (60 ms)");
                Console.WriteLine();
            }

            if (haveSeq && header.Sequence > lastSeq + 1)
                Interlocked.Add(ref packetsLost, (int)(header.Sequence - lastSeq - 1));
            lastSeq = header.Sequence;
            haveSeq = true;

            if (!payload.IsEmpty)
                buffer!.AddSamples(payload.ToArray(), 0, payload.Length);

            Interlocked.Increment(ref packetsReceived);

            if (!playbackStarted && buffer!.BufferedBytes >= preBufferBytes)
            {
                waveOut!.Play();
                playbackStarted = true;
                Console.WriteLine(">>> Playback started! <<<");
                Console.WriteLine();
            }

            if (playbackStarted && buffer!.BufferedBytes == 0)
                Interlocked.Increment(ref underruns);
        }
    }
    catch (OperationCanceledException) { }
});

// ---- metrics: one CSV row and one console line per second ------------
var metricsTask = Task.Run(async () =>
{
    await using var csv = new StreamWriter(csvPath, append: false);
    // Column order is fixed by BUILD_PLAN WP3. transit_jitter_ms is appended
    // after them so the specified columns keep their positions.
    await csv.WriteLineAsync("elapsed_s,rtt_ms,buffer_ms,packets_received," +
                             "packets_lost,underruns,est_latency_ms," +
                             "transit_jitter_ms");
    await csv.FlushAsync();

    var started = DateTime.UtcNow;
    try
    {
        while (!cts.Token.IsCancellationRequested)
        {
            await Task.Delay(1000, cts.Token);

            int elapsed = (int)Math.Round((DateTime.UtcNow - started).TotalSeconds);
            double medianRtt = rtt.Median;
            double bufferMs = format is null || buffer is null
                ? 0
                : (double)buffer.BufferedBytes / format.AverageBytesPerSecond * 1000.0;
            int outputMs = waveOut?.DesiredLatency ?? 0;

            // Estimate, not measurement. Half the round trip stands in for the
            // one-way network hop, which assumes a symmetric path; the buffer
            // and the output device are the two delays we know exactly. The
            // honest end-to-end number comes from the acoustic method in
            // docs/MEASUREMENT.md.
            double est = medianRtt / 2.0 + bufferMs + outputMs;

            double jitterMs = Interlocked.Exchange(ref jitterPeakUs, 0) / 1000.0;
            int recv = Volatile.Read(ref packetsReceived);
            int lost = Volatile.Read(ref packetsLost);
            int under = Volatile.Read(ref underruns);

            await csv.WriteLineAsync(string.Create(CultureInfo.InvariantCulture,
                $"{elapsed},{medianRtt:F3},{bufferMs:F1},{recv},{lost},{under}," +
                $"{est:F1},{jitterMs:F3}"));
            await csv.FlushAsync();        // survive a Ctrl-C mid-run

            Console.WriteLine(
                $"[{elapsed,4}s] rtt {medianRtt,6:F2} ms | buffer {bufferMs,6:F1} ms | " +
                $"est {est,6:F1} ms | jitter {jitterMs,5:F2} ms | " +
                $"rx {recv} lost {lost} under {under}");
        }
    }
    catch (OperationCanceledException) { }
});

Console.ReadLine();

// Stop the keepalive BEFORE saying goodbye. If it were still running, a
// HELLO could land after the BYE and re-register this client, and the sender
// would then sit on a dead client for the full three-second timeout -- the
// exact delay BYE exists to avoid. The BYE send below deliberately passes no
// cancellation token, so cancelling here does not abort it.
cts.Cancel();

// Say goodbye before tearing anything down, so the sender drops us
// immediately instead of waiting three seconds for the keepalive to lapse.
try
{
    byte[] bye = new byte[WtpPacket.CommonHeaderSize];
    int len = WtpPacket.WriteControl(bye, PacketType.Bye, NextControlSeq(),
                                     WtpPacket.TimestampMicroseconds);
    await controlUdp.SendAsync(bye.AsMemory(0, len), senderControl);
    Console.WriteLine("Sent BYE.");
}
catch (SocketException) { }

waveOut?.Stop();

try { await receiveTask; } catch { }
try { await keepaliveTask; } catch { }
try { await pingTask; } catch { }
try { await controlTask; } catch { }
try { await metricsTask; } catch { }

waveOut?.Dispose();

Console.WriteLine();
Console.WriteLine("=== FINAL STATS ===");
Console.WriteLine($"Packets received : {packetsReceived}");
Console.WriteLine($"Packets lost     : {packetsLost}");
Console.WriteLine($"Packets dropped  : {packetsDropped}  (malformed / wrong magic)");
Console.WriteLine($"Underruns        : {underruns}");
Console.WriteLine($"Median RTT       : {rtt.Median:F2} ms  ({rtt.Count} samples)");
if (packetsReceived + packetsLost > 0)
{
    Console.WriteLine($"Loss rate        : " +
        $"{(double)packetsLost / (packetsReceived + packetsLost) * 100:F1}%");
}
Console.WriteLine($"Metrics CSV      : {Path.GetFullPath(csvPath)}");
Console.WriteLine("Stopped.");

/// <summary>
/// Rolling median of the last N round-trip samples.
///
/// Median, not mean: one Wi-Fi retransmission can produce a 200 ms outlier,
/// and a mean over 20 samples would carry a tenth of that into the latency
/// figure for the next ten seconds. A median ignores it entirely unless it
/// becomes the common case, which is exactly the behaviour wanted from a
/// number that goes on a status display.
/// </summary>
sealed class RttTracker(int capacity)
{
    private readonly double[] _samples = new double[capacity];
    private readonly Lock _gate = new();
    private int _count;
    private int _next;

    public void Add(double ms)
    {
        lock (_gate)
        {
            _samples[_next] = ms;
            _next = (_next + 1) % _samples.Length;
            if (_count < _samples.Length) _count++;
        }
    }

    public int Count
    {
        get { lock (_gate) return _count; }
    }

    public double Median
    {
        get
        {
            lock (_gate)
            {
                if (_count == 0) return 0;
                var copy = new double[_count];
                Array.Copy(_samples, copy, _count);
                Array.Sort(copy);
                return _count % 2 == 1
                    ? copy[_count / 2]
                    : (copy[_count / 2 - 1] + copy[_count / 2]) / 2.0;
            }
        }
    }
}
