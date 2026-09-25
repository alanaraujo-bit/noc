using System.Security.Cryptography;
using System.Text.Json.Nodes;
using Noc.Core.Crypto;
using Noc.Core.ImageEngine;
using Noc.Core.Storage;
using Xunit.Abstractions;

namespace Noc.Core.Tests;

/// <summary>
/// Motor de imagem ponta a ponta, com a RTX e os modelos reais deste PC (NOC_E2E=1), pelo mesmo caminho do celular:
/// sessão cifrada → image.start → fila → motor → worker → GPU → PNG no disco → image.get em pedaços.
/// </summary>
[Collection("e2e")]
public class ImageEngineTests(ITestOutputHelper log)
{
    private static bool Enabled => Environment.GetEnvironmentVariable("NOC_E2E") == "1";
    /// <summary>Estado de instalação conferido pelo primeiro teste (os outros não precisam reconferir 33 GB).</summary>
    private static string Seed => Path.Combine(Path.GetTempPath(), "noc-e2e-image-models.json");

    private sealed record Paired(CompanionHost Host, TestClient Client, ECDsa Device, string Root) : IAsyncDisposable
    {
        public async ValueTask DisposeAsync()
        {
            await Client.DisposeAsync();
            await Host.DisposeAsync();
            Device.Dispose();
        }
    }

    private static async Task<Paired> PairedAsync(string? root = null, ECDsa? device = null, bool seed = true, Action<CompanionHost>? tweak = null)
    {
        var fresh = root is null;
        root ??= Path.Combine(Path.GetTempPath(), "noc-e2e-" + Guid.NewGuid().ToString("N")[..8]);
        Directory.CreateDirectory(root);
        if (fresh && seed && File.Exists(Seed)) File.Copy(Seed, Path.Combine(root, "image-models.json"));
        var host = await EndToEndTests.StartHostAsync(remote: false, root, image: true);
        tweak?.Invoke(host);
        var url = $"ws://127.0.0.1:{host.Lan.Port}/v1/ws";
        device ??= ECDsa.Create(ECCurve.NamedCurves.nistP256);
        if (fresh)
        {
            var offer = host.Pairing.NewQrOffer();
            await using var pair = await TestClient.ConnectAsync(url, HandshakeMode.PairQr, th => TestClient.PairAuth(th, device, offer.PairingId, offer.Secret));
        }
        var c = await TestClient.ConnectAsync(url, HandshakeMode.Session, th => TestClient.SessionAuth(th, device));
        for (var i = 0; i < 60 && host.Gpu.Last is null; i++) await Task.Delay(250);
        return new Paired(host, c, device, root);
    }

    private sealed record Run(JsonObject End, List<JsonObject> Events, List<JsonObject> Previews);

    /// <summary>Acompanha a tarefa até o fim, conferindo a sequência (sem buracos) e guardando as prévias efêmeras à parte.</summary>
    private static async Task<Run> Collect(TestClient c, string job, TimeSpan max, int fromSeq = 0, Func<JsonObject, Task>? onEvent = null)
    {
        var events = new List<JsonObject>();
        var previews = new List<JsonObject>();
        var lastSeq = fromSeq;
        var deadline = DateTime.UtcNow + max;
        while (true)
        {
            var evt = await c.Events.Reader.ReadAsync().AsTask().WaitAsync(deadline - DateTime.UtcNow);
            if (evt["e"]!.GetValue<string>() != "job") continue;
            var d = evt["d"]!.AsObject();
            if (d["job"]!.GetValue<string>() != job) continue;
            if (d["transient"] is not null) { previews.Add(d); continue; }
            var seq = d["seq"]!.GetValue<int>();
            if (seq <= lastSeq) continue;
            if (seq != lastSeq + 1 && seq < lastSeq + 10_000) throw new Xunit.Sdk.XunitException($"seq {seq} depois de {lastSeq}");
            lastSeq = seq;
            events.Add(d);
            if (onEvent is not null) await onEvent(d);
            if (d["end"] is JsonObject end) return new Run(end, events, previews);
        }
    }

