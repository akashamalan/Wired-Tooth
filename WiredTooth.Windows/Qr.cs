using System.IO;
using System.Windows.Media.Imaging;
using QRCoder;

namespace WiredTooth.Windows;

/// <summary>
/// Pairing QR rendering, shared by the main window and the pop-out QR window
/// so there is one definition of what a pairing code looks like.
/// </summary>
public static class Qr
{
    public static BitmapImage Render(string text, int pixelsPerModule = 12)
    {
        using var generator = new QRCodeGenerator();
        // Q correction (25%) rather than L. The code is read off a glossy
        // laptop screen at an angle, often with reflections across it, and the
        // payload is short enough that the redundancy costs nothing that
        // matters.
        using var data = generator.CreateQrCode(text, QRCodeGenerator.ECCLevel.Q);
        var png = new PngByteQRCode(data);
        byte[] bytes = png.GetGraphic(pixelsPerModule);

        var bmp = new BitmapImage();
        bmp.BeginInit();
        bmp.StreamSource = new MemoryStream(bytes);
        bmp.CacheOption = BitmapCacheOption.OnLoad;
        bmp.EndInit();
        bmp.Freeze();
        return bmp;
    }
}
