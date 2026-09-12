using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace WiredTooth.Sender;

/// <summary>
/// Works out which of this machine's addresses a phone should actually be
/// pointed at.
///
/// This is the whole difficulty of QR pairing. A laptop typically has five or
/// six IPv4 addresses -- Wi-Fi, Ethernet, a couple of 169.254 link-local
/// autoconfig addresses, and whatever Hyper-V, WSL or a VPN has added -- and
/// encoding the wrong one produces a QR code that scans perfectly and then
/// never connects. Measured on this machine: five addresses, only one of which
/// was reachable from the phone.
/// </summary>
public static class LocalAddress
{
    /// <summary>
    /// Best guess at the address a phone on the same Wi-Fi can reach, or null.
    ///
    /// Preference order, most to least likely to be right:
    ///   1. the interface carrying the default route (what the OS itself uses)
    ///   2. an operational Wi-Fi interface
    ///   3. any operational non-virtual interface
    /// </summary>
    public static IPAddress? Best()
    {
        var candidates = Candidates().ToList();
        if (candidates.Count == 0) return null;

        // The interface that owns the default route is the one actually
        // carrying traffic off this machine, which is the strongest signal
        // available without probing.
        var viaDefaultRoute = candidates.FirstOrDefault(c => c.HasGateway);
        if (viaDefaultRoute is not null) return viaDefaultRoute.Address;

        var wifi = candidates.FirstOrDefault(c => c.IsWireless);
        if (wifi is not null) return wifi.Address;

        return candidates[0].Address;
    }

    public sealed record Candidate(IPAddress Address, string InterfaceName,
                                   bool IsWireless, bool HasGateway)
    {
        public override string ToString() =>
            $"{Address}  ({InterfaceName}{(IsWireless ? ", Wi-Fi" : "")}" +
            $"{(HasGateway ? ", default route" : "")})";
    }

    /// <summary>Every plausible address, best first. Shown in the QR window so
    /// the user can pick another if the guess is wrong.</summary>
    public static IEnumerable<Candidate> Candidates()
    {
        var found = new List<Candidate>();

        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up) continue;
            if (nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;

            var props = nic.GetIPProperties();
            bool hasGateway = props.GatewayAddresses
                .Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork
                          && !g.Address.Equals(IPAddress.Any));

            bool wireless = nic.NetworkInterfaceType == NetworkInterfaceType.Wireless80211;

            foreach (var ua in props.UnicastAddresses)
            {
                if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                if (IPAddress.IsLoopback(ua.Address)) continue;

                // 169.254.x.x means DHCP failed and Windows made something up.
                // Such an address is never reachable from a phone, but it is
                // reported exactly like a working one.
                byte[] b = ua.Address.GetAddressBytes();
                if (b[0] == 169 && b[1] == 254) continue;

                found.Add(new Candidate(ua.Address, nic.Name, wireless, hasGateway));
            }
        }

        return found
            .OrderByDescending(c => c.HasGateway)
            .ThenByDescending(c => c.IsWireless);
    }
}
