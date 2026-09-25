using System.Text.Json;
using System.Text.Json.Nodes;
using Noc.Core.Storage;

namespace Noc.Core.ImageEngine;

/// <summary>Arquivo baixado direto de uma URL oficial (Civitai, GitHub), com tamanho e SHA-256 fixados.</summary>
public sealed record RemoteFile(string Path, string Url, long Size, string Sha256);

/// <summary>
/// Uma entrada do Model Registry: um modelo de imagem (ou um pacote auxiliar, como o ControlNet) que o Noc sabe instalar,
/// conferir e executar. Os embutidos vêm sempre da distribuição oficial, com revisão e hashes fixados; os importados pelo
/// usuário são um .safetensors local detectado pelo cabeçalho.
/// </summary>
public sealed record ImageModelDef
{
    public required string Id { get; init; }
    /// <summary>Nome para o usuário ("RealVisXL V5.0").</summary>
    public required string Name { get; init; }
    /// <summary>zimage | flux | sdxl (qual pipeline o worker monta). Pacotes auxiliares: aux.</summary>
    public required string Engine { get; init; }
    /// <summary>fast | quality | specialist | custom | aux</summary>
    public required string Profile { get; init; }
    /// <summary>zimage | flux | sdxl | pony | noobai | illustrious (famílias SDXL compartilham arquitetura, não estilo de prompt).</summary>
    public string Family { get; init; } = "";
    /// <summary>Uma linha para o celular ("Fotografia e realismo").</summary>
    public string Tagline { get; init; } = "";
    /// <summary>photo | anime | illustration | general — usado no roteamento automático.</summary>
    public string Category { get; init; } = "general";
    /// <summary>Versão legível ("V5.0", "V-Pred 1.0").</summary>
    public string Version { get; init; } = "";

    /// <summary>Repositório do Hugging Face (quando a fonte é o HF).</summary>
    public string Repo { get; init; } = "";
    /// <summary>Revisão testada (commit do HF, versão do Civitai...). Atualizações só com decisão do usuário.</summary>
    public required string Revision { get; init; }
    public bool Gated { get; init; }
    public required string License { get; init; }
    public required string LicenseUrl { get; init; }
    /// <summary>Resumo do que a licença permite (mostrado antes de usar).</summary>
    public string LicenseNote { get; init; } = "";
    /// <summary>De onde vem (para a tela: "Hugging Face · SG161222").</summary>
    public string Origin { get; init; } = "";
    /// <summary>Arquivos do repositório do HF que o pipeline usa.</summary>
    public string[] Include { get; init; } = [];
    /// <summary>Arquivos baixados direto (fonte não-HF). Quando presente, substitui Repo/Include.</summary>
    public RemoteFile[]? Files { get; init; }
    /// <summary>Checkpoint de arquivo único (relativo à pasta da revisão, ou caminho absoluto num importado).</summary>
    public string? Checkpoint { get; init; }
    /// <summary>SHA-256 do checkpoint (identidade do modelo na metadata).</summary>
    public string? Sha256 { get; init; }
    /// <summary>Importado pelo usuário (arquivo local; nada a baixar).</summary>
    public bool Local { get; init; }
    /// <summary>O checkpoint traz o próprio VAE (false = usa o VAE de outro SDXL instalado).</summary>
    public bool BakedVae { get; init; } = true;
    /// <summary>Resolução de treino declarada (lado, px): 1024 no SDXL.</summary>
    public int NativeResolution { get; init; } = 1024;
    public DateTimeOffset? ImportedAt { get; init; }
    /// <summary>Tamanho aproximado do download (para a interface antes de listar o repositório).</summary>
    public required long ApproxBytes { get; init; }
    /// <summary>Espaço extra que a otimização local ocupa (ex.: cópia fp8 do transformer do FLUX).</summary>
    public long DerivedBytes { get; init; }
    /// <summary>Pacotes auxiliares sem os quais o modelo não roda (ex.: configs do SDXL).</summary>
    public string[] Requires { get; init; } = [];
    public ImageDefaults Defaults { get; init; } = ImageDefaults.None;
    public ModelPreset Preset { get; init; } = ModelPreset.Natural;
    public ModelCaps Caps { get; init; } = new();
    /// <summary>Ordem na lista de instalação automática (menor primeiro); 0 = não instala sozinho.</summary>
    public int AutoInstallOrder { get; init; }

