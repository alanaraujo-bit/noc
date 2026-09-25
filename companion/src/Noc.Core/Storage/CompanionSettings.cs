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
    /// <summary>Ditado por voz (Whisper local na GPU).</summary>
    public bool VoiceEnabled { get; set; } = true;
    /// <summary>Procurar GGUFs novos no Downloads (e nas pastas extras) e colocá-los no LM Studio sozinho.</summary>
    public bool AutoImportModels { get; set; } = true;
    public List<string> ExtraModelFolders { get; set; } = [];
    /// <summary>Permite baixar componentes que faltam (mmproj oficial, modelo de voz) de fontes públicas.</summary>
    public bool AllowComponentDownloads { get; set; } = true;
    /// <summary>Religa o servidor do LM Studio sozinho se ele cair.</summary>
    public bool KeepLmServerAlive { get; set; } = true;
    /// <summary>Geração de imagens local (Z-Image Turbo / FLUX.1 Dev).</summary>
    public bool ImageEnabled { get; set; } = true;
    /// <summary>Pasta única do motor de imagem (runtime, modelos, cache, imagens). Escolhida uma vez: o disco local com mais espaço.</summary>
    public string? ImageEngineRoot { get; set; }
    /// <summary>Quanto tempo o modelo de imagem fica aquecido depois da última imagem quando a GPU estava livre.</summary>
    public int ImageKeepWarmMinutes { get; set; } = 10;
    /// <summary>Com um modelo de conversa esperando para voltar, o motor de imagem libera a GPU mais cedo.</summary>
    public int ImageKeepWarmWithLlmSeconds { get; set; } = 90;
    /// <summary>VRAM (GiB) que o motor de imagem deixa para outros programas (ex.: um jogo aberto). 0 = usa o que estiver livre.</summary>
    public double ImageVramReserveGiB { get; set; }

    private static readonly JsonSerializerOptions Json = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
    };

    private static string Backup => AppPaths.Settings + ".bak";

    public static CompanionSettings Load()
    {
        // arquivo corrompido (ex.: queda de energia): usa a cópia anterior; sem nenhuma, os padrões — sem derrubar o app
        foreach (var path in new[] { AppPaths.Settings, Backup })
        {
            try
            {
                if (File.Exists(path) && JsonSerializer.Deserialize<CompanionSettings>(File.ReadAllText(path)) is { } s) return s;
            }
            catch (Exception) { }
        }
        return new CompanionSettings();
    }

    public void Save()
    {
        // a versão anterior (válida) fica como cópia de segurança
        try
        {
            if (File.Exists(AppPaths.Settings) && JsonSerializer.Deserialize<CompanionSettings>(File.ReadAllText(AppPaths.Settings)) is not null)
                File.Copy(AppPaths.Settings, Backup, overwrite: true);
        }
        catch (Exception) { }
        AppPaths.WriteAtomic(AppPaths.Settings, JsonSerializer.SerializeToUtf8Bytes(this, Json));
    }
}
