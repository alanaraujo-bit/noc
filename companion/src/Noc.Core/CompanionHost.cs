using System.Collections.Concurrent;
using System.Reflection;
using System.Text.Json.Nodes;
using Noc.Core.Crypto;
using Noc.Core.Jobs;
using Noc.Core.Library;
using Noc.Core.LmStudio;
using Noc.Core.Net;
using Noc.Core.Platform;
using Noc.Core.Security;
using Noc.Core.Sessions;
using Noc.Core.Speech;
using Noc.Core.Storage;

namespace Noc.Core;

public sealed record ActivityEntry(DateTimeOffset At, string Text, string Kind);

/// <summary>
/// Orquestra o Companion: identidade, LM Studio, sessões (LAN + relay), pareamento e estado para a UI.
/// </summary>
public sealed class CompanionHost : ISessionHost, IAsyncDisposable
{
    public static string Version { get; } =
        Assembly.GetExecutingAssembly().GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion.Split('+')[0] ?? "1.0.0";

    public CompanionSettings Settings { get; }
    public PcIdentity Identity { get; }
    public DeviceStore Devices { get; } = new();
    public PairingManager Pairing { get; } = new();
    public SecurityLog Security { get; } = new();
    public LmStudioClient Lm { get; }
    public ModelCatalog Catalog { get; }
    public ModelManager Models { get; }
    public ModelLibrary Library { get; }
    public BlobStore Blobs { get; } = new();
    public SttService Stt { get; } = new();
    public JobManager Jobs { get; }
    public Benchmark Bench { get; }
    public GpuMonitor Gpu { get; } = new();
    public Diagnostics.DiagLog Diag { get; } = new();

    /// <summary>Recursos que este Companion oferece (o celular se adapta a Companions antigos).</summary>
    public static readonly string[] Features = ["tiers", "blobs", "stt", "jobs2", "bench", "library"];
    public LanServer Lan { get; }
    public RelayClient Relay { get; }

    private readonly ConcurrentDictionary<string, Session> _sessions = new();
    private readonly ConcurrentDictionary<string, (int Failures, DateTimeOffset Since, DateTimeOffset? BlockedUntil)> _failures = new();
    private readonly LinkedList<ActivityEntry> _activity = new();
    private readonly Lock _activityLock = new();
    private readonly CancellationTokenSource _cts = new();
    private Timer? _pollTimer;
    private int _polling;
    private DateTimeOffset _lastLmRestart = DateTimeOffset.MinValue;
    private int _lmRestartFailures;
    private bool _preloadDone;
    private LmState _lastLmState = LmState.Unknown;
    private Timer? _statusDebounce;
    private int _handshakesThisMinute;
    private DateTimeOffset _minuteStart = DateTimeOffset.Now;

    /// <summary>Qualquer mudança relevante para a UI do PC.</summary>
    public event Action? Changed;
    public event Action<ActivityEntry>? ActivityAdded;

