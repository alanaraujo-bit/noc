using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Noc.Core.Crypto;
using Noc.Core.Library;
using Noc.Core.Speech;
using Noc.Core.Storage;
using Xunit.Abstractions;

namespace Noc.Core.Tests;

/// <summary>
/// Fase 2 ponta a ponta, com o LM Studio e os modelos reais deste PC (NOC_E2E=1):
/// perfis, fila entre modelos, imagem por blob, voz, retomada depois de reinício do Companion.
/// </summary>
[Collection("e2e")]
public class Phase2Tests(ITestOutputHelper log)
{
    private static bool Enabled => Environment.GetEnvironmentVariable("NOC_E2E") == "1";
    private static readonly string Fixtures = Path.Combine(AppContext.BaseDirectory, "fixtures");

    private sealed record Paired(CompanionHost Host, TestClient Client, ECDsa Device, string Url) : IAsyncDisposable
    {
        public async ValueTask DisposeAsync()
        {
            await Client.DisposeAsync();
            await Host.DisposeAsync();
            Device.Dispose();
        }
    }

    private static async Task<Paired> PairedAsync(string? root = null, bool voice = false, ECDsa? device = null)
    {
        var host = await EndToEndTests.StartHostAsync(remote: false, root, voice);
        var url = $"ws://127.0.0.1:{host.Lan.Port}/v1/ws";
        device ??= ECDsa.Create(ECCurve.NamedCurves.nistP256);
        if (root is null)
        {
            var offer = host.Pairing.NewQrOffer();
            await using var pair = await TestClient.ConnectAsync(url, HandshakeMode.PairQr, th => TestClient.PairAuth(th, device, offer.PairingId, offer.Secret));
        }
        var c = await TestClient.ConnectAsync(url, HandshakeMode.Session, th => TestClient.SessionAuth(th, device));
        // espera o catálogo montar os perfis (precisa da leitura da GPU)
        for (var i = 0; i < 40 && host.Catalog.ModelForTier(Tiers.Deep) is null; i++) await Task.Delay(250);
        return new Paired(host, c, device, url);
    }

    private static async Task<(string Content, string Reasoning, JsonObject End, List<JsonObject> Events)> Collect(TestClient c, string job, TimeSpan? max = null)
    {
        var content = new StringBuilder();
        var reasoning = new StringBuilder();
        var events = new List<JsonObject>();
        var lastSeq = 0;
        var deadline = DateTime.UtcNow + (max ?? TimeSpan.FromMinutes(4));
        var stash = Stashes.GetOrCreateValue(c);
        while (true)
        {
            JsonObject d;
            var stashed = stash.FirstOrDefault(x => x["job"]!.GetValue<string>() == job);
            if (stashed is not null) { stash.Remove(stashed); d = stashed; }
            else
            {
                var evt = await c.Events.Reader.ReadAsync().AsTask().WaitAsync(deadline - DateTime.UtcNow);
                if (evt["e"]!.GetValue<string>() != "job") continue;
                d = evt["d"]!.AsObject();
                // evento de outra tarefa: guarda para quem for coletá-la depois
                if (d["job"]!.GetValue<string>() != job) { stash.Add(d); continue; }
            }
            var seq = d["seq"]!.GetValue<int>();
            if (seq <= lastSeq) continue;
            if (seq != lastSeq + 1) throw new Xunit.Sdk.XunitException($"seq {seq} depois de {lastSeq}: {d.ToJsonString()} | antes: {string.Join(" ; ", events.Select(x => x.ToJsonString()))}");
            lastSeq = seq;
            events.Add(d);
            if (d["reset"] is not null) { content.Clear(); reasoning.Clear(); }
            if (d["r"] is { } rr) reasoning.Append(rr.GetValue<string>());
            if (d["c"] is { } cc) content.Append(cc.GetValue<string>());
            if (d["end"] is JsonObject end) return (content.ToString(), reasoning.ToString(), end, events);
        }
    }

    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<TestClient, List<JsonObject>> Stashes = new();

    private static JsonObject UserText(string text) => new() { ["role"] = "user", ["content"] = text };