    private static async Task<byte[]> Download(TestClient c, string id, string kind)
    {
        using var ms = new MemoryStream();
        long total = -1;
        while (total < 0 || ms.Length < total)
        {
            var r = (await c.CallAsync("image.get", new JsonObject { ["id"] = id, ["kind"] = kind, ["offset"] = ms.Length, ["length"] = 262_144 }))["r"]!;
            total = r["total"]!.GetValue<long>();
            var data = Convert.FromBase64String(r["data"]!.GetValue<string>());
            Assert.NotEmpty(data);
            ms.Write(data);
        }
        return ms.ToArray();
    }

    private static JsonObject Ok(JsonObject res)
    {
        Assert.True(res["ok"]!.GetValue<bool>(), res.ToJsonString());
        return res["r"]!.AsObject();
    }

    private static async Task WaitAsync(Func<bool> cond, TimeSpan max, string what)
    {
        var until = DateTime.UtcNow + max;
        while (!cond())
        {
            if (DateTime.UtcNow > until) throw new Xunit.Sdk.XunitException("tempo esgotado esperando: " + what);
            await Task.Delay(500);
        }
    }

    [Fact]
    public async Task A0_Install_adopts_existing_official_files_and_verifies_hashes()
    {
        if (!Enabled) return;
        await using var p = await PairedAsync(seed: false);
        var z = ImageCatalog.ZImageTurbo;
        // a instalação automática começa sozinha na primeira partida; os arquivos já baixados são conferidos, não baixados de novo
        await WaitAsync(() => p.Host.Images.Store.IsInstalled(z), TimeSpan.FromMinutes(20), "Z-Image instalado");
        var status = Ok(await p.Client.CallAsync("image.status"));
        log.WriteLine(status.ToJsonString());
        var models = status["models"]!.AsArray().OfType<JsonObject>().ToList();
        Assert.Equal("installed", models.First(m => m["id"]!.GetValue<string>() == z.Id)["state"]!.GetValue<string>());
        var flux = models.First(m => m["id"]!.GetValue<string>() == ImageCatalog.FluxDev.Id);
        Assert.True(flux["gated"]!.GetValue<bool>());
        Assert.NotEqual("installed", flux["state"]!.GetValue<string>());
        // sem token: o FLUX responde claramente que precisa de autorização
        var err = await p.Client.CallAsync("image.start", new JsonObject { ["job"] = Guid.NewGuid().ToString(), ["prompt"] = "teste", ["engine"] = "quality" });
        Assert.False(err["ok"]!.GetValue<bool>());
        log.WriteLine("FLUX sem autorização: " + err["e"]!.ToJsonString());
        File.Copy(Path.Combine(p.Root, "image-models.json"), Seed, overwrite: true);
    }

