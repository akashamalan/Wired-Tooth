using NAudio.CoreAudioApi;
using NAudio.CoreAudioApi.Interfaces;

namespace WiredTooth.Sender;

/// <summary>
/// Raises a callback when Windows switches the default RENDER endpoint.
///
/// WasapiLoopbackCapture binds one device when it is constructed. If the user
/// changes their output device, the old endpoint keeps producing nothing and
/// the stream goes silent with no exception anywhere -- the most confusing
/// possible failure, because everything still reports healthy.
///
/// These callbacks arrive on a COM thread owned by the audio service. Doing
/// real work here stalls the audio subsystem process-wide, so the handler is
/// expected to hand off to a worker immediately.
/// </summary>
public sealed class DefaultRenderWatcher(Action<string> onDefaultRenderChanged)
    : IMMNotificationClient
{
    public void OnDefaultDeviceChanged(DataFlow flow, Role role, string defaultDeviceId)
    {
        // Console/Multimedia/Communications each fire separately; reacting to
        // all three would restart capture up to three times for one user
        // action. Multimedia is the role WasapiLoopbackCapture actually uses.
        if (flow == DataFlow.Render && role == Role.Multimedia)
            onDefaultRenderChanged(defaultDeviceId);
    }

    public void OnDeviceStateChanged(string deviceId, DeviceState newState) { }
    public void OnDeviceAdded(string pwstrDeviceId) { }
    public void OnDeviceRemoved(string deviceId) { }
    public void OnPropertyValueChanged(string pwstrDeviceId, PropertyKey key) { }
}
