using System.Net;
using System.Net.Sockets;

namespace AndroidTabletCast;

public sealed record DiscoveredTablet(string Name, string Host, int Port, byte[] TabletId, DateTime SeenAt);

public sealed class Discovery : IDisposable
{
    private readonly object gate = new();
    private readonly Dictionary<string, DiscoveredTablet> devices = new();
    private UdpClient? beacon;
    private UdpClient? mdns;
    private CancellationTokenSource? cancel;

    public event Action? Changed;

    public IReadOnlyList<DiscoveredTablet> Snapshot()
    {
        lock (gate)
        {
            var now = DateTime.UtcNow;
            var stale = devices.Where(pair => now - pair.Value.SeenAt > TimeSpan.FromSeconds(4)).Select(pair => pair.Key).ToArray();
            foreach (var key in stale)
            {
                devices.Remove(key);
            }

            return devices.Values.OrderBy(item => item.Name).ToArray();
        }
    }

    public void Start()
    {
        Stop();
        cancel = new CancellationTokenSource();
        beacon = new UdpClient();
        beacon.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        beacon.Client.Bind(new IPEndPoint(IPAddress.Any, ProtocolLimits.UdpBeaconPort));
        mdns = new UdpClient();
        mdns.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        mdns.Client.Bind(new IPEndPoint(IPAddress.Any, 5353));
        try
        {
            mdns.JoinMulticastGroup(IPAddress.Parse("224.0.0.251"));
        }
        catch (SocketException)
        {
            // マルチキャストに参加できなくても、UDP ビーコンで探索を続ける。
        }

        var token = cancel.Token;
        _ = Task.Run(() => ListenBeacon(token));
        _ = Task.Run(() => ListenMdns(token));
        _ = Task.Run(() => QueryMdns(token));
    }

    public void Stop()
    {
        cancel?.Cancel();
        beacon?.Dispose();
        mdns?.Dispose();
        beacon = null;
        mdns = null;
    }

    public void Dispose() => Stop();

    private async Task ListenBeacon(CancellationToken token)
    {
        while (!token.IsCancellationRequested && beacon != null)
        {
            try
            {
                var result = await beacon.ReceiveAsync(token);
                if (!Beacon.TryDecode(result.Buffer, out var port, out var tabletId, out var name))
                {
                    continue;
                }

                Remember(name, result.RemoteEndPoint.Address.ToString(), port, tabletId);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (SocketException)
            {
                break;
            }
        }
    }

    private async Task ListenMdns(CancellationToken token)
    {
        while (!token.IsCancellationRequested && mdns != null)
        {
            try
            {
                var result = await mdns.ReceiveAsync(token);
                foreach (var service in Mdns.Parse(result.Buffer))
                {
                    Remember(service.Name, service.Host, service.Port, Array.Empty<byte>());
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (SocketException)
            {
                break;
            }
        }
    }

    private async Task QueryMdns(CancellationToken token)
    {
        var query = Mdns.QueryAtcast();
        while (!token.IsCancellationRequested && mdns != null)
        {
            try
            {
                await mdns.SendAsync(query, query.Length, new IPEndPoint(IPAddress.Parse("224.0.0.251"), 5353));
            }
            catch (SocketException)
            {
            }
            catch (ObjectDisposedException)
            {
                break;
            }

            try
            {
                await Task.Delay(2000, token);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    private void Remember(string name, string host, int port, byte[] tabletId)
    {
        lock (gate)
        {
            DiscoveryMerge.Remember(devices, name, host, port, tabletId, DateTime.UtcNow);
        }

        Changed?.Invoke();
    }
}

internal static class DiscoveryMerge
{
    public static void Remember(
        Dictionary<string, DiscoveredTablet> devices,
        string name,
        string host,
        int port,
        byte[] tabletId,
        DateTime seenAt)
    {
        var key = $"{host}:{port}";
        if (tabletId.Length == ProtocolLimits.IdBytes)
        {
            var id = Hex.ToHex(tabletId);
            var stale = devices
                .Where(pair => pair.Key != key && pair.Value.TabletId.Length == ProtocolLimits.IdBytes && Hex.ToHex(pair.Value.TabletId) == id)
                .Select(pair => pair.Key)
                .ToArray();
            foreach (var old in stale)
            {
                devices.Remove(old);
            }
        }

        if (devices.TryGetValue(key, out var existing))
        {
            var id = tabletId.Length == ProtocolLimits.IdBytes ? tabletId : existing.TabletId;
            var shown = name.Length > 0 ? name : existing.Name;
            devices[key] = new DiscoveredTablet(shown, host, port, id, seenAt);
            return;
        }

        devices[key] = new DiscoveredTablet(name, host, port, tabletId, seenAt);
    }
}
