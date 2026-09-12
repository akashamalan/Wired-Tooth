using System.Net;
using System.Runtime.InteropServices;
using System.Windows;
using WiredTooth.Sender;

namespace WiredTooth.Windows;

/// <summary>
/// Entry point. Either a tray app or, with --console, the old console
/// behaviour, so there is one binary to ship and debugging does not require a
/// different build.
/// </summary>
public static class Program
{
    // DllImport rather than LibraryImport: the source generator needs a
    // partial class and AllowUnsafeBlocks, which is a lot of ceremony for two
    // calls made once at startup.
    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AllocConsole();

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AttachConsole(uint dwProcessId);

    private const uint AttachParentProcess = 0xFFFFFFFF;

    [STAThread]
    public static int Main(string[] args)
    {
        IPAddress? forced = null;
        foreach (string a in args)
        {
            if (a.StartsWith("--", StringComparison.Ordinal)) continue;
            if (IPAddress.TryParse(a, out var parsed)) forced = parsed;
        }

        bool console = args.Any(a =>
            a.Equals("--console", StringComparison.OrdinalIgnoreCase));

        return console ? RunConsole(forced) : RunTray(forced);
    }

    private static int RunTray(IPAddress? forced)
    {
        var app = new System.Windows.Application
        {
            ShutdownMode = ShutdownMode.OnExplicitShutdown,
        };
        using var tray = new TrayApp(forced);
        return app.Run();
    }

    /// <summary>
    /// The WP1-WP5 console behaviour, unchanged in substance. This project is
    /// built as WinExe so the tray app does not flash a console window, which
    /// means stdout goes nowhere until a console is attached by hand.
    /// </summary>
    private static int RunConsole(IPAddress? forced)
    {
        // Attach to the calling terminal if there is one, so output lands where
        // the user typed the command. Only allocate a new window if there isn't.
        if (!AttachConsole(AttachParentProcess))
            AllocConsole();

        Console.WriteLine("=== WIRED TOOTH - SENDER (--console) ===");
        Console.WriteLine("Play audio on your laptop.");
        Console.WriteLine("Press ENTER to stop streaming.");
        Console.WriteLine();

        using var sender = new AudioSender(forced);
        sender.Log += Console.WriteLine;
        sender.Start();

        Console.WriteLine();
        Console.WriteLine("Waiting for a client to say HELLO...");
        Console.WriteLine();

        var cts = new CancellationTokenSource();
        var reporter = Task.Run(async () =>
        {
            try
            {
                while (!cts.Token.IsCancellationRequested)
                {
                    await Task.Delay(1000, cts.Token);
                    var s = sender.GetStatus();
                    if (s.Clients.Count == 0 && s.PacketsSent == 0) continue;
                    Console.WriteLine(
                        $"Streaming... packets: {s.PacketsSent} | " +
                        $"clients: {s.Clients.Count} | " +
                        $"sent: {s.BytesSent / 1024} KB | " +
                        $"{s.BitrateKbps:F0} kbit/s");
                }
            }
            catch (OperationCanceledException) { }
        });

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
            Console.ReadLine();
            stopping.TrySetResult();
        });
        stopping.Task.GetAwaiter().GetResult();

        cts.Cancel();
        try { reporter.Wait(2000); } catch { }
        sender.Stop();
        return 0;
    }
}
