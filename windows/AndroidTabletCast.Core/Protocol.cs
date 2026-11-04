using System.Security.Cryptography;
using System.Text;

namespace AndroidTabletCast;

public static class ProtocolLimits
{
    public const int Version = 1;
    public const int MaxPayloadBytes = 4 * 1024 * 1024;
    public const int MaxNameBytes = 64;
    public const int TcpPort = 39721;
    public const int UdpBeaconPort = 39720;
    public const int IdBytes = 16;
    public const int TokenBytes = 32;
    public const int NonceBytes = 16;
    public const int HmacBytes = 32;

    public const int Hello = 1;
    public const int Auth = 2;
    public const int AuthResult = 3;
    public const int TrustGrant = 4;
    public const int TrustRequest = 5;
    public const int VideoConfig = 16;
    public const int Video = 17;
    public const int AudioConfig = 18;
    public const int Audio = 19;
    public const int InputTouch = 32;
    public const int InputKey = 33;
    public const int InputScroll = 34;
    public const int StreamConfig = 48;
    public const int RequestKeyframe = 49;
    public const int Ping = 126;
    public const int Pong = 127;

    public const int AuthOk = 0;
    public const int AuthBadPin = 1;
    public const int AuthUntrusted = 2;
    public const int AuthTrustModeOff = 3;
    public const int AuthLocked = 4;
    public const int AuthMethodPin = 0;
    public const int AuthMethodTrust = 1;

    public const int TouchDown = 0;
    public const int TouchMove = 1;
    public const int TouchUp = 2;
    public const int TouchCancel = 3;
    public const int PresetLowLatency = 0;
    public const int PresetBalanced = 1;
    public const int PresetHighQuality = 2;
}

public sealed class ProtocolException : Exception
{
    public ProtocolException(string message) : base(message)
    {
    }
}

public abstract record Packet
{
    public abstract int Type { get; }
}

public sealed record HelloPacket(
    int Version,
    byte[] TabletId,
    bool TrustMode,
    byte[] Nonce,
    int VideoWidth,
    int VideoHeight,
    int DeviceWidth,
    int DeviceHeight,
    string Name) : Packet
{
    public override int Type => ProtocolLimits.Hello;
}

public sealed record AuthPinPacket(string Pin) : Packet
{
    public override int Type => ProtocolLimits.Auth;
}

public sealed record AuthTrustPacket(byte[] PcId, byte[] Hmac) : Packet
{
    public override int Type => ProtocolLimits.Auth;
}

public sealed record AuthResultPacket(int Code) : Packet
{
    public override int Type => ProtocolLimits.AuthResult;
}

public sealed record TrustGrantPacket(byte[] TabletId, byte[] Token) : Packet
{
    public override int Type => ProtocolLimits.TrustGrant;
}

public sealed record TrustRequestPacket(byte[] PcId, string Name) : Packet
{
    public override int Type => ProtocolLimits.TrustRequest;
}

public sealed record VideoConfigPacket(int Width, int Height, byte[] AnnexB) : Packet
{
    public override int Type => ProtocolLimits.VideoConfig;
}

public sealed record VideoPacket(long PtsUs, bool Keyframe, byte[] AnnexB) : Packet
{
    public override int Type => ProtocolLimits.Video;
}

public sealed record AudioConfigPacket(int SampleRate, int Channels, byte[] SpecificConfig) : Packet
{
    public override int Type => ProtocolLimits.AudioConfig;
}

public sealed record AudioPacket(long PtsUs, byte[] Aac) : Packet
{
    public override int Type => ProtocolLimits.Audio;
}

public sealed record InputTouchPacket(int Action, int XNorm, int YNorm) : Packet
{
    public override int Type => ProtocolLimits.InputTouch;
}

public sealed record InputKeyPacket(int Action, int KeyCode, int Meta) : Packet
{
    public override int Type => ProtocolLimits.InputKey;
}

public sealed record InputScrollPacket(int XNorm, int YNorm, short Delta) : Packet
{
    public override int Type => ProtocolLimits.InputScroll;
}

public sealed record StreamConfigPacket(int Preset) : Packet
{
    public override int Type => ProtocolLimits.StreamConfig;
}