    [Fact]
    public async Task Catalog_names_tiers_and_capabilities()
    {
        if (!Enabled) return;
        await using var p = await PairedAsync();
        var models = (await p.Client.CallAsync("models.list"))["r"]!["models"]!.AsArray().OfType<JsonObject>().ToList();
        foreach (var m in models.Where(m => m["type"]!.GetValue<string>() == "llm"))
            log.WriteLine($"{m["name"]} | {m["technical"]} | tier={m["tier"]} vision={m["vision"]} reasoning={m["reasoning"]!.ToJsonString()} fits={m["fitsGpu"]}");
        var status = (await p.Client.CallAsync("status"))["r"]!.AsObject();
        log.WriteLine("perfis: " + status["tiers"]!.ToJsonString());
        Assert.Contains(models, m => m["name"]!.GetValue<string>() == "Qwen 3.5 9B" && m["vision"]!.GetValue<bool>());
        Assert.Contains(models, m => m["name"]!.GetValue<string>() == "Gemma 4 26B-A4B" && m["vision"]!.GetValue<bool>());
        Assert.Equal("qwen3.5-9b-abliterated-vision", status["tiers"]!["fast"]!["model"]!.GetValue<string>());
        Assert.Equal("gemma4-26b-a4b-uncensored", status["tiers"]!["smart"]!["model"]!.GetValue<string>());
        Assert.Equal("qwen3.8-27b-uncensored@q4_k_m", status["tiers"]!["deep"]!["model"]!.GetValue<string>());
        // o Q8 (29 GB) nunca cabe numa GPU de 24 GB
        Assert.False(models.First(m => m["key"]!.GetValue<string>() == "qwen3.8-27b-uncensored@q8_0")["fitsGpu"]!.GetValue<bool>());

        var plan = (await p.Client.CallAsync("models.plan", new JsonObject { ["model"] = "tier:deep" }))["r"]!.AsObject();
        log.WriteLine("plano profundo: " + plan.ToJsonString());
        Assert.True(plan["fits"]!.GetValue<bool>());
        Assert.True(plan["context"]!.GetValue<int>() >= 16384);
    }

    [Fact]
    public async Task Fast_tier_answers_without_reasoning_and_reports_phases()
    {
        if (!Enabled) return;
        await using var p = await PairedAsync();
        var job = Guid.NewGuid().ToString();
        var res = await p.Client.CallAsync("chat.start", new JsonObject
        {
            ["job"] = job, ["model"] = "tier:fast", ["label"] = "teste rápido",
            ["messages"] = new JsonArray(UserText("Qual é a capital do Brasil? Responda em uma frase.")),
            ["params"] = new JsonObject { ["temperature"] = 0, ["max_tokens"] = 80 },
        }, TimeSpan.FromMinutes(3));
        Assert.True(res["ok"]!.GetValue<bool>(), res.ToJsonString());
        var (content, reasoning, end, events) = await Collect(p.Client, job);
        var phases = events.Where(e => e["phase"] is not null).Select(e => e["phase"]!.GetValue<string>()).ToList();
        log.WriteLine($"fases: {string.Join(" → ", phases)} | {content} | {end.ToJsonString()}");
        Assert.Contains("Brasília", content);
        Assert.Equal("", reasoning); // perfil Rápido desliga o raciocínio
        Assert.Contains("preparing", phases);
        Assert.Contains("generating", phases);
        Assert.Equal("qwen3.5-9b-abliterated-vision", end["model"]!.GetValue<string>());

        // a mesma tarefa reenviada (queda de rede) não gera outra resposta
        var again = await p.Client.CallAsync("chat.start", new JsonObject
        {
            ["job"] = job, ["model"] = "tier:fast", ["messages"] = new JsonArray(UserText("x")),
        });
        Assert.True(again["r"]!["existing"]!.GetValue<bool>());

        var list = (await p.Client.CallAsync("jobs.list"))["r"]!["jobs"]!.AsArray();
        Assert.Contains(list, j => j!["job"]!.GetValue<string>() == job && j["label"]!.GetValue<string>() == "teste rápido" && j["phase"]!.GetValue<string>() == "done");
    }

