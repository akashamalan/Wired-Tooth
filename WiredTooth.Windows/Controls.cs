using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;

using Button = System.Windows.Controls.Button;
using Control = System.Windows.Controls.Control;
using HorizontalAlignment = System.Windows.HorizontalAlignment;
using Orientation = System.Windows.Controls.Orientation;

using Cursors = System.Windows.Input.Cursors;
using Brush = System.Windows.Media.Brush;
using Brushes = System.Windows.Media.Brushes;
using Color = System.Windows.Media.Color;
using FontWeights = System.Windows.FontWeights;
using Point = System.Windows.Point;
using Size = System.Windows.Size;

namespace WiredTooth.Windows;

/// <summary>
/// The component set. Three rules carried over from the phone apps:
///
///   1. Feedback happens on press-DOWN, not on release. Waiting for the mouse
///      to come back up before acknowledging a click is the cheapest way to
///      make an interface feel dead.
///   2. Colour is never the only signal. Every coloured state also carries
///      text or a shape.
///   3. Everything clickable is a real Button, so it tabs, takes Enter and
///      Space, and reports itself to Narrator. Styling a Border to look like a
///      button would have been fewer lines and unreachable by keyboard.
/// </summary>
public static class Controls
{
    /// <summary>
    /// A filled button. Press feedback is a scale to 0.97 applied on mouse-down
    /// and released on mouse-up, matching the phone apps.
    /// </summary>
    public static Button Primary(string label, Action onClick) =>
        Build(label, onClick, Theme.Accent, Theme.TextPrimary, Theme.AccentPressed);

    /// <summary>Quieter action: same geometry, no fill, hairline border.</summary>
    public static Button Secondary(string label, Action onClick, bool destructive = false) =>
        Build(label, onClick, Theme.SurfaceRaised,
              destructive ? Theme.Bad : Theme.TextPrimary, Theme.Hairline, bordered: true);

    private static Button Build(string label, Action onClick, Brush fill,
                                Brush text, Brush hoverFill, bool bordered = false)
    {
        var b = new Button
        {
            Content = label,
            Background = fill,
            Foreground = text,
            FontFamily = Theme.Text,
            FontSize = 14,
            FontWeight = FontWeights.SemiBold,
            // 40 rather than the phone's 56: a mouse pointer is far more
            // precise than a fingertip, so the 44pt touch minimum does not
            // apply. Still well above the 23px Windows default, because these
            // are the app's primary actions.
            MinHeight = 40,
            Padding = new Thickness(Theme.S2, Theme.S1, Theme.S2, Theme.S1),
            Cursor = Cursors.Hand,
            BorderThickness = new Thickness(bordered ? 1 : 0),
            BorderBrush = Theme.Hairline,
            Template = FlatTemplate(),
            RenderTransformOrigin = new Point(0.5, 0.5),
            FocusVisualStyle = null,        // replaced by our own focus ring
        };

        var scale = new ScaleTransform(1, 1);
        b.RenderTransform = scale;
        b.Click += (_, _) => onClick();

        b.PreviewMouseLeftButtonDown += (_, _) => Press(scale, 0.97);
        b.PreviewMouseLeftButtonUp += (_, _) => Press(scale, 1.0);

        b.MouseEnter += (_, _) => b.Background = hoverFill;
        b.MouseLeave += (_, _) =>
        {
            b.Background = fill;
            Press(scale, 1.0);              // dragging off cancels the press
        };

        // Hover and keyboard focus both have to be visible, and they have to be
        // told apart: focus changes the border, hover changes the fill.
        b.GotKeyboardFocus += (_, _) =>
        {
            b.BorderBrush = Theme.TextPrimary;
            b.BorderThickness = new Thickness(2);
        };
        b.LostKeyboardFocus += (_, _) =>
        {
            b.BorderBrush = Theme.Hairline;
            b.BorderThickness = new Thickness(bordered ? 1 : 0);
        };

        return b;
    }

