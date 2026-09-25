using System.Diagnostics;
using System.Globalization;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Noc.Core.Jobs;
using Noc.Core.LmStudio;
using Noc.Core.Platform;
using Noc.Core.Storage;

namespace Noc.Core.ImageEngine;

public enum EngineState { Off, Preparing, Starting, Loading, Ready, Working, Releasing, Failed }

/// <summary>Pedido de imagem (vem do celular pelo image.start; fica no diário da tarefa).</summary>
public sealed record ImageRequest
{
    public required string Prompt { get; init; }
    /// <summary>fast | quality | auto (como o usuário escolheu).</summary>
    public string Choice { get; init; } = "fast";
    /// <summary>Modelo resolvido no momento do pedido.</summary>
    public required string Model { get; init; }
    public int Width { get; init; } = 1024;
    public int Height { get; init; } = 1024;
    public string Format { get; init; } = "square";
    /// <summary>fast | standard | high</summary>
    public string Quality { get; init; } = "standard";
    public int Steps { get; init; }
    public double Guidance { get; init; }
    public int Count { get; init; } = 1;
    public long Seed { get; init; }
    public bool Enhance { get; init; }
    /// <summary>Por que o motor automático escolheu este modelo (para mostrar em Detalhes).</summary>
    public string? AutoNote { get; init; }

    public JsonObject ToJson() => new()
    {
        ["kind"] = "image", ["prompt"] = Prompt, ["choice"] = Choice, ["model"] = Model, ["width"] = Width, ["height"] = Height,
        ["format"] = Format, ["quality"] = Quality, ["steps"] = Steps, ["guidance"] = Guidance, ["count"] = Count, ["seed"] = Seed,
        ["enhance"] = Enhance, ["autoNote"] = AutoNote,
    };

    public static ImageRequest FromJson(JsonObject o) => new()
    {
        Prompt = o["prompt"]!.GetValue<string>(), Choice = o["choice"]?.GetValue<string>() ?? "fast", Model = o["model"]!.GetValue<string>(),
        Width = o["width"]?.GetValue<int>() ?? 1024, Height = o["height"]?.GetValue<int>() ?? 1024, Format = o["format"]?.GetValue<string>() ?? "square",
        Quality = o["quality"]?.GetValue<string>() ?? "standard", Steps = o["steps"]?.GetValue<int>() ?? 0, Guidance = o["guidance"]?.GetValue<double>() ?? 0,
        Count = o["count"]?.GetValue<int>() ?? 1, Seed = o["seed"]?.GetValue<long>() ?? 0, Enhance = o["enhance"]?.GetValue<bool>() ?? false,
        AutoNote = o["autoNote"]?.GetValue<string>(),
    };
}

/// <summary>
/// O motor de imagem do Noc. Uma instância só, dona do lado "imagem" da GPU:
/// - prepara o ambiente, liga o worker, carrega o modelo certo e gera, com progresso e prévias reais;
/// - antes de carregar, abre espaço na VRAM (descarrega o LLM ocioso e lembra qual era para devolver depois);
/// - falta de VRAM: libera tudo, passa para o modo econômico e tenta uma vez; nunca em laço;
/// - ocioso: mantém o modelo aquecido por um tempo e depois libera a GPU e devolve o LLM que estava carregado.
/// O LM Studio não participa: imagem e texto são pipelines diferentes, unificados aqui.
/// </summary>
public sealed class ImageEngineService : IAsyncDisposable
{
    private readonly CompanionSettings _settings;
    private readonly ModelManager _models;
    private readonly LmStudioClient _lm;
    private readonly GpuMonitor _gpu;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly Timer _idleTimer;
    private readonly Dictionary<string, string> _rewriteCache = new();
    private WorkerProcess? _worker;
    private Action<JsonObject>? _sink;
    private string? _sinkJob;
    private volatile bool _disposed;

    public ImageModelStore Store { get; }
    public ImageRuntime Runtime { get; } = new();
    public ImageOutputs Outputs { get; } = new();

    public EngineState State { get; private set; } = EngineState.Off;
    public ImageModelDef? Loaded { get; private set; }
    public string? LoadedTransformer { get; private set; }
    public string? LoadedTextEncoder { get; private set; }
    public string? Stage { get; private set; }
    public DateTimeOffset LastUse { get; private set; } = DateTimeOffset.MinValue;
    public string? LastError { get; private set; }
    /// <summary>LLM que estava carregado antes de o motor de imagem pegar a GPU (para devolver depois).</summary>
    public (string Key, string? Tier)? Displaced { get; private set; }
    /// <summary>Há tarefas de imagem na fila ou rodando (o JobManager informa).</summary>
    public Func<bool> HasPendingWork { get; set; } = () => false;
    /// <summary>Há alguma tarefa (de qualquer tipo) ativa.</summary>
    public Func<bool> AnyJobActive { get; set; } = () => false;

    public event Action? Changed;
    public event Action<string, string>? Activity;
    public event Action<string>? Trace;

    public ImageEngineService(CompanionSettings settings, ModelManager models, LmStudioClient lm, GpuMonitor gpu)
    {
        _settings = settings;
        _models = models;
        _lm = lm;
        _gpu = gpu;
        if (string.IsNullOrWhiteSpace(settings.ImageEngineRoot))
        {
            settings.ImageEngineRoot = ImagePaths.ChooseDefaultRoot();
            settings.Save();
        }
        ImagePaths.Root = settings.ImageEngineRoot;
        Store = new ImageModelStore();
        Store.Changed += () => Changed?.Invoke();
        Store.Activity += (t, k) => Activity?.Invoke(t, k);
        Store.Trace += t => Trace?.Invoke(t);
        Runtime.Changed += () => Changed?.Invoke();
        Runtime.Trace += t => Trace?.Invoke(t);
        var strays = WorkerProcess.KillStrays();
        if (strays > 0) Trace?.Invoke($"{strays} worker(s) de imagem órfão(s) encerrado(s)");
        _idleTimer = new Timer(_ => _ = IdleTickAsync(), null, TimeSpan.FromSeconds(15), TimeSpan.FromSeconds(10));
        try { Directory.CreateDirectory(ImagePaths.Tmp); foreach (var f in Directory.EnumerateFiles(ImagePaths.Tmp)) File.Delete(f); } catch (Exception) { }
    }

