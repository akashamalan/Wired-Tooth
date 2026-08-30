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
Console.WriteLine();

using UdpClient udp = new UdpClient();

string targetIp = args.Length > 0 ? args[0] : "127.0.0.1";

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

        udp.Send(packet, headerLen + chunk, targetIp, 5000);

        totalBytesSent += headerLen + chunk;
        sequenceNumber++;
        packetsSent++;
    }

    if (packetsSent % 100 == 0)
    {
        Console.WriteLine(
            $"Streaming... packets: {sequenceNumber} | " +
            $"sent: {totalBytesSent / 1024} KB | " +
            $"time: {WtpPacket.TimestampMicroseconds / 1_000_000}s");
    }
};

capture.StartRecording();

Console.WriteLine($"Streaming to {targetIp}:5000. Press ENTER to stop.");
Console.WriteLine();

Console.ReadLine();

capture.StopRecording();

Console.WriteLine();
Console.WriteLine($"Stopped. Total packets sent: {sequenceNumber}");
Console.WriteLine($"Total data sent: {totalBytesSent / 1024} KB");
