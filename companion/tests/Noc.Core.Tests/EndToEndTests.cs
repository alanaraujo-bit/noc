using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Noc.Core.Crypto;
using Noc.Core.Storage;
using Xunit.Abstractions;

namespace Noc.Core.Tests;

/// <summary>
/// Testes ponta a ponta reais: Companion + LM Studio local + relay no Railway.
/// Só rodam com NOC_E2E=1 (precisam do LM Studio ligado com um modelo disponível).
/// </summary>
[Collection("e2e")]
public class EndToEndTests(ITestOutputHelper log)
{
    private static bool Enabled => Environment.GetEnvironmentVariable("NOC_E2E") == "1";

    private static async Task<CompanionHost> StartHostAsync(bool remote)
    {
        AppPaths.Root = Path.Combine(Path.GetTempPath(), "noc-e2e-" + Guid.NewGuid().ToString("N")[..8]);
        var host = new CompanionHost();
        host.Settings.LanPort = 47900 + Random.Shared.Next(0, 90);
        host.Settings.RemoteEnabled = remote;
        host.Settings.AutoStartLmServer = false;
        await host.StartAsync();
        if (remote)
        {
            for (var i = 0; i < 60 && host.Relay.State != Net.RelayState.Online; i++) await Task.Delay(250);
            Assert.Equal(Net.RelayState.Online, host.Relay.State);
        }
        return host;
    }

    private async Task<(string Reasoning, string Content, JsonObject End)> CollectJob(TestClient c, string job)
    {
        var r = new StringBuilder();
        var content = new StringBuilder();
        var lastSeq = 0;
        while (true)
        {
            var evt = await c.Events.Reader.ReadAsync().AsTask().WaitAsync(TimeSpan.FromMinutes(3));
            if (evt["e"]!.GetValue<string>() != "job") continue;
            var d = evt["d"]!.AsObject();
            if (d["job"]!.GetValue<string>() != job) continue;
            var seq = d["seq"]!.GetValue<int>();
            Assert.Equal(lastSeq + 1, seq);
            lastSeq = seq;
            if (d["r"] is { } rr) r.Append(rr.GetValue<string>());
            if (d["c"] is { } cc) content.Append(cc.GetValue<string>());
            if (d["end"] is JsonObject end) return (r.ToString(), content.ToString(), end);
        }
    }

    private static string FirstLlm(JsonObject models) =>
        models["models"]!.AsArray().First(m => m!["type"]!.GetValue<string>() == "llm" && m["fitsGpu"]?.GetValue<bool>() != false)!["key"]!.GetValue<string>();

    [Fact]
    public async Task Lan_pair_then_session_then_stream()
    {
        if (!Enabled) return;
        await using var host = await StartHostAsync(remote: false);
        var url = $"ws://127.0.0.1:{host.Lan.Port}/v1/ws";
        using var device = ECDsa.Create(ECCurve.NamedCurves.nistP256);

        // pareamento por QR
        var offer = host.Pairing.NewQrOffer();
        await using (var pair = await TestClient.ConnectAsync(url, HandshakeMode.PairQr,
                         th => TestClient.PairAuth(th, device, offer.PairingId, offer.Secret)))
        {
            Assert.Equal("welcome", pair.Welcome["k"]!.GetValue<string>());
        }
        // o mesmo QR não pode ser usado duas vezes
        using var intruder = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        await using (var again = await TestClient.ConnectAsync(url, HandshakeMode.PairQr,
                         th => TestClient.PairAuth(th, intruder, offer.PairingId, offer.Secret)))
        {
            Assert.Equal("denied", again.Welcome["k"]!.GetValue<string>());
        }
        // aparelho desconhecido é recusado
        await using (var unknown = await TestClient.ConnectAsync(url, HandshakeMode.Session, th => TestClient.SessionAuth(th, intruder)))
        {
            Assert.Equal("unknown", unknown.Welcome["code"]!.GetValue<string>());
        }

        await using var c = await TestClient.ConnectAsync(url, HandshakeMode.Session, th => TestClient.SessionAuth(th, device));
        Assert.Equal("welcome", c.Welcome["k"]!.GetValue<string>());
        var models = (await c.CallAsync("models.list"))["r"]!.AsObject();
        var model = FirstLlm(models);
        log.WriteLine("modelo: " + model);

        var job = Guid.NewGuid().ToString();
        var res = await c.CallAsync("chat.start", new JsonObject
        {
            ["job"] = job,
            ["model"] = model,
            ["messages"] = new JsonArray(new JsonObject { ["role"] = "user", ["content"] = "Responda apenas: pong" }),
            ["params"] = new JsonObject { ["reasoning"] = "off", ["temperature"] = 0, ["max_tokens"] = 20 },
        }, TimeSpan.FromMinutes(3));
        Assert.True(res["ok"]!.GetValue<bool>(), res.ToJsonString());
        var (_, content, end) = await CollectJob(c, job);
        log.WriteLine($"resposta: {content} | {end.ToJsonString()}");
        Assert.Contains("pong", content, StringComparison.OrdinalIgnoreCase);
        Assert.Equal("stop", end["reason"]!.GetValue<string>());
        Assert.True(end["stats"]!["tps"]!.GetValue<double>() > 1);
    }

