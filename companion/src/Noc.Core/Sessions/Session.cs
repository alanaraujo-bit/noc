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
                var key = Str(p, "model");
                int? ctx = p["context"]?.GetValue<int>();
                if (_host.Models.Find(key) is null) await _host.Models.RefreshAsync(ct);
                if (_host.Models.Find(key) is null) throw new RpcException("model_not_found", "Modelo não encontrado");
                // Não amarra ao ciclo de vida da sessão: se o celular cair, o carregamento continua.
                _ = Task.Run(async () =>
                {
                    try { await _host.Models.EnsureLoadedAsync(key, ctx, CancellationToken.None); }
                    catch (Exception) { /* o evento "failed" já foi emitido */ }
                });
                _host.Security.Write(SecurityLevel.Info, "model.load", $"{DeviceName} pediu para carregar {key}", DeviceId, Route);
                return new JsonObject { ["accepted"] = true };
            }

            case "models.unload":
            {
                var key = Str(p, "model");
                await _host.Models.UnloadAsync(key, ct);
                return new JsonObject { ["ok"] = true };
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
                var model = Str(p, "model");
                int? ctx = p["context"]?.GetValue<int>();
                var request = ChatRequestBuilder.Build(p);
                var (job, existing) = _host.Jobs.Start(jobId, DeviceId!, model, ctx, request);
                SubscribeJob(job, p["from"]?.GetValue<int>() ?? 0);
                return new JsonObject { ["job"] = job.Id, ["existing"] = existing };
            }

            case "chat.subscribe":
            {
                var jobId = Str(p, "job");
                var job = _host.Jobs.Get(jobId) ?? throw new RpcException("job_unknown", "Esta geração não existe mais no PC");
                SubscribeJob(job, p["from"]?.GetValue<int>() ?? 0);
                return new JsonObject { ["job"] = job.Id, ["lastSeq"] = job.LastSeq, ["finished"] = job.IsFinished };
            }

            case "chat.cancel":
            {
                var job = _host.Jobs.Get(Str(p, "job"));
                job?.Cancel();
                return new JsonObject { ["ok"] = job is not null };
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
