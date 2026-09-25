using System.Diagnostics;
using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Noc.Core.LmStudio;
using Noc.Core.Storage;

namespace Noc.Core.Library;

/// <summary>Nomes de modelo legíveis: "Gemma4 26B A4B QAT Uncensored HauhauCS Balanced" → "Gemma 4 26B-A4B".</summary>
public static class ModelNames
{
    private static readonly Dictionary<string, string> Known = new(StringComparer.OrdinalIgnoreCase)
    {
        ["gpt"] = "GPT", ["oss"] = "OSS", ["glm"] = "GLM", ["lfm"] = "LFM", ["phi"] = "Phi", ["olmo"] = "OLMo",
        ["deepseek"] = "DeepSeek", ["qwq"] = "QwQ", ["smollm"] = "SmolLM", ["internvl"] = "InternVL", ["minicpm"] = "MiniCPM",
    };

    public static string Friendly(string raw, string? paramsString, string? quant)
    {
        var tokens = Regex.Split(raw.Trim(), @"[-_ ]+").Where(t => t.Length > 0).ToList();
        var family = new List<string>();
        string? size = null;
        for (var i = 0; i < tokens.Count; i++)
        {
            var t = tokens[i];
            if (Regex.IsMatch(t, @"^\d+(\.\d+)?[BbMm]$"))
            {
                size = t.ToUpperInvariant();
                if (i + 1 < tokens.Count && Regex.IsMatch(tokens[i + 1], @"^A\d+(\.\d+)?B$", RegexOptions.IgnoreCase))
                    size += "-" + tokens[i + 1].ToUpperInvariant();
                break;
            }
            family.Add(t);
        }
        if (size is null)
        {
            // sem tamanho no nome: usa o nome inteiro, limpo
            var clean = string.Join(' ', tokens.Where(t => !Regex.IsMatch(t, @"^(I?Q\d.*|F16|BF16|F32|GGUF)$", RegexOptions.IgnoreCase)).Take(4).Select(Word));
            return string.IsNullOrWhiteSpace(clean) ? raw : clean + (paramsString is { Length: > 0 } p ? " " + p : "");
        }
        if (family.Count == 0) return raw;
        var words = family.SelectMany(SplitLettersDigits).Select(Word);
        return string.Join(' ', words) + " " + size;
    }

    private static IEnumerable<string> SplitLettersDigits(string t)
    {
        // "Qwen3.5" → "Qwen", "3.5"; "Gemma4" → "Gemma", "4"; "v1.5" fica junto
        var m = Regex.Match(t, @"^([A-Za-z]{2,})(\d+(\.\d+)?)$");
        if (m.Success) { yield return m.Groups[1].Value; yield return m.Groups[2].Value; }
        else yield return t;
    }

    private static string Word(string t)
    {
        if (Known.TryGetValue(t, out var k)) return k;
        if (t.Length > 0 && t.All(c => char.IsLower(c) || char.IsDigit(c) || c == '.')) return char.ToUpperInvariant(t[0]) + t[1..];
        return t;
    }

    /// <summary>Total de parâmetros em bilhões a partir de "26B-A4B", "9B", "270M".</summary>
    public static double? ParamsB(string? s)
    {
        if (s is null) return null;
        var m = Regex.Match(s, @"(\d+(?:\.\d+)?)\s*([BM])", RegexOptions.IgnoreCase);
        if (!m.Success) return null;
        var v = double.Parse(m.Groups[1].Value, CultureInfo.InvariantCulture);
        return m.Groups[2].Value.Equals("M", StringComparison.OrdinalIgnoreCase) ? v / 1000 : v;
    }

    /// <summary>Parâmetros ativos (MoE "26B-A4B" → 4).</summary>
    public static double? ActiveB(string? s)
    {
        if (s is null) return null;
        var m = Regex.Match(s, @"A(\d+(?:\.\d+)?)B", RegexOptions.IgnoreCase);
        return m.Success ? double.Parse(m.Groups[1].Value, CultureInfo.InvariantCulture) : ParamsB(s);
    }
}

public static class Tiers
{
    public const string Fast = "fast";
    public const string Smart = "smart";
    public const string Deep = "deep";
    public static readonly string[] All = [Fast, Smart, Deep];