    public bool IsAux => Engine == "aux";
    public string RepoUrl => Files is { Length: > 0 } f ? f[0].Url : "https://huggingface.co/" + Repo;

    public bool Wants(string path) =>
        Include.Any(p => p.EndsWith('/') ? path.StartsWith(p, StringComparison.Ordinal) : path == p);
}

/// <summary>Padrões medidos nesta máquina (RTX 3090 Ti) — ver PROGRESS.md, benchmarks das fases 3 e 4.</summary>
public sealed record ImageDefaults
{
    /// <summary>Passos por qualidade: rápida / padrão / alta.</summary>
    public required int[] Steps { get; init; }
    /// <summary>Guidance (FLUX) ou CFG (SDXL).</summary>
    public required double Guidance { get; init; }
    public required bool GuidanceAdjustable { get; init; }
    public required double GuidanceMin { get; init; }
    public required double GuidanceMax { get; init; }
    public required int StepsMin { get; init; }
    public required int StepsMax { get; init; }
    /// <summary>Precisão do transformer/UNet na GPU: bf16 (qualidade total) ou fp8 (armazenamento, cálculo em bf16).</summary>
    public required string Transformer { get; init; }
    /// <summary>Codificador de texto: gpu | swap | fp8.</summary>
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
    /// <summary>O modelo tem modo econômico (fp8) — SDXL não precisa: já cabe com folga.</summary>
    public bool HasLowVram { get; init; } = true;

    public static readonly ImageDefaults None = new()
    {
        Steps = [1, 1, 1], Guidance = 0, GuidanceAdjustable = false, GuidanceMin = 0, GuidanceMax = 0, StepsMin = 1, StepsMax = 1,
        Transformer = "bf16", TextEncoder = "gpu", TransformerGiB = 0, ResidentGiB = 0, PeakExtraGiB = 0, NativePortuguese = false, MaxPixels = 0,
    };
}

/// <summary>Como gerar bem com este modelo: sampler, CFG, v-prediction e como escrever o prompt (Prompt Compiler).</summary>
public sealed record ModelPreset
{
    /// <summary>natural (inglês descritivo) | tags (Danbooru) | pony (frase + tags, com score tags)</summary>
    public string Strategy { get; init; } = "natural";
    public string Sampler { get; init; } = "euler";
    /// <summary>normal | karras | exponential | beta | sgm_uniform</summary>
    public string Schedule { get; init; } = "normal";
    /// <summary>epsilon | v_prediction</summary>
    public string Prediction { get; init; } = "epsilon";
    /// <summary>Zero terminal SNR (modelos v-pred como o NoobAI).</summary>
    public bool ZeroSnr { get; init; }
    public double GuidanceRescale { get; init; }
    /// <summary>null = penúltima camada do CLIP (o "clip skip 2" das interfaces; padrão do SDXL).</summary>
    public int? ClipSkip { get; init; }
    /// <summary>Início fixo do prompt (score tags do Pony, tags de qualidade do NoobAI).</summary>
    public string Prefix { get; init; } = "";
    public string Suffix { get; init; } = "";
    public string Negative { get; init; } = "";
    /// <summary>Tipo de LoRA aceito: sdxl (qualquer família SDXL, com aviso se for de outra) | flux | none.</summary>
    public string LoraArch { get; init; } = "none";

    public static readonly ModelPreset Natural = new();
}

/// <summary>O que o modelo sabe fazer (o celular só oferece o que funciona).</summary>
public sealed record ModelCaps
{
    public bool Img2Img { get; init; } = true;
    public bool Inpaint { get; init; } = true;
    public bool Outpaint { get; init; } = true;
    public bool Loras { get; init; }
    public bool ControlNet { get; init; }
    public bool Reference { get; init; }
    public bool Negative { get; init; }
}