    [Fact]
    public async Task Different_models_queue_instead_of_fighting()
    {
        if (!Enabled) return;
        await using var p = await PairedAsync();
        // aquece o Rápido
        var warm = Guid.NewGuid().ToString();
        await p.Client.CallAsync("chat.start", new JsonObject
        {
            ["job"] = warm, ["model"] = "tier:fast", ["messages"] = new JsonArray(UserText("Diga oi.")),
            ["params"] = new JsonObject { ["max_tokens"] = 10 },
        }, TimeSpan.FromMinutes(3));
        await Collect(p.Client, warm);

        var a = Guid.NewGuid().ToString();
        var b = Guid.NewGuid().ToString();
        await p.Client.CallAsync("chat.start", new JsonObject
        {
            ["job"] = a, ["model"] = "tier:fast",
            ["messages"] = new JsonArray(UserText("Escreva 12 frases curtas numeradas sobre redes de computadores.")),
            ["params"] = new JsonObject { ["temperature"] = 0, ["max_tokens"] = 400 },
        });
        var rb = await p.Client.CallAsync("chat.start", new JsonObject
        {
            ["job"] = b, ["model"] = "tier:smart",
            ["messages"] = new JsonArray(UserText("Responda apenas: segundo")),
            ["params"] = new JsonObject { ["temperature"] = 0, ["max_tokens"] = 20, ["reasoning"] = "off" },
        });
        Assert.Equal(0, rb["r"]!["position"]!.GetValue<int>()); // primeira da fila, com 1 tarefa rodando antes
        var ra = await Collect(p.Client, a);
        var rbRes = await Collect(p.Client, b, TimeSpan.FromMinutes(5));
        var bPhases = rbRes.Events.Where(e => e["phase"] is not null).Select(e => e["phase"]!.GetValue<string>() + (e["ahead"] is { } ah ? $"({ah})" : "")).ToList();
        log.WriteLine("B: " + string.Join(" → ", bPhases) + " | " + rbRes.End.ToJsonString());
        Assert.Contains("queued(1)", bPhases);
        Assert.Contains("loading", bPhases);
        Assert.Equal("stop", ra.End["reason"]!.GetValue<string>());
        Assert.Contains("segundo", rbRes.Content, StringComparison.OrdinalIgnoreCase);
        // B só começou depois que A terminou
        Assert.True(rbRes.End["stats"]!["queuedMs"]!.GetValue<long>() > 500);
    }

    [Fact]
    public async Task Image_by_blob_reaches_vision_model()
    {
        if (!Enabled) return;
        await using var p = await PairedAsync();
        var png = File.ReadAllBytes(Path.Combine(Fixtures, "vision.png"));
        var hash = Convert.ToHexString(SHA256.HashData(png)).ToLowerInvariant();
        var has = (await p.Client.CallAsync("blob.has", new JsonObject { ["hashes"] = new JsonArray(hash) }))["r"]!;
        Assert.Contains(hash, has["missing"]!.AsArray().Select(x => x!.GetValue<string>()));
        // em pedaços, como o celular faz
        const int chunk = 8 * 1024;
        for (var off = 0; off < png.Length; off += chunk)
        {
            var part = png.AsSpan(off, Math.Min(chunk, png.Length - off)).ToArray();
            var r = await p.Client.CallAsync("blob.put", new JsonObject { ["hash"] = hash, ["total"] = png.Length, ["offset"] = off, ["data"] = Convert.ToBase64String(part) });
            Assert.True(r["ok"]!.GetValue<bool>(), r.ToJsonString());
        }
        has = (await p.Client.CallAsync("blob.has", new JsonObject { ["hashes"] = new JsonArray(hash) }))["r"]!;
        Assert.Empty(has["missing"]!.AsArray());

        foreach (var tier in new[] { "fast", "smart" })
        {
            var job = Guid.NewGuid().ToString();
            await p.Client.CallAsync("chat.start", new JsonObject
            {
                ["job"] = job, ["model"] = "tier:" + tier,
                ["messages"] = new JsonArray(new JsonObject
                {
                    ["role"] = "user",
                    ["content"] = new JsonArray(
                        new JsonObject { ["type"] = "text", ["text"] = "Que erro aparece nesta imagem e em qual porta?" },
                        new JsonObject { ["type"] = "image_ref", ["hash"] = hash, ["mime"] = "image/png" }),
                }),
                ["params"] = new JsonObject { ["temperature"] = 0, ["max_tokens"] = 120, ["reasoning"] = "off" },
            }, TimeSpan.FromMinutes(3));
            var (content, _, end, events) = await Collect(p.Client, job, TimeSpan.FromMinutes(5));
            log.WriteLine($"{tier}: {content.Replace('\n', ' ')} | {end["stats"]!.ToJsonString()}");
            Assert.Contains(events, e => e["phase"]?.GetValue<string>() == "preparing" && e["images"]!.GetValue<int>() == 1);
            Assert.Contains("7", content);
            Assert.Contains("link", content, StringComparison.OrdinalIgnoreCase);
        }
    }