    [Fact]
    public async Task Relay_stream_resume_cancel_and_revoke()
    {
        if (!Enabled) return;
        await using var host = await StartHostAsync(remote: true);
        using var device = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var relayUrl = CompanionSettings.DefaultRelay + "/v1/connect?pc=" + host.Identity.PcId;

        // pareamento por código (com aprovação no PC) via relay
        var code = await host.NewPairCodeAsync();
        await Task.Delay(500);
        string? pcSas = null;
        host.Pairing.ApprovalRequested += req => { pcSas = req.Sas; req.Approve(); };
        using var http = new HttpClient();
        var lookup = JsonNode.Parse(await http.GetStringAsync(CompanionSettings.DefaultRelay.Replace("wss://", "https://") + "/v1/pair/" + code.Lookup))!;
        Assert.Equal(host.Identity.PcId, lookup["pcId"]!.GetValue<string>());
        await using (var pair = await TestClient.ConnectAsync(relayUrl, HandshakeMode.PairCode,
                         th => TestClient.PairAuth(th, device, code.Lookup, Encoding.UTF8.GetBytes(code.Secret), code: true)))
        {
            Assert.Equal("welcome", pair.Welcome["k"]!.GetValue<string>());
            Assert.Equal(pcSas, pair.Sas); // o código de verificação bate dos dois lados
        }

        await using var c = await TestClient.ConnectAsync(relayUrl, HandshakeMode.Session, th => TestClient.SessionAuth(th, device));
        Assert.Equal("welcome", c.Welcome["k"]!.GetValue<string>());
        var model = FirstLlm((await c.CallAsync("models.list"))["r"]!.AsObject());

        // geração longa; derruba a conexão no meio e retoma por outra
        var job = Guid.NewGuid().ToString();
        await c.CallAsync("chat.start", new JsonObject
        {
            ["job"] = job, ["model"] = model,
            ["messages"] = new JsonArray(new JsonObject { ["role"] = "user", ["content"] = "Conte de 1 a 60 por extenso, um número por linha." }),
            ["params"] = new JsonObject { ["reasoning"] = "off", ["temperature"] = 0, ["max_tokens"] = 600 },
        }, TimeSpan.FromMinutes(3));
        var partial = new StringBuilder();
        var seq = 0;
        while (partial.Length < 60)
        {
            var e = await c.Events.Reader.ReadAsync().AsTask().WaitAsync(TimeSpan.FromMinutes(2));
            if (e["e"]!.GetValue<string>() != "job") continue;
            seq = e["d"]!["seq"]!.GetValue<int>();
            if (e["d"]!["c"] is { } cc) partial.Append(cc.GetValue<string>());
        }
        await c.DisposeAsync(); // queda
        await Task.Delay(1500);

        await using var c2 = await TestClient.ConnectAsync(relayUrl, HandshakeMode.Session, th => TestClient.SessionAuth(th, device));
        var sub = await c2.CallAsync("chat.subscribe", new JsonObject { ["job"] = job, ["from"] = seq });
        Assert.True(sub["ok"]!.GetValue<bool>(), sub.ToJsonString());
        var rest = new StringBuilder();
        var lastSeq = seq;
        JsonObject? end = null;
        while (end is null)
        {
            var e = await c2.Events.Reader.ReadAsync().AsTask().WaitAsync(TimeSpan.FromMinutes(2));
            if (e["e"]!.GetValue<string>() != "job") continue;
            var d = e["d"]!.AsObject();
            Assert.Equal(lastSeq + 1, d["seq"]!.GetValue<int>());
            lastSeq = d["seq"]!.GetValue<int>();
            if (d["c"] is { } cc) rest.Append(cc.GetValue<string>());
            end = d["end"] as JsonObject;
        }
        var full = partial.ToString() + rest;
        log.WriteLine($"retomado: {full.Length} chars, fim: {end.ToJsonString()}");
        Assert.Contains("sessenta", full, StringComparison.OrdinalIgnoreCase);

        // cancelamento
        var job2 = Guid.NewGuid().ToString();
        await c2.CallAsync("chat.start", new JsonObject
        {
            ["job"] = job2, ["model"] = model,
            ["messages"] = new JsonArray(new JsonObject { ["role"] = "user", ["content"] = "Escreva uma redação de 2000 palavras sobre o mar." }),
            ["params"] = new JsonObject { ["reasoning"] = "off" },
        });
        await Task.Delay(2500);
        await c2.CallAsync("chat.cancel", new JsonObject { ["job"] = job2 });
        JsonObject? end2 = null;
        while (end2 is null)
        {
            var e = await c2.Events.Reader.ReadAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(30));
            if (e["e"]!.GetValue<string>() == "job" && e["d"]!["job"]!.GetValue<string>() == job2) end2 = e["d"]!["end"] as JsonObject;
        }
        Assert.Equal("cancelled", end2["reason"]!.GetValue<string>());