public static class ImageCatalog
{
    // ------------------------------------------------------------------ motores rápido e de qualidade (fase 3)

    public static readonly ImageModelDef ZImageTurbo = new()
    {
        Id = "z-image-turbo", Name = "Z-Image Turbo", Engine = "zimage", Profile = "fast", Family = "zimage", Category = "general",
        Tagline = "Rápido, fotos e texto na imagem", Version = "Turbo", Origin = "Hugging Face · Tongyi-MAI",
        Repo = "Tongyi-MAI/Z-Image-Turbo", Revision = "f332072aa78be7aecdf3ee76d5c247082da564a6", Gated = false,
        License = "Apache 2.0", LicenseUrl = "https://huggingface.co/Tongyi-MAI/Z-Image-Turbo", LicenseNote = "Uso livre, inclusive comercial.",
        Include = ["model_index.json", "README.md", "scheduler/", "text_encoder/", "tokenizer/", "transformer/", "vae/"],
        ApproxBytes = 32_860_000_000, AutoInstallOrder = 1,
        Defaults = new ImageDefaults
        {
            Steps = [6, 9, 12], Guidance = 0, GuidanceAdjustable = false, GuidanceMin = 0, GuidanceMax = 0,
            StepsMin = 4, StepsMax = 16, Transformer = "bf16", TextEncoder = "fp8", TransformerGiB = 11.4,
            ResidentGiB = 16.3, PeakExtraGiB = 3.4, NativePortuguese = false, MaxPixels = 1536L * 1536,
        },
        // expansão: o pipeline de inpaint do Z-Image não harmoniza a borda (costura dura, medido) → vai para um especialista SDXL
        Caps = new ModelCaps { Img2Img = true, Inpaint = true, Outpaint = false },
    };

    public static readonly ImageModelDef FluxDev = new()
    {
        Id = "flux1-dev", Name = "FLUX.1 Dev", Engine = "flux", Profile = "quality", Family = "flux", Category = "general",
        Tagline = "Qualidade máxima e composição", Version = "Dev", Origin = "Hugging Face · Black Forest Labs",
        Repo = "black-forest-labs/FLUX.1-dev", Revision = "3de623fc3c33e44ffbe2bad470d0f45bccf2eb21", Gated = true,
        License = "FLUX.1 [dev] Non-Commercial License", LicenseUrl = "https://huggingface.co/black-forest-labs/FLUX.1-dev",
        LicenseNote = "Uso pessoal e não comercial.",
        Include = ["model_index.json", "README.md", "LICENSE.md", "scheduler/", "text_encoder/", "text_encoder_2/", "tokenizer/", "tokenizer_2/", "transformer/", "vae/"],
        ApproxBytes = 33_770_000_000, DerivedBytes = 11_920_000_000, AutoInstallOrder = 2,
        Defaults = new ImageDefaults
        {
            Steps = [20, 28, 40], Guidance = 3.5, GuidanceAdjustable = true, GuidanceMin = 1.5, GuidanceMax = 6,
            // medido com o FLUX real: residente 14,6 GiB, pico 21 GiB (o T5 em fp8 sobe só para codificar)
            StepsMin = 12, StepsMax = 50, Transformer = "fp8", TextEncoder = "swap", TransformerGiB = 11.1,
            ResidentGiB = 14.6, PeakExtraGiB = 6.4, NativePortuguese = false, MaxPixels = 1536L * 1536,
        },
        Caps = new ModelCaps { Img2Img = true, Inpaint = true, Outpaint = false },
    };

    // ------------------------------------------------------------------ especialistas SDXL (fase 4)

    /// <summary>Padrões medidos do SDXL em bf16 (UNet + 2 CLIPs + VAE residentes; pico a 1 MP).</summary>
    private static ImageDefaults Sdxl(int[] steps, double cfg, double min, double max) => new()
    {
        Steps = steps, Guidance = cfg, GuidanceAdjustable = true, GuidanceMin = min, GuidanceMax = max, StepsMin = 8, StepsMax = 60,
        Transformer = "bf16", TextEncoder = "gpu", TransformerGiB = 4.8, ResidentGiB = 6.6, PeakExtraGiB = 4.4,
        NativePortuguese = false, MaxPixels = 1344L * 1344, HasLowVram = false,
    };

