using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading.Channels;
using Noc.Core.Crypto;
using Noc.Core.Jobs;
using Noc.Core.LmStudio;
using Noc.Core.Security;

namespace Noc.Core.Sessions;

/// <summary>
/// Uma conexão autenticada com um celular, pela LAN ou pelo relay.
/// Tudo que passa daqui para a rede está cifrado com as chaves da sessão.
/// </summary>
public sealed class Session : IAsyncDisposable
{
    private const int MaxHandshakeFrame = 2048;
    private const int MaxFrame = 32 * 1024 * 1024;

    private readonly WebSocket _ws;
    private readonly ISessionHost _host;
    private readonly Channel<byte[]> _outbox = Channel.CreateUnbounded<byte[]>(new UnboundedChannelOptions { SingleReader = true });
    private readonly CancellationTokenSource _cts = new();
    private readonly Dictionary<string, IDisposable> _subs = new();
    private readonly Lock _subsLock = new();
    private SecureChannel? _channel;
    private bool _selfTest;

    public string Route { get; }
    public string SourceKey { get; }
    public string? DeviceId { get; private set; }
    public string DeviceName { get; private set; } = "";
    public DateTimeOffset ConnectedAt { get; } = DateTimeOffset.Now;
    public string Id { get; } = Guid.NewGuid().ToString("N")[..8];

    public Session(WebSocket ws, ISessionHost host, string route, string sourceKey)
    {
        _ws = ws;
        _host = host;
        Route = route;
        SourceKey = sourceKey;
    }

