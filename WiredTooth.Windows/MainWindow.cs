using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using WiredTooth.Sender;

// UseWindowsForms is on for NotifyIcon, which puts WinForms' Image, TextBox and
// friends in scope alongside WPF's. This window is WPF; say so once.
using Image = System.Windows.Controls.Image;
using TextBox = System.Windows.Controls.TextBox;
using Button = System.Windows.Controls.Button;
using Orientation = System.Windows.Controls.Orientation;
using HorizontalAlignment = System.Windows.HorizontalAlignment;
using Clipboard = System.Windows.Clipboard;

using Brush = System.Windows.Media.Brush;
using Brushes = System.Windows.Media.Brushes;
using FontWeights = System.Windows.FontWeights;
using Point = System.Windows.Point;

namespace WiredTooth.Windows;

/// <summary>
/// The home screen. Landscape.
///
/// Landscape is the format the content actually wants. Pairing and status are
/// two independent things a user looks at for different reasons -- one while
/// setting up, one while listening -- and side by side, neither pushes the
/// other off the screen. Stacked in a tall window, the status you check daily
/// sat below the QR code you need once.
///
/// Two rules the layout follows:
///
///   Left is setup, right is state. The eye goes left first, which is where a
///   first-time user needs to be, and a returning user's eye goes straight
///   right to the one word they came for.
///
///   Nothing here shows an IP address until asked. See <see cref="_reveal"/>.
/// </summary>
public sealed class MainWindow : Window
{
    private readonly AudioSender _sender;
    private readonly Func<string[]> _log;
    private readonly LocalMute _mute;

    private readonly ContentControl _pageHost = new();
    private UIElement? _listenPage;
    private UIElement? _customPage;

    private readonly Controls.Dot _dot = new();
    private readonly TextBlock _heroText = Theme.Hero("Starting");
    private readonly TextBlock _heroSub = Theme.Subhead("");
    private readonly Button _start;
    private readonly Button _stop;
    private readonly Controls.Toggle _silence;
    private readonly TextBlock _silenceNote = Theme.Caption("");

    private readonly Image _qrImage = new()
    {
        Width = 250,
        Height = 250,
        Stretch = Stretch.Uniform,
        SnapsToDevicePixels = true,
        HorizontalAlignment = HorizontalAlignment.Center,
    };
    private readonly TextBlock _addressText = Theme.MonoText("");
    private readonly TextBlock _networkName = Theme.Heading("Checking network...");
    private readonly TextBlock _networkNote = Theme.Caption("");
    private readonly Controls.Dot _networkDot = new();
    private readonly Button _revealButton;
    private readonly Button _copyButton;
    private readonly WrapPanel _networkChips = new() { HorizontalAlignment = HorizontalAlignment.Center };

    private readonly StackPanel _devices = new();
    private readonly Controls.StatTile _bitrate = new("Bitrate", "kbit/s");
    private readonly Controls.StatTile _sentTile = new("Sent", "MB");
    private readonly Controls.StatTile _packets = new("Packets", "");
    private readonly Controls.StatTile _restarts = new("Restarts", "");
    private readonly TextBlock _format = Theme.Caption("");

    private LocalAddress.Candidate? _selected;
    private string _chipSignature = "";

    /// <summary>
    /// Whether the address is currently on screen.
    ///
    /// Default false. The address is the one thing here that is worth hiding:
    /// a screen share, a shoulder, or a screenshot pasted into a chat all leak
    /// it, and anything that can reach it can ask for the stream -- WTP1 has no
    /// authentication. The QR stays visible because it has to be scanned to be
    /// useful and is not readable at a glance the way four numbers are.
    /// </summary>
    private bool _reveal;
    private DateTime _networkCheckedAt = DateTime.MinValue;

    /// <summary>
    /// Set by the tray's Exit so shutdown can get past <see cref="OnClosing"/>,
    /// which otherwise cancels every close in order to hide instead.
    /// </summary>
    public bool AllowClose { get; set; }

