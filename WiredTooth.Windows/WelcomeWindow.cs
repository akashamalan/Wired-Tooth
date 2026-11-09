using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;

using Button = System.Windows.Controls.Button;
using Orientation = System.Windows.Controls.Orientation;
using HorizontalAlignment = System.Windows.HorizontalAlignment;
using Brush = System.Windows.Media.Brush;
using Brushes = System.Windows.Media.Brushes;
using FontWeights = System.Windows.FontWeights;
using KeyEventArgs = System.Windows.Input.KeyEventArgs;
using Point = System.Windows.Point;

namespace WiredTooth.Windows;

/// <summary>
/// First-run introduction. Landscape, three steps, shown once.
///
/// Landscape is not decoration here -- it is what lets the illustration and the
/// words sit side by side instead of stacked, which is the whole reason a
/// desktop onboarding can be read at a glance while a phone one has to be
/// scrolled.
///
/// Navigation is deliberately bound to the keyboard as well as the buttons, and
/// the buttons SAY which key they are. A wizard where Enter does nothing
/// teaches people to reach for the mouse for everything.
/// </summary>
public sealed class WelcomeWindow : Window
{
    private sealed record Page(
        string Step,
        string Title,
        string Body,
        string[] Points,
        Func<UIElement> Art);

    private readonly Page[] _pages;
    private int _index;

    private readonly ContentControl _art = new();
    private readonly StackPanel _copy = new();
    private readonly Button _previous;
    private readonly Button _next;
    private readonly StackPanel _dots = new() { Orientation = Orientation.Horizontal };

    /// <summary>Raised once the user has finished or dismissed the flow.</summary>
    public event Action? Finished;

    public WelcomeWindow()
    {
        _pages =
        [
            new Page(
                "Step 1",
                "Two sets of ears, one sound",
                $"{Brand.Name} sends your computer's audio to any phone on the same "
                + "Wi-Fi. More than one phone can listen at the same time, to the "
                + "same audio, in sync.",
                [
                    "Watch a film together, each on your own earbuds",
                    "No pairing dance, no six-device Bluetooth limit",
                    "Every listener hears the same moment",
                ],
                RadiatingArt),

            new Page(
                "Step 2",
                "Wired earbuds, no waiting",
                "Bluetooth adds its own delay on top of everything else — enough "
                + "that lips stop matching voices. Plug wired earbuds into the "
                + "phone and that delay is simply not there.",
                [
                    "Any wired earbuds work. Nothing to charge",
                    "Nothing is installed on your computer's audio path",
                    "Your computer's speakers mute while the phone listens, so "
                        + "you do not hear everything twice",
                ],
                LatencyArt),

            new Page(
                "Step 3",
                "Three things and you're listening",
                "Setup is short, and these are the only three things that ever go "
                + "wrong. Getting them right now saves the guessing later.",
                [
                    "Put the phone and this computer on the same Wi-Fi. A Personal "
                        + "Hotspot is the most reliable option",
                    "Allow the app through Windows Firewall when asked. It only "
                        + "listens on your own network",
                    "Plug wired earbuds into the phone before you start",
                ],
                ChecklistArt),
        ];

        Title = Brand.Name;
        Width = 960;
        Height = 580;
        MinWidth = 820;
        MinHeight = 520;
        Background = Theme.Background;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;

        _previous = Controls.KeyedButton("Previous", "Esc", Controls.KeyedTone.Quiet, Back);
        _next = Controls.KeyedButton("Next", "↵", Controls.KeyedTone.Accent, Forward);

        Content = BuildBody();
        Show(0, animate: false);

        KeyDown += OnKeyDown;
    }

    // --------------------------------------------------------------- chrome

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

    /// <summary>
    /// Closing the window counts as finishing. Refusing to close an
    /// introduction traps the user in the one part of the app that has nothing
    /// they need, which is the fastest way to make it feel hostile.
    /// </summary>
    protected override void OnClosed(EventArgs e)
    {
        Prefs.ShowWelcome = false;
        Finished?.Invoke();
        base.OnClosed(e);
    }

    private void OnKeyDown(object sender, KeyEventArgs e)
    {
        switch (e.Key)
        {
            case Key.Enter or Key.Right: Forward(); e.Handled = true; break;
            case Key.Escape or Key.Left: Back(); e.Handled = true; break;
        }
    }