public sealed record RequestKeyframePacket : Packet
{
    public override int Type => ProtocolLimits.RequestKeyframe;
}

public sealed record PingPacket(long ClientUnixMs) : Packet
{
    public override int Type => ProtocolLimits.Ping;
}

public sealed record PongPacket(long ClientUnixMs, long ServerUnixMs) : Packet
{
    public override int Type => ProtocolLimits.Pong;
}

public static class Protocol
{
    public static byte[] Encode(Packet packet)
    {
        var payload = Payload(packet);
        if (payload.Length > ProtocolLimits.MaxPayloadBytes)
        {
            throw new ProtocolException("payload が上限を超えています");
        }

        var frame = new byte[5 + payload.Length];
        frame[0] = (byte)packet.Type;
        WriteU32(frame, 1, (uint)payload.Length);
        Buffer.BlockCopy(payload, 0, frame, 5, payload.Length);
        return frame;
    }

    public static Packet Decode(byte[] frame)
    {
        if (frame.Length < 5)
        {
            throw new ProtocolException("ヘッダが足りません");
        }

        var length = ReadU32(frame, 1);
        if (length > ProtocolLimits.MaxPayloadBytes)
        {
            throw new ProtocolException("payload が上限を超えています");
        }

        if (frame.Length != 5 + length)
        {
            throw new ProtocolException("フレーム長が一致しません");
        }

        var payload = new byte[length];
        Buffer.BlockCopy(frame, 5, payload, 0, (int)length);
        return DecodePayload(frame[0], payload);
    }

    public static Packet DecodePayload(int type, byte[] payload)
    {
        var reader = new ByteCursor(payload);
        var packet = type switch
        {
            ProtocolLimits.Hello => ReadHello(reader),
            ProtocolLimits.Auth => ReadAuth(reader),
            ProtocolLimits.AuthResult => new AuthResultPacket(reader.U8()),
            ProtocolLimits.TrustGrant => new TrustGrantPacket(reader.Bytes(ProtocolLimits.IdBytes), reader.Bytes(ProtocolLimits.TokenBytes)),
            ProtocolLimits.TrustRequest => new TrustRequestPacket(reader.Bytes(ProtocolLimits.IdBytes), ReadName(reader)),
            ProtocolLimits.VideoConfig => new VideoConfigPacket(reader.U16(), reader.U16(), reader.Rest()),
            ProtocolLimits.Video => new VideoPacket(reader.U64(), reader.U8() != 0, reader.Rest()),
            ProtocolLimits.AudioConfig => new AudioConfigPacket((int)reader.U32(), reader.U8(), reader.Rest()),
            ProtocolLimits.Audio => new AudioPacket(reader.U64(), reader.Rest()),
            ProtocolLimits.InputTouch => new InputTouchPacket(reader.U8(), reader.U16(), reader.U16()),
            ProtocolLimits.InputKey => new InputKeyPacket(reader.U8(), reader.U16(), (int)reader.U32()),
            ProtocolLimits.InputScroll => new InputScrollPacket(reader.U16(), reader.U16(), reader.I16()),
            ProtocolLimits.StreamConfig => new StreamConfigPacket(reader.U8()),
            ProtocolLimits.RequestKeyframe => new RequestKeyframePacket(),
            ProtocolLimits.Ping => new PingPacket(reader.U64()),
            ProtocolLimits.Pong => new PongPacket(reader.U64(), reader.U64()),
            _ => throw new ProtocolException($"未知の type: {type}"),
        };
        if (type is not (ProtocolLimits.VideoConfig or ProtocolLimits.Video or ProtocolLimits.AudioConfig or ProtocolLimits.Audio))
        {
            reader.ExpectEnded();
        }

        return packet;
    }

    private static Packet ReadHello(ByteCursor reader)
    {
        var version = reader.U16();
        if (version != ProtocolLimits.Version)
        {
            throw new ProtocolException("未対応のプロトコル版です");
        }

        return new HelloPacket(
            version,
            reader.Bytes(ProtocolLimits.IdBytes),
            reader.U8() != 0,
            reader.Bytes(ProtocolLimits.NonceBytes),
            reader.U16(),
            reader.U16(),
            reader.U16(),
            reader.U16(),
            ReadName(reader));
    }

