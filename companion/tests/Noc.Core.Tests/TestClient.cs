using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading.Channels;
using Noc.Core.Crypto;

namespace Noc.Core.Tests;

/// <summary>Cliente do protocolo Noc para testes (espelha o que o app Android faz).</summary>
public sealed class TestClient : IAsyncDisposable
{
    private readonly ClientWebSocket _ws = new();
    private SecureChannel _ch = null!;
    private long _nextId;
    private readonly ConcurrentDictionary<long, TaskCompletionSource<JsonObject>> _pending = new();
    public Channel<JsonObject> Events { get; } = Channel.CreateUnbounded<JsonObject>();
    public JsonObject Welcome { get; private set; } = null!;
    public string Sas { get; private set; } = "";
    private Task? _reader;

    public static async Task<TestClient> ConnectAsync(string url, HandshakeMode mode, Func<byte[], JsonObject> auth, Action<string>? onSas = null)
    {
        var c = new TestClient();
        await c._ws.ConnectAsync(new Uri(url), CancellationToken.None);
        using var hs = new HandshakeClient(mode);
        await c._ws.SendAsync(hs.ClientHello, WebSocketMessageType.Binary, true, CancellationToken.None);
        var sh = await c.ReceiveAsync();
        var done = hs.Complete(sh);
        c._ch = done.Channel;
        c.Sas = done.Sas;
        onSas?.Invoke(done.Sas);
        var authMsg = auth(done.TranscriptHash);
        await c._ws.SendAsync(c._ch.Seal(Encoding.UTF8.GetBytes(authMsg.ToJsonString())), WebSocketMessageType.Binary, true, CancellationToken.None);
        var welcome = JsonNode.Parse(c._ch.Open(await c.ReceiveAsync()))!.AsObject();
        c.Welcome = welcome;
        if (welcome["k"]!.GetValue<string>() == "welcome") c._reader = Task.Run(c.ReadLoop);
        return c;
    }

    private async Task<byte[]> ReceiveAsync()
    {
        var buf = new byte[64 * 1024];
        using var ms = new MemoryStream();
        while (true)
        {
            var r = await _ws.ReceiveAsync(buf, CancellationToken.None);
            if (r.MessageType == WebSocketMessageType.Close) throw new IOException($"closed {_ws.CloseStatus} {_ws.CloseStatusDescription}");
            ms.Write(buf, 0, r.Count);
            if (r.EndOfMessage) return ms.ToArray();
        }
    }

    private async Task ReadLoop()
    {
        try
        {
            while (true)
            {
                var msg = JsonNode.Parse(_ch.Open(await ReceiveAsync()))!.AsObject();
                if (msg["t"]!.GetValue<string>() == "res" && _pending.TryRemove(msg["id"]!.GetValue<long>(), out var tcs)) tcs.SetResult(msg);
                else if (msg["t"]!.GetValue<string>() == "evt") await Events.Writer.WriteAsync(msg);
            }
        }
        catch (Exception e)
        {
            Events.Writer.TryComplete(e);
            foreach (var p in _pending.Values) p.TrySetException(e);
        }
    }

    private readonly SemaphoreSlim _send = new(1, 1);

    public async Task<JsonObject> CallAsync(string method, JsonObject? p = null, TimeSpan? timeout = null)
    {
        var id = Interlocked.Increment(ref _nextId);
        var tcs = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
        _pending[id] = tcs;
        var msg = new JsonObject { ["t"] = "req", ["id"] = id, ["m"] = method, ["p"] = p ?? new JsonObject() };
        await _send.WaitAsync();
        try { await _ws.SendAsync(_ch.Seal(Encoding.UTF8.GetBytes(msg.ToJsonString())), WebSocketMessageType.Binary, true, CancellationToken.None); }
        finally { _send.Release(); }
        return await tcs.Task.WaitAsync(timeout ?? TimeSpan.FromSeconds(30));
    }

    public static JsonObject PairAuth(byte[] th, ECDsa device, string pairingId, byte[] secret, bool code = false)
    {
        var spki = device.ExportSubjectPublicKeyInfo();
        var o = new JsonObject
        {
            ["k"] = "pair",
            ["pub"] = Convert.ToBase64String(spki),
            ["name"] = "Teste",
            ["model"] = "xunit",
            ["mac"] = Convert.ToBase64String(Handshake.PairMac(secret, th, spki)),
            ["sig"] = Convert.ToBase64String(HandshakeClient.SignClient(device, th)),
        };
        o[code ? "code" : "pairing"] = pairingId;
        return o;
    }

    public static JsonObject SessionAuth(byte[] th, ECDsa device) => new()
    {
        ["k"] = "auth",
        ["device"] = Wire.IdFromSpki(device.ExportSubjectPublicKeyInfo()),
        ["sig"] = Convert.ToBase64String(HandshakeClient.SignClient(device, th)),
    };

    public async ValueTask DisposeAsync()
    {
        try { await _ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", CancellationToken.None); } catch { }
        _ws.Dispose();
    }
}