    private static readonly ModelCaps SdxlCaps = new()
    {
        Img2Img = true, Inpaint = true, Outpaint = true, Loras = true, ControlNet = true, Reference = true, Negative = true,
    };

    public const string SdxlConfigId = "sdxl-config";

    public static readonly ImageModelDef RealVisXL = new()
    {
        Id = "realvisxl-v5", Name = "RealVisXL V5.0", Engine = "sdxl", Profile = "specialist", Family = "sdxl", Category = "photo",
        Tagline = "Fotografia e realismo", Version = "V5.0", Origin = "Hugging Face · SG161222",
        Repo = "SG161222/RealVisXL_V5.0", Revision = "ac93e0dda1f6d448cae19bbfab8c5e720a5e48bc",
        License = "CreativeML Open RAIL++-M", LicenseUrl = "https://huggingface.co/SG161222/RealVisXL_V5.0",
        LicenseNote = "Uso livre, inclusive comercial, respeitando as restrições de uso da OpenRAIL++.",
        Include = ["RealVisXL_V5.0_fp16.safetensors"], Checkpoint = "RealVisXL_V5.0_fp16.safetensors",
        Sha256 = "6a35a7855770ae9820a3c931d4964c3817b6d9e3c6f9c4dabb5b3a94e5643b80",
        ApproxBytes = 6_938_065_488, Requires = [SdxlConfigId], AutoInstallOrder = 10,
        Defaults = Sdxl([20, 30, 40], 5, 2, 9),
        Preset = new ModelPreset
        {
            Strategy = "natural", Sampler = "dpmpp_2m_sde", Schedule = "karras", LoraArch = "sdxl",
            Negative = "bad hands, bad anatomy, ugly, deformed, face asymmetry, eyes asymmetry, deformed eyes, deformed mouth, open mouth",
        },
        Caps = SdxlCaps,
    };

    public static readonly ImageModelDef NoobAI = new()
    {
        Id = "noobai-xl-vpred-1", Name = "NoobAI XL", Engine = "sdxl", Profile = "specialist", Family = "noobai", Category = "illustration",
        Tagline = "Personagens e ilustração", Version = "V-Pred 1.0", Origin = "Hugging Face · Laxhar",
        Repo = "Laxhar/noobai-XL-Vpred-1.0", Revision = "66aa55e3469c27c29a89813cd35dd95fb7485fa1",
        License = "Fair AI Public License 1.0-SD", LicenseUrl = "https://freedevproject.org/faipl-1.0-sd/",
        LicenseNote = "Uso livre das imagens; modelos derivados precisam manter a mesma licença.",
        Include = ["NoobAI-XL-Vpred-v1.0.safetensors"], Checkpoint = "NoobAI-XL-Vpred-v1.0.safetensors",
        Sha256 = "ea349eeae87ca8d25ba902c93810f7ca83e5c82f920edf12f273af004ae02819",
        ApproxBytes = 7_105_350_110, Requires = [SdxlConfigId], AutoInstallOrder = 11,
        Defaults = Sdxl([20, 28, 34], 5, 3, 7),
        Preset = new ModelPreset
        {
            Strategy = "tags", Sampler = "euler", Schedule = "normal", Prediction = "v_prediction", ZeroSnr = true, GuidanceRescale = 0.5,
            LoraArch = "sdxl", Prefix = "masterpiece, best quality, newest, absurdres, highres, safe, ",
            Negative = "nsfw, worst quality, old, early, low quality, lowres, signature, username, logo, bad hands, mutated hands, mammal, anthro, furry, ambiguous form, feral, semi-anthro",
        },
        Caps = SdxlCaps,
    };

