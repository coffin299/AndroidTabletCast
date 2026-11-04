using System.IO;
using System.Security.Cryptography;
using System.Text.Json;

namespace AndroidTabletCast;

public sealed class LocalStore
{
    private readonly string directory;
    private readonly string trustPath;
    private List<TrustEntry> entries;

    public LocalStore()
    {
        directory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "AndroidTabletCast");
        Directory.CreateDirectory(directory);
        trustPath = Path.Combine(directory, "trust.json");
        PcId = LoadOrCreateId();
        entries = LoadTrusts();
    }

    public byte[] PcId { get; }

    public string PcName { get; } = Environment.MachineName;

    public void SaveTrust(byte[] tabletId, byte[] token, string name)
    {
        var id = Hex.ToHex(tabletId);
        entries.RemoveAll(entry => entry.TabletId == id);
        entries.Add(new TrustEntry(id, Convert.ToBase64String(token), name));
        Save();
    }

    public bool TryGetToken(byte[] tabletId, out byte[] token)
    {
        var id = Hex.ToHex(tabletId);
        var found = entries.FirstOrDefault(entry => entry.TabletId == id);
        if (found == null)
        {
            token = Array.Empty<byte>();
            return false;
        }

        token = Convert.FromBase64String(found.Token);
        return token.Length == ProtocolLimits.TokenBytes;
    }

    public void Forget(byte[] tabletId)
    {
        var id = Hex.ToHex(tabletId);
        entries.RemoveAll(entry => entry.TabletId == id);
        Save();
    }

    private byte[] LoadOrCreateId()
    {
        var path = Path.Combine(directory, "device-id.txt");
        if (File.Exists(path))
        {
            var text = File.ReadAllText(path).Trim();
            if (text.Length == ProtocolLimits.IdBytes * 2)
            {
                return Hex.FromHex(text);
            }
        }

        var created = RandomNumberGenerator.GetBytes(ProtocolLimits.IdBytes);
        File.WriteAllText(path, Hex.ToHex(created));
        return created;
    }

    private List<TrustEntry> LoadTrusts()
    {
        if (!File.Exists(trustPath))
        {
            return new List<TrustEntry>();
        }

        return JsonSerializer.Deserialize<List<TrustEntry>>(File.ReadAllText(trustPath)) ?? new List<TrustEntry>();
    }

    private void Save()
    {
        File.WriteAllText(trustPath, JsonSerializer.Serialize(entries));
    }

    private sealed record TrustEntry(string TabletId, string Token, string Name);
}
