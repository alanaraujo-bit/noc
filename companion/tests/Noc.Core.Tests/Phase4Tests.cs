using System.Security.Cryptography;
using System.Text.Json.Nodes;
using Noc.Core.ImageEngine;
using Xunit.Abstractions;
using static Noc.Core.Tests.ImageEngineTests;

namespace Noc.Core.Tests;

/// <summary>
/// Fase 4 — especialistas SDXL, Prompt Compiler, LoRAs, edição e metadata, com a RTX e os modelos reais (NOC_E2E=1),
/// sempre pelo caminho do celular: sessão cifrada → image.start → fila → motor → worker → GPU → PNG → image.get.
/// </summary>
[Collection("e2e")]
public class Phase4Tests(ITestOutputHelper log)
{
    private static bool Enabled => Environment.GetEnvironmentVariable("NOC_E2E") == "1";
    private static string Fixtures => Path.Combine(AppContext.BaseDirectory, "fixtures");
    /// <summary>Imagem gerada pelo P1 (RealVisXL), reaproveitada nos testes de edição que rodam no mesmo PC.</summary>
    private static string SharedRoot => Path.Combine(Path.GetTempPath(), "noc-e2e-p4");

    private static async Task<Paired> P4Async(Action<CompanionHost>? tweak = null)
    {
        // uma pasta de dados só para a fase 4: LoRAs importados e imagens ficam entre os testes (como no uso real)
        var fresh = !Directory.Exists(SharedRoot);
        Directory.CreateDirectory(SharedRoot);
        if (fresh && File.Exists(Seed)) File.Copy(Seed, Path.Combine(SharedRoot, "image-models.json"), overwrite: true);
        var dev = Path.Combine(SharedRoot, "device.key");
        ECDsa device;
        if (File.Exists(dev)) { device = ECDsa.Create(); device.ImportECPrivateKey(File.ReadAllBytes(dev), out _); }
        else { device = ECDsa.Create(ECCurve.NamedCurves.nistP256); File.WriteAllBytes(dev, device.ExportECPrivateKey()); fresh = true; }
        // primeiro uso: pareia por QR nesta pasta de dados
        return await PairedAsync(SharedRoot, device, seed: false, tweak: tweak, pairNow: fresh || !File.Exists(Path.Combine(SharedRoot, "devices.json")));
    }

    private static async Task<(JsonObject Start, Run Run)> Gen(Paired p, JsonObject req, TimeSpan? max = null)
    {
        var job = Guid.NewGuid().ToString();
        req["job"] = job;
        var res = await p.Client.CallAsync("image.start", req);
        var start = Ok(res);
        var run = await Collect(p.Client, job, max ?? TimeSpan.FromMinutes(15));
        return (start, run);
    }

    private static JsonObject Img(Run r, int i = 0) => r.End["images"]!.AsArray()[i]!.AsObject();

    private static async Task<string> Blob(Paired p, byte[] data)
    {
        var hash = Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant();
        const int chunk = 256 * 1024;
        for (var off = 0; off < data.Length; off += chunk)
        {
            var part = data.AsSpan(off, Math.Min(chunk, data.Length - off)).ToArray();
            Ok(await p.Client.CallAsync("blob.put", new JsonObject { ["hash"] = hash, ["total"] = data.Length, ["offset"] = off, ["data"] = Convert.ToBase64String(part) }));
        }
        return hash;
    }

    /// <summary>Confere que a imagem não saiu preta/NaN nem ruído puro (desvio e média razoáveis).</summary>
    private void AssertRealImage(byte[] png, string what)
    {
        Assert.Equal(new byte[] { 0x89, 0x50, 0x4E, 0x47 }, png[..4]);
        Assert.True(png.Length > 150_000, $"{what}: PNG pequeno demais ({png.Length} bytes) — imagem lisa/preta?");
        log.WriteLine($"{what}: {png.Length / 1024} KB");
    }

    private static string Short(string s, int n = 160) => s.Length > n ? s[..n] + "…" : s;

