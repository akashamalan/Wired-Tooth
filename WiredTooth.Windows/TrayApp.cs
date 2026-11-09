using System.Drawing;
using System.Net;
using System.Windows.Forms;
using WiredTooth.Sender;

namespace WiredTooth.Windows;

/// <summary>
/// The tray icon and its menu. Owns the AudioSender and the windows.
///
/// The tray is no longer the whole interface. It is the app's background
/// presence -- streaming has to survive the window being closed -- while
/// MainWindow is what the user actually deals with. Closing the window hides
/// it here; Exit on this menu is the only thing that quits.
/// </summary>
public sealed class TrayApp : IDisposable
{
    private readonly NotifyIcon _icon;
    private readonly AudioSender _sender;
    private readonly ToolStripMenuItem _startStop;
    private readonly System.Windows.Forms.Timer _poll;
    private readonly List<string> _log = [];
    private readonly Lock _logGate = new();

    private MainWindow? _main;
    private readonly LocalMute _mute = new();
    private readonly Icon _idleIcon;
    private readonly Icon _activeIcon;
    private bool _wasStreaming;

    public TrayApp(IPAddress? forced)
    {
        _sender = new AudioSender(forced);
        _sender.Log += line =>
        {
            lock (_logGate)
            {
                _log.Add($"{DateTime.Now:HH:mm:ss}  {line}");
                // Bounded: this runs for hours and the log is a diagnostic
                // panel, not a record.
                if (_log.Count > 500) _log.RemoveRange(0, _log.Count - 500);
            }
        };

        _idleIcon = BuildIcon(Color.FromArgb(120, 120, 120));
        _activeIcon = BuildIcon(Color.FromArgb(64, 190, 120));

        _startStop = new ToolStripMenuItem("Stop streaming", null, (_, _) => ToggleStartStop());

        var menu = new ContextMenuStrip();
        menu.Items.Add(new ToolStripMenuItem($"Open {Brand.Name}", null, (_, _) => ShowMain()));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(_startStop);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Exit", null, (_, _) => Exit()));

        _icon = new NotifyIcon
        {
            Icon = _idleIcon,
            Text = $"{Brand.Name} - idle",
            Visible = true,
            ContextMenuStrip = menu,
        };
        _icon.DoubleClick += (_, _) => ShowMain();

        _sender.Start();

        // First run gets the introduction; every run after it goes straight to
        // the home screen. A background utility that starts with no visible
        // sign of itself leaves the user hunting the tray to find out whether
        // anything happened, and the first run is exactly when they need both
        // the explanation and the pairing code.
        if (Prefs.ShowWelcome)
        {
            var welcome = new WelcomeWindow();
            welcome.Finished += ShowMain;
            welcome.Show();
        }
        else
        {
            ShowMain();
        }

        // One timer drives the icon state, the tooltip and both windows. A
        // second-resolution poll is far cheaper than pushing events onto the UI
        // thread from the audio path, and nothing here needs to be fresher.
        _poll = new System.Windows.Forms.Timer { Interval = 1000 };
        _poll.Tick += (_, _) => Refresh();
        _poll.Start();
        Refresh();
    }

    public string[] LogLines()
    {
        lock (_logGate) return [.. _log];
    }

    private void Refresh()
    {
        var s = _sender.GetStatus();
        bool streaming = s.Running && s.Clients.Count > 0;

        if (streaming != _wasStreaming)
        {
            _icon.Icon = streaming ? _activeIcon : _idleIcon;
            _wasStreaming = streaming;
        }

        _icon.Text = !s.Running
            ? $"{Brand.Name} - stopped"
            : streaming
                ? Truncate($"{Brand.Name} - streaming to {s.Clients.Count} listener(s), " +
                           $"{s.BitrateKbps:F0} kbit/s")
                : $"{Brand.Name} - idle, waiting for a phone";

        _startStop.Text = s.Running ? "Stop streaming" : "Start streaming";

        _main?.Refresh(s, LogLines());
    }

    // NotifyIcon.Text throws above 63 characters rather than truncating.
    private static string Truncate(string s) =>
        s.Length <= 63 ? s : s[..60] + "...";

    private void ToggleStartStop()
    {
        if (_sender.IsRunning) _sender.Stop();
        else _sender.Start();
        Refresh();
    }

    private void ShowMain()
    {
        // The window is hidden, never closed, so it keeps its scroll position
        // and the address the user picked. Re-creating it each time would
        // silently reset both.
        _main ??= new MainWindow(_sender, LogLines, _mute);
        _main.Refresh(_sender.GetStatus(), LogLines());
        _main.Show();
        if (_main.WindowState == System.Windows.WindowState.Minimized)
            _main.WindowState = System.Windows.WindowState.Normal;
        _main.Activate();
    }


    private void Exit()
    {
        // The window cancels its own Close to hide instead, so shutting down
        // has to bypass that or the process would linger.
        if (_main is not null)
        {
            _main.AllowClose = true;
            _main.Close();
        }
        Dispose();
        System.Windows.Application.Current.Shutdown();
    }

    /// <summary>
    /// Draws the tray icon rather than shipping .ico files. Two states, one
    /// shape, one colour difference -- a grey dot when nothing is connected and
    /// a coloured one while streaming, which is the only distinction the menu
    /// spec asks for and the only one legible at 16x16.
    /// </summary>
    private static Icon BuildIcon(Color colour)
    {
        using var bmp = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);
            using var brush = new SolidBrush(colour);
            g.FillEllipse(brush, 4, 4, 24, 24);
            using var pen = new Pen(Color.FromArgb(230, 255, 255, 255), 2.5f);
            // A crude waveform, enough to read as "audio" at tray size.
            g.DrawLine(pen, 11, 16, 11, 16);
            g.DrawLine(pen, 12, 12, 12, 20);
            g.DrawLine(pen, 16, 9, 16, 23);
            g.DrawLine(pen, 20, 12, 20, 20);
        }
        return Icon.FromHandle(bmp.GetHicon());
    }

    public void Dispose()
    {
        _poll.Stop();
        _poll.Dispose();
        _icon.Visible = false;
        _icon.Dispose();
        _sender.Dispose();
        // Last thing, and unconditional. Leaving a user's speakers muted after
        // the app is gone would be unforgivable.
        _mute.Dispose();
    }
}
