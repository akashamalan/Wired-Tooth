using System.Buffers.Binary;
using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using WiredTooth.Protocol;
using WiredTooth.Receiver;

Console.WriteLine("=== WIRED TOOTH - RECEIVER ===");
Console.WriteLine();

const int AudioPort = 5000;
const int ControlPort = 5001;
const int KeepaliveMs = 1000;
const int PingIntervalMs = 500;
const int RttWindow = 20;

// Output backend is WasapiOut, not WaveOutEvent.
//
// Measured on this machine, same device and same format, no networking:
//     WaveOutEvent (WinMM)   39,977 frames/s   -16.72%
//     WasapiOut    (shared)  48,219 frames/s   +0.46%
// The default render endpoint is a virtual device (FxSound), and WinMM drains
// it 16.7% slower than real time. No drift controller can close a gap that
// large -- the clamp is +/-0.5% precisely so corrections stay inaudible -- so
// the buffer filled and stayed full no matter what the loop commanded. WASAPI
// shared mode drains the same device correctly, and its +0.46% residual is
// ordinary clock difference, which is exactly what the controller is for.
const int OutputLatencyMs = 50;

string senderArg = args.Length > 0 ? args[0] : "127.0.0.1";
if (!IPAddress.TryParse(senderArg, out var senderAddress))
{
    Console.WriteLine($"'{senderArg}' is not an IP address. Usage: " +
                      $"dotnet run --project NetworkTest -- <sender-ip> [target-buffer-ms]");
    return;
}
var senderControl = new IPEndPoint(senderAddress, ControlPort);

double targetBufferMs = 60.0;
if (args.Length > 1 && double.TryParse(args[1], NumberStyles.Float,
                                       CultureInfo.InvariantCulture, out var t)
    && t > 0)
{
    targetBufferMs = t;
}

var drift = new DriftController(targetBufferMs);
LinearResampler? resampler = null;

// Playback is built lazily from the first valid AUDIO packet rather than
// hardcoded. The sender stamps its real format into every packet, so the
// receiver never has to assume 48kHz stereo and cannot be silently wrong
// when the capture device differs.
BufferedWaveProvider? buffer = null;
WasapiOut? waveOut = null;
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
Console.WriteLine($"Buffer : target {targetBufferMs:F0} ms, drift correction " +
                  $"clamped to +/-{DriftController.MaxDeviation * 100:F1}%");
Console.WriteLine("Press ENTER to stop.");
Console.WriteLine();

uint lastSeq = 0;
bool haveSeq = false;
int packetsReceived = 0;
int packetsLost = 0;
int packetsDropped = 0;      // malformed or not ours
int underruns = 0;
int resyncs = 0;
bool acked = false;

// WP4 instrumentation. Every frame entering the jitter buffer is attributed
// to exactly one source, so an over-production of N% can be pinned on the
// path that caused it instead of inferred from the buffer misbehaving.
long framesIn = 0;              // frames arriving in audio payloads
long framesAudio = 0;           // frames added after resampling
long framesSilence = 0;         // frames synthesised from silence keepalives
long framesConceal = 0;         // frames synthesised for lost packets
long framesDroppedResync = 0;   // frames discarded by the resync guard
int silencePackets = 0;
DateTime firstPacketUtc = DateTime.MinValue;