    private static Packet ReadAuth(ByteCursor reader)
    {
        return reader.U8() switch
        {
            ProtocolLimits.AuthMethodPin => new AuthPinPacket(ReadName(reader)),
            ProtocolLimits.AuthMethodTrust => new AuthTrustPacket(reader.Bytes(ProtocolLimits.IdBytes), reader.Bytes(ProtocolLimits.HmacBytes)),
            var method => throw new ProtocolException($"未知の認証方式です: {method}"),
        };
    }

    private static string ReadName(ByteCursor reader)
    {
        var length = reader.U8();
        if (length > ProtocolLimits.MaxNameBytes)
        {
            throw new ProtocolException("名前が長すぎます");
        }

        return Encoding.UTF8.GetString(reader.Bytes(length));
    }

    private static byte[] Payload(Packet packet)
    {
        var writer = new ByteWriter();
        switch (packet)
        {
            case HelloPacket hello:
                writer.U16(hello.Version);
                Require(hello.TabletId, ProtocolLimits.IdBytes, "tabletId");
                writer.Bytes(hello.TabletId);
                writer.U8(hello.TrustMode ? 1 : 0);
                Require(hello.Nonce, ProtocolLimits.NonceBytes, "nonce");
                writer.Bytes(hello.Nonce);
                writer.U16(hello.VideoWidth);
                writer.U16(hello.VideoHeight);
                writer.U16(hello.DeviceWidth);
                writer.U16(hello.DeviceHeight);
                WriteName(writer, hello.Name);
                break;
            case AuthPinPacket pin:
                writer.U8(ProtocolLimits.AuthMethodPin);
                WriteName(writer, pin.Pin);
                break;
            case AuthTrustPacket trust:
                writer.U8(ProtocolLimits.AuthMethodTrust);
                Require(trust.PcId, ProtocolLimits.IdBytes, "pcId");
                writer.Bytes(trust.PcId);
                Require(trust.Hmac, ProtocolLimits.HmacBytes, "hmac");
                writer.Bytes(trust.Hmac);
                break;
            case AuthResultPacket result:
                writer.U8(result.Code);
                break;
            case TrustGrantPacket grant:
                Require(grant.TabletId, ProtocolLimits.IdBytes, "tabletId");
                writer.Bytes(grant.TabletId);
                Require(grant.Token, ProtocolLimits.TokenBytes, "token");
                writer.Bytes(grant.Token);
                break;
            case TrustRequestPacket request:
                Require(request.PcId, ProtocolLimits.IdBytes, "pcId");
                writer.Bytes(request.PcId);
                WriteName(writer, request.Name);
                break;
            case VideoConfigPacket videoConfig:
                writer.U16(videoConfig.Width);
                writer.U16(videoConfig.Height);
                writer.Bytes(videoConfig.AnnexB);
                break;
            case VideoPacket video:
                writer.U64(video.PtsUs);
                writer.U8(video.Keyframe ? 1 : 0);
                writer.Bytes(video.AnnexB);
                break;
            case AudioConfigPacket audioConfig:
                writer.U32((uint)audioConfig.SampleRate);
                writer.U8(audioConfig.Channels);
                writer.Bytes(audioConfig.SpecificConfig);
                break;
            case AudioPacket audio:
                writer.U64(audio.PtsUs);
                writer.Bytes(audio.Aac);
                break;
            case InputTouchPacket touch:
                writer.U8(touch.Action);
                writer.U16(touch.XNorm);
                writer.U16(touch.YNorm);
                break;
            case InputKeyPacket key:
                writer.U8(key.Action);
                writer.U16(key.KeyCode);
                writer.U32((uint)key.Meta);
                break;
            case InputScrollPacket scroll:
                writer.U16(scroll.XNorm);
                writer.U16(scroll.YNorm);
                writer.U16((ushort)scroll.Delta);
                break;
            case StreamConfigPacket config:
                writer.U8(config.Preset);
                break;
            case RequestKeyframePacket:
                break;
            case PingPacket ping:
                writer.U64(ping.ClientUnixMs);
                break;
            case PongPacket pong:
                writer.U64(pong.ClientUnixMs);
                writer.U64(pong.ServerUnixMs);
                break;
            default:
                throw new ProtocolException("未知のパケットです");
        }

        return writer.ToArray();
    }

