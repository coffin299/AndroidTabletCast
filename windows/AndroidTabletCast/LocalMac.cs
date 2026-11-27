using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace AndroidTabletCast;

public static class LocalMac
{
    public static byte[] ForHost(string host)
    {
        if (!IPAddress.TryParse(host, out var remote) || remote.AddressFamily != AddressFamily.InterNetwork)
        {
            return Array.Empty<byte>();
        }

        byte[]? fallback = null;
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up)
            {
                continue;
            }

            if (nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel)
            {
                continue;
            }

            var raw = nic.GetPhysicalAddress().GetAddressBytes();
            if (!IsUsable(raw))
            {
                continue;
            }

            foreach (var address in nic.GetIPProperties().UnicastAddresses)
            {
                if (address.Address.AddressFamily != AddressFamily.InterNetwork)
                {
                    continue;
                }

                fallback ??= raw;
                if (SameSubnet(address.Address, address.IPv4Mask, remote))
                {
                    return raw;
                }
            }
        }

        return fallback ?? Array.Empty<byte>();
    }

    public static string Format(byte[] mac)
    {
        return mac.Length == 6 ? string.Join(":", mac.Select(value => value.ToString("X2"))) : "";
    }

    private static bool IsUsable(byte[] mac)
    {
        if (mac.Length != 6)
        {
            return false;
        }

        if (mac.All(value => value == 0))
        {
            return false;
        }

        return !(mac[0] == 0x02 && mac[1] == 0 && mac[2] == 0 && mac[3] == 0 && mac[4] == 0 && mac[5] == 0);
    }

    private static bool SameSubnet(IPAddress local, IPAddress? mask, IPAddress remote)
    {
        var localBytes = local.GetAddressBytes();
        var remoteBytes = remote.GetAddressBytes();
        if (mask == null || mask.Equals(IPAddress.Any))
        {
            return localBytes[0] == remoteBytes[0] && localBytes[1] == remoteBytes[1] && localBytes[2] == remoteBytes[2];
        }

        var maskBytes = mask.GetAddressBytes();
        if (maskBytes.Length != 4 || localBytes.Length != 4 || remoteBytes.Length != 4)
        {
            return false;
        }

        for (var index = 0; index < 4; index++)
        {
            if ((localBytes[index] & maskBytes[index]) != (remoteBytes[index] & maskBytes[index]))
            {
                return false;
            }
        }

        return true;
    }
}