// Above this much excess over target, stop trying to resample it away and
// discard it in one step. See the resync guard in the drift loop.
const double ResyncThresholdMs = 150.0;

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
    // Reused across packets. This loop is single-threaded, so one scratch
    // buffer per role keeps the audio path allocation-free.
    byte[] outBytes = [];
    byte[] fillBytes = [];
    bool resyncing = false;
    short[] lastFrame = [];
    int lastPayloadFrames = 0;

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
                waveOut = new WasapiOut(AudioClientShareMode.Shared, OutputLatencyMs);
                waveOut.Init(buffer);

                resampler = new LinearResampler(format.Channels);
                lastFrame = new short[format.Channels];

                // Start playback once the buffer holds the target depth, so
                // the control loop begins at its setpoint instead of having to
                // climb to it.
                preBufferBytes = (int)(format.AverageBytesPerSecond
                                       * targetBufferMs / 1000.0);

                Console.WriteLine($"Stream: {header.SampleRate} Hz, " +
                    $"{header.Channels} ch, {header.BitsPerSample}-bit, codec " +
                    $"{header.Codec}");
                Console.WriteLine($"Output latency: {OutputLatencyMs} ms (WasapiOut shared), " +
                    $"pre-buffer {preBufferBytes} bytes ({targetBufferMs:F0} ms)");
                Console.WriteLine();
            }

            int frameBytes = format.Channels * sizeof(short);

            // ---- packet loss concealment ----------------------------
            // Letting the buffer simply go short would starve playback and
            // register an underrun. Filling the hole keeps the timeline
            // intact, which also keeps the drift controller honest: it is
            // measuring depth, and a gap it cannot see would look like the
            // clocks diverging.
            if (haveSeq && header.Sequence > lastSeq + 1)
            {
                int missing = (int)(header.Sequence - lastSeq - 1);
                Interlocked.Add(ref packetsLost, missing);

                if (lastPayloadFrames > 0)
                {
                    // Capped: beyond a couple of hundred ms the stream is not
                    // dropping packets, it is gone, and synthesising seconds
                    // of filler would just add latency to the recovery.
                    int maxFrames = format.SampleRate / 5;          // 200 ms
                    int frames = Math.Min(missing * lastPayloadFrames, maxFrames);
                    int needed = frames * frameBytes;
                    if (fillBytes.Length < needed) fillBytes = new byte[needed];

                    // Fade the last received frame out over 5 ms rather than
                    // cutting to zero. A hard step to silence is a click; a
                    // short ramp is inaudible.
                    var fill = MemoryMarshal.Cast<byte, short>(
                        fillBytes.AsSpan(0, needed));
                    int fade = Math.Min(format.SampleRate / 200, frames);   // 5 ms
                    for (int f = 0; f < frames; f++)
                    {
                        float g = f < fade ? 1f - (float)f / fade : 0f;
                        for (int c = 0; c < format.Channels; c++)
                            fill[f * format.Channels + c] = (short)(lastFrame[c] * g);
                    }

                    buffer!.AddSamples(fillBytes, 0, needed);
                    framesConceal += frames;

                    // The next real packet is not continuous with the faded
                    // tail, so interpolating across that join would smear two
                    // unrelated moments together.
                    resampler!.Reset();
                }
            }
            lastSeq = header.Sequence;
            haveSeq = true;

            if (header.IsSilence)
            {
                // An empty silence-flagged packet stands for a fixed span of
                // quiet (WtpPacket.SilenceKeepaliveMs). Expanding it here is
                // what keeps the buffer fed while WASAPI is not firing at all.
                int bytes = (int)(format.AverageBytesPerSecond
                                  * WtpPacket.SilenceKeepaliveMs / 1000.0);
                bytes -= bytes % frameBytes;
                if (bytes > 0)
                {
                    if (fillBytes.Length < bytes) fillBytes = new byte[bytes];
                    Array.Clear(fillBytes, 0, bytes);
                    buffer!.AddSamples(fillBytes, 0, bytes);
                    framesSilence += bytes / frameBytes;
                    silencePackets++;
                    resampler!.Reset();
                }
            }
            else if (!payload.IsEmpty)
            {
                var inShorts = MemoryMarshal.Cast<byte, short>(payload);
                int inFrames = inShorts.Length / format.Channels;
                framesIn += inFrames;
                if (firstPacketUtc == DateTime.MinValue)
                    firstPacketUtc = DateTime.UtcNow;

                int maxOut = LinearResampler.MaxOutputFrames(inFrames) * frameBytes;
                if (outBytes.Length < maxOut) outBytes = new byte[maxOut];

                // Resample BEFORE the buffer, per WP4. Playing the content
                // slightly fast is what drains a too-full buffer; the output
                // device rate is fixed and cannot be adjusted.
                int outFrames = resampler!.Resample(
                    inShorts, drift.Ratio,
                    MemoryMarshal.Cast<byte, short>(outBytes.AsSpan()));

                // Resync guard, applied here because this is the only thread
                // that may mutate the buffer besides WaveOut's reader.
                //
                // The +/-0.5% clamp is sized to stay inaudible, which by
                // construction makes it far too slow to recover a large
                // transient: a startup burst puts a few hundred ms in the
                // buffer within seconds, and at a net drain well under 0.2%/s
                // the controller would need minutes to claw it back, during
                // which latency is exactly what this project exists to avoid.
                // So drift correction handles drift, and dropping incoming
                // audio handles transients. Hysteresis: enter above
                // target+threshold, leave only once back at target, so it
                // cannot chatter packet by packet.
                double depth = (double)buffer!.BufferedBytes
                               / format.AverageBytesPerSecond * 1000.0;
                if (!resyncing && depth > targetBufferMs + ResyncThresholdMs)
                {
                    resyncing = true;
                    Interlocked.Increment(ref resyncs);
                    Console.WriteLine($"[resync] buffer {depth:F0} ms, dropping " +
                                      $"to {targetBufferMs:F0} ms");
                }
                else if (resyncing && depth <= targetBufferMs)
                {
                    resyncing = false;
                }

                if (outFrames > 0 && !resyncing)
                {
                    buffer.AddSamples(outBytes, 0, outFrames * frameBytes);
                    framesAudio += outFrames;
                }
                else if (outFrames > 0)
                {
                    framesDroppedResync += outFrames;
                }

                lastPayloadFrames = inFrames;
                for (int c = 0; c < format.Channels; c++)
                    lastFrame[c] = inShorts[(inFrames - 1) * format.Channels + c];
            }

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

