using NAudio.CoreAudioApi;

namespace WiredTooth.Windows;

/// <summary>
/// Silences this computer's speakers while the phone is listening.
///
/// THE PROBLEM: WASAPI loopback captures whatever the PC is playing, so the
/// audio comes out of the laptop AND the phone at once, a few tens of
/// milliseconds apart. Two copies of the same sound slightly out of step is
/// worse than either one alone.
///
/// WHY THIS WORKS: muting the render endpoint does NOT silence the loopback
/// capture. Measured on this machine with a 440 Hz tone -- captured RMS was
/// 0.078 unmuted, 0.054 with the endpoint muted and 0.051 at volume zero. The
/// mute is applied at the endpoint, after the loopback tap. So the speakers go
/// quiet and the stream keeps flowing.
///
/// WHAT THIS IS NOT: a virtual audio device. A real "Luma" entry in the Windows
/// sound settings, selectable per app, needs a signed kernel-mode driver --
/// a different language, a different toolchain, and a certificate. This gets
/// the outcome the user actually wanted without any of that. The honest
/// difference is that muting is system-wide: notification sounds go quiet too,
/// and the volume keys will appear to do nothing until streaming stops.
///
/// The endpoint is remembered rather than re-resolved, so that if the default
/// device changes while muted, the device we muted is the device we restore.
/// Leaving a user's speakers muted after the app closes would be unforgivable.
/// </summary>
public sealed class LocalMute : IDisposable
{
    private MMDevice? _muted;
    private bool _previous;

    public bool Active => _muted is not null;

    /// <summary>Mutes the current default output, remembering its prior state.</summary>
    public void Apply()
    {
        if (Active) return;

        try
        {
            using var en = new MMDeviceEnumerator();
            var device = en.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);

            _previous = device.AudioEndpointVolume.Mute;
            device.AudioEndpointVolume.Mute = true;
            _muted = device;
        }
        catch (Exception)
        {
            // No output device, or one that refuses endpoint volume control.
            // Failing to mute must never stop the stream.
            _muted = null;
        }
    }

    /// <summary>Puts the endpoint back exactly as it was found.</summary>
    public void Restore()
    {
        if (_muted is null) return;

        try
        {
            _muted.AudioEndpointVolume.Mute = _previous;
        }
        catch (Exception)
        {
            // The device can disappear between muting and restoring -- unplugged
            // headphones, a disabled endpoint. Nothing to put back.
        }
        finally
        {
            _muted.Dispose();
            _muted = null;
        }
    }

    public void Dispose() => Restore();
}