    public bool Enabled => _settings.ImageEnabled;
    public bool Busy => _gate.CurrentCount == 0;
    public bool WorkerAlive => _worker?.Alive == true;

    // ------------------------------------------------------------------ escolha do modelo

    /// <summary>Resolve "fast" | "quality" | "auto" para um modelo instalado (ou explica por que não dá).</summary>
    public (ImageModelDef? Model, string? Note, string? Error) Choose(string choice, string quality, string prompt, long pixels)
    {
        var z = ImageCatalog.ZImageTurbo;
        var f = ImageCatalog.FluxDev;
        bool Ok(ImageModelDef m) => Store.IsInstalled(m);
        switch (choice)
        {
            case "fast":
                return Ok(z) ? (z, null, null) : (null, null, "model_not_installed:" + z.Id);
            case "quality":
                if (Ok(f)) return pixels > f.Defaults.MaxPixels ? (null, null, "too_large") : (f, null, null);
                return (null, null, (f.Gated ? "flux_unavailable:" : "model_not_installed:") + f.Id);
            default:
                // automático: o Z-Image é o padrão (mais rápido, ótimo em fotorrealismo e em texto dentro da imagem);
                // o FLUX entra quando a qualidade alta foi pedida, cabe na resolução e não há texto a desenhar
                var wantsText = prompt.Contains('"') || prompt.Contains('“') || Regex.IsMatch(prompt, @"\b(escrit[oa]|texto|letreiro|placa|logo|t[íi]tulo)\b", RegexOptions.IgnoreCase);
                if (quality == "high" && Ok(f) && pixels <= f.Defaults.MaxPixels && !wantsText)
                    return (f, "Qualidade alta pedida: FLUX.1 Dev", null);
                if (Ok(z)) return (z, wantsText ? "Texto na imagem: Z-Image Turbo desenha letras melhor" : "Z-Image Turbo: rápido e com ótima qualidade", null);
                if (Ok(f) && pixels <= f.Defaults.MaxPixels) return (f, "Único motor instalado: FLUX.1 Dev", null);
                return (null, null, "model_not_installed:" + z.Id);
        }
    }

    // ------------------------------------------------------------------ execução de uma tarefa

    private void Publish(JsonObject e)
    {
        try { _sink?.Invoke(e); } catch (Exception) { }
    }

    private void OnWorkerEvent(JsonObject e)
    {
        var ev = e["ev"]?.GetValue<string>();
        switch (ev)
        {
            case "load_progress":
                Stage = e["stage"]?.GetValue<string>();
                Publish(new JsonObject { ["kind"] = "load", ["stage"] = Stage, ["progress"] = e["progress"]?.DeepClone() });
                Changed?.Invoke();
                break;
            case "progress" when e["id"]?.GetValue<string>() == _sinkJob:
            case "stage" when e["id"]?.GetValue<string>() == _sinkJob:
            case "image" when e["id"]?.GetValue<string>() == _sinkJob:
                Publish(e);
                break;
        }
    }

    /// <summary>
    /// Roda uma tarefa de imagem do começo ao fim. <paramref name="publish"/> recebe os eventos para o celular
    /// (fase, passo, prévia, imagem pronta). Devolve o "end" da tarefa. Erros previsíveis viram JobFailure com texto útil.
    /// </summary>
    public async Task<JsonObject> RunAsync(ChatJob job, ImageRequest req, Action<string, JsonObject?> phase, Action<JsonObject> publish, CancellationToken ct)
    {
        var def = ImageCatalog.Find(req.Model) ?? throw new JobFailure("model_unknown", "Este modelo de imagem não existe neste Companion.");
        if (!Store.IsInstalled(def))
            throw new JobFailure("model_not_installed", $"O {def.Name} ainda não está instalado no PC. Instale em Modelos de imagem.");
        var wall = Stopwatch.StartNew();
        await _gate.WaitAsync(ct);
        _sink = publish;
        _sinkJob = job.Id;
        var lowVram = false;
        long loadMs = 0;
        try
        {
            // 1. ambiente (uma vez por PC)
            if (!Runtime.QuickCheck())
            {
                State = EngineState.Preparing;
                Changed?.Invoke();
                void OnRt() => phase("preparing_engine", new JsonObject { ["step"] = Runtime.Step, ["index"] = Runtime.StepIndex, ["steps"] = ImageRuntime.StepCount });
                Runtime.Changed += OnRt;
                try { await Runtime.EnsureAsync(ct); }
                catch (RuntimeSetupException e) { throw new JobFailure(e.Code, RuntimeMessage(e.Code)); }
                finally { Runtime.Changed -= OnRt; }
            }

            // 1b. cópias de carga rápida (uma vez por modelo, num processo à parte)
            if (!IsOptimized(def, false)) await OptimizeAsync(def, false, phase, ct);

            // 2. prompt: tradução (os modelos entendem melhor inglês) e melhoria opcional
            var prompt = req.Prompt.Trim();
            string? rewriteMode = req.Enhance ? "enhance" : !def.Defaults.NativePortuguese && !LooksEnglish(prompt) ? "translate" : null;
            string finalPrompt = prompt;
            string? rewriteNote = null;

            for (var attempt = 0; ; attempt++)
            {
                try
                {
                    // 3. VRAM: abre espaço (ou escolhe o modo econômico) e liga o worker
                    var mode = Mode(def, lowVram);
                    if (!(WorkerAlive && Loaded == def && LoadedTransformer == mode.Transformer && LoadedTextEncoder == mode.TextEncoder))
                    {
                        lowVram = await MakeRoomAsync(def, lowVram, phase, ct);
                        mode = Mode(def, lowVram);
                    }
                    if (lowVram && !IsOptimized(def, true))
                    {
                        if (WorkerAlive) await ShutdownWorkerAsync("optimize");
                        await OptimizeAsync(def, true, phase, ct);
                    }
                    await EnsureWorkerAsync(phase, ct);

                    // FLUX: reescreve antes de carregar (o Qwen do Z-Image sobe sozinho e sai; pico de VRAM menor)
                    if (rewriteMode is not null && finalPrompt == prompt && def.Engine != "zimage")
                        (finalPrompt, rewriteNote) = await RewriteAsync(prompt, rewriteMode, phase, ct);

                    // 4. carrega o modelo
                    var loadSw = Stopwatch.StartNew();
                    await LoadAsync(def, mode, phase, ct);
                    loadMs += loadSw.ElapsedMilliseconds;

                    // Z-Image: reescreve com o Qwen que já está carregado como codificador de texto
                    if (rewriteMode is not null && finalPrompt == prompt && def.Engine == "zimage")
                        (finalPrompt, rewriteNote) = await RewriteAsync(prompt, rewriteMode, phase, ct);

                    // 5. gera
                    return await GenerateAsync(job, req, def, prompt, finalPrompt, rewriteMode, rewriteNote, loadMs, wall, phase, publish, ct);
                }
                catch (WorkerException e) when (e.Code == "oom" && attempt == 0 && !ct.IsCancellationRequested)
                {
                    // Falta de VRAM: libera tudo que for nosso, descarrega LLMs e tenta uma vez no modo econômico.
                    Trace?.Invoke($"tarefa de imagem {Short(job.Id)}: falta de VRAM ({def.Id}, {(lowVram ? "econômico" : "normal")}); recuperando");
                    phase("recovering", new JsonObject { ["why"] = "vram" });
                    await ShutdownWorkerAsync("oom");
                    lowVram = true;
                }
                catch (WorkerException e) when (e.Code == "worker_crash" && attempt == 0 && !ct.IsCancellationRequested)
                {
                    Trace?.Invoke($"tarefa de imagem {Short(job.Id)}: o worker caiu ({e.Message}); reiniciando uma vez");
                    phase("recovering", new JsonObject { ["why"] = "worker" });
                    await ShutdownWorkerAsync("crash");
                }
            }
        }
        catch (WorkerException e)
        {
            LastError = e.Code;
            throw new JobFailure(e.Code, WorkerMessage(e.Code, def));
        }
        finally
        {
            _sink = null;
            _sinkJob = null;
            LastUse = DateTimeOffset.Now;
            if (State is EngineState.Working or EngineState.Loading or EngineState.Starting or EngineState.Preparing)
                State = WorkerAlive ? (Loaded is null ? EngineState.Off : EngineState.Ready) : EngineState.Off;
            Stage = null;
            _gate.Release();
            Changed?.Invoke();
        }
    }