    // ----------------------------------------------------------------- body

    private UIElement BuildBody()
    {
        var root = new Grid { Margin = new Thickness(Theme.S5, Theme.S4, Theme.S5, Theme.S3) };
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

        // Illustration left, words right. Deliberately not centred: a reader's
        // eye starts at the left, and the picture is the thing that makes the
        // words make sense.
        var split = new Grid();
        split.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.9, GridUnitType.Star) });
        split.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.1, GridUnitType.Star) });

        _art.Margin = new Thickness(0, 0, Theme.S5, 0);
        _art.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(_art, 0);
        split.Children.Add(_art);

        _copy.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(_copy, 1);
        split.Children.Add(_copy);

        Grid.SetRow(split, 0);
        root.Children.Add(split);

        var footer = Footer();
        Grid.SetRow(footer, 1);
        root.Children.Add(footer);
        return root;
    }

    private Grid Footer()
    {
        var bar = new Grid { Margin = new Thickness(0, Theme.S3, 0, 0) };
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        _previous.HorizontalAlignment = HorizontalAlignment.Left;
        _previous.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(_previous, 0);
        bar.Children.Add(_previous);

        _dots.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(_dots, 1);
        bar.Children.Add(_dots);

        _next.HorizontalAlignment = HorizontalAlignment.Right;
        _next.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(_next, 2);
        bar.Children.Add(_next);

        return bar;
    }

    // ------------------------------------------------------------ paging

    private void Forward()
    {
        if (_index >= _pages.Length - 1) { Close(); return; }
        Show(_index + 1, animate: true);
    }

    private void Back()
    {
        if (_index == 0) return;        // nothing behind the first page
        Show(_index - 1, animate: true);
    }

    private void Show(int index, bool animate)
    {
        _index = index;
        var page = _pages[index];

        _art.Content = page.Art();
        _copy.Children.Clear();

        var step = Theme.Overline(page.Step);
        step.Foreground = Theme.Accent;
        _copy.Children.Add(step);

        var title = Controls.BlurInText(page.Title, Theme.Title, delayMs: 85);
        title.Margin = new Thickness(0, 0, 0, Theme.S2);
        title.MaxWidth = 470;
        title.HorizontalAlignment = HorizontalAlignment.Left;
        _copy.Children.Add(title);

        var body = Theme.Body(page.Body);
        body.Foreground = Theme.TextSecondary;
        body.Margin = new Thickness(0, 0, 0, Theme.S3);
        body.MaxWidth = 460;
        body.HorizontalAlignment = HorizontalAlignment.Left;
        _copy.Children.Add(body);

        foreach (string point in page.Points) _copy.Children.Add(Bullet(point));

        // The back button is absent on the first page rather than disabled. A
        // greyed control still invites a click and then refuses it; an absent
        // one says there is nothing behind you.
        _previous.Visibility = index == 0 ? Visibility.Hidden : Visibility.Visible;
        _next.Content = null;
        Controls.SetKeyedLabel(_next, index == _pages.Length - 1 ? "Get started" : "Next", "↵");

        RebuildDots();

        if (animate && !Theme.ReduceMotion) Enter(_copy, _art);
    }

    private static UIElement Bullet(string text)
    {
        var row = new Grid { Margin = new Thickness(0, 0, 0, Theme.S1 + 2) };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var dot = new Border
        {
            Width = 6,
            Height = 6,
            CornerRadius = new CornerRadius(3),
            Background = Theme.Accent,
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(2, 8, Theme.S1 + 2, 0),
        };
        Grid.SetColumn(dot, 0);
        row.Children.Add(dot);

        var t = Theme.Subhead(text);
        t.MaxWidth = 440;
        t.HorizontalAlignment = HorizontalAlignment.Left;
        Grid.SetColumn(t, 1);
        row.Children.Add(t);
        return row;
    }

    private void RebuildDots()
    {
        _dots.Children.Clear();
        for (int i = 0; i < _pages.Length; i++)
        {
            bool active = i == _index;
            _dots.Children.Add(new Border
            {
                // The active step is wider as well as brighter, so the position
                // survives being seen in greyscale or out of the corner of an eye.
                Width = active ? 22 : 7,
                Height = 7,
                CornerRadius = new CornerRadius(3.5),
                Background = active ? Theme.Accent : Theme.Hairline,
                Margin = new Thickness(3, 0, 3, 0),
            });
        }
    }

    /// <summary>
    /// The page transition: a slow rise and fade, 0.45s, no bounce.
    ///
    /// Slow on purpose. This screen is read, not operated, and a snappy
    /// transition on a page of prose makes the reader feel hurried. Everywhere
    /// else in the app motion is 0.3s because everywhere else is a control.
    /// </summary>
    private static void Enter(params UIElement[] elements)
    {
        foreach (var el in elements)
        {
            var move = new TranslateTransform(0, 18);
            el.RenderTransform = move;

            var fade = new DoubleAnimation(0, 1, new Duration(TimeSpan.FromSeconds(0.45)))
            {
                EasingFunction = Theme.EaseOut,
            };
            var rise = new DoubleAnimation(18, 0, new Duration(TimeSpan.FromSeconds(0.45)))
            {
                EasingFunction = Theme.EaseOut,
            };
            el.BeginAnimation(OpacityProperty, fade);
            move.BeginAnimation(TranslateTransform.YProperty, rise);
        }
    }

    // ------------------------------------------------------------------ art
    //
    // Drawn rather than shipped as images: three PNGs would have to exist at
    // several scale factors and in two themes, and these are simple enough that
    // vector shapes are smaller in every sense.

    /// <summary>Two listeners, one source. Rings travel outward, slowly.</summary>
    private static UIElement RadiatingArt()
    {
        var host = new Grid { Height = 300 };

        host.Children.Add(new Border
        {
            Width = 210,
            Height = 210,
            CornerRadius = new CornerRadius(105),
            BorderBrush = Theme.Hairline,
            BorderThickness = new Thickness(1),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        });

        for (int i = 0; i < 3; i++)
        {
            var ring = new Border
            {
                Width = 120,
                Height = 120,
                CornerRadius = new CornerRadius(60),
                BorderBrush = Theme.Accent,
                BorderThickness = new Thickness(1.4),
                Opacity = 0,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                RenderTransformOrigin = new Point(0.5, 0.5),
            };
            var grow = new ScaleTransform(1, 1);
            ring.RenderTransform = grow;
            host.Children.Add(ring);

            if (Theme.ReduceMotion)
            {
                // Static concentric rings say the same thing without motion.
                grow.ScaleX = grow.ScaleY = 1 + i * 0.6;
                ring.Opacity = 0.35 - i * 0.1;
                continue;
            }

            // A 4.2s cycle: slow enough to read as "reaching outward" rather
            // than "loading", and well away from the flicker band.
            var period = TimeSpan.FromSeconds(4.2);
            var begin = TimeSpan.FromSeconds(i * 1.4);

            var scale = new DoubleAnimation(1, 2.5, new Duration(period))
            {
                BeginTime = begin,
                RepeatBehavior = RepeatBehavior.Forever,
                EasingFunction = new SineEase { EasingMode = EasingMode.EaseOut },
            };
            var fade = new DoubleAnimationUsingKeyFrames
            {
                BeginTime = begin,
                Duration = new Duration(period),
                RepeatBehavior = RepeatBehavior.Forever,
            };
            fade.KeyFrames.Add(new LinearDoubleKeyFrame(0.0, KeyTime.FromPercent(0)));
            fade.KeyFrames.Add(new LinearDoubleKeyFrame(0.5, KeyTime.FromPercent(0.25)));
            fade.KeyFrames.Add(new LinearDoubleKeyFrame(0.0, KeyTime.FromPercent(1)));

            grow.BeginAnimation(ScaleTransform.ScaleXProperty, scale);
            grow.BeginAnimation(ScaleTransform.ScaleYProperty, scale);
            ring.BeginAnimation(OpacityProperty, fade);
        }

        // The source at the centre, a listener either side.
        host.Children.Add(Chip(104, 74, Theme.Accent, HorizontalAlignment.Center, 0));
        host.Children.Add(Chip(56, 96, Theme.SurfaceRaised, HorizontalAlignment.Center, -190));
        host.Children.Add(Chip(56, 96, Theme.SurfaceRaised, HorizontalAlignment.Center, 190));
        return host;

        static Border Chip(double w, double h, Brush fill, HorizontalAlignment align, double dx) => new()
        {
            Width = w,
            Height = h,
            CornerRadius = new CornerRadius(10),
            Background = fill,
            HorizontalAlignment = align,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(dx, 0, 0, 0),
            BorderBrush = Theme.Hairline,
            BorderThickness = new Thickness(1),
        };
    }

    /// <summary>
    /// Two delays, side by side. No numbers: the honest figure depends on the
    /// earbuds, the phone and the network, and a made-up millisecond count on a
    /// welcome screen is a promise the app has not measured on this machine.
    /// </summary>
    private static UIElement LatencyArt()
    {
        var col = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        col.Children.Add(Bar("Bluetooth earbuds", 1.0, Theme.TextTertiary, "long delay"));
        col.Children.Add(Bar("Wired earbuds", 0.22, Theme.Accent, "barely any"));
        return col;

        static UIElement Bar(string label, double fraction, Brush fill, string note)
        {
            var wrap = new StackPanel { Margin = new Thickness(0, 0, 0, Theme.S3) };
            wrap.Children.Add(Theme.Caption(label));

            var track = new Border
            {
                Height = 22,
                CornerRadius = new CornerRadius(11),
                Background = Theme.Surface,
                Margin = new Thickness(0, Theme.Half, 0, Theme.Half),
                HorizontalAlignment = HorizontalAlignment.Stretch,
            };
            var fillBar = new Border
            {
                Height = 22,
                CornerRadius = new CornerRadius(11),
                Background = fill,
                HorizontalAlignment = HorizontalAlignment.Left,
            };
            // Width comes from the track's real width, so the comparison holds
            // at any window size, and it GROWS into place -- watching the
            // Bluetooth bar take four times as long to fill says more than the
            // finished lengths do.
            track.SizeChanged += (_, e) =>
            {
                double target = e.NewSize.Width * fraction;
                if (Theme.ReduceMotion)
                {
                    fillBar.Width = target;
                    return;
                }
                fillBar.Width = 0;
                fillBar.BeginAnimation(FrameworkElement.WidthProperty,
                    new DoubleAnimation(0, target,
                        new Duration(TimeSpan.FromSeconds(0.45 + fraction * 0.9)))
                    {
                        BeginTime = TimeSpan.FromMilliseconds(180),
                        EasingFunction = Theme.EaseOut,
                    });
            };
            var stack = new Grid();
            stack.Children.Add(track);
            stack.Children.Add(fillBar);
            wrap.Children.Add(stack);

            var n = Theme.Caption(note);
            n.Foreground = Theme.TextTertiary;
            wrap.Children.Add(n);
            return wrap;
        }
    }

    /// <summary>The three prerequisites, as a list that looks like the checks.</summary>
    private static UIElement ChecklistArt()
    {
        var col = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        foreach (string s in new[] { "Same Wi-Fi", "Firewall allowed", "Earbuds plugged in" })
        {
            var row = new Border
            {
                Background = Theme.Surface,
                CornerRadius = new CornerRadius(Theme.RCard),
                Padding = new Thickness(Theme.S2),
                Margin = new Thickness(0, 0, 0, Theme.S1),
            };
            var line = new StackPanel { Orientation = Orientation.Horizontal };
            line.Children.Add(new Border
            {
                Width = 18,
                Height = 18,
                CornerRadius = new CornerRadius(9),
                Background = Theme.Accent,
                Margin = new Thickness(0, 0, Theme.S1 + 2, 0),
                VerticalAlignment = VerticalAlignment.Center,
            });
            var t = Theme.Body(s);
            t.VerticalAlignment = VerticalAlignment.Center;
            line.Children.Add(t);
            row.Child = line;

            if (!Theme.ReduceMotion)
            {
                var slide = new TranslateTransform(-26, 0);
                row.RenderTransform = slide;
                row.Opacity = 0;
                var begin = TimeSpan.FromMilliseconds(120 * col.Children.Count);
                var span = new Duration(TimeSpan.FromSeconds(0.5));
                row.BeginAnimation(OpacityProperty,
                    new DoubleAnimation(0, 1, span) { BeginTime = begin, EasingFunction = Theme.EaseOut });
                slide.BeginAnimation(TranslateTransform.XProperty,
                    new DoubleAnimation(-26, 0, span) { BeginTime = begin, EasingFunction = Theme.EaseOut });
            }

            col.Children.Add(row);
        }
        return col;
    }
}
