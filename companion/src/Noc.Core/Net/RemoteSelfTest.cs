using System.Diagnostics;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Noc.Core.Crypto;

namespace Noc.Core.Net;

/// <summary>
/// Autoteste do acesso remoto: o próprio PC se conecta a ele mesmo pelo relay, como um celular faria,
/// usando uma identidade descartável. O handshake completo acontece e o Companion recusa o aparelho
/// desconhecido — o que prova que o caminho internet → relay → PC → criptografia está funcionando.
/// </summary>
public static class RemoteSelfTest
{
    public static async Task<string> RunAsync(CompanionHost host)
    {
        if (!host.Settings.RemoteEnabled) return "Acesso remoto está desligado nos ajustes.";
        if (host.Relay.State != RelayState.Online) return "O PC não está conectado ao serviço remoto agora.";
        var sw = Stopwatch.StartNew();
        using var ws = new ClientWebSocket();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(20));
        try
        {
            await ws.ConnectAsync(new Uri(host.Settings.RelayUrl.TrimEnd('/') + "/v1/connect?pc=" + host.Identity.PcId), cts.Token);
            using var hs = new HandshakeClient(HandshakeMode.Session);
            await ws.SendAsync(hs.ClientHello, WebSocketMessageType.Binary, true, cts.Token);
            var done = hs.Complete(await ReceiveAsync(ws, cts.Token));
            if (!done.PcSpki.AsSpan().SequenceEqual(host.Identity.PublicSpki)) return "Falhou: a identidade que respondeu não é a deste PC.";
            using var throwaway = ECDsa.Create(ECCurve.NamedCurves.nistP256);
            host.ExpectSelfTest(Wire.IdFromSpki(throwaway.ExportSubjectPublicKeyInfo()));
            var auth = new JsonObject
            {
                ["k"] = "auth",
                ["device"] = Wire.IdFromSpki(throwaway.ExportSubjectPublicKeyInfo()),
                ["sig"] = Convert.ToBase64String(HandshakeClient.SignClient(throwaway, done.TranscriptHash)),
            };
            await ws.SendAsync(done.Channel.Seal(Encoding.UTF8.GetBytes(auth.ToJsonString())), WebSocketMessageType.Binary, true, cts.Token);
            var reply = JsonNode.Parse(done.Channel.Open(await ReceiveAsync(ws, cts.Token)));
            return reply?["k"]?.GetValue<string>() == "denied"
                ? $"Funcionando: o caminho remoto respondeu em {sw.ElapsedMilliseconds} ms e recusou um aparelho desconhecido, como deveria."
                : "Resposta inesperada do PC.";
        }
        catch (OperationCanceledException)
        {
            return "Tempo esgotado: o relay não entregou a conexão ao PC.";
        }
        catch (Exception e)
        {
            return "Falhou: " + e.Message;
        }
    }

    private static async Task<byte[]> ReceiveAsync(ClientWebSocket ws, CancellationToken ct)
    {
        var buf = new byte[16 * 1024];
        using var ms = new MemoryStream();
        while (true)
        {
            var r = await ws.ReceiveAsync(buf, ct);
            if (r.MessageType == WebSocketMessageType.Close) throw new IOException("conexão fechada: " + ws.CloseStatusDescription);
            ms.Write(buf, 0, r.Count);
            if (r.EndOfMessage) return ms.ToArray();
        }
    }
}
