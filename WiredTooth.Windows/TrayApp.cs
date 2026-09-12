using System.Drawing;
using System.Net;
using System.Windows.Forms;
using WiredTooth.Sender;

namespace WiredTooth.Windows;

/// <summary>
/// The tray icon and its menu. Owns the AudioSender and both windows.
///
/// There is no main window on purpose: this is a background utility, and a
/// WPF Window that exists only to be hidden shows up in Alt-Tab and on the
/// taskbar, which is not what a tray app should do.
/// </summary>
public sealed class TrayApp : IDisposable
{
    private readonly NotifyIcon _icon;
    private readonly AudioSender _sender;
    private readonly ToolStripMenuItem _startStop;
    private readonly System.Windows.Forms.Timer _poll;
    private readonly List<string> _log = [];
    private readonly Lock _logGate = new();

    private StatusWindow? _status;
    private QrWindow? _qr;
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
        menu.Items.Add(new ToolStripMenuItem("Show Status", null, (_, _) => ShowStatus()));
        menu.Items.Add(new ToolStripMenuItem("Show QR Code", null, (_, _) => ShowQr()));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(_startStop);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Exit", null, (_, _) => Exit()));

        _icon = new NotifyIcon
        {
            Icon = _idleIcon,
            Text = "Wired Tooth - idle",
            Visible = true,
            ContextMenuStrip = menu,
        };
        _icon.DoubleClick += (_, _) => ShowStatus();

        _sender.Start();

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
            ? "Wired Tooth - stopped"
            : streaming
                ? Truncate($"Wired Tooth - streaming to {s.Clients.Count} client(s), " +
                           $"{s.BitrateKbps:F0} kbit/s")
                : "Wired Tooth - idle, waiting for a client";

        _startStop.Text = s.Running ? "Stop streaming" : "Start streaming";

        _status?.Update(s, LogLines());
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

    private void ShowStatus()
    {
        if (_status is null || !_status.IsLoaded)
        {
            _status = new StatusWindow();
            _status.Closed += (_, _) => _status = null;
        }
        _status.Update(_sender.GetStatus(), LogLines());
        _status.Show();
        _status.Activate();
    }

    private void ShowQr()
    {
        if (_qr is null || !_qr.IsLoaded)
        {
            _qr = new QrWindow();
            _qr.Closed += (_, _) => _qr = null;
        }
        _qr.Show();
        _qr.Activate();
    }

    private void Exit()
    {
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
    }
}