    /// <summary>Full control over the palette, for the few buttons that need it.</summary>
    public static Button Custom(string label, Action onClick, Brush fill, Brush text,
                                Brush hover, bool bordered = true) =>
        Build(label, onClick, fill, text, hover, bordered);

    public enum KeyedTone { Accent, Quiet }

    /// <summary>
    /// A button that shows the key that also triggers it.
    ///
    /// The keycap is not decoration. A wizard where Enter silently works is a
    /// wizard nobody discovers is keyboard-driven, and one where Enter silently
    /// does nothing is worse. Printing the key on the button makes the promise
    /// and then the binding keeps it.
    /// </summary>
    public static Button KeyedButton(string label, string keycap, KeyedTone tone, Action onClick)
    {
        Button b = tone == KeyedTone.Accent
            ? Custom(label, onClick, Theme.Accent, Theme.TextPrimary, Theme.AccentPressed, bordered: false)
            // Black, as specified. It needs the hairline: pure black on a
            // near-black background is an invisible button.
            : Custom(label, onClick, Theme.Black, Theme.TextSecondary, Theme.Surface);

        b.MinWidth = 150;
        SetKeyedLabel(b, label, keycap);
        return b;
    }

    /// <summary>Relabels a keyed button without rebuilding it.</summary>
    public static void SetKeyedLabel(Button b, string label, string keycap)
    {
        var row = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Center,
        };

        var text = new TextBlock
        {
            Text = label,
            FontFamily = Theme.Text,
            FontSize = 14,
            FontWeight = FontWeights.SemiBold,
            VerticalAlignment = VerticalAlignment.Center,
        };
        row.Children.Add(text);

        row.Children.Add(new Border
        {
            CornerRadius = new CornerRadius(4),
            Background = Theme.KeycapFill,
            BorderBrush = Theme.KeycapEdge,
            BorderThickness = new Thickness(1),
            Padding = new Thickness(6, 1, 6, 2),
            Margin = new Thickness(Theme.S1, 0, 0, 0),
            VerticalAlignment = VerticalAlignment.Center,
            Child = new TextBlock
            {
                Text = keycap,
                FontFamily = Theme.Text,
                FontSize = 11,
                Opacity = 0.85,
            },
        });

