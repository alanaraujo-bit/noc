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

/// <summary>LoRA pedido numa geração (id da biblioteca + peso).</summary>
public sealed record LoraUse(string Id, double Weight);

/// <summary>Pedido de imagem (vem do celular pelo image.start; fica no diário da tarefa).</summary>
public sealed record ImageRequest
{
    public required string Prompt { get; init; }
    /// <summary>fast | quality | auto | model (como o usuário escolheu).</summary>
    public string Choice { get; init; } = "fast";
    /// <summary>Modelo resolvido no momento do pedido.</summary>
    public required string Model { get; init; }
    public int Width { get; init; } = 1024;
    public int Height { get; init; } = 1024;
    public string Format { get; init; } = "square";
    /// <summary>fast | standard | high</summary>
    public string Quality { get; init; } = "standard";
    public int Steps { get; init; }
    /// <summary>Guidance (FLUX) ou CFG (SDXL).</summary>
    public double Guidance { get; init; }
    public int Count { get; init; } = 1;
    public long Seed { get; init; }
    public bool Enhance { get; init; }
    /// <summary>Por que o motor automático escolheu este modelo (para mostrar em Detalhes).</summary>
    public string? AutoNote { get; init; }

    // ---- fase 4
    /// <summary>txt2img | img2img (variação) | inpaint | outpaint | upscale | detail | hd</summary>
    public string Op { get; init; } = "txt2img";
    /// <summary>Negativo escrito pela pessoa (o do modelo entra sozinho, exceto no RAW).</summary>
    public string? Negative { get; init; }
    /// <summary>RAW: o prompt vai exatamente como foi escrito.</summary>
    public bool Raw { get; init; }
    public string? Sampler { get; init; }
    public string? Schedule { get; init; }
    public int? ClipSkip { get; init; }
    public double? GuidanceRescale { get; init; }
    /// <summary>Força da edição (img2img/inpaint): 0 = igual, 1 = tudo novo.</summary>
    public double? Strength { get; init; }
    /// <summary>Imagem de origem: "img:&lt;id no PC&gt;" ou "blob:&lt;sha256&gt;" (enviada pelo celular).</summary>
    public string? Source { get; init; }
    /// <summary>Máscara do inpaint (PNG com alfa ou tons de cinza; branco = mudar): "blob:&lt;sha256&gt;".</summary>
    public string? Mask { get; init; }
    /// <summary>Outpaint: pixels a acrescentar em cada lado.</summary>
    public int[]? Expand { get; init; }
    /// <summary>ControlNet: pose | depth | canny (+ imagem e força).</summary>
    public string? ControlType { get; init; }
    public string? ControlSource { get; init; }
    public double? ControlScale { get; init; }
    /// <summary>Imagem de referência (IP-Adapter) e força.</summary>
    public string? Reference { get; init; }
    public double? ReferenceScale { get; init; }
    /// <summary>Detail pass: rostos, mãos.</summary>
    public string[]? Detail { get; init; }
    /// <summary>Ampliação (2 ou 4) e modo Alta Definição (amplia e refina com o modelo).</summary>
    public int Upscale { get; init; }
    public bool Hd { get; init; }
    public List<LoraUse> Loras { get; init; } = [];
    /// <summary>Recriar: prompt final e negativo exatos (não recompila — o reescritor pode mudar entre versões).</summary>
    public string? FinalPrompt { get; init; }
    public string? FinalNegative { get; init; }
    /// <summary>Imagem da qual esta nasceu (variação, edição, recriação).</summary>
    public string? Parent { get; init; }
    /// <summary>O que a imagem de origem mostra (o prompt dela): dá contexto a "um gorro vermelho" pintado num gato.</summary>
    public string? Context { get; init; }

    public bool IsEdit => Op is not "txt2img";
    /// <summary>Só ampliação (sem refino): não precisa de nenhum modelo de difusão.</summary>
    public bool PlainUpscale => Op == "upscale" && !Hd && (Detail is null || Detail.Length == 0);

    public JsonObject ToJson() => new()
    {
        ["kind"] = "image", ["prompt"] = Prompt, ["choice"] = Choice, ["model"] = Model, ["width"] = Width, ["height"] = Height,
        ["format"] = Format, ["quality"] = Quality, ["steps"] = Steps, ["guidance"] = Guidance, ["count"] = Count, ["seed"] = Seed,
        ["enhance"] = Enhance, ["autoNote"] = AutoNote, ["op"] = Op, ["negative"] = Negative, ["raw"] = Raw, ["sampler"] = Sampler,
        ["schedule"] = Schedule, ["clipSkip"] = ClipSkip, ["guidanceRescale"] = GuidanceRescale, ["strength"] = Strength, ["source"] = Source,
        ["mask"] = Mask, ["expand"] = Expand is null ? null : new JsonArray(Expand.Select(x => (JsonNode)x).ToArray()),
        ["controlType"] = ControlType, ["controlSource"] = ControlSource, ["controlScale"] = ControlScale, ["reference"] = Reference,
        ["referenceScale"] = ReferenceScale, ["detail"] = Detail is null ? null : new JsonArray(Detail.Select(x => (JsonNode)x).ToArray()),
        ["upscale"] = Upscale, ["hd"] = Hd,
        ["loras"] = new JsonArray(Loras.Select(l => (JsonNode)new JsonObject { ["id"] = l.Id, ["weight"] = l.Weight }).ToArray()),
        ["finalPrompt"] = FinalPrompt, ["finalNegative"] = FinalNegative, ["parent"] = Parent, ["context"] = Context,
    };

    private static T? V<T>(JsonObject o, string k) => o[k] is JsonValue v && v.TryGetValue<T>(out var r) ? r : default;