    private static void WriteName(ByteWriter writer, string value)
    {
        var bytes = Encoding.UTF8.GetBytes(TextEncoding.Utf8Trunc(value, ProtocolLimits.MaxNameBytes));
        writer.U8(bytes.Length);
        writer.Bytes(bytes);
    }

    private static void Require(byte[] value, int size, string label)
    {
        if (value.Length != size)
        {
            throw new ProtocolException($"{label} は {size} バイトである必要があります");
        }
    }

    private static void WriteU32(byte[] target, int offset, uint value)
    {
        target[offset] = (byte)(value >> 24);
        target[offset + 1] = (byte)(value >> 16);
        target[offset + 2] = (byte)(value >> 8);
        target[offset + 3] = (byte)value;
    }

    private static uint ReadU32(byte[] source, int offset)
    {
        return ((uint)source[offset] << 24) |
               ((uint)source[offset + 1] << 16) |
               ((uint)source[offset + 2] << 8) |
               source[offset + 3];
    }
}

public sealed class PacketReader
{
    private readonly Stream input;

    public PacketReader(Stream input)
    {
        this.input = input;
    }

    public Packet? Read()
    {
        var header = ReadExact(5, allowStartEof: true);
        if (header == null)
        {
            return null;
        }

        var length = ((uint)header[1] << 24) | ((uint)header[2] << 16) | ((uint)header[3] << 8) | header[4];
        if (length > ProtocolLimits.MaxPayloadBytes)
        {
            throw new ProtocolException("payload が上限を超えています");
        }

        var payload = length == 0 ? Array.Empty<byte>() : ReadExact((int)length, allowStartEof: false)!;
        var frame = new byte[5 + payload.Length];
        Buffer.BlockCopy(header, 0, frame, 0, 5);
        Buffer.BlockCopy(payload, 0, frame, 5, payload.Length);
        return Protocol.Decode(frame);
    }

    private byte[]? ReadExact(int length, bool allowStartEof)
    {
        var buffer = new byte[length];
        var offset = 0;
        while (offset < length)
        {
            var count = input.Read(buffer, offset, length - offset);
            if (count == 0)
            {
                if (offset == 0 && allowStartEof)
                {
                    return null;
                }

                throw new ProtocolException("接続が途中で切れました");
            }

            offset += count;
        }

        return buffer;
    }
}

sealed class ByteWriter
{
    private readonly MemoryStream stream = new();

    public void U8(int value) => stream.WriteByte((byte)value);

    public void U16(int value)
    {
        U8(value >> 8);
        U8(value);
    }

    public void U32(uint value)
    {
        U8((int)(value >> 24));
        U8((int)(value >> 16));
        U8((int)(value >> 8));
        U8((int)value);
    }

    public void U64(long value)
    {
        U32((uint)((ulong)value >> 32));
        U32((uint)value);
    }

    public void Bytes(byte[] value) => stream.Write(value, 0, value.Length);

    public byte[] ToArray() => stream.ToArray();
}

sealed class ByteCursor
{
    private readonly byte[] data;
    private int offset;

    public ByteCursor(byte[] data)
    {
        this.data = data;
    }

    public int Remaining => data.Length - offset;

    public int U8()
    {
        if (offset >= data.Length)
        {
            throw new ProtocolException("payload が途中で終わっています");
        }

        return data[offset++];
    }

    public int U16() => (U8() << 8) | U8();

    public short I16() => (short)U16();

    public uint U32() => ((uint)U8() << 24) | ((uint)U8() << 16) | ((uint)U8() << 8) | (uint)U8();

    public long U64() => ((long)U32() << 32) | U32();

    public byte[] Bytes(int length)
    {
        if (length < 0 || Remaining < length)
        {
            throw new ProtocolException("payload の長さが足りません");
        }

        var slice = new byte[length];
        Buffer.BlockCopy(data, offset, slice, 0, length);
        offset += length;
        return slice;
    }

    public byte[] Rest() => Bytes(Remaining);

    public void ExpectEnded()
    {
        if (Remaining != 0)
        {
            throw new ProtocolException("payload に余りがあります");
        }
    }
}

