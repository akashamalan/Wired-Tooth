using System.Buffers.Binary;
using System.Diagnostics;
using System.Text;

namespace WiredTooth.Protocol;

/// <summary>
/// Parsed view of a WTP1 header. Payload is handed back separately as a slice
/// of the caller's buffer so parsing a packet allocates nothing.
/// </summary>
public readonly record struct WtpHeader(
    PacketType Type,
    PacketFlags Flags,
    ushort PayloadLength,
    uint Sequence,
    long TimestampMicroseconds,
    uint SampleRate,
    ushort Channels,
    byte BitsPerSample,
    byte Codec)
{
    public bool IsSilence => (Flags & PacketFlags.Silence) != 0;
}

/// <summary>
/// Wired Tooth Protocol v1. Byte layout is specified in docs/PROTOCOL.md and
/// is the contract between this code and the Swift receiver, so nothing here
/// may change without changing that document and the magic.
///
/// Little-endian throughout. Chosen because both ends are little-endian in
/// practice (x86 and ARM), so neither side pays a byte swap per packet.
/// </summary>
public static class WtpPacket
{
    /// <summary>"WTP1". A wrong-magic datagram is someone else's traffic on
    /// our port, and must be dropped rather than interpreted as audio.</summary>
    public static ReadOnlySpan<byte> Magic => "WTP1"u8;

    public const int CommonHeaderSize = 20;
    public const int AudioSubHeaderSize = 8;
    public const int AudioHeaderSize = CommonHeaderSize + AudioSubHeaderSize;

    /// <summary>
    /// Ceiling on a whole datagram. Wi-Fi links usually carry a 1500 byte MTU
    /// and IP fragmentation on a lossy wireless link turns one lost fragment
    /// into a lost frame, so we stay comfortably under it.
    /// </summary>
    public const int MaxDatagramSize = 1400;

    public const int MaxAudioPayload = MaxDatagramSize - AudioHeaderSize; // 1372

    public const byte CodecPcm16 = 0;

    /// <summary>
    /// How much silence a silence-flagged AUDIO packet with an empty payload
    /// stands for, in milliseconds.
    ///
    /// WASAPI loopback stops firing DataAvailable entirely when nothing is
    /// playing, so the sender emits one of these every SilenceKeepaliveMs to
    /// keep the receiver's buffer fed and its RTT tracking alive. The duration
    /// cannot be derived from the packet -- the payload is empty by design, to
    /// avoid sending kilobytes of zeros -- so it is a shared constant and both
    /// implementations must agree on it. The Swift receiver reads this value
    /// from docs/PROTOCOL.md.
    /// </summary>
    public const int SilenceKeepaliveMs = 100;

    // Microseconds, not milliseconds: at 48kHz one millisecond is 48 frames,
    // which is far too coarse to measure a latency figure that is the whole
    // point of the project. Stopwatch, not DateTime, because we need a
    // monotonic clock that an NTP correction cannot step backwards.
    private static readonly long StartTicks = Stopwatch.GetTimestamp();

    public static long TimestampMicroseconds =>
        (Stopwatch.GetTimestamp() - StartTicks) * 1_000_000L / Stopwatch.Frequency;

    private static void WriteCommon(Span<byte> dest, PacketType type,
                                    PacketFlags flags, int payloadLength,
                                    uint sequence, long timestampUs)
    {
        Magic.CopyTo(dest);
        dest[4] = (byte)type;
        dest[5] = (byte)flags;
        BinaryPrimitives.WriteUInt16LittleEndian(dest[6..], (ushort)payloadLength);
        BinaryPrimitives.WriteUInt32LittleEndian(dest[8..], sequence);
        BinaryPrimitives.WriteInt64LittleEndian(dest[12..], timestampUs);
    }

