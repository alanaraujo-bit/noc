using Noc.Core.Library;
using Noc.Core.Speech;
using Noc.Core.Storage;

namespace Noc.Core.Tests;

[Collection("e2e")] // um teste troca AppPaths.Root (estático)
public class LibraryTests
{
    [Theory]
    [InlineData("Qwen3.5 9B Abliterated Vision", "Qwen 3.5 9B")]
    [InlineData("Gemma4 26B A4B QAT Uncensored HauhauCS Balanced", "Gemma 4 26B-A4B")]
    [InlineData("Qwen3.8 27B Uncensored", "Qwen 3.8 27B")]
    [InlineData("Qwen3.5-9B-abliterated-vision-Q4_K_M", "Qwen 3.5 9B")]
    [InlineData("Meta-Llama-3.1-8B-Instruct", "Meta Llama 3.1 8B")]
    [InlineData("gpt-oss-20b", "GPT OSS 20B")]
    public void Friendly_names(string raw, string expected) => Assert.Equal(expected, ModelNames.Friendly(raw, null, null));

    [Theory]
    [InlineData("Qwen3.5-9B-abliterated-vision-Q4_K_M.gguf", "Qwen3.5-9B")]
    [InlineData("Gemma4-26B-A4B-QAT-Uncensored-HauhauCS-Balanced-Q4_K_M.gguf", "Gemma4-26B-A4B")]
    [InlineData("modelo-sem-tamanho.gguf", null)]
    public void Base_model_name(string file, string? expected) => Assert.Equal(expected, ModelLibrary.BaseModelName(file));

    [Fact]
    public void Repo_name_strips_quantization()
    {
        Assert.Equal("Qwen3.5-9B-abliterated-vision-GGUF", ModelLibrary.RepoNameFor(@"C:\x\Qwen3.5-9B-abliterated-vision-Q4_K_M.gguf"));
        Assert.Equal("mmproj-Gemma4-26B-GGUF", ModelLibrary.RepoNameFor(@"C:\x\mmproj-Gemma4-26B-BF16.gguf"));
    }

    [Theory]
    [InlineData("26B-A4B", 26, 4)]
    [InlineData("9B", 9, 9)]
    [InlineData("270M", 0.27, 0.27)]
    public void Params_parsing(string s, double total, double active)
    {
        Assert.Equal(total, ModelNames.ParamsB(s)!.Value, 3);
        Assert.Equal(active, ModelNames.ActiveB(s)!.Value, 3);
    }

    /// <summary>O GGUF real do usuário (se existir nesta máquina) precisa ser detectado como reparável.</summary>
    [Fact]
    public void Real_legacy_qwen35_file_needs_repair()
    {
        var f = Path.Combine(ModelLibrary.DownloadsDir, "Qwen3.5-9B-abliterated-vision-Q4_K_M.gguf");
        if (!File.Exists(f)) return;
        var h = GgufFile.Read(f);
        Assert.Equal("qwen35", h.Architecture);
        Assert.True(h.HasEmbeddedVision);
        var plan = GgufRepair.Analyze(h);
        Assert.True(plan.NeedsRepair);
        Assert.Contains(plan.KvEdits, e => e.Key == "qwen35.rope.dimension_sections");
        Assert.NotEmpty(plan.RenameTensors);
        Assert.Contains(plan.DropTensors, t => t.StartsWith("v."));
    }

    [Fact]
    public void Repaired_file_in_library_is_clean()
    {
        var f = Path.Combine(ModelLibrary.LmModelsDir, "local", "Qwen3.5-9B-abliterated-vision-GGUF", "Qwen3.5-9B-abliterated-vision-Q4_K_M.gguf");
        if (!File.Exists(f)) return;
        Assert.False(GgufRepair.Analyze(GgufFile.Read(f)).NeedsRepair);
    }

    [Fact]
    public void Projector_header_is_recognized()
    {
        var f = Path.Combine(ModelLibrary.DownloadsDir, "mmproj-Gemma4-26B-A4B-QAT-Uncensored-HauhauCS-Balanced-BF16.gguf");
        if (!File.Exists(f)) return;
        var h = GgufFile.Read(f);
        Assert.True(h.IsProjector);
        Assert.Equal(2816, h.ProjectionDim);
    }

    [Theory]
    [InlineData(".", "")]
    [InlineData(" Legendas pela comunidade Amara.org ", "")]
    [InlineData("Obrigado por assistir!", "")]
    [InlineData("[Música]", "")]
    [InlineData("Configura a VLAN 102.", "Configura a VLAN 102.")]
    public void Stt_cleanup(string raw, string expected) => Assert.Equal(expected, SttService.Clean(raw, []));