// ---- drift control loop: every 100 ms --------------------------------
// Separate from the metrics tick because it has to run ten times more often.
// Metrics are for reading; this is the feedback path, and sampling the buffer
// once a second would leave the loop far too slow to hold a 60 ms setpoint.
var driftTask = Task.Run(async () =>
{
    try
    {
        while (!cts.Token.IsCancellationRequested)
        {
            await Task.Delay(100, cts.Token);
            if (!playbackStarted || format is null || buffer is null)
                continue;
            double depthMs = (double)buffer.BufferedBytes
                             / format.AverageBytesPerSecond * 1000.0;

            // Resync guard.
            //
            // The +/-0.5% clamp is sized to stay inaudible, which by
            // construction makes it far too slow to recover a large transient.
            // Measured: the receiver takes a startup burst that puts ~450ms in
            // the buffer within three seconds, and at a net drain of well under
            // 0.2%/s the controller would need many minutes to claw that back --
            // during which latency is 500ms and the whole point of the project
            // is lost. It also cannot recover at all if the residual imbalance
            // is near the clamp.
            //
            // So: drift correction handles drift, and a one-off discard handles
            // transients. Above the threshold, drop the oldest excess in a
            // single step. That is one audible discontinuity, at startup or
            // after a stall, in exchange for holding the target the rest of the
            // time. Counted and logged rather than hidden, because a resync
            // mid-stream means something is wrong upstream.
            // The discard itself is performed on the RECEIVE thread, by not
            // adding incoming audio, never by reading here. BufferedWaveProvider
            // is a single-producer single-consumer circular buffer: WaveOutEvent's
            // playback thread is the consumer, and a second reader on this thread
            // corrupts its state. Measured the hard way -- doing the drain with
            // buffer.Read() here produced 182 resyncs in 190 seconds and a buffer
            // that never settled.
            drift.Update(depthMs);
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
                             "transit_jitter_ms,correction_ratio");
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
            int outputMs = OutputLatencyMs;

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
                $"{est:F1},{jitterMs:F3},{drift.Ratio:F6}"));
            await csv.FlushAsync();        // survive a Ctrl-C mid-run

            Console.WriteLine(
                $"[{elapsed,4}s] rtt {medianRtt,6:F2} ms | buffer {bufferMs,6:F1} ms | " +
                $"est {est,6:F1} ms | ratio {drift.Ratio:F5} | " +
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
try { await driftTask; } catch { }
try { await metricsTask; } catch { }

waveOut?.Dispose();

Console.WriteLine();
Console.WriteLine("=== FINAL STATS ===");
Console.WriteLine($"Packets received : {packetsReceived}");
Console.WriteLine($"Packets lost     : {packetsLost}");
Console.WriteLine($"Packets dropped  : {packetsDropped}  (malformed / wrong magic)");
Console.WriteLine($"Underruns        : {underruns}");
Console.WriteLine($"Resyncs          : {resyncs}  (buffer discarded to target)");
Console.WriteLine($"Median RTT       : {rtt.Median:F2} ms  ({rtt.Count} samples)");
Console.WriteLine($"Final ratio      : {drift.Ratio:F6}  (target {drift.TargetMs:F0} ms)");
if (packetsReceived + packetsLost > 0)
{
    Console.WriteLine($"Loss rate        : " +
        $"{(double)packetsLost / (packetsReceived + packetsLost) * 100:F1}%");
}

if (format is not null && firstPacketUtc != DateTime.MinValue)
{
    double secs = (DateTime.UtcNow - firstPacketUtc).TotalSeconds;
    double need = format.SampleRate * secs;               // frames real time needs
    long added = framesAudio + framesSilence + framesConceal;
    Console.WriteLine();
    Console.WriteLine("=== WP4 FRAME ACCOUNTING ===");
    Console.WriteLine($"Elapsed          : {secs:F1} s  ->  {need:N0} frames at " +
                      $"{format.SampleRate} Hz");
    Console.WriteLine($"Arrived (payload): {framesIn:N0}  ({framesIn / secs:N0}/s, " +
                      $"{(framesIn / need - 1) * 100:+0.00;-0.00}% vs real time)");
    Console.WriteLine($"  + from audio   : {framesAudio:N0}");
    Console.WriteLine($"  + from silence : {framesSilence:N0}  " +
                      $"({silencePackets} packets x {WtpPacket.SilenceKeepaliveMs} ms)");
    Console.WriteLine($"  + concealment  : {framesConceal:N0}");
    Console.WriteLine($"  - resync drop  : {framesDroppedResync:N0}");
    Console.WriteLine($"ADDED to buffer  : {added:N0}  ({added / secs:N0}/s, " +
                      $"{(added / need - 1) * 100:+0.00;-0.00}% vs real time)");
    Console.WriteLine($"Consumed (implied): {(added - (buffer?.BufferedBytes ?? 0) / (double)(format.Channels * 2)) / secs:N0}/s");
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