    public static ImageRequest FromJson(JsonObject o) => new()
    {
        Prompt = o["prompt"]!.GetValue<string>(), Choice = V<string>(o, "choice") ?? "fast", Model = o["model"]!.GetValue<string>(),
        Width = o["width"]?.GetValue<int>() ?? 1024, Height = o["height"]?.GetValue<int>() ?? 1024, Format = V<string>(o, "format") ?? "square",
        Quality = V<string>(o, "quality") ?? "standard", Steps = o["steps"]?.GetValue<int>() ?? 0, Guidance = o["guidance"]?.GetValue<double>() ?? 0,
        Count = o["count"]?.GetValue<int>() ?? 1, Seed = o["seed"]?.GetValue<long>() ?? 0, Enhance = o["enhance"]?.GetValue<bool>() ?? false,
        AutoNote = V<string>(o, "autoNote"), Op = V<string>(o, "op") ?? "txt2img", Negative = V<string>(o, "negative"), Raw = V<bool>(o, "raw"),
        Sampler = V<string>(o, "sampler"), Schedule = V<string>(o, "schedule"), ClipSkip = o["clipSkip"] is null ? null : V<int>(o, "clipSkip"),
        GuidanceRescale = o["guidanceRescale"] is null ? null : V<double>(o, "guidanceRescale"), Strength = o["strength"] is null ? null : V<double>(o, "strength"),
        Source = V<string>(o, "source"), Mask = V<string>(o, "mask"), Expand = (o["expand"] as JsonArray)?.Select(x => x!.GetValue<int>()).ToArray(),
        ControlType = V<string>(o, "controlType"), ControlSource = V<string>(o, "controlSource"),
        ControlScale = o["controlScale"] is null ? null : V<double>(o, "controlScale"), Reference = V<string>(o, "reference"),
        ReferenceScale = o["referenceScale"] is null ? null : V<double>(o, "referenceScale"),
        Detail = (o["detail"] as JsonArray)?.Select(x => x!.GetValue<string>()).ToArray(), Upscale = V<int>(o, "upscale"), Hd = V<bool>(o, "hd"),
        Loras = (o["loras"] as JsonArray)?.OfType<JsonObject>().Select(l => new LoraUse(l["id"]!.GetValue<string>(), l["weight"]?.GetValue<double>() ?? 0.8)).ToList() ?? [],
        FinalPrompt = V<string>(o, "finalPrompt"), FinalNegative = V<string>(o, "finalNegative"), Parent = V<string>(o, "parent"),
        Context = V<string>(o, "context"),
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
    /// <summary>LoRAs e checkpoints importados.</summary>
    public ImageLibrary Library { get; } = new();
    /// <summary>Imagens enviadas pelo celular (origem de edições, máscaras, referências) — o host liga no BlobStore.</summary>
    public Func<string, byte[]?> BlobGet { get; set; } = _ => null;
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
        ImageCatalog.ReloadCustom(); // registro de importados é por pasta de dados
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

    /// <summary>Modelo pronto para rodar: instalado e com os pacotes de que depende (ex.: configs do SDXL).</summary>
    public bool Ready(ImageModelDef m) =>
        Store.IsInstalled(m) && m.Requires.All(r => ImageCatalog.Find(r) is { } d && Store.IsInstalled(d));

    /// <summary>
    /// Resolve "fast" | "quality" | "auto" | "model:&lt;id&gt;" para um modelo pronto (ou explica por que não dá).
    /// No automático, LoRAs escolhidos e a imagem de origem (edição) pesam na escolha.
    /// </summary>
    public (ImageModelDef? Model, string? Note, string? Error) Choose(string choice, string quality, string prompt, long pixels,
        string? loraFamily = null, string? sourceModel = null, string op = "txt2img")
    {
        if (choice.StartsWith("model:", StringComparison.Ordinal))
        {
            var m = ImageCatalog.Find(choice[6..]);
            if (m is null || m.IsAux) return (null, null, "model_unknown");
            if (!Ready(m)) return (null, null, (m.Gated && !Store.IsInstalled(m) ? "flux_unavailable:" : "model_not_installed:") + m.Id);
            return pixels > m.Defaults.MaxPixels ? (null, null, "too_large") : (m, null, null);
        }
        var z = ImageCatalog.ZImageTurbo;
        var f = ImageCatalog.FluxDev;
        switch (choice)
        {
            case "fast":
                return Ready(z) ? (z, null, null) : (null, null, "model_not_installed:" + z.Id);
            case "quality":
                if (Ready(f)) return pixels > f.Defaults.MaxPixels ? (null, null, "too_large") : (f, null, null);
                return (null, null, (f.Gated ? "flux_unavailable:" : "model_not_installed:") + f.Id);
            default:
                var r = ImageRouter.Choose(prompt, quality, pixels, Ready, loraFamily, sourceModel, op);
                return (r.Model, r.Note, r.Error);
        }
    }

    /// <summary>Pacotes auxiliares que a operação usa (ControlNet, IP-Adapter, detectores, ampliação).</summary>
    public static List<ImageModelDef> AuxFor(ImageRequest req, ImageModelDef def)
    {
        var need = new List<ImageModelDef>();
        if (def.Engine == "sdxl") need.Add(ImageCatalog.SdxlConfig);
        if (req.ControlType is { } ct)
        {
            need.Add(ImageCatalog.ControlNetUnion);
            if (ct == "pose") { need.Add(ImageCatalog.VitPose); need.Add(ImageCatalog.RtDetr); }
            if (ct == "depth") need.Add(ImageCatalog.DepthAnything);
        }
        if (req.Reference is not null) need.Add(ImageCatalog.IpAdapter);
        if (req.Detail is { Length: > 0 } || req.Op == "detail") need.Add(ImageCatalog.GroundingDino);
        if (req.Upscale > 0 || req.Hd || req.Op is "upscale" or "hd") need.Add(ImageCatalog.RealEsrgan);
        return need.Distinct().ToList();
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

    /// <summary>Entradas da tarefa (origem, máscara, controle, referência) como arquivos que o worker lê; os temporários somem no fim.</summary>
    private sealed class Inputs : IDisposable
    {
        public string? Source, Mask, Control, Reference;
        public readonly List<string> Temp = [];
        public void Dispose()
        {
            foreach (var t in Temp) try { File.Delete(t); } catch (Exception) { }
        }
    }

    private string? ResolveInput(string? r, string job, string name, Inputs inputs)
    {
        if (string.IsNullOrEmpty(r)) return null;
        if (r.StartsWith("img:", StringComparison.Ordinal))
        {
            var b = Outputs.BaseOf(r[4..]) ?? throw new JobFailure("source_missing", "A imagem de origem não está mais no PC.");
            return b + ".png";
        }
        if (r.StartsWith("blob:", StringComparison.Ordinal))
        {
            var bytes = BlobGet(r[5..]) ?? throw new JobFailure("source_missing", "A imagem enviada expirou no PC. Envie de novo.");
            Directory.CreateDirectory(ImagePaths.Tmp);
            var path = Path.Combine(ImagePaths.Tmp, $"{job}-{name}.img");
            File.WriteAllBytes(path, bytes);
            inputs.Temp.Add(path);
            return path;
        }
        throw new JobFailure("bad_request", "Referência de imagem inválida.");
    }

    /// <summary>Modelo que gerou uma imagem do PC (para editar no mesmo modelo).</summary>
    public string? ModelOfImage(string? r) =>
        r is not null && r.StartsWith("img:", StringComparison.Ordinal) ? Outputs.ReadMeta(r[4..])?["model"]?.GetValue<string>() : null;

    /// <summary>LoRAs pedidos → entradas da biblioteca, conferindo compatibilidade (arquitetura errada = erro claro).</summary>
    private List<(LoraEntry Entry, double Weight)> ResolveLoras(ImageRequest req, ImageModelDef def)
    {
        var res = new List<(LoraEntry, double)>();
        foreach (var u in req.Loras)
        {
            var e = Library.Find(u.Id) ?? throw new JobFailure("lora_missing", "Um dos LoRAs escolhidos não está mais na biblioteca do PC.");
            if (!File.Exists(e.File)) throw new JobFailure("lora_missing", $"O arquivo do LoRA \"{e.Name}\" sumiu do PC.");
            var (ok, warn) = ImageLibrary.Compatible(e, def);
            if (!ok) throw new JobFailure("lora_incompatible", $"\"{e.Name}\": {warn}");
            res.Add((e, u.Weight));
        }
        return res;
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
        foreach (var a in AuxFor(req, def))
            if (!Store.IsInstalled(a))
            {
                if (!Store.Busy(a)) QueueInstall(a);
                throw new JobFailure("aux_missing", $"O componente \"{a.Name}\" ainda está sendo instalado no PC. Tente de novo em alguns minutos.");
            }
        var wall = Stopwatch.StartNew();
        await _gate.WaitAsync(ct);
        _sink = publish;
        _sinkJob = job.Id;
        // já carregado no modo econômico (VRAM estava apertada): continua nele em vez de recarregar tudo
        var lowVram = WorkerAlive && Loaded == def && LoadedTransformer != def.Defaults.Transformer;
        long loadMs = 0;
        using var inputs = new Inputs();
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

            // 1b. cópias de carga rápida (Z-Image/FLUX; uma vez por modelo, num processo à parte)
            if (def.Engine != "sdxl" && !req.PlainUpscale && !IsOptimized(def, false)) await OptimizeAsync(def, false, phase, ct);

            // 1c. entradas
            inputs.Source = ResolveInput(req.Source, job.Id, "src", inputs);
            inputs.Mask = ResolveInput(req.Mask, job.Id, "mask", inputs);
            inputs.Control = ResolveInput(req.ControlSource, job.Id, "ctl", inputs) ?? (req.ControlType is not null ? inputs.Source : null);
            inputs.Reference = ResolveInput(req.Reference, job.Id, "ref", inputs);
            if (req.IsEdit && inputs.Source is null) throw new JobFailure("bad_request", "Falta a imagem a editar.");
            if (req.ControlType is not null && inputs.Control is null) throw new JobFailure("bad_request", "Falta a imagem de referência para a pose/estrutura.");

            // 2. prompt: LoRAs, compilação por modelo (tradução, tags, score tags) — ou exatamente o de antes, ao recriar
            var loras = ResolveLoras(req, def);
            var prompt = req.Prompt.Trim();
            string? rewriteMode = req.FinalPrompt is not null || req.PlainUpscale ? null : PromptCompiler.RewriteMode(def, prompt, req.Enhance, req.Raw);
            // edição com pincel/expansão: o pedido é o que muda; a cena vem da imagem de origem
            var rewriteInput = prompt;
            if (!req.Raw && req.FinalPrompt is null && req.Context is { Length: > 0 } ctxt && req.Op is "inpaint" or "outpaint" && !string.Equals(ctxt.Trim(), prompt, StringComparison.OrdinalIgnoreCase))
            {
                if (def.Preset.Strategy == "natural") { rewriteMode = "edit"; rewriteInput = $"SCENE: {ctxt}\nCHANGE: {prompt}"; }
                else { rewriteMode ??= def.Preset.Strategy; rewriteInput = $"{prompt}, {ctxt}"; }
            }
            string? body = null;
            string? rewriteNote = null;

            for (var attempt = 0; ; attempt++)
            {
                try
                {
                    // 3. VRAM: abre espaço (ou escolhe o modo econômico) e liga o worker
                    var mode = Mode(def, lowVram);
                    var keepRewriter = def.Engine == "sdxl" && rewriteMode is not null && !lowVram;
                    var extra = ExtraGiB(req, keepRewriter);
                    if (!req.PlainUpscale && !(WorkerAlive && Loaded == def && LoadedTransformer == mode.Transformer && LoadedTextEncoder == mode.TextEncoder))
                    {
                        lowVram = await MakeRoomAsync(def, lowVram, phase, ct, extra);
                        mode = Mode(def, lowVram);
                    }
                    if (lowVram && def.Defaults.HasLowVram && !IsOptimized(def, true))
                    {
                        if (WorkerAlive) await ShutdownWorkerAsync("optimize");
                        await OptimizeAsync(def, true, phase, ct);
                    }
                    await EnsureWorkerAsync(phase, ct);

                    // FLUX/SDXL: reescreve antes de carregar (o Qwen do Z-Image sobe sozinho; no SDXL pode ficar residente)
                    if (rewriteMode is not null && body is null && def.Engine != "zimage")
                        (body, rewriteNote) = await RewriteAsync(rewriteInput, rewriteMode, phase, ct, keepRewriter && def.Engine == "sdxl");

                    // 4. carrega o modelo (ampliação simples não precisa de nenhum)
                    if (!req.PlainUpscale)
                    {
                        var loadSw = Stopwatch.StartNew();
                        await LoadAsync(def, mode, phase, ct);
                        loadMs += loadSw.ElapsedMilliseconds;
                    }

                    // Z-Image: reescreve com o Qwen que já está carregado como codificador de texto
                    if (rewriteMode is not null && body is null && def.Engine == "zimage")
                        (body, rewriteNote) = await RewriteAsync(rewriteInput, rewriteMode, phase, ct, false);

                    var compiled = req.FinalPrompt is not null
                        ? new CompiledPrompt(prompt, req.FinalPrompt, req.FinalNegative ?? "", "recreate", null, null, [])
                        : PromptCompiler.Compile(def, prompt, body, rewriteMode, req.Raw, req.Negative, loras.Select(l => l.Entry));

                    // 5. gera
                    var end = await GenerateAsync(job, req, def, compiled, rewriteNote, loras, inputs, loadMs, wall, phase, publish, ct);
                    Library.MarkUsed(loras.Select(l => l.Entry.Id));
                    return end;
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
            throw new JobFailure(e.Code, WorkerMessage(e.Code, def, e.Message));
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
        def.Engine == "sdxl" ? new EngineMode("bf16", "gpu")
        : lowVram ? new EngineMode("fp8", def.Engine == "flux" ? "swap" : "fp8") : new EngineMode(def.Defaults.Transformer, def.Defaults.TextEncoder);

    /// <summary>VRAM (GiB) que o modelo precisa no modo escolhido: residente + pico de uma geração + margem.</summary>
    private static double NeedGiB(ImageModelDef def, bool lowVram, double extra = 0)
    {
        var d = def.Defaults;
        var resident = lowVram && d.Transformer == "bf16" && d.HasLowVram ? d.ResidentGiB - d.TransformerGiB / 2 : d.ResidentGiB;
        return resident + d.PeakExtraGiB + 0.5 + (lowVram ? 0 : extra);
    }

    /// <summary>VRAM medida dos componentes extras de uma operação (ControlNet, IP-Adapter, detector, Qwen residente).</summary>
    private static double ExtraGiB(ImageRequest req, bool keepRewriter)
    {
        var x = 0.0;
        if (req.ControlType is not null || req.Hd) x += 2.6;
        if (req.Reference is not null) x += 2.0;
        if (req.Detail is { Length: > 0 } || req.Op == "detail") x += 0.8;
        if (keepRewriter) x += 4.6;
        if (req.Hd || req.Upscale > 0) x += 0.6;
        return x;
    }

    /// <summary>
    /// Abre espaço na GPU para o modelo: descarrega outro modelo de imagem e, se ainda faltar, o LLM (lembrando qual era).
    /// Se outros programas ocupam a VRAM, passa para o modo econômico; se nem assim couber, explica.
    /// Devolve se o modo econômico foi necessário.
    /// </summary>
    private async Task<bool> MakeRoomAsync(ImageModelDef def, bool lowVram, Action<string, JsonObject?> phase, CancellationToken ct, double extra = 0)
    {
        // um modelo de conversa está carregando agora (ex.: a devolução depois da última imagem):
        // espera terminar para medir a VRAM de verdade, senão os dois acabam juntos na GPU
        for (var i = 0; i < 360 && _models.Op != ModelOpState.Idle; i++) await Task.Delay(500, ct);
        await _models.RefreshAsync(ct);
        // o que o próprio motor ocupa (outro modelo ou outro modo) sai primeiro; o worker fica, só descarrega
        if (WorkerAlive && Loaded is not null)
        {
            try { await _worker!.RequestAsync(new JsonObject { ["cmd"] = "unload" }, ct, TimeSpan.FromSeconds(60)); }
            catch (WorkerException) { await ShutdownWorkerAsync("switch"); }
            Loaded = null;
        }
        var (total, used) = await VramAsync(ct);
        if (total <= 0) return lowVram; // sem leitura da GPU: segue e deixa a recuperação de OOM agir
        var reserve = Math.Max(0, _settings.ImageVramReserveGiB);
        var free = total - used - reserve;
        var need = NeedGiB(def, lowVram, extra);
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
            if (!def.Defaults.HasLowVram) Trace?.Invoke($"VRAM apertada ({free:0.0} GiB livres): {def.Id} sem componentes residentes extras");
            else
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
        phase("loading", new JsonObject { ["name"] = def.Name, ["expectedSeconds"] = Store.LoadSeconds(def), ["stage"] = "model" });
        var sw = Stopwatch.StartNew();
        JsonObject r;
        JsonObject cmd;
        if (def.Engine == "sdxl")
        {
            cmd = new JsonObject
            {
                ["cmd"] = "load", ["engine"] = "sdxl", ["path"] = Store.CheckpointPath(def) ?? throw new JobFailure("model_missing", $"O arquivo do {def.Name} sumiu do PC."),
                ["config"] = Store.InstalledDir(ImageCatalog.SdxlConfig) ?? throw new JobFailure("aux_missing", "A configuração do SDXL ainda não foi instalada."),
                ["dtype"] = "bf16",
            };
            // checkpoint sem VAE embutido: o VAE vem de um SDXL instalado (o arquivo inteiro serve de fonte)
            if (!def.BakedVae)
                cmd["vae"] = new[] { ImageCatalog.RealVisXL, ImageCatalog.PonyV6, ImageCatalog.NoobAI }.Select(Store.CheckpointPath).FirstOrDefault(x => x is not null)
                             ?? throw new JobFailure("vae_missing", $"O {def.Name} não traz VAE e nenhum SDXL instalado pode emprestar o dele.");
        }
        else
        {
            var dir = Store.InstalledDir(def)!;
            var rev = Store.InstalledRevision(def)!;
            cmd = new JsonObject
            {
                ["cmd"] = "load", ["engine"] = def.Engine, ["path"] = dir, ["cache"] = ImagePaths.CacheDir(def, rev),
                ["transformer"] = mode.Transformer, ["text_encoder"] = mode.TextEncoder,
            };
        }
        try
        {
            r = await _worker!.RequestAsync(cmd, ct, TimeSpan.FromMinutes(30));
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
        if (r["mem"]?["allocatedMb"]?.GetValue<long>() is long alloc && alloc > 0) Store.SetMeasuredVram(def, resident: alloc / 1024.0, peak: null);
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
        if (def.Engine == "sdxl") return Store.IsInstalled(def); // arquivo único já é o formato de carga
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
            if (def.IsAux || def.Engine == "sdxl" || IsOptimized(def, false)) return;
            await _gate.WaitAsync();
            try
            {
                if (!IsOptimized(def, false)) await OptimizeAsync(def, false, (_, _) => { }, CancellationToken.None);
            }
            finally { _gate.Release(); }
        }
        catch (Exception e) { Trace?.Invoke($"preparação de {def.Id} depois da instalação falhou: {e.GetType().Name} {e.Message}"); }
    });

    // ------------------------------------------------------------------ instalação automática (um por vez)

    private readonly List<ImageModelDef> _installQueue = [];
    private bool _installLoop;

    /// <summary>Põe na fila de instalação (um modelo por vez: não disputa banda nem disco com os outros).</summary>
    public void QueueInstall(ImageModelDef m)
    {
        lock (_installQueue)
        {
            if (Store.IsInstalled(m) || _installQueue.Contains(m)) return;
            _installQueue.Add(m);
            _installQueue.Sort((x, y) => (x.AutoInstallOrder == 0 ? 999 : x.AutoInstallOrder).CompareTo(y.AutoInstallOrder == 0 ? 999 : y.AutoInstallOrder));
            if (_installLoop) return;
            _installLoop = true;
        }
        _ = Task.Run(InstallLoopAsync);
    }

    /// <summary>Modelos esperando a vez de instalar (para a tela).</summary>
    public string[] InstallQueue { get { lock (_installQueue) return _installQueue.Select(m => m.Id).ToArray(); } }

    private async Task InstallLoopAsync()
    {
        while (!_disposed)
        {
            ImageModelDef? next;
            lock (_installQueue)
            {
                next = _installQueue.FirstOrDefault();
                if (next is null) { _installLoop = false; return; }
            }
            try
            {
                // dependências primeiro (as configs do SDXL são pequenas e o modelo não roda sem elas)
                foreach (var dep in next.Requires.Select(ImageCatalog.Find).OfType<ImageModelDef>().Where(d => !Store.IsInstalled(d)))
                {
                    Store.StartInstall(dep);
                    await Store.WaitIdleAsync(dep, CancellationToken.None);
                }
                if (!Store.IsInstalled(next))
                {
                    Store.StartInstall(next);
                    await Task.Delay(300);
                    await Store.WaitIdleAsync(next, CancellationToken.None);
                }
                if (Store.IsInstalled(next)) Store.MarkAutoInstall(next);
            }
            catch (Exception e) { Trace?.Invoke($"fila de instalação: {next.Id} falhou: {e.GetType().Name} {e.Message}"); }
            lock (_installQueue) _installQueue.Remove(next);
            Changed?.Invoke();
        }
    }

    /// <summary>
    /// Primeira vez (e a cada partida): continua instalações interrompidas e instala sozinho, um por vez, o motor padrão
    /// (Z-Image), os especialistas SDXL e os componentes de edição. O FLUX.1 Dev só entra quando há conta do Hugging Face
    /// com os termos aceitos. Algo que o usuário removeu não volta sozinho.
    /// </summary>
    public void AutoSetup()
    {
        if (!Enabled) return;
        Store.ResumePending();
        foreach (var m in ImageCatalog.BuiltIn.Where(x => x.AutoInstallOrder > 0).OrderBy(x => x.AutoInstallOrder))
        {
            if (Store.IsInstalled(m)) { if (!m.IsAux && m.Engine != "sdxl" && !IsOptimized(m, false)) AfterInstall(m); continue; }
            if (m.Gated && HfToken.Load() is null) continue;
            // marcado só depois de instalar: uma falha no meio não impede de tentar de novo na próxima partida
            if (Store.Busy(m) || Store.WasAutoInstalled(m)) continue;
            QueueInstall(m);
        }
        _ = Task.Run(async () =>
        {
            try { await Runtime.EnsureAsync(CancellationToken.None); } catch (Exception) { }
            // aviso de atualização (no máximo um por dia; nunca baixa sozinho)
            foreach (var m in ImageCatalog.BuiltIn.Where(Store.IsInstalled))
                if (Store.CheckedAt(m) is not { } at || DateTimeOffset.Now - at > TimeSpan.FromDays(1))
                    await Store.CheckUpdateAsync(m, CancellationToken.None);
        });
    }

    private static WorkerException WorkerError(JsonObject r) =>
        new(r["code"]?.GetValue<string>() ?? "pipeline_error", r["message"]?.GetValue<string>() ?? "falha");

    private async Task<(string Prompt, string? Note)> RewriteAsync(string prompt, string mode, Action<string, JsonObject?> phase, CancellationToken ct, bool keep = false)
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
                    ["cache"] = Store.InstalledRevision(z) is { } zr ? ImagePaths.CacheDir(z, zr) : null, ["keep"] = keep,
                }, ct, TimeSpan.FromMinutes(3));
                if (r["ev"]?.GetValue<string>() == "rewritten") result = r["text"]?.GetValue<string>();
                else Trace?.Invoke($"reescrita do prompt falhou no worker: {r["code"]}");
            }
            catch (WorkerException e) when (e.Code != "worker_crash") { Trace?.Invoke($"reescrita do prompt falhou: {e.Code}"); }
        }
        if (string.IsNullOrWhiteSpace(result)) result = await RewriteWithLmStudioAsync(prompt, mode, ct);
        if (mode is "tags" or "pony" && result is not null) result = string.Join(", ", result.Split(['\n', ','], StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries).Select(t => t.Trim('-', '*', ' ')));
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
            var sys = mode switch
            {
                "enhance" => "Rewrite the user's image request as a detailed English prompt for an image generator (composition, lighting, camera, materials). Keep every requested element; add no people or personal traits that were not requested; keep quoted text as written. Output only the prompt.",
                "tags" => "Convert the user's image request into comma-separated English Danbooru-style tags (lowercase, spaces instead of underscores): subject count tags only if people are requested, then appearance, clothing, pose, setting, lighting and style. Invent nothing that was not requested. No quality or rating tags. Output only the tags.",
                "edit" => "The user is editing part of an existing image. You get the SCENE (what the image shows) and the CHANGE (what the painted area must become). Write one English prompt describing the whole image after the change: the scene's main subject and setting plus the change applied to it, e.g. an orange cat sleeping on a window sill wearing a red wool hat. Invent nothing else. Output only the prompt.",
                "pony" => "Turn the user's image request into one line: a short English sentence describing the scene, a comma, then comma-separated lowercase Danbooru-style tags for the key details. Invent nothing that was not requested. No score, rating or quality tags. Output only that line.",
                _ => "Translate the user's image request into an English prompt for an image generator. Keep every detail; remove phrases like 'create an image of'; keep quoted text as written. Output only the prompt.",
            };
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

