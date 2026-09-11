namespace WiredTooth.Receiver;

/// <summary>
/// Interleaved PCM16 resampler for a continuously varying, near-unity ratio.
///
/// Why linear interpolation and not NAudio's WdlResamplingSampleProvider:
///
/// WDL is a windowed-sinc resampler built around a FIXED input/output rate
/// pair. It is the better choice for real rate conversion -- 44.1k to 48k, say
/// -- because at large ratios linear interpolation is a poor low-pass filter
/// and audibly dulls the top end. But it holds internal filter state tied to
/// that rate pair, and the drift loop changes the ratio every 100ms. Rebuilding
/// or reconfiguring it that often either discards filter state, producing a
/// discontinuity at every change, or is simply not supported by the API.
///
/// The ratio here never leaves ±0.5% of 1.0. At that ratio every output sample
/// falls between two input samples that are essentially the same point in the
/// waveform, and the interpolation error sits far below the 16-bit noise floor.
/// The stopband performance that makes WDL worth its complexity is irrelevant
/// when barely resampling at all.
///
/// The state that DOES matter is phase continuity. Fractional position and the
/// final frame of each block carry across calls, so a packet boundary is not a
/// discontinuity. Resetting per packet would click 140 times a second.
/// </summary>
public sealed class LinearResampler
{
    private readonly int _channels;
    private readonly float[] _prev;      // last frame of the previous block
    private double _pos;                 // fractional read position, in frames

    public LinearResampler(int channels)
    {
        _channels = channels;
        _prev = new float[channels];
        _pos = 0.0;
    }

    /// <summary>
    /// Worst-case output frames for a given input, so callers can size a
    /// buffer once instead of per packet.
    /// </summary>
    public static int MaxOutputFrames(int inputFrames) =>
        (int)(inputFrames / (1.0 - DriftController.MaxDeviation)) + 4;

    /// <summary>
    /// Consumes one whole block and returns the number of frames written.
    /// <paramref name="speed"/> above 1.0 produces FEWER output frames than
    /// input frames, which is what drains a too-full buffer.
    /// </summary>
    public int Resample(ReadOnlySpan<short> input, double speed,
                        Span<short> output)
    {
        int frames = input.Length / _channels;
        if (frames == 0) return 0;

        int produced = 0;
        int maxOut = output.Length / _channels;

        while (produced < maxOut)
        {
            double pos = _pos + produced * speed;
            int i = (int)Math.Floor(pos);

            // Need i and i+1 to both exist. i == -1 is served from the
            // previous block's final frame, which is the whole point of
            // keeping it.
            if (i + 1 > frames - 1) break;
            if (i < -1) break;

            float frac = (float)(pos - i);

            for (int c = 0; c < _channels; c++)
            {
                float a = i < 0 ? _prev[c] : input[i * _channels + c];
                float b = input[(i + 1) * _channels + c];
                float v = a + (b - a) * frac;

                // Interpolation cannot exceed the range of its two endpoints,
                // so this clamp is belt and braces against rounding at the
                // extremes rather than a real risk.
                output[produced * _channels + c] =
                    (short)Math.Clamp((int)MathF.Round(v), short.MinValue, short.MaxValue);
            }

            produced++;
        }

        // Rebase the phase onto the next block. Everything before frame
        // `frames` has now been consumed.
        _pos = _pos + produced * speed - frames;
        if (_pos < -1.0) _pos = -1.0;

        for (int c = 0; c < _channels; c++)
            _prev[c] = input[(frames - 1) * _channels + c];

        return produced;
    }

    /// <summary>
    /// Drop carried state. Used after concealment or a silence gap, where the
    /// previous frame is no longer adjacent in time to what comes next and
    /// interpolating across the join would smear unrelated audio together.
    /// </summary>
    public void Reset()
    {
        Array.Clear(_prev);
        _pos = 0.0;
    }
}
