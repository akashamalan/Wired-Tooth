using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Text;
using WiredTooth.Sender;

namespace WiredTooth.Windows;

/// <summary>
/// The checks that answer "why does my phone say No PCs found?".
///
/// Every one of these is read-only. Nothing here changes a firewall rule or a
/// network setting: the fixes need elevation, and an app that silently edits
/// the firewall is worse than one that tells you the command to run.
///
/// The set exists because the failure is always the same three things -- no
/// usable address, the wrong address, or the firewall -- and all three look
/// identical from the phone, which just says it found nothing.
/// </summary>
public static class Diagnostics
{
    public enum Level { Ok, Warn, Bad }

    public readonly record struct Result(Level Level, string Title, string Detail);

    public static async Task<IReadOnlyList<Result>> RunAsync(SenderStatus status)
    {
        var results = new List<Result>();

        // 1. Is there an address a phone could even reach?
        var candidates = LocalAddress.Candidates().ToList();
        if (candidates.Count == 0)
        {
            results.Add(new(Level.Bad, "No usable network address",
                "This PC has no non-loopback IPv4 address. Connect to Wi-Fi or "
                + "join the phone's Personal Hotspot, then run the checks again."));
        }
        else
        {
            var wifi = candidates.FirstOrDefault(c => c.IsWireless);
            results.Add(new(Level.Ok,
                $"{candidates.Count} network address{(candidates.Count == 1 ? "" : "es")}",
                wifi is not null
                    ? $"Wi-Fi: {wifi.Address} on {wifi.InterfaceName}. The phone must "
                      + "be on this same network."
                    : "None of them is Wi-Fi. If the phone is on Wi-Fi or a hotspot "
                      + "and this PC is on Ethernet, they may not be able to reach "
                      + "each other."));

            if (candidates.Count > 1)
            {
                results.Add(new(Level.Warn, "More than one address",
                    "Only one of these is usually reachable from the phone. If "
                    + "pairing fails, pick a different address above and rescan "
                    + "on the phone — the QR code changes with it."));
            }
        }

        // 2. Is the sender actually holding the sockets?
        if (!status.Running)
        {
            results.Add(new(Level.Bad, "Not streaming",
                "Press Start streaming. While stopped, nothing is listening and "
                + "the phone cannot find this PC."));
        }
        else
        {
            var udp = IPGlobalProperties.GetIPGlobalProperties().GetActiveUdpListeners();
            bool control = udp.Any(e => e.Port == status.ControlPort);
            bool audio = udp.Any(e => e.Port == status.AudioPort);

            results.Add(control && audio
                ? new(Level.Ok, "Ports open",
                    $"Listening on UDP {status.AudioPort} for audio and "
                    + $"{status.ControlPort} for pairing.")
                : new(Level.Bad, "Ports not bound",
                    $"Expected UDP {status.AudioPort} and {status.ControlPort}. "
                    + "Another program may already be using them."));
        }

        // 3. The firewall. This is the single most common cause, and it fails
        //    silently -- Windows drops the packets without telling anyone.
        results.Add(await FirewallAsync(status).ConfigureAwait(false));

        // 4. Has anything ever actually connected?
        results.Add(status.Clients.Count > 0
            ? new(Level.Ok, $"{status.Clients.Count} phone(s) connected",
                "Pairing works. If there is no sound, the problem is on the "
                + "phone side, not here.")
            : new(Level.Warn, "No phone has connected",
                "This is normal before the first pairing. If the phone still "
                + "says No PCs found after scanning the code, the answer is in "
                + "the checks above — whichever one is not OK."));

        return results;
    }

