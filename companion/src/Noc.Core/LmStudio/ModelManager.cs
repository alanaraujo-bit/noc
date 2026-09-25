using System.Text.Json.Nodes;
using Noc.Core.Library;
using Noc.Core.Platform;
using Noc.Core.Storage;

namespace Noc.Core.LmStudio;

public enum ModelOpState { Idle, Loading, Unloading }

/// <summary>
/// Serializa carregar/descarregar modelos e mantém a última leitura do LM Studio.
/// Toda carga passa pelo planejador: contexto que cabe na VRAM, MTP quando o modelo tem cabeça MTP,
/// e nova tentativa com contexto menor se faltar memória. Com SingleModel ligado, descarrega os outros antes.
/// </summary>
public sealed class ModelManager
{
    private readonly LmStudioClient _lm;
    private readonly CompanionSettings _settings;
    private readonly ModelCatalog _catalog;
    private readonly GpuMonitor _gpu;
    private readonly Func<double> _reserveGiB;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private LmProbe _last = new(LmState.Unknown, [], null);
    private Dictionary<string, string> _paths = new();
    private string _pathsSignature = "";
    private readonly Dictionary<string, GgufFile?> _headers = new();

    public ModelManager(LmStudioClient lm, CompanionSettings settings, ModelCatalog catalog, GpuMonitor gpu, Func<double> reserveGiB)
    {
        _lm = lm;
        _settings = settings;
        _catalog = catalog;
        _gpu = gpu;
        _reserveGiB = reserveGiB;
    }

    public LmProbe Last => _last;
    /// <summary>Quantas leituras seguidas o LM Studio não respondeu a tempo (ocupado ou travado).</summary>
    public int ConsecutiveBusy { get; private set; }
    public ModelCatalog Catalog => _catalog;
    public ModelOpState Op { get; private set; }
    public string? OpModel { get; private set; }
    public DateTimeOffset? OpStarted { get; private set; }
    /// <summary>Duração esperada da carga em andamento (a última medida deste modelo).</summary>
    public double? OpExpectedSeconds { get; private set; }
    /// <summary>O usuário descarregou de propósito: o pré-carregamento não deve recarregar sozinho.</summary>
    public bool UserUnloaded { get; private set; }

    /// <summary>Eventos de modelo para os celulares: {state, model, ...}.</summary>
    public event Action<JsonObject>? ModelEvent;
    public event Action? Changed;
    /// <summary>Mensagens técnicas para o log de diagnóstico.</summary>
    public event Action<string>? RawLog;

    public async Task<LmProbe> RefreshAsync(CancellationToken ct = default)
    {
        var probe = await _lm.ProbeAsync(ct);
        // Durante uma carga o LM Studio às vezes responde erro na listagem: mantém a última lista boa.
        if (probe.State == LmState.Running && probe.Models.Count == 0 && probe.Error is not null && _last.Models.Count > 0)
            probe = probe with { Models = _last.Models };
        ConsecutiveBusy = probe.Busy ? ConsecutiveBusy + 1 : 0;
        var changed = probe.State != _last.State || Signature(probe) != Signature(_last);
        _last = probe;
        if (changed) Changed?.Invoke();
        return probe;
    }

    private static string Signature(LmProbe p) =>
        string.Join('|', p.Models.Select(m => m.Key + ":" + m.Vision + ":" + string.Join(',', m.Instances.Select(i => i.Id + "/" + i.ContextLength))));

    public LmModel? Find(string key) => _last.Models.FirstOrDefault(m => m.Key == key);

    public string DisplayName(string key) => Find(key) is { } m ? _catalog.DisplayName(m, _last.Models) : key;

    // ------------------------------------------------------------------ arquivos

    /// <summary>Caminho absoluto do GGUF principal do modelo (para ler metadados), se conhecido.</summary>
    public async Task<string?> PathOfAsync(string key, CancellationToken ct)
    {
        var sig = string.Join('|', _last.Models.Select(m => m.Key));
        if (sig != _pathsSignature || !_paths.ContainsKey(key))
        {
            try
            {
                _paths = await LmStudioClient.ListPathsAsync(ct);
                _pathsSignature = sig;
            }
            catch (Exception) when (!ct.IsCancellationRequested) { }
        }
        return _paths.TryGetValue(key, out var rel) ? Path.Combine(ModelLibrary.LmModelsDir, rel.Replace('/', '\\')) : null;
    }

    public async Task<GgufFile?> HeaderOfAsync(string key, CancellationToken ct)
    {
        lock (_headers) if (_headers.TryGetValue(key, out var h)) return h;
        var path = await PathOfAsync(key, ct);
        GgufFile? header = null;
        if (path is not null && File.Exists(path))
        {
            try { header = GgufFile.Read(path); } catch (Exception) { }
        }
        lock (_headers) _headers[key] = header;
        return header;
    }

    // ------------------------------------------------------------------ memória

    /// <summary>Quanto da VRAM está ocupado por coisas que não são modelos do LM Studio (GiB).</summary>
    public async Task<(double Total, double Other)> VramPictureAsync(CancellationToken ct)
    {
        var g = await Task.Run(_gpu.SampleNow, ct) ?? _gpu.Last;
        if (g is null || g.VramTotalMb == 0) return (0, 0);
        double loadedEst = 0;
        foreach (var m in _last.Models.Where(m => m.Loaded))
            foreach (var inst in m.Instances)
                loadedEst += await _catalog.EstimateAsync(m, inst.ContextLength, ct) ?? m.SizeBytes / 1073741824.0;
        var used = g.VramUsedMb / 1024.0;
        return (g.VramTotalMb / 1024.0, Math.Max(0.3, used - loadedEst));
    }

    public async Task<LoadPlan> PlanAsync(LmModel model, string? tier, int? forcedContext, CancellationToken ct)
    {
        var (total, other) = await VramPictureAsync(ct);
        if (!_settings.SingleModel)
        {
            // os outros continuam carregados: contam como ocupados
            foreach (var m in _last.Models.Where(m => m.Loaded && m.Key != model.Key))
                foreach (var inst in m.Instances)
                    other += await _catalog.EstimateAsync(m, inst.ContextLength, ct) ?? m.SizeBytes / 1073741824.0;
        }
        var plan = await LoadPlanner.PlanAsync(_catalog, model, tier, forcedContext, total, other, _reserveGiB(), ct);
        if (plan.Mtp is null)
        {
            var header = await HeaderOfAsync(model.Key, ct);
            var hasMtp = header is not null && header.Architecture is { } a && (header.Int(a + ".nextn_predict_layers") ?? 0) > 0;
            plan = plan with { Mtp = hasMtp ? true : null };
        }
        return plan;
    }

    // ------------------------------------------------------------------ carregar / descarregar

    /// <summary>Garante que o modelo esteja carregado. Devolve o id da instância e o contexto efetivo.</summary>
    public async Task<(string InstanceId, int Context)> EnsureLoadedAsync(string key, int? contextLength, CancellationToken ct, string? tier = null)
    {
        var probe = await RefreshAsync(ct);
        if (probe.State != LmState.Running) throw new LmStudioException("lm_offline", "O LM Studio não está em execução");
        var model = probe.Models.FirstOrDefault(m => m.Key == key)
                    ?? throw new LmStudioException("model_not_found", "Este modelo não está mais no PC");
        if (model.Type != "llm") throw new LmStudioException("not_llm", "Este modelo não é de conversa");
        if (model.Loaded && (contextLength is null || model.Instances[0].ContextLength == contextLength))
            return (model.Instances[0].Id, model.Instances[0].ContextLength);

        await _gate.WaitAsync(ct);
        try
        {
            probe = await RefreshAsync(ct);
            model = probe.Models.FirstOrDefault(m => m.Key == key) ?? throw new LmStudioException("model_not_found", "Este modelo não está mais no PC");
            if (model.Loaded && (contextLength is null || model.Instances[0].ContextLength == contextLength))
                return (model.Instances[0].Id, model.Instances[0].ContextLength);

            var prefs = _catalog.Prefs(key);
            SetOp(ModelOpState.Loading, key, prefs.LastLoadSeconds);
            try
            {
                // Mesmo modelo com outro contexto, ou política de um modelo por vez: descarrega antes de medir a VRAM.
                foreach (var other in probe.Models.Where(m => m.Loaded && m.Type == "llm" && (_settings.SingleModel || m.Key == key)))
                    foreach (var inst in other.Instances)
                    {
                        await _lm.UnloadAsync(inst.Id, ct);
                        Emit("unloaded", other.Key);
                    }
                if (probe.Models.Any(m => m.Loaded && m.Type == "llm" && (_settings.SingleModel || m.Key == key)))
                {
                    // a VRAM demora um instante para ser liberada: espera ela baixar antes de medir
                    var before = _gpu.Last?.VramUsedMb ?? 0;
                    for (var i = 0; i < 16; i++)
                    {
                        await Task.Delay(250, ct);
                        var now = await Task.Run(_gpu.SampleNow, ct);
                        if (now is null || before - now.VramUsedMb > 1024) break;
                    }
                    await Task.Delay(300, ct);
                }
                await RefreshAsync(ct);

                var plan = await PlanAsync(model, tier, contextLength, ct);
                Emit("loading", key, new JsonObject
                {
                    ["context"] = plan.Context, ["expectedSeconds"] = prefs.LastLoadSeconds, ["estimateGiB"] = plan.EstimateGiB,
                    ["fits"] = plan.Fits, ["note"] = plan.Note,
                });
                var ctx = plan.Context;
                var mtp = plan.Mtp;
                for (var attempt = 0; ; attempt++)
                {
                    try
                    {
                        var (instance, seconds, effective) = await _lm.LoadAsync(key, ctx, ct, plan.Parallel, mtp);
                        prefs.LastLoadSeconds = Math.Round(seconds, 1);
                        prefs.LastError = null;
                        if (mtp == true) prefs.MtpWorks = true;
                        _catalog.Save();
                        UserUnloaded = false;
                        await RefreshAsync(ct);
                        Emit("loaded", key, new JsonObject { ["seconds"] = Math.Round(seconds, 1), ["context"] = effective });
                        return (instance, effective);
                    }
                    catch (LmStudioException e) when (attempt < 3 && mtp == true && e.Message.Contains("MTP", StringComparison.OrdinalIgnoreCase))
                    {
                        mtp = null; // o arquivo tem MTP mas o runtime não aceita: carrega sem
                        prefs.MtpWorks = false;
                    }
                    catch (LmStudioException e) when (attempt < 3 && LooksLikeOutOfMemory(e.Message) && LoadPlanner.Ladder.Any(c => c < ctx && c >= 4096))
                    {
                        ctx = LoadPlanner.Ladder.First(c => c < ctx); // tenta de novo com menos contexto
                        Emit("loading", key, new JsonObject { ["context"] = ctx, ["retry"] = true, ["note"] = "Tentando com menos contexto" });
                    }
                    catch (LmStudioException e) when (attempt == 0 && Diagnose(e.Message) == GenericLoadError)
                    {
                        // falha genérica (às vezes a VRAM do modelo anterior ainda não foi devolvida): uma nova tentativa
                        LastRawError = e.Message;
                        await Task.Delay(3000, ct);
                        Emit("loading", key, new JsonObject { ["context"] = ctx, ["retry"] = true, ["note"] = "Tentando de novo" });
                    }
                }
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                var friendly = Diagnose(e.Message);
                LastRawError = e.Message;
                prefs.LastError = friendly;
                prefs.LastErrorAt = DateTimeOffset.Now;
                _catalog.Save();
                Emit("failed", key, new JsonObject { ["error"] = friendly, ["detail"] = e.Message });
                RawLog?.Invoke($"Falha ao carregar {key}: {e.Message}");
                await RefreshAsync(CancellationToken.None);
                throw new LmStudioException("load_failed", friendly);
            }
            finally
            {
                SetOp(ModelOpState.Idle, null, null);
            }
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task UnloadAsync(string key, CancellationToken ct, bool byUser = true)
    {
        await _gate.WaitAsync(ct);
        try
        {
            var probe = await RefreshAsync(ct);
            var model = probe.Models.FirstOrDefault(m => m.Key == key);
            if (model is null || !model.Loaded) return;
            SetOp(ModelOpState.Unloading, key, null);
            foreach (var inst in model.Instances) await _lm.UnloadAsync(inst.Id, ct);
            if (byUser) UserUnloaded = true;
            await RefreshAsync(ct);
            Emit("unloaded", key);
        }
        finally
        {
            SetOp(ModelOpState.Idle, null, null);
            _gate.Release();
        }
    }

    private static bool LooksLikeOutOfMemory(string msg) =>
        msg.Contains("memory", StringComparison.OrdinalIgnoreCase) || msg.Contains("alloc", StringComparison.OrdinalIgnoreCase) ||
        msg.Contains("OOM", StringComparison.Ordinal) || msg.Contains("VRAM", StringComparison.OrdinalIgnoreCase) ||
        msg.Contains("resources", StringComparison.OrdinalIgnoreCase);

    /// <summary>Traduz o erro técnico do LM Studio para algo que o usuário entende.</summary>
    public static string Diagnose(string raw)
    {
        var m = raw.ToLowerInvariant();
        if (LooksLikeOutOfMemory(raw)) return "Memória de vídeo (VRAM) insuficiente para este modelo agora.";
        if (m.Contains("hyperparameters") || m.Contains("unknown model architecture") || m.Contains("wrong array length") || m.Contains("unknown architecture"))
            return "O arquivo deste modelo está num formato que o LM Studio instalado não reconhece.";
        if (m.Contains("tensor") && (m.Contains("bounds") || m.Contains("missing") || m.Contains("wrong number")))
            return "O arquivo do modelo parece incompleto ou corrompido.";
        if (m.Contains("eof") || m.Contains("corrupt") || m.Contains("truncated")) return "O arquivo do modelo parece incompleto ou corrompido.";
        if (m.Contains("mmproj") || m.Contains("clip")) return "O componente de visão deste modelo não carregou.";
        if (m.Contains("no such file") || m.Contains("not found") || m.Contains("does not exist")) return "O arquivo do modelo não foi encontrado.";
        if (m.Contains("lm studio não") || m.Contains("connection refused") || m.Contains("unreachable")) return "O LM Studio não está respondendo.";
        return GenericLoadError;
    }

    public const string GenericLoadError = "O LM Studio não conseguiu carregar este modelo.";

    /// <summary>Último erro técnico (para a área de diagnóstico).</summary>
    public string? LastRawError { get; private set; }

    private void SetOp(ModelOpState op, string? model, double? expected)
    {
        Op = op;
        OpModel = model;
        OpStarted = op == ModelOpState.Idle ? null : DateTimeOffset.Now;
        OpExpectedSeconds = expected;
        Changed?.Invoke();
    }

    private void Emit(string state, string model, JsonObject? extra = null)
    {
        var o = extra ?? new JsonObject();
        o["state"] = state;
        o["model"] = model;
        o["name"] = DisplayName(model);
        ModelEvent?.Invoke(o);
    }
}