    [Fact]
    public async Task Text_only_model_refuses_images_clearly()
    {
        if (!Enabled) return;
        await using var p = await PairedAsync();
        var png = File.ReadAllBytes(Path.Combine(Fixtures, "vision.png"));
        var hash = Convert.ToHexString(SHA256.HashData(png)).ToLowerInvariant();
        await p.Client.CallAsync("blob.put", new JsonObject { ["hash"] = hash, ["total"] = png.Length, ["offset"] = 0, ["data"] = Convert.ToBase64String(png) });
        var job = Guid.NewGuid().ToString();
        await p.Client.CallAsync("chat.start", new JsonObject
        {
            ["job"] = job, ["model"] = "tier:deep",
            ["messages"] = new JsonArray(new JsonObject
            {
                ["role"] = "user",
                ["content"] = new JsonArray(new JsonObject { ["type"] = "text", ["text"] = "o que tem aqui?" }, new JsonObject { ["type"] = "image_ref", ["hash"] = hash }),
            }),
        });
        var (_, _, end, _) = await Collect(p.Client, job);
        Assert.Equal("error", end["reason"]!.GetValue<string>());
        Assert.Equal("no_vision", end["error"]!.GetValue<string>());
    }

    [Fact]
    public async Task Voice_dictation_ptbr_is_accurate_and_silence_is_empty()
    {
        if (!Enabled) return;
        var root = Path.Combine(Path.GetTempPath(), "noc-e2e-" + Guid.NewGuid().ToString("N")[..8]);
        // usa o modelo de voz já baixado (sem baixar 874 MB de novo)
        var existing = new[] { Path.Combine(AppPaths.DefaultRoot, "stt", "ggml-large-v3-turbo-q8_0.bin"), @"D:\PROJETOS\Noc\.devdata\stt\ggml-large-v3-turbo-q8_0.bin" }.FirstOrDefault(File.Exists);
        if (existing is null) return;
        Directory.CreateDirectory(Path.Combine(root, "stt"));
        File.Copy(existing, Path.Combine(root, "stt", Path.GetFileName(existing)));
        using var device = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var host = await EndToEndTests.StartHostAsync(remote: false, root, voice: true);
        await using var _ = host;
        var url = $"ws://127.0.0.1:{host.Lan.Port}/v1/ws";
        var offer = host.Pairing.NewQrOffer();
        await using (var pair = await TestClient.ConnectAsync(url, HandshakeMode.PairQr, th => TestClient.PairAuth(th, device, offer.PairingId, offer.Secret))) { }
        await using var c = await TestClient.ConnectAsync(url, HandshakeMode.Session, th => TestClient.SessionAuth(th, device));

        async Task<JsonObject> Dictate(string wav)
        {
            var pcm = WavPcm(File.ReadAllBytes(Path.Combine(Fixtures, wav)));
            var id = Guid.NewGuid().ToString();
            await c.CallAsync("stt.begin", new JsonObject { ["id"] = id, ["vocab"] = new JsonArray("Aionix", "MikroTik", "PostgreSQL", "Railway", "Vercel", "Qwen", "HttpClient") });
            var seq = 0;
            for (var off = 0; off < pcm.Length; off += 16000)
                await c.CallAsync("stt.chunk", new JsonObject { ["id"] = id, ["seq"] = seq++, ["data"] = Convert.ToBase64String(pcm.AsSpan(off, Math.Min(16000, pcm.Length - off))) });
            var r = await c.CallAsync("stt.end", new JsonObject { ["id"] = id }, TimeSpan.FromMinutes(3));
            Assert.True(r["ok"]!.GetValue<bool>(), r.ToJsonString());
            log.WriteLine($"{wav}: {r["r"]!.ToJsonString()}");
            return r["r"]!.AsObject();
        }

        var vlan = await Dictate("vlan.wav");
        Assert.Contains("VLAN 102", vlan["text"]!.GetValue<string>());
        Assert.Contains("MikroTik", vlan["text"]!.GetValue<string>());
        Assert.Contains("24", vlan["text"]!.GetValue<string>());
        var cs = await Dictate("csharp.wav");
        Assert.Contains("C#", cs["text"]!.GetValue<string>());
        Assert.Contains("HttpClient", cs["text"]!.GetValue<string>());
        var longOne = await Dictate("long.wav");
        Assert.Contains("PostgreSQL", longOne["text"]!.GetValue<string>());
        Assert.Contains("Aionix", longOne["text"]!.GetValue<string>());
        Assert.True(longOne["processMs"]!.GetValue<long>() < 5000);
        var noisy = await Dictate("noisy.wav");
        Assert.Contains("pull request", noisy["text"]!.GetValue<string>());
        var silence = await Dictate("silence.wav");
        Assert.True(silence["empty"]!.GetValue<bool>());
        Assert.Equal("", silence["text"]!.GetValue<string>());
    }

