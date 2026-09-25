using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Noc.Core.Storage;

namespace Noc.Core.ImageEngine;

/// <summary>O que o cabeçalho de um .safetensors revela (sem carregar nenhum peso e sem executar nada).</summary>
public sealed record SafetensorsInfo(IReadOnlyCollection<string> Keys, IReadOnlyDictionary<string, string> Meta, long HeaderBytes)
{
    /// <summary>Lê só o cabeçalho JSON (os primeiros bytes). Arquivo que não é safetensors válido → null.</summary>
    public static SafetensorsInfo? Read(string path)
    {
        try
        {
            using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
            if (fs.Length < 16) return null;
            var lenBytes = new byte[8];
            fs.ReadExactly(lenBytes);
            var n = BitConverter.ToInt64(lenBytes);
            if (n <= 2 || n > 100L * 1024 * 1024 || n > fs.Length - 8) return null;
            var buf = new byte[n];
            fs.ReadExactly(buf);
            if (JsonNode.Parse(buf) is not JsonObject root) return null;
            var meta = new Dictionary<string, string>();
            if (root["__metadata__"] is JsonObject m)
                foreach (var (k, v) in m) meta[k] = v?.ToString() ?? "";
            var keys = root.Select(kv => kv.Key).Where(k => k != "__metadata__").ToList();
            return new SafetensorsInfo(keys, meta, n);
        }
        catch (Exception) { return null; }
    }

    public string? M(string key) => Meta.TryGetValue(key, out var v) && v.Length > 0 ? v : null;
}

/// <summary>Resultado da detecção de um arquivo: checkpoint ou LoRA, arquitetura, família e parâmetros.</summary>
public sealed record Detection(string Kind, string Arch, string Family, string Prediction, string? BaseModel, string? Title, List<string> Triggers, string? Note);

public static class ModelDetector
{
    /// <summary>
    /// Classifica pelo cabeçalho: checkpoint SDXL (UNet + 2 CLIPs), LoRA (SDXL / SD1.5 / FLUX), v-prediction (chaves
    /// v_pred/ztsnr ou modelspec), família (Pony/NoobAI/Illustrious pelo nome, metadados e, quando houver, pelo Civitai).
    /// </summary>
    public static Detection Detect(SafetensorsInfo h, string fileName)
    {
        var keys = h.Keys;
        bool Any(string prefix) => keys.Any(k => k.StartsWith(prefix, StringComparison.Ordinal));
        bool Has(string part) => keys.Any(k => k.Contains(part, StringComparison.Ordinal));
        var arch = "unknown";
        var kind = "unknown";
        var isLora = Has("lora_down") || Has("lora_up") || Has("lora_A") || Has("lora_B") || Has(".hada_") || Has(".lokr_");
        if (isLora)
        {
            kind = "lora";
            if (Any("lora_unet_double_blocks") || Any("lora_unet_single_blocks") || Has("transformer.single_transformer_blocks") ||
                Has("double_blocks.") || Has("single_blocks.")) arch = "flux";
            else if (Any("lora_te2_") || Has("text_encoder_2.") || Any("lora_unet_input_blocks") && Has("transformer_blocks_9") ||
                     Has("down_blocks.2.attentions.1.transformer_blocks.9") || Has("input_blocks_8_1_transformer_blocks_9") ||
                     Has("output_blocks_2_1_transformer_blocks_9"))
                arch = "sdxl";
            else if (Any("lora_unet_") || Any("lora_te_") || Any("unet.")) arch = SdxlByMeta(h) ? "sdxl" : "sd15";
            if (arch != "flux" && SdxlByMeta(h)) arch = "sdxl";
        }
        else if (Any("model.diffusion_model.input_blocks") || Any("model.diffusion_model.output_blocks"))
        {
            kind = "checkpoint";
            arch = Any("conditioner.embedders.1") ? "sdxl" : Any("cond_stage_model.") ? "sd15" : "unknown";
        }
        else if (Any("double_blocks.") || Any("model.diffusion_model.double_blocks"))
        {
            kind = "checkpoint";
            arch = "flux";
        }

        var prediction = keys.Contains("v_pred") || h.M("modelspec.prediction_type") is "v" or "v_prediction" ? "v_prediction" : "epsilon";
        var title = h.M("modelspec.title") ?? h.M("ss_output_name");
        var baseModel = h.M("ss_sd_model_name") ?? h.M("ss_base_model_version") ?? h.M("modelspec.architecture");
        var hay = (fileName + " " + title + " " + baseModel + " " + h.M("ss_sd_model_name")).ToLowerInvariant();
        var family = arch switch
        {
            "sdxl" => hay.Contains("pony") ? "pony" : hay.Contains("noob") ? "noobai" : hay.Contains("illustrious") || hay.Contains("illu") ? "illustrious" : "sdxl",
            _ => arch,
        };
        if (family == "sdxl" && prediction == "v_prediction") family = "noobai";
        var triggers = TriggersFrom(h);
        return new Detection(kind, arch, family, prediction, baseModel, title, triggers, null);
    }

