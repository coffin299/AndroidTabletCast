using System.Net.Sockets;

namespace AndroidTabletCast;

public sealed class StreamClient : IDisposable
{
    private readonly object writeLock = new();
    private TcpClient? client;
    private NetworkStream? stream;
    private CancellationTokenSource? readCancel;

    public HelloPacket? Hello { get; private set; }
    public bool Authenticated { get; private set; }

    public event Action<Packet>? PacketReceived;
    public event Action? Closed;

    public async Task ConnectAsync(string host, int port, CancellationToken cancellationToken)
    {
        Disconnect();
        var tcp = new TcpClient { NoDelay = true };
        await tcp.ConnectAsync(host, port, cancellationToken);
        client = tcp;
        stream = tcp.GetStream();
        var reader = new PacketReader(stream);
        var hello = reader.Read();
        if (hello is not HelloPacket packet)
        {
            Disconnect();
            throw new ProtocolException("HELLO を受信できませんでした");
        }

        Hello = packet;
        readCancel = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        _ = Task.Run(() => ReadLoop(reader));
    }

    public void Disconnect()
    {
        Authenticated = false;
        readCancel?.Cancel();
        try
        {
            stream?.Close();
        }
        catch (IOException)
        {
        }

        client?.Close();
        stream = null;
        client = null;
        Hello = null;
    }

    public void Send(Packet packet)
    {
        var current = stream;
        if (current == null)
        {
            return;
        }

        var bytes = Protocol.Encode(packet);
        lock (writeLock)
        {
            try
            {
                current.Write(bytes, 0, bytes.Length);
                current.Flush();
            }
            catch (IOException)
            {
                Disconnect();
            }
        }
    }

    public void SendPin(string pin) => Send(new AuthPinPacket(pin));

    public void SendTrust(byte[] pcId, byte[] token)
    {
        var hello = Hello ?? throw new ProtocolException("まだ接続していません");
        Send(new AuthTrustPacket(pcId, TrustCrypto.TrustHmac(token, hello.Nonce)));
    }

    public void MarkAuthenticated() => Authenticated = true;

    public void Dispose() => Disconnect();

    private void ReadLoop(PacketReader reader)
    {
        try
        {
            while (readCancel is { IsCancellationRequested: false })
            {
                var packet = reader.Read();
                if (packet == null)
                {
                    break;
                }

                if (packet is AuthResultPacket result && result.Code == ProtocolLimits.AuthOk)
                {
                    Authenticated = true;
                }

                PacketReceived?.Invoke(packet);
            }
        }
        catch (Exception) when (readCancel == null || readCancel.IsCancellationRequested)
        {
        }
        catch (ProtocolException)
        {
        }
        catch (IOException)
        {
        }
        finally
        {
            Authenticated = false;
            Closed?.Invoke();
        }
    }
}