    public CompanionHost()
    {
        Settings = CompanionSettings.Load();
        Identity = PcIdentity.LoadOrCreate();
        Lm = new LmStudioClient(() => Settings.LmStudioPort > 0 ? Settings.LmStudioPort : LmStudioLocator.ConfiguredPort());
        Catalog = new ModelCatalog();
        // Enquanto a voz não está carregada, reserva a VRAM dela para o modelo não ocupar tudo.
        Models = new ModelManager(Lm, Settings, Catalog, Gpu, () => Settings.VoiceEnabled && !Stt.Ready ? 1.3 : 0);
        Library = new ModelLibrary(() => Settings.ExtraModelFolders, () => Settings.AllowComponentDownloads);
        Jobs = new JobManager(Lm, Models, Blobs);
        Bench = new Benchmark(Lm, Models, Jobs, Gpu);
        Lan = new LanServer(this);
        Relay = new RelayClient(this, () => Settings.RelayUrl);

        Models.Changed += () =>
        {
            Catalog.AutoAssign(Models.Last.Models, (Gpu.Last?.VramTotalMb ?? 0) / 1024.0);
            OnStateChanged();
        };
        Catalog.Changed += () => Changed?.Invoke();
        Models.ModelEvent += e =>
        {
            Broadcast("model", e);
            var model = e["name"]?.GetValue<string>() ?? e["model"]!.GetValue<string>();
            switch (e["state"]!.GetValue<string>())
            {
                case "loading" when e["retry"] is null: Activity($"Carregando {model}…", "model"); break;
                case "loaded": Activity($"{model} pronto (carregado em {e["seconds"]} s)", "model"); break;
                case "unloaded": Activity($"{model} descarregado", "model"); break;
                case "failed": Activity($"Não foi possível carregar {model}: {e["error"]}", "error"); break;
            }
        };
        Library.Changed += () => { Broadcast("library", new JsonObject { ["items"] = LibraryJson() }); Changed?.Invoke(); };
        Library.Activity += Activity;
        Library.Imported += () => _ = Task.Run(async () =>
        {
            // o LM Studio leva uns segundos para indexar o arquivo novo
            for (var i = 0; i < 5; i++) { await Task.Delay(2000); try { await Models.RefreshAsync(_cts.Token); } catch { } }
        });
        Models.RawLog += m => Diag.Write("modelo", m);
        Jobs.Trace += m => Diag.Write("tarefa", m);
        Stt.Log += (m, _) => Diag.Write("voz", m);
        Models.ModelEvent += e => Diag.Write("modelo", e.ToJsonString());
        Stt.Changed += OnStateChanged;
        Stt.Log += Activity;
        Jobs.Changed += OnStateChanged;
        Jobs.Log += Activity;
        Bench.Update += e => { Broadcast("bench", e); Changed?.Invoke(); };
        Bench.Log += Activity;
        Gpu.Changed += () =>
        {
            Catalog.AutoAssign(Models.Last.Models, (Gpu.Last?.VramTotalMb ?? 0) / 1024.0);
            OnStateChanged();
        };
        Relay.Changed += () =>
        {
            if (Relay.State == RelayState.Online) Activity("Acesso remoto ativo", "net");
            else if (Relay.State == RelayState.Offline) Activity("Acesso remoto indisponível — tentando de novo", "warn");
            Changed?.Invoke();
        };
        Devices.Revoked += id =>
        {
            foreach (var s in _sessions.Values.Where(s => s.DeviceId == id))
                _ = s.TerminateAsync("revoked");
        };
        Devices.Changed += () => Changed?.Invoke();
        Pairing.OffersChanged += () => Changed?.Invoke();
    }

    public async Task StartAsync()
    {
        Security.Write(SecurityLevel.Info, "start", $"Companion iniciado (v{Version})");
        var probe = await Models.RefreshAsync();
        _lastLmState = probe.State;
        Task? lmStart = null;
        if (probe.State == LmState.Stopped && Settings.AutoStartLmServer)
        {
            Activity("Iniciando o servidor do LM Studio…", "lm");
            lmStart = StartLmServerAsync(_cts.Token);
        }
        if (Settings.LanEnabled) await Lan.StartAsync(Settings.LanPort);
        if (Settings.RemoteEnabled) Relay.Start();
        // Tarefas que estavam na fila ou gerando quando o PC/Companion desligou.
        Jobs.RecoverFromDisk();
        _pollTimer = new Timer(_ => _ = HealthTickAsync(), null, TimeSpan.FromSeconds(5), TimeSpan.FromSeconds(5));
        _ = Task.Run(async () =>
        {
            try
            {
                if (lmStart is not null) await lmStart;
                // ordem importa para a VRAM: primeiro a voz (pequena), depois o modelo padrão
                if (Settings.VoiceEnabled) await Stt.EnsureReadyAsync(_cts.Token);
                await PreloadAsync();
                if (Settings.AutoImportModels)
                {
                    Library.Watch();
                    await Library.ScanAndImportAsync(_cts.Token);
                }
            }
            catch (OperationCanceledException) { }
            catch (Exception e) { Activity("Falha na preparação inicial: " + e.Message, "error"); }
        });
        Activity("Companion pronto", "info");
    }