    [Fact]
    public async Task P0_Installs_specialists_and_tools_adopting_verified_files()
    {
        if (!Enabled) return;
        if (Directory.Exists(SharedRoot)) Directory.Delete(SharedRoot, true);
        await using var p = await P4Async();
        var st = p.Host.Images.Store;
        var all = new[] { ImageCatalog.RealVisXL, ImageCatalog.NoobAI, ImageCatalog.PonyV6 }.Concat(ImageCatalog.AuxPackages).ToList();
        // instalação automática, um por vez; arquivos já presentes são conferidos pelo SHA-256 e adotados
        await WaitAsync(() => all.All(st.IsInstalled), TimeSpan.FromMinutes(40), "especialistas e componentes instalados");
        var status = Ok(await p.Client.CallAsync("image.status"));
        var models = status["models"]!.AsArray().OfType<JsonObject>().ToList();
        foreach (var id in new[] { "realvisxl-v5", "noobai-xl-vpred-1", "pony-v6-xl" })
        {
            var m = models.First(x => x["id"]!.GetValue<string>() == id);
            log.WriteLine($"{id}: {m["state"]} ready={m["ready"]} {m["tagline"]} preset={m["preset"]!.ToJsonString()}");
            Assert.True(m["ready"]!.GetValue<bool>());
            Assert.Equal("specialist", m["profile"]!.GetValue<string>());
        }
        Assert.DoesNotContain(models, m => m["aux"]?.GetValue<bool>() == true); // auxiliares ficam à parte (app 1.2 não os vê como modelos)
        var tools = status["tools"]!.AsObject();
        log.WriteLine("ferramentas: " + tools.ToJsonString());
        foreach (var (k, v) in tools) Assert.True(v!.GetValue<bool>(), k);
        File.Copy(Path.Combine(SharedRoot, "image-models.json"), Seed, overwrite: true);
    }

    [Fact]
    public async Task P1_RealVisXL_from_portuguese_with_model_preset_and_full_metadata()
    {
        if (!Enabled) return;
        await using var p = await P4Async();
        var (start, run) = await Gen(p, new JsonObject
        {
            ["prompt"] = "Foto de um velho pescador consertando uma rede num píer de madeira ao pôr do sol, pele com textura detalhada",
            ["model"] = "realvisxl-v5", ["format"] = "portrait", ["quality"] = "standard", ["seed"] = 1234,
            ["meta"] = new JsonObject { ["conv"] = "c-p1" },
        });
        log.WriteLine("start: " + start.ToJsonString());
        log.WriteLine("fim: " + Short(run.End.ToJsonString(), 1500));
        Assert.Equal("stop", run.End["reason"]!.GetValue<string>());
        var prompt = run.End["prompt"]!.AsObject();
        Assert.Equal("natural", prompt["strategy"]!.GetValue<string>());
        Assert.Contains("fisherman", prompt["final"]!.GetValue<string>(), StringComparison.OrdinalIgnoreCase);
        Assert.Contains("face asymmetry", prompt["negative"]!.GetValue<string>());
        Assert.Contains(run.Events, e => e["step"]?.GetValue<int>() == 30);
        Assert.True(run.Previews.Count > 0, "prévias do SDXL (latente de 4 canais)");
        var img = Img(run);
        Assert.Equal(832, img["width"]!.GetValue<int>());
        var png = await Download(p.Client, img["id"]!.GetValue<string>(), "full");
        AssertRealImage(png, "RealVisXL");
        var meta = Ok(await p.Client.CallAsync("image.meta", new JsonObject { ["id"] = img["id"]!.GetValue<string>() }));
        log.WriteLine("meta: " + meta.ToJsonString());
        Assert.Equal("dpmpp_2m_sde", meta["sampler"]!.GetValue<string>());
        Assert.Equal("karras", meta["schedule"]!.GetValue<string>());
        Assert.Equal(ImageCatalog.RealVisXL.Sha256, meta["modelSha256"]!.GetValue<string>());
        Assert.Equal(1234, meta["seed"]!.GetValue<long>());
        Assert.Equal("txt2img", meta["op"]!.GetValue<string>());
        Assert.Equal(5, meta["guidance"]!.GetValue<double>());
        Assert.NotNull(meta["finalPrompt"]);
        Assert.NotNull(meta["wallMs"]);
        File.WriteAllText(Path.Combine(SharedRoot, "p1-image.txt"), img["id"]!.GetValue<string>());
    }

