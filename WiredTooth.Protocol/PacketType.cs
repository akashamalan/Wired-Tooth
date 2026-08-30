namespace WiredTooth.Protocol;

/// <summary>
/// Wire values are fixed by the spec in docs/PROTOCOL.md. A Swift receiver
/// parses these same bytes, so the numbers must never be renumbered.
/// </summary>
public enum PacketType : byte
{
    Audio = 1,
    Hello = 2,
    HelloAck = 3,
    Ping = 4,
    Pong = 5,
    Bye = 6,
}

[Flags]
public enum PacketFlags : byte
{
    None = 0,

    /// <summary>
    /// bit0. The sender is emitting a keepalive because WASAPI loopback has
    /// gone quiet. Payload may be empty; the receiver should treat the frame
    /// as silence rather than as a gap.
    /// </summary>
    Silence = 1 << 0,
}