    public static readonly ImageModelDef PonyV6 = new()
    {
        Id = "pony-v6-xl", Name = "Pony Diffusion V6 XL", Engine = "sdxl", Profile = "specialist", Family = "pony", Category = "anime",
        Tagline = "Anime e estilos", Version = "V6", Origin = "Civitai · PurpleSmartAI",
        Revision = "290640",
        License = "Fair AI Public License 1.0-SD (modificada)", LicenseUrl = "https://civitai.com/models/257749",
        LicenseNote = "Uso pessoal liberado; proibido oferecer geração paga com este modelo.",
        Files = [new RemoteFile("ponyDiffusionV6XL_v6StartWithThisOne.safetensors", "https://civitai.com/api/download/models/290640?fileId=228616",
            6_938_041_050, "67ab2fd8ec439a89b3fedb15cc65f54336af163c7eb5e4f2acc98f090a29b0b3")],
        Checkpoint = "ponyDiffusionV6XL_v6StartWithThisOne.safetensors",
        Sha256 = "67ab2fd8ec439a89b3fedb15cc65f54336af163c7eb5e4f2acc98f090a29b0b3",
        ApproxBytes = 6_938_041_050, Requires = [SdxlConfigId], AutoInstallOrder = 12,
        Defaults = Sdxl([20, 25, 32], 7, 4, 10),
        Preset = new ModelPreset
        {
            Strategy = "pony", Sampler = "euler_a", Schedule = "normal", LoraArch = "sdxl",
            Prefix = "score_9, score_8_up, score_7_up, score_6_up, score_5_up, score_4_up, ",
            Negative = "signature, watermark, text, worst quality, low quality",
        },
        Caps = SdxlCaps,
    };

    // ------------------------------------------------------------------ pacotes auxiliares

    private static ImageModelDef Aux(string id, string name, string repo, string rev, string[] include, long bytes, int order, string license = "Apache 2.0") => new()
    {
        Id = id, Name = name, Engine = "aux", Profile = "aux", Repo = repo, Revision = rev, Include = include, ApproxBytes = bytes,
        License = license, LicenseUrl = "https://huggingface.co/" + repo, Origin = "Hugging Face · " + repo.Split('/')[0], AutoInstallOrder = order,
    };

    public static readonly ImageModelDef SdxlConfig = Aux(SdxlConfigId, "Configuração do SDXL", "stabilityai/stable-diffusion-xl-base-1.0",
        "462165984030d82259a11f4367a4eed129e94a7b",
        ["model_index.json", "scheduler/scheduler_config.json", "text_encoder/config.json", "text_encoder_2/config.json", "tokenizer/", "tokenizer_2/",
         "unet/config.json", "vae/config.json", "LICENSE.md"], 5_000_000, 3, "CreativeML Open RAIL++-M");

    public static readonly ImageModelDef ControlNetUnion = Aux("controlnet-union-sdxl", "ControlNet Union ProMax (SDXL)", "xinsir/controlnet-union-sdxl-1.0",
        "801a4a3fa3d4c936f4feea95b98607bc6726f80c", ["config_promax.json", "diffusion_pytorch_model_promax.safetensors"], 2_513_343_668, 20);

    public static readonly ImageModelDef IpAdapter = Aux("ip-adapter-sdxl", "IP-Adapter Plus (SDXL)", "h94/IP-Adapter",
        "018e402774aeeddd60609b4ecdb7e298259dc729",
        ["sdxl_models/ip-adapter-plus_sdxl_vit-h.safetensors", "models/image_encoder/config.json", "models/image_encoder/model.safetensors"], 3_375_891_520, 21);

    public static readonly ImageModelDef GroundingDino = Aux("grounding-dino-tiny", "Detector de rostos e mãos", "IDEA-Research/grounding-dino-tiny",
        "a2bb814dd30d776dcf7e30523b00659f4f141c71",
        ["added_tokens.json", "config.json", "model.safetensors", "preprocessor_config.json", "special_tokens_map.json", "tokenizer.json", "tokenizer_config.json", "vocab.txt"],
        690_300_000, 22);