    /// <summary>
    /// Deixa o modelo padrão carregado para a primeira mensagem sair rápido. Não briga com o usuário:
    /// não recarrega se ele descarregou de propósito, e não troca um modelo que já está carregado.
    /// </summary>
    public async Task PreloadAsync()
    {
        var def = Catalog.Data.DefaultModel;
        if (!Catalog.Data.Preload || def is null || Models.UserUnloaded || Jobs.Active.Count > 0) return;
        var probe = await Models.RefreshAsync(_cts.Token);
        if (probe.State != LmState.Running || probe.Models.Any(m => m.Loaded && m.Type == "llm")) { _preloadDone = probe.State == LmState.Running; return; }
        if (Models.Find(def) is null) return;
        _preloadDone = true;
        Activity($"Deixando {Models.DisplayName(def)} pronto para você", "model");
        try { await Models.EnsureLoadedAsync(def, null, _cts.Token, Catalog.TierOf(def)); }
        catch (LmStudioException) { /* o evento "failed" já explica */ }
    }

    /// <summary>Monitor de saúde (a cada 5 s): lê o LM Studio, religa o servidor se ele cair e refaz o pré-carregamento.</summary>
    private async Task HealthTickAsync()
    {
        if (Interlocked.Exchange(ref _polling, 1) == 1) return;
        try
        {
            var probe = await Models.RefreshAsync(_cts.Token);
            if (probe.State != _lastLmState)
            {
                if (probe.State == LmState.Running && _lastLmState != LmState.Unknown) Activity("O LM Studio voltou a responder", "lm");
                else if (_lastLmState == LmState.Running) Activity("O LM Studio parou de responder", "warn");
                _lastLmState = probe.State;
            }
            // Só religa quando o servidor está de fato fora (porta fechada), ou travado há 2 min sem nada rodando.
            // Nunca durante uma carga de modelo ou geração: religar o servidor mata o que está em andamento.
            var hung = probe.Busy && Models.ConsecutiveBusy >= 24;
            var idle = Models.Op == ModelOpState.Idle && Jobs.Active.All(j => j.State == JobState.Queued) && !Bench.Busy;
            if ((probe.State == LmState.Stopped || hung) && idle && Settings.KeepLmServerAlive && Settings.AutoStartLmServer)
            {
                if (hung) Diag.Write("lm", "LM Studio sem responder há 2 min; religando o servidor");
                // espera crescente entre tentativas (30 s, 60 s, 120 s… até 10 min)
                var wait = TimeSpan.FromSeconds(Math.Min(600, 30 * Math.Pow(2, Math.Min(5, _lmRestartFailures))));
                if (DateTimeOffset.Now - _lastLmRestart > wait)
                {
                    _lastLmRestart = DateTimeOffset.Now;
                    Activity("Religando o servidor do LM Studio…", "lm");
                    var (ok, _) = await StartLmServerAsync(_cts.Token);
                    _lmRestartFailures = ok ? 0 : _lmRestartFailures + 1;
                    if (ok) _preloadDone = false;
                }
            }
            if (probe.State == LmState.Running && !_preloadDone && Models.Op == ModelOpState.Idle) await PreloadAsync();
        }
        catch (Exception) { }
        finally { Interlocked.Exchange(ref _polling, 0); }
    }

    // ---------------- estado ----------------

    private void OnStateChanged()
    {
        Changed?.Invoke();
        // agrupa mudanças rápidas num único evento de status para os celulares
        _statusDebounce?.Dispose();
        _statusDebounce = new Timer(_ => Broadcast("status", BuildStatus()), null, 300, Timeout.Infinite);
    }

    public IReadOnlyList<Session> Sessions => _sessions.Values.ToList();

    public IReadOnlyList<ActivityEntry> RecentActivity
    {
        get { lock (_activityLock) return _activity.Reverse().ToList(); }
    }

    public void Activity(string text, string kind)
    {
        var e = new ActivityEntry(DateTimeOffset.Now, text, kind);
        lock (_activityLock)
        {
            _activity.AddLast(e);
            while (_activity.Count > 200) _activity.RemoveFirst();
        }
        ActivityAdded?.Invoke(e);
    }

    public IReadOnlyList<string> LanEndpoints =>
        Lan.Running ? NetworkInfo.LanAddresses().Select(ip => $"{ip}:{Lan.Port}").ToList() : [];

    public JsonObject PcInfo()
    {
        var lan = new JsonArray();
        foreach (var e in LanEndpoints) lan.Add(e);
        return new JsonObject
        {
            ["id"] = Identity.PcId,
            ["name"] = Settings.PcName,
            ["version"] = Version,
            ["os"] = Environment.OSVersion.VersionString,
            ["lan"] = lan,
            ["relay"] = Settings.RemoteEnabled ? Settings.RelayUrl : null,
            ["features"] = new JsonArray(Features.Select(f => (JsonNode)f).ToArray()),
        };
    }

