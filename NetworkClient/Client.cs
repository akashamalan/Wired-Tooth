using System.Diagnostics;
using System.Net.Sockets;
using NAudio.Wave;

Console.WriteLine("=== WIRED TOOTH — SENDER ===");
Console.WriteLine("Play audio on your laptop.");
Console.WriteLine("Press ENTER to stop streaming.");
Console.WriteLine();

using var capture = new WasapiLoopbackCapture();

Console.WriteLine($"Format: {capture.WaveFormat.SampleRate} Hz, " +
    $"{capture.WaveFormat.Channels} ch, " +
    $"{capture.WaveFormat.BitsPerSample}-bit {capture.WaveFormat.Encoding}");

using UdpClient udp = new UdpClient();

uint sequenceNumber = 0;
Stopwatch timer = Stopwatch.StartNew();
long totalBytesSent = 0;

capture.DataAvailable += (sender, e) =>
{
    if (e.BytesRecorded == 0)
        return;

    // Build packet: [12-byte header] + [audio data]
    uint timestamp = (uint)timer.ElapsedMilliseconds;
    byte[] packet = new byte[12 + e.BytesRecorded];

    BitConverter.GetBytes(sequenceNumber).CopyTo(packet, 0);
    BitConverter.GetBytes(timestamp).CopyTo(packet, 4);
    BitConverter.GetBytes((uint)e.BytesRecorded).CopyTo(packet, 8);
    Array.Copy(e.Buffer, 0, packet, 12, e.BytesRecorded);

    string targetIp = args.Length > 0 ? args[0] : "127.0.0.1";
    udp.Send(packet, packet.Length, targetIp, 5000);

    totalBytesSent += packet.Length;
    sequenceNumber++;

    // Print status every 10 packets
    if (sequenceNumber % 10 == 0)
    {
        Console.WriteLine(
            $"Streaming... packets: {sequenceNumber} | " +
            $"sent: {totalBytesSent / 1024} KB | " +
            $"time: {timestamp / 1000}s");
    }
};

capture.StartRecording();

Console.WriteLine("Streaming started. Press ENTER to stop.");
Console.WriteLine();

Console.ReadLine();

capture.StopRecording();

Console.WriteLine();
Console.WriteLine($"Stopped. Total packets sent: {sequenceNumber}");
Console.WriteLine($"Total data sent: {totalBytesSent / 1024} KB");