using System.Net.WebSockets;
using System.Text;
using System.Text.Json.Nodes;
using Noc.Core.Crypto;
using Noc.Core.Sessions;

namespace Noc.Core.Net;

public enum RelayState { Disabled, Connecting, Online, Offline }

/// <summary>
/// Mantém o PC registrado no relay (conexão de controle) e aceita os canais que chegam dos celulares.
/// O relay só une dois sockets: o conteúdo continua cifrado ponta a ponta.
/// </summary>
public sealed class RelayClient : IAsyncDisposable
{
    private readonly ISessionHost _host;
    private readonly Func<string> _url;
    private CancellationTokenSource? _cts;
    private Task? _loop;
    private ClientWebSocket? _control;
    private readonly SemaphoreSlim _sendLock = new(1, 1);

    public RelayState State { get; private set; } = RelayState.Disabled;
    public string? LastError { get; private set; }
    public DateTimeOffset? OnlineSince { get; private set; }
    public TimeSpan? LastRtt { get; private set; }
    public event Action? Changed;
    public event Action<string>? PairCodeRegistered;

    public RelayClient(ISessionHost host, Func<string> url)
    {
        _host = host;
        _url = url;
    }

    public void Start()
    {
        if (_loop is not null) return;
        _cts = new CancellationTokenSource();
        _loop = Task.Run(() => LoopAsync(_cts.Token));
    }

    public async Task StopAsync()
    {
        _cts?.Cancel();
        if (_loop is not null) await _loop.ContinueWith(_ => { });
        _loop = null;
        Set(RelayState.Disabled, null);
    }

    private void Set(RelayState s, string? error)
    {
        State = s;
        LastError = error;
        OnlineSince = s == RelayState.Online ? DateTimeOffset.Now : null;
        Changed?.Invoke();
    }

    private async Task LoopAsync(CancellationToken ct)
    {
        var backoff = TimeSpan.FromSeconds(1);
        while (!ct.IsCancellationRequested)
        {
            Set(RelayState.Connecting, LastError);
            try
            {
                await RunControlAsync(ct);
                backoff = TimeSpan.FromSeconds(1);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                return;
            }
            catch (Exception e)
            {
                Set(RelayState.Offline, Friendly(e));
            }
            if (ct.IsCancellationRequested) return;
            if (State == RelayState.Online) Set(RelayState.Offline, LastError);
            try { await Task.Delay(backoff + TimeSpan.FromMilliseconds(Random.Shared.Next(0, 500)), ct); }
            catch (OperationCanceledException) { return; }
            backoff = TimeSpan.FromSeconds(Math.Min(30, backoff.TotalSeconds * 2));
        }
    }

    private static string Friendly(Exception e) => e switch
    {
        WebSocketException { InnerException: System.Net.Http.HttpRequestException h } => h.Message,
        _ => e.Message,
    };

