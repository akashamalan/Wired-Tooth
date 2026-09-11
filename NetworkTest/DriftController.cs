namespace WiredTooth.Receiver;

/// <summary>
/// Holds the jitter buffer at a target depth by nudging playback speed.
///
/// The problem it solves: the sender's sound card and the receiver's are never
/// at exactly the same rate. A few parts per million sounds like nothing, but
/// at 48kHz a 20 ppm difference is about one sample per second, roughly 3.6
/// seconds of accumulated error per hour. The buffer either drains to zero and
/// clicks, or grows until latency is unusable. It looks perfect for two
/// minutes and fails at twenty, which is why the acceptance test is 30 minutes
/// and not 30 seconds.
///
/// The fix is not to resynchronise occasionally -- that means dropping or
/// duplicating a block of samples, which is audible. It is to run permanently
/// slightly off-speed, by an amount too small to hear, in whichever direction
/// keeps the buffer level.
///
/// This is a proportional controller with no integral or derivative term:
///
///     error  = bufferMs - targetMs        (positive = too full)
///     target = 1 + Kp * error             (>1 = play faster, drain)
///     ratio  = ratio + (target - ratio) * Smoothing
///
/// No integral term on purpose. The steady-state offset a P controller leaves
/// behind is exactly what is wanted here: the residual error IS the clock
/// difference, and holding a small constant error is how the loop expresses
/// "run 12 ppm fast forever". An integrator would drive that error to zero,
/// wind up, and oscillate around the setpoint -- audible as slow pitch waver.
/// </summary>
public sealed class DriftController
{
    /// <summary>
    /// Maximum speed deviation, ±0.5%. About 8.7 cents of pitch, which is
    /// below what anyone notices on programme material and an order of
    /// magnitude larger than any real clock difference, so the clamp should
    /// only ever engage while catching up from a startup transient.
    /// </summary>
    public const double MaxDeviation = 0.005;

    // An error of 100ms commands the full ±0.5%. Chosen so that normal drift,
    // which produces errors of a few ms, commands a correspondingly tiny
    // fraction of the range and the loop stays firmly in its linear region.
    private const double Kp = MaxDeviation / 100.0;

    // Applied once per 100ms tick, so ~1 second to move most of the way to a
    // new commanded ratio. Slow enough that a momentary burst of packets does
    // not produce a step change in pitch.
    private const double Smoothing = 0.1;

    private readonly double _targetMs;

    public DriftController(double targetMs) => _targetMs = targetMs;

    /// <summary>
    /// Current speed multiplier. 1.0 is unmodified. Above 1.0 the receiver
    /// consumes its input faster than real time, which drains the buffer.
    /// </summary>
    public double Ratio { get; private set; } = 1.0;

    public double TargetMs => _targetMs;

    /// <summary>Call every 100 ms with the measured buffer depth.</summary>
    public double Update(double bufferMs)
    {
        double error = bufferMs - _targetMs;
        double commanded = Math.Clamp(1.0 + Kp * error,
                                      1.0 - MaxDeviation,
                                      1.0 + MaxDeviation);
        Ratio += (commanded - Ratio) * Smoothing;
        return Ratio;
    }
}