    public static readonly ImageModelDef DepthAnything = Aux("depth-anything-v2-small", "Mapa de profundidade", "depth-anything/Depth-Anything-V2-Small-hf",
        "5426e4f0f36572d16453bbda7a8389317b1bef99", ["config.json", "model.safetensors", "preprocessor_config.json"], 99_175_385, 23);

    public static readonly ImageModelDef VitPose = Aux("vitpose-base", "Detector de pose", "usyd-community/vitpose-base-simple",
        "a93ac0c67e0b7e2c55287d21d4c460c8f3c54d45", ["config.json", "model.safetensors", "preprocessor_config.json"], 343_675_237, 24);

    public static readonly ImageModelDef RtDetr = Aux("rtdetr-r50", "Detector de pessoas", "PekingU/rtdetr_r50vd_coco_o365",
        "457857cec8ac28ddede40ecee9eed2beca321af8", ["config.json", "model.safetensors", "preprocessor_config.json"], 172_181_810, 25);

    public static readonly ImageModelDef RealEsrgan = new()
    {
        Id = "realesrgan", Name = "Ampliação (Real-ESRGAN)", Engine = "aux", Profile = "aux", Revision = "xinntao",
        License = "BSD 3-Clause", LicenseUrl = "https://github.com/xinntao/Real-ESRGAN", Origin = "GitHub · xinntao",
        Files =
        [
            new RemoteFile("RealESRGAN_x4plus.pth", "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.0/RealESRGAN_x4plus.pth",
                67_040_989, "4fa0d38905f75ac06eb49a7951b426670021be3018265fd191d2125df9d682f1"),
            new RemoteFile("RealESRGAN_x2plus.pth", "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.1/RealESRGAN_x2plus.pth",
                67_061_725, "49fafd45f8fd7aa8d31ab2a22d14d91b536c34494a5cfe31eb5d89c2fa266abb"),
            new RemoteFile("RealESRGAN_x4plus_anime_6B.pth", "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.2.4/RealESRGAN_x4plus_anime_6B.pth",
                17_938_799, "f872d837d3c90ed2e05227bed711af5671a6fd1c9f7d7e91c911a61f155e99da"),
        ],
        ApproxBytes = 152_041_513, AutoInstallOrder = 4,
    };

    public static readonly ImageModelDef[] BuiltIn =
        [ZImageTurbo, FluxDev, RealVisXL, NoobAI, PonyV6, SdxlConfig, RealEsrgan, ControlNetUnion, IpAdapter, GroundingDino, DepthAnything, VitPose, RtDetr];

    // ------------------------------------------------------------------ registro (embutidos + importados)

    private static readonly Lock RegLock = new();
    private static List<ImageModelDef> _custom = [];
    private static bool _customLoaded;

    private static string RegistryFile => Path.Combine(AppPaths.Root, "image-registry.json");

    /// <summary>Tudo que o registro conhece: embutidos e checkpoints importados.</summary>
    public static ImageModelDef[] All
    {
        get
        {
            lock (RegLock)
            {
                EnsureCustom();
                return [.. BuiltIn, .. _custom];
            }
        }
    }

    /// <summary>Só os modelos de geração (sem os pacotes auxiliares).</summary>
    public static ImageModelDef[] Models => All.Where(m => !m.IsAux).ToArray();
    public static ImageModelDef[] AuxPackages => BuiltIn.Where(m => m.IsAux).ToArray();

    public static ImageModelDef? Find(string id) => All.FirstOrDefault(m => m.Id == id);
    public static ImageModelDef? ForProfile(string profile) => BuiltIn.FirstOrDefault(m => m.Profile == profile);

    private static void EnsureCustom()
    {
        if (_customLoaded) return;
        _customLoaded = true;
        try
        {
            if (File.Exists(RegistryFile))
                _custom = JsonSerializer.Deserialize<List<ImageModelDef>>(File.ReadAllText(RegistryFile), JsonOpts) ?? [];
        }
        catch (Exception) { _custom = []; }
    }

    private static readonly JsonSerializerOptions JsonOpts = new() { WriteIndented = true };

