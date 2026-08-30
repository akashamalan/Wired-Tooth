using System.Net.Sockets;
using NAudio.Wave;
using WiredTooth.Protocol;

Console.WriteLine("=== WIRED TOOTH - RECEIVER ===");
Console.WriteLine();

// Playback is built lazily from the first valid AUDIO packet rather than
// hardcoded. The sender stamps its real format into every packet, so the
// receiver never has to assume 48kHz stereo and cannot be silently wrong
// when the capture device differs.
BufferedWaveProvider? buffer = null;
WaveOutEvent? waveOut = null;
WaveFormat? format = null;

int preBufferBytes = 0;
bool playbackStarted = false;

using UdpClient udp = new UdpClient(5000);

Console.WriteLine("Listening on port 5000...");
Console.WriteLine("Press ENTER to stop.");
Console.WriteLine();

uint lastSeq = 0;
bool haveSeq = false;
int packetsReceived = 0;
int packetsLost = 0;
int packetsDropped = 0;      // malformed or not ours
int underruns = 0;

var cts = new CancellationTokenSource();

var receiveTask = Task.Run(async () =>
{
    try
    {
        while (!cts.Token.IsCancellationRequested)
        {
            UdpReceiveResult result = await udp.ReceiveAsync(cts.Token);

            // Anything that is not a well-formed WTP1 packet is discarded and
            // counted. It never reaches the audio path and never throws.
            if (!WtpPacket.TryParse(result.Buffer, out var header, out var payload))
            {
                packetsDropped++;
                continue;
            }

            if (header.Type != PacketType.Audio)
                continue;                       // control packets arrive in WP2

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

cts.Cancel();
waveOut?.Stop();

try { await receiveTask; } catch { }

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