    public MainWindow(AudioSender sender, Func<string[]> log, LocalMute mute)
    {
        _sender = sender;
        _log = log;
        _mute = mute;

        Title = Brand.Name;
        Width = 1040;
        Height = 740;
        MinWidth = 900;
        MinHeight = 600;
        Background = Theme.Background;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        TextOptions.SetTextFormattingMode(this, TextFormattingMode.Ideal);

        _start = Controls.Primary("Start streaming", () =>
        {
            string? error = SenderStart.Try(_sender);
            if (error is not null)
            {
                System.Windows.MessageBox.Show(
                    error, Brand.Name,
                    System.Windows.MessageBoxButton.OK,
                    System.Windows.MessageBoxImage.Warning);
            }
            Refresh();
        });
        // Not destructive: stopping loses nothing and one click undoes it. Red
        // here would be the app crying wolf, and an interface that cries wolf
        // on a safe action gets ignored on a dangerous one.
        _stop = Controls.Secondary("Stop streaming", () => { _sender.Stop(); Refresh(); });

        _silence = new Controls.Toggle(Prefs.SilenceWhileStreaming, on =>
        {
            Prefs.SilenceWhileStreaming = on;
            Refresh();
        });

        _revealButton = Controls.Custom("i", ToggleReveal,
            Theme.SurfaceRaised, Theme.TextSecondary, Theme.Hairline);
        _revealButton.MinWidth = 34;
        _revealButton.MinHeight = 34;
        _revealButton.Padding = new Thickness(0);
        System.Windows.Automation.AutomationProperties.SetName(_revealButton, "Show address");

        _copyButton = Controls.Secondary("Copy", () =>
        {
            if (_selected is not null) Clipboard.SetText(_selected.Address.ToString());
        });
        _copyButton.MinHeight = 34;
        _copyButton.FontSize = 12;

        Resources.MergedDictionaries.Add(Theme.DarkScrollBars());
        Content = BuildBody();
        RebuildNetworkChips();
    }

    // ------------------------------------------------------------------ chrome

