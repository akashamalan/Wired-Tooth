using System.Net;
using WiredTooth.Sender;

// Console front-end for AudioSender. Everything that was here in WP1-WP5 now
// lives in WiredTooth.Sender so the tray app (WP6) and this tool are the same
// sender with two faces, rather than two implementations that drift apart.
// Kept permanently: it is the debugging tool and the regression harness, and
// it works with no GUI at all.

Console.WriteLine("=== WIRED TOOTH - SENDER ===");
Console.WriteLine("Play audio on your laptop.");
Console.WriteLine("Press ENTER to stop streaming.");
Console.WriteLine();

// BUILD_PLAN WP2's debug fallback: passing an IP streams to it unconditionally,
// with no handshake and no timeout.
IPAddress? forced = null;
if (args.Length > 0)
{
    if (IPAddress.TryParse(args[0], out var parsed))
        forced = parsed;
    else
        Console.WriteLine($"Ignoring argument '{args[0]}': not an IP address");
}

using var sender = new AudioSender(forced);
sender.Log += Console.WriteLine;

sender.Start();

Console.WriteLine();
Console.WriteLine("Waiting for a client to say HELLO...");
Console.WriteLine();

// One line per second, the same cadence the receiver reports on, so the two
// consoles line up when read side by side.
var cts = new CancellationTokenSource();
var reporter = Task.Run(async () =>
{
    try
    {
        while (!cts.Token.IsCancellationRequested)
        {
            await Task.Delay(1000, cts.Token);
            var s = sender.GetStatus();
            if (s.Clients.Count == 0 && s.PacketsSent == 0)
                continue;
            Console.WriteLine(
                $"Streaming... packets: {s.PacketsSent} | " +
                $"clients: {s.Clients.Count} | " +
                $"sent: {s.BytesSent / 1024} KB | " +
                $"{s.BitrateKbps:F0} kbit/s");
        }
    }
    catch (OperationCanceledException) { }
});

// WP5 case 4: ENTER and Ctrl-C run the SAME shutdown path. Ctrl-C used to kill
// the process outright, which skipped the BYE and left every connected client
// waiting out the full 3s timeout. e.Cancel=true takes the termination back so
// the cleanup below actually runs.
var stopping = new TaskCompletionSource();
Console.CancelKeyPress += (_, e) =>
{
    e.Cancel = true;
    Console.WriteLine();
    Console.WriteLine("Ctrl-C: shutting down...");
    stopping.TrySetResult();
};
_ = Task.Run(() =>
{
    // Returns immediately on EOF when stdin is not a console, which is how the
    // scripted test runs drive shutdown.
    Console.ReadLine();
    stopping.TrySetResult();
});
await stopping.Task;

cts.Cancel();
try { await reporter; } catch { }

sender.Stop();
