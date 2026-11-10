using System.Net.Sockets;
using WiredTooth.Sender;

namespace WiredTooth.Windows;

/// <summary>
/// Starting the sender without taking the whole app down with it.
///
/// Binding UDP 5001 is the one step at startup that routinely fails for a
/// reason the user can act on, and it was unhandled: a second copy of Luma,
/// or anything else already on the port, produced an unhandled
/// SocketException and the process died before a window ever appeared. There
/// was nothing on screen to explain it, and the crash dialog named
/// KERNELBASE.dll.
///
/// There are four places that start the sender -- launch, the tray toggle,
/// the Start streaming button and --console -- so the handling lives here
/// once rather than being written out four times and drifting.
/// </summary>
internal static class SenderStart
{
    /// <summary>WSAEADDRINUSE. The port is taken.</summary>
    private const int AddressInUse = 10048;

    /// <summary>
    /// Starts the sender. Returns null on success, or a sentence to show the
    /// user. The caller decides how to show it, because a console build and a
    /// tray app disagree about what "show" means.
    /// </summary>
    public static string? Try(AudioSender sender)
    {
        try
        {
            sender.Start();
            return null;
        }
        catch (SocketException ex) when (ex.ErrorCode == AddressInUse)
        {
            return $"{Brand.Name} is already running, or another app is using " +
                   $"port {AudioSender.ControlPort}.";
        }
        catch (SocketException ex)
        {
            // Anything else from the socket layer is rarer and not worth
            // guessing at, but it still must not be a crash.
            return $"{Brand.Name} could not open its network ports " +
                   $"({ex.SocketErrorCode}). Check your firewall and try again.";
        }
    }
}
