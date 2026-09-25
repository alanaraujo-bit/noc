namespace Noc.Core.ImageEngine;

/// <summary>Um modelo de imagem que o Noc sabe instalar e executar (sempre a distribuição oficial, revisão fixada).</summary>
public sealed record ImageModelDef
{
    public required string Id { get; init; }
    /// <summary>Nome para o usuário ("Z-Image Turbo").</summary>
    public required string Name { get; init; }
    /// <summary>zimage | flux (qual pipeline o worker monta).</summary>
    public required string Engine { get; init; }
    /// <summary>fast | quality</summary>
    public required string Profile { get; init; }
    public required string Repo { get; init; }
    /// <summary>Revisão testada. Atualizações só com decisão do usuário.</summary>
    public required string Revision { get; init; }
    public required bool Gated { get; init; }
    public required string License { get; init; }
    public required string LicenseUrl { get; init; }
    /// <summary>Prefixos/arquivos do repositório que o pipeline usa (o resto — imagens de exemplo, cópias em arquivo único — não é baixado).</summary>
    public required string[] Include { get; init; }
    /// <summary>Tamanho aproximado do download (para a interface antes de listar o repositório).</summary>
    public required long ApproxBytes { get; init; }
    /// <summary>Espaço extra que a otimização local ocupa (ex.: cópia fp8 do transformer do FLUX).</summary>
    public long DerivedBytes { get; init; }
    public required ImageDefaults Defaults { get; init; }

    public string RepoUrl => "https://huggingface.co/" + Repo;

    public bool Wants(string path) =>
        Include.Any(p => p.EndsWith('/') ? path.StartsWith(p, StringComparison.Ordinal) : path == p);
}

/// <summary>Padrões medidos nesta máquina (RTX 3090 Ti) — ver PROGRESS.md, benchmark da fase 3.</summary>
public sealed record ImageDefaults
{
    /// <summary>Passos por qualidade: rápida / padrão / alta.</summary>
    public required int[] Steps { get; init; }
    public required double Guidance { get; init; }
    /// <summary>O guidance tem efeito real neste modelo (Turbo: não; FLUX dev: guidance destilado).</summary>
    public required bool GuidanceAdjustable { get; init; }
    public required double GuidanceMin { get; init; }
    public required double GuidanceMax { get; init; }
    public required int StepsMin { get; init; }
    public required int StepsMax { get; init; }
    /// <summary>Precisão do transformer na GPU: bf16 (qualidade total) ou fp8 (armazenamento, cálculo em bf16).</summary>
    public required string Transformer { get; init; }
    /// <summary>Codificador de texto: gpu (fica na GPU) | swap (sobe só para codificar o prompt).</summary>
    public required string TextEncoder { get; init; }
    /// <summary>Tamanho do transformer na precisão padrão (GiB): o modo econômico usa fp8 (metade).</summary>
    public required double TransformerGiB { get; init; }
    /// <summary>VRAM (GiB) com o motor carregado, antes de gerar (medida).</summary>
    public required double ResidentGiB { get; init; }
    /// <summary>VRAM extra (GiB) no pico de uma geração de 1 megapixel (medida).</summary>
    public required double PeakExtraGiB { get; init; }
    /// <summary>O modelo entende bem prompts em português sem tradução.</summary>
    public required bool NativePortuguese { get; init; }
    /// <summary>Máximo de pixels suportado com confiança (largura × altura).</summary>
    public required long MaxPixels { get; init; }
}

public static class ImageCatalog
{
    public static readonly ImageModelDef ZImageTurbo = new()
    {
        Id = "z-image-turbo",
        Name = "Z-Image Turbo",
        Engine = "zimage",
        Profile = "fast",
        Repo = "Tongyi-MAI/Z-Image-Turbo",
        Revision = "f332072aa78be7aecdf3ee76d5c247082da564a6",
        Gated = false,
        License = "Apache 2.0",
        LicenseUrl = "https://huggingface.co/Tongyi-MAI/Z-Image-Turbo",
        Include = ["model_index.json", "README.md", "scheduler/", "text_encoder/", "tokenizer/", "transformer/", "vae/"],
        ApproxBytes = 32_860_000_000,
        Defaults = new ImageDefaults
        {
            Steps = [6, 9, 12], Guidance = 0, GuidanceAdjustable = false, GuidanceMin = 0, GuidanceMax = 0,
            StepsMin = 4, StepsMax = 16, Transformer = "bf16", TextEncoder = "fp8", TransformerGiB = 11.4,
            ResidentGiB = 20.6, PeakExtraGiB = 1.4, NativePortuguese = false, MaxPixels = 2048L * 2048,
        },
    };

    public static readonly ImageModelDef FluxDev = new()
    {
        Id = "flux1-dev",
        Name = "FLUX.1 Dev",
        Engine = "flux",
        Profile = "quality",
        Repo = "black-forest-labs/FLUX.1-dev",
        Revision = "3de623fc3c33e44ffbe2bad470d0f45bccf2eb21",
        Gated = true,
        License = "FLUX.1 [dev] Non-Commercial License",
        LicenseUrl = "https://huggingface.co/black-forest-labs/FLUX.1-dev",
        Include = ["model_index.json", "README.md", "LICENSE.md", "scheduler/", "text_encoder/", "text_encoder_2/", "tokenizer/", "tokenizer_2/", "transformer/", "vae/"],
        ApproxBytes = 33_770_000_000,
        DerivedBytes = 11_920_000_000,
        Defaults = new ImageDefaults
        {
            Steps = [20, 28, 40], Guidance = 3.5, GuidanceAdjustable = true, GuidanceMin = 1.5, GuidanceMax = 6,
            StepsMin = 12, StepsMax = 50, Transformer = "fp8", TextEncoder = "swap", TransformerGiB = 11.1,
            ResidentGiB = 12.7, PeakExtraGiB = 3.0, NativePortuguese = false, MaxPixels = 1536L * 1536,
        },
    };

    public static readonly ImageModelDef[] All = [ZImageTurbo, FluxDev];

    public static ImageModelDef? Find(string id) => All.FirstOrDefault(m => m.Id == id);
    public static ImageModelDef? ForProfile(string profile) => All.FirstOrDefault(m => m.Profile == profile);
}

/// <summary>Formatos oferecidos na criação (proporções testadas nos dois modelos, múltiplos de 16).</summary>
public static class ImageFormats
{
    public sealed record Format(string Id, string Label, int Width, int Height);

    public static readonly Format[] All =
    [
        new("square", "Quadrado", 1024, 1024),
        new("portrait", "Retrato", 832, 1216),
        new("landscape", "Paisagem", 1216, 832),
        new("story", "Story", 768, 1344),
        new("wallpaper", "Wallpaper", 1344, 768),
    ];

    public static Format? Find(string id) => All.FirstOrDefault(f => f.Id == id);

    /// <summary>Ajusta um tamanho livre para o que o modelo aceita: múltiplo de 16, limites e área máxima.</summary>
    public static (int W, int H) Snap(int w, int h, long maxPixels)
    {
        w = Math.Clamp(w, 256, 2048);
        h = Math.Clamp(h, 256, 2048);
        var area = (long)w * h;
        if (area > maxPixels)
        {
            var k = Math.Sqrt((double)maxPixels / area);
            w = (int)(w * k);
            h = (int)(h * k);
        }
        return (Math.Max(256, w / 16 * 16), Math.Max(256, h / 16 * 16));
    }
}
