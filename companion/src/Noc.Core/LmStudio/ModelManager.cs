using System.Text.Json.Nodes;
using Noc.Core.Storage;

namespace Noc.Core.LmStudio;

public enum ModelOpState { Idle, Loading, Unloading }

/// <summary>
/// Serializa carregar/descarregar modelos e mantém a última leitura do LM Studio.
/// Com SingleModel ligado, descarrega os outros LLMs antes de carregar um novo, para não estourar a VRAM.
/// </summary>
public sealed class ModelManager
{
    private readonly LmStudioClient _lm;
    private readonly CompanionSettings _settings;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private LmProbe _last = new(LmState.Unknown, [], null);

    public ModelManager(LmStudioClient lm, CompanionSettings settings)
    {
        _lm = lm;
        _settings = settings;
    }

    public LmProbe Last => _last;
    public ModelOpState Op { get; private set; }
    public string? OpModel { get; private set; }
    public DateTimeOffset? OpStarted { get; private set; }

    /// <summary>Eventos de modelo para os celulares: {state, model, ...}.</summary>
    public event Action<JsonObject>? ModelEvent;
    public event Action? Changed;

    public async Task<LmProbe> RefreshAsync(CancellationToken ct = default)
    {
        var probe = await _lm.ProbeAsync(ct);
        var changed = probe.State != _last.State || Signature(probe) != Signature(_last);
        _last = probe;
        if (changed) Changed?.Invoke();
        return probe;
    }

    private static string Signature(LmProbe p) =>
        string.Join('|', p.Models.Select(m => m.Key + ":" + string.Join(',', m.Instances.Select(i => i.Id + "/" + i.ContextLength))));

    public LmModel? Find(string key) => _last.Models.FirstOrDefault(m => m.Key == key);

    /// <summary>Garante que o modelo esteja carregado. Devolve o id da instância e o contexto efetivo.</summary>
    public async Task<(string InstanceId, int Context)> EnsureLoadedAsync(string key, int? contextLength, CancellationToken ct)
    {
        var probe = await RefreshAsync(ct);
        if (probe.State != LmState.Running) throw new LmStudioException("lm_offline", "LM Studio não está em execução");
        var model = probe.Models.FirstOrDefault(m => m.Key == key)
                    ?? throw new LmStudioException("model_not_found", $"Modelo {key} não existe neste PC");
        if (model.Type != "llm") throw new LmStudioException("not_llm", "Este modelo não é de conversa");
        if (model.Loaded && (contextLength is null || model.Instances[0].ContextLength == contextLength))
            return (model.Instances[0].Id, model.Instances[0].ContextLength);

        await _gate.WaitAsync(ct);
        try
        {
            probe = await RefreshAsync(ct);
            model = probe.Models.First(m => m.Key == key);
            if (model.Loaded && (contextLength is null || model.Instances[0].ContextLength == contextLength))
                return (model.Instances[0].Id, model.Instances[0].ContextLength);

            var ctx = Math.Clamp(contextLength ?? _settings.DefaultContextLength, 2048, Math.Max(2048, model.MaxContext));
            SetOp(ModelOpState.Loading, key);
            Emit("loading", key, new JsonObject { ["context"] = ctx });
            try
            {
                // Mesmo modelo com outro contexto, ou política de um modelo por vez: descarrega antes.
                foreach (var other in probe.Models.Where(m => m.Loaded && m.Type == "llm" && (_settings.SingleModel || m.Key == key)))
                    foreach (var inst in other.Instances)
                    {
                        await _lm.UnloadAsync(inst.Id, ct);
                        Emit("unloaded", other.Key);
                    }
                var (instance, seconds, effective) = await _lm.LoadAsync(key, ctx, ct);
                await RefreshAsync(ct);
                Emit("loaded", key, new JsonObject { ["seconds"] = Math.Round(seconds, 1), ["context"] = effective });
                return (instance, effective);
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                Emit("failed", key, new JsonObject { ["error"] = e.Message });
                await RefreshAsync(CancellationToken.None);
                throw e as LmStudioException ?? new LmStudioException("load_failed", e.Message);
            }
            finally
            {
                SetOp(ModelOpState.Idle, null);
            }
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task UnloadAsync(string key, CancellationToken ct)
    {
        await _gate.WaitAsync(ct);
        try
        {
            var probe = await RefreshAsync(ct);
            var model = probe.Models.FirstOrDefault(m => m.Key == key);
            if (model is null || !model.Loaded) return;
            SetOp(ModelOpState.Unloading, key);
            foreach (var inst in model.Instances) await _lm.UnloadAsync(inst.Id, ct);
            await RefreshAsync(ct);
            Emit("unloaded", key);
        }
        finally
        {
            SetOp(ModelOpState.Idle, null);
            _gate.Release();
        }
    }

    private void SetOp(ModelOpState op, string? model)
    {
        Op = op;
        OpModel = model;
        OpStarted = op == ModelOpState.Idle ? null : DateTimeOffset.Now;
        Changed?.Invoke();
    }

    private void Emit(string state, string model, JsonObject? extra = null)
    {
        var o = extra ?? new JsonObject();
        o["state"] = state;
        o["model"] = model;
        ModelEvent?.Invoke(o);
    }
}