    public static string Label(string tier) => tier switch { Fast => "Rápido", Smart => "Inteligente", Deep => "Profundo", _ => tier };

    /// <summary>Contexto alvo de cada perfil (reduzido automaticamente se não couber na VRAM).</summary>
    public static int TargetContext(string? tier) => tier switch { Deep => 65536, _ => 32768 };

    /// <summary>Raciocínio padrão do perfil quando o modelo permite desligar.</summary>
    public static string DefaultReasoning(string? tier) => tier switch { Fast => "off", _ => "auto" };
}

/// <summary>Preferências do usuário para um modelo (apelido, favorito, perfil de desempenho, benchmark).</summary>
public sealed class ModelPrefs
{
    public string? Alias { get; set; }
    public bool Favorite { get; set; }
    public bool Hidden { get; set; }
    /// <summary>Contexto fixo escolhido no modo avançado (null = automático).</summary>
    public int? Context { get; set; }
    /// <summary>auto | off | on | low | medium | high</summary>
    public string? Reasoning { get; set; }
    public int? Parallel { get; set; }
    public double? LastLoadSeconds { get; set; }
    public string? LastError { get; set; }
    public DateTimeOffset? LastErrorAt { get; set; }
    public JsonObject? Benchmark { get; set; }
    public bool? MtpWorks { get; set; }
}

public sealed class CatalogData
{
    public Dictionary<string, string?> Tiers { get; set; } = new();
    /// <summary>true enquanto o usuário não escolheu os modelos dos perfis: o Noc reorganiza quando entram modelos novos.</summary>
    public bool TiersAuto { get; set; } = true;
    public string? DefaultModel { get; set; }
    public bool DefaultAuto { get; set; } = true;
    public bool Preload { get; set; } = true;
    public Dictionary<string, ModelPrefs> Models { get; set; } = new();
    /// <summary>Estimativas de VRAM (GiB) já calculadas: "chave|tamanho|contexto" → GiB.</summary>
    public Dictionary<string, double> Estimates { get; set; } = new();
}