    [Fact]
    public async Task A1_Generates_from_portuguese_through_session_evicting_and_restoring_the_llm()
    {
        if (!Enabled) return;
        await using var p = await PairedAsync(tweak: h => h.Settings.ImageKeepWarmWithLlmSeconds = 8);
        Assert.True(p.Host.Images.Store.IsInstalled(ImageCatalog.ZImageTurbo), "rode o teste A0 antes");
        // Qwen carregado (o cenário do usuário conversando)
        var fast = p.Host.Catalog.ModelForTier(Library.Tiers.Fast)!;
        await p.Host.Models.EnsureLoadedAsync(fast, null, CancellationToken.None, Library.Tiers.Fast);
        Assert.Contains(p.Host.Models.Last.Models, m => m.Loaded && m.Key == fast);

        var job = Guid.NewGuid().ToString();
        var start = Ok(await p.Client.CallAsync("image.start", new JsonObject
        {
            ["job"] = job, ["prompt"] = "Uma mansão brutalista extremamente moderna no meio de uma floresta montanhosa durante a noite, chuva forte, iluminação cinematográfica.",
            ["engine"] = "fast", ["format"] = "portrait", ["quality"] = "standard", ["count"] = 1,
            ["meta"] = new JsonObject { ["conv"] = "c1", ["msg"] = "m1" }, ["label"] = "mansão",
        }));
        log.WriteLine("image.start: " + start.ToJsonString());
        Assert.Equal(832, start["width"]!.GetValue<int>());
        var run = await Collect(p.Client, job, TimeSpan.FromMinutes(15));
        var phases = run.Events.Where(e => e["phase"] is not null).Select(e => e["phase"]!.GetValue<string>()).ToList();
        log.WriteLine("fases: " + string.Join(" > ", phases.Distinct()));
        log.WriteLine("fim: " + run.End.ToJsonString());
        log.WriteLine($"prévias: {run.Previews.Count}");
        Assert.Equal("stop", run.End["reason"]!.GetValue<string>());
        Assert.Contains("freeing", phases);        // o Qwen saiu da GPU
        Assert.Contains("generating", phases);
        Assert.Contains(run.Events, e => e["step"]?.GetValue<int>() == 9);
        Assert.Contains(run.Events, e => e["imageReady"] is not null);
        var final = run.End["prompt"]!["final"]!.GetValue<string>();
        Assert.Contains("brutalist", final, StringComparison.OrdinalIgnoreCase); // traduzido para o modelo
        var img = run.End["images"]!.AsArray()[0]!.AsObject();
        Assert.Equal(832, img["width"]!.GetValue<int>());
        Assert.Equal(1216, img["height"]!.GetValue<int>());

        // download pelo canal cifrado, em pedaços; o arquivo é exatamente o do PC e é um PNG sem o prompt dentro
        var png = await Download(p.Client, img["id"]!.GetValue<string>(), "full");
        Assert.Equal(img["sha256"]!.GetValue<string>(), Convert.ToHexString(SHA256.HashData(png)).ToLowerInvariant());
        Assert.Equal(new byte[] { 0x89, 0x50, 0x4E, 0x47 }, png[..4]);
        Assert.DoesNotContain("mansão", System.Text.Encoding.Latin1.GetString(png));
        Assert.DoesNotContain("brutalist", System.Text.Encoding.Latin1.GetString(png));
        var thumb = await Download(p.Client, img["id"]!.GetValue<string>(), "thumb");
        Assert.True(thumb.Length is > 5_000 and < 400_000);
        var meta = Ok(await p.Client.CallAsync("image.meta", new JsonObject { ["id"] = img["id"]!.GetValue<string>() }));
        Assert.Equal("c1", meta["meta"]!["conv"]!.GetValue<string>());

        // ocioso: o motor solta a GPU e o Qwen volta sozinho
        await WaitAsync(() => !p.Host.Images.WorkerAlive, TimeSpan.FromSeconds(60), "motor liberar a GPU");
        await WaitAsync(() => p.Host.Models.Op == LmStudio.ModelOpState.Idle && p.Host.Models.Last.Models.Any(m => m.Loaded && m.Key == fast) &&
                              p.Host.Images.Displaced is null, TimeSpan.FromSeconds(120), "Qwen voltar");
        log.WriteLine("Qwen restaurado depois da imagem");
    }

    [Fact]
    public async Task A2_Chat_after_image_takes_the_gpu_back_and_answers()
    {
        if (!Enabled) return;
        await using var p = await PairedAsync(tweak: h => h.Settings.ImageKeepWarmMinutes = 30);
        var job = Guid.NewGuid().ToString();
        Ok(await p.Client.CallAsync("image.start", new JsonObject { ["job"] = job, ["prompt"] = "A red Ferrari in Tokyo at night, neon reflections", ["engine"] = "fast" }));
        var run = await Collect(p.Client, job, TimeSpan.FromMinutes(10));
        Assert.Equal("stop", run.End["reason"]!.GetValue<string>());
        Assert.True(p.Host.Images.WorkerAlive); // aquecido para a próxima

        var chat = Guid.NewGuid().ToString();
        Ok(await p.Client.CallAsync("chat.start", new JsonObject
        {
            ["job"] = chat, ["model"] = "tier:fast", ["messages"] = new JsonArray(new JsonObject { ["role"] = "user", ["content"] = "Diga apenas: ok" }),
            ["params"] = new JsonObject { ["max_tokens"] = 20 },
        }));
        var answer = await Collect(p.Client, chat, TimeSpan.FromMinutes(5));
        Assert.Equal("stop", answer.End["reason"]!.GetValue<string>());
        Assert.False(p.Host.Images.WorkerAlive); // a carga do LLM tirou o motor de imagem da GPU antes
    }