    /// <summary>
    /// Writes the 28-byte AUDIO header into <paramref name="dest"/> and returns
    /// its size. The caller copies the payload in at offset 28.
    /// </summary>
    public static int WriteAudioHeader(Span<byte> dest, uint sequence,
                                       long timestampUs, int payloadLength,
                                       int sampleRate, int channels,
                                       int bitsPerSample,
                                       PacketFlags flags = PacketFlags.None,
                                       byte codec = CodecPcm16)
    {
        if (dest.Length < AudioHeaderSize)
            throw new ArgumentException(
                $"need {AudioHeaderSize} bytes for an audio header", nameof(dest));
        if ((uint)payloadLength > MaxAudioPayload)
            throw new ArgumentOutOfRangeException(nameof(payloadLength),
                $"payload {payloadLength} exceeds {MaxAudioPayload}; chunk it");

        WriteCommon(dest, PacketType.Audio, flags, payloadLength, sequence,
                    timestampUs);
        BinaryPrimitives.WriteUInt32LittleEndian(dest[20..], (uint)sampleRate);
        BinaryPrimitives.WriteUInt16LittleEndian(dest[24..], (ushort)channels);
        dest[26] = (byte)bitsPerSample;
        dest[27] = codec;
        return AudioHeaderSize;
    }

    /// <summary>
    /// Writes a control packet (HELLO/HELLO_ACK/PING/PONG/BYE) and returns the
    /// total datagram length.
    /// </summary>
    public static int WriteControl(Span<byte> dest, PacketType type,
                                   uint sequence, long timestampUs,
                                   ReadOnlySpan<byte> payload = default)
    {
        if (type == PacketType.Audio)
            throw new ArgumentException("use WriteAudioHeader for audio",
                                        nameof(type));
        if (dest.Length < CommonHeaderSize + payload.Length)
            throw new ArgumentException("destination too small", nameof(dest));

        WriteCommon(dest, type, PacketFlags.None, payload.Length, sequence,
                    timestampUs);
        payload.CopyTo(dest[CommonHeaderSize..]);
        return CommonHeaderSize + payload.Length;
    }

    /// <summary>
    /// Parses a datagram. Returns false for anything malformed and NEVER
    /// throws: this runs on data straight off a public socket, and a crash
    /// on a corrupt or hostile packet would take the whole stream down.
    /// </summary>
    public static bool TryParse(ReadOnlySpan<byte> datagram,
                                out WtpHeader header,
                                out ReadOnlySpan<byte> payload)
    {
        header = default;
        payload = default;

        if (datagram.Length < CommonHeaderSize)
            return false;
        if (!datagram[..4].SequenceEqual(Magic))
            return false;

        var type = (PacketType)datagram[4];
        if (type is < PacketType.Audio or > PacketType.Bye)
            return false;                       // unknown type from a newer sender

        var flags = (PacketFlags)datagram[5];
        ushort payloadLength = BinaryPrimitives.ReadUInt16LittleEndian(datagram[6..]);
        uint sequence = BinaryPrimitives.ReadUInt32LittleEndian(datagram[8..]);
        long timestamp = BinaryPrimitives.ReadInt64LittleEndian(datagram[12..]);

        int headerSize = type == PacketType.Audio ? AudioHeaderSize
                                                  : CommonHeaderSize;
        if (datagram.Length < headerSize)
            return false;

        // The declared length must match what actually arrived. A mismatch
        // means truncation or a forged header, and trusting it would slice
        // past the end of the buffer.
        if (payloadLength != datagram.Length - headerSize)
            return false;

        uint sampleRate = 0;
        ushort channels = 0;
        byte bits = 0, codec = 0;

        if (type == PacketType.Audio)
        {
            sampleRate = BinaryPrimitives.ReadUInt32LittleEndian(datagram[20..]);
            channels = BinaryPrimitives.ReadUInt16LittleEndian(datagram[24..]);
            bits = datagram[26];
            codec = datagram[27];

            // A zero rate or channel count would make the receiver divide by
            // zero working out frame sizes.
            if (sampleRate == 0 || channels == 0 || bits == 0)
                return false;
        }

        header = new WtpHeader(type, flags, payloadLength, sequence, timestamp,
                               sampleRate, channels, bits, codec);
        payload = datagram[headerSize..];
        return true;
    }

    /// <summary>Hex dump used by docs/PROTOCOL.md and by the diagnostics.</summary>
    public static string ToHex(ReadOnlySpan<byte> bytes, int max = 32)
    {
        var sb = new StringBuilder();
        int n = Math.Min(bytes.Length, max);
        for (int i = 0; i < n; i++)
        {
            if (i > 0) sb.Append(' ');
            sb.Append(bytes[i].ToString("X2"));
        }
        if (bytes.Length > n) sb.Append(" ...");
        return sb.ToString();
    }
}
