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

            case "image.status":
                return ImageCapabilities();

            case "image.prepare":
                _host.Images.Prepare(p["model"]?.GetValue<string>() is { Length: > 0 } pm ? "model:" + pm : p["engine"]?.GetValue<string>() ?? "fast");
                return new JsonObject { ["ok"] = true };

            case "image.start":
            {
                if (!_host.Images.Enabled) throw new RpcException("image_off", "A geração de imagens está desligada no PC.");
                var jobId = Str(p, "job");
                if (jobId.Length is < 8 or > 64 || !ImageEngine.ImageOutputs.ValidId(jobId + "-0")) throw new RpcException("bad_request", "job inválido");
                var existingJob = _host.Jobs.Get(jobId);
                if (existingJob is not null)
                {
                    SubscribeJob(existingJob, p["from"]?.GetValue<int>() ?? 0);
                    return new JsonObject { ["job"] = existingJob.Id, ["existing"] = true, ["model"] = existingJob.Model[6..] };
                }
                var req = BuildImageRequest(p);
                var label = p["label"]?.GetValue<string>() is { } l ? (l.Length > 80 ? l[..80] : l) : null;
                var meta = p["meta"] as JsonObject;
                if (meta is not null && meta.ToJsonString().Length > 512) meta = null;
                var (job, existing) = _host.Jobs.StartImage(jobId, DeviceId!, req, (JsonObject?)meta?.DeepClone(), label);
                SubscribeJob(job, p["from"]?.GetValue<int>() ?? 0);
                return new JsonObject
                {
                    ["job"] = job.Id, ["existing"] = existing, ["model"] = req.Model, ["name"] = ImageEngine.ImageCatalog.Find(req.Model)?.Name,
                    ["position"] = _host.Jobs.QueuePosition(job), ["width"] = req.Width, ["height"] = req.Height, ["steps"] = req.Steps,
                    ["seed"] = req.Seed, ["count"] = req.Count, ["note"] = req.AutoNote, ["op"] = req.Op, ["guidance"] = req.Guidance,
                };
            }

            case "image.get":
            {
                var id = Str(p, "id");
                var kind = p["kind"]?.GetValue<string>() == "thumb" ? "thumb" : "full";
                var offset = p["offset"]?.GetValue<long>() ?? 0;
                var length = Math.Clamp(p["length"]?.GetValue<int>() ?? 262_144, 1024, 1_048_576);
                try
                {
                    var r = _host.Images.Outputs.Read(id, kind, offset, length) ?? throw new RpcException("image_unknown", "Esta imagem não está mais no PC.");
                    return new JsonObject { ["id"] = id, ["kind"] = kind, ["offset"] = offset, ["total"] = r.Total, ["data"] = Convert.ToBase64String(r.Data) };
                }
                catch (ArgumentOutOfRangeException) { throw new RpcException("bad_request", "posição inválida"); }
            }

            case "image.meta":
            {
                var meta = _host.Images.Outputs.ReadMeta(Str(p, "id")) ?? throw new RpcException("image_unknown", "Esta imagem não está mais no PC.");
                // só quem pediu vê o prompt
                if (meta["device"]?.GetValue<string>() != DeviceId) throw new RpcException("forbidden", "Imagem de outro aparelho.");
                return meta;
            }

            case "image.delete":
            {
                var id = Str(p, "id");
                var meta = _host.Images.Outputs.ReadMeta(id);
                if (meta is not null && meta["device"]?.GetValue<string>() != DeviceId) throw new RpcException("forbidden", "Imagem de outro aparelho.");
                return new JsonObject { ["ok"] = _host.Images.Outputs.Delete(id) };
            }

            case "image.models":
                return _host.Images.ToJson();

            case "image.install":
            {
                var m = ImageEngine.ImageCatalog.Find(Str(p, "model")) ?? throw new RpcException("model_not_found", "Modelo de imagem desconhecido");
                if (m.Local) throw new RpcException("bad_request", "Modelo importado: nada a instalar.");
                _host.Images.QueueInstall(m);
                _host.Security.Write(SecurityLevel.Info, "image.install", $"{DeviceName} pediu para instalar {m.Name}", DeviceId, Route);
                return _host.Images.ToJson();
            }

            case "image.update":
            {
                var m = ImageEngine.ImageCatalog.Find(Str(p, "model")) ?? throw new RpcException("model_not_found", "Modelo de imagem desconhecido");
                var latest = _host.Images.Store.LatestRevision(m) ?? throw new RpcException("no_update", "Não há atualização para este modelo.");
                _host.Images.Store.StartInstall(m, latest);
                return _host.Images.ToJson();
            }

            case "image.pause":
            {
                var m = ImageEngine.ImageCatalog.Find(Str(p, "model")) ?? throw new RpcException("model_not_found", "Modelo de imagem desconhecido");
                _host.Images.Store.Pause(m);
                return _host.Images.ToJson();
            }

            case "image.verify":
            {
                var m = ImageEngine.ImageCatalog.Find(Str(p, "model")) ?? throw new RpcException("model_not_found", "Modelo de imagem desconhecido");
                _ = Task.Run(() => _host.Images.Store.VerifyInstalledAsync(m, CancellationToken.None));
                return _host.Images.ToJson();
            }

            case "image.token":
            {
                var (ok, message) = await _host.SetHfTokenAsync(p["token"]?.GetValue<string>());
                if (!ok) throw new RpcException("bad_token", message);
                return new JsonObject { ["ok"] = true, ["message"] = message, ["status"] = _host.Images.ToJson() };
            }

            case "image.list":
            {
                // galeria do PC: metadados das imagens deste aparelho (filtros e busca ficam no celular)
                var max = Math.Clamp(p["max"]?.GetValue<int>() ?? 200, 1, 2000);
                var arr = new JsonArray();
                foreach (var m in _host.Images.Outputs.ListMeta(DeviceId!, max)) arr.Add(m);
                return new JsonObject { ["images"] = arr };
            }

            case "lora.list":
            {
                var model = p["model"]?.GetValue<string>() is { } mid ? ImageEngine.ImageCatalog.Find(mid) : null;
                return new JsonObject
                {
                    ["loras"] = new JsonArray(_host.Images.Library.Loras.OrderByDescending(l => l.Favorite).ThenByDescending(l => l.LastUsed ?? l.AddedAt)
                        .Select(l => (JsonNode)LoraJson(l, model)).ToArray()),
                    ["imports"] = _host.Images.Library.ImportsJson(),
                };
            }

            case "lora.update":
            {
                if (!_host.Images.Library.Update(Str(p, "id"), p)) throw new RpcException("lora_missing", "LoRA não encontrado.");
                return LoraJson(_host.Images.Library.Find(Str(p, "id"))!, null);
            }

            case "lora.delete":
                _host.Security.Write(SecurityLevel.Info, "lora.delete", $"{DeviceName} removeu um LoRA", DeviceId, Route);
                return new JsonObject { ["ok"] = _host.Images.Library.Delete(Str(p, "id")) };

            case "image.import":
            {
                // link do Civitai / Hugging Face: o PC baixa, confere o hash e organiza (LoRA ou checkpoint)
                var url = Str(p, "url").Trim();
                if (url.Length > 1000 || !url.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) throw new RpcException("bad_url", "Use um link https.");
                var key = _host.Images.Library.StartImportUrl(url);
                _host.Security.Write(SecurityLevel.Info, "image.import", $"{DeviceName} pediu para importar um modelo por link", DeviceId, Route);
                return new JsonObject { ["key"] = key };
            }

            case "image.import.clear":
                _host.Images.Library.ClearImport(Str(p, "key"));
                return new JsonObject { ["ok"] = true };

            case "image.remove":
            {
                var m = ImageEngine.ImageCatalog.Find(Str(p, "model")) ?? throw new RpcException("model_not_found", "Modelo de imagem desconhecido");
                if (_host.Images.Loaded == m) throw new RpcException("busy", "Este modelo está em uso agora. Tente de novo em instantes.");
                _host.Images.Store.Delete(m);
                _host.Security.Write(SecurityLevel.Info, "image.remove", $"{DeviceName} removeu {m.Name}", DeviceId, Route);
                return _host.Images.ToJson();
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

    /// <summary>O que o celular pode oferecer na criação de imagem (só o que funciona de verdade nesta máquina).</summary>
    private JsonObject ImageCapabilities()
    {
        var o = _host.Images.ToJson();
        o["formats"] = new JsonArray(ImageEngine.ImageFormats.All.Select(f => (JsonNode)new JsonObject
        {
            ["id"] = f.Id, ["label"] = f.Label, ["width"] = f.Width, ["height"] = f.Height,
        }).ToArray());
        o["qualities"] = new JsonArray(
            new JsonObject { ["id"] = "fast", ["label"] = "Rápida" },
            new JsonObject { ["id"] = "standard", ["label"] = "Padrão" },
            new JsonObject { ["id"] = "high", ["label"] = "Alta" });
        o["counts"] = new JsonArray(1, 2, 4);
        // compatível com o app 1.2: só os dois motores da fase 3 ("fast"/"quality")
        var engines = new JsonArray();
        foreach (var m in new[] { ImageEngine.ImageCatalog.ZImageTurbo, ImageEngine.ImageCatalog.FluxDev })
        {
            var d = m.Defaults;
            engines.Add(new JsonObject
            {
                ["choice"] = m.Profile, ["model"] = m.Id, ["name"] = m.Name, ["label"] = m.Profile == "fast" ? "Rápido" : "Qualidade",
                ["installed"] = _host.Images.Store.IsInstalled(m),
                ["steps"] = new JsonObject { ["min"] = d.StepsMin, ["max"] = d.StepsMax, ["presets"] = new JsonArray(d.Steps.Select(x => (JsonNode)x).ToArray()) },
                ["guidance"] = d.GuidanceAdjustable ? new JsonObject { ["min"] = d.GuidanceMin, ["max"] = d.GuidanceMax, ["default"] = d.Guidance } : null,
                ["maxPixels"] = d.MaxPixels,
            });
        }
        o["engines"] = engines;
        o["samplers"] = new JsonArray(ImageEngine.Samplers.All.Select(s => (JsonNode)new JsonObject { ["id"] = s.Id, ["label"] = s.Label }).ToArray());
        o["schedules"] = new JsonArray(ImageEngine.Samplers.Schedules.Select(s => (JsonNode)new JsonObject { ["id"] = s.Id, ["label"] = s.Label }).ToArray());
        var aux = ImageEngine.ImageCatalog.AuxPackages.ToDictionary(a => a.Id, a => _host.Images.Store.IsInstalled(a));
        o["tools"] = new JsonObject
        {
            ["controlnet"] = aux["controlnet-union-sdxl"], ["pose"] = aux["controlnet-union-sdxl"] && aux["vitpose-base"] && aux["rtdetr-r50"],
            ["depth"] = aux["controlnet-union-sdxl"] && aux["depth-anything-v2-small"], ["canny"] = aux["controlnet-union-sdxl"],
            ["reference"] = aux["ip-adapter-sdxl"], ["detail"] = aux["grounding-dino-tiny"], ["upscale"] = aux["realesrgan"],
        };
        return o;
    }

    private static readonly string[] ImageOps = ["txt2img", "img2img", "inpaint", "outpaint", "upscale", "detail", "hd"];

    /// <summary>
    /// Valida e completa o pedido de imagem: modelo (escolhido, automático ou o da imagem de origem), operação, formato,
    /// qualidade → passos, sampler/CFG do preset do modelo (ou do Estúdio), LoRAs, controles e seed. "recreate" repete
    /// uma imagem exatamente (mesmo prompt final, modelo, LoRAs, seed e parâmetros).
    /// </summary>
    private ImageEngine.ImageRequest BuildImageRequest(JsonObject p)
    {
        var images = _host.Images;
        if (p["recreate"]?.GetValue<string>() is { Length: > 0 } recreateId) return RecreateRequest(recreateId, p);

        var op = p["op"]?.GetValue<string>() is { } o0 && ImageOps.Contains(o0) ? o0 : "txt2img";
        var prompt = (p["prompt"]?.GetValue<string>() ?? "").Trim();
        if (prompt.Length > 4000) throw new RpcException("bad_request", "Descrição longa demais (máx. 4000 caracteres).");
        var source = ImageRef(p["source"]?.GetValue<string>());
        if (op != "txt2img" && source is null) throw new RpcException("bad_request", "Falta a imagem a editar.");
        JsonObject? srcMeta = source is not null && source.StartsWith("img:") ? images.Outputs.ReadMeta(source[4..]) : null;
        if (srcMeta is not null && srcMeta["device"]?.GetValue<string>() != DeviceId) srcMeta = null; // prompt de outro aparelho não vaza
        if (prompt.Length == 0) prompt = srcMeta?["prompt"]?.GetValue<string>() ?? "";
        if (prompt.Length == 0 && op is "txt2img" or "img2img" or "inpaint" or "outpaint")
            throw new RpcException("bad_request", "Descreva o que você quer na imagem.");

        // modelo: escolhido (id) | fast | quality | auto
        var choice = p["model"]?.GetValue<string>() is { Length: > 0 } mid ? "model:" + mid
            : p["engine"]?.GetValue<string>() is "quality" or "auto" ? p["engine"]!.GetValue<string>() : "fast";
        if (op != "txt2img" && choice is "fast" or "quality" && p["model"] is null && p["engine"] is null) choice = "auto";
        var quality = p["quality"]?.GetValue<string>() is "fast" or "high" ? p["quality"]!.GetValue<string>() : "standard";
        var count = p["count"]?.GetValue<int>() is 2 or 4 ? p["count"]!.GetValue<int>() : 1;
        if (op is "upscale" or "hd" or "detail" or "inpaint" or "outpaint") count = Math.Min(count, op == "inpaint" ? 4 : 1);

        // LoRAs (sem sintaxe: id + peso)
        var loraUses = new List<ImageEngine.LoraUse>();
        foreach (var l in (p["loras"] as JsonArray ?? []).OfType<JsonObject>().Take(5))
        {
            var id = l["id"]?.GetValue<string>() ?? "";
            var e = images.Library.Find(id) ?? throw new RpcException("lora_missing", "Um dos LoRAs escolhidos não está na biblioteca do PC.");
            loraUses.Add(new ImageEngine.LoraUse(e.Id, Math.Round(Math.Clamp(l["weight"]?.GetValue<double>() ?? e.Weight, -2, 2), 2)));
        }
        var loraFamily = loraUses.Count > 0 ? images.Library.Find(loraUses[0].Id)!.Family : null;

        // tamanho: formato (criação) ou o da imagem de origem (edição)
        var formatId = p["format"]?.GetValue<string>() ?? "square";
        int w, h;
        if (op == "txt2img")
        {
            if (ImageEngine.ImageFormats.Find(formatId) is { } f) (w, h) = (f.Width, f.Height);
            else
            {
                formatId = "custom";
                w = p["width"]?.GetValue<int>() ?? 1024;
                h = p["height"]?.GetValue<int>() ?? 1024;
            }
        }
        else
        {
            formatId = "source";
            (w, h) = SourceSize(source!, p);
        }
        var sourceModel = images.ModelOfImage(source);
        var (def, note, error) = images.Choose(choice, quality, prompt, op == "txt2img" ? (long)w * h : 1024 * 1024, loraFamily, sourceModel, op);
        // modelo escolhido à mão que não faz esta edição: o automático acha quem faz (e explica)
        if (def is not null && op != "txt2img" && !ImageEngine.ImageRouter.CanDo(def, op))
            (def, note, error) = images.Choose("auto", quality, prompt, 1024 * 1024, loraFamily, def.Id, op);
        if (def is null)
        {
            var (code, msg) = error switch
            {
                { } e when e.StartsWith("flux_unavailable") => ("model_not_installed", "O FLUX.1 Dev ainda não foi liberado neste PC (precisa da sua conta do Hugging Face)."),
                { } e when e.StartsWith("model_not_installed") => ("model_not_installed", $"O {ImageEngine.ImageCatalog.Find(e.Split(':')[1])?.Name ?? "modelo"} ainda não está instalado no PC."),
                "model_unknown" => ("model_not_found", "Este modelo de imagem não existe neste PC."),
                "too_large" => ("bad_request", "Esse tamanho é grande demais para este modelo."),
                _ => ("model_not_installed", "Nenhum modelo de imagem instalado no PC."),
            };
            throw new RpcException(code, msg);
        }
        if (!ImageEngine.ImageRouter.CanDo(def, op))
            throw new RpcException("unsupported", $"Nenhum modelo instalado no PC faz esta edição ainda.");
        var controlType = p["control"]?["type"]?.GetValue<string>() is "pose" or "depth" or "canny" ? p["control"]!["type"]!.GetValue<string>() : null;
        var reference = ImageRef(p["reference"]?["source"]?.GetValue<string>());
        if ((controlType is not null || reference is not null) && def.Engine != "sdxl")
            throw new RpcException("unsupported", "Pose, estrutura e imagem de referência funcionam nos modelos especialistas (SDXL).");
        if (loraUses.Count > 0)
            foreach (var u in loraUses)
            {
                var (ok, warn) = ImageEngine.ImageLibrary.Compatible(images.Library.Find(u.Id)!, def);
                if (!ok) throw new RpcException("lora_incompatible", warn ?? "LoRA incompatível com este modelo.");
            }

        var d = def.Defaults;
        if (op == "txt2img") (w, h) = ImageEngine.ImageFormats.Snap(w, h, d.MaxPixels);
        var steps = p["steps"]?.GetValue<int>() is int st ? Math.Clamp(st, d.StepsMin, d.StepsMax) : d.Steps[quality switch { "fast" => 0, "high" => 2, _ => 1 }];
        var guidance = d.GuidanceAdjustable && p["guidance"]?.GetValue<double>() is double g ? Math.Clamp(g, d.GuidanceMin, d.GuidanceMax) : d.Guidance;
        long seed = p["seed"]?.GetValue<long>() is long sd and >= 0 and < int.MaxValue - 8 ? sd : System.Security.Cryptography.RandomNumberGenerator.GetInt32(0, int.MaxValue - 8);
        var expand = (p["expand"] as JsonArray)?.Select(x => Math.Clamp((x?.GetValue<int>() ?? 0) / 8 * 8, 0, 1536)).ToArray();
        if (op == "outpaint" && (expand is not { Length: 4 } || expand.Sum() == 0)) throw new RpcException("bad_request", "Escolha para que lado expandir.");
        var detail = (p["detail"] as JsonArray)?.Select(x => x?.GetValue<string>()).Where(x => x is "face" or "hand").Select(x => x!).Distinct().ToArray();
        return new ImageEngine.ImageRequest
        {
            Prompt = prompt, Choice = choice.StartsWith("model:") ? "model" : choice, Model = def.Id, Width = w, Height = h, Format = formatId,
            Quality = quality, Steps = steps, Guidance = guidance, Count = count, Seed = seed, Enhance = p["enhance"]?.GetValue<bool>() ?? false,
            AutoNote = choice == "auto" || note is not null ? note : null, Op = op,
            Negative = p["negative"]?.GetValue<string>() is { } neg && neg.Length <= 2000 ? neg : null, Raw = p["raw"]?.GetValue<bool>() ?? false,
            Sampler = ImageEngine.Samplers.Valid(p["sampler"]?.GetValue<string>()) && def.Engine == "sdxl" ? p["sampler"]!.GetValue<string>() : null,
            Schedule = ImageEngine.Samplers.ValidSchedule(p["schedule"]?.GetValue<string>()) && def.Engine == "sdxl" ? p["schedule"]!.GetValue<string>() : null,
            ClipSkip = p["clipSkip"]?.GetValue<int>() is int cs and >= 1 and <= 4 && def.Engine == "sdxl" ? cs : null,
            GuidanceRescale = p["guidanceRescale"]?.GetValue<double>() is double gr && def.Engine == "sdxl" ? Math.Clamp(gr, 0, 1) : null,
            Strength = p["strength"]?.GetValue<double>() is double s ? Math.Clamp(s, 0.05, 1) : null,
            Source = source, Mask = ImageRef(p["mask"]?.GetValue<string>()), Expand = op == "outpaint" ? expand : null,
            ControlType = controlType, ControlSource = ImageRef(p["control"]?["source"]?.GetValue<string>()),
            ControlScale = p["control"]?["scale"]?.GetValue<double>(), Reference = reference, ReferenceScale = p["reference"]?["scale"]?.GetValue<double>(),
            Detail = detail is { Length: > 0 } ? detail : null, Upscale = p["upscale"]?.GetValue<int>() is 2 or 4 ? p["upscale"]!.GetValue<int>() : op == "hd" ? 2 : 0,
            Hd = op == "hd" || (p["hd"]?.GetValue<bool>() ?? false), Loras = loraUses,
            Parent = source is not null && source.StartsWith("img:") ? source[4..] : null,
        };
    }

    /// <summary>"img:&lt;id&gt;" (imagem do PC) ou "blob:&lt;sha&gt;" (enviada pelo celular); qualquer outra coisa é recusada.</summary>
    private string? ImageRef(string? r)
    {
        if (string.IsNullOrEmpty(r)) return null;
        if (r.StartsWith("img:") && ImageEngine.ImageOutputs.ValidId(r[4..]))
        {
            if (_host.Images.Outputs.BaseOf(r[4..]) is null) throw new RpcException("image_unknown", "A imagem não está mais no PC.");
            return r;
        }
        if (r.StartsWith("blob:") && Jobs.BlobStore.ValidHash(r[5..]))
        {
            if (!_host.Blobs.Has(r[5..])) throw new RpcException("blob_missing", "A imagem enviada não chegou ao PC. Tente de novo.");
            return r;
        }
        throw new RpcException("bad_request", "Referência de imagem inválida.");
    }

    private (int W, int H) SourceSize(string source, JsonObject p)
    {
        if (source.StartsWith("img:") && _host.Images.Outputs.BaseOf(source[4..]) is { } b)
            try
            {
                using var fs = File.OpenRead(b + ".png");
                var x = new byte[24];
                fs.ReadExactly(x);
                return ((x[16] << 24) | (x[17] << 16) | (x[18] << 8) | x[19], (x[20] << 24) | (x[21] << 16) | (x[22] << 8) | x[23]);
            }
            catch (Exception) { }
        return (Math.Clamp(p["width"]?.GetValue<int>() ?? 1024, 64, 8192), Math.Clamp(p["height"]?.GetValue<int>() ?? 1024, 64, 8192));
    }

    /// <summary>Recriar: mesmo modelo, prompt final, negativo, LoRAs, seed, tamanho e parâmetros (não recompila).</summary>
    private ImageEngine.ImageRequest RecreateRequest(string id, JsonObject p)
    {
        var m = _host.Images.Outputs.ReadMeta(id) ?? throw new RpcException("image_unknown", "Esta imagem não está mais no PC.");
        if (m["device"]?.GetValue<string>() != DeviceId) throw new RpcException("forbidden", "Imagem de outro aparelho.");
        var op = m["op"]?.GetValue<string>() ?? "txt2img";
        if (op == "inpaint") throw new RpcException("unsupported", "Edições com pincel não podem ser recriadas exatamente (a máscara não fica guardada).");
        var def = ImageEngine.ImageCatalog.Find(m["model"]?.GetValue<string>() ?? "") ?? throw new RpcException("model_not_found", "O modelo desta imagem não existe mais no PC.");
        if (!_host.Images.Ready(def)) throw new RpcException("model_not_installed", $"O {def.Name} não está instalado no PC.");
        if (def.Sha256 is not null && m["modelSha256"]?.GetValue<string>() is { } sha && sha != def.Sha256)
            throw new RpcException("model_changed", $"O {def.Name} instalado não é mais a mesma versão desta imagem.");
        var loras = new List<ImageEngine.LoraUse>();
        foreach (var l in (m["loras"] as JsonArray ?? []).OfType<JsonObject>())
        {
            var e = _host.Images.Library.Find(l["id"]!.GetValue<string>());
            if (e is null || e.Sha256 != l["sha256"]?.GetValue<string>())
                throw new RpcException("lora_missing", $"O LoRA \"{l["name"]}\" desta imagem não está mais na biblioteca.");
            loras.Add(new ImageEngine.LoraUse(e.Id, l["weight"]!.GetValue<double>()));
        }
        var source = m["source"]?.GetValue<string>();
        if (source is not null && (!source.StartsWith("img:") || _host.Images.Outputs.BaseOf(source[4..]) is null))
            throw new RpcException("source_missing", "A imagem de origem desta edição não está mais no PC.");
        T? V<T>(string k) => m[k] is JsonValue v && v.TryGetValue<T>(out var r) ? r : default;
        return new ImageEngine.ImageRequest
        {
            Prompt = V<string>("prompt") ?? "", Choice = "model", Model = def.Id, Width = V<int?>("genWidth") ?? V<int>("width"), Height = V<int?>("genHeight") ?? V<int>("height"),
            Format = V<string>("format") ?? "square", Quality = V<string>("quality") ?? "standard", Steps = V<int>("steps"), Guidance = V<double>("guidance"),
            Count = 1, Seed = V<long>("seed"), Op = op, Raw = V<bool>("raw"), Sampler = V<string>("sampler"), Schedule = V<string>("schedule"),
            ClipSkip = V<int?>("clipSkip"), GuidanceRescale = V<double?>("guidanceRescale"), Strength = V<double?>("strength"), Source = source,
            Expand = (m["expand"] as JsonArray)?.Select(x => x!.GetValue<int>()).ToArray(), ControlType = null,
            Upscale = V<int?>("upscale") ?? 0, Hd = V<bool>("hd"),
            Detail = (m["workflow"] as JsonArray)?.Any(x => x?.GetValue<string>() == "detail") == true ? ["face", "hand"] : null,
            Loras = loras, FinalPrompt = V<string>("finalPrompt"), FinalNegative = V<string>("negative") ?? "", Parent = id,
            AutoNote = "Recriada com os mesmos parâmetros",
        };
    }

    /// <summary>Um LoRA para o celular, com a compatibilidade em relação ao modelo pedido (quando houver).</summary>
    private JsonObject LoraJson(ImageEngine.LoraEntry l, ImageEngine.ImageModelDef? model)
    {
        var (ok, warn) = model is null ? (true, (string?)null) : ImageEngine.ImageLibrary.Compatible(l, model);
        return l.ToJson(ok, warn);
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