    /// <summary>Operação do pedido → operação do worker + pós-processos (detail pass, ampliação, refino da Alta Definição).</summary>
    private (string Op, JsonArray Post, List<string> Workflow) Plan(ImageRequest req, ImageModelDef def)
    {
        var post = new JsonArray();
        var wf = new List<string>();
        var op = req.Op switch
        {
            "img2img" => "img2img",
            "inpaint" => "inpaint",
            "outpaint" => "outpaint",
            "upscale" or "detail" or "hd" => "post",
            _ => "txt2img",
        };
        wf.Add(req.Op);
        var detail = req.Detail is { Length: > 0 } d ? d : req.Op == "detail" ? ["face", "hand"] : null;
        if (detail is not null)
        {
            post.Add(new JsonObject
            {
                ["kind"] = "detail", ["targets"] = new JsonArray(detail.Select(t => (JsonNode)t).ToArray()),
                ["strength"] = def.Engine == "sdxl" ? 0.4 : 0.35, ["detector"] = Store.InstalledDir(ImageCatalog.GroundingDino),
            });
            if (req.Op != "detail") wf.Add("detail");
        }
        var factor = req.Hd || req.Op == "hd" ? Math.Max(2, req.Upscale) : req.Upscale > 0 ? req.Upscale : req.Op == "upscale" ? 2 : 0;
        if (factor > 0)
        {
            var anime = def.Category is "anime" or "illustration";
            var file = anime ? "RealESRGAN_x4plus_anime_6B.pth" : factor == 2 ? "RealESRGAN_x2plus.pth" : "RealESRGAN_x4plus.pth";
            var up = new JsonObject
            {
                ["kind"] = "upscale", ["factor"] = factor, ["model"] = Path.Combine(Store.InstalledDir(ImageCatalog.RealEsrgan)!, file),
                ["cache"] = Path.Combine(ImagePaths.Cache, "realesrgan"),
            };
            if (req.Hd || req.Op == "hd")
            {
                var refine = new JsonObject { ["strength"] = def.Engine == "sdxl" ? 0.3 : 0.28, ["steps"] = req.Steps, ["tile"] = 1024, ["overlap"] = 128 };
                if (def.Engine == "sdxl" && Store.InstalledDir(ImageCatalog.ControlNetUnion) is { } cn)
                    refine["cn"] = new JsonObject { ["dir"] = cn, ["weights"] = "diffusion_pytorch_model_promax.safetensors", ["config"] = "config_promax.json", ["scale"] = 0.5 };
                up["refine"] = refine;
            }
            post.Add(up);
            if (req.Op is not ("upscale" or "hd")) wf.Add(req.Hd ? "hd" : "upscale");
        }
        return (op, post, wf);
    }