    public JsonArray LibraryJson()
    {
        var arr = new JsonArray();
        foreach (var i in Library.Items.Take(30)) arr.Add(i.ToJson());
        return arr;
    }

    private JsonObject TiersJson()
    {
        var o = new JsonObject();
        foreach (var t in Tiers.All)
        {
            var key = Catalog.ModelForTier(t);
            o[t] = key is null ? null : new JsonObject { ["model"] = key, ["name"] = Models.DisplayName(key), ["label"] = Tiers.Label(t) };
        }
        return o;
    }

    public JsonObject BuildStatus()
    {
        var probe = Models.Last;
        var loaded = new JsonArray();
        foreach (var m in probe.Models.Where(m => m.Loaded && m.Type == "llm"))
            loaded.Add(new JsonObject
            {
                ["model"] = m.Key, ["name"] = Catalog.DisplayName(m, probe.Models), ["context"] = m.Instances[0].ContextLength,
                ["vision"] = m.Vision, ["tier"] = Catalog.TierOf(m.Key),
            });
        var generating = new JsonArray();
        foreach (var j in Jobs.Active)
        {
            var d = Jobs.Describe(j);
            d["device"] = j.DeviceId;
            generating.Add(d);
        }
        var lastDone = Jobs.Recent(TimeSpan.FromHours(24)).FirstOrDefault(j => j.IsFinished && j.EndStats?["tps"] is not null);
        return new JsonObject
        {
            ["lm"] = new JsonObject
            {
                ["state"] = probe.State switch
                {
                    LmState.Running => "running",
                    LmState.Stopped => "stopped",
                    LmState.NotInstalled => "not_installed",
                    _ => "unknown",
                },
                ["error"] = probe.State == LmState.Running ? null : probe.Error,
                ["llms"] = probe.Models.Count(m => m.Type == "llm"),
            },
            ["loaded"] = loaded,
            ["op"] = Models.Op == ModelOpState.Idle ? null : new JsonObject
            {
                ["kind"] = Models.Op == ModelOpState.Loading ? "loading" : "unloading",
                ["model"] = Models.OpModel,
                ["name"] = Models.OpModel is { } om ? Models.DisplayName(om) : null,
                ["since"] = Models.OpStarted?.ToUnixTimeMilliseconds(),
                ["expectedSeconds"] = Models.OpExpectedSeconds,
            },
            ["gpu"] = Gpu.Last?.ToJson(),
            ["jobs"] = generating,
            ["tiers"] = TiersJson(),
            ["defaultModel"] = Catalog.Data.DefaultModel,
            ["stt"] = Settings.VoiceEnabled ? Stt.ToJson() : new JsonObject { ["state"] = "off" },
            ["bench"] = Bench.Busy ? new JsonObject { ["model"] = Bench.RunningModel, ["step"] = Bench.Step, ["progress"] = Bench.Progress } : null,
            ["importing"] = Library.Busy,
            ["last"] = lastDone is null ? null : new JsonObject
            {
                ["model"] = lastDone.Model, ["name"] = Models.DisplayName(lastDone.Model),
                ["tps"] = lastDone.EndStats!["tps"]?.DeepClone(), ["ttftMs"] = lastDone.EndStats["ttftMs"]?.DeepClone(),
                ["at"] = lastDone.FinishedAt?.ToUnixTimeMilliseconds(),
            },
            ["sessions"] = _sessions.Count,
            ["remote"] = Relay.State.ToString().ToLowerInvariant(),
            ["companion"] = Version,
            ["ts"] = DateTimeOffset.Now.ToUnixTimeMilliseconds(),
        };
    }