    /// <summary>Recarrega do disco (troca da raiz de dados em testes).</summary>
    public static void ReloadCustom()
    {
        lock (RegLock) { _customLoaded = false; _custom = []; }
    }

    public static void AddCustom(ImageModelDef def)
    {
        lock (RegLock)
        {
            EnsureCustom();
            _custom.RemoveAll(m => m.Id == def.Id);
            _custom.Add(def);
            AppPaths.WriteAtomic(RegistryFile, JsonSerializer.SerializeToUtf8Bytes(_custom, JsonOpts));
        }
    }

    public static bool RemoveCustom(string id)
    {
        lock (RegLock)
        {
            EnsureCustom();
            var n = _custom.RemoveAll(m => m.Id == id);
            if (n > 0) AppPaths.WriteAtomic(RegistryFile, JsonSerializer.SerializeToUtf8Bytes(_custom, JsonOpts));
            return n > 0;
        }
    }

    /// <summary>Preset de fábrica para uma família SDXL (checkpoints importados herdam o do embutido da mesma família).</summary>
    public static (ImageDefaults Defaults, ModelPreset Preset, string Category, string Tagline) FamilyTemplate(string family, string prediction) => family switch
    {
        "pony" => (PonyV6.Defaults, PonyV6.Preset, "anime", "Anime e estilos (família Pony)"),
        "noobai" or "illustrious" => (NoobAI.Defaults, NoobAI.Preset with
        {
            Prediction = prediction, ZeroSnr = prediction == "v_prediction", GuidanceRescale = prediction == "v_prediction" ? 0.5 : 0,
            Sampler = prediction == "v_prediction" ? "euler" : "euler_a",
        }, "illustration", family == "noobai" ? "Personagens e ilustração (família NoobAI)" : "Personagens e ilustração (família Illustrious)"),
        _ => (RealVisXL.Defaults, RealVisXL.Preset with
        {
            Prediction = prediction, ZeroSnr = prediction == "v_prediction", Sampler = prediction == "v_prediction" ? "euler" : "dpmpp_2m",
            Negative = "worst quality, low quality, deformed, bad anatomy",
        }, "general", "Checkpoint SDXL"),
    };

    public static JsonObject PresetJson(ImageModelDef m) => new()
    {
        ["strategy"] = m.Preset.Strategy, ["sampler"] = m.Preset.Sampler, ["schedule"] = m.Preset.Schedule, ["prediction"] = m.Preset.Prediction,
        ["zsnr"] = m.Preset.ZeroSnr, ["guidanceRescale"] = m.Preset.GuidanceRescale, ["clipSkip"] = m.Preset.ClipSkip,
        ["prefix"] = m.Preset.Prefix, ["negative"] = m.Preset.Negative,
    };
}

/// <summary>Formatos oferecidos na criação (proporções testadas nos modelos, múltiplos de 64 — bons para SDXL, FLUX e Z-Image).</summary>
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

/// <summary>Os samplers e schedulers que o worker SDXL entende (para o modo Estúdio).</summary>
public static class Samplers
{
    public static readonly (string Id, string Label)[] All =
    [
        ("euler", "Euler"), ("euler_a", "Euler Ancestral"), ("dpmpp_2m", "DPM++ 2M"), ("dpmpp_2m_sde", "DPM++ 2M SDE"),
        ("dpmpp_3m_sde", "DPM++ 3M SDE"), ("dpmpp_sde", "DPM++ SDE"), ("ddim", "DDIM"), ("unipc", "UniPC"), ("heun", "Heun"),
        ("lms", "LMS"), ("dpm2_a", "DPM2 Ancestral"),
    ];

    public static readonly (string Id, string Label)[] Schedules =
        [("normal", "Normal"), ("karras", "Karras"), ("exponential", "Exponencial"), ("beta", "Beta"), ("sgm_uniform", "SGM Uniforme")];

    public static bool Valid(string? s) => s is not null && All.Any(x => x.Id == s);
    public static bool ValidSchedule(string? s) => s is not null && Schedules.Any(x => x.Id == s);
}