    private async Task<JsonObject> GenerateAsync(ChatJob job, ImageRequest req, ImageModelDef def, CompiledPrompt prompt, string? rewriteNote,
        List<(LoraEntry Entry, double Weight)> loras, Inputs inputs, long loadMs, Stopwatch wall, Action<string, JsonObject?> phase,
        Action<JsonObject> publish, CancellationToken ct)
    {
        State = EngineState.Working;
        Changed?.Invoke();
        var outBase = Outputs.NewBase(job.Id);
        // retomada depois de um reinício: as imagens já salvas não são geradas de novo
        var first = 0;
        while (first < req.Count && Outputs.BaseOf($"{job.Id}-{first}") is not null) first++;
        var images = new JsonArray();
        for (var i = 0; i < first; i++) images.Add(DescribeImage($"{job.Id}-{i}", Outputs.BaseOf($"{job.Id}-{i}")!, req.Seed + i));
        var (op, post, workflow) = Plan(req, def);
        var p = def.Preset;
        var sampler = req.Sampler ?? p.Sampler;
        var schedule = req.Schedule ?? p.Schedule;
        var clipSkip = req.ClipSkip ?? p.ClipSkip;
        var rescale = req.GuidanceRescale ?? p.GuidanceRescale;
        phase("generating", new JsonObject { ["name"] = def.Name, ["count"] = req.Count, ["steps"] = req.Steps, ["image"] = first, ["op"] = req.Op });
        JsonObject r;
        var aux = new JsonArray();
        if (first < req.Count)
        {
            var cmd = new JsonObject
            {
                ["cmd"] = "generate", ["id"] = job.Id, ["prompt"] = prompt.Positive, ["negative"] = prompt.Negative, ["width"] = req.Width,
                ["height"] = req.Height, ["steps"] = req.Steps, ["guidance"] = req.Guidance, ["seed"] = req.Seed, ["count"] = req.Count, ["first"] = first,
                ["out"] = outBase, ["preview"] = true, ["previewEvery"] = def.Engine switch { "flux" => 3, "sdxl" => 3, _ => 2 },
                ["op"] = op, ["sampler"] = sampler, ["schedule"] = schedule, ["prediction"] = p.Prediction, ["zsnr"] = p.ZeroSnr,
                ["guidanceRescale"] = rescale, ["clipSkip"] = clipSkip, ["post"] = post,
                ["loras"] = new JsonArray(loras.Select(l => (JsonNode)new JsonObject { ["path"] = l.Entry.File, ["weight"] = l.Weight, ["name"] = l.Entry.Name }).ToArray()),
            };
            if (inputs.Source is not null) cmd["init"] = inputs.Source;
            if (req.Strength is double st) cmd["strength"] = Math.Clamp(st, 0.05, 1.0);
            else if (op == "img2img") cmd["strength"] = 0.55;
            else if (op == "inpaint") cmd["strength"] = def.Engine == "sdxl" ? 0.99 : 1.0;
            if (inputs.Mask is not null) cmd["mask"] = inputs.Mask;
            if (req.Expand is { Length: 4 } ex) cmd["expand"] = new JsonObject { ["left"] = ex[0], ["top"] = ex[1], ["right"] = ex[2], ["bottom"] = ex[3] };
            if (req.ControlType is { } ctype && def.Engine == "sdxl")
            {
                cmd["control"] = new JsonObject
                {
                    ["type"] = ctype, ["mode"] = ctype switch { "pose" => 0, "depth" => 1, "canny" => 3, "lineart" => 3, "tile" => 6, _ => 3 },
                    ["image"] = inputs.Control, ["scale"] = Math.Clamp(req.ControlScale ?? (ctype == "pose" ? 0.9 : 0.7), 0.1, 1.5), ["preprocess"] = true,
                    ["cn"] = new JsonObject
                    {
                        ["dir"] = Store.InstalledDir(ImageCatalog.ControlNetUnion), ["weights"] = "diffusion_pytorch_model_promax.safetensors", ["config"] = "config_promax.json",
                    },
                    ["aux"] = new JsonObject
                    {
                        ["detector"] = Store.InstalledDir(ImageCatalog.RtDetr), ["pose"] = Store.InstalledDir(ImageCatalog.VitPose),
                        ["depth"] = Store.InstalledDir(ImageCatalog.DepthAnything),
                    },
                };
            }
            if (inputs.Reference is not null && def.Engine == "sdxl")
            {
                cmd["reference"] = new JsonObject
                {
                    ["image"] = inputs.Reference, ["scale"] = Math.Clamp(req.ReferenceScale ?? 0.5, 0.05, 1.2),
                    ["ip"] = new JsonObject
                    {
                        ["dir"] = Store.InstalledDir(ImageCatalog.IpAdapter), ["weight"] = "ip-adapter-plus_sdxl_vit-h.safetensors",
                        ["subfolder"] = "sdxl_models", ["encoder"] = "models/image_encoder",
                    },
                };
            }
            // cada imagem pronta é registrada e anunciada na hora (o celular começa a baixar enquanto as outras são geradas)
            void OnImage(JsonObject e)
            {
                if (e["ev"]?.GetValue<string>() != "image" || e["id"]?.GetValue<string>() != job.Id) return;
                var idx = e["index"]!.GetValue<int>();
                var id = $"{job.Id}-{idx}";
                var b = $"{outBase}-{idx}";
                Outputs.Register(id, b);
                Outputs.SaveMeta(b, Meta(job, req, def, prompt, loras, workflow, sampler, schedule, clipSkip, rescale, id, idx, e, wall.ElapsedMilliseconds, loadMs));
                publish(new JsonObject { ["imageReady"] = DescribeImage(id, b, e["seed"]!.GetValue<long>()) });
            }
            _worker!.Event += OnImage;
            try
            {
                using var reg = ct.Register(() =>
                {
                    try { _worker?.Send(new JsonObject { ["cmd"] = "cancel", ["id"] = job.Id }); } catch (Exception) { }
                });
                var t = _worker.RequestAsync(cmd, CancellationToken.None, TimeSpan.FromMinutes(45));
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
            if (r["aux"] is JsonArray ax)
                foreach (var a in ax) if (a is not null) aux.Add(Path.GetFileName(a.GetValue<string>()));
        }
        else r = new JsonObject { ["stats"] = new JsonObject() };
        var stats = r["stats"] as JsonObject ?? new JsonObject();
        var end = new JsonObject
        {
            ["reason"] = "stop", ["model"] = def.Id, ["name"] = def.Name, ["images"] = images, ["op"] = req.Op, ["parent"] = req.Parent,
            ["prompt"] = new JsonObject
            {
                ["original"] = prompt.Original, ["final"] = prompt.Positive, ["negative"] = prompt.Negative, ["strategy"] = prompt.Strategy,
                ["mode"] = prompt.RewriteMode, ["note"] = rewriteNote ?? req.AutoNote,
                ["added"] = new JsonArray(prompt.Added.Select(a => (JsonNode)a).ToArray()),
            },
            ["loras"] = new JsonArray(loras.Select(l => (JsonNode)new JsonObject { ["id"] = l.Entry.Id, ["name"] = l.Entry.Name, ["weight"] = l.Weight }).ToArray()),
            ["stats"] = new JsonObject
            {
                ["wallMs"] = wall.ElapsedMilliseconds, ["loadMs"] = loadMs, ["generateMs"] = stats["totalMs"]?.DeepClone(),
                ["meanStepMs"] = stats["meanStepMs"]?.DeepClone(), ["peakVramMb"] = stats["peakReservedMb"]?.DeepClone(),
                // edições: o tamanho do resultado (expandir e ampliar mudam o tamanho)
                ["width"] = images.Count > 0 && images[0]?["width"]?.GetValue<int>() is int iw and > 0 ? iw : req.Width,
                ["height"] = images.Count > 0 && images[0]?["height"]?.GetValue<int>() is int ih and > 0 ? ih : req.Height,
                ["steps"] = req.Steps, ["guidance"] = req.Guidance, ["seed"] = req.Seed,
                ["count"] = req.Count, ["transformer"] = LoadedTransformer, ["textEncoder"] = LoadedTextEncoder, ["sampler"] = def.Engine == "sdxl" ? sampler : null,
                ["schedule"] = def.Engine == "sdxl" ? schedule : null,
                ["queuedMs"] = (long)((job.StartedAt ?? DateTimeOffset.Now) - job.CreatedAt).TotalMilliseconds,
            },
        };
        if (stats["peakReservedMb"]?.GetValue<long>() is long pk && pk > 0 && req.Op == "txt2img" && req.ControlType is null && req.Reference is null && !req.Hd)
            Store.SetMeasuredVram(def, resident: null, peak: pk / 1024.0);
        Metrics(new JsonObject
        {
            ["kind"] = "generate", ["model"] = def.Id, ["op"] = req.Op, ["width"] = req.Width, ["height"] = req.Height, ["steps"] = req.Steps, ["count"] = req.Count,
            ["wallMs"] = wall.ElapsedMilliseconds, ["loadMs"] = loadMs, ["generateMs"] = stats["totalMs"]?.DeepClone(), ["encodeMs"] = stats["encodeMs"]?.DeepClone(),
            ["meanStepMs"] = stats["meanStepMs"]?.DeepClone(), ["peakAllocatedMb"] = stats["peakAllocatedMb"]?.DeepClone(),
            ["peakReservedMb"] = stats["peakReservedMb"]?.DeepClone(), ["freeMbAfter"] = r["mem"]?["freeMb"]?.DeepClone(), ["rssMb"] = r["rssMb"]?.DeepClone(),
            ["transformer"] = LoadedTransformer, ["textEncoder"] = LoadedTextEncoder, ["rewrite"] = prompt.RewriteMode, ["loras"] = loras.Count,
            ["post"] = string.Join('+', workflow),
        });
        var what = req.Op switch
        {
            "upscale" => "Imagem ampliada", "hd" => "Imagem em alta definição", "detail" => "Detalhes refeitos", "inpaint" => "Edição pronta",
            "outpaint" => "Imagem expandida", "img2img" => req.Count > 1 ? $"{req.Count} variações geradas" : "Variação gerada",
            _ => req.Count > 1 ? $"{req.Count} imagens geradas" : "Imagem gerada",
        };
        Activity?.Invoke($"{what} com {def.Name} em {wall.Elapsed.TotalSeconds:0.0} s", "task");
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

    /// <summary>
    /// Metadata completa de cada imagem (cifrada com DPAPI ao lado do PNG): prompts original e final, negativo, modelo
    /// (versão e hash), LoRAs (com hash e peso), seed, tamanho, sampler/scheduler, CFG/passos, operação e fluxo, datas e tempos.
    /// É o que permite RECRIAR exatamente a mesma imagem e fazer variações.
    /// </summary>
    private JsonObject Meta(ChatJob job, ImageRequest req, ImageModelDef def, CompiledPrompt prompt, List<(LoraEntry Entry, double Weight)> loras,
        List<string> workflow, string sampler, string schedule, int? clipSkip, double rescale, string id, int idx, JsonObject e, long wallMs, long loadMs) => new()
    {
        ["v"] = 2, ["id"] = id, ["job"] = job.Id, ["index"] = idx, ["createdAt"] = DateTimeOffset.Now.ToUnixTimeMilliseconds(),
        ["model"] = def.Id, ["modelName"] = def.Name, ["modelVersion"] = def.Version, ["modelRevision"] = Store.InstalledRevision(def) ?? def.Revision,
        ["modelSha256"] = def.Sha256, ["engine"] = def.Engine, ["family"] = def.Family,
        ["prompt"] = prompt.Original, ["finalPrompt"] = prompt.Positive, ["negative"] = prompt.Negative, ["strategy"] = prompt.Strategy,
        ["rewrite"] = prompt.RewriteMode, ["raw"] = req.Raw, ["userNegative"] = req.Negative, ["added"] = new JsonArray(prompt.Added.Select(a => (JsonNode)a).ToArray()),
        ["loras"] = new JsonArray(loras.Select(l => (JsonNode)new JsonObject
        {
            ["id"] = l.Entry.Id, ["name"] = l.Entry.Name, ["weight"] = l.Weight, ["sha256"] = l.Entry.Sha256, ["family"] = l.Entry.Family,
        }).ToArray()),
        ["width"] = e["width"]?.DeepClone(), ["height"] = e["height"]?.DeepClone(), ["genWidth"] = req.Width, ["genHeight"] = req.Height,
        ["steps"] = req.Steps, ["guidance"] = req.Guidance, ["seed"] = e["seed"]?.DeepClone(), ["baseSeed"] = req.Seed,
        ["sampler"] = def.Engine == "sdxl" ? sampler : null, ["schedule"] = def.Engine == "sdxl" ? schedule : null,
        ["clipSkip"] = clipSkip, ["guidanceRescale"] = def.Engine == "sdxl" ? rescale : null, ["prediction"] = def.Preset.Prediction,
        ["format"] = req.Format, ["quality"] = req.Quality, ["choice"] = req.Choice, ["autoNote"] = req.AutoNote,
        ["op"] = req.Op, ["workflow"] = new JsonArray(workflow.Select(w => (JsonNode)w).ToArray()), ["parent"] = req.Parent,
        ["source"] = req.Source, ["strength"] = req.Strength, ["control"] = req.ControlType, ["controlScale"] = req.ControlScale,
        ["referenceScale"] = req.Reference is null ? null : req.ReferenceScale ?? 0.5, ["upscale"] = e["upscale"]?.DeepClone(), ["hd"] = req.Hd,
        ["detail"] = e["detail"]?.DeepClone(), ["expand"] = req.Expand is null ? null : new JsonArray(req.Expand.Select(x => (JsonNode)x).ToArray()),
        ["ms"] = e["ms"]?.DeepClone(), ["wallMs"] = wallMs, ["loadMs"] = loadMs, ["device"] = job.DeviceId, ["meta"] = job.Meta?.DeepClone(),
        ["noc"] = CompanionHost.Version,
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

    public static string WorkerMessage(string code, ImageModelDef def, string? detail = null) => code switch
    {
        // mensagens que o próprio worker já escreve para a pessoa (pose não encontrada, máscara vazia...)
        "no_pose" or "lora_incompatible" or "lora_missing" or "bad_request" when !string.IsNullOrWhiteSpace(detail) && !detail.Contains("Error") => detail!,
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
            ["models"] = new JsonArray(ImageCatalog.Models.Select(m => (JsonNode)ModelJson(m)).ToArray()),
            ["aux"] = new JsonArray(ImageCatalog.AuxPackages.Select(m => (JsonNode)Store.ToJson(m)).ToArray()),
            ["installQueue"] = new JsonArray(InstallQueue.Select(i => (JsonNode)i).ToArray()),
            ["loraCount"] = Library.Loras.Count,
            ["imports"] = Library.ImportsJson(),
        };
    }

    /// <summary>Um modelo para o celular: estado da instalação + o que ele sabe fazer + preset.</summary>
    public JsonObject ModelJson(ImageModelDef m)
    {
        var o = Store.ToJson(m);
        var d = m.Defaults;
        o["ready"] = Ready(m);
        o["caps"] = new JsonObject
        {
            ["img2img"] = m.Caps.Img2Img, ["inpaint"] = m.Caps.Inpaint, ["outpaint"] = m.Caps.Outpaint, ["loras"] = m.Caps.Loras,
            ["controlnet"] = m.Caps.ControlNet, ["reference"] = m.Caps.Reference, ["negative"] = m.Caps.Negative,
        };
        o["steps"] = new JsonObject { ["min"] = d.StepsMin, ["max"] = d.StepsMax, ["presets"] = new JsonArray(d.Steps.Select(x => (JsonNode)x).ToArray()) };
        o["guidance"] = d.GuidanceAdjustable ? new JsonObject { ["min"] = d.GuidanceMin, ["max"] = d.GuidanceMax, ["default"] = d.Guidance } : null;
        o["maxPixels"] = d.MaxPixels;
        o["preset"] = ImageCatalog.PresetJson(m);
        o["vramGiB"] = Math.Round(Store.MeasuredPeak(m) ?? d.ResidentGiB + d.PeakExtraGiB, 1);
        o["vramMeasured"] = Store.MeasuredPeak(m) is not null;
        o["residentGiB"] = Store.MeasuredResident(m) is { } rg ? Math.Round(rg, 1) : null;
        o["resolution"] = m.NativeResolution;
        o["vae"] = m.Engine != "sdxl" ? null : m.BakedVae ? "embutido" : "emprestado de outro SDXL";
        o["prediction"] = m.Preset.Prediction;
        o["loadSeconds"] = Store.LoadSeconds(m);
        return o;
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