    public async Task RunAsync(CancellationToken outer)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(outer, _cts.Token);
        var ct = linked.Token;
        try
        {
            if (!await HandshakeAsync(ct)) return;
            _host.Register(this);
            var writer = Task.Run(() => WriterLoopAsync(ct), ct);
            await ReaderLoopAsync(ct);
            _cts.Cancel();
            await writer.ContinueWith(_ => { });
        }
        catch (OperationCanceledException) { }
        catch (WebSocketException) { }
        catch (ProtocolException e)
        {
            _host.Security.Write(SecurityLevel.Warning, "protocol", $"Conexão encerrada: frame inválido ({e.Code})", DeviceId, Route);
        }
        finally
        {
            _host.Unregister(this);
            await DisposeAsync();
        }
    }

    // ---------------- handshake ----------------

    private async Task<bool> HandshakeAsync(CancellationToken ct)
    {
        if (!_host.AllowHandshake(SourceKey))
        {
            await SendRawAsync([Wire.FrameError, .. "rate"u8], ct);
            await CloseAsync(WebSocketCloseStatus.PolicyViolation, "rate");
            return false;
        }

        using var helloTimeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        helloTimeout.CancelAfter(TimeSpan.FromSeconds(15));
        var hello = await ReceiveAsync(MaxHandshakeFrame, helloTimeout.Token);
        if (hello is null) return false;

        HandshakeResult hs;
        try
        {
            hs = Handshake.ServerRespond(hello, _host.Identity.Key, _host.Identity.PublicSpki);
        }
        catch (ProtocolException e)
        {
            _host.ReportFailure(SourceKey);
            await SendRawAsync([Wire.FrameError, .. Encoding.UTF8.GetBytes(e.Code)], ct);
            await CloseAsync(WebSocketCloseStatus.ProtocolError, e.Code);
            return false;
        }
        await SendRawAsync(hs.ServerHello, ct);
        _channel = hs.Channel;

        using var authTimeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        authTimeout.CancelAfter(TimeSpan.FromSeconds(30));
        var authFrame = await ReceiveAsync(MaxHandshakeFrame * 4, authTimeout.Token);
        if (authFrame is null) return false;
        var auth = JsonNode.Parse(_channel.Open(authFrame)) as JsonObject ?? throw new ProtocolException("bad_auth");

        var verdict = await AuthenticateAsync(hs, auth, ct);
        if (verdict is not null)
        {
            if (!_selfTest) _host.ReportFailure(SourceKey);
            await SendSealedAsync(new JsonObject { ["k"] = "denied", ["code"] = verdict }, ct);
            await CloseAsync(WebSocketCloseStatus.PolicyViolation, verdict);
            return false;
        }

        _host.Devices.Touch(DeviceId!, Route);
        await SendSealedAsync(new JsonObject
        {
            ["k"] = "welcome",
            ["device"] = DeviceId,
            ["proto"] = 1,
            ["pc"] = _host.PcInfo(),
            ["status"] = _host.BuildStatus(),
        }, ct);
        return true;
    }

    /// <summary>Devolve null se autenticado, ou o código de recusa.</summary>
    private async Task<string?> AuthenticateAsync(HandshakeResult hs, JsonObject auth, CancellationToken ct)
    {
        var kind = auth["k"]?.GetValue<string>();
        try
        {
            if (hs.Mode == HandshakeMode.Session && kind == "auth")
            {
                var id = auth["device"]!.GetValue<string>();
                var sig = Convert.FromBase64String(auth["sig"]!.GetValue<string>());
                var device = _host.Devices.Find(id);
                if (device is null)
                {
                    if (_host.IsSelfTest(id))
                    {
                        _host.Security.Write(SecurityLevel.Info, "selftest", "Autoteste do acesso remoto concluído", null, Route);
                        _selfTest = true;
                        return "unknown";
                    }
                    _host.Security.Write(SecurityLevel.Warning, "auth.unknown", "Tentativa de acesso de um aparelho não pareado", null, Route);
                    return "unknown";
                }
                if (device.Revoked)
                {
                    _host.Security.Write(SecurityLevel.Alert, "auth.revoked", $"Aparelho revogado tentou se conectar: {device.Name}", id, Route);
                    return "revoked";
                }
                if (!Handshake.VerifyClientSignature(Convert.FromBase64String(device.PublicKey), hs.TranscriptHash, sig))
                {
                    _host.Security.Write(SecurityLevel.Alert, "auth.badsig", $"Assinatura inválida para {device.Name}", id, Route);
                    return "unknown";
                }
                DeviceId = id;
                DeviceName = device.Name;
                return null;
            }

            if ((hs.Mode == HandshakeMode.PairQr && kind == "pair") || (hs.Mode == HandshakeMode.PairCode && kind == "pair"))
            {
                var spki = Convert.FromBase64String(auth["pub"]!.GetValue<string>());
                var sig = Convert.FromBase64String(auth["sig"]!.GetValue<string>());
                var mac = Convert.FromBase64String(auth["mac"]!.GetValue<string>());
                var name = auth["name"]?.GetValue<string>() ?? "Celular";
                var model = auth["model"]?.GetValue<string>() ?? "";
                if (!Handshake.VerifyClientSignature(spki, hs.TranscriptHash, sig)) return "bad_proof";

                var verdict = hs.Mode == HandshakeMode.PairQr
                    ? _host.Pairing.VerifyQr(auth["pairing"]!.GetValue<string>(), hs.TranscriptHash, spki, mac)
                    : await _host.Pairing.VerifyCodeAsync(auth["code"]!.GetValue<string>(), hs.TranscriptHash, spki, mac, name, model, hs.Sas, Route, ct);

                if (verdict != PairVerdict.Ok)
                {
                    _host.Security.Write(SecurityLevel.Warning, "pair.fail", $"Pareamento recusado ({verdict}) — {name}", null, Route);
                    return verdict switch
                    {
                        PairVerdict.Expired => "expired",
                        PairVerdict.Rejected => "rejected",
                        PairVerdict.BadProof => "bad_proof",
                        _ => "unknown_pairing",
                    };
                }
                var id = Wire.IdFromSpki(spki);
                var record = _host.Devices.Add(id, name, model, spki);
                _host.Security.Write(SecurityLevel.Info, "pair.ok", $"Novo aparelho pareado: {record.Name}", id, Route);
                DeviceId = id;
                DeviceName = record.Name;
                return null;
            }
        }
        catch (Exception e) when (e is FormatException or NullReferenceException or InvalidOperationException or KeyNotFoundException)
        {
            return "bad_request";
        }
        return "bad_request";
    }

    // ---------------- loop principal ----------------

    private async Task ReaderLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            var frame = await ReceiveAsync(MaxFrame, ct);
            if (frame is null) return;
            var plain = _channel!.Open(frame);
            JsonObject? msg;
            try { msg = JsonNode.Parse(plain) as JsonObject; }
            catch (JsonException) { continue; }
            if (msg?["t"]?.GetValue<string>() != "req") continue;
            var id = msg["id"]?.GetValue<long>() ?? 0;
            var method = msg["m"]?.GetValue<string>() ?? "";
            var p = msg["p"] as JsonObject ?? new JsonObject();
            _ = Task.Run(() => DispatchAsync(id, method, p, ct), ct);
        }
    }

    private async Task DispatchAsync(long id, string method, JsonObject p, CancellationToken ct)
    {
        try
        {
            var result = await HandleAsync(method, p, ct);
            Enqueue(new JsonObject { ["t"] = "res", ["id"] = id, ["ok"] = true, ["r"] = result ?? new JsonObject() });
        }
        catch (RpcException e)
        {
            Enqueue(Error(id, e.Code, e.Message));
        }
        catch (LmStudioException e)
        {
            Enqueue(Error(id, e.Code, e.Message));
        }
        catch (OperationCanceledException) { }
        catch (Exception e)
        {
            Enqueue(Error(id, "internal", e.Message));
        }
    }

    private static JsonObject Error(long id, string code, string msg) =>
        new() { ["t"] = "res", ["id"] = id, ["ok"] = false, ["e"] = new JsonObject { ["code"] = code, ["msg"] = msg } };

    private async Task<JsonNode?> HandleAsync(string method, JsonObject p, CancellationToken ct)
    {
        switch (method)
        {
            case "ping":
                return new JsonObject { ["t"] = DateTimeOffset.Now.ToUnixTimeMilliseconds() };

            case "status":
                await _host.Models.RefreshAsync(ct);
                return _host.BuildStatus();

            case "models.list":
                await _host.Models.RefreshAsync(ct);
                return new JsonObject { ["models"] = _host.ModelsJson() };

            case "models.load":
            {
                var key = ResolveModel(Str(p, "model"));
                int? ctx = p["context"]?.GetValue<int>();
                if (_host.Models.Find(key) is null) await _host.Models.RefreshAsync(ct);
                if (_host.Models.Find(key) is null) throw new RpcException("model_not_found", "Modelo não encontrado");
                if (_host.Jobs.Active.Any(j => j.Model != key && j.State != JobState.Queued))
                    throw new RpcException("busy", "Há uma resposta sendo gerada com outro modelo. A troca acontece quando ela terminar.");
                // Não amarra ao ciclo de vida da sessão: se o celular cair, o carregamento continua.
                _ = Task.Run(async () =>
                {
                    try { await _host.Models.EnsureLoadedAsync(key, ctx, CancellationToken.None, _host.Catalog.TierOf(key)); }
                    catch (Exception) { /* o evento "failed" já foi emitido */ }
                });
                _host.Security.Write(SecurityLevel.Info, "model.load", $"{DeviceName} pediu para carregar {_host.Models.DisplayName(key)}", DeviceId, Route);
                return new JsonObject { ["accepted"] = true, ["model"] = key };
            }

            case "models.unload":
            {
                var key = ResolveModel(Str(p, "model"));
                if (_host.Jobs.Active.Any(j => j.Model == key && j.State != JobState.Queued))
                    throw new RpcException("busy", "Este modelo está respondendo agora. Espere terminar ou pare a resposta.");
                await _host.Models.UnloadAsync(key, ct);
                return new JsonObject { ["ok"] = true };
            }

            case "models.update":
            {
                var key = Str(p, "model");
                if (_host.Models.Find(key) is null) throw new RpcException("model_not_found", "Modelo não encontrado");
                var prefs = _host.Catalog.Prefs(key);
                if (p.ContainsKey("alias")) prefs.Alias = p["alias"]?.GetValue<string>() is { } a && a.Trim().Length is > 0 and <= 40 ? a.Trim() : null;
                if (p["favorite"] is JsonValue fav) prefs.Favorite = fav.GetValue<bool>();
                if (p["hidden"] is JsonValue hid) prefs.Hidden = hid.GetValue<bool>();
                if (p.ContainsKey("context")) prefs.Context = p["context"]?.GetValue<int>() is int c and >= 2048 ? c : null;
                if (p.ContainsKey("reasoning")) prefs.Reasoning = p["reasoning"]?.GetValue<string>() is { } r && r is "auto" or "off" or "on" or "low" or "medium" or "high" ? r : null;
                if (p.ContainsKey("parallel")) prefs.Parallel = p["parallel"]?.GetValue<int>() is int par and >= 1 and <= 8 ? par : null;
                if (p["clearError"]?.GetValue<bool>() == true) prefs.LastError = null;
                _host.Catalog.Save();
                return new JsonObject { ["models"] = _host.ModelsJson() };
            }

            case "models.plan":
            {
                var key = ResolveModel(Str(p, "model"));
                var m = _host.Models.Find(key) ?? throw new RpcException("model_not_found", "Modelo não encontrado");
                var plan = await _host.Models.PlanAsync(m, _host.Catalog.TierOf(key), p["context"]?.GetValue<int>(), ct);
                return new JsonObject
                {
                    ["model"] = key, ["context"] = plan.Context, ["estimateGiB"] = plan.EstimateGiB is { } e ? Math.Round(e, 2) : null,
                    ["budgetGiB"] = plan.BudgetGiB is { } b ? Math.Round(b, 2) : null, ["fits"] = plan.Fits, ["note"] = plan.Note,
                    ["parallel"] = plan.Parallel, ["mtp"] = plan.Mtp,
                };
            }

            case "models.bench":
            {
                var key = ResolveModel(Str(p, "model"));
                if (_host.Models.Find(key) is null) throw new RpcException("model_not_found", "Modelo não encontrado");
                if (!_host.Bench.TryStart(key, out var why)) throw new RpcException("busy", why ?? "Não foi possível testar agora");
                return new JsonObject { ["accepted"] = true };
            }

            case "models.library":
                return new JsonObject { ["items"] = _host.LibraryJson(), ["folders"] = new JsonArray(_host.Library.Folders.Select(f => (JsonNode)f).ToArray()) };

            case "models.scan":
                _ = Task.Run(() => _host.Library.ScanAndImportAsync(CancellationToken.None));
                return new JsonObject { ["accepted"] = true };

            case "tiers.set":
            {
                var tier = Str(p, "tier");
                if (!Library.Tiers.All.Contains(tier)) throw new RpcException("bad_request", "perfil inválido");
                var key = p["model"]?.GetValue<string>();
                if (key is not null && _host.Models.Find(key) is null) throw new RpcException("model_not_found", "Modelo não encontrado");
                _host.Catalog.AssignTier(tier, key);
                return _host.BuildStatus();
            }

            case "models.default":
            {
                var key = p["model"]?.GetValue<string>();
                if (key is not null && _host.Models.Find(key) is null) throw new RpcException("model_not_found", "Modelo não encontrado");
                _host.Catalog.SetDefault(key, p["preload"]?.GetValue<bool>() ?? true);
                return _host.BuildStatus();
            }

            case "lms.start":
            {
                var (ok, output) = await _host.StartLmServerAsync(ct);
                if (!ok) throw new RpcException("lm_start_failed", output);
                return _host.BuildStatus();
            }

            case "chat.start":
            {
                var jobId = Str(p, "job");
                if (jobId.Length is < 8 or > 64) throw new RpcException("bad_request", "job inválido");
                var existingJob = _host.Jobs.Get(jobId);
                if (existingJob is not null)
                {
                    // mesmo pedido reenviado (queda de rede): é a mesma tarefa, nunca uma segunda geração
                    SubscribeJob(existingJob, p["from"]?.GetValue<int>() ?? 0);
                    return new JsonObject { ["job"] = existingJob.Id, ["existing"] = true, ["model"] = existingJob.Model };
                }
                var asked = Str(p, "model");
                var tier = asked.StartsWith("tier:", StringComparison.Ordinal) ? asked[5..] : p["tier"]?.GetValue<string>();
                var model = ResolveModel(asked);
                int? ctx = p["context"]?.GetValue<int>();
                var request = ChatRequestBuilder.Build(p);
                ApplyReasoningDefault(request, p, model, tier);
                var label = p["label"]?.GetValue<string>() is { } l ? (l.Length > 80 ? l[..80] : l) : null;
                var meta = p["meta"] as JsonObject;
                if (meta is not null && meta.ToJsonString().Length > 512) meta = null;
                var (job, existing) = _host.Jobs.Start(jobId, DeviceId!, model, ctx, request, tier, (JsonObject?)meta?.DeepClone(), label,
                    p["background"]?.GetValue<bool>() ?? false);
                SubscribeJob(job, p["from"]?.GetValue<int>() ?? 0);
                return new JsonObject { ["job"] = job.Id, ["existing"] = existing, ["model"] = model, ["position"] = _host.Jobs.QueuePosition(job) };
            }

            case "jobs.list":
            {
                var hours = Math.Clamp(p["hours"]?.GetValue<int>() ?? 24, 1, 24);
                var arr = new JsonArray();
                foreach (var j in _host.Jobs.Recent(TimeSpan.FromHours(hours)).Where(j => j.DeviceId == DeviceId || !j.IsFinished).Take(100))
                    arr.Add(_host.Jobs.Describe(j, DeviceId));
                return new JsonObject { ["jobs"] = arr };
            }

            case "jobs.get":
            {
                var ids = p["jobs"] as JsonArray ?? [];
                var arr = new JsonArray();
                foreach (var id in ids.Take(200))
                {
                    var j = id?.GetValue<string>() is { } s ? _host.Jobs.Get(s) : null;
                    arr.Add(j is null ? new JsonObject { ["job"] = id?.GetValue<string>(), ["state"] = "unknown" } : _host.Jobs.Describe(j, DeviceId));
                }
                return new JsonObject { ["jobs"] = arr };
            }

            case "jobs.prioritize":
            {
                var job = _host.Jobs.Get(Str(p, "job"));
                return new JsonObject { ["ok"] = job is not null && job.DeviceId == DeviceId && _host.Jobs.Prioritize(job) };
            }

            case "blob.has":
            {
                var missing = new JsonArray();
                var partial = new JsonObject();
                foreach (var h in (p["hashes"] as JsonArray ?? []).Take(32))
                {
                    var hash = h?.GetValue<string>() ?? "";
                    if (!BlobStore.ValidHash(hash)) continue;
                    var got = _host.Blobs.Received(hash);
                    if (got < 0) continue;
                    missing.Add(hash);
                    if (got > 0) partial[hash] = got;
                }
                return new JsonObject { ["missing"] = missing, ["partial"] = partial };
            }

            case "blob.put":
            {
                var hash = Str(p, "hash");
                var total = p["total"]?.GetValue<long>() ?? 0;
                var offset = p["offset"]?.GetValue<long>() ?? 0;
                var data = Convert.FromBase64String(Str(p, "data"));
                try
                {
                    var done = _host.Blobs.Put(hash, total, offset, data);
                    return new JsonObject { ["done"] = done, ["received"] = offset + data.Length };
                }
                catch (InvalidDataException e) { throw new RpcException("blob_corrupt", e.Message); }
                catch (ArgumentException e) { throw new RpcException("bad_request", e.Message); }
            }

            case "diag.log":
                return new JsonObject { ["entries"] = _host.Diag.Recent(Math.Clamp(p["max"]?.GetValue<int>() ?? 100, 1, 500), p["kind"]?.GetValue<string>()) };

            case "stt.status":
                return _host.Stt.ToJson();

            case "stt.prepare":
                if (!_host.Settings.VoiceEnabled) throw new RpcException("stt_off", "A transcrição de voz está desligada no PC.");
                _ = Task.Run(() => _host.Stt.EnsureReadyAsync(CancellationToken.None));
                return _host.Stt.ToJson();

            case "stt.begin":
            {
                if (!_host.Settings.VoiceEnabled) throw new RpcException("stt_off", "A transcrição de voz está desligada no PC.");
                var vocab = (p["vocab"] as JsonArray)?.Select(v => v?.GetValue<string>() ?? "").ToList();
                _host.Stt.Begin(Str(p, "id"), DeviceId!, vocab);
                // prepara o modelo enquanto a pessoa fala
                if (!_host.Stt.Ready) _ = Task.Run(() => _host.Stt.EnsureReadyAsync(CancellationToken.None));
                return _host.Stt.ToJson();
            }

            case "stt.chunk":
                try
                {
                    _host.Stt.Append(Str(p, "id"), DeviceId!, p["seq"]?.GetValue<int>() ?? 0, Convert.FromBase64String(Str(p, "data")));
                    return new JsonObject { ["ok"] = true };
                }
                catch (KeyNotFoundException) { throw new RpcException("stt_unknown", "Gravação não encontrada no PC"); }
                catch (InvalidDataException e) { throw new RpcException("stt_bad", e.Message); }

            case "stt.end":
                try
                {
                    // não depende da sessão: se o celular trocar de rede agora, o resultado é pedido de novo por stt.result
                    var id = Str(p, "id");
                    var r = await _host.Stt.EndAsync(id, DeviceId!, CancellationToken.None);
                    _host.Security.Write(SecurityLevel.Info, "stt", $"Ditado transcrito ({r.AudioMs / 1000.0:0.0} s de áudio em {r.ProcessMs} ms)", DeviceId, Route);
                    return new JsonObject
                    {
                        ["text"] = r.Text, ["empty"] = r.Empty, ["quiet"] = r.Quiet, ["audioMs"] = r.AudioMs, ["processMs"] = r.ProcessMs, ["hint"] = r.Hint,
                    };
                }
                catch (KeyNotFoundException) { throw new RpcException("stt_unknown", "Gravação não encontrada no PC"); }
                catch (InvalidOperationException e) { throw new RpcException("stt_unavailable", e.Message); }

            case "stt.cancel":
                _host.Stt.Cancel(Str(p, "id"));
                return new JsonObject { ["ok"] = true };

            case "chat.subscribe":
            {
                var jobId = Str(p, "job");
                var job = _host.Jobs.Get(jobId) ?? throw new RpcException("job_unknown", "Esta geração não existe mais no PC");
                _host.Diag.Write("tarefa", $"tarefa {(jobId.Length > 8 ? jobId[..8] : jobId)} acompanhada de novo pelo celular a partir do evento {p["from"]?.GetValue<int>() ?? 0} ({Route})");
                SubscribeJob(job, p["from"]?.GetValue<int>() ?? 0);
                return new JsonObject { ["job"] = job.Id, ["lastSeq"] = job.LastSeq, ["finished"] = job.IsFinished };
            }

            case "chat.cancel":
            {
                var job = _host.Jobs.Get(Str(p, "job"));
                if (job is not null && !job.IsFinished) _host.Jobs.Cancel(job);
                return new JsonObject { ["ok"] = job is not null, ["finished"] = job?.IsFinished };
            }

            case "devices.list":
                return new JsonObject { ["devices"] = _host.DevicesJson(DeviceId) };

            case "devices.revoke":
            {
                var target = Str(p, "id");
                var ok = _host.Devices.Revoke(target);
                if (ok) _host.Security.Write(SecurityLevel.Alert, "device.revoke", $"Aparelho revogado a partir de {DeviceName}", target, Route);
                return new JsonObject { ["ok"] = ok };
            }

            case "devices.rename":
                return new JsonObject { ["ok"] = _host.Devices.Rename(Str(p, "id"), Str(p, "name")) };

            case "pc.rename":
                _host.RenamePc(Str(p, "name"));
                return _host.PcInfo();

            case "security.log":
            {
                var arr = new JsonArray();
                foreach (var e in _host.Security.Recent(p["max"]?.GetValue<int>() ?? 50))
                    arr.Add(new JsonObject
                    {
                        ["at"] = e.At.ToUnixTimeMilliseconds(), ["level"] = e.Level.ToString().ToLowerInvariant(),
                        ["kind"] = e.Kind, ["msg"] = e.Message, ["device"] = e.Device, ["route"] = e.Route,
                    });
                return new JsonObject { ["events"] = arr };
            }

            default:
                throw new RpcException("unknown_method", method);
        }
    }

    private string ResolveModel(string modelOrTier)
    {
        try { return _host.Catalog.Resolve(modelOrTier); }
        catch (LmStudioException e) { throw new RpcException(e.Code, e.Message); }
    }

    /// <summary>Sem escolha explícita de raciocínio: vale a do modelo (modo avançado) ou a do perfil (Rápido desliga).</summary>
    private void ApplyReasoningDefault(JsonObject request, JsonObject p, string model, string? tier)
    {
        if ((p["params"] as JsonObject)?["reasoning"] is not null) return;
        var m = _host.Models.Find(model);
        var choice = _host.Catalog.Data.Models.TryGetValue(model, out var prefs) && prefs.Reasoning is { } r && r != "auto"
            ? r
            : Library.Tiers.DefaultReasoning(tier ?? _host.Catalog.TierOf(model));
        if (choice == "off" && m?.ReasoningOptions.Contains("off") == true) request["reasoning_effort"] = "none";
        else if (choice is "low" or "medium" or "high") request["reasoning_effort"] = choice;
    }

    private void SubscribeJob(ChatJob job, int from)
    {
        lock (_subsLock)
        {
            if (_subs.Remove(job.Id, out var old)) old.Dispose();
            _subs[job.Id] = job.Subscribe(from, e => Push("job", e));
        }
    }

    private static string Str(JsonObject p, string key) =>
        p[key]?.GetValue<string>() is { Length: > 0 } s ? s : throw new RpcException("bad_request", $"falta '{key}'");

    public void Push(string evt, JsonNode data) =>
        Enqueue(new JsonObject { ["t"] = "evt", ["e"] = evt, ["d"] = data });

    private void Enqueue(JsonObject msg) =>
        _outbox.Writer.TryWrite(Encoding.UTF8.GetBytes(msg.ToJsonString()));

    private async Task WriterLoopAsync(CancellationToken ct)
    {
        try
        {
            await foreach (var plain in _outbox.Reader.ReadAllAsync(ct))
            {
                var frame = _channel!.Seal(plain);
                await _ws.SendAsync(frame, WebSocketMessageType.Binary, true, ct);
            }
        }
        catch (Exception) when (ct.IsCancellationRequested || _ws.State != WebSocketState.Open)
        {
        }
        finally
        {
            _cts.Cancel();
        }
    }

    // ---------------- transporte ----------------

    private async Task<byte[]?> ReceiveAsync(int max, CancellationToken ct)
    {
        var buffer = new byte[16 * 1024];
        using var ms = new MemoryStream();
        while (true)
        {
            var r = await _ws.ReceiveAsync(buffer, ct);
            if (r.MessageType == WebSocketMessageType.Close) return null;
            if (r.MessageType != WebSocketMessageType.Binary) throw new ProtocolException("text_frame");
            ms.Write(buffer, 0, r.Count);
            if (ms.Length > max) throw new ProtocolException("too_large");
            if (r.EndOfMessage) return ms.ToArray();
        }
    }

    private Task SendRawAsync(byte[] data, CancellationToken ct) =>
        _ws.SendAsync(data, WebSocketMessageType.Binary, true, ct);

    private Task SendSealedAsync(JsonObject msg, CancellationToken ct) =>
        SendRawAsync(_channel!.Seal(Encoding.UTF8.GetBytes(msg.ToJsonString())), ct);

    private async Task CloseAsync(WebSocketCloseStatus status, string reason)
    {
        try
        {
            using var t = new CancellationTokenSource(TimeSpan.FromSeconds(2));
            await _ws.CloseOutputAsync(status, reason, t.Token);
        }
        catch (Exception) { }
    }

    /// <summary>Encerra a sessão (ex.: aparelho revogado ou Companion fechando).</summary>
    public async Task TerminateAsync(string reason)
    {
        if (_channel is not null && _ws.State == WebSocketState.Open)
        {
            try
            {
                using var t = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                await SendSealedAsyncLocked(new JsonObject { ["t"] = "evt", ["e"] = "bye", ["d"] = new JsonObject { ["reason"] = reason } }, t.Token);
            }
            catch (Exception) { }
        }
        await CloseAsync(WebSocketCloseStatus.NormalClosure, reason);
        _cts.Cancel();
    }

    // O writer pode estar parado; para o "bye" usamos a própria fila para manter a ordem dos contadores.
    private Task SendSealedAsyncLocked(JsonObject msg, CancellationToken ct)
    {
        Enqueue(msg);
        return Task.Delay(150, ct);
    }

    public async ValueTask DisposeAsync()
    {
        lock (_subsLock)
        {
            foreach (var s in _subs.Values) s.Dispose();
            _subs.Clear();
        }
        _outbox.Writer.TryComplete();
        if (_ws.State == WebSocketState.Open) await CloseAsync(WebSocketCloseStatus.NormalClosure, "bye");
        _ws.Dispose();
        _channel?.Dispose();
        _channel = null;
    }
}

public sealed class RpcException(string code, string message) : Exception(message)
{
    public string Code { get; } = code;
}
