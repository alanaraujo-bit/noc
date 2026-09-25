using System.Text.Json.Nodes;
using Noc.Core.Jobs;
using Noc.Core.LmStudio;
using Noc.Core.Security;

namespace Noc.Core.Sessions;

public interface ISessionHost
{
    PcIdentity Identity { get; }
    DeviceStore Devices { get; }
    PairingManager Pairing { get; }
    SecurityLog Security { get; }
    ModelManager Models { get; }
    JobManager Jobs { get; }
    Library.ModelCatalog Catalog { get; }
    Library.ModelLibrary Library { get; }
    Library.Benchmark Bench { get; }
    BlobStore Blobs { get; }
    Speech.SttService Stt { get; }
    Storage.CompanionSettings Settings { get; }
    JsonArray LibraryJson();
    Diagnostics.DiagLog Diag { get; }

    JsonObject PcInfo();
    JsonObject BuildStatus();
    JsonArray ModelsJson();
    JsonArray DevicesJson(string? currentDevice);
    void RenamePc(string name);
    Task<(bool Ok, string Output)> StartLmServerAsync(CancellationToken ct);

    void Register(Session s);
    void Unregister(Session s);

    /// <summary>Limite de tentativas de handshake por origem (IP na LAN, canal no relay).</summary>
    bool AllowHandshake(string sourceKey);
    void ReportFailure(string sourceKey);

    /// <summary>True se este deviceId é a identidade descartável do autoteste remoto (só o próprio Companion a conhece).</summary>
    bool IsSelfTest(string deviceId);
}
