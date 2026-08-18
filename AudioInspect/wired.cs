using NAudio.Wave;

Console.WriteLine("Starting audio inspection...");
Console.WriteLine("Play some audio.");
Console.WriteLine("Press ENTER to stop.");

using var capture = new WasapiLoopbackCapture();

Console.WriteLine();
Console.WriteLine($"Sample Rate : {capture.WaveFormat.SampleRate} Hz");
Console.WriteLine($"Channels    : {capture.WaveFormat.Channels}");
Console.WriteLine($"Bits       : {capture.WaveFormat.BitsPerSample}");
Console.WriteLine($"Encoding   : {capture.WaveFormat.Encoding}");
Console.WriteLine();

int buffersShown = 0;

capture.DataAvailable += (sender, e) =>
{
    // Skip empty buffers (silence — no audio playing)
    if (e.BytesRecorded == 0)
        return;

    if (buffersShown >= 3)
        return;

    Console.WriteLine($"--- Buffer #{buffersShown + 1} ---");
    Console.WriteLine($"Buffer received: {e.BytesRecorded} bytes");

    int bytesPerSample = capture.WaveFormat.BitsPerSample / 8;
    int channels = capture.WaveFormat.Channels;
    int bytesPerFrame = bytesPerSample * channels;
    int sampleRate = capture.WaveFormat.SampleRate;

    // How many frames are in this buffer?
    int totalFrames = e.BytesRecorded / bytesPerFrame;

    // How many milliseconds of audio does this buffer represent?
    double durationMs = (double)totalFrames / sampleRate * 1000.0;

    Console.WriteLine($"Bytes per sample : {bytesPerSample}");
    Console.WriteLine($"Bytes per frame  : {bytesPerFrame}");
    Console.WriteLine($"Frames in buffer : {totalFrames}");
    Console.WriteLine($"Buffer duration  : {durationMs:F2} ms");
    Console.WriteLine();

    // Show first 5 frames with Left/Right labels
    int framesToShow = Math.Min(5, totalFrames);

    for (int frame = 0; frame < framesToShow; frame++)
    {
        int frameOffset = frame * bytesPerFrame;

        if (bytesPerSample == 4)
        {
            float left = BitConverter.ToSingle(
                e.Buffer, frameOffset);
            float right = BitConverter.ToSingle(
                e.Buffer, frameOffset + bytesPerSample);

            Console.WriteLine(
                $"Frame {frame}: Left = {left,12:F8}  Right = {right,12:F8}");
        }
    }

    Console.WriteLine();

    buffersShown++;
};

capture.StartRecording();

Console.ReadLine();

capture.StopRecording();

Console.WriteLine("Stopped.");