    [Fact]
    public async Task A3_Cancel_stops_the_gpu_and_the_next_image_still_works()
    {
        if (!Enabled) return;
        await using var p = await PairedAsync();
        var job = Guid.NewGuid().ToString();
        Ok(await p.Client.CallAsync("image.start", new JsonObject { ["job"] = job, ["prompt"] = "a lighthouse in a storm", ["engine"] = "fast", ["count"] = 4 }));
        var cancelledAt = DateTime.MinValue;
        var run = await Collect(p.Client, job, TimeSpan.FromMinutes(10), onEvent: async e =>
        {
            if (cancelledAt == DateTime.MinValue && e["step"]?.GetValue<int>() == 3)
            {
                cancelledAt = DateTime.UtcNow;
                Ok(await p.Client.CallAsync("chat.cancel", new JsonObject { ["job"] = job }));
            }
        });
        var stopMs = (DateTime.UtcNow - cancelledAt).TotalMilliseconds;
        log.WriteLine($"cancelado; fim chegou {stopMs:0} ms depois: {run.End.ToJsonString()}");
        Assert.Equal("cancelled", run.End["reason"]!.GetValue<string>());
        Assert.True(stopMs < 5000);
        Assert.DoesNotContain(run.Events, e => e["imageReady"] is not null); // parou antes da primeira ficar pronta

        var again = Guid.NewGuid().ToString();
        Ok(await p.Client.CallAsync("image.start", new JsonObject { ["job"] = again, ["prompt"] = "a lighthouse in a storm", ["engine"] = "fast" }));
        Assert.Equal("stop", (await Collect(p.Client, again, TimeSpan.FromMinutes(10))).End["reason"]!.GetValue<string>());
    }

    [Fact]
    public async Task A4_Four_images_in_one_request_arrive_one_by_one()
    {
        if (!Enabled) return;
        await using var p = await PairedAsync();
        var job = Guid.NewGuid().ToString();
        Ok(await p.Client.CallAsync("image.start", new JsonObject { ["job"] = job, ["prompt"] = "product photo of a ceramic mug on a wooden table", ["engine"] = "fast", ["count"] = 4, ["seed"] = 42 }));
        var run = await Collect(p.Client, job, TimeSpan.FromMinutes(15));
        Assert.Equal("stop", run.End["reason"]!.GetValue<string>());
        var imgs = run.End["images"]!.AsArray().OfType<JsonObject>().ToList();
        Assert.Equal(4, imgs.Count);
        Assert.Equal(new long[] { 42, 43, 44, 45 }, imgs.Select(i => i["seed"]!.GetValue<long>()));
        Assert.Equal(4, run.Events.Count(e => e["imageReady"] is not null));
        Assert.Equal(4, imgs.Select(i => i["sha256"]!.GetValue<string>()).Distinct().Count());
        log.WriteLine(run.End["stats"]!.ToJsonString());
    }

    [Fact]
    public async Task A5_Generation_survives_companion_restart_without_redoing_saved_images()
    {
        if (!Enabled) return;
        var p = await PairedAsync();
        var root = p.Root;
        var device = p.Device;
        var job = Guid.NewGuid().ToString();
        Ok(await p.Client.CallAsync("image.start", new JsonObject { ["job"] = job, ["prompt"] = "a watercolor fox in the snow", ["engine"] = "fast", ["count"] = 2, ["seed"] = 7 }));
        var firstReady = new TaskCompletionSource();
        var collector = Collect(p.Client, job, TimeSpan.FromMinutes(10), onEvent: e =>
        {
            if (e["imageReady"] is not null) firstReady.TrySetResult();
            return Task.CompletedTask;
        });
        await firstReady.Task.WaitAsync(TimeSpan.FromMinutes(10));
        var firstId = $"{job}-0";
        var firstHash = ImageOutputs.Sha256Of(p.Host.Images.Outputs.BaseOf(firstId)! + ".png");
        // "Companion fechou" no meio da segunda imagem
        await p.Client.DisposeAsync();
        await p.Host.DisposeAsync();
        _ = collector.ContinueWith(_ => { });

        await using var p2 = await PairedAsync(root, device);
        var sub = Ok(await p2.Client.CallAsync("chat.subscribe", new JsonObject { ["job"] = job, ["from"] = 0 }));
        log.WriteLine("reassinada: " + sub.ToJsonString());
        var run = await Collect(p2.Client, job, TimeSpan.FromMinutes(10));
        log.WriteLine("fim: " + run.End.ToJsonString());
        Assert.Equal("stop", run.End["reason"]!.GetValue<string>());
        var imgs = run.End["images"]!.AsArray().OfType<JsonObject>().ToList();
        Assert.Equal(2, imgs.Count);
        Assert.Equal(firstHash, imgs[0]["sha256"]!.GetValue<string>()); // a primeira não foi refeita
    }