    public JsonArray ModelsJson()
    {
        var vramMb = Gpu.Last?.VramTotalMb ?? 0;
        var arr = new JsonArray();
        var all = Models.Last.Models;
        foreach (var m in all)
        {
            var reasoning = new JsonArray();
            foreach (var o in m.ReasoningOptions) reasoning.Add(o);
            var prefs = Catalog.Data.Models.TryGetValue(m.Key, out var p) ? p : new ModelPrefs();
            // cabe na GPU? usa a estimativa do LM Studio (8k) se já calculada; senão, o tamanho do arquivo
            Catalog.Data.Estimates.TryGetValue($"{m.Key}|{m.SizeBytes}|8192", out var est8k);
            bool? fits = vramMb <= 0 ? null : est8k > 0 ? est8k < vramMb / 1024.0 - 0.8 : m.SizeBytes / 1048576.0 < vramMb * 0.95;
            arr.Add(new JsonObject
            {
                ["key"] = m.Key,
                ["name"] = Catalog.DisplayName(m, all),
                ["technical"] = m.DisplayName,
                ["alias"] = prefs.Alias,
                ["favorite"] = prefs.Favorite,
                ["hidden"] = prefs.Hidden,
                ["tier"] = Catalog.TierOf(m.Key),
                ["isDefault"] = Catalog.Data.DefaultModel == m.Key,
                ["lastError"] = prefs.LastError,
                ["lastLoadSeconds"] = prefs.LastLoadSeconds,
                ["bench"] = prefs.Benchmark?.DeepClone(),
                ["perf"] = new JsonObject { ["context"] = prefs.Context, ["reasoning"] = prefs.Reasoning, ["parallel"] = prefs.Parallel },
                ["type"] = m.Type,
                ["arch"] = m.Architecture,
                ["quant"] = m.Quantization,
                ["size"] = m.SizeBytes,
                ["params"] = m.Params,
                ["maxContext"] = m.MaxContext,
                ["vision"] = m.Vision,
                ["toolUse"] = m.ToolUse,
                ["reasoning"] = reasoning,
                ["reasoningDefault"] = m.ReasoningDefault,
                ["loaded"] = m.Loaded,
                ["context"] = m.Loaded ? m.Instances[0].ContextLength : null,
                ["fitsGpu"] = fits,
            });
        }
        return arr;
    }

    public JsonArray DevicesJson(string? currentDevice)
    {
        var online = _sessions.Values.Select(s => s.DeviceId).ToHashSet();
        var arr = new JsonArray();
        foreach (var d in Devices.All.OrderByDescending(d => d.LastSeen))
            arr.Add(new JsonObject
            {
                ["id"] = d.Id, ["name"] = d.Name, ["model"] = d.Model,
                ["pairedAt"] = d.PairedAt.ToUnixTimeMilliseconds(),
                ["lastSeen"] = d.LastSeen?.ToUnixTimeMilliseconds(),
                ["route"] = d.LastRoute, ["revoked"] = d.Revoked,
                ["current"] = d.Id == currentDevice, ["online"] = online.Contains(d.Id),
            });
        return arr;
    }

    public void RenamePc(string name)
    {
        var clean = new string(name.Where(c => !char.IsControl(c)).ToArray()).Trim();
        if (clean.Length is 0 or > 40) throw new RpcException("bad_request", "Nome inválido");
        Settings.PcName = clean;
        Settings.Save();
        Changed?.Invoke();
        Broadcast("pc", PcInfo());
    }

    public async Task<(bool Ok, string Output)> StartLmServerAsync(CancellationToken ct)
    {
        var (ok, output) = await LmStudioLocator.StartServerAsync(ct);
        for (var i = 0; i < 20 && ok; i++)
        {
            var probe = await Models.RefreshAsync(ct);
            if (probe.State == LmState.Running)
            {
                Activity("Servidor do LM Studio ativo", "lm");
                return (true, output);
            }
            await Task.Delay(500, ct);
        }
        Activity("Não foi possível iniciar o LM Studio", "error");
        return (false, string.IsNullOrWhiteSpace(output) ? "O LM Studio não respondeu" : output);
    }

    // ---------------- sessões ----------------

    public void Register(Session s)
    {
        _sessions[s.Id] = s;
        Activity($"{s.DeviceName} conectou ({(s.Route == "lan" ? "rede local" : "remoto")})", "net");
        Security.Write(SecurityLevel.Info, "session.open", $"{s.DeviceName} conectou", s.DeviceId, s.Route);
        OnStateChanged();
    }

    public void Unregister(Session s)
    {
        if (!_sessions.TryRemove(s.Id, out _)) return;
        Activity($"{s.DeviceName} desconectou", "net");
        OnStateChanged();
    }

