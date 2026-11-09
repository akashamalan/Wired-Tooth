using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;

// UseWindowsForms is on for NotifyIcon, which puts WinForms' types in scope
// alongside WPF's. These files are WPF; say so once.
using FontFamily = System.Windows.Media.FontFamily;
using HorizontalAlignment = System.Windows.HorizontalAlignment;
using Orientation = System.Windows.Controls.Orientation;

using ColorConverter = System.Windows.Media.ColorConverter;
using Brush = System.Windows.Media.Brush;
using Brushes = System.Windows.Media.Brushes;
using Color = System.Windows.Media.Color;
using FontWeights = System.Windows.FontWeights;
using Point = System.Windows.Point;
using Size = System.Windows.Size;

namespace WiredTooth.Windows;

/// <summary>
/// Design tokens, ported from AndroidReceiver/DESIGN.md so the Windows app and
/// the phone apps read as one product rather than two projects that happen to
/// share a protocol.
///
/// Two deliberate translations away from the phone spec:
///
///   Sizes are smaller. A desktop monitor is viewed from roughly twice the
///   distance of a phone, so the phone's 56pt hero would be shouting here. The
///   HIERARCHY is identical -- status is still by far the largest thing on
///   screen -- only the absolute scale moved.
///
///   Tracking is absent. WPF's TextBlock has no letter-spacing, so the
///   size-specific tracking table from DESIGN.md cannot be expressed. Rather
///   than fake it with per-character runs, it is dropped and said out loud.
/// </summary>
public static class Theme
{
    // ---------------------------------------------------------------- space
    // The same 8pt grid. 4 stays a half-step for optical corrections only.
    public const double Half = 4;
    public const double S1 = 8;
    public const double S2 = 16;
    public const double S3 = 24;
    public const double S4 = 32;
    public const double S5 = 48;

    // --------------------------------------------------------------- radius
    public const double RButton = 10;
    public const double RCard = 12;
    public const double RPill = 999;

    // --------------------------------------------------------------- colour
    // Dark only. This is a background utility that sits on a second monitor at
    // night; a light palette nobody asked for is a maintenance cost, and the
    // phone apps set the reference.
    public static readonly Brush Background = Frozen("#FF0B0B0F");
    public static readonly Brush Surface = Frozen("#FF17171C");
    public static readonly Brush SurfaceRaised = Frozen("#FF1F1F26");
    public static readonly Brush Hairline = Frozen("#FF2A2A33");

    /// <summary>True black, for the quiet half of a two-button pair.</summary>
    public static readonly Brush Black = Frozen("#FF000000");

    // A keycap reads as a physical key because it is lighter than the surface
    // it sits on and has a defined edge, the same way a real one catches light.
    public static readonly Brush KeycapFill = Frozen("#22FFFFFF");
    public static readonly Brush KeycapEdge = Frozen("#33FFFFFF");

    public static readonly Brush TextPrimary = Frozen("#FFF5F5F7");
    public static readonly Brush TextSecondary = Frozen("#FF9A9AA5");
    public static readonly Brush TextTertiary = Frozen("#FF6A6A75");

    // iOS system blue in dark mode, matching the phone app exactly.
    public static readonly Brush Accent = Frozen("#FF0A84FF");
    public static readonly Brush AccentPressed = Frozen("#FF0969CC");
    public static readonly Brush Good = Frozen("#FF32D74B");
    public static readonly Brush Warn = Frozen("#FFFF9F0A");
    public static readonly Brush Bad = Frozen("#FFFF453A");

    private static Brush Frozen(string hex)
    {
        var b = new SolidColorBrush((Color)ColorConverter.ConvertFromString(hex));
        b.Freeze();     // shared across threads and never mutated
        return b;
    }

    // ----------------------------------------------------------------- type
    // Segoe UI Variable is the Windows 11 system face and ships its own optical
    // sizing; Segoe UI is the Windows 10 fallback. Naming both lets WPF pick.
    public static readonly FontFamily Display =
        new("Segoe UI Variable Display, Segoe UI");
    public static readonly FontFamily Text =
        new("Segoe UI Variable Text, Segoe UI");
    public static readonly FontFamily Mono =
        new("Cascadia Mono, Consolas");

    public static TextBlock Hero(string s) => Make(s, Display, 26, FontWeights.SemiBold, TextPrimary);
    public static TextBlock Title(string s) => Make(s, Display, 30, FontWeights.Bold, TextPrimary);
    public static TextBlock StatValue(string s) => Make(s, Display, 22, FontWeights.SemiBold, TextPrimary);
    public static TextBlock Heading(string s) => Make(s, Text, 15, FontWeights.SemiBold, TextPrimary);
    public static TextBlock Body(string s) => Make(s, Text, 14, FontWeights.Normal, TextPrimary);
    public static TextBlock Subhead(string s) => Make(s, Text, 13, FontWeights.Normal, TextSecondary);
    public static TextBlock Caption(string s) => Make(s, Text, 12, FontWeights.Normal, TextSecondary);
    public static TextBlock MonoText(string s) => Make(s, Mono, 13, FontWeights.Normal, TextPrimary);