    [Fact]
    public async Task A6_Busy_gpu_in_practice_still_generates_at_full_speed()
    {
        if (!Enabled) return;
        // outro programa usando 12 GB de VRAM ativamente (como um jogo): o Windows (WDDM) mantém a geração na VRAM
        var hog = HogVram(12);
        try
        {
            await using var p = await PairedAsync();
            var job = Guid.NewGuid().ToString();
            Ok(await p.Client.CallAsync("image.start", new JsonObject { ["job"] = job, ["prompt"] = "a small cabin in the woods", ["engine"] = "fast" }));
            var run = await Collect(p.Client, job, TimeSpan.FromMinutes(25));
            log.WriteLine("fim com outro programa na GPU: " + run.End.ToJsonString());
            Assert.Equal("stop", run.End["reason"]!.GetValue<string>());
        }
        finally
        {
            hog.Kill(true);
            hog.Dispose();
        }
    }

    [Fact]
    public async Task A7_Tight_vram_switches_to_economy_mode()
    {
        if (!Enabled) return;
        // 5 GB reservados para "outros programas": não cabe o modo normal (21 GB), cabe o econômico (fp8)
        await using var p = await PairedAsync(tweak: h => h.Settings.ImageVramReserveGiB = 5);
        var job = Guid.NewGuid().ToString();
        Ok(await p.Client.CallAsync("image.start", new JsonObject { ["job"] = job, ["prompt"] = "a small cabin in the woods", ["engine"] = "fast", ["seed"] = 5 }));
        var run = await Collect(p.Client, job, TimeSpan.FromMinutes(40));
        log.WriteLine("fim: " + run.End.ToJsonString());
        Assert.Equal("stop", run.End["reason"]!.GetValue<string>());
        Assert.Equal("fp8", run.End["stats"]!["transformer"]!.GetValue<string>());
        Assert.Contains(run.Events, e => e["phase"]?.GetValue<string>() == "optimizing" || e["phase"]?.GetValue<string>() == "loading");
    }

    [Fact]
    public async Task A8_No_room_at_all_explains_instead_of_crashing()
    {
        if (!Enabled) return;
        await using var p = await PairedAsync(tweak: h => h.Settings.ImageVramReserveGiB = 12);
        var job = Guid.NewGuid().ToString();
        Ok(await p.Client.CallAsync("image.start", new JsonObject { ["job"] = job, ["prompt"] = "a small cabin in the woods", ["engine"] = "fast" }));
        var run = await Collect(p.Client, job, TimeSpan.FromMinutes(5));
        log.WriteLine("fim: " + run.End.ToJsonString());
        Assert.Equal("error", run.End["reason"]!.GetValue<string>());
        Assert.Equal("vram_insufficient", run.End["error"]!.GetValue<string>());
        Assert.DoesNotContain("CUDA", run.End["detail"]!.GetValue<string>());
        Assert.False(p.Host.Images.WorkerAlive);
    }

    [Fact]
    public async Task A9_Out_of_memory_recovers_once_in_economy_mode()
    {
        if (!Enabled) return;
        var flag = Path.Combine(Path.GetTempPath(), "noc-fault-oom-" + Guid.NewGuid().ToString("N")[..6]);
        File.WriteAllText(flag, "1");
        Environment.SetEnvironmentVariable("NOC_IMAGE_FAULT_OOM", flag);
        try
        {
            await using var p = await PairedAsync();
            var job = Guid.NewGuid().ToString();
            Ok(await p.Client.CallAsync("image.start", new JsonObject { ["job"] = job, ["prompt"] = "a small cabin in the woods", ["engine"] = "fast" }));
            var run = await Collect(p.Client, job, TimeSpan.FromMinutes(40));
            log.WriteLine("fim: " + run.End.ToJsonString());
            Assert.Contains(run.Events, e => e["phase"]?.GetValue<string>() == "recovering");
            Assert.Equal("stop", run.End["reason"]!.GetValue<string>());
            Assert.Equal("fp8", run.End["stats"]!["transformer"]!.GetValue<string>());
            Assert.False(File.Exists(flag));
        }
        finally { Environment.SetEnvironmentVariable("NOC_IMAGE_FAULT_OOM", null); }
    }