    [Fact]
    public async Task Generation_continues_after_companion_restart()
    {
        if (!Enabled) return;
        using var device = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var p = await PairedAsync(device: device);
        var root = AppPaths.Root;
        var job = Guid.NewGuid().ToString();
        await p.Client.CallAsync("chat.start", new JsonObject
        {
            ["job"] = job, ["model"] = "tier:fast",
            ["messages"] = new JsonArray(UserText("Liste os números de 1 a 60, um por linha, sem mais nada.")),
            ["params"] = new JsonObject { ["temperature"] = 0, ["max_tokens"] = 600, ["reasoning"] = "off" },
        }, TimeSpan.FromMinutes(3));
        // espera sair algum conteúdo e "derruba" o Companion no meio
        var partial = new StringBuilder();
        var lastSeq = 0;
        while (partial.Length < 30)
        {
            var evt = await p.Client.Events.Reader.ReadAsync().AsTask().WaitAsync(TimeSpan.FromMinutes(2));
            if (evt["e"]!.GetValue<string>() != "job" || evt["d"]!["job"]!.GetValue<string>() != job) continue;
            lastSeq = evt["d"]!["seq"]!.GetValue<int>();
            if (evt["d"]!["c"] is { } cc) partial.Append(cc.GetValue<string>());
        }
        await p.Client.DisposeAsync();
        await p.Host.DisposeAsync();
        log.WriteLine($"derrubado com {partial.Length} caracteres (seq {lastSeq})");

        await using var host2 = await EndToEndTests.StartHostAsync(remote: false, root);
        await using var c2 = await TestClient.ConnectAsync($"ws://127.0.0.1:{host2.Lan.Port}/v1/ws", HandshakeMode.Session, th => TestClient.SessionAuth(th, device));
        var sub = await c2.CallAsync("chat.subscribe", new JsonObject { ["job"] = job, ["from"] = 0 });
        Assert.True(sub["ok"]!.GetValue<bool>(), sub.ToJsonString());
        var (content, _, end, _) = await Collect(c2, job, TimeSpan.FromMinutes(5));
        log.WriteLine($"final ({end["reason"]}): {content.Replace('\n', ' ')}");
        Assert.Equal("stop", end["reason"]!.GetValue<string>());
        Assert.StartsWith(partial.ToString()[..20], content);
        Assert.Contains("60", content);
    }

    [Fact]
    public async Task Queued_job_can_be_cancelled()
    {
        if (!Enabled) return;
        await using var p = await PairedAsync();
        var a = Guid.NewGuid().ToString();
        var b = Guid.NewGuid().ToString();
        await p.Client.CallAsync("chat.start", new JsonObject
        {
            ["job"] = a, ["model"] = "tier:fast", ["messages"] = new JsonArray(UserText("Conte até 40 por extenso.")),
            ["params"] = new JsonObject { ["max_tokens"] = 300, ["reasoning"] = "off" },
        });
        await p.Client.CallAsync("chat.start", new JsonObject
        {
            ["job"] = b, ["model"] = "tier:deep", ["messages"] = new JsonArray(UserText("oi")),
        });
        var cancel = await p.Client.CallAsync("chat.cancel", new JsonObject { ["job"] = b });
        Assert.True(cancel["ok"]!.GetValue<bool>());
        var (_, _, endB, _) = await Collect(p.Client, b);
        Assert.Equal("cancelled", endB["reason"]!.GetValue<string>());
        var (_, _, endA, _) = await Collect(p.Client, a);
        Assert.Equal("stop", endA["reason"]!.GetValue<string>());
        // o modelo profundo nunca chegou a ser carregado
        Assert.False(p.Host.Models.Find("qwen3.8-27b-uncensored@q4_k_m")!.Loaded);
    }

    internal static byte[] WavPcm(byte[] wav)
    {
        var o = 12;
        while (o < wav.Length)
        {
            var id = Encoding.ASCII.GetString(wav, o, 4);
            var size = BitConverter.ToInt32(wav, o + 4);
            if (id == "data") return wav.AsSpan(o + 8, Math.Min(size, wav.Length - o - 8)).ToArray();
            o += 8 + size;
        }
        throw new InvalidDataException("wav sem dados");
    }
}