    private async Task RunControlAsync(CancellationToken ct)
    {
        using var ws = new ClientWebSocket();
        ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(20);
        using (var connectTimeout = CancellationTokenSource.CreateLinkedTokenSource(ct))
        {
            connectTimeout.CancelAfter(TimeSpan.FromSeconds(15));
            await ws.ConnectAsync(new Uri(_url().TrimEnd('/') + "/v1/pc"), connectTimeout.Token);
        }
        _control = ws;
        try
        {
            var buffer = new byte[64 * 1024];
            using var pinger = new PeriodicTimer(TimeSpan.FromSeconds(25));
            var lastPing = DateTimeOffset.Now;
            var pingTask = Task.Run(async () =>
            {
                while (await pinger.WaitForNextTickAsync(ct))
                {
                    lastPing = DateTimeOffset.Now;
                    await SendJsonAsync(new JsonObject { ["t"] = "ping" }, ct);
                }
            }, ct);

            while (!ct.IsCancellationRequested)
            {
                var text = await ReceiveTextAsync(ws, buffer, ct);
                if (text is null) throw new WebSocketException($"relay fechou ({ws.CloseStatus} {ws.CloseStatusDescription})");
                var msg = JsonNode.Parse(text) as JsonObject;
                switch (msg?["t"]?.GetValue<string>())
                {
                    case "challenge":
                    {
                        var nonce = Convert.FromBase64String(msg["n"]!.GetValue<string>());
                        var sig = _host.Identity.Sign(Wire.Concat(Wire.LabelRelay, nonce));
                        await SendJsonAsync(new JsonObject
                        {
                            ["t"] = "auth",
                            ["pub"] = Convert.ToBase64String(_host.Identity.PublicSpki),
                            ["sig"] = Convert.ToBase64String(sig),
                        }, ct);
                        break;
                    }
                    case "ok":
                        if (msg["pcId"]?.GetValue<string>() != _host.Identity.PcId)
                            throw new InvalidOperationException("relay devolveu pcId diferente");
                        Set(RelayState.Online, null);
                        if (_host.Pairing.CurrentCode is { } code) await RegisterPairCodeAsync(code.Lookup);
                        break;
                    case "pong":
                        LastRtt = DateTimeOffset.Now - lastPing;
                        break;
                    case "incoming":
                    {
                        var ch = msg["ch"]!.GetValue<string>();
                        _ = Task.Run(() => AcceptAsync(ch, ct), ct);
                        break;
                    }
                    case "pair.registered":
                        PairCodeRegistered?.Invoke(msg["lookup"]!.GetValue<string>());
                        break;
                    case "pair.conflict":
                        // Colisão rara de código com outro PC: gera outro.
                        var fresh = _host.Pairing.NewCodeOffer();
                        await RegisterPairCodeAsync(fresh.Lookup);
                        break;
                }
            }
        }
        finally
        {
            _control = null;
        }
    }

    private async Task AcceptAsync(string ch, CancellationToken ct)
    {
        var ws = new ClientWebSocket();
        ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(20);
        try
        {
            using var t = CancellationTokenSource.CreateLinkedTokenSource(ct);
            t.CancelAfter(TimeSpan.FromSeconds(10));
            await ws.ConnectAsync(new Uri(_url().TrimEnd('/') + "/v1/accept?ch=" + Uri.EscapeDataString(ch)), t.Token);
        }
        catch (Exception)
        {
            ws.Dispose();
            return;
        }
        var session = new Session(ws, _host, "relay", "relay");
        await session.RunAsync(ct);
    }

    public async Task RegisterPairCodeAsync(string lookup)
    {
        if (_control is null || State != RelayState.Online) return;
        await SendJsonAsync(new JsonObject { ["t"] = "pair.register", ["lookup"] = lookup }, CancellationToken.None);
    }

    public async Task CancelPairCodeAsync()
    {
        if (_control is null) return;
        await SendJsonAsync(new JsonObject { ["t"] = "pair.cancel" }, CancellationToken.None);
    }

    private async Task SendJsonAsync(JsonObject o, CancellationToken ct)
    {
        var ws = _control;
        if (ws is null || ws.State != WebSocketState.Open) return;
        await _sendLock.WaitAsync(ct);
        try
        {
            await ws.SendAsync(Encoding.UTF8.GetBytes(o.ToJsonString()), WebSocketMessageType.Text, true, ct);
        }
        finally
        {
            _sendLock.Release();
        }
    }

    private static async Task<string?> ReceiveTextAsync(ClientWebSocket ws, byte[] buffer, CancellationToken ct)
    {
        using var ms = new MemoryStream();
        while (true)
        {
            var r = await ws.ReceiveAsync(buffer, ct);
            if (r.MessageType == WebSocketMessageType.Close) return null;
            ms.Write(buffer, 0, r.Count);
            if (ms.Length > 1024 * 1024) throw new InvalidOperationException("mensagem de controle grande demais");
            if (r.EndOfMessage) return Encoding.UTF8.GetString(ms.ToArray());
        }
    }

    public async ValueTask DisposeAsync() => await StopAsync();
}
