using System.Text;

namespace AndroidTabletCast;

public readonly record struct ServiceEndpoint(string Name, string Host, int Port);

public static class Mdns
{
    public static byte[] QueryAtcast()
    {
        var message = new List<byte>
        {
            0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0,
        };
        foreach (var label in new[] { "_atcast", "_tcp", "local" })
        {
            message.Add((byte)label.Length);
            message.AddRange(Encoding.ASCII.GetBytes(label));
        }

        message.Add(0);
        message.AddRange(new byte[] { 0, 12, 0, 1 });
        return message.ToArray();
    }

    public static IReadOnlyList<ServiceEndpoint> Parse(byte[] message)
    {
        if (message.Length < 12)
        {
            return Array.Empty<ServiceEndpoint>();
        }

        var questions = (message[4] << 8) | message[5];
        var answers = (message[6] << 8) | message[7];
        var authorities = (message[8] << 8) | message[9];
        var additionals = (message[10] << 8) | message[11];
        var offset = 12;
        for (var index = 0; index < questions && offset < message.Length; index++)
        {
            ReadName(message, ref offset);
            offset += 4;
        }

        string? serviceName = null;
        string? target = null;
        var port = 0;
        var addresses = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var records = answers + authorities + additionals;
        for (var index = 0; index < records && offset + 10 < message.Length; index++)
        {
            var owner = ReadName(message, ref offset);
            if (offset + 10 > message.Length)
            {
                break;
            }

            var type = (message[offset] << 8) | message[offset + 1];
            var length = (message[offset + 8] << 8) | message[offset + 9];
            offset += 10;
            if (offset + length > message.Length)
            {
                break;
            }

            var start = offset;
            offset += length;
            if (type == 12 && owner.Contains("_atcast._tcp", StringComparison.OrdinalIgnoreCase))
            {
                var cursor = start;
                serviceName = ReadName(message, ref cursor);
            }
            else if (type == 33)
            {
                var cursor = start;
                port = (message[cursor + 4] << 8) | message[cursor + 5];
                cursor += 6;
                target = ReadName(message, ref cursor);
                serviceName ??= owner;
            }
            else if (type == 1 && length == 4)
            {
                addresses[owner] = $"{message[start]}.{message[start + 1]}.{message[start + 2]}.{message[start + 3]}";
            }
            else if (type == 16 && serviceName == null)
            {
                serviceName = owner;
            }
        }

        if (port == 0)
        {
            return Array.Empty<ServiceEndpoint>();
        }

        var host = "";
        if (target != null && addresses.TryGetValue(target, out var address))
        {
            host = address;
        }
        else if (addresses.Count == 1)
        {
            host = addresses.Values.First();
        }

        if (host.Length == 0)
        {
            return Array.Empty<ServiceEndpoint>();
        }

        var label = serviceName?.Split('.')[0] ?? "Tablet";
        return new[] { new ServiceEndpoint(label, host, port) };
    }

    private static string ReadName(byte[] data, ref int offset)
    {
        var labels = new List<string>();
        var jumped = false;
        var cursor = offset;
        var end = offset;
        for (var guard = 0; guard < 32 && cursor < data.Length; guard++)
        {
            var length = data[cursor];
            if (length == 0)
            {
                cursor++;
                if (!jumped)
                {
                    end = cursor;
                }

                break;
            }

            if ((length & 0xC0) == 0xC0)
            {
                if (cursor + 1 >= data.Length)
                {
                    break;
                }

                if (!jumped)
                {
                    end = cursor + 2;
                }

                cursor = ((length & 0x3F) << 8) | data[cursor + 1];
                jumped = true;
                continue;
            }

            cursor++;
            if (cursor + length > data.Length)
            {
                break;
            }

            labels.Add(Encoding.UTF8.GetString(data, cursor, length));
            cursor += length;
            if (!jumped)
            {
                end = cursor;
            }
        }

        offset = end;
        return string.Join('.', labels);
    }
}