    [Fact]
    public async Task P2_Prompt_compiler_tags_for_NoobAI_and_score_tags_for_Pony_and_RAW_untouched()
    {
        if (!Enabled) return;
        await using var p = await P4Async();
        var (_, noob) = await Gen(p, new JsonObject
        {
            ["prompt"] = "Uma garota de cabelo prateado e olhos azuis, uniforme escolar, sorrindo debaixo de cerejeiras", ["model"] = "noobai-xl-vpred-1",
            ["format"] = "portrait", ["seed"] = 7,
        });
        var nf = noob.End["prompt"]!["final"]!.GetValue<string>();
        log.WriteLine("NoobAI final: " + nf);
        Assert.StartsWith("masterpiece, best quality, newest, absurdres, highres, safe, ", nf);
        Assert.Contains("silver hair", nf);
        Assert.Contains("nsfw", noob.End["prompt"]!["negative"]!.GetValue<string>());
        AssertRealImage(await Download(p.Client, Img(noob)["id"]!.GetValue<string>(), "full"), "NoobAI");

        var (_, pony) = await Gen(p, new JsonObject
        {
            ["prompt"] = "um dragão fofo estilo anime voando sobre um castelo", ["model"] = "pony-v6-xl", ["seed"] = 7,
        });
        var pf = pony.End["prompt"]!["final"]!.GetValue<string>();
        log.WriteLine("Pony final: " + pf);
        Assert.StartsWith("score_9, score_8_up, score_7_up, score_6_up, score_5_up, score_4_up, source_anime, rating_safe, ", pf);
        Assert.Contains("dragon", pf, StringComparison.OrdinalIgnoreCase);
        AssertRealImage(await Download(p.Client, Img(pony)["id"]!.GetValue<string>(), "full"), "Pony");

        var raw = "a lighthouse on a cliff, (dramatic sky:1.2), BREAK storm, waves";
        var (_, r) = await Gen(p, new JsonObject { ["prompt"] = raw, ["model"] = "realvisxl-v5", ["raw"] = true, ["negative"] = "blurry", ["seed"] = 3, ["quality"] = "fast" });
        Assert.Equal(raw, r.End["prompt"]!["final"]!.GetValue<string>());
        Assert.Equal("blurry", r.End["prompt"]!["negative"]!.GetValue<string>());
        Assert.Equal("raw", r.End["prompt"]!["strategy"]!.GetValue<string>());
    }

    [Fact]
    public async Task P3_Automatic_routing_picks_the_specialist_and_explains_why()
    {
        if (!Enabled) return;
        await using var p = await P4Async();
        var cases = new (string Prompt, string Model)[]
        {
            ("garota estilo anime com orelhas de gato, cel shading", "pony-v6-xl"),
            ("ilustração de uma personagem guerreira com armadura, fan art", "noobai-xl-vpred-1"),
            ("retrato fotorrealista de uma mulher idosa, luz de janela, 85mm", "realvisxl-v5"),
            ("uma placa escrito \"PADARIA NOC\" numa rua de pedra", "z-image-turbo"),
        };
        foreach (var (prompt, model) in cases)
        {
            var job = Guid.NewGuid().ToString();
            var s = Ok(await p.Client.CallAsync("image.start", new JsonObject { ["job"] = job, ["prompt"] = prompt, ["engine"] = "auto", ["quality"] = "fast" }));
            log.WriteLine($"{prompt} → {s["model"]} ({s["note"]})");
            Assert.Equal(model, s["model"]!.GetValue<string>());
            Assert.False(string.IsNullOrEmpty(s["note"]?.GetValue<string>()));
            await p.Client.CallAsync("chat.cancel", new JsonObject { ["job"] = job });
        }
    }

