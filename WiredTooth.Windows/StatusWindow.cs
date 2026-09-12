using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using WiredTooth.Sender;

// UseWindowsForms is enabled for NotifyIcon, which puts WinForms' Image,
// ComboBox, ListBox and TextBox in scope alongside WPF's. These windows are
// WPF, so say so once rather than fully qualifying every use.
using Image = System.Windows.Controls.Image;
using ComboBox = System.Windows.Controls.ComboBox;
using ListBox = System.Windows.Controls.ListBox;
using TextBox = System.Windows.Controls.TextBox;
using Orientation = System.Windows.Controls.Orientation;
using FontFamily = System.Windows.Media.FontFamily;
using Brushes = System.Windows.Media.Brushes;
using HorizontalAlignment = System.Windows.HorizontalAlignment;

namespace WiredTooth.Windows;

/// <summary>
/// Live sender status. Built in code rather than XAML: it is one grid of
/// label/value pairs plus a log pane, and a XAML file plus code-behind would be
/// two files to keep in sync for no benefit.
/// </summary>
public sealed class StatusWindow : Window
{
    private readonly TextBlock _localAddr = Value();
    private readonly TextBlock _format = Value();
    private readonly TextBlock _packets = Value();
    private readonly TextBlock _sent = Value();
    private readonly TextBlock _bitrate = Value();
    private readonly TextBlock _restarts = Value();
    private readonly TextBlock _state = Value();
    private readonly ListBox _clients = new()
    {
        Height = 90,
        FontFamily = new FontFamily("Consolas"),
        FontSize = 12,
    };
    private readonly TextBox _log = new()
    {
        IsReadOnly = true,
        FontFamily = new FontFamily("Consolas"),
        FontSize = 11,
        VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
        TextWrapping = TextWrapping.NoWrap,
        Height = 150,
    };

    public StatusWindow()
    {
        Title = "Wired Tooth - Status";
        Width = 560;
        SizeToContent = SizeToContent.Height;
        ResizeMode = ResizeMode.CanResize;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;

        var grid = new Grid { Margin = new Thickness(14) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        int row = 0;
        void Add(string label, UIElement value)
        {
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            var l = new TextBlock
            {
                Text = label,
                Margin = new Thickness(0, 4, 12, 4),
                Foreground = Brushes.DimGray,
            };
            Grid.SetRow(l, row); Grid.SetColumn(l, 0);
            Grid.SetRow((UIElement)value, row); Grid.SetColumn((UIElement)value, 1);
            grid.Children.Add(l);
            grid.Children.Add(value);
            row++;
        }

        Add("State", _state);
        Add("Listening on", _localAddr);
        Add("Capture format", _format);
        Add("Packets sent", _packets);
        Add("Data sent", _sent);
        Add("Bitrate", _bitrate);
        Add("Capture restarts", _restarts);
        Add("Clients", _clients);

        // Honest about the gap rather than showing zeros. Latency, loss and
        // buffer depth are measured by the RECEIVER -- loss is detected from
        // sequence gaps at the far end, and buffer depth exists only there.
        // WTP1 has no packet type for reporting them back, so the sender
        // genuinely cannot know them. Adding one is a protocol change, not a
        // UI change, so it is not smuggled in here.
        var note = new TextBlock
        {
            Text = "Latency, packet loss and buffer depth are measured at the "
                 + "receiver and are not reported back over WTP1. Run "
                 + "NetworkTest or MacReceiver/receiver_stats.py to see them.",
            TextWrapping = TextWrapping.Wrap,
            Foreground = Brushes.DimGray,
            FontStyle = FontStyles.Italic,
            Margin = new Thickness(0, 10, 0, 4),
        };
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        Grid.SetRow(note, row); Grid.SetColumn(note, 0); Grid.SetColumnSpan(note, 2);
        grid.Children.Add(note);
        row++;

        Add("Log", _log);

        Content = new ScrollViewer { Content = grid };
    }

    private static TextBlock Value() => new()
    {
        Margin = new Thickness(0, 4, 0, 4),
        FontFamily = new FontFamily("Consolas"),
        TextWrapping = TextWrapping.Wrap,
    };

    public void Update(SenderStatus s, string[] log)
    {
        if (!IsLoaded && !IsVisible) return;

        _state.Text = !s.Running ? "stopped"
            : s.Clients.Count > 0 ? $"streaming to {s.Clients.Count} client(s)"
            : "idle - waiting for a client to say HELLO";

        var best = LocalAddress.Best();
        _localAddr.Text = best is null
            ? $"audio {s.AudioPort}, control {s.ControlPort}  (no usable IPv4 address)"
            : $"{best}   audio {s.AudioPort}, control {s.ControlPort}";

        _format.Text = s.CaptureFormat;
        _packets.Text = $"{s.PacketsSent:N0}";
        _sent.Text = $"{s.BytesSent / 1024.0 / 1024.0:F1} MB";
        _bitrate.Text = $"{s.BitrateKbps:F0} kbit/s";
        _restarts.Text = s.CaptureRestarts.ToString();

        _clients.Items.Clear();
        if (s.Clients.Count == 0)
            _clients.Items.Add("(none)");
        else
            foreach (var c in s.Clients)
                _clients.Items.Add(
                    $"{c.Address,-16} connected {c.SecondsConnected}s, " +
                    $"last seen {c.MillisecondsSinceLastSeen} ms ago");

        string text = string.Join(Environment.NewLine, log.TakeLast(200));
        if (_log.Text != text)
        {
            _log.Text = text;
            _log.ScrollToEnd();
        }
    }
}