    /// <summary>
    /// The only all-caps text in the app. Caps are hard to read in quantity, so
    /// they stay confined to short group labels.
    /// </summary>
    public static TextBlock Overline(string s)
    {
        var t = Make(s.ToUpperInvariant(), Text, 11, FontWeights.SemiBold, TextTertiary);
        t.Margin = new Thickness(Half, 0, 0, S1);
        return t;
    }

    private static TextBlock Make(string s, FontFamily f, double size,
                                  FontWeight w, Brush colour) => new()
    {
        Text = s,
        FontFamily = f,
        FontSize = size,
        FontWeight = w,
        Foreground = colour,
        TextWrapping = TextWrapping.Wrap,
    };

    // --------------------------------------------------------------- motion
    // WPF has no spring. DESIGN.md's specs are damping + response, so the
    // closest honest translation is an ease-out curve at the same response
    // time: critically damped (damping 1.0) means "no overshoot", which is
    // exactly what an ease-out does. Momentum springs, which DO overshoot,
    // have no gesture on this platform to justify them, so nothing here
    // bounces.
    public static readonly Duration Fast = new(TimeSpan.FromSeconds(0.30));
    public static readonly Duration Standard = new(TimeSpan.FromSeconds(0.40));

    public static IEasingFunction EaseOut =>
        new CubicEase { EasingMode = EasingMode.EaseOut };

    /// <summary>
    /// Windows' "Show animations" setting, off by default under Ease of Access.
    /// It is this platform's prefers-reduced-motion, and it is respected for
    /// the same reason: motion that aids comprehension stays, motion that only
    /// decorates goes.
    /// </summary>
    public static bool ReduceMotion => !SystemParameters.ClientAreaAnimation;

    // ------------------------------------------------------------ scrollbars

    /// <summary>
    /// WPF's stock ScrollBar is a light grey Windows-7 relic with stepper
    /// arrows, and it cannot be recoloured by properties -- the template
    /// hard-codes its brushes. A light scrollbar down the edge of a dark window
    /// is the single most obvious "this is a themed app, not a designed one"
    /// tell, so it gets replaced.
    ///
    /// Written as XAML and parsed rather than assembled from
    /// FrameworkElementFactory: a nested Track/Thumb/RepeatButton template is
    /// roughly eighty lines of factory code for something XAML says plainly.
    /// </summary>
    public static ResourceDictionary DarkScrollBars()
    {
        const string xaml = """
        <ResourceDictionary xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
                            xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">
          <Style TargetType="{x:Type ScrollBar}">
            <Setter Property="Background" Value="Transparent"/>
            <Setter Property="Width" Value="10"/>
            <Setter Property="Template">
              <Setter.Value>
                <ControlTemplate TargetType="{x:Type ScrollBar}">
                  <Grid Background="Transparent">
                    <Track x:Name="PART_Track" IsDirectionReversed="True">
                      <Track.Thumb>
                        <Thumb>
                          <Thumb.Template>
                            <ControlTemplate TargetType="{x:Type Thumb}">
                              <Border CornerRadius="3" Background="#FF3A3A45" Margin="2,0"/>
                            </ControlTemplate>
                          </Thumb.Template>
                        </Thumb>
                      </Track.Thumb>
                      <Track.IncreaseRepeatButton>
                        <RepeatButton Command="ScrollBar.PageDownCommand"
                                      Opacity="0" Focusable="False"/>
                      </Track.IncreaseRepeatButton>
                      <Track.DecreaseRepeatButton>
                        <RepeatButton Command="ScrollBar.PageUpCommand"
                                      Opacity="0" Focusable="False"/>
                      </Track.DecreaseRepeatButton>
                    </Track>
                  </Grid>
                </ControlTemplate>
              </Setter.Value>
            </Setter>
          </Style>
        </ResourceDictionary>
        """;

        return (ResourceDictionary)System.Windows.Markup.XamlReader.Parse(xaml);
    }

    // ----------------------------------------------------------- containers
    /// <summary>A card: the default surface for a group of related things.</summary>
    public static Border Card(UIElement child, double pad = S2) => new()
    {
        Background = Surface,
        CornerRadius = new CornerRadius(RCard),
        Padding = new Thickness(pad),
        Child = child,
    };

    public static StackPanel Stack(double spacing = S1, Orientation o = Orientation.Vertical)
    {
        var p = new StackPanel { Orientation = o };
        // WPF has no gap property, so spacing is applied per child on add.
        p.Tag = spacing;
        return p;
    }

    /// <summary>Adds a child honouring the spacing recorded by <see cref="Stack"/>.</summary>
    public static T Add<T>(this StackPanel panel, T child) where T : FrameworkElement
    {
        if (panel.Children.Count > 0 && panel.Tag is double gap)
        {
            child.Margin = panel.Orientation == Orientation.Vertical
                ? new Thickness(0, gap, 0, 0)
                : new Thickness(gap, 0, 0, 0);
        }
        panel.Children.Add(child);
        return child;
    }
}