        b.Content = row;
        // The spoken name is the action, not "Next Esc" read as two words.
        System.Windows.Automation.AutomationProperties.SetName(b, label);
    }

    private static void Press(ScaleTransform t, double to)
    {
        if (Theme.ReduceMotion)
        {
            t.ScaleX = t.ScaleY = to;
            return;
        }
        var a = new DoubleAnimation(to, Theme.Fast) { EasingFunction = Theme.EaseOut };
        t.BeginAnimation(ScaleTransform.ScaleXProperty, a);
        t.BeginAnimation(ScaleTransform.ScaleYProperty, a);
    }

    /// <summary>
    /// WPF's stock Button chrome is a grey Aero-era gradient that setting
    /// Background does not remove, because the default template paints over it
    /// while hovered. Replacing the template is the only way to a flat surface.
    /// </summary>
    private static ControlTemplate FlatTemplate()
    {
        var border = new FrameworkElementFactory(typeof(Border));
        border.SetValue(Border.BackgroundProperty,
            new TemplateBindingExtension(Control.BackgroundProperty));
        border.SetValue(Border.BorderBrushProperty,
            new TemplateBindingExtension(Control.BorderBrushProperty));
        border.SetValue(Border.BorderThicknessProperty,
            new TemplateBindingExtension(Control.BorderThicknessProperty));
        border.SetValue(Border.PaddingProperty,
            new TemplateBindingExtension(Control.PaddingProperty));
        border.SetValue(Border.CornerRadiusProperty, new CornerRadius(Theme.RButton));

        var content = new FrameworkElementFactory(typeof(ContentPresenter));
        content.SetValue(FrameworkElement.HorizontalAlignmentProperty,
            HorizontalAlignment.Center);
        content.SetValue(FrameworkElement.VerticalAlignmentProperty,
            VerticalAlignment.Center);
        border.AppendChild(content);

        var t = new ControlTemplate(typeof(Button)) { VisualTree = border };
        t.Seal();
        return t;
    }

    /// <summary>
    /// The mark: six petals around a centre.
    ///
    /// A wordmark on its own was too plain to be recognised at a glance, and
    /// this shape is doing double duty -- it reads as a flower, and it reads as
    /// the radiating rings from the welcome screen resolved into an object. The
    /// same idea the app is about: one centre, several petals around it.
    ///
    /// Drawn, not an asset, so it is sharp at any DPI and recolours with the
    /// palette.
    /// </summary>
    public static FrameworkElement Mark(double size)
    {
        var host = new Grid { Width = size, Height = size };

        for (int i = 0; i < 6; i++)
        {
            var petal = new Border
            {
                Width = size * 0.30,
                Height = size * 0.62,
                CornerRadius = new CornerRadius(size * 0.15),
                Background = Theme.Accent,
                // Alternating weight keeps it from reading as a loading spinner.
                Opacity = i % 2 == 0 ? 0.95 : 0.55,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                RenderTransformOrigin = new Point(0.5, 0.5),
                RenderTransform = new RotateTransform(i * 60),
            };
            host.Children.Add(petal);
        }

        host.Children.Add(new Border
        {
            Width = size * 0.22,
            Height = size * 0.22,
            CornerRadius = new CornerRadius(size * 0.11),
            Background = Theme.Background,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        });

        return host;
    }

    // ------------------------------------------------------- borrowed motion
    //
    // The three React Bits components could not be used directly -- they are
    // React, CSS and the `motion` library, and this is WPF. What follows
    // reimplements their BEHAVIOUR natively. The behaviour is the part worth
    // having; the JSX was never going to run here.

    /// <summary>
    /// BlurText, ported. Words arrive one at a time, each rising out of a blur.
    ///
    /// The blur is what makes this read as "coming into focus" rather than
    /// "sliding in", and it is why it suits a welcome screen: it looks like the
    /// sentence is being thought rather than delivered. Per-word, not
    /// per-letter -- letters shimmer at this size and slow the reading down.
    /// </summary>
    public static FrameworkElement BlurInText(
        string text, Func<string, TextBlock> style, int delayMs = 90)
    {
        var host = new WrapPanel();
        string[] words = text.Split(' ');

        for (int i = 0; i < words.Length; i++)
        {
            var word = style(words[i] + (i < words.Length - 1 ? " " : ""));

            if (Theme.ReduceMotion)
            {
                host.Children.Add(word);
                continue;
            }

            var move = new TranslateTransform(0, -22);
            var blur = new BlurEffect { Radius = 10 };
            word.RenderTransform = move;
            word.Effect = blur;
            word.Opacity = 0;

            var begin = TimeSpan.FromMilliseconds(i * delayMs);
            var span = new Duration(TimeSpan.FromSeconds(0.55));

            word.BeginAnimation(UIElement.OpacityProperty,
                new DoubleAnimation(0, 1, span) { BeginTime = begin, EasingFunction = Theme.EaseOut });
            move.BeginAnimation(TranslateTransform.YProperty,
                new DoubleAnimation(-22, 0, span) { BeginTime = begin, EasingFunction = Theme.EaseOut });
            blur.BeginAnimation(BlurEffect.RadiusProperty,
                new DoubleAnimation(10, 0, span) { BeginTime = begin, EasingFunction = Theme.EaseOut });

            host.Children.Add(word);
        }

        return host;
    }

    /// <summary>
    /// JellyRadio, ported. The chosen chip swells; its neighbours are shoved
    /// aside to make room, the nearer ones sooner.
    ///
    /// The shove is the whole trick. A segmented control where only the
    /// selection changes colour is a set of buttons; one where the row reacts
    /// to the selection reads as a physical thing being pushed through. WPF has
    /// no spring, so the overshoot comes from a small BackEase -- which is
    /// legitimate here, because this motion follows a real click.
    /// </summary>
    public sealed class Segmented : StackPanel
    {
        private readonly List<Button> _chips = [];
        private readonly List<TranslateTransform> _slides = [];
        private readonly List<ScaleTransform> _scales = [];
        private readonly Action<int> _onChange;
        private int _selected;

        private const double Swell = 0.10;   // how much the chosen chip grows
        private const double Barge = 7;      // extra pixels its neighbours yield

        public Segmented(string[] items, Action<int> onChange)
        {
            _onChange = onChange;
            Orientation = Orientation.Horizontal;

            for (int i = 0; i < items.Length; i++)
            {
                int index = i;
                var chip = Custom(items[i], () => Select(index),
                    Theme.SurfaceRaised, Theme.TextSecondary, Theme.Hairline, bordered: false);
                chip.MinHeight = 34;
                chip.MinWidth = 0;
                chip.FontSize = 13;
                chip.Margin = new Thickness(0, 0, Theme.Half + 1, 0);
                chip.RenderTransformOrigin = new Point(0.5, 0.5);

                var slide = new TranslateTransform();
                var scale = new ScaleTransform(1, 1);
                var group = new TransformGroup();
                group.Children.Add(scale);
                group.Children.Add(slide);
                chip.RenderTransform = group;

                _chips.Add(chip);
                _slides.Add(slide);
                _scales.Add(scale);
                Children.Add(chip);
            }

            Apply(0, instant: true);
        }

        public void Select(int index)
        {
            if (index == _selected) return;
            _selected = index;
            Apply(index, instant: false);
            _onChange(index);
        }

        private void Apply(int selected, bool instant)
        {
            for (int i = 0; i < _chips.Count; i++)
            {
                bool on = i == selected;
                int distance = Math.Abs(i - selected);
                double push = Math.Sign(i - selected) * Barge;
                double scale = on ? 1 + Swell : 1;

                _chips[i].Background = on ? Theme.Accent : Theme.SurfaceRaised;
                _chips[i].Foreground = on ? Theme.TextPrimary : Theme.TextSecondary;
                _chips[i].FontWeight = on ? FontWeights.SemiBold : FontWeights.Normal;

                if (instant || Theme.ReduceMotion)
                {
                    _slides[i].X = push;
                    _scales[i].ScaleX = _scales[i].ScaleY = scale;
                    continue;
                }

                // Nearer neighbours move first, which is what makes the row
                // read as pushed rather than rearranged.
                var begin = TimeSpan.FromMilliseconds(distance * 22);
                var span = new Duration(TimeSpan.FromSeconds(0.38));
                var ease = new BackEase { EasingMode = EasingMode.EaseOut, Amplitude = on ? 0.5 : 0.2 };

                _slides[i].BeginAnimation(TranslateTransform.XProperty,
                    new DoubleAnimation(push, span) { BeginTime = begin, EasingFunction = ease });
                var grow = new DoubleAnimation(scale, span) { BeginTime = begin, EasingFunction = ease };
                _scales[i].BeginAnimation(ScaleTransform.ScaleXProperty, grow);
                _scales[i].BeginAnimation(ScaleTransform.ScaleYProperty, grow);
            }
        }
    }

    /// <summary>
    /// A switch. Reads its state from its shape as well as its colour: the knob
    /// is on the left when off and the right when on, which survives greyscale.
    /// </summary>
    public sealed class Toggle : Button
    {
        private readonly Border _knob;
        private readonly TranslateTransform _slide = new();
        private bool _on;

        public Toggle(bool on, Action<bool> onChange)
        {
            Width = 46;
            Height = 28;
            Padding = new Thickness(0);
            BorderThickness = new Thickness(0);
            Cursor = Cursors.Hand;
            FocusVisualStyle = null;
            HorizontalAlignment = HorizontalAlignment.Right;
            VerticalAlignment = VerticalAlignment.Center;

            _knob = new Border
            {
                Width = 22,
                Height = 22,
                CornerRadius = new CornerRadius(11),
                Background = Theme.TextPrimary,
                HorizontalAlignment = HorizontalAlignment.Left,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(3, 0, 0, 0),
                RenderTransform = _slide,
            };

            var track = new Border
            {
                CornerRadius = new CornerRadius(14),
                Background = Theme.SurfaceRaised,
                Child = _knob,
            };

            var t = new ControlTemplate(typeof(Button));
            var host = new FrameworkElementFactory(typeof(ContentPresenter));
            t.VisualTree = host;
            t.Seal();
            Template = t;
            Content = track;

            Click += (_, _) => { _on = !_on; Paint(track, animate: true); onChange(_on); };
            _on = on;
            Paint(track, animate: false);
        }

        private void Paint(Border track, bool animate)
        {
            track.Background = _on ? Theme.Good : Theme.SurfaceRaised;
            double x = _on ? 18 : 0;

            if (animate && !Theme.ReduceMotion)
            {
                _slide.BeginAnimation(TranslateTransform.XProperty,
                    new DoubleAnimation(x, Theme.Fast) { EasingFunction = Theme.EaseOut });
            }
            else
            {
                _slide.BeginAnimation(TranslateTransform.XProperty, null);
                _slide.X = x;
            }
        }
    }

    /// <summary>
    /// Status dot. Pulses only while WAITING -- a steady dot means settled, a
    /// pulsing one means working. It always sits beside text saying the same
    /// thing, because colour alone fails for a large share of users.
    /// </summary>
    public sealed class Dot : Border
    {
        private bool _pulsing;

        // A Border with a full corner radius rather than an Ellipse, because
        // Shape's subclasses are sealed and this needs to carry its own state.
        public Dot()
        {
            Width = Height = 10;
            CornerRadius = new CornerRadius(5);
            VerticalAlignment = VerticalAlignment.Center;
            Background = Theme.TextTertiary;
        }

        public void Set(Brush colour, bool pulsing)
        {
            Background = colour;
            if (pulsing == _pulsing) return;    // do not restart a running pulse
            _pulsing = pulsing;

            if (pulsing && !Theme.ReduceMotion)
            {
                BeginAnimation(OpacityProperty, new DoubleAnimation
                {
                    From = 1.0,
                    To = 0.3,
                    // 1.6s round trip. Deliberately away from 0.2 Hz, the
                    // frequency band that reads as uncomfortable flicker.
                    Duration = new Duration(TimeSpan.FromSeconds(0.8)),
                    AutoReverse = true,
                    RepeatBehavior = RepeatBehavior.Forever,
                    EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut },
                });
            }
            else
            {
                BeginAnimation(OpacityProperty, null);
                Opacity = 1;
            }
        }
    }

    /// <summary>
    /// A single number. The value is the largest thing in the tile, the unit is
    /// split off and dimmed so the number reads first, and the label sits
    /// underneath in caption rather than beside it competing.
    /// </summary>
    public sealed class StatTile : Border
    {
        private readonly TextBlock _value = Theme.StatValue("--");

        public StatTile(string label, string unit)
        {
            Background = Theme.Surface;
            CornerRadius = new CornerRadius(Theme.RCard);
            Padding = new Thickness(Theme.S2);

            var row = new StackPanel
            {
                Orientation = Orientation.Horizontal,
                // Bottom, so the unit sits on the value's baseline rather than
                // floating at the top of its box.
                VerticalAlignment = VerticalAlignment.Bottom,
            };
            _value.VerticalAlignment = VerticalAlignment.Bottom;
            row.Children.Add(_value);

            if (unit.Length > 0)
            {
                var u = Theme.Caption(unit);
                u.Foreground = Theme.TextTertiary;
                u.VerticalAlignment = VerticalAlignment.Bottom;
                u.Margin = new Thickness(Theme.Half, 0, 0, 3);
                row.Children.Add(u);
            }

            var col = new StackPanel();
            col.Children.Add(row);
            col.Children.Add(Theme.Caption(label));
            Child = col;
        }

        /// <summary>
        /// Tone changes only when a number means something is wrong. A calm
        /// grid means everything is fine; one tile going amber tells you where
        /// to look without reading a word.
        /// </summary>
        public void Set(string value, Brush? tone = null)
        {
            _value.Text = value;
            _value.Foreground = tone ?? Theme.TextPrimary;
        }
    }
}