/// <summary>
/// O catálogo do usuário: perfis Rápido/Inteligente/Profundo, modelo padrão, apelidos, favoritos e
/// o planejador de carga (contexto que cabe na VRAM, segundo a própria estimativa do LM Studio).
/// </summary>
public sealed class ModelCatalog
{
    private static string FilePath => Path.Combine(AppPaths.Root, "models.json");
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };
    private readonly Lock _lock = new();
    public CatalogData Data { get; private set; }
    public event Action? Changed;

    public ModelCatalog()
    {
        Data = Load();
    }

    private static CatalogData Load()
    {
        try
        {
            if (File.Exists(FilePath)) return JsonSerializer.Deserialize<CatalogData>(File.ReadAllText(FilePath)) ?? new();
        }
        catch (Exception) { }
        return new CatalogData();
    }

    public void Save()
    {
        lock (_lock) AppPaths.WriteAtomic(FilePath, JsonSerializer.SerializeToUtf8Bytes(Data, Json));
        Changed?.Invoke();
    }

    public ModelPrefs Prefs(string key)
    {
        lock (_lock)
        {
            if (!Data.Models.TryGetValue(key, out var p)) Data.Models[key] = p = new ModelPrefs();
            return p;
        }
    }

    public string? TierOf(string key) => Data.Tiers.FirstOrDefault(kv => kv.Value == key).Key;

    public string? ModelForTier(string tier) => Data.Tiers.TryGetValue(tier, out var k) ? k : null;

    /// <summary>"tier:fast" → chave do modelo; outras chaves passam direto.</summary>
    public string Resolve(string modelOrTier)
    {
        if (modelOrTier.StartsWith("tier:", StringComparison.Ordinal))
            return ModelForTier(modelOrTier[5..]) ?? throw new LmStudioException("tier_empty", $"Nenhum modelo definido para o perfil {Tiers.Label(modelOrTier[5..])}");
        return modelOrTier;
    }

    public string DisplayName(LmModel m, IReadOnlyList<LmModel> all)
    {
        var alias = Data.Models.TryGetValue(m.Key, out var p) ? p.Alias : null;
        if (!string.IsNullOrWhiteSpace(alias)) return alias!;
        var name = ModelNames.Friendly(m.DisplayName, m.Params, m.Quantization);
        // dois arquivos do mesmo modelo (Q4 e Q8): diferencia pela quantização
        var twins = all.Where(o => o.Type == m.Type && ModelNames.Friendly(o.DisplayName, o.Params, o.Quantization) == name).ToList();
        if (twins.Count <= 1 || m.Quantization is not { } q) return name;
        // o gêmeo que está num perfil fica com o nome limpo; os outros ganham a quantização
        if (TierOf(m.Key) is not null) return name;
        return $"{name} · {q}";
    }

    // ------------------------------------------------------------------ perfis automáticos

    /// <summary>
    /// Distribui os modelos que cabem na GPU entre Rápido (menor), Profundo (maior) e Inteligente (o do meio,
    /// preferindo visão). Só mexe enquanto o usuário não tiver escolhido manualmente.
    /// </summary>
    public bool AutoAssign(IReadOnlyList<LmModel> models, double vramGiB)
    {
        if (models.Count == 0) return false;
        var llms = models.Where(m => m.Type == "llm" && !(Data.Models.TryGetValue(m.Key, out var p) && p.Hidden)).ToList();
        var changed = false;
        lock (_lock)
        {
            if (Data.TiersAuto && llms.Count > 0)
            {
                var budget = vramGiB > 0 ? vramGiB * 0.92 : double.MaxValue;
                var fit = llms.Where(m => m.SizeBytes / 1073741824.0 < budget).ToList();
                if (fit.Count == 0) fit = llms;
                // um arquivo por modelo: prefere a quantização maior que ainda cabe
                var byFamily = fit.GroupBy(m => ModelNames.Friendly(m.DisplayName, m.Params, null))
                    .Select(g => g.OrderByDescending(m => m.SizeBytes).First()).ToList();
                var ordered = byFamily.OrderBy(m => ModelNames.ParamsB(m.Params) ?? m.SizeBytes / 1e9).ToList();
                var fast = ordered.First();
                var deep = ordered.Last();
                var middle = ordered.Skip(1).SkipLast(1).ToList();
                var smart = middle.OrderByDescending(m => m.Vision).ThenByDescending(m => ModelNames.ParamsB(m.Params)).FirstOrDefault() ?? deep;
                changed |= SetTier(Tiers.Fast, fast.Key);
                changed |= SetTier(Tiers.Smart, smart.Key);
                changed |= SetTier(Tiers.Deep, deep.Key);
            }
            // perfis apontando para modelos que sumiram
            foreach (var t in Tiers.All)
                if (Data.Tiers.TryGetValue(t, out var k) && k is not null && llms.All(m => m.Key != k))
                {
                    Data.Tiers[t] = null;
                    changed = true;
                }
            if (Data.DefaultAuto || (Data.DefaultModel is not null && llms.All(m => m.Key != Data.DefaultModel)))
            {
                var def = ModelForTier(Tiers.Fast) ?? llms.FirstOrDefault()?.Key;
                if (def != Data.DefaultModel) { Data.DefaultModel = def; changed = true; }
            }
        }
        if (changed) Save();
        return changed;
    }

    private bool SetTier(string tier, string key)
    {
        if (Data.Tiers.TryGetValue(tier, out var cur) && cur == key) return false;
        Data.Tiers[tier] = key;
        return true;
    }

    public void AssignTier(string tier, string? key)
    {
        lock (_lock)
        {
            Data.Tiers[tier] = key;
            Data.TiersAuto = false;
        }
        Save();
    }

    public void SetDefault(string? key, bool preload)
    {
        lock (_lock)
        {
            Data.DefaultModel = key;
            Data.DefaultAuto = false;
            Data.Preload = preload;
        }
        Save();
    }

    // ------------------------------------------------------------------ estimativas de memória

    private static readonly SemaphoreSlim EstimateGate = new(1, 1);

    /// <summary>VRAM (GiB) que o LM Studio estima para o modelo com este contexto. Cacheado em disco.</summary>
    public async Task<double?> EstimateAsync(LmModel m, int context, CancellationToken ct)
    {
        var cacheKey = $"{m.Key}|{m.SizeBytes}|{context}";
        lock (_lock) if (Data.Estimates.TryGetValue(cacheKey, out var v)) return v;
        if (!File.Exists(LmStudioLocator.LmsPath)) return null;
        await EstimateGate.WaitAsync(ct);
        try
        {
            lock (_lock) if (Data.Estimates.TryGetValue(cacheKey, out var v)) return v;
            var psi = new ProcessStartInfo(LmStudioLocator.LmsPath)
            {
                CreateNoWindow = true, UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8,
            };
            foreach (var a in new[] { "load", m.Key, "--estimate-only", "-c", context.ToString(CultureInfo.InvariantCulture), "-y" }) psi.ArgumentList.Add(a);
            using var p = Process.Start(psi)!;
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(TimeSpan.FromSeconds(30));
            var outTask = p.StandardOutput.ReadToEndAsync(timeout.Token);
            var errTask = p.StandardError.ReadToEndAsync(timeout.Token);
            try { await p.WaitForExitAsync(timeout.Token); }
            catch (OperationCanceledException) { try { p.Kill(true); } catch { } throw; }
            var text = await outTask + await errTask;
            var match = Regex.Match(text, @"Estimated GPU Memory:\s*([\d.,]+)\s*GiB");
            if (!match.Success) return null;
            var raw = match.Groups[1].Value;
            if (!raw.Contains('.') && raw.Contains(',')) raw = raw.Replace(',', '.');
            var gib = double.Parse(raw, CultureInfo.InvariantCulture);
            lock (_lock) Data.Estimates[cacheKey] = gib;
            Save();
            return gib;
        }
        catch (Exception) when (!ct.IsCancellationRequested) { return null; }
        finally { EstimateGate.Release(); }
    }
}

