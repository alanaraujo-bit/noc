using System.Text.Json;
using Noc.Core.Storage;

namespace Noc.Core.Security;

public sealed class DeviceRecord
{
    public required string Id { get; init; }
    public required string Name { get; set; }
    public string Model { get; set; } = "";
    public required string PublicKey { get; init; } // SPKI base64
    public DateTimeOffset PairedAt { get; init; }
    public DateTimeOffset? LastSeen { get; set; }
    public string? LastRoute { get; set; } // "lan" | "relay"
    public bool Revoked { get; set; }
    public DateTimeOffset? RevokedAt { get; set; }
}

/// <summary>
/// Dispositivos autorizados. Só guarda chaves públicas, que não são segredo.
/// Um dispositivo revogado continua listado para auditoria, mas nunca mais autentica.
/// </summary>
public sealed class DeviceStore
{
    private readonly Lock _lock = new();
    private List<DeviceRecord> _devices;
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    public event Action? Changed;
    /// <summary>Disparado quando um dispositivo é revogado: sessões abertas dele devem cair na hora.</summary>
    public event Action<string>? Revoked;

    public DeviceStore()
    {
        try
        {
            _devices = File.Exists(AppPaths.Devices)
                ? JsonSerializer.Deserialize<List<DeviceRecord>>(File.ReadAllText(AppPaths.Devices)) ?? []
                : [];
        }
        catch (JsonException)
        {
            var backup = AppPaths.Devices + ".corrupt-" + DateTime.Now.ToString("yyyyMMddHHmmss");
            File.Copy(AppPaths.Devices, backup, overwrite: true);
            _devices = [];
        }
    }

    public IReadOnlyList<DeviceRecord> All
    {
        get { lock (_lock) return _devices.Select(Clone).ToList(); }
    }

    public IReadOnlyList<DeviceRecord> Active => All.Where(d => !d.Revoked).ToList();

    public DeviceRecord? Find(string id)
    {
        lock (_lock) return _devices.FirstOrDefault(d => d.Id == id) is { } d ? Clone(d) : null;
    }

    public DeviceRecord Add(string id, string name, string model, byte[] spki)
    {
        DeviceRecord record;
        lock (_lock)
        {
            _devices.RemoveAll(d => d.Id == id); // repareamento do mesmo aparelho reativa o registro
            record = new DeviceRecord
            {
                Id = id,
                Name = Sanitize(name, "Celular"),
                Model = Sanitize(model, ""),
                PublicKey = Convert.ToBase64String(spki),
                PairedAt = DateTimeOffset.Now,
                LastSeen = DateTimeOffset.Now,
            };
            _devices.Add(record);
            Persist();
        }
        Changed?.Invoke();
        return Clone(record);
    }

    public void Touch(string id, string route)
    {
        lock (_lock)
        {
            var d = _devices.FirstOrDefault(x => x.Id == id);
            if (d is null) return;
            d.LastSeen = DateTimeOffset.Now;
            d.LastRoute = route;
            Persist();
        }
        Changed?.Invoke();
    }

    public bool Rename(string id, string name)
    {
        lock (_lock)
        {
            var d = _devices.FirstOrDefault(x => x.Id == id);
            if (d is null) return false;
            d.Name = Sanitize(name, d.Name);
            Persist();
        }
        Changed?.Invoke();
        return true;
    }

    public bool Revoke(string id)
    {
        lock (_lock)
        {
            var d = _devices.FirstOrDefault(x => x.Id == id);
            if (d is null || d.Revoked) return false;
            d.Revoked = true;
            d.RevokedAt = DateTimeOffset.Now;
            Persist();
        }
        Revoked?.Invoke(id);
        Changed?.Invoke();
        return true;
    }

    /// <summary>Remove de vez registros já revogados (limpeza da lista).</summary>
    public void Forget(string id)
    {
        lock (_lock)
        {
            if (_devices.RemoveAll(d => d.Id == id && d.Revoked) == 0) return;
            Persist();
        }
        Changed?.Invoke();
    }

    private void Persist() =>
        AppPaths.WriteAtomic(AppPaths.Devices, JsonSerializer.SerializeToUtf8Bytes(_devices, Json));

    private static string Sanitize(string? s, string fallback)
    {
        if (string.IsNullOrWhiteSpace(s)) return fallback;
        var clean = new string(s.Where(c => !char.IsControl(c)).ToArray()).Trim();
        return clean.Length > 60 ? clean[..60] : clean;
    }

    private static DeviceRecord Clone(DeviceRecord d) => new()
    {
        Id = d.Id, Name = d.Name, Model = d.Model, PublicKey = d.PublicKey, PairedAt = d.PairedAt,
        LastSeen = d.LastSeen, LastRoute = d.LastRoute, Revoked = d.Revoked, RevokedAt = d.RevokedAt,
    };
}