    [Fact]
    public void Stt_personal_dictionary_fixes_close_spellings()
    {
        string[] vocab = ["Aionix", "MikroTik", "PostgreSQL", "Qwen"];
        Assert.Equal("O deploy do Aionix quebrou", SttService.ApplyVocab("O deploy do Ionix quebrou", vocab));
        Assert.Equal("O deploy do Aionix quebrou", SttService.ApplyVocab("O deploy do Ioniqs quebrou", vocab));
        Assert.Equal("Usei o Qwen ontem", SttService.ApplyVocab("Usei o Quen ontem", vocab));
        Assert.Equal("Configura o MikroTik", SttService.ApplyVocab("Configura o Mikrotik", vocab));
        // palavras comuns (minúsculas) não são tocadas
        Assert.Equal("uma pequena questão", SttService.ApplyVocab("uma pequena questão", vocab));
    }

    [Fact]
    public async Task Planner_uses_measured_vram_instead_of_pessimistic_estimate()
    {
        // números reais deste PC: Qwen 3.8 27B Q4_K_M, RTX 3090 Ti, ~2,6 GiB ocupados pelo resto do sistema
        var catalog = new ModelCatalog();
        var model = new Noc.Core.LmStudio.LmModel("teste-planner-" + Guid.NewGuid().ToString("N")[..6], "Teste", "llm", "qwen35",
            "Q4_K_M", 16_000_000_000, "27B", 262144, false, false, [], null, []);
        var est = new Dictionary<int, double> { [65536] = 22.9, [49152] = 21.96, [32768] = 21.02, [24576] = 20.09, [16384] = 19.6, [12288] = 19.4, [8192] = 19.1, [4096] = 18.9 };
        foreach (var (ctx, gib) in est) catalog.Data.Estimates[$"{model.Key}|{model.SizeBytes}|{ctx}"] = gib;

        var before = await LoadPlanner.PlanAsync(catalog, model, Tiers.Deep, null, 23.99, 2.6, 0, CancellationToken.None);
        Assert.Equal(24576, before.Context);

        // carregado com 24k ocupou 18,3 GiB (a estimativa dizia 20,09): o próximo plano usa o real + margem
        catalog.Prefs(model.Key).VramFactor = Math.Round(18.3 / 20.09 + 0.02, 3);
        var after = await LoadPlanner.PlanAsync(catalog, model, Tiers.Deep, null, 23.99, 2.6, 0, CancellationToken.None);
        Assert.True(after.Context >= 32768);
        Assert.True(after.EstimateGiB + LoadPlanner.SafetyGiB <= 23.99 - 2.6);

        // fator absurdo (medida ruim) não deixa o plano passar do que cabe
        catalog.Prefs(model.Key).VramFactor = 0.1;
        var clamped = await LoadPlanner.PlanAsync(catalog, model, Tiers.Deep, null, 23.99, 2.6, 0, CancellationToken.None);
        Assert.True(clamped.EstimateGiB >= 0.8 * est[clamped.Context] - 0.01);
    }

    [Fact]
    public void Profiles_never_pick_a_quant_that_may_not_fit_while_vram_is_unknown()
    {
        AppPaths.Root = Path.Combine(Path.GetTempPath(), "noc-catalog-" + Guid.NewGuid().ToString("N")[..8]);
        Directory.CreateDirectory(AppPaths.Root);
        static Noc.Core.LmStudio.LmModel M(string key, string name, string p, double gb, bool vision = false) =>
            new(key, name, "llm", "x", null, (long)(gb * 1073741824), p, 32768, vision, false, [], null, []);
        var models = new[]
        {
            M("qwen3.5-9b", "Qwen3.5 9B Abliterated Vision", "9B", 5.6, vision: true),
            M("gemma4-26b-a4b", "Gemma4 26B A4B", "26B", 16.1, vision: true),
            M("qwen3.8-27b@q4_k_m", "Qwen3.8 27B Uncensored", "27B", 15.9),
            M("qwen3.8-27b@q8_0", "Qwen3.8 27B Uncensored", "27B", 27.0),
        };
        var catalog = new ModelCatalog();

        // logo ao ligar (GPU ainda não lida): a quantização mais leve
        catalog.AutoAssign(models, 0);
        Assert.Equal("qwen3.8-27b@q4_k_m", catalog.ModelForTier(Tiers.Deep));
        Assert.Equal("qwen3.5-9b", catalog.ModelForTier(Tiers.Fast));
        Assert.Equal("gemma4-26b-a4b", catalog.ModelForTier(Tiers.Smart));

        // 24 GB: o Q8 (27 GiB) não cabe
        catalog.AutoAssign(models, 24);
        Assert.Equal("qwen3.8-27b@q4_k_m", catalog.ModelForTier(Tiers.Deep));

        // 48 GB: agora a melhor quantização cabe
        catalog.AutoAssign(models, 48);
        Assert.Equal("qwen3.8-27b@q8_0", catalog.ModelForTier(Tiers.Deep));
    }
}