/// <summary>Configuração de carga escolhida para um modelo.</summary>
public sealed record LoadPlan(int Context, int Parallel, bool? Mtp, double? EstimateGiB, double? BudgetGiB, bool Fits, string? Note);

/// <summary>
/// Escolhe o maior contexto (até o alvo do perfil) cuja estimativa cabe na VRAM livre, com margem de segurança.
/// A meta é manter tudo na GPU: um modelo que transborda para a RAM fica dezenas de vezes mais lento.
/// </summary>
public static class LoadPlanner
{
    public static readonly int[] Ladder = [131072, 98304, 65536, 49152, 32768, 24576, 16384, 12288, 8192, 4096];
    public const double SafetyGiB = 0.5;

    public static async Task<LoadPlan> PlanAsync(ModelCatalog catalog, LmModel model, string? tier, int? forcedContext,
        double totalGiB, double otherUsageGiB, double reserveGiB, CancellationToken ct)
    {
        var prefs = catalog.Prefs(model.Key);
        var parallel = prefs.Parallel ?? 2;
        var mtp = prefs.MtpWorks;
        var maxCtx = model.MaxContext > 0 ? model.MaxContext : 32768;
        var budget = totalGiB > 0 ? totalGiB - otherUsageGiB - reserveGiB - SafetyGiB : (double?)null;

        var want = forcedContext ?? prefs.Context;
        if (want is { } fixedCtx)
        {
            fixedCtx = Math.Clamp(fixedCtx, 2048, maxCtx);
            var est = await catalog.EstimateAsync(model, fixedCtx, ct);
            var fits = budget is null || est is null || est <= budget;
            return new LoadPlan(fixedCtx, parallel, mtp, est, budget, fits, fits ? null : "O contexto escolhido não cabe inteiro na GPU");
        }

        var target = Math.Min(Tiers.TargetContext(tier ?? catalog.TierOf(model.Key)), maxCtx);
        double? lastEst = null;
        foreach (var ctx in Ladder.Where(c => c <= target))
        {
            var est = await catalog.EstimateAsync(model, ctx, ct);
            lastEst = est;
            if (budget is null || est is null || est <= budget)
                return new LoadPlan(ctx, parallel, mtp, est, budget, true, ctx < target ? $"Contexto reduzido para caber na GPU" : null);
        }
        return new LoadPlan(4096, parallel, mtp, lastEst, budget, false, "Este modelo não cabe inteiro na memória da sua GPU");
    }
}
