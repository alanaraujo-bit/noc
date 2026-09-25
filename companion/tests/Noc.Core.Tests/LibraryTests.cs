using Noc.Core.Library;
using Noc.Core.Speech;

namespace Noc.Core.Tests;

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
}
