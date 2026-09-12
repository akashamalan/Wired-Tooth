using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using QRCoder;
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
/// The pairing QR code: wiredtooth://&lt;ip&gt;:5000
///
/// The URL is always shown as text under the code. A QR that will not scan --
/// bad lighting, a cracked screen, a camera app that ignores unknown schemes --
/// leaves the user with nothing unless the address is also readable, and typing
/// an IP is a perfectly good fallback.
///
/// The address picker matters more than it looks. This machine reports five
/// IPv4 addresses and only one is reachable from a phone; encoding the wrong
/// one gives a QR that scans perfectly and then never connects, which is a
/// miserable thing to debug from the phone end.
/// </summary>
public sealed class QrWindow : Window
{
    private readonly Image _image = new()
    {
        Width = 280,
        Height = 280,
        Stretch = Stretch.Uniform,
        // Nearest-neighbour: a QR is hard-edged by nature and bilinear
        // smoothing of a small bitmap blurs the modules enough to hurt scanning.
        SnapsToDevicePixels = true,
    };
    private readonly TextBlock _url = new()
    {
        FontFamily = new FontFamily("Consolas"),
        FontSize = 15,
        HorizontalAlignment = System.Windows.HorizontalAlignment.Center,
        Margin = new Thickness(0, 10, 0, 2),
        TextWrapping = TextWrapping.Wrap,
    };
    private readonly ComboBox _addresses = new()
    {
        Margin = new Thickness(0, 6, 0, 0),
        FontFamily = new FontFamily("Consolas"),
        FontSize = 12,
    };

    public QrWindow()
    {
        Title = "Wired Tooth - Pair";
        Width = 360;
        SizeToContent = SizeToContent.Height;
        ResizeMode = ResizeMode.NoResize;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;

        RenderOptions.SetBitmapScalingMode(_image, BitmapScalingMode.NearestNeighbor);

        var panel = new StackPanel { Margin = new Thickness(18) };
        panel.Children.Add(new TextBlock
        {
            Text = "Scan with the phone's camera",
            HorizontalAlignment = System.Windows.HorizontalAlignment.Center,
            Margin = new Thickness(0, 0, 0, 12),
            FontSize = 14,
        });
        panel.Children.Add(_image);
        panel.Children.Add(_url);

        var candidates = LocalAddress.Candidates().ToList();
        foreach (var c in candidates) _addresses.Items.Add(c);
        if (_addresses.Items.Count > 0) _addresses.SelectedIndex = 0;
        _addresses.SelectionChanged += (_, _) => RenderSelected();

        panel.Children.Add(new TextBlock
        {
            Text = candidates.Count > 1
                ? "This PC has several addresses. If the phone cannot connect, "
                  + "try another:"
                : "Address:",
            Foreground = Brushes.DimGray,
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(0, 14, 0, 0),
            FontSize = 11,
        });
        panel.Children.Add(_addresses);

        Content = panel;
        RenderSelected();
    }

    private void RenderSelected()
    {
        if (_addresses.SelectedItem is not LocalAddress.Candidate c)
        {
            _url.Text = "No usable IPv4 address found";
            _image.Source = null;
            return;
        }

        string url = $"wiredtooth://{c.Address}:{AudioSender.AudioPort}";
        _url.Text = url;
        _image.Source = Render(url);
    }

    private static BitmapImage Render(string text)
    {
        using var generator = new QRCodeGenerator();
        // Q correction (25%) rather than L. The code is read off a glossy
        // laptop screen at an angle, often with reflections across it, and the
        // payload is short enough that the extra redundancy costs nothing that
        // matters.
        using var data = generator.CreateQrCode(text, QRCodeGenerator.ECCLevel.Q);
        var png = new PngByteQRCode(data);
        byte[] bytes = png.GetGraphic(12);

        var bmp = new BitmapImage();
        bmp.BeginInit();
        bmp.StreamSource = new MemoryStream(bytes);
        bmp.CacheOption = BitmapCacheOption.OnLoad;
        bmp.EndInit();
        bmp.Freeze();
        return bmp;
    }
}