    private static string Short(string id) => id.Length > 8 ? id[..8] : id;

    private sealed record EngineMode(string Transformer, string TextEncoder);

    private static EngineMode Mode(ImageModelDef def, bool lowVram) =>
        lowVram ? new EngineMode("fp8", def.Engine == "flux" ? "swap" : "fp8") : new EngineMode(def.Defaults.Transformer, def.Defaults.TextEncoder);

    /// <summary>VRAM (GiB) que o modelo precisa no modo escolhido: residente + pico de uma geração + margem.</summary>
    private static double NeedGiB(ImageModelDef def, bool lowVram)
    {
        var d = def.Defaults;
        var resident = lowVram && d.Transformer == "bf16" ? d.ResidentGiB - d.TransformerGiB / 2 : d.ResidentGiB;
        return resident + d.PeakExtraGiB + 0.8;
    }

    /// <summary>
    /// Abre espaço na GPU para o modelo: descarrega outro modelo de imagem e, se ainda faltar, o LLM (lembrando qual era).
    /// Se outros programas ocupam a VRAM, passa para o modo econômico; se nem assim couber, explica.
    /// Devolve se o modo econômico foi necessário.
    /// </summary>
    private async Task<bool> MakeRoomAsync(ImageModelDef def, bool lowVram, Action<string, JsonObject?> phase, CancellationToken ct)
    {
        // outro modelo de imagem carregado: sai primeiro (o worker fica, só descarrega)
        if (WorkerAlive && Loaded is not null && Loaded != def)
        {
            try { await _worker!.RequestAsync(new JsonObject { ["cmd"] = "unload" }, ct, TimeSpan.FromSeconds(60)); }
            catch (WorkerException) { await ShutdownWorkerAsync("switch"); }
            Loaded = null;
        }
        var (total, used) = await VramAsync(ct);
        if (total <= 0) return lowVram; // sem leitura da GPU: segue e deixa a recuperação de OOM agir
        var reserve = Math.Max(0, _settings.ImageVramReserveGiB);
        var free = total - used - reserve;
        var need = NeedGiB(def, lowVram);
        var loadedLlms = _models.Last.Models.Where(m => m.Loaded && m.Type == "llm").ToList();
        if (free < need && loadedLlms.Count > 0)
        {
            var first = loadedLlms[0];
            Displaced ??= (first.Key, _models.Catalog.TierOf(first.Key));
            foreach (var m in loadedLlms)
            {
                phase("freeing", new JsonObject { ["name"] = _models.DisplayName(m.Key) });
                Trace?.Invoke($"liberando a VRAM do LLM {m.Key} para gerar imagem ({free:0.0} GiB livres, precisa de {need:0.0})");
                await _models.UnloadAsync(m.Key, ct, byUser: false);
            }
            await WaitVramSettleAsync(ct);
            (total, used) = await VramAsync(ct);
            free = total - used - reserve;
        }
        if (free >= need) return lowVram;
        if (!lowVram && free >= NeedGiB(def, true))
        {
            Trace?.Invoke($"VRAM apertada ({free:0.0} GiB livres): {def.Id} no modo econômico");
            return true;
        }
        throw new JobFailure("vram_insufficient",
            $"Não há memória de vídeo livre suficiente agora ({free:0.0} GB livres; o {def.Name} precisa de {NeedGiB(def, true):0.0} GB). " +
            "Outro programa está usando a GPU. Feche jogos ou apps pesados e tente de novo.");
    }

    private async Task<(double Total, double Used)> VramAsync(CancellationToken ct)
    {
        var g = await Task.Run(_gpu.SampleNow, ct) ?? _gpu.Last;
        return g is null || g.VramTotalMb == 0 ? (0, 0) : (g.VramTotalMb / 1024.0, g.VramUsedMb / 1024.0);
    }

