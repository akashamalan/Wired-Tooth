using System.Net;
using System.Net.Sockets;
using NAudio.Wave;
using WiredTooth.Protocol;

Console.WriteLine("=== WIRED TOOTH - RECEIVER ===");
Console.WriteLine();

const int AudioPort = 5000;
const int ControlPort = 5001;
const int KeepaliveMs = 1000;

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

Console.WriteLine($"Audio  : listening on UDP {AudioPort}");
Console.WriteLine($"Control: sending HELLO to {senderControl} every {KeepaliveMs} ms");
Console.WriteLine("Press ENTER to stop.");
Console.WriteLine();

uint lastSeq = 0;
bool haveSeq = false;
int packetsReceived = 0;
int packetsLost = 0;
int packetsDropped = 0;      // malformed or not ours
int underruns = 0;
bool acked = false;

var cts = new CancellationTokenSource();
// Written by the keepalive task and by the main thread's BYE, so `seq++`
// would be a torn read-modify-write. Harmless while nothing reads control
// sequence numbers, but WP3 matches PONG back to PING by sequence, and a
// duplicated number there silently corrupts an RTT sample.
// Numbering starts at 1: Interlocked.Increment returns the new value.
int controlSeq = 0;
uint NextControlSeq() => (uint)Interlocked.Increment(ref controlSeq);

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

// ---- control: listen for HELLO_ACK -----------------------------------
var controlTask = Task.Run(async () =>
{
    try
    {
        while (!cts.Token.IsCancellationRequested)
        {
            var result = await controlUdp.ReceiveAsync(cts.Token);
            if (!WtpPacket.TryParse(result.Buffer, out var header, out _))
                continue;

            if (header.Type == PacketType.HelloAck && !acked)
            {
                acked = true;
                Console.WriteLine($"Connected to sender {result.RemoteEndPoint}");
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
                packetsDropped++;
                continue;
            }

            if (header.Type != PacketType.Audio)
                continue;

            if (header.Codec != WtpPacket.CodecPcm16)
            {
                packetsDropped++;               // a codec we cannot decode
                continue;
            }

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
                packetsLost += (int)(header.Sequence - lastSeq - 1);
            lastSeq = header.Sequence;
            haveSeq = true;

            if (!payload.IsEmpty)
                buffer!.AddSamples(payload.ToArray(), 0, payload.Length);

            packetsReceived++;

            if (!playbackStarted && buffer!.BufferedBytes >= preBufferBytes)
            {
                waveOut!.Play();
                playbackStarted = true;
                Console.WriteLine(">>> Playback started! <<<");
                Console.WriteLine();
            }

            if (playbackStarted && buffer!.BufferedBytes == 0)
                underruns++;

            if (packetsReceived % 100 == 0)
            {
                double bufferMs = (double)buffer!.BufferedBytes /
                    format!.AverageBytesPerSecond * 1000;

                Console.WriteLine(
                    $"Packets: {packetsReceived} | " +
                    $"lost: {packetsLost} | " +
                    $"dropped: {packetsDropped} | " +
                    $"buffer: {bufferMs:F0} ms | " +
                    $"underruns: {underruns}");
            }
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
try { await controlTask; } catch { }

waveOut?.Dispose();

Console.WriteLine();
Console.WriteLine("=== FINAL STATS ===");
Console.WriteLine($"Packets received : {packetsReceived}");
Console.WriteLine($"Packets lost     : {packetsLost}");
Console.WriteLine($"Packets dropped  : {packetsDropped}  (malformed / wrong magic)");
Console.WriteLine($"Underruns        : {underruns}");
if (packetsReceived + packetsLost > 0)
{
    Console.WriteLine($"Loss rate        : " +
        $"{(double)packetsLost / (packetsReceived + packetsLost) * 100:F1}%");
}
Console.WriteLine("Stopped.");