    [Fact]
    public async Task P4_LoRA_imported_by_link_applies_without_syntax_and_recreate_is_bit_exact()
    {
        if (!Enabled) return;
        await using var p = await P4Async();
        var url = "https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0/blob/462165984030d82259a11f4367a4eed129e94a7b/sd_xl_offset_example-lora_1.0.safetensors";
        var key = Ok(await p.Client.CallAsync("image.import", new JsonObject { ["url"] = url }))["key"]!.GetValue<string>();
        JsonObject? done = null;
        await WaitAsync(() =>
        {
            done = p.Host.Images.Library.ImportsJson().OfType<JsonObject>().FirstOrDefault(i => i["key"]!.GetValue<string>() == key && i["state"]!.GetValue<string>() is "done" or "failed");
            return done is not null;
        }, TimeSpan.FromMinutes(10), "importação do LoRA");
        log.WriteLine("importação: " + done!.ToJsonString());
        Assert.Equal("done", done["state"]!.GetValue<string>());
        Assert.Equal("lora", done["kind"]!.GetValue<string>());
        var list = Ok(await p.Client.CallAsync("lora.list", new JsonObject { ["model"] = "realvisxl-v5" }))["loras"]!.AsArray().OfType<JsonObject>().ToList();
        var lora = list.First(l => l["id"]!.GetValue<string>() == done["id"]!.GetValue<string>());
        log.WriteLine("LoRA: " + lora.ToJsonString());
        Assert.Equal("sdxl", lora["arch"]!.GetValue<string>());
        Assert.True(lora["compatible"]!.GetValue<bool>());
        // de novo pelo mesmo link: não duplica (mesmo hash)
        Ok(await p.Client.CallAsync("lora.update", new JsonObject { ["id"] = lora["id"]!.DeepClone(), ["favorite"] = true, ["weight"] = 0.9 }));

        var (_, withLora) = await Gen(p, new JsonObject
        {
            ["prompt"] = "a lighthouse at night on a rocky coast", ["model"] = "realvisxl-v5", ["seed"] = 55, ["quality"] = "fast",
            ["loras"] = new JsonArray(new JsonObject { ["id"] = lora["id"]!.DeepClone(), ["weight"] = 0.9 }),
        });
        Assert.Equal("stop", withLora.End["reason"]!.GetValue<string>());
        Assert.Single(withLora.End["loras"]!.AsArray());
        var a = Img(withLora);
        var meta = Ok(await p.Client.CallAsync("image.meta", new JsonObject { ["id"] = a["id"]!.GetValue<string>() }));
        Assert.Equal(lora["sha256"]!.GetValue<string>(), meta["loras"]![0]!["sha256"]!.GetValue<string>());
        Assert.Equal(0.9, meta["loras"]![0]!["weight"]!.GetValue<double>());

        // RECRIAR: mesmo modelo, prompt final, LoRA, seed e parâmetros → exatamente a mesma imagem
        var (_, again) = await Gen(p, new JsonObject { ["recreate"] = a["id"]!.DeepClone() });
        log.WriteLine("recriada: " + Short(again.End.ToJsonString(), 600));
        Assert.Equal(a["sha256"]!.GetValue<string>(), Img(again)["sha256"]!.GetValue<string>());

        // sem o LoRA (mesma seed): outra imagem — e nada do LoRA ficou grudado no modelo
        var (_, without) = await Gen(p, new JsonObject { ["prompt"] = "a lighthouse at night on a rocky coast", ["model"] = "realvisxl-v5", ["seed"] = 55, ["quality"] = "fast" });
        Assert.NotEqual(a["sha256"]!.GetValue<string>(), Img(without)["sha256"]!.GetValue<string>());
        var (_, without2) = await Gen(p, new JsonObject { ["prompt"] = "a lighthouse at night on a rocky coast", ["model"] = "realvisxl-v5", ["seed"] = 55, ["quality"] = "fast" });
        Assert.Equal(Img(without)["sha256"]!.GetValue<string>(), Img(without2)["sha256"]!.GetValue<string>());
    }

