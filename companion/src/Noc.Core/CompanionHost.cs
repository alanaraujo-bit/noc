using System.Collections.Concurrent;
using System.Reflection;
using System.Text.Json.Nodes;
using Noc.Core.Crypto;
using Noc.Core.Jobs;
using Noc.Core.LmStudio;
using Noc.Core.Net;
using Noc.Core.Platform;
using Noc.Core.Security;
using Noc.Core.Sessions;
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
    public ModelManager Models { get; }
    public JobManager Jobs { get; }
    public GpuMonitor Gpu { get; } = new();
    public LanServer Lan { get; }
    public RelayClient Relay { get; }

    private readonly ConcurrentDictionary<string, Session> _sessions = new();
    private readonly ConcurrentDictionary<string, (int Failures, DateTimeOffset Since, DateTimeOffset? BlockedUntil)> _failures = new();
    private readonly LinkedList<ActivityEntry> _activity = new();
    private readonly Lock _activityLock = new();
    private readonly CancellationTokenSource _cts = new();
    private Timer? _pollTimer;
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
        Models = new ModelManager(Lm, Settings);
        Jobs = new JobManager(Lm, Models);
        Lan = new LanServer(this);
        Relay = new RelayClient(this, () => Settings.RelayUrl);

        Models.Changed += OnStateChanged;
        Models.ModelEvent += e =>
        {
            Broadcast("model", e);
            var model = Models.Find(e["model"]!.GetValue<string>())?.DisplayName ?? e["model"]!.GetValue<string>();
            switch (e["state"]!.GetValue<string>())
            {
                case "loading": Activity($"Carregando {model}…", "model"); break;
                case "loaded": Activity($"{model} carregado em {e["seconds"]} s", "model"); break;
                case "unloaded": Activity($"{model} descarregado", "model"); break;
                case "failed": Activity($"Falha ao carregar {model}", "error"); break;
            }
        };
        Jobs.Changed += OnStateChanged;
        Gpu.Changed += OnStateChanged;
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
        if (probe.State == LmState.Stopped && Settings.AutoStartLmServer)
        {
            Activity("Iniciando o servidor do LM Studio…", "lm");
            _ = StartLmServerAsync(_cts.Token);
        }
        if (Settings.LanEnabled) await Lan.StartAsync(Settings.LanPort);
        if (Settings.RemoteEnabled) Relay.Start();
        _pollTimer = new Timer(async _ =>
        {
            try { await Models.RefreshAsync(_cts.Token); } catch { }
        }, null, TimeSpan.FromSeconds(5), TimeSpan.FromSeconds(5));
        Activity("Companion pronto", "info");
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
        };
    }

    public JsonObject BuildStatus()
    {
        var probe = Models.Last;
        var loaded = new JsonArray();
        foreach (var m in probe.Models.Where(m => m.Loaded && m.Type == "llm"))
            loaded.Add(new JsonObject
            {
                ["model"] = m.Key, ["name"] = m.DisplayName, ["context"] = m.Instances[0].ContextLength,
                ["vision"] = m.Vision,
            });
        var active = Jobs.Active;
        var generating = new JsonArray();
        foreach (var j in active)
            generating.Add(new JsonObject
            {
                ["job"] = j.Id, ["model"] = j.Model, ["state"] = j.State.ToString().ToLowerInvariant(),
                ["tps"] = j.LiveTokensPerSecond is { } t ? Math.Round(t, 1) : null, ["tokens"] = j.Tokens,
                ["mine"] = false,
            });
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
                ["since"] = Models.OpStarted?.ToUnixTimeMilliseconds(),
            },
            ["gpu"] = Gpu.Last?.ToJson(),
            ["jobs"] = generating,
            ["sessions"] = _sessions.Count,
            ["remote"] = Relay.State.ToString().ToLowerInvariant(),
            ["ts"] = DateTimeOffset.Now.ToUnixTimeMilliseconds(),
        };
    }

    public JsonArray ModelsJson()
    {
        var vramMb = Gpu.Last?.VramTotalMb ?? 0;
        var arr = new JsonArray();
        foreach (var m in Models.Last.Models)
        {
            var reasoning = new JsonArray();
            foreach (var o in m.ReasoningOptions) reasoning.Add(o);
            arr.Add(new JsonObject
            {
                ["key"] = m.Key,
                ["name"] = m.DisplayName,
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
                // Estimativa simples: pesos maiores que ~95% da VRAM não cabem inteiros na GPU.
                ["fitsGpu"] = vramMb > 0 ? m.SizeBytes / 1048576.0 < vramMb * 0.95 : null,
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
        Gpu.Dispose();
        Lm.Dispose();
        Identity.Dispose();
    }
}