public static class TextEncoding
{
    public static string Utf8Trunc(string value, int maxBytes)
    {
        var used = 0;
        var builder = new StringBuilder();
        foreach (var rune in value.EnumerateRunes())
        {
            var piece = rune.ToString();
            var size = Encoding.UTF8.GetByteCount(piece);
            if (used + size > maxBytes)
            {
                break;
            }

            builder.Append(piece);
            used += size;
        }

        return builder.ToString();
    }
}

public static class TrustCrypto
{
    public static byte[] Sha256(byte[] value) => SHA256.HashData(value);

    public static byte[] TrustHmac(byte[] token, byte[] nonce)
    {
        return HMACSHA256.HashData(Sha256(token), nonce);
    }

    public static bool ConstantTimeEquals(byte[] left, byte[] right)
    {
        if (left.Length != right.Length)
        {
            return false;
        }

        var diff = 0;
        for (var index = 0; index < left.Length; index++)
        {
            diff |= left[index] ^ right[index];
        }

        return diff == 0;
    }
}

public readonly record struct FrameSize(int Width, int Height);

public static class FrameGeometry
{
    public static FrameSize ScaledSize(int deviceWidth, int deviceHeight, int longSideCap)
    {
        var longSide = Math.Max(deviceWidth, deviceHeight);
        if (longSide < 1)
        {
            longSide = 1;
        }

        var cap = Math.Max(longSideCap, 16);
        var scale = Math.Min(1.0, cap / (double)longSide);
        var width = (int)(deviceWidth * scale) / 16 * 16;
        var height = (int)(deviceHeight * scale) / 16 * 16;
        if (width < 16)
        {
            width = 16;
        }

        if (height < 16)
        {
            height = 16;
        }

        return new FrameSize(width, height);
    }
}

public static class Beacon
{
    private static readonly byte[] Magic = "ATC1"u8.ToArray();

    public static byte[] Encode(int tcpPort, byte[] tabletId, string name)
    {
        var writer = new ByteWriter();
        writer.Bytes(Magic);
        writer.U16(tcpPort);
        writer.Bytes(tabletId.AsSpan(0, ProtocolLimits.IdBytes).ToArray());
        var nameBytes = Encoding.UTF8.GetBytes(TextEncoding.Utf8Trunc(name, ProtocolLimits.MaxNameBytes));
        writer.U8(nameBytes.Length);
        writer.Bytes(nameBytes);
        return writer.ToArray();
    }

    public static bool TryDecode(byte[] packet, out int port, out byte[] tabletId, out string name)
    {
        port = 0;
        tabletId = Array.Empty<byte>();
        name = "";
        if (packet.Length < 4 + 2 + ProtocolLimits.IdBytes + 1)
        {
            return false;
        }

        for (var index = 0; index < Magic.Length; index++)
        {
            if (packet[index] != Magic[index])
            {
                return false;
            }
        }

        var reader = new ByteCursor(packet.AsSpan(4).ToArray());
        port = reader.U16();
        tabletId = reader.Bytes(ProtocolLimits.IdBytes);
        var nameLength = reader.U8();
        if (reader.Remaining < nameLength)
        {
            return false;
        }

        name = Encoding.UTF8.GetString(reader.Bytes(nameLength));
        return true;
    }
}

public static class Hex
{
    public static string ToHex(byte[] value) => Convert.ToHexString(value).ToLowerInvariant();

    public static byte[] FromHex(string value)
    {
        var text = value.Trim();
        if (text.Length % 2 != 0)
        {
            throw new FormatException("16進文字列の長さが奇数です");
        }

        return Convert.FromHexString(text);
    }
}

public static class FixtureFiles
{
    public static string Find(string name)
    {
        foreach (var start in new[] { Directory.GetCurrentDirectory(), AppContext.BaseDirectory })
        {
            var dir = new DirectoryInfo(start);
            while (dir != null)
            {
                var candidate = Path.Combine(dir.FullName, "protocol", "fixtures", name);
                if (File.Exists(candidate))
                {
                    return candidate;
                }

                dir = dir.Parent;
            }
        }

        throw new FileNotFoundException(name);
    }
}