    [Fact]
    public async Task P5_Edits_variation_inpaint_outpaint_upscale_detail_and_hd()
    {
        if (!Enabled) return;
        await using var p = await P4Async();
        // origem: a foto do pescador enviada pelo celular (blob), como vinda da galeria
        var person = await Blob(p, File.ReadAllBytes(Path.Combine(Fixtures, "person.jpg")));
        var mask = await Blob(p, File.ReadAllBytes(Path.Combine(Fixtures, "mask_hat.png")));
        var src = "blob:" + person;

        var (_, v) = await Gen(p, new JsonObject { ["op"] = "img2img", ["source"] = src, ["prompt"] = "old fisherman mending a net, golden hour photo", ["model"] = "realvisxl-v5", ["strength"] = 0.45, ["count"] = 2, ["width"] = 832, ["height"] = 1216 });
        Assert.Equal(2, v.End["images"]!.AsArray().Count);
        Assert.Equal(832, Img(v)["width"]!.GetValue<int>());

        var (_, inp) = await Gen(p, new JsonObject { ["op"] = "inpaint", ["source"] = src, ["mask"] = "blob:" + mask, ["prompt"] = "old man wearing a bright red wool beanie", ["model"] = "realvisxl-v5", ["width"] = 832, ["height"] = 1216 });
        Assert.Equal("stop", inp.End["reason"]!.GetValue<string>());
        Assert.Equal(1216, Img(inp)["height"]!.GetValue<int>());

        var (_, outp) = await Gen(p, new JsonObject { ["op"] = "outpaint", ["source"] = src, ["expand"] = new JsonArray(0, 0, 384, 0), ["prompt"] = "wooden pier on a lake at sunset", ["model"] = "realvisxl-v5", ["width"] = 832, ["height"] = 1216 });
        Assert.Equal(832 + 384, Img(outp)["width"]!.GetValue<int>());
        var outId = Img(outp)["id"]!.GetValue<string>();

        // ampliação simples de uma imagem do PC (sem modelo de difusão)
        var (_, up) = await Gen(p, new JsonObject { ["op"] = "upscale", ["source"] = "img:" + outId, ["upscale"] = 2 });
        Assert.Equal((832 + 384) * 2, Img(up)["width"]!.GetValue<int>());
        var upMeta = Ok(await p.Client.CallAsync("image.meta", new JsonObject { ["id"] = Img(up)["id"]!.GetValue<string>() }));
        Assert.Equal(outId, upMeta["parent"]!.GetValue<string>());

        var (_, det) = await Gen(p, new JsonObject { ["op"] = "detail", ["source"] = src, ["model"] = "realvisxl-v5", ["width"] = 832, ["height"] = 1216 });
        log.WriteLine("detail: " + Short(det.End.ToJsonString(), 600));
        Assert.Contains(det.Events, e => e["stage"]?.GetValue<string>() == "detailing");

        var (_, hd) = await Gen(p, new JsonObject { ["op"] = "hd", ["source"] = src, ["model"] = "realvisxl-v5", ["prompt"] = "old fisherman on a pier, detailed photo", ["width"] = 832, ["height"] = 1216 });
        Assert.Equal(1664, Img(hd)["width"]!.GetValue<int>());
        Assert.Contains(hd.Events, e => e["stage"]?.GetValue<string>() == "refining");
        AssertRealImage(await Download(p.Client, Img(hd)["id"]!.GetValue<string>(), "full"), "HD");
    }