    private static bool SdxlByMeta(SafetensorsInfo h) =>
        (h.M("ss_base_model_version") ?? "").Contains("sdxl", StringComparison.OrdinalIgnoreCase) ||
        (h.M("modelspec.architecture") ?? "").Contains("stable-diffusion-xl", StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// Palavras de ativação pelo cabeçalho: a frase declarada (modelspec) ou, no ss_tag_frequency do kohya, só tags curtas
    /// (até 3 palavras) presentes em pelo menos 80% das imagens de uma pasta de treino. Legendas longas nunca viram gatilho
    /// (medido: o Pixel Art XL traria "pixelbuildings128 a spider with red eyes" para todo prompt).
    /// </summary>
    public static List<string> TriggersFrom(SafetensorsInfo h)
    {
        var res = new List<string>();
        if (h.M("modelspec.trigger_phrase") is { } tp) res.AddRange(tp.Split(',').Select(t => t.Trim()).Where(t => t.Length > 0));
        if (h.M("ss_tag_frequency") is { } tf)
        {
            try
            {
                if (JsonNode.Parse(tf) is JsonObject dirs)
                    foreach (var (dir, d) in dirs)
                    {
                        if (d is not JsonObject tags) continue;
                        // "10_ohwx woman": repetições + nome; o número de imagens é o da tag mais comum (aproximação segura)
                        var counts = tags.Select(kv => (Tag: kv.Key.Trim(), N: kv.Value?.GetValue<double>() ?? 0)).ToList();
                        if (counts.Count == 0) continue;
                        var images = h.M("ss_num_train_images") is { } ni && double.TryParse(ni, out var nimg) && nimg > 0 ? nimg : counts.Max(c => c.N);
                        foreach (var (tag, n) in counts.OrderByDescending(c => c.N))
                            if (n >= 0.8 * images && tag.Split(' ', StringSplitOptions.RemoveEmptyEntries).Length <= 3 && tag.Length < 40 && tags.Count > 1)
                                res.Add(tag);
                    }
            }
            catch (Exception) { }
        }
        return res.Distinct(StringComparer.OrdinalIgnoreCase).Take(3).ToList();
    }
}

/// <summary>Um LoRA na biblioteca.</summary>
public sealed class LoraEntry
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string File { get; set; } = "";
    public string Sha256 { get; set; } = "";
    public long Size { get; set; }
    /// <summary>sdxl | sd15 | flux | unknown</summary>
    public string Arch { get; set; } = "unknown";
    /// <summary>sdxl | pony | noobai | illustrious | ...</summary>
    public string Family { get; set; } = "sdxl";
    public string? BaseModel { get; set; }
    public List<string> Triggers { get; set; } = [];
    /// <summary>As palavras de ativação entram no prompt automaticamente (o usuário não escreve sintaxe).</summary>
    public bool AutoTriggers { get; set; } = true;
    public double Weight { get; set; } = 0.8;
    public bool Favorite { get; set; }
    public string? Source { get; set; }
    public string? License { get; set; }
    public string? Description { get; set; }
    public string? Creator { get; set; }
    public List<string> Tags { get; set; } = [];
    public DateTimeOffset AddedAt { get; set; } = DateTimeOffset.Now;
    public DateTimeOffset? LastUsed { get; set; }
    public int Uses { get; set; }

    public JsonObject ToJson(bool compatible, string? warning) => new()
    {
        ["id"] = Id, ["name"] = Name, ["arch"] = Arch, ["family"] = Family, ["baseModel"] = BaseModel, ["size"] = Size, ["sha256"] = Sha256,
        ["triggers"] = new JsonArray(Triggers.Select(t => (JsonNode)t).ToArray()), ["autoTriggers"] = AutoTriggers, ["weight"] = Weight,
        ["favorite"] = Favorite, ["source"] = Source, ["license"] = License, ["description"] = Description, ["creator"] = Creator,
        ["tags"] = new JsonArray(Tags.Select(t => (JsonNode)t).ToArray()), ["addedAt"] = AddedAt.ToUnixTimeMilliseconds(),
        ["lastUsed"] = LastUsed?.ToUnixTimeMilliseconds(), ["uses"] = Uses, ["compatible"] = compatible, ["warning"] = warning,
    };
}

public sealed class ImportException(string code, string message) : Exception(message)
{
    public string Code { get; } = code;
}

