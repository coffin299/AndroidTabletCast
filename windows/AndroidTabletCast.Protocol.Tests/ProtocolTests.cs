using System.Text;
using Xunit;

namespace AndroidTabletCast.Tests;

public class ProtocolTests
{
    [Fact]
    public void FixtureRoundTrip()
    {
        var bytes = File.ReadAllBytes(FixtureFiles.Find("packets.bin"));
        var reader = new PacketReader(new MemoryStream(bytes));
        var packets = new List<Packet>();
        var encoded = new MemoryStream();
        while (reader.Read() is { } packet)
        {
            packets.Add(packet);
            var frame = Protocol.Encode(packet);
            encoded.Write(frame, 0, frame.Length);
        }

        Assert.Equal(21, packets.Count);
        Assert.Equal(bytes, encoded.ToArray());

        var hello = Assert.IsType<HelloPacket>(packets[0]);
        Assert.Equal(1, hello.Version);
        Assert.True(hello.TrustMode);
        Assert.Equal(1920, hello.VideoWidth);
        Assert.Equal(1080, hello.VideoHeight);
        Assert.Equal(2560, hello.DeviceWidth);
        Assert.Equal(1600, hello.DeviceHeight);
        Assert.Equal("Tab", hello.Name);
        Assert.Equal("123456", Assert.IsType<AuthPinPacket>(packets[1]).Pin);

        var trust = Assert.IsType<AuthTrustPacket>(packets[2]);
        var token = Enumerable.Range(1, 32).Select(value => (byte)value).ToArray();
        Assert.Equal(TrustCrypto.TrustHmac(token, hello.Nonce), trust.Hmac);
        Assert.Equal(ProtocolLimits.AuthOk, Assert.IsType<AuthResultPacket>(packets[3]).Code);
        Assert.Equal(ProtocolLimits.AuthBadPin, Assert.IsType<AuthResultPacket>(packets[4]).Code);
        Assert.Equal(ProtocolLimits.AuthUntrusted, Assert.IsType<AuthResultPacket>(packets[5]).Code);
        Assert.Equal(ProtocolLimits.AuthTrustModeOff, Assert.IsType<AuthResultPacket>(packets[6]).Code);
        Assert.Equal(ProtocolLimits.AuthLocked, Assert.IsType<AuthResultPacket>(packets[7]).Code);
        Assert.Equal("Desk", Assert.IsType<TrustRequestPacket>(packets[8]).Name);
        Assert.Equal(32, Assert.IsType<TrustGrantPacket>(packets[9]).Token.Length);
        var videoConfig = Assert.IsType<VideoConfigPacket>(packets[10]);
        Assert.Equal(ProtocolLimits.CodecAvc, videoConfig.Codec);
        Assert.Equal(1920, videoConfig.Width);
        var video = Assert.IsType<VideoPacket>(packets[11]);
        Assert.Equal(1000, video.PtsUs);
        Assert.True(video.Keyframe);
        Assert.Equal(48000, Assert.IsType<AudioConfigPacket>(packets[12]).SampleRate);
        Assert.Equal(2000, Assert.IsType<AudioPacket>(packets[13]).PtsUs);
        var touch = Assert.IsType<InputTouchPacket>(packets[14]);
        Assert.Equal(ProtocolLimits.TouchDown, touch.Action);
        Assert.Equal(32768, touch.XNorm);
        Assert.Equal(100, touch.YNorm);
        Assert.Equal(29, Assert.IsType<InputKeyPacket>(packets[15]).KeyCode);
        Assert.Equal(-120, Assert.IsType<InputScrollPacket>(packets[16]).Delta);
        Assert.Equal(ProtocolLimits.PresetHighQuality, Assert.IsType<StreamConfigPacket>(packets[17]).Preset);
        Assert.IsType<RequestKeyframePacket>(packets[18]);
        Assert.Equal(123456789, Assert.IsType<PingPacket>(packets[19]).ClientUnixMs);
        var pong = Assert.IsType<PongPacket>(packets[20]);
        Assert.Equal(123456789, pong.ClientUnixMs);
        Assert.Equal(123456800, pong.ServerUnixMs);
    }

    [Fact]
    public void BeaconMatchesFixture()
    {
        var bytes = File.ReadAllBytes(FixtureFiles.Find("beacon.bin"));
        Assert.True(Beacon.TryDecode(bytes, out var port, out var tabletId, out var name));
        Assert.Equal(ProtocolLimits.TcpPort, port);
        Assert.Equal("Tab", name);
        Assert.Equal(bytes, Beacon.Encode(port, tabletId, name));
    }

    [Fact]
    public void ScaleLongSideAndAlignment()
    {
        Assert.Equal(new FrameSize(1920, 1200), FrameGeometry.ScaledSize(2560, 1600, 1920));
        Assert.Equal(new FrameSize(2000, 992), FrameGeometry.ScaledSize(2000, 1000, 2560));
    }

    [Fact]
    public void TruncatesWithoutSplittingEmoji()
    {
        Assert.Equal("あ", TextEncoding.Utf8Trunc("あ😀い", 3));
    }

    [Fact]
    public void MdnsReadsSrvAndAddress()
    {
        var message = BuildMdns();
        var found = Mdns.Parse(message);
        var service = Assert.Single(found);
        Assert.Equal("Tab", service.Name);
        Assert.Equal("192.168.1.20", service.Host);
        Assert.Equal(39721, service.Port);
    }

    [Fact]
    public void BeaconAndMdnsCollapseToOneDevice()
    {
        var devices = new Dictionary<string, DiscoveredTablet>();
        var id = Enumerable.Range(1, 16).Select(value => (byte)value).ToArray();
        var seen = DateTime.UtcNow;
        DiscoveryMerge.Remember(devices, "Tab", "192.168.1.20", 39721, id, seen);
        DiscoveryMerge.Remember(devices, "Tab", "192.168.1.20", 39721, Array.Empty<byte>(), seen);
        var only = Assert.Single(devices.Values);
        Assert.Equal("192.168.1.20", only.Host);
        Assert.Equal(16, only.TabletId.Length);
    }

    private static byte[] BuildMdns()
    {
        var body = new List<byte>();
        void Name(string dotted)
        {
            foreach (var label in dotted.Split('.'))
            {
                body.Add((byte)label.Length);
                body.AddRange(Encoding.ASCII.GetBytes(label));
            }

            body.Add(0);
        }

        void Record(string owner, int type, byte[] data)
        {
            Name(owner);
            body.Add(0);
            body.Add((byte)type);
            body.Add(0);
            body.Add(1);
            body.AddRange(new byte[] { 0, 0, 0, 0 });
            body.Add((byte)(data.Length >> 8));
            body.Add((byte)data.Length);
            body.AddRange(data);
        }

        var srv = new List<byte> { 0, 0, 0, 0, 0x9B, 0x29 };
        foreach (var label in new[] { "tab", "local" })
        {
            srv.Add((byte)label.Length);
            srv.AddRange(Encoding.ASCII.GetBytes(label));
        }

        srv.Add(0);
        Record("Tab._atcast._tcp.local", 33, srv.ToArray());
        Record("tab.local", 1, new byte[] { 192, 168, 1, 20 });
        var header = new byte[] { 0, 0, 0x84, 0, 0, 0, 0, 2, 0, 0, 0, 0 };
        return header.Concat(body).ToArray();
    }
}