    [Fact]
    public async Task P6_Pose_depth_and_reference_guide_the_specialists()
    {
        if (!Enabled) return;
        await using var p = await P4Async();
        var person = "blob:" + await Blob(p, File.ReadAllBytes(Path.Combine(Fixtures, "person.jpg")));
        var (_, pose) = await Gen(p, new JsonObject
        {
            ["prompt"] = "um astronauta ajoelhado em marte", ["model"] = "realvisxl-v5", ["format"] = "portrait", ["seed"] = 3,
            ["control"] = new JsonObject { ["type"] = "pose", ["source"] = person, ["scale"] = 0.9 },
        });
        Assert.Equal("stop", pose.End["reason"]!.GetValue<string>());
        Assert.Contains(pose.Events, e => e["stage"]?.GetValue<string>() == "analyzing");
        var (_, depth) = await Gen(p, new JsonObject
        {
            ["prompt"] = "anime character sculpture made of glass", ["model"] = "noobai-xl-vpred-1", ["format"] = "portrait", ["seed"] = 3,
            ["control"] = new JsonObject { ["type"] = "depth", ["source"] = person, ["scale"] = 0.6 },
        });
        Assert.Equal("stop", depth.End["reason"]!.GetValue<string>());
        var (_, refr) = await Gen(p, new JsonObject
        {
            ["prompt"] = "portrait of a sailor", ["model"] = "realvisxl-v5", ["seed"] = 3, ["reference"] = new JsonObject { ["source"] = person, ["scale"] = 0.5 },
        });
        Assert.Equal("stop", refr.End["reason"]!.GetValue<string>());
        // sem pessoa na referência: explica em vez de gerar qualquer coisa
        var mask = await Blob(p, File.ReadAllBytes(Path.Combine(Fixtures, "mask_hat.png")));
        var (_, noPose) = await Gen(p, new JsonObject
        {
            ["prompt"] = "a dancer", ["model"] = "realvisxl-v5", ["control"] = new JsonObject { ["type"] = "pose", ["source"] = "blob:" + mask },
        });
        log.WriteLine("sem pessoa: " + noPose.End.ToJsonString());
        Assert.Equal("error", noPose.End["reason"]!.GetValue<string>());
        Assert.Contains("ninguém", noPose.End["detail"]!.GetValue<string>());
    }

    [Fact]
    public async Task P7_Queue_of_mixed_models_cancel_and_switching_never_ooms()
    {
        if (!Enabled) return;
        await using var p = await P4Async();
        var jobs = new List<string>();
        foreach (var (model, prompt) in new[] { ("pony-v6-xl", "a cat knight"), ("z-image-turbo", "a red bicycle"), ("noobai-xl-vpred-1", "1girl, reading a book"), ("realvisxl-v5", "a bowl of ramen") })
        {
            var job = Guid.NewGuid().ToString();
            Ok(await p.Client.CallAsync("image.start", new JsonObject { ["job"] = job, ["prompt"] = prompt, ["model"] = model, ["quality"] = "fast" }));
            jobs.Add(job);
        }
        // a terceira é cancelada enquanto espera na fila
        await p.Client.CallAsync("chat.cancel", new JsonObject { ["job"] = jobs[2] });
        var reasons = new List<string>();
        foreach (var j in jobs)
        {
            var r = await Collect(p.Client, j, TimeSpan.FromMinutes(15));
            reasons.Add(r.End["reason"]!.GetValue<string>());
            log.WriteLine($"{j[..8]}: {r.End["reason"]} {r.End["name"]} {r.End["stats"]?["wallMs"]} ms");
        }
        Assert.Equal(new[] { "stop", "stop", "cancelled", "stop" }, reasons);
        // cancelar no meio da geração solta a GPU e a próxima segue
        var mid = Guid.NewGuid().ToString();
        Ok(await p.Client.CallAsync("image.start", new JsonObject { ["job"] = mid, ["prompt"] = "a castle", ["model"] = "realvisxl-v5", ["quality"] = "high" }));
        var cancelled = await Collect(p.Client, mid, TimeSpan.FromMinutes(5), onEvent: async e =>
        {
            if (e["step"]?.GetValue<int>() == 5) await p.Client.CallAsync("chat.cancel", new JsonObject { ["job"] = mid });
        });
        Assert.Equal("cancelled", cancelled.End["reason"]!.GetValue<string>());
        var metrics = File.ReadAllLines(Path.Combine(SharedRoot, "image-metrics.jsonl")).Select(l => JsonNode.Parse(l)!.AsObject()).ToList();
        Assert.DoesNotContain(metrics, m => m["kind"]?.GetValue<string>() == "oom");
    }
}
