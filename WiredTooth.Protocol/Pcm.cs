namespace WiredTooth.Protocol;

/// <summary>
/// Sample-format conversion for the wire.
///
/// WASAPI loopback hands us 32-bit float. The wire format is 16-bit PCM,
/// which halves bandwidth for no audible loss at these levels and is what
/// every receiver can play without further conversion. The codec field in the
/// audio sub-header stays 0 (PCM16) until Opus is added, if ever.
/// </summary>
public static class Pcm
{
    /// <summary>
    /// float32 [-1, 1] to little-endian int16. Returns bytes written.
    ///
    /// Clamping is not optional. WASAPI loopback can hand back samples
    /// slightly outside [-1, 1] when an application applies its own gain, and
    /// an unclamped cast wraps: a sample just over +1.0 becomes a large
    /// NEGATIVE int16. That is a full-scale discontinuity, which is audible
    /// as a loud click on exactly the loud passages where it happens.
    /// </summary>
    public static int FloatToInt16(ReadOnlySpan<byte> src, Span<byte> dest)
    {
        int samples = src.Length / sizeof(float);
        if (dest.Length < samples * sizeof(short))
            throw new ArgumentException("destination too small", nameof(dest));

        var floats = System.Runtime.InteropServices.MemoryMarshal
                           .Cast<byte, float>(src);
        var shorts = System.Runtime.InteropServices.MemoryMarshal
                           .Cast<byte, short>(dest);

        for (int i = 0; i < samples; i++)
        {
            float f = floats[i];

            // NaN fails every comparison, so it must be handled before the
            // clamp or it would fall through to the multiply and become junk.
            if (float.IsNaN(f)) f = 0f;
            else if (f > 1f) f = 1f;
            else if (f < -1f) f = -1f;

            // 32767, not 32768: +1.0 * 32768 is 32768, which does not fit in
            // an int16 and wraps to -32768.
            shorts[i] = (short)(f * 32767f);
        }

        return samples * sizeof(short);
    }

    /// <summary>Bytes per frame on the wire for a PCM16 stream.</summary>
    public static int Int16FrameSize(int channels) => channels * sizeof(short);
}