    public void Broadcast(string evt, JsonNode data)
    {
        foreach (var s in _sessions.Values) s.Push(evt, data.DeepClone());
    }

    /// <summary>Encerra todas as sessões de um aparelho sem revogá-lo (ele pode reconectar).</summary>
    public async Task DisconnectDeviceAsync(string deviceId)
    {
        foreach (var s in _sessions.Values.Where(s => s.DeviceId == deviceId)) await s.TerminateAsync("kicked");
    }

    public bool AllowHandshake(string sourceKey)
    {
        var now = DateTimeOffset.Now;
        lock (_failures)
        {
            if (now - _minuteStart > TimeSpan.FromMinutes(1)) { _minuteStart = now; _handshakesThisMinute = 0; }
            if (++_handshakesThisMinute > 120) return false;
        }
        if (_failures.TryGetValue(sourceKey, out var f) && f.BlockedUntil is { } until && until > now)
            return false;
        return true;
    }

    public void ReportFailure(string sourceKey)
    {
        var now = DateTimeOffset.Now;
        _failures.AddOrUpdate(sourceKey,
            _ => (1, now, null),
            (_, f) =>
            {
                if (now - f.Since > TimeSpan.FromMinutes(5)) return (1, now, null);
                var count = f.Failures + 1;
                // relay agrega todos os celulares remotos; limite maior para não bloquear o dono
                var limit = sourceKey == "relay" ? 30 : 10;
                if (count >= limit)
                {
                    Security.Write(SecurityLevel.Alert, "rate.block", $"Muitas tentativas falhas ({sourceKey}). Bloqueado por 5 min.");
                    return (count, f.Since, now + TimeSpan.FromMinutes(5));
                }
                return (count, f.Since, f.BlockedUntil);
            });
    }

    private readonly ConcurrentDictionary<string, DateTimeOffset> _selfTests = new();

    /// <summary>Anuncia a identidade descartável que o autoteste vai usar (válida por 30 s).</summary>
    public void ExpectSelfTest(string deviceId) => _selfTests[deviceId] = DateTimeOffset.Now + TimeSpan.FromSeconds(30);

    public bool IsSelfTest(string deviceId) => _selfTests.TryRemove(deviceId, out var until) && until > DateTimeOffset.Now;

    // ---------------- pareamento ----------------

    /// <summary>Conteúdo do QR Code de pareamento (também funciona como link noc://).</summary>
    public string BuildPairingUri(QrOffer offer)
    {
        var q = new List<string>
        {
            "v=1",
            "k=" + Wire.B64Url(Identity.PublicSpki),
            "p=" + offer.PairingId,
            "s=" + Wire.B64Url(offer.Secret),
            "n=" + Uri.EscapeDataString(Settings.PcName),
        };
        if (Settings.RemoteEnabled) q.Add("r=" + Uri.EscapeDataString(Settings.RelayUrl));
        if (LanEndpoints.Count > 0) q.Add("l=" + Uri.EscapeDataString(string.Join(',', LanEndpoints)));
        return "noc://pair?" + string.Join('&', q);
    }

    public async Task<CodeOffer> NewPairCodeAsync()
    {
        var offer = Pairing.NewCodeOffer();
        await Relay.RegisterPairCodeAsync(offer.Lookup);
        return offer;
    }

    public async Task ApplySettingsAsync()
    {
        Settings.Save();
        if (Settings.LanEnabled && !Lan.Running) await Lan.StartAsync(Settings.LanPort);
        if (!Settings.LanEnabled && Lan.Running) await Lan.StopAsync();
        if (Settings.RemoteEnabled && Relay.State == RelayState.Disabled) Relay.Start();
        if (!Settings.RemoteEnabled && Relay.State != RelayState.Disabled) await Relay.StopAsync();
        Changed?.Invoke();
        Broadcast("pc", PcInfo());
    }

    public async ValueTask DisposeAsync()
    {
        _cts.Cancel();
        _pollTimer?.Dispose();
        _statusDebounce?.Dispose();
        foreach (var s in _sessions.Values) await s.TerminateAsync("shutdown");
        await Relay.DisposeAsync();
        await Lan.DisposeAsync();
        Jobs.Dispose();
        Library.Dispose();
        Stt.Dispose();
        Blobs.Dispose();
        Gpu.Dispose();
        Lm.Dispose();
        Identity.Dispose();
    }
}
