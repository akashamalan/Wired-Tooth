using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using WiredTooth.Sender;

using TextBox = System.Windows.Controls.TextBox;
using Button = System.Windows.Controls.Button;
using Orientation = System.Windows.Controls.Orientation;
using HorizontalAlignment = System.Windows.HorizontalAlignment;
using Brush = System.Windows.Media.Brush;
using FontWeights = System.Windows.FontWeights;

namespace WiredTooth.Windows;

/// <summary>
/// Settings, as a dialog rather than a screen.
///
/// Everything here is something you do once or when something is wrong:
/// diagnose the network, read the log, see the introduction again. None of it
/// belongs on the home screen competing with the two things that matter, and
/// none of it is worth a navigation model.
///
/// What is deliberately NOT here: start/stop. That is the primary action and it
/// stays on the home screen. Burying the main control in a settings dialog is
/// how an interface looks minimal without being simple.
/// </summary>
public sealed class SettingsWindow : Window
{
    private readonly AudioSender _sender;
    private readonly Func<string[]> _log;

    private readonly StackPanel _results = new();
    private readonly Button _run;
    private readonly TextBox _logBox = new()
    {
        IsReadOnly = true,
        Background = Theme.Background,
        Foreground = Theme.TextSecondary,
        BorderThickness = new Thickness(0),
        FontFamily = Theme.Mono,
        FontSize = 11,
        Height = 150,
        TextWrapping = TextWrapping.NoWrap,
        VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
    };

    private bool _running;

    public SettingsWindow(AudioSender sender, Func<string[]> log)
    {
        _sender = sender;
        _log = log;

        Title = "Settings";
        Width = 620;
        Height = 640;
        MinWidth = 520;
        MinHeight = 480;
        Background = Theme.Background;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;
        ShowInTaskbar = false;

        _run = Controls.Secondary("Run checks", RunChecks);
        _run.HorizontalAlignment = HorizontalAlignment.Left;
        _run.MinWidth = 150;

        Resources.MergedDictionaries.Add(Theme.DarkScrollBars());
        Content = BuildBody();
    }

    private const int DwmwaUseImmersiveDarkMode = 20;

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(
        IntPtr hwnd, int attribute, ref int value, int size);

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        IntPtr hwnd = new WindowInteropHelper(this).Handle;
        int on = 1;
        DwmSetWindowAttribute(hwnd, DwmwaUseImmersiveDarkMode, ref on, sizeof(int));
    }

    private UIElement BuildBody()
    {
        var page = Theme.Stack(Theme.S3);
        page.Margin = new Thickness(Theme.S3);

        // --- network checks
        var checks = Theme.Stack(Theme.S2);
        checks.Add(Theme.Subhead(
            "If a phone cannot find this computer, these checks name the cause. "
            + "Nothing here changes a setting."));
        checks.Add(_run);
        checks.Add(_results);
        page.Add(Section("If it isn't working", Theme.Card(checks)));

        // --- introduction
        var intro = Theme.Stack(Theme.S2);
        intro.Add(Theme.Subhead("Watch the three-step introduction again."));
        var again = Controls.Secondary("Show the introduction", () =>
        {
            Prefs.ShowWelcome = true;
            var w = new WelcomeWindow();
            w.Show();
            Close();
        });
        again.HorizontalAlignment = HorizontalAlignment.Left;
        again.MinWidth = 220;
        intro.Add(again);
        page.Add(Section("Introduction", Theme.Card(intro)));

        // --- log
        _logBox.Text = string.Join(Environment.NewLine, _log().TakeLast(300));
        page.Add(Section("Activity log", Theme.Card(_logBox, Theme.S1)));

        // --- about
        var about = Theme.Stack(Theme.Half);
        about.Add(Row("Version", Brand.Version));
        about.Add(Row("Protocol", "WTP1"));
        about.Add(Row("Ports", $"UDP {AudioSender.AudioPort} audio, "
                             + $"{AudioSender.ControlPort} pairing"));
        page.Add(Section("About", Theme.Card(about)));

        return new ScrollViewer
        {
            Content = page,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            Background = Theme.Background,
        };
    }

    private static StackPanel Section(string label, UIElement content)
    {
        var s = Theme.Stack(0);
        s.Add(Theme.Overline(label));
        s.Add((FrameworkElement)content);
        return s;
    }

    private static FrameworkElement Row(string label, string value)
    {
        var g = new Grid();
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var l = Theme.Subhead(label);
        Grid.SetColumn(l, 0);
        g.Children.Add(l);

        var v = Theme.Subhead(value);
        v.Foreground = Theme.TextPrimary;
        Grid.SetColumn(v, 1);
        g.Children.Add(v);
        return g;
    }

    private async void RunChecks()
    {
        if (_running) return;           // netsh can take seconds; do not stack
        _running = true;
        _run.Content = "Checking...";
        _results.Children.Clear();

        try
        {
            var found = await Diagnostics.RunAsync(_sender.GetStatus());
            foreach (var r in found) _results.Children.Add(ResultRow(r));
        }
        catch (Exception ex)
        {
            _results.Children.Add(ResultRow(new Diagnostics.Result(
                Diagnostics.Level.Warn, "Checks could not finish", ex.Message)));
        }
        finally
        {
            _running = false;
            _run.Content = "Run checks";
        }
    }

    private static UIElement ResultRow(Diagnostics.Result r)
    {
        var (tone, mark) = r.Level switch
        {
            Diagnostics.Level.Ok => (Theme.Good, "OK"),
            Diagnostics.Level.Warn => (Theme.Warn, "CHECK"),
            _ => (Theme.Bad, "PROBLEM"),
        };

        var row = Theme.Stack(2);
        row.Margin = new Thickness(0, Theme.S2, 0, 0);

        var header = new StackPanel { Orientation = Orientation.Horizontal };
        // The severity is a word as well as a colour. A red dot alone is not a
        // result a colour-blind user can read.
        var badge = Theme.Caption(mark);
        badge.Foreground = tone;
        badge.FontWeight = FontWeights.Bold;
        badge.Margin = new Thickness(0, 0, Theme.S1, 0);
        header.Children.Add(badge);
        header.Children.Add(Theme.Heading(r.Title));
        row.Add(header);

        // The firewall fix is a command that must be copied exactly, so it is
        // shown in mono and stays selectable.
        if (r.Detail.Contains("netsh ", StringComparison.Ordinal))
        {
            row.Add(new TextBox
            {
                Text = r.Detail,
                IsReadOnly = true,
                Background = Theme.Background,
                Foreground = Theme.TextSecondary,
                BorderThickness = new Thickness(0),
                FontFamily = Theme.Mono,
                FontSize = 11,
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, Theme.Half, 0, 0),
            });
        }
        else
        {
            row.Add(Theme.Caption(r.Detail));
        }

        return row;
    }
}