/// <summary>
/// Biblioteca de LoRAs e checkpoints importados. Tudo fica em &lt;raiz&gt;\user (fora de "models", que só tem distribuições
/// oficiais). Um arquivo é identificado pelo SHA-256: importar de novo o mesmo arquivo não duplica nada; no mesmo disco
/// entra por vínculo (hard link), sem ocupar espaço duas vezes. Só .safetensors — nenhum formato que executa código.
/// </summary>
public sealed class ImageLibrary : IDisposable
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };
    private readonly Lock _lock = new();
    private readonly HttpClient _http;
    private List<LoraEntry> _loras;
    private readonly Dictionary<string, JsonObject> _imports = new();

    public event Action? Changed;
    public event Action<string, string>? Activity;
    public event Action<string>? Trace;
    /// <summary>Consultar o Civitai pelo hash para descobrir nome, família, licença e palavras de ativação.</summary>
    public Func<bool> LookupEnabled { get; set; } = () => true;

    private static string LorasFile => Path.Combine(AppPaths.Root, "loras.json");

    public ImageLibrary()
    {
        _http = new HttpClient(new SocketsHttpHandler { AllowAutoRedirect = true, ConnectTimeout = TimeSpan.FromSeconds(20) }) { Timeout = Timeout.InfiniteTimeSpan };
        _http.DefaultRequestHeaders.UserAgent.ParseAdd("Noc-Companion/" + CompanionHost.Version);
        _loras = Load();
    }

    private static List<LoraEntry> Load()
    {
        try { return File.Exists(LorasFile) ? JsonSerializer.Deserialize<List<LoraEntry>>(File.ReadAllText(LorasFile)) ?? [] : []; }
        catch (Exception) { return []; }
    }

    private void Save()
    {
        lock (_lock) AppPaths.WriteAtomic(LorasFile, JsonSerializer.SerializeToUtf8Bytes(_loras, Json));
    }

    public List<LoraEntry> Loras
    {
        get { lock (_lock) return _loras.Where(l => System.IO.File.Exists(l.File)).ToList(); }
    }

    public LoraEntry? Find(string id)
    {
        lock (_lock) return _loras.FirstOrDefault(l => l.Id == id);
    }

    /// <summary>Compatibilidade com o modelo: arquitetura precisa bater; família diferente é só um aviso.</summary>
    public static (bool Ok, string? Warning) Compatible(LoraEntry l, ImageModelDef m)
    {
        var want = m.Preset.LoraArch;
        if (want == "none") return (false, $"O {m.Name} não usa LoRAs.");
        if (l.Arch != want)
            return (false, l.Arch switch
            {
                "sd15" => "Este LoRA é do Stable Diffusion 1.5 e não funciona em modelos SDXL.",
                "flux" => "Este LoRA é do FLUX e não funciona em modelos SDXL.",
                _ => "Arquitetura do LoRA não reconhecida para este modelo.",
            });
        if (l.Family is "sdxl" or "" || l.Family == m.Family) return (true, null);
        if (l.Family is "noobai" or "illustrious" && m.Family is "noobai" or "illustrious") return (true, null);
        var fam = l.Family switch { "pony" => "Pony", "noobai" => "NoobAI", "illustrious" => "Illustrious", _ => l.Family };
        return (true, $"Feito para modelos {fam}: pode funcionar, mas com resultado diferente.");
    }

    // ------------------------------------------------------------------ importação

    /// <summary>Importações em andamento (por URL): progresso para a tela.</summary>
    public JsonArray ImportsJson()
    {
        lock (_imports) return new JsonArray(_imports.Values.Select(v => v.DeepClone()).ToArray());
    }

    private void SetImport(string key, JsonObject? state)
    {
        lock (_imports)
        {
            if (state is null) _imports.Remove(key);
            else _imports[key] = state;
        }
        Changed?.Invoke();
    }

    /// <summary>Importa um arquivo que já está no PC (LoRA ou checkpoint SDXL). Devolve o id do que entrou.</summary>
    public async Task<(string Kind, string Id, bool Existing)> ImportFileAsync(string path, JsonObject? info, CancellationToken ct, bool move = false)
    {
        if (!path.EndsWith(".safetensors", StringComparison.OrdinalIgnoreCase))
            throw new ImportException("unsupported_format", "Só arquivos .safetensors são aceitos (formatos que executam código não entram).");
        var h = SafetensorsInfo.Read(path) ?? throw new ImportException("invalid_file", "O arquivo não é um .safetensors válido.");
        var det = ModelDetector.Detect(h, Path.GetFileNameWithoutExtension(path));
        var size = new FileInfo(path).Length;
        var sha = await Sha256Async(path, ct);
        info ??= await LookupAsync(sha, ct);
        if (det.Kind == "lora") return ("lora", await AddLoraAsync(path, sha, size, det, info, move, ct), false);
        if (det.Kind == "checkpoint") return ("checkpoint", AddCheckpoint(path, sha, size, det, info, move), false);
        throw new ImportException("unknown_kind", "Não reconheci este arquivo como checkpoint SDXL nem como LoRA.");
    }

    private async Task<string> AddLoraAsync(string path, string sha, long size, Detection det, JsonObject? info, bool move, CancellationToken ct)
    {
        await Task.Yield();
        LoraEntry? existing;
        lock (_lock) existing = _loras.FirstOrDefault(l => l.Sha256 == sha && System.IO.File.Exists(l.File));
        if (existing is not null) return existing.Id;
        if (det.Arch is not ("sdxl" or "flux" or "sd15"))
            throw new ImportException("unknown_arch", "Não reconheci a arquitetura deste LoRA.");
        var id = sha[..16];
        var name = info?["name"]?.GetValue<string>() ?? det.Title ?? Pretty(Path.GetFileNameWithoutExtension(path));
        var dst = Place(path, ImagePaths.UserLoras, id, move);
        var family = info?["family"]?.GetValue<string>() ?? det.Family;
        var e = new LoraEntry
        {
            Id = id, Name = name.Length > 80 ? name[..80] : name, File = dst, Sha256 = sha, Size = size, Arch = det.Arch, Family = family,
            BaseModel = info?["baseModel"]?.GetValue<string>() ?? det.BaseModel,
            // o Civitai é a fonte da verdade quando conhece o arquivo (lista vazia = o LoRA não precisa de palavra)
            Triggers = info?["triggers"] is JsonArray tw ? tw.SelectMany(t => (t?.GetValue<string>() ?? "").Split(',')).Select(t => t.Trim()).Where(t => t.Length is > 0 and < 60).Distinct().Take(8).ToList() : det.Triggers,
            Source = info?["source"]?.GetValue<string>(), License = info?["license"]?.GetValue<string>(),
            Description = info?["description"]?.GetValue<string>(), Creator = info?["creator"]?.GetValue<string>(),
            Weight = info?["weight"]?.GetValue<double>() is double w and > 0 and <= 2 ? w : 0.8,
        };
        lock (_lock)
        {
            _loras.RemoveAll(l => l.Id == id);
            _loras.Add(e);
        }
        Save();
        Activity?.Invoke($"LoRA \"{e.Name}\" adicionado à biblioteca", "model");
        Trace?.Invoke($"LoRA importado: {e.Name} ({e.Arch}/{e.Family}, {size / 1e6:0} MB, sha {sha[..12]})");
        Changed?.Invoke();
        return id;
    }

    private string AddCheckpoint(string path, string sha, long size, Detection det, JsonObject? info, bool move)
    {
        if (det.Arch != "sdxl")
            throw new ImportException("unsupported_arch", det.Arch switch
            {
                "sd15" => "Checkpoints do Stable Diffusion 1.5 ainda não são suportados (só SDXL).",
                "flux" => "Checkpoints FLUX em arquivo único ainda não são suportados.",
                _ => "Arquitetura do checkpoint não reconhecida.",
            });
        var dup = ImageCatalog.All.FirstOrDefault(m => m.Sha256 == sha);
        if (dup is not null) return dup.Id;
        var id = "ckpt-" + sha[..12];
        var family = info?["family"]?.GetValue<string>() ?? det.Family;
        var (defaults, preset, category, tagline) = ImageCatalog.FamilyTemplate(family, det.Prediction);
        var name = info?["name"]?.GetValue<string>() ?? det.Title ?? Pretty(Path.GetFileNameWithoutExtension(path));
        var dst = Place(path, ImagePaths.UserCheckpoints, id, move);
        var def = new ImageModelDef
        {
            Id = id, Name = name.Length > 60 ? name[..60] : name, Engine = "sdxl", Profile = "custom", Family = family, Category = category,
            Tagline = tagline, Version = info?["version"]?.GetValue<string>() ?? "", Origin = info?["source"]?.GetValue<string>() is { } s ? "Importado · " + s : "Importado do PC",
            Revision = sha[..12], License = info?["license"]?.GetValue<string>() ?? "Verifique a licença na página do modelo",
            LicenseUrl = info?["source"]?.GetValue<string>() ?? "", Checkpoint = dst, Sha256 = sha, Local = true, ImportedAt = DateTimeOffset.Now,
            ApproxBytes = size, Requires = [ImageCatalog.SdxlConfigId], Defaults = defaults, Preset = preset,
            Caps = new ModelCaps { Img2Img = true, Inpaint = true, Outpaint = true, Loras = true, ControlNet = true, Reference = true, Negative = true },
        };
        ImageCatalog.AddCustom(def);
        Activity?.Invoke($"Checkpoint \"{def.Name}\" adicionado ({family}, {(det.Prediction == "v_prediction" ? "v-prediction" : "epsilon")})", "model");
        Trace?.Invoke($"checkpoint importado: {def.Name} família={family} predição={det.Prediction} sha {sha[..12]}");
        Changed?.Invoke();
        return id;
    }

    /// <summary>Põe o arquivo na biblioteca: vínculo no mesmo disco (sem ocupar espaço), cópia entre discos.</summary>
    private static string Place(string src, string dir, string id, bool move)
    {
        Directory.CreateDirectory(dir);
        var dst = Path.Combine(dir, id + "_" + Safe(Path.GetFileNameWithoutExtension(src)) + ".safetensors");
        if (string.Equals(Path.GetFullPath(src), Path.GetFullPath(dst), StringComparison.OrdinalIgnoreCase)) return dst;
        if (File.Exists(dst)) return dst;
        if (move)
        {
            File.Move(src, dst);
            return dst;
        }
        if (string.Equals(Path.GetPathRoot(Path.GetFullPath(src)), Path.GetPathRoot(Path.GetFullPath(dst)), StringComparison.OrdinalIgnoreCase) &&
            CreateHardLink(dst, src, 0))
            return dst;
        File.Copy(src, dst + ".tmp", overwrite: true);
        File.Move(dst + ".tmp", dst, overwrite: true);
        return dst;
    }

    [System.Runtime.InteropServices.DllImport("kernel32.dll", CharSet = System.Runtime.InteropServices.CharSet.Unicode, SetLastError = true)]
    private static extern bool CreateHardLink(string lpFileName, string lpExistingFileName, nint lpSecurityAttributes);

    private static string Safe(string s)
    {
        var r = Regex.Replace(s, @"[^\w\-\.]+", "_");
        return r.Length > 60 ? r[..60] : r;
    }

    private static string Pretty(string s) => Regex.Replace(s.Replace('_', ' ').Replace('-', ' '), @"\s+", " ").Trim();

    private static async Task<string> Sha256Async(string path, CancellationToken ct)
    {
        await using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 20, true);
        return Convert.ToHexString(await SHA256.HashDataAsync(fs, ct)).ToLowerInvariant();
    }

    // ------------------------------------------------------------------ Civitai / Hugging Face

    /// <summary>Informações pelo hash no Civitai (nome, família, licença, palavras de ativação). Opcional; falha em silêncio.</summary>
    public async Task<JsonObject?> LookupAsync(string sha, CancellationToken ct)
    {
        if (!LookupEnabled()) return null;
        try
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(TimeSpan.FromSeconds(12));
            using var res = await _http.GetAsync($"https://civitai.com/api/v1/model-versions/by-hash/{sha}", cts.Token);
            if (!res.IsSuccessStatusCode) return null;
            return CivitaiInfo(JsonNode.Parse(await res.Content.ReadAsStringAsync(cts.Token)) as JsonObject, null);
        }
        catch (Exception e) when (e is not OperationCanceledException || !ct.IsCancellationRequested)
        {
            Trace?.Invoke($"consulta ao Civitai falhou: {e.GetType().Name}");
            return null;
        }
    }

    private static JsonObject? CivitaiInfo(JsonObject? v, JsonObject? model)
    {
        if (v is null) return null;
        var baseModel = v["baseModel"]?.GetValue<string>() ?? "";
        var family = baseModel.Contains("Pony", StringComparison.OrdinalIgnoreCase) ? "pony"
            : baseModel.Contains("NoobAI", StringComparison.OrdinalIgnoreCase) ? "noobai"
            : baseModel.Contains("Illustrious", StringComparison.OrdinalIgnoreCase) ? "illustrious"
            : baseModel.Contains("SDXL", StringComparison.OrdinalIgnoreCase) ? "sdxl"
            : baseModel.Contains("Flux", StringComparison.OrdinalIgnoreCase) ? "flux"
            : baseModel.Contains("SD 1", StringComparison.OrdinalIgnoreCase) ? "sd15" : null;
        var m = model ?? v["model"] as JsonObject;
        var modelId = v["modelId"]?.ToString();
        var o = new JsonObject
        {
            ["name"] = (m?["name"]?.GetValue<string>() ?? "") is { Length: > 0 } mn ? mn + (v["name"]?.GetValue<string>() is { Length: > 0 } vn && vn != mn ? " · " + vn : "") : v["name"]?.GetValue<string>(),
            ["baseModel"] = baseModel, ["family"] = family, ["version"] = v["name"]?.GetValue<string>(),
            ["source"] = modelId is null ? null : $"https://civitai.com/models/{modelId}?modelVersionId={v["id"]}",
            ["triggers"] = v["trainedWords"] is JsonArray tw ? (JsonNode)tw.DeepClone() : new JsonArray(),
            ["creator"] = m?["creator"]?["username"]?.GetValue<string>(),
        };
        if (m is not null)
        {
            var commercial = m["allowCommercialUse"] is JsonArray ac ? string.Join(",", ac.Select(x => x?.ToString())) : m["allowCommercialUse"]?.ToString();
            o["license"] = $"Civitai: crédito {(m["allowNoCredit"]?.GetValue<bool>() == true ? "opcional" : "obrigatório")}, uso comercial: {(string.IsNullOrEmpty(commercial) || commercial == "None" ? "não" : commercial)}";
        }
        return o;
    }

    private static readonly Regex CivitaiUrl = new(@"civitai\.com/(?:models/(?<m>\d+)(?:[^?]*)?(?:\?.*modelVersionId=(?<v>\d+))?|api/download/models/(?<v2>\d+))", RegexOptions.IgnoreCase);
    private static readonly Regex HfUrl = new(@"huggingface\.co/(?<repo>[^/]+/[^/]+)/(?:blob|resolve)/(?<rev>[^/]+)/(?<path>.+?\.safetensors)", RegexOptions.IgnoreCase);

    /// <summary>
    /// Importa por link (Civitai ou Hugging Face): descobre o arquivo .safetensors, confere espaço, baixa com retomada e
    /// confere o SHA-256 publicado pela fonte. Roda em segundo plano; o progresso aparece em ImportsJson.
    /// </summary>
    public string StartImportUrl(string url)
    {
        var key = "u" + Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(url)))[..10].ToLowerInvariant();
        lock (_imports) if (_imports.ContainsKey(key)) return key;
        SetImport(key, new JsonObject { ["key"] = key, ["url"] = url, ["state"] = "checking" });
        _ = Task.Run(async () =>
        {
            try
            {
                var (fileUrl, name, sha, size, info) = await ResolveUrlAsync(url, CancellationToken.None);
                var free = ImagePaths.FreeBytes(ImagePaths.User);
                if (free >= 0 && free < size + 2L * 1024 * 1024 * 1024)
                    throw new ImportException("no_space", $"Espaço insuficiente no disco ({ImageModelStore.Gb(free)} livres, precisa de {ImageModelStore.Gb(size)}).");
                Directory.CreateDirectory(ImagePaths.Tmp);
                var part = Path.Combine(ImagePaths.User, "downloads", Safe(name) + ".safetensors.part");
                Directory.CreateDirectory(Path.GetDirectoryName(part)!);
                await DownloadAsync(fileUrl, part, size, (done, total) => SetImport(key, new JsonObject
                {
                    ["key"] = key, ["url"] = url, ["name"] = name, ["state"] = "downloading", ["done"] = done, ["total"] = total,
                }), CancellationToken.None);
                SetImport(key, new JsonObject { ["key"] = key, ["url"] = url, ["name"] = name, ["state"] = "verifying" });
                var got = await Sha256Async(part, CancellationToken.None);
                if (sha is not null && !string.Equals(got, sha, StringComparison.OrdinalIgnoreCase))
                {
                    File.Delete(part);
                    throw new ImportException("checksum", "O arquivo baixado não confere com o hash publicado pela fonte.");
                }
                var final = part[..^5];
                File.Move(part, final, overwrite: true);
                var (kind, id, _) = await ImportFileAsync(final, info, CancellationToken.None, move: true);
                SetImport(key, new JsonObject { ["key"] = key, ["url"] = url, ["name"] = name, ["state"] = "done", ["kind"] = kind, ["id"] = id });
            }
            catch (Exception e)
            {
                var (code, msg) = e is ImportException ie ? (ie.Code, ie.Message) : ("failed", "Não foi possível importar este link.");
                Trace?.Invoke($"importação de link falhou: {e.GetType().Name} {e.Message}");
                SetImport(key, new JsonObject { ["key"] = key, ["url"] = url, ["state"] = "failed", ["error"] = code, ["detail"] = msg });
            }
        });
        return key;
    }

    public void ClearImport(string key) => SetImport(key, null);

    private async Task<(string Url, string Name, string? Sha, long Size, JsonObject? Info)> ResolveUrlAsync(string url, CancellationToken ct)
    {
        var cm = CivitaiUrl.Match(url);
        if (cm.Success)
        {
            JsonObject? version;
            JsonObject? model = null;
            var vid = cm.Groups["v"].Success ? cm.Groups["v"].Value : cm.Groups["v2"].Success ? cm.Groups["v2"].Value : null;
            if (vid is null)
            {
                model = await GetJsonAsync($"https://civitai.com/api/v1/models/{cm.Groups["m"].Value}", ct);
                version = (model?["modelVersions"] as JsonArray)?.OfType<JsonObject>().FirstOrDefault();
            }
            else version = await GetJsonAsync($"https://civitai.com/api/v1/model-versions/{vid}", ct);
            if (version is null) throw new ImportException("not_found", "Não encontrei este modelo no Civitai.");
            model ??= await GetJsonAsync($"https://civitai.com/api/v1/models/{version["modelId"]}", ct);
            var file = (version["files"] as JsonArray)?.OfType<JsonObject>()
                .Where(f => f["name"]?.GetValue<string>()?.EndsWith(".safetensors", StringComparison.OrdinalIgnoreCase) == true)
                .OrderByDescending(f => f["primary"]?.GetValue<bool>() == true).FirstOrDefault()
                ?? throw new ImportException("unsupported_format", "Este modelo não tem versão .safetensors (outros formatos podem executar código).");
            var size = (long)((file["sizeKB"]?.GetValue<double>() ?? 0) * 1024);
            var info = CivitaiInfo(version, model);
            return (file["downloadUrl"]!.GetValue<string>(), file["name"]!.GetValue<string>().Replace(".safetensors", "", StringComparison.OrdinalIgnoreCase),
                file["hashes"]?["SHA256"]?.GetValue<string>()?.ToLowerInvariant(), size, info);
        }
        var hm = HfUrl.Match(url);
        if (hm.Success)
        {
            var repo = hm.Groups["repo"].Value;
            var rev = hm.Groups["rev"].Value;
            var path = hm.Groups["path"].Value;
            var tree = await GetJsonNodeAsync($"https://huggingface.co/api/models/{repo}/tree/{rev}?recursive=true", ct) as JsonArray
                ?? throw new ImportException("not_found", "Não encontrei este arquivo no Hugging Face.");
            var n = tree.OfType<JsonObject>().FirstOrDefault(x => x["path"]?.GetValue<string>() == path)
                ?? throw new ImportException("not_found", "Não encontrei este arquivo no Hugging Face.");
            var lfs = n["lfs"] as JsonObject;
            return ($"https://huggingface.co/{repo}/resolve/{rev}/{path}", Path.GetFileNameWithoutExtension(path), lfs?["oid"]?.GetValue<string>(),
                lfs?["size"]?.GetValue<long>() ?? n["size"]?.GetValue<long>() ?? 0,
                new JsonObject { ["source"] = $"https://huggingface.co/{repo}", ["name"] = Pretty(Path.GetFileNameWithoutExtension(path)) });
        }
        if (Uri.TryCreate(url, UriKind.Absolute, out var u) && u.Scheme == "https" && u.AbsolutePath.EndsWith(".safetensors", StringComparison.OrdinalIgnoreCase))
            return (url, Path.GetFileNameWithoutExtension(u.AbsolutePath), null, 0, new JsonObject { ["source"] = u.GetLeftPart(UriPartial.Authority) });
        throw new ImportException("bad_url", "Use um link do Civitai, do Hugging Face ou um link https direto para um .safetensors.");
    }

    private async Task<JsonObject?> GetJsonAsync(string url, CancellationToken ct) => await GetJsonNodeAsync(url, ct) as JsonObject;

    private async Task<JsonNode?> GetJsonNodeAsync(string url, CancellationToken ct)
    {
        using var res = await _http.GetAsync(url, ct);
        if (res.StatusCode == HttpStatusCode.NotFound) return null;
        res.EnsureSuccessStatusCode();
        return JsonNode.Parse(await res.Content.ReadAsStringAsync(ct));
    }

    private async Task DownloadAsync(string url, string part, long expected, Action<long, long> progress, CancellationToken ct)
    {
        var backoff = TimeSpan.FromSeconds(2);
        for (var attempt = 0; ; attempt++)
        {
            try
            {
                var have = File.Exists(part) ? new FileInfo(part).Length : 0;
                using var req = new HttpRequestMessage(HttpMethod.Get, url);
                if (have > 0) req.Headers.Range = new RangeHeaderValue(have, null);
                using var res = await _http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct);
                if (res.StatusCode == HttpStatusCode.Unauthorized || res.StatusCode == HttpStatusCode.Forbidden)
                    throw new ImportException("auth_required", "A fonte exige login para baixar este arquivo. Baixe pelo navegador e coloque na pasta Downloads do PC: o Noc importa sozinho.");
                if (res.StatusCode == HttpStatusCode.RequestedRangeNotSatisfiable) return;
                res.EnsureSuccessStatusCode();
                var append = have > 0 && res.StatusCode == HttpStatusCode.PartialContent;
                var total = expected > 0 ? expected : (res.Content.Headers.ContentLength ?? 0) + (append ? have : 0);
                await using var body = await res.Content.ReadAsStreamAsync(ct);
                await using var fs = new FileStream(part, append ? FileMode.Append : FileMode.Create, FileAccess.Write, FileShare.Read, 1 << 20, true);
                var buf = new byte[1 << 20];
                var done = append ? have : 0;
                var last = DateTimeOffset.MinValue;
                int n;
                while ((n = await body.ReadAsync(buf, ct)) > 0)
                {
                    await fs.WriteAsync(buf.AsMemory(0, n), ct);
                    done += n;
                    if (DateTimeOffset.Now - last > TimeSpan.FromMilliseconds(700)) { progress(done, total); last = DateTimeOffset.Now; }
                }
                progress(done, total);
                return;
            }
            catch (Exception e) when (e is HttpRequestException or IOException && attempt < 8 && !ct.IsCancellationRequested)
            {
                Trace?.Invoke($"download interrompido ({e.GetType().Name}); nova tentativa em {backoff.TotalSeconds:0} s");
                await Task.Delay(backoff, ct);
                backoff = TimeSpan.FromSeconds(Math.Min(60, backoff.TotalSeconds * 2));
            }
        }
    }

    // ------------------------------------------------------------------ edição

    public bool Update(string id, JsonObject p)
    {
        lock (_lock)
        {
            var l = _loras.FirstOrDefault(x => x.Id == id);
            if (l is null) return false;
            if (p["name"]?.GetValue<string>() is { Length: > 0 and <= 80 } n) l.Name = n.Trim();
            if (p["favorite"]?.GetValue<bool>() is bool f) l.Favorite = f;
            if (p["weight"]?.GetValue<double>() is double w) l.Weight = Math.Round(Math.Clamp(w, -2, 2), 2);
            if (p["autoTriggers"]?.GetValue<bool>() is bool at) l.AutoTriggers = at;
            if (p["triggers"] is JsonArray tr)
                l.Triggers = tr.Select(t => t?.GetValue<string>()?.Trim() ?? "").Where(t => t.Length is > 0 and < 80).Distinct().Take(12).ToList();
            if (p["tags"] is JsonArray tg)
                l.Tags = tg.Select(t => t?.GetValue<string>()?.Trim() ?? "").Where(t => t.Length is > 0 and < 40).Distinct().Take(12).ToList();
        }
        Save();
        Changed?.Invoke();
        return true;
    }

    public void MarkUsed(IEnumerable<string> ids)
    {
        var set = ids.ToHashSet();
        if (set.Count == 0) return;
        lock (_lock)
            foreach (var l in _loras.Where(l => set.Contains(l.Id))) { l.LastUsed = DateTimeOffset.Now; l.Uses++; }
        Save();
    }

    public bool Delete(string id)
    {
        LoraEntry? l;
        lock (_lock)
        {
            l = _loras.FirstOrDefault(x => x.Id == id);
            if (l is null) return false;
            _loras.Remove(l);
        }
        // só apaga o que está na biblioteca do Noc (o original do usuário nunca é tocado)
        if (Path.GetFullPath(l.File).StartsWith(Path.GetFullPath(ImagePaths.UserLoras), StringComparison.OrdinalIgnoreCase))
            try { File.Delete(l.File); } catch (Exception) { }
        Save();
        Activity?.Invoke($"LoRA \"{l.Name}\" removido", "model");
        Changed?.Invoke();
        return true;
    }

    // ------------------------------------------------------------------ pasta de importação automática

    private FileSystemWatcher? _watcher;
    private readonly HashSet<string> _seen = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Vigia a pasta Downloads: um .safetensors novo (LoRA ou checkpoint SDXL) entra sozinho na biblioteca.</summary>
    public void Watch(string folder)
    {
        try
        {
            if (!Directory.Exists(folder)) return;
            _watcher = new FileSystemWatcher(folder, "*.safetensors") { IncludeSubdirectories = false, NotifyFilter = NotifyFilters.FileName | NotifyFilters.Size | NotifyFilters.LastWrite };
            _watcher.Created += (_, e) => Consider(e.FullPath);
            _watcher.Renamed += (_, e) => Consider(e.FullPath);
            _watcher.EnableRaisingEvents = true;
            foreach (var f in Directory.EnumerateFiles(folder, "*.safetensors")) _seen.Add(f); // os que já estavam: só por pedido explícito
        }
        catch (Exception e) { Trace?.Invoke($"vigiar {folder} falhou: {e.Message}"); }
    }

    private void Consider(string path)
    {
        lock (_seen) if (!_seen.Add(path)) return;
        _ = Task.Run(async () =>
        {
            // espera o arquivo parar de crescer (download do navegador)
            long last = -1;
            for (var i = 0; i < 720; i++)
            {
                await Task.Delay(TimeSpan.FromSeconds(5));
                if (!File.Exists(path)) return;
                var len = new FileInfo(path).Length;
                if (len == last && len > 0) break;
                last = len;
            }
            try
            {
                var (kind, id, _) = await ImportFileAsync(path, null, CancellationToken.None);
                Trace?.Invoke($"importado sozinho da pasta Downloads: {Path.GetFileName(path)} → {kind} {id}");
            }
            catch (ImportException e) { Trace?.Invoke($"{Path.GetFileName(path)} não importado: {e.Code}"); }
            catch (Exception e) { Trace?.Invoke($"{Path.GetFileName(path)} não importado: {e.GetType().Name}"); }
        });
    }

    public void Dispose()
    {
        _watcher?.Dispose();
        _http.Dispose();
    }
}