    /// <summary>
    /// Whether Windows Firewall is blocking the phone.
    ///
    /// The order of these three questions is deliberate, and it is the order of
    /// how much the answer is worth:
    ///
    ///   1. Is a phone connected RIGHT NOW? Then the firewall demonstrably is
    ///      not blocking, whatever the rules say. Evidence beats inference.
    ///   2. Is the firewall even on for this profile? On a machine where it is
    ///      off, "no rule exists" is true and completely irrelevant -- reporting
    ///      it as the problem sends the user to fix something that is fine.
    ///   3. Only then, does a rule exist for this executable?
    ///
    /// Step 3 is last because it is also the slow one. netsh cannot filter
    /// rules by program, so finding out means dumping every inbound rule --
    /// measured at 15 seconds and half a megabyte on this machine. Steps 1 and
    /// 2 answer most cases in under a second and skip it entirely.
    /// </summary>
    private static async Task<Result> FirewallAsync(SenderStatus status)
    {
        string exe = Environment.ProcessPath ?? "";

        if (status.Clients.Count > 0)
        {
            return new(Level.Ok, "Firewall is not blocking",
                "A phone is connected right now, so inbound packets are getting "
                + "through. Whatever the rules say, the firewall is not your "
                + "problem.");
        }

        try
        {
            string profile = await NetshAsync("advfirewall show currentprofile")
                .ConfigureAwait(false);

            // Which profile is active matters: a rule can exist for Private
            // while the machine is currently on Public, which behaves exactly
            // like having no rule at all.
            string which =
                profile.Contains("Public Profile", StringComparison.OrdinalIgnoreCase) ? "Public"
                : profile.Contains("Private Profile", StringComparison.OrdinalIgnoreCase) ? "Private"
                : profile.Contains("Domain Profile", StringComparison.OrdinalIgnoreCase) ? "Domain"
                : "unknown";

            // "State  OFF" on its own line. If the firewall is off there is
            // nothing to allow and no rule to look for.
            bool firewallOn = !profile
                .Split('\n')
                .Any(l => l.TrimStart().StartsWith("State", StringComparison.OrdinalIgnoreCase)
                          && l.Contains("OFF", StringComparison.OrdinalIgnoreCase));

            if (!firewallOn)
            {
                return new(Level.Ok, $"Firewall is off ({which} network)",
                    "Windows Firewall is switched off for this network, so it is "
                    + "not blocking anything. If the phone still cannot find this "
                    + "PC, the network itself is separating them — most campus "
                    + "and public Wi-Fi does. Use a Personal Hotspot.");
            }

            string rules = await NetshAsync(
                "advfirewall firewall show rule name=all dir=in verbose")
                .ConfigureAwait(false);

            bool allowed = exe.Length > 0
                && rules.Contains(exe, StringComparison.OrdinalIgnoreCase);

            if (allowed)
            {
                return new(Level.Ok, $"Firewall rule found ({which} network)",
                    $"Windows Firewall has an inbound rule for {Brand.Name}. If the "
                    + "phone still cannot see this PC, the network itself is "
                    + "blocking device-to-device traffic. Use a Personal Hotspot.");
            }

            return new(Level.Bad, $"No firewall rule ({which} network)",
                $"Windows Firewall is on and has no inbound rule for {Brand.Name}, "
                + "so it is silently dropping the phone's packets. Either click "
                + "Allow on the Windows prompt at startup, or run this in an "
                + "Administrator terminal:\n\n"
                + $"netsh advfirewall firewall add rule name=\"{Brand.Name}\" "
                + "dir=in action=allow protocol=UDP localport=5000-5001 "
                + $"program=\"{exe}\"");
        }
        catch (Exception ex)
        {
            return new(Level.Warn, "Could not check the firewall",
                $"netsh did not answer ({ex.Message}). Check Windows Firewall by "
                + $"hand: {Brand.Name} needs inbound UDP 5000 and 5001 allowed on "
                + "the network you are using.");
        }
    }

    private static async Task<string> NetshAsync(string args)
    {
        using var p = new Process
        {
            StartInfo = new ProcessStartInfo("netsh", args)
            {
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
                StandardOutputEncoding = Encoding.UTF8,
            },
        };

        p.Start();
        // Read before waiting: a full rule dump is megabytes, and a filled pipe
        // with nobody draining it deadlocks the wait.
        Task<string> stdout = p.StandardOutput.ReadToEndAsync();

        // The full inbound rule dump measured 15.5s on the development
        // machine, so 20s left almost no headroom on a box with more rules.
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(60));
        await p.WaitForExitAsync(cts.Token).ConfigureAwait(false);
        return await stdout.ConfigureAwait(false);
    }
}