    private const int DwmwaUseImmersiveDarkMode = 20;

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(
        IntPtr hwnd, int attribute, ref int value, int size);

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);

        // A dark app with a white title bar looks broken rather than themed.
        // Windows 10 1809+; older builds return an error we ignore.
        IntPtr hwnd = new WindowInteropHelper(this).Handle;
        int on = 1;
        DwmSetWindowAttribute(hwnd, DwmwaUseImmersiveDarkMode, ref on, sizeof(int));
    }

    /// <summary>
    /// Closing hides instead of quitting: audio must survive the window being
    /// dismissed, like every other background media app. Exit lives in the
    /// tray, and the footer says so, because an app that ignores the close
    /// button without explaining is infuriating.
    /// </summary>
    protected override void OnClosing(System.ComponentModel.CancelEventArgs e)
    {
        if (!AllowClose)
        {
            e.Cancel = true;
            Hide();
        }
        base.OnClosing(e);
    }

    // -------------------------------------------------------------------- body

    private UIElement BuildBody()
    {
        var root = new Grid { Margin = new Thickness(Theme.S4, Theme.S3, Theme.S4, Theme.S3) };
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

        var bar = TopBar();
        Grid.SetRow(bar, 0);
        root.Children.Add(bar);

        // Two destinations, named for their contents. "Listen" is what the app
        // does; "Custom" is where per-device settings will live.
        var tabs = new Controls.Segmented(["Listen", "Custom"], ShowPage)
        {
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, Theme.S2, 0, 0),
        };
        Grid.SetRow(tabs, 1);
        root.Children.Add(tabs);

        var scroller = new ScrollViewer
        {
            Content = _pageHost,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            Background = Theme.Background,
            Margin = new Thickness(0, Theme.S2, 0, 0),
        };
        Grid.SetRow(scroller, 2);
        root.Children.Add(scroller);

        var footer = Theme.Caption(
            $"Closing this window keeps {Brand.Name} running in the notification area. "
            + "Quit it from there.");
        footer.Foreground = Theme.TextTertiary;
        footer.Margin = new Thickness(0, Theme.S2, 0, 0);
        Grid.SetRow(footer, 3);
        root.Children.Add(footer);

        ShowPage(0);
        return root;
    }

    /// <summary>Swaps the page under the tabs, with a short cross-fade.</summary>
    private void ShowPage(int index)
    {
        _listenPage ??= ListenPage();
        _customPage ??= CustomPage();

        var page = index == 0 ? _listenPage : _customPage;
        _pageHost.Content = page;

        if (Theme.ReduceMotion) return;
        // Opacity only. The tabs already moved to say where you are; sliding
        // the page as well would be two answers to the same question.
        page.BeginAnimation(UIElement.OpacityProperty,
            new DoubleAnimation(0, 1, Theme.Standard) { EasingFunction = Theme.EaseOut });
    }

    /// <summary>The placeholder second tab. Empty, and honest about it.</summary>
    private UIElement CustomPage()
    {
        var inner = Theme.Stack(Theme.S1);
        inner.Add(Theme.Heading("Nothing here yet"));
        var body = Theme.Subhead(
            "This is where per-device settings will live \u2014 what each phone does "
            + "when a call arrives, and which of them hears what. None of it is "
            + "built, so the page is empty rather than full of controls that do "
            + "nothing.");
        body.MaxWidth = 520;
        inner.Add(body);

        var card = Theme.Card(inner, Theme.S3);
        card.VerticalAlignment = VerticalAlignment.Top;
        return card;
    }

    private UIElement ListenPage()
    {
        var split = new Grid();
        split.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(360) });
        split.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var left = PairPanel();
        left.Margin = new Thickness(0, 0, Theme.S3, 0);
        Grid.SetColumn(left, 0);
        split.Children.Add(left);

        var right = StatePanel();
        Grid.SetColumn(right, 1);
        split.Children.Add(right);

        return split;
    }

    private Grid TopBar()
    {
        var bar = new Grid();
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var lockup = new StackPanel { Orientation = Orientation.Horizontal };

        var mark = Controls.Mark(30);
        mark.Margin = new Thickness(0, 0, Theme.S1 + 2, 0);
        mark.VerticalAlignment = VerticalAlignment.Center;
        lockup.Children.Add(mark);

        var word = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        var name = Theme.Title(Brand.Name);
        name.FontSize = 26;
        word.Children.Add(name);
        var tag = Theme.Caption(Brand.Tagline);
        tag.Foreground = Theme.TextTertiary;
        word.Children.Add(tag);
        lockup.Children.Add(word);

        Grid.SetColumn(lockup, 0);
        bar.Children.Add(lockup);

        // Small and grey on purpose. Settings is where you go once; making it
        // loud would have it compete with the thing you came for every time.
        var gear = Controls.Custom("⚙", OpenSettings,
            Brushes.Transparent, Theme.TextTertiary, Theme.Surface);
        gear.FontSize = 17;
        gear.MinWidth = 36;
        gear.MinHeight = 36;
        gear.Padding = new Thickness(0);
        gear.VerticalAlignment = VerticalAlignment.Top;
        System.Windows.Automation.AutomationProperties.SetName(gear, "Settings");
        Grid.SetColumn(gear, 1);
        bar.Children.Add(gear);

        return bar;
    }

    /// <summary>Left column: everything to do with getting a phone connected.</summary>
    private Border PairPanel()
    {
        var inner = Theme.Stack(Theme.S2);

        var net = Theme.Stack(2);
        var nameRow = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Center,
        };
        _networkDot.Margin = new Thickness(0, 0, Theme.S1, 0);
        nameRow.Children.Add(_networkDot);
        _networkName.HorizontalAlignment = HorizontalAlignment.Left;
        nameRow.Children.Add(_networkName);
        net.Add(nameRow);

        _networkNote.Foreground = Theme.TextTertiary;
        _networkNote.TextAlignment = TextAlignment.Center;
        net.Add(_networkNote);
        inner.Add(net);

        // The code needs a white quiet zone to scan reliably; a QR floating on
        // a dark card is a QR that phones refuse to read.
        // No frame around the code. The white that remains is the quiet zone,
        // which is part of the QR specification rather than decoration: a code
        // with no light margin is a code most scanners refuse. Removing the
        // extra padding and rounding the corners makes it read as the panel's
        // own surface rather than a bordered sticker.
        inner.Add(new Border
        {
            Background = Brushes.White,
            CornerRadius = new CornerRadius(Theme.RCard),
            Padding = new Thickness(0),
            ClipToBounds = true,
            HorizontalAlignment = HorizontalAlignment.Center,
            Child = _qrImage,
        });

        // The address row: masked value, then the reveal toggle to its right.
        var row = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Center,
        };
        _addressText.VerticalAlignment = VerticalAlignment.Center;
        _addressText.Margin = new Thickness(0, 0, Theme.S1, 0);
        row.Children.Add(_addressText);
        row.Children.Add(_revealButton);
        _copyButton.Margin = new Thickness(Theme.S1, 0, 0, 0);
        _copyButton.Visibility = Visibility.Collapsed;
        row.Children.Add(_copyButton);
        inner.Add(row);

        var hint = Theme.Caption(
            $"Open {Brand.Name} on your phone and point its camera here.");
        hint.Foreground = Theme.TextTertiary;
        hint.TextAlignment = TextAlignment.Center;
        inner.Add(hint);

        inner.Add(_networkChips);
        return Theme.Card(inner, Theme.S3);
    }

    /// <summary>Right column: what is happening right now.</summary>
    private UIElement StatePanel()
    {
        var col = Theme.Stack(Theme.S2);

        // --- status
        var status = Theme.Stack(Theme.S1);
        var line = new StackPanel { Orientation = Orientation.Horizontal };
        _dot.Margin = new Thickness(0, 0, Theme.S1 + 2, 0);
        line.Children.Add(_dot);
        line.Children.Add(_heroText);
        status.Add(line);
        status.Add(_heroSub);

        var actions = new Grid { Margin = new Thickness(0, Theme.S1, 0, 0), HorizontalAlignment = HorizontalAlignment.Left };
        _start.MinWidth = 190;
        _stop.MinWidth = 190;
        actions.Children.Add(_start);
        actions.Children.Add(_stop);
        status.Add(actions);

        var silenceRow = new Grid { Margin = new Thickness(0, Theme.S2, 0, 0) };
        silenceRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        silenceRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var silenceText = Theme.Stack(1);
        silenceText.Add(Theme.Body("Silence this computer"));
        _silenceNote.Foreground = Theme.TextTertiary;
        _silenceNote.MaxWidth = 380;
        _silenceNote.HorizontalAlignment = HorizontalAlignment.Left;
        silenceText.Add(_silenceNote);
        Grid.SetColumn(silenceText, 0);
        silenceRow.Children.Add(silenceText);

        Grid.SetColumn(_silence, 1);
        silenceRow.Children.Add(_silence);
        status.Add(silenceRow);

        col.Add(Theme.Card(status, Theme.S3));

        // --- devices
        var devicesBlock = Theme.Stack(0);
        devicesBlock.Add(Theme.Overline("Connected phones"));
        devicesBlock.Add(Theme.Card(_devices));
        col.Add(devicesBlock);

        // --- stats
        var statsBlock = Theme.Stack(0);
        statsBlock.Add(Theme.Overline("This session"));

        // A row, not a grid: landscape has the width for four across, and one
        // row of numbers scans faster than a square of them.
        var strip = new UniformGrid { Columns = 4 };
        foreach (var tile in new Border[] { _bitrate, _sentTile, _packets, _restarts })
        {
            tile.Margin = new Thickness(0, 0, Theme.S1, 0);
            strip.Children.Add(tile);
        }
        statsBlock.Add(strip);

        _format.Foreground = Theme.TextTertiary;
        _format.Margin = new Thickness(Theme.Half, Theme.S1, 0, 0);
        statsBlock.Add(_format);

        // Honest about the gap rather than showing zeros. Latency, loss and
        // buffer depth are measured by the RECEIVER -- loss is detected from
        // sequence gaps at the far end, and buffer depth exists only there.
        // WTP1 has no packet type to report them back, so inventing a plausible
        // number here would be the worst possible answer.
        var note = Theme.Caption(
            "Latency, packet loss and buffer depth are measured on the phone and "
            + "are not sent back. The phone's own screen shows them.");
        note.Foreground = Theme.TextTertiary;
        note.Margin = new Thickness(Theme.Half, Theme.Half, 0, 0);
        statsBlock.Add(note);

        col.Add(statsBlock);
        return col;
    }

    private void OpenSettings()
    {
        var w = new SettingsWindow(_sender, _log) { Owner = this };
        w.ShowDialog();
    }

    // ------------------------------------------------------------------ update

    /// <summary>Called once a second by the tray's poll timer.</summary>
    public void Refresh(SenderStatus? status = null, string[]? log = null)
    {
        var s = status ?? _sender.GetStatus();
        bool streaming = s.Running && s.Clients.Count > 0;

        if (!s.Running)
        {
            _dot.Set(Theme.TextTertiary, pulsing: false);
            _heroText.Text = "Stopped";
            _heroSub.Text = "Nothing is being sent. Your computer's audio is unaffected.";
        }
        else if (streaming)
        {
            _dot.Set(Theme.Good, pulsing: false);
            _heroText.Text = s.Clients.Count == 1
                ? "Connected"
                : $"Connected to {s.Clients.Count} phones";
            _heroSub.Text = s.Clients.Count == 1
                ? "Your phone is playing this computer's audio."
                : "All of them are hearing the same audio, in sync.";
        }
        else
        {
            _dot.Set(Theme.Accent, pulsing: true);
            _heroText.Text = "Waiting for a phone";
            _heroSub.Text = $"Open {Brand.Name} on your phone and scan the code.";
        }

        _start.Visibility = s.Running ? Visibility.Collapsed : Visibility.Visible;
        _stop.Visibility = s.Running ? Visibility.Visible : Visibility.Collapsed;

        _bitrate.Set($"{s.BitrateKbps:F0}");
        _sentTile.Set($"{s.BytesSent / 1024.0 / 1024.0:F1}");
        _packets.Set($"{s.PacketsSent:N0}");
        // A restart is the capture recovering from a device or format change.
        // One is routine; a climbing count means something is wrong upstream.
        _restarts.Set(s.CaptureRestarts.ToString(), s.CaptureRestarts > 0 ? Theme.Warn : null);
        _format.Text = $"Capturing {s.CaptureFormat}";

        UpdateDevices(s);
        RebuildNetworkChips();
        ApplySilence(streaming);

        if ((DateTime.UtcNow - _networkCheckedAt).TotalSeconds >= 10)
        {
            _networkCheckedAt = DateTime.UtcNow;
            _ = RefreshNetworkAsync();
        }
    }

    /// <summary>
    /// Names the network, and says whether it is the kind that works.
    ///
    /// A hotspot is the good case and gets said so. A large subnet is the
    /// warning case -- not because it cannot work, but because that is what a
    /// campus or office network looks like, and those routinely stop devices
    /// from reaching each other no matter how correct everything else is.
    /// </summary>
    private async Task RefreshNetworkAsync()
    {
        // ConfigureAwait(false) plus an explicit marshal, rather than relying
        // on the await to come back to the UI thread. This is fire-and-forget,
        // so a wrong-thread exception here would be swallowed silently and the
        // panel would sit on "Checking network..." forever with no clue why.
        var net = await Task.Run(WifiInfo.Current).ConfigureAwait(false);
        int hostCount = _selected is not null
            ? await Task.Run(() => WifiInfo.SubnetHosts(_selected.Address)).ConfigureAwait(false)
            : 0;

        Dispatcher.Invoke(() => PaintNetwork(net, hostCount));
    }

    private void PaintNetwork(WifiInfo.Network net, int hosts)
    {
        _networkName.Text = net.Ssid ?? "Not on Wi-Fi";

        if (net.Ssid is null)
        {
            _networkDot.Set(Theme.Warn, pulsing: false);
            _networkNote.Text =
                "This computer is not on a wireless network. Join the same Wi-Fi "
                + "as your phone, or turn on your phone's hotspot.";
            return;
        }

        if (net.OnHotspot)
        {
            _networkDot.Set(Theme.Good, pulsing: false);
            _networkNote.Text =
                $"{net.HotspotKind}. This is the best network for {Brand.Name} \u2014 "
                + "nothing sits between the two devices.";
        }
        else if (hosts > 254)
        {
            _networkDot.Set(Theme.Warn, pulsing: false);
            _networkNote.Text =
                $"A large network ({hosts:N0} addresses). Networks this size often "
                + "stop devices from reaching each other. If pairing fails, turn "
                + "on your phone's hotspot and join it from here.";
        }
        else
        {
            _networkDot.Set(Theme.Good, pulsing: false);
            _networkNote.Text = "Your phone must be on this same network.";
        }
    }

    /// <summary>
    /// Mute the speakers only while a phone is actually listening.
    ///
    /// Not while merely running: a muted machine with nothing connected is a
    /// machine whose sound is mysteriously broken. The mute goes on when the
    /// first phone connects and comes off the moment the last one leaves, so
    /// the speakers are only ever quiet while something else is playing the
    /// audio instead.
    /// </summary>
    private void ApplySilence(bool streaming)
    {
        bool want = streaming && Prefs.SilenceWhileStreaming;

        if (want && !_mute.Active) _mute.Apply();
        else if (!want && _mute.Active) _mute.Restore();

        _silenceNote.Text = !Prefs.SilenceWhileStreaming
            ? "Off \u2014 you will hear the audio from both the speakers and the phone."
            : _mute.Active
                ? "Speakers are muted while your phone is listening."
                : "Speakers will mute as soon as a phone connects.";
    }

    private void UpdateDevices(SenderStatus s)
    {
        _devices.Children.Clear();

        if (s.Clients.Count == 0)
        {
            _devices.Children.Add(Theme.Subhead(
                "None yet. Scan the code, or type the address into the phone app."));
            return;
        }

        foreach (var c in s.Clients)
        {
            // Last-seen matters more than uptime: a phone that connected an
            // hour ago but went quiet eight seconds ago is the interesting
            // case, and that is what walking out of range looks like.
            bool stale = c.MillisecondsSinceLastSeen > 3000;

            var row = new StackPanel
            {
                Orientation = Orientation.Horizontal,
                Margin = new Thickness(0, 0, 0, Theme.Half),
            };

            var dot = new Controls.Dot { Margin = new Thickness(0, 0, Theme.S1, 0) };
            dot.Set(stale ? Theme.Warn : Theme.Good, pulsing: false);
            row.Children.Add(dot);

            // The device is named by how long it has been listening, not by its
            // address. The address is the thing being kept off screen, and it
            // was never the useful part of this row anyway.
            var text = Theme.Body(stale
                ? $"A phone, quiet for {c.MillisecondsSinceLastSeen / 1000}s"
                : $"A phone, listening for {Duration(c.SecondsConnected)}");
            text.VerticalAlignment = VerticalAlignment.Center;
            row.Children.Add(text);

            _devices.Children.Add(row);
        }
    }

    private static string Duration(int seconds) =>
        seconds < 60 ? $"{seconds}s"
        : seconds < 3600 ? $"{seconds / 60}m {seconds % 60}s"
        : $"{seconds / 3600}h {seconds % 3600 / 60}m";

    // ------------------------------------------------------------- addressing

    private void ToggleReveal()
    {
        _reveal = !_reveal;
        System.Windows.Automation.AutomationProperties.SetName(
            _revealButton, _reveal ? "Hide address" : "Show address");
        _copyButton.Visibility = _reveal ? Visibility.Visible : Visibility.Collapsed;
        PaintAddress();
    }

    private void PaintAddress()
    {
        if (_selected is null)
        {
            _addressText.Text = "No network address";
            _addressText.Foreground = Theme.Bad;
            return;
        }

        _addressText.Foreground = _reveal ? Theme.TextPrimary : Theme.TextTertiary;
        _addressText.Text = _reveal
            ? _selected.Address.ToString()
            // Same shape as the real thing, so the row does not jump when it is
            // revealed and nothing is learned from the mask's length.
            : "•••.•••.•••.•••";
    }

    /// <summary>
    /// The candidate networks, as chips labelled by INTERFACE, never by address.
    ///
    /// This machine reports five IPv4 addresses and exactly one is reachable
    /// from a phone, so the choice has to be offered. Naming them "Wi-Fi" and
    /// "Ethernet" instead of printing four numbers each is both the private
    /// option and, as it turns out, the more readable one -- nobody recognises
    /// their own subnet faster than they recognise the word Wi-Fi.
    /// </summary>
    private void RebuildNetworkChips()
    {
        var candidates = LocalAddress.Candidates().ToList();

        // Rebuilding every second would fight the user's click. Only redraw
        // when the set of addresses actually changed.
        string signature = string.Join(",", candidates.Select(c => c.Address));
        if (signature == _chipSignature) return;
        _chipSignature = signature;

        _networkChips.Children.Clear();

        if (candidates.Count == 0)
        {
            _selected = null;
            _qrImage.Source = null;
            PaintAddress();
            return;
        }

        // Keep the user's pick across a rebuild if it survived the change.
        _selected = candidates.FirstOrDefault(c => c.Address.Equals(_selected?.Address))
                    ?? candidates[0];

        if (candidates.Count > 1)
        {
            var seen = new Dictionary<string, int>();
            foreach (var c in candidates)
            {
                string label = c.IsWireless ? "Wi-Fi" : c.InterfaceName;
                // Two adapters can share a name; number the duplicates rather
                // than showing two identical chips.
                if (!seen.TryAdd(label, 1)) label = $"{label} {++seen[label]}";

                var chip = Controls.Custom(label, () => { _selected = c; SelectAddress(); },
                    Theme.SurfaceRaised, Theme.TextSecondary, Theme.Hairline);
                chip.MinHeight = 30;
                chip.MinWidth = 0;
                chip.FontSize = 12;
                chip.Margin = new Thickness(0, 0, Theme.S1, Theme.S1);
                chip.Tag = c.Address;
                _networkChips.Children.Add(chip);
            }
        }

        SelectAddress();
    }

    private void SelectAddress()
    {
        if (_selected is null) return;

        string url = $"{Brand.Scheme}://{_selected.Address}:{AudioSender.AudioPort}";
        _qrImage.Source = Qr.Render(url);
        RenderOptions.SetBitmapScalingMode(_qrImage, BitmapScalingMode.NearestNeighbor);
        PaintAddress();

        // Selection is shown by fill and by text colour together, never by
        // colour alone.
        foreach (var child in _networkChips.Children)
        {
            if (child is not Button b || b.Tag is not System.Net.IPAddress ip) continue;
            bool on = ip.Equals(_selected.Address);
            b.Background = on ? Theme.Accent : Theme.SurfaceRaised;
            b.Foreground = on ? Theme.TextPrimary : Theme.TextSecondary;
        }
    }
}