        // revogação derruba a sessão ativa e impede reconexão
        host.Devices.Revoke(Wire.IdFromSpki(device.ExportSubjectPublicKeyInfo()));
        var bye = false;
        try
        {
            while (true)
            {
                var e = await c2.Events.Reader.ReadAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(10));
                if (e["e"]!.GetValue<string>() == "bye") { bye = true; break; }
            }
        }
        catch (Exception) { bye = true; } // canal fechado também conta
        Assert.True(bye);
        await using var c3 = await TestClient.ConnectAsync(relayUrl, HandshakeMode.Session, th => TestClient.SessionAuth(th, device));
        Assert.Equal("revoked", c3.Welcome["code"]!.GetValue<string>());
    }

    [Fact]
    public async Task Finished_job_survives_companion_restart()
    {
        if (!Enabled) return;
        var host = await StartHostAsync(remote: false);
        var dataDir = AppPaths.Root;
        var port = host.Lan.Port;
        using var device = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var offer = host.Pairing.NewQrOffer();
        var url = $"ws://127.0.0.1:{port}/v1/ws";
        await using (var pair = await TestClient.ConnectAsync(url, HandshakeMode.PairQr, th => TestClient.PairAuth(th, device, offer.PairingId, offer.Secret))) { }

        string job = Guid.NewGuid().ToString(), content;
        await using (var c = await TestClient.ConnectAsync(url, HandshakeMode.Session, th => TestClient.SessionAuth(th, device)))
        {
            var model = FirstLlm((await c.CallAsync("models.list"))["r"]!.AsObject());
            await c.CallAsync("chat.start", new JsonObject
            {
                ["job"] = job, ["model"] = model,
                ["messages"] = new JsonArray(new JsonObject { ["role"] = "user", ["content"] = "Responda apenas: persistente" }),
                ["params"] = new JsonObject { ["reasoning"] = "off", ["temperature"] = 0, ["max_tokens"] = 20 },
            }, TimeSpan.FromMinutes(3));
            (_, content, _) = await CollectJob(c, job);
        }
        await host.DisposeAsync();

        // novo processo do Companion, mesma pasta de dados: o job terminado ainda pode ser buscado
        AppPaths.Root = dataDir;
        await using var host2 = new CompanionHost();
        host2.Settings.LanPort = port + 1;
        host2.Settings.RemoteEnabled = false;
        host2.Settings.AutoStartLmServer = false;
        await host2.StartAsync();
        await using var c2 = await TestClient.ConnectAsync($"ws://127.0.0.1:{host2.Lan.Port}/v1/ws", HandshakeMode.Session, th => TestClient.SessionAuth(th, device));
        var sub = await c2.CallAsync("chat.subscribe", new JsonObject { ["job"] = job, ["from"] = 0 });
        Assert.True(sub["ok"]!.GetValue<bool>(), sub.ToJsonString());
        var (_, again, end) = await CollectJob(c2, job);
        Assert.Equal(content, again);
        Assert.Equal("stop", end["reason"]!.GetValue<string>());
        log.WriteLine("recuperado após reinício: " + again);
    }
}
