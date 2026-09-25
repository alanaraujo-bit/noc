using System.Text.Json;
using System.Text.Json.Serialization;

namespace Noc.Core.Storage;

public sealed class CompanionSettings
{
    public const string DefaultRelay = "wss://noc-relay-production.up.railway.app";

    public string PcName { get; set; } = Environment.MachineName;
    public string RelayUrl { get; set; } = DefaultRelay;
    public bool RemoteEnabled { get; set; } = true;
    public bool LanEnabled { get; set; } = true;
    public int LanPort { get; set; } = 47821;
    /// <summary>Porta do LM Studio. 0 = ler do config do LM Studio.</summary>
    public int LmStudioPort { get; set; }
    /// <summary>Ao carregar um modelo pelo celular, descarregar os outros antes (evita estourar a VRAM).</summary>
    public bool SingleModel { get; set; } = true;
    /// <summary>Tenta iniciar o servidor do LM Studio automaticamente quando o Companion abre.</summary>
    public bool AutoStartLmServer { get; set; } = true;
    public bool StartMinimized { get; set; } = true;
    public int DefaultContextLength { get; set; } = 16384;
    public bool FirstRunDone { get; set; }
    /// <summary>system | light | dark</summary>
    public string Theme { get; set; } = "system";

    private static readonly JsonSerializerOptions Json = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
    };

    public static CompanionSettings Load()
    {
        try
        {
            if (File.Exists(AppPaths.Settings))
                return JsonSerializer.Deserialize<CompanionSettings>(File.ReadAllText(AppPaths.Settings)) ?? new();
        }
        catch (Exception)
        {
            // Arquivo corrompido: volta aos padrões sem derrubar o app.
        }
        return new CompanionSettings();
    }

    public void Save() => AppPaths.WriteAtomic(AppPaths.Settings, JsonSerializer.SerializeToUtf8Bytes(this, Json));
}