    [Fact]
    public async Task B1_Image_arriving_while_the_llm_is_coming_back_never_shares_the_gpu()
    {
        if (!Enabled) return;
        await using var p = await PairedAsync(tweak: h => h.Settings.ImageKeepWarmWithLlmSeconds = 3);
        var fast = p.Host.Catalog.ModelForTier(Library.Tiers.Fast)!;
        await p.Host.Models.EnsureLoadedAsync(fast, null, CancellationToken.None, Library.Tiers.Fast);
        var first = Guid.NewGuid().ToString();
        Ok(await p.Client.CallAsync("image.start", new JsonObject { ["job"] = first, ["prompt"] = "a red bicycle", ["engine"] = "fast" }));
        Assert.Equal("stop", (await Collect(p.Client, first, TimeSpan.FromMinutes(10))).End["reason"]!.GetValue<string>());
        // o motor fica ocioso, solta a GPU e começa a devolver o Qwen; no meio da devolução chega outra imagem
        await WaitAsync(() => p.Host.Models.Op == LmStudio.ModelOpState.Loading, TimeSpan.FromMinutes(2), "a devolução do LLM começar");
        var second = Guid.NewGuid().ToString();
        Ok(await p.Client.CallAsync("image.start", new JsonObject { ["job"] = second, ["prompt"] = "a blue bicycle", ["engine"] = "fast" }));
        var overlap = false;
        var run = await Collect(p.Client, second, TimeSpan.FromMinutes(10), onEvent: e =>
        {
            // enquanto gera, nenhum LLM pode estar na GPU junto
            if (e["step"] is not null && p.Host.Models.Last.Models.Any(m => m.Loaded && m.Type == "llm")) overlap = true;
            return Task.CompletedTask;
        });
        log.WriteLine("fim: " + run.End.ToJsonString());
        Assert.Equal("stop", run.End["reason"]!.GetValue<string>());
        Assert.False(overlap, "LLM e motor de imagem ficaram juntos na GPU");
    }

    [Fact]
    public async Task B2_Warm_engine_in_economy_mode_is_reused_not_refused()
    {
        if (!Enabled) return;
        await using var p = await PairedAsync(tweak: h => h.Settings.ImageVramReserveGiB = 7);
        for (var i = 0; i < 2; i++)
        {
            var job = Guid.NewGuid().ToString();
            Ok(await p.Client.CallAsync("image.start", new JsonObject { ["job"] = job, ["prompt"] = "a green apple", ["engine"] = "fast" }));
            var run = await Collect(p.Client, job, TimeSpan.FromMinutes(10));
            log.WriteLine($"{i}: " + run.End.ToJsonString());
            Assert.Equal("stop", run.End["reason"]!.GetValue<string>());
            Assert.Equal("fp8", run.End["stats"]!["transformer"]!.GetValue<string>());
        }
    }

    private static System.Diagnostics.Process HogVram(int gb)
    {
        if (!ImagePaths.HasRoot) ImagePaths.Root = ImagePaths.ChooseDefaultRoot();
        var py = ImageRuntime.PythonExe;
        var psi = new System.Diagnostics.ProcessStartInfo(py) { UseShellExecute = false, RedirectStandardOutput = true, CreateNoWindow = true };
        psi.ArgumentList.Add("-c");
        // usa a memória o tempo todo (como um jogo): memória parada o Windows despeja para a RAM e não pressiona nada
        psi.ArgumentList.Add($"import torch,time;x=torch.zeros({gb}*1024**3,dtype=torch.uint8,device='cuda');print('ok',flush=True)\nwhile True:\n x[::65536].add_(1);torch.cuda.synchronize();time.sleep(0.02)");
        var proc = System.Diagnostics.Process.Start(psi)!;
        var line = proc.StandardOutput.ReadLine();
        Assert.Equal("ok", line);
        return proc;
    }
}
