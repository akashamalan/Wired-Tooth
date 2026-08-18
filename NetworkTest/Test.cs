using System.Net;
using System.Net.Sockets;
using NAudio.Wave;

Console.WriteLine("=== WIRED TOOTH — RECEIVER ===");
Console.WriteLine();

// === AUDIO PLAYBACK SETUP ===

// Create the audio format to match the sender:
// 48000 Hz, 2 channels, 32-bit IEEE float
var waveFormat = WaveFormat.CreateIeeeFloatWaveFormat(48000, 2);

// BufferedWaveProvider = our JITTER BUFFER
// It stores audio data that we feed it from the network.
// WaveOutEvent reads from it at a steady rate.
var buffer = new BufferedWaveProvider(waveFormat);

// Max buffer: 500ms. If more audio piles up, discard oldest.
// This prevents the buffer from growing forever if data arrives
// faster than it plays.
buffer.BufferLength = 48000 * 2 * 4 / 2;  // 500ms worth of bytes
buffer.DiscardOnBufferOverflow = true;

// WaveOutEvent = the audio player
// It reads from our buffer and sends audio to speakers.
using var waveOut = new WaveOutEvent();
waveOut.DesiredLatency = 50;  // request low-latency from Windows audio

waveOut.Init(buffer);

Console.WriteLine($"Audio output: {waveFormat.SampleRate} Hz, " +
    $"{waveFormat.Channels} ch, {waveFormat.BitsPerSample}-bit");
Console.WriteLine($"Output latency: {waveOut.DesiredLatency} ms");

// === NETWORK SETUP ===
using UdpClient udp = new UdpClient(5000);

Console.WriteLine("Listening on port 5000...");
Console.WriteLine("Press ENTER to stop.");
Console.WriteLine();

// === PRE-BUFFER ===
// Wait for some audio before starting playback.
// This gives us a cushion against network jitter.
int preBufferBytes = 48000 * 2 * 4 * 60 / 1000;  // 60ms = 23,040 bytes
bool playbackStarted = false;

// === STATS ===
uint lastSeq = uint.MaxValue;
int packetsReceived = 0;
int packetsLost = 0;
int underruns = 0;

// === RECEIVE LOOP ===
// Run the receive loop in a background task.
// The main thread waits for ENTER to stop.
var cts = new CancellationTokenSource();

var receiveTask = Task.Run(async () =>
{
    try
    {
        while (!cts.Token.IsCancellationRequested)
        {
            UdpReceiveResult result = await udp.ReceiveAsync(cts.Token);
            byte[] datagram = result.Buffer;

            if (datagram.Length < 12)
                continue;

            // Parse header
            uint seq       = BitConverter.ToUInt32(datagram, 0);
            uint timestamp = BitConverter.ToUInt32(datagram, 4);
            uint payloadLen = BitConverter.ToUInt32(datagram, 8);

            // Check for lost packets
            if (lastSeq != uint.MaxValue && seq > lastSeq + 1)
            {
                int lost = (int)(seq - lastSeq - 1);
                packetsLost += lost;
            }
            lastSeq = seq;

            // Extract audio data (everything after the 12-byte header)
            int audioBytes = datagram.Length - 12;

            // Add audio to the jitter buffer
            buffer.AddSamples(datagram, 12, audioBytes);

            packetsReceived++;

            // Start playback once we have enough audio buffered
            if (!playbackStarted && buffer.BufferedBytes >= preBufferBytes)
            {
                waveOut.Play();
                playbackStarted = true;
                Console.WriteLine(">>> Playback started! <<<");
                Console.WriteLine();
            }

            // Check for underruns (buffer ran empty)
            if (playbackStarted && buffer.BufferedBytes == 0)
            {
                underruns++;
            }

            // Print status every 10 packets
            if (packetsReceived % 10 == 0)
            {
                double bufferMs = (double)buffer.BufferedBytes /
                    (48000 * 2 * 4) * 1000;

                Console.WriteLine(
                    $"Packets: {packetsReceived} | " +
                    $"lost: {packetsLost} | " +
                    $"buffer: {bufferMs:F0} ms | " +
                    $"underruns: {underruns}");
            }
        }
    }
    catch (OperationCanceledException) { }
});

Console.ReadLine();

cts.Cancel();
waveOut.Stop();

try { await receiveTask; } catch { }

Console.WriteLine();
Console.WriteLine("=== FINAL STATS ===");
Console.WriteLine($"Packets received : {packetsReceived}");
Console.WriteLine($"Packets lost     : {packetsLost}");
Console.WriteLine($"Underruns        : {underruns}");
if (packetsReceived + packetsLost > 0)
{
    Console.WriteLine($"Loss rate        : " +
        $"{(double)packetsLost / (packetsReceived + packetsLost) * 100:F1}%");
}
Console.WriteLine("Stopped.");