    /// <summary>Espera a VRAM parar de cair (o driver devolve a memória de um processo alguns instantes depois).</summary>
    private async Task WaitVramSettleAsync(CancellationToken ct)
    {
        var prev = (await Task.Run(_gpu.SampleNow, ct))?.VramUsedMb ?? 0;
        for (var i = 0; i < 20; i++)
        {
            await Task.Delay(300, ct);
            var now = (await Task.Run(_gpu.SampleNow, ct))?.VramUsedMb ?? 0;
            if (Math.Abs(prev - now) < 64 && i >= 2) break;
            prev = now;
        }
    }

    private async Task EnsureWorkerAsync(Action<string, JsonObject?> phase, CancellationToken ct)
    {
        if (WorkerAlive) return;
        State = EngineState.Starting;
        Loaded = null;
        Changed?.Invoke();
        phase("loading", new JsonObject { ["stage"] = "starting" });
        var sw = Stopwatch.StartNew();
        try { _worker = await WorkerProcess.StartAsync(ImageRuntime.PythonExe, ImageRuntime.WorkerScript, ct); }
        catch (WorkerException) { throw; }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            throw new WorkerException("worker_start", e.Message);
        }
        _worker.Event += OnWorkerEvent;
        var w = _worker;
        _worker.Exited += code =>
        {
            if (!ReferenceEquals(_worker, w)) return;
            Trace?.Invoke($"worker de imagem saiu (código {code})");
            Loaded = null;
            if (State != EngineState.Releasing) State = EngineState.Off;
            Changed?.Invoke();
        };
        var info = _worker.ReadyInfo;
        if (info?["cudaAvailable"]?.GetValue<bool>() != true)
        {
            await ShutdownWorkerAsync("no_cuda");
            throw new WorkerException("cuda_unavailable", "CUDA indisponível");
        }
        Trace?.Invoke($"worker de imagem pronto em {sw.ElapsedMilliseconds} ms (pid {_worker.Pid}, torch {info["torch"]}, CUDA {info["cuda"]})");
    }

    private async Task LoadAsync(ImageModelDef def, EngineMode mode, Action<string, JsonObject?> phase, CancellationToken ct)
    {
        if (Loaded == def && LoadedTransformer == mode.Transformer && LoadedTextEncoder == mode.TextEncoder) return;
        State = EngineState.Loading;
        Changed?.Invoke();
        var dir = Store.InstalledDir(def)!;
        var rev = Store.InstalledRevision(def)!;
        phase("loading", new JsonObject { ["name"] = def.Name, ["expectedSeconds"] = Store.LoadSeconds(def), ["stage"] = "model" });
        var sw = Stopwatch.StartNew();
        JsonObject r;
        try
        {
            r = await _worker!.RequestAsync(new JsonObject
            {
                ["cmd"] = "load", ["engine"] = def.Engine, ["path"] = dir, ["cache"] = ImagePaths.CacheDir(def, rev),
                ["transformer"] = mode.Transformer, ["text_encoder"] = mode.TextEncoder,
            }, ct, TimeSpan.FromMinutes(30));
        }
        catch (OperationCanceledException)
        {
            // carregar não tem como ser interrompido por dentro: encerra o worker para soltar a GPU na hora
            await ShutdownWorkerAsync("cancel_load");
            throw;
        }
        if (r["ev"]?.GetValue<string>() == "error") throw WorkerError(r);
        Loaded = def;
        LoadedTransformer = mode.Transformer;
        LoadedTextEncoder = mode.TextEncoder;
        State = EngineState.Ready;
        var secs = sw.Elapsed.TotalSeconds;
        Store.SetLoadSeconds(def, Math.Round(secs, 1));
        Store.MarkUsed(def);
        Activity?.Invoke($"{def.Name} pronto para gerar (carregado em {secs:0.0} s)", "model");
        Trace?.Invoke($"{def.Id} carregado em {secs:0.0} s (transformer {mode.Transformer}, texto {mode.TextEncoder}, VRAM alocada {r["mem"]?["allocatedMb"]} MB, livre {r["mem"]?["freeMb"]} MB)");
        Metrics(new JsonObject
        {
            ["kind"] = "load", ["model"] = def.Id, ["seconds"] = Math.Round(secs, 2), ["transformer"] = mode.Transformer, ["textEncoder"] = mode.TextEncoder,
            ["allocatedMb"] = r["mem"]?["allocatedMb"]?.DeepClone(), ["freeMb"] = r["mem"]?["freeMb"]?.DeepClone(), ["peakRssMb"] = r["peakRssMb"]?.DeepClone(),
        });
        Changed?.Invoke();
    }

    /// <summary>As cópias de carga rápida deste modelo (no modo normal ou econômico) já existem?</summary>
    public bool IsOptimized(ImageModelDef def, bool lowVram)
    {
        var rev = Store.InstalledRevision(def);
        if (rev is null) return false;
        var c = ImagePaths.CacheDir(def, rev);
        var t = lowVram ? "fp8" : def.Defaults.Transformer;
        var ok = File.Exists(Path.Combine(c, $"transformer-{t}", "noc-cache.json"));
        if (def.Engine == "zimage") ok &= File.Exists(Path.Combine(c, "text_encoder-fp8", "noc-cache.json"));
        return ok;
    }

    /// <summary>Modelo sendo otimizado agora (para a tela) e o progresso (0..1).</summary>
    public string? Optimizing { get; private set; }
    public double? OptimizeProgress { get; private set; }

    /// <summary>
    /// Cria as cópias de carga rápida (transformer em bf16/fp8, codificador de texto em fp8) a partir dos pesos oficiais.
    /// Roda num processo próprio, com prioridade baixa, e sai no fim: a conversão incha a memória do processo
    /// e a primeira geração num processo assim fica lenta (medido).
    /// </summary>
    private async Task OptimizeAsync(ImageModelDef def, bool lowVram, Action<string, JsonObject?> phase, CancellationToken ct)
    {
        var dir = Store.InstalledDir(def)!;
        var rev = Store.InstalledRevision(def)!;
        Optimizing = def.Id;
        OptimizeProgress = 0;
        Changed?.Invoke();
        phase("optimizing", new JsonObject { ["name"] = def.Name, ["progress"] = 0 });
        Activity?.Invoke($"Otimizando o {def.Name} para a sua GPU (só na primeira vez)", "model");
        var sw = Stopwatch.StartNew();
        using var w = await WorkerProcess.StartAsync(ImageRuntime.PythonExe, ImageRuntime.WorkerScript, ct);
        w.LowerPriority();
        w.Event += e =>
        {
            if (e["ev"]?.GetValue<string>() != "optimize_progress") return;
            // o transformer é quase todo o trabalho; o codificador de texto fecha os últimos 15%
            var part = e["part"]?.GetValue<string>();
            var pr = e["progress"]?.GetValue<double>() ?? 0;
            OptimizeProgress = part == "text_encoder" ? 0.85 + pr * 0.15 : pr * (def.Engine == "zimage" ? 0.85 : 1);
            phase("optimizing", new JsonObject { ["name"] = def.Name, ["progress"] = Math.Round(OptimizeProgress.Value, 3) });
            Changed?.Invoke();
        };
        try
        {
            var r = await w.RequestAsync(new JsonObject
            {
                ["cmd"] = "optimize", ["engine"] = def.Engine, ["path"] = dir, ["cache"] = ImagePaths.CacheDir(def, rev),
                ["transformer"] = lowVram ? "fp8" : def.Defaults.Transformer,
            }, ct, TimeSpan.FromMinutes(60));
            if (r["ev"]?.GetValue<string>() == "error") throw WorkerError(r);
            Trace?.Invoke($"{def.Id} otimizado ({(lowVram ? "econômico" : "normal")}) em {sw.Elapsed.TotalSeconds:0} s");
        }
        finally
        {
            await w.ShutdownAsync(TimeSpan.FromSeconds(10));
            Optimizing = null;
            OptimizeProgress = null;
            Changed?.Invoke();
        }
    }

    /// <summary>Depois de instalar: prepara o ambiente e otimiza já, para a primeira imagem sair rápido.</summary>
    public void AfterInstall(ImageModelDef def) => _ = Task.Run(async () =>
    {
        try
        {
            await Runtime.EnsureAsync(CancellationToken.None);
            if (IsOptimized(def, false)) return;
            await _gate.WaitAsync();
            try
            {
                if (!IsOptimized(def, false)) await OptimizeAsync(def, false, (_, _) => { }, CancellationToken.None);
            }
            finally { _gate.Release(); }
        }
        catch (Exception e) { Trace?.Invoke($"preparação de {def.Id} depois da instalação falhou: {e.GetType().Name} {e.Message}"); }
    });

    /// <summary>
    /// Primeira vez (e a cada partida): continua instalações interrompidas e instala sozinho o Z-Image Turbo,
    /// o motor padrão. O FLUX.1 Dev só entra quando há conta do Hugging Face com os termos aceitos.
    /// </summary>
    public void AutoSetup()
    {
        if (!Enabled) return;
        Store.ResumePending();
        foreach (var m in ImageCatalog.All)
        {
            if (Store.IsInstalled(m)) { if (!IsOptimized(m, false)) AfterInstall(m); continue; }
            if (m.Gated && HfToken.Load() is null) continue;
            if (Store.Busy(m) || !Store.MarkAutoInstall(m)) continue;
            Store.StartInstall(m);
        }
        _ = Task.Run(async () =>
        {
            try { await Runtime.EnsureAsync(CancellationToken.None); } catch (Exception) { }
            // aviso de atualização (no máximo um por dia; nunca baixa sozinho)
            foreach (var m in ImageCatalog.All.Where(Store.IsInstalled))
                if (Store.CheckedAt(m) is not { } at || DateTimeOffset.Now - at > TimeSpan.FromDays(1))
                    await Store.CheckUpdateAsync(m, CancellationToken.None);
        });
    }

    private static WorkerException WorkerError(JsonObject r) =>
        new(r["code"]?.GetValue<string>() ?? "pipeline_error", r["message"]?.GetValue<string>() ?? "falha");

    private async Task<(string Prompt, string? Note)> RewriteAsync(string prompt, string mode, Action<string, JsonObject?> phase, CancellationToken ct)
    {
        var key = mode + "\u0001" + prompt;
        lock (_rewriteCache) if (_rewriteCache.TryGetValue(key, out var hit)) return (hit, null);
        phase("prompt", new JsonObject { ["mode"] = mode });
        var z = ImageCatalog.ZImageTurbo;
        var zdir = Store.InstalledDir(z);
        string? result = null;
        string? note = null;
        var sw = Stopwatch.StartNew();
        if (zdir is not null && WorkerAlive)
        {
            try
            {
                var r = await _worker!.RequestAsync(new JsonObject
                {
                    ["cmd"] = "rewrite", ["text"] = prompt, ["mode"] = mode,
                    ["path"] = Path.Combine(zdir, "text_encoder"), ["tokenizer"] = Path.Combine(zdir, "tokenizer"),
                }, ct, TimeSpan.FromMinutes(3));
                if (r["ev"]?.GetValue<string>() == "rewritten") result = r["text"]?.GetValue<string>();
                else Trace?.Invoke($"reescrita do prompt falhou no worker: {r["code"]}");
            }
            catch (WorkerException e) when (e.Code != "worker_crash") { Trace?.Invoke($"reescrita do prompt falhou: {e.Code}"); }
        }
        if (string.IsNullOrWhiteSpace(result)) result = await RewriteWithLmStudioAsync(prompt, mode, ct);
        if (string.IsNullOrWhiteSpace(result) || result.Length > 2000)
        {
            note = "O prompt foi usado como você escreveu (a tradução não estava disponível).";
            return (prompt, note);
        }
        Trace?.Invoke($"prompt {(mode == "enhance" ? "melhorado" : "traduzido")} em {sw.ElapsedMilliseconds} ms ({prompt.Length} → {result.Length} caracteres)");
        lock (_rewriteCache)
        {
            if (_rewriteCache.Count > 200) _rewriteCache.Clear();
            _rewriteCache[key] = result;
        }
        return (result, note);
    }

    /// <summary>Plano B da tradução: o LLM que já estiver carregado no LM Studio (sem carregar nada novo).</summary>
    private async Task<string?> RewriteWithLmStudioAsync(string prompt, string mode, CancellationToken ct)
    {
        var loaded = _models.Last.Models.FirstOrDefault(m => m.Loaded && m.Type == "llm");
        if (loaded is null) return null;
        try
        {
            var sys = mode == "enhance"
                ? "Rewrite the user's image request as a detailed English prompt for an image generator (composition, lighting, camera, materials). Keep every requested element; add no people or personal traits that were not requested; keep quoted text as written. Output only the prompt."
                : "Translate the user's image request into an English prompt for an image generator. Keep every detail; remove phrases like 'create an image of'; keep quoted text as written. Output only the prompt.";
            var req = new JsonObject
            {
                ["model"] = loaded.Instances[0].Id,
                ["messages"] = new JsonArray(new JsonObject { ["role"] = "system", ["content"] = sys }, new JsonObject { ["role"] = "user", ["content"] = prompt }),
                ["max_tokens"] = 300, ["temperature"] = 0.2, ["reasoning_effort"] = "none",
            };
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(TimeSpan.FromSeconds(40));
            var sb = new StringBuilder();
            await foreach (var d in _lm.StreamChatAsync(req, cts.Token)) if (d.Content is not null) sb.Append(d.Content);
            return sb.ToString().Trim().Trim('"');
        }
        catch (Exception e) when (e is not OperationCanceledException || !ct.IsCancellationRequested)
        {
            Trace?.Invoke($"tradução pelo LM Studio falhou: {e.GetType().Name}");
            return null;
        }
    }

    private async Task<JsonObject> GenerateAsync(ChatJob job, ImageRequest req, ImageModelDef def, string original, string finalPrompt,
        string? rewriteMode, string? rewriteNote, long loadMs, Stopwatch wall, Action<string, JsonObject?> phase, Action<JsonObject> publish, CancellationToken ct)
    {
        State = EngineState.Working;
        Changed?.Invoke();
        var outBase = Outputs.NewBase(job.Id);
        // retomada depois de um reinício: as imagens já salvas não são geradas de novo
        var first = 0;
        while (first < req.Count && Outputs.BaseOf($"{job.Id}-{first}") is not null) first++;
        var images = new JsonArray();
        for (var i = 0; i < first; i++) images.Add(DescribeImage($"{job.Id}-{i}", Outputs.BaseOf($"{job.Id}-{i}")!, req.Seed + i));
        phase("generating", new JsonObject { ["name"] = def.Name, ["count"] = req.Count, ["steps"] = req.Steps, ["image"] = first });
        JsonObject r;
        if (first < req.Count)
        {
            var cmd = new JsonObject
            {
                ["cmd"] = "generate", ["id"] = job.Id, ["prompt"] = finalPrompt, ["width"] = req.Width, ["height"] = req.Height,
                ["steps"] = req.Steps, ["guidance"] = req.Guidance, ["seed"] = req.Seed, ["count"] = req.Count, ["first"] = first,
                ["out"] = outBase, ["preview"] = true, ["previewEvery"] = def.Engine == "flux" ? 3 : 2,
            };
            // cada imagem pronta é registrada e anunciada na hora (o celular começa a baixar enquanto as outras são geradas)
            void OnImage(JsonObject e)
            {
                if (e["ev"]?.GetValue<string>() != "image" || e["id"]?.GetValue<string>() != job.Id) return;
                var idx = e["index"]!.GetValue<int>();
                var id = $"{job.Id}-{idx}";
                var b = $"{outBase}-{idx}";
                Outputs.Register(id, b);
                Outputs.SaveMeta(b, Meta(job, req, def, original, finalPrompt, rewriteMode, id, idx, e));
                publish(new JsonObject { ["imageReady"] = DescribeImage(id, b, e["seed"]!.GetValue<long>()) });
            }
            _worker!.Event += OnImage;
            try
            {
                using var reg = ct.Register(() =>
                {
                    try { _worker?.Send(new JsonObject { ["cmd"] = "cancel", ["id"] = job.Id }); } catch (Exception) { }
                });
                var t = _worker.RequestAsync(cmd, CancellationToken.None, TimeSpan.FromMinutes(30));
                // cancelado e o worker não parou em 10 s: encerra à força (a GPU tem de ser solta)
                while (!t.IsCompleted)
                {
                    await Task.WhenAny(t, Task.Delay(500, CancellationToken.None));
                    if (ct.IsCancellationRequested && !t.IsCompleted)
                    {
                        if (await Task.WhenAny(t, Task.Delay(10_000, CancellationToken.None)) != t)
                        {
                            Trace?.Invoke($"tarefa de imagem {Short(job.Id)}: o worker não parou a tempo; encerrando o processo");
                            await ShutdownWorkerAsync("cancel_timeout");
                        }
                        break;
                    }
                }
                r = t.IsCompletedSuccessfully ? t.Result : new JsonObject { ["ev"] = "cancelled" };
            }
            finally { if (_worker is { } wk) wk.Event -= OnImage; }
            var ev = r["ev"]?.GetValue<string>();
            if (ev == "cancelled" || ct.IsCancellationRequested) throw new OperationCanceledException(ct);
            if (ev == "error") throw WorkerError(r);
            foreach (var img in (r["images"] as JsonArray ?? []).OfType<JsonObject>())
            {
                var idx = img["index"]!.GetValue<int>();
                var id = $"{job.Id}-{idx}";
                images.Add(DescribeImage(id, Outputs.BaseOf(id) ?? $"{outBase}-{idx}", img["seed"]!.GetValue<long>()));
            }
        }
        else r = new JsonObject { ["stats"] = new JsonObject() };
        var stats = r["stats"] as JsonObject ?? new JsonObject();
        var end = new JsonObject
        {
            ["reason"] = "stop", ["model"] = def.Id, ["name"] = def.Name, ["images"] = images,
            ["prompt"] = new JsonObject { ["original"] = original, ["final"] = finalPrompt, ["mode"] = rewriteMode, ["note"] = rewriteNote ?? req.AutoNote },
            ["stats"] = new JsonObject
            {
                ["wallMs"] = wall.ElapsedMilliseconds, ["loadMs"] = loadMs, ["generateMs"] = stats["totalMs"]?.DeepClone(),
                ["meanStepMs"] = stats["meanStepMs"]?.DeepClone(), ["peakVramMb"] = stats["peakReservedMb"]?.DeepClone(),
                ["width"] = req.Width, ["height"] = req.Height, ["steps"] = req.Steps, ["guidance"] = req.Guidance, ["seed"] = req.Seed,
                ["count"] = req.Count, ["transformer"] = LoadedTransformer, ["textEncoder"] = LoadedTextEncoder,
                ["queuedMs"] = (long)((job.StartedAt ?? DateTimeOffset.Now) - job.CreatedAt).TotalMilliseconds,
            },
        };
        Metrics(new JsonObject
        {
            ["kind"] = "generate", ["model"] = def.Id, ["width"] = req.Width, ["height"] = req.Height, ["steps"] = req.Steps, ["count"] = req.Count,
            ["wallMs"] = wall.ElapsedMilliseconds, ["loadMs"] = loadMs, ["generateMs"] = stats["totalMs"]?.DeepClone(), ["encodeMs"] = stats["encodeMs"]?.DeepClone(),
            ["meanStepMs"] = stats["meanStepMs"]?.DeepClone(), ["peakAllocatedMb"] = stats["peakAllocatedMb"]?.DeepClone(),
            ["peakReservedMb"] = stats["peakReservedMb"]?.DeepClone(), ["freeMbAfter"] = r["mem"]?["freeMb"]?.DeepClone(), ["rssMb"] = r["rssMb"]?.DeepClone(),
            ["transformer"] = LoadedTransformer, ["textEncoder"] = LoadedTextEncoder, ["rewrite"] = rewriteMode,
        });
        Activity?.Invoke($"{(req.Count > 1 ? $"{req.Count} imagens geradas" : "Imagem gerada")} com {def.Name} em {wall.Elapsed.TotalSeconds:0.0} s", "task");
        return end;
    }

    private JsonObject DescribeImage(string id, string basePath, long seed)
    {
        var png = new FileInfo(basePath + ".png");
        var thumb = new FileInfo(basePath + ".thumb.jpg");
        int w = 0, h = 0;
        try { (w, h) = PngSize(png.FullName); } catch (Exception) { }
        return new JsonObject
        {
            ["id"] = id, ["width"] = w, ["height"] = h, ["seed"] = seed, ["bytes"] = png.Exists ? png.Length : 0,
            ["thumbBytes"] = thumb.Exists ? thumb.Length : 0, ["sha256"] = png.Exists ? ImageOutputs.Sha256Of(png.FullName) : null,
        };
    }

    private static (int W, int H) PngSize(string path)
    {
        using var fs = File.OpenRead(path);
        var b = new byte[24];
        fs.ReadExactly(b);
        return ((b[16] << 24) | (b[17] << 16) | (b[18] << 8) | b[19], (b[20] << 24) | (b[21] << 16) | (b[22] << 8) | b[23]);
    }

    private static JsonObject Meta(ChatJob job, ImageRequest req, ImageModelDef def, string original, string finalPrompt, string? rewriteMode, string id, int idx, JsonObject e) => new()
    {
        ["id"] = id, ["job"] = job.Id, ["index"] = idx, ["createdAt"] = DateTimeOffset.Now.ToUnixTimeMilliseconds(), ["model"] = def.Id,
        ["modelName"] = def.Name, ["prompt"] = original, ["finalPrompt"] = finalPrompt, ["rewrite"] = rewriteMode, ["width"] = e["width"]?.DeepClone(),
        ["height"] = e["height"]?.DeepClone(), ["steps"] = req.Steps, ["guidance"] = req.Guidance, ["seed"] = e["seed"]?.DeepClone(),
        ["format"] = req.Format, ["quality"] = req.Quality, ["ms"] = e["ms"]?.DeepClone(), ["device"] = job.DeviceId, ["meta"] = job.Meta?.DeepClone(),
    };

    private static void Metrics(JsonObject o)
    {
        try
        {
            o["at"] = DateTimeOffset.Now.ToUnixTimeMilliseconds();
            var path = Path.Combine(AppPaths.Root, "image-metrics.jsonl");
            var fi = new FileInfo(path);
            if (fi.Exists && fi.Length > 2 * 1024 * 1024) File.Move(path, path + ".1", overwrite: true);
            File.AppendAllText(path, o.ToJsonString() + "\n");
        }
        catch (Exception) { }
    }

    // ------------------------------------------------------------------ ciclo de vida

    /// <summary>Um LLM vai carregar: o motor de imagem solta a GPU (chamado pelo ModelManager antes de qualquer carga).</summary>
    public async Task ReleaseForLlmAsync(CancellationToken ct)
    {
        if (!WorkerAlive) return;
        await _gate.WaitAsync(ct);
        try
        {
            if (!WorkerAlive) return;
            Trace?.Invoke("um modelo de conversa vai carregar: liberando a GPU do motor de imagem");
            await ShutdownWorkerAsync("llm");
            await WaitVramSettleAsync(ct);
        }
        finally { _gate.Release(); }
        // o LLM que vai carregar assume; não há mais o que devolver
        Displaced = null;
    }

    /// <summary>Encerra o worker (a GPU volta inteira) e zera o estado.</summary>
    private async Task ShutdownWorkerAsync(string why)
    {
        var w = _worker;
        if (w is null) return;
        State = EngineState.Releasing;
        Changed?.Invoke();
        _worker = null;
        Loaded = null;
        LoadedTransformer = null;
        LoadedTextEncoder = null;
        await w.ShutdownAsync(TimeSpan.FromSeconds(8));
        w.Dispose();
        State = EngineState.Off;
        Trace?.Invoke($"worker de imagem encerrado ({why})");
        Changed?.Invoke();
    }

    /// <summary>
    /// Pré-aquecimento (o celular abriu "Criar imagem"): liga o worker e, se a GPU estiver livre sem tirar ninguém,
    /// já carrega o modelo. Nunca descarrega um LLM por causa de um palpite.
    /// </summary>
    public void Prepare(string choice)
    {
        if (!Enabled || _disposed || Busy || AnyJobActive()) return;
        var (def, _, _) = Choose(choice, "standard", "", 1024 * 1024);
        if (def is null || !Runtime.QuickCheck()) return;
        _ = Task.Run(async () =>
        {
            if (!await _gate.WaitAsync(0)) return;
            try
            {
                LastUse = DateTimeOffset.Now;
                if (AnyJobActive()) return;
                var noop = new Action<string, JsonObject?>((_, _) => { });
                await EnsureWorkerAsync(noop, CancellationToken.None);
                if (Loaded == def) return;
                var llmLoaded = _models.Last.Models.Any(m => m.Loaded && m.Type == "llm");
                var (total, used) = await VramAsync(CancellationToken.None);
                if (!llmLoaded && total > 0 && total - used >= NeedGiB(def, false))
                {
                    Trace?.Invoke($"pré-aquecendo {def.Id} (GPU livre)");
                    await LoadAsync(def, Mode(def, false), noop, CancellationToken.None);
                }
            }
            catch (Exception e) { Trace?.Invoke($"pré-aquecimento falhou: {e.GetType().Name} {e.Message}"); }
            finally
            {
                if (State is EngineState.Loading or EngineState.Starting) State = WorkerAlive ? EngineState.Ready : EngineState.Off;
                _gate.Release();
                Changed?.Invoke();
            }
        });
    }

    /// <summary>
    /// A cada 10 s: com o motor ocioso, mantém aquecido por um tempo e depois libera a GPU.
    /// Se um LLM saiu para a imagem entrar, ele volta (a menos que o usuário tenha descarregado de propósito).
    /// </summary>
    private async Task IdleTickAsync()
    {
        if (_disposed || Busy || HasPendingWork()) return;
        try
        {
            var idle = DateTimeOffset.Now - LastUse;
            var keep = Displaced is not null ? TimeSpan.FromSeconds(_settings.ImageKeepWarmWithLlmSeconds) : TimeSpan.FromMinutes(_settings.ImageKeepWarmMinutes);
            if (WorkerAlive && Loaded is null && idle > TimeSpan.FromMinutes(3)) keep = TimeSpan.Zero; // só o processo, sem modelo
            if (WorkerAlive && idle > keep)
            {
                if (!await _gate.WaitAsync(0)) return;
                try
                {
                    if (HasPendingWork()) return;
                    Activity?.Invoke("Motor de imagem ocioso: GPU liberada", "model");
                    await ShutdownWorkerAsync("idle");
                }
                finally { _gate.Release(); }
            }
            if (!WorkerAlive && Displaced is { } d && !AnyJobActive() && _models.Op == ModelOpState.Idle)
            {
                Displaced = null;
                if (_models.UserUnloaded || _models.Last.Models.Any(m => m.Loaded && m.Type == "llm")) return;
                if (_models.Find(d.Key) is null) return;
                Activity?.Invoke($"Devolvendo {_models.DisplayName(d.Key)} à GPU", "model");
                try { await _models.EnsureLoadedAsync(d.Key, null, CancellationToken.None, d.Tier); }
                catch (Exception e) { Trace?.Invoke($"não foi possível recarregar {d.Key}: {e.Message}"); }
            }
        }
        catch (Exception e) { Trace?.Invoke($"manutenção do motor de imagem: {e.GetType().Name}"); }
    }

    // ------------------------------------------------------------------ textos

    /// <summary>Heurística simples: o texto parece inglês (não precisa de tradução)?</summary>
    public static bool LooksEnglish(string text)
    {
        var t = " " + Regex.Replace(text.ToLowerInvariant(), @"[^\p{L}\s]", " ") + " ";
        if (Regex.IsMatch(t, "[ãõçáéíóúâêôà]")) return false;
        string[] pt = [" de ", " da ", " do ", " uma ", " um ", " com ", " em ", " no ", " na ", " que ", " para ", " os ", " as ", " e ", " imagem ", " crie ", " gere ", " foto "];
        string[] en = [" the ", " a ", " an ", " of ", " with ", " in ", " on ", " and ", " at ", " photo ", " image ", " style ", " lighting "];
        var p = pt.Count(w => t.Contains(w));
        var e = en.Count(w => t.Contains(w));
        return e >= 2 && e > p;
    }

    public static string RuntimeMessage(string code) => code switch
    {
        "cuda_unavailable" => "A GPU NVIDIA não está disponível para o motor de imagem (driver ou CUDA).",
        "driver_incompatible" => "O driver da NVIDIA deste PC é antigo demais para o motor de imagem. Atualize o driver da NVIDIA.",
        "no_space" => "Não há espaço em disco suficiente no PC para preparar o motor de imagem.",
        "network" => "O PC está sem internet para baixar os componentes do motor de imagem. Tente de novo quando a conexão voltar.",
        _ => "Não foi possível preparar o motor de imagem no PC.",
    };

    public static string WorkerMessage(string code, ImageModelDef def) => code switch
    {
        "oom" => "Não havia VRAM suficiente para esta configuração. Ajustamos os recursos e você pode tentar novamente.",
        "model_missing" => $"Os arquivos do {def.Name} estão incompletos no PC. Abra Modelos de imagem para reparar.",
        "cuda_unavailable" or "cuda_error" => "A GPU NVIDIA não respondeu ao motor de imagem (driver ou CUDA).",
        "worker_crash" or "worker_start" or "worker_timeout" => "O motor de imagem parou inesperadamente no PC. Tente de novo.",
        "not_loaded" => "O modelo de imagem não estava pronto. Tente de novo.",
        _ => "A geração da imagem falhou no PC. Tente de novo.",
    };

    public JsonObject ToJson()
    {
        var gpu = _gpu.Last;
        return new JsonObject
        {
            ["enabled"] = Enabled,
            ["state"] = State.ToString().ToLowerInvariant(),
            ["loaded"] = Loaded?.Id,
            ["loadedName"] = Loaded?.Name,
            ["stage"] = Stage,
            ["mode"] = LoadedTransformer is null ? null : LoadedTransformer + "/" + LoadedTextEncoder,
            ["runtime"] = Runtime.ToJson(),
            ["gpu"] = gpu?.Name,
            ["root"] = ImagePaths.HasRoot ? ImagePaths.Root : null,
            ["defaultChoice"] = "fast",
            ["hfUser"] = HfUser,
            ["models"] = new JsonArray(ImageCatalog.All.Select(m => (JsonNode)Store.ToJson(m)).ToArray()),
        };
    }

    /// <summary>Conta do Hugging Face conectada (nome), se houver token.</summary>
    public string? HfUser { get; set; }

    public async ValueTask DisposeAsync()
    {
        _disposed = true;
        await _idleTimer.DisposeAsync();
        if (_worker is { } w) await w.ShutdownAsync(TimeSpan.FromSeconds(3));
        Store.Dispose();
    }
}
