using System.Diagnostics;
using System.Net;
using System.Text;

namespace WiredTooth.Windows;

/// <summary>
/// What network this computer is on, in the words the user would use.
///
/// "Is the phone on the same Wi-Fi?" is the question behind most failures, and
/// until now the app could only answer it with an IP address -- which nobody
/// can check against their phone without digging through settings. A network
/// NAME is checkable in two seconds: the phone shows the same string at the top
/// of its Wi-Fi screen.
///
/// The hotspot detection matters more than it looks. A phone hotspot is the
/// configuration this whole project works best on: a handful of devices, no
/// router in between, and none of the client isolation that campus and public
/// Wi-Fi use to stop devices reaching each other. When the machine is on one,
/// the app can say so instead of hedging.
/// </summary>
public static class WifiInfo
{
    public readonly record struct Network(string? Ssid, bool OnHotspot, string? HotspotKind);

    /// <summary>
    /// Reads the current wireless network. Blocking (it shells out to netsh);
    /// call it off the UI thread or accept about a second.
    /// </summary>
    public static Network Current()
    {
        string? ssid = null;

        try
        {
            using var p = new Process
            {
                StartInfo = new ProcessStartInfo("netsh", "wlan show interfaces")
                {
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    StandardOutputEncoding = Encoding.UTF8,
                },
            };
            p.Start();
            string output = p.StandardOutput.ReadToEnd();
            p.WaitForExit(5000);

            foreach (string line in output.Split('\n'))
            {
                string t = line.Trim();
                // "SSID" also prefixes "BSSID", which is the access point's MAC
                // and not a name anybody recognises.
                if (!t.StartsWith("SSID", StringComparison.OrdinalIgnoreCase)) continue;
                if (t.StartsWith("BSSID", StringComparison.OrdinalIgnoreCase)) continue;

                int colon = t.IndexOf(':');
                if (colon < 0) continue;
                string value = t[(colon + 1)..].Trim();
                if (value.Length > 0) { ssid = value; break; }
            }
        }
        catch (Exception)
        {
            // No wireless adapter, or netsh unavailable. The app still works;
            // it just cannot name the network.
        }

        var (isHotspot, kind) = ClassifyHotspot();
        return new Network(ssid, isHotspot, kind);
    }

    /// <summary>
    /// Recognises a phone or PC hotspot from the address range it hands out.
    ///
    /// These three are fixed by the platforms rather than chosen by the user,
    /// which is what makes them reliable to match on. Anything else is reported
    /// as unknown rather than guessed at -- claiming "you are on a hotspot"
    /// when the user is on their home router would send them to fix the one
    /// thing that was already right.
    /// </summary>
    private static (bool, string?) ClassifyHotspot()
    {
        foreach (var c in Sender.LocalAddress.Candidates())
        {
            byte[] b = c.Address.GetAddressBytes();

            // iOS Personal Hotspot always hands out 172.20.10.0/28.
            if (b[0] == 172 && b[1] == 20 && b[2] == 10)
                return (true, "iPhone Personal Hotspot");

            // The long-standing Android tethering range.
            if (b[0] == 192 && b[1] == 168 && b[2] == 43)
                return (true, "Android hotspot");

            // Windows Mobile Hotspot and Internet Connection Sharing.
            if (b[0] == 192 && b[1] == 168 && b[2] == 137)
                return (true, "Windows Mobile Hotspot");
        }

        return (false, null);
    }

    /// <summary>
    /// How many addresses a phone would have to probe to find this PC.
    ///
    /// Not a curiosity: the receiver finds the sender by sweeping the subnet,
    /// so this is literally the cost of discovery. A /28 hotspot is 14 probes
    /// and instant; a /24 is 254 and takes a moment; anything larger is not
    /// swept at all and the address has to be typed.
    /// </summary>
    public static int SubnetHosts(IPAddress address)
    {
        foreach (var c in Sender.LocalAddress.Candidates())
        {
            if (!c.Address.Equals(address)) continue;
            var nic = System.Net.NetworkInformation.NetworkInterface
                .GetAllNetworkInterfaces()
                .FirstOrDefault(n => n.Name == c.InterfaceName);

            var info = nic?.GetIPProperties().UnicastAddresses
                .FirstOrDefault(u => u.Address.Equals(address));
            if (info is null) continue;

            int hostBits = 32 - info.PrefixLength;
            return hostBits >= 31 ? 0 : (1 << hostBits) - 2;
        }
        return 0;
    }
}
