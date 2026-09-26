using System.Text;
using System.Text.Json.Nodes;
using Noc.Core.ImageEngine;

namespace Noc.Core.Tests;

/// <summary>Fase 4 sem GPU: Prompt Compiler, roteamento automático, detecção pelo cabeçalho safetensors e compatibilidade de LoRA.</summary>
public class Phase4UnitTests
{
    private static LoraEntry Lora(string family = "sdxl", string arch = "sdxl", params string[] triggers) =>
        new() { Id = "l1", Name = "teste", Arch = arch, Family = family, Triggers = [.. triggers], File = "x" };

    [Fact]
    public void Tags_get_model_prefix_lose_invented_style_and_fix_fake_counts()
    {
        var c = PromptCompiler.Compile(ImageCatalog.NoobAI, "garota lendo na biblioteca",
            "1girl, solo, reading, library, realistic art style, clothing, 3d, masterpiece", "tags", false, null, []);
        Assert.Equal("masterpiece, best quality, newest, absurdres, highres, safe, 1girl, solo, reading, library", c.Positive);
        Assert.Contains("nsfw", c.Negative);
        // estilo pedido pela pessoa fica
        var w = PromptCompiler.Compile(ImageCatalog.NoobAI, "menina estilo aquarela", "1girl, watercolor style", "tags", false, null, []);
        Assert.Contains("watercolor style", w.Positive);
    }

    [Fact]
    public void Pony_gets_score_tags_source_from_the_original_request_and_rating()
    {
        var c = PromptCompiler.Compile(ImageCatalog.PonyV6, "um dragão estilo anime", "A cute dragon, cartoon style, 1dragon, flying", "pony", false, null, []);
        Assert.StartsWith("score_9, score_8_up, score_7_up, score_6_up, score_5_up, score_4_up, source_anime, rating_safe, ", c.Positive);
        Assert.Contains("dragon, flying", c.Positive);
        Assert.DoesNotContain("1dragon", c.Positive);
        Assert.Equal("source_furry", PromptCompiler.PonySource("um lobo furry"));
        Assert.Equal("source_cartoon", PromptCompiler.PonySource("estilo cartoon"));
    }

    [Fact]
    public void Raw_is_untouched_and_uses_only_the_user_negative()
    {
        const string p = "(masterpiece:1.2), 1girl, BREAK night";
        var c = PromptCompiler.Compile(ImageCatalog.NoobAI, p, null, null, true, "bad hands", [Lora("sdxl", "sdxl", "trig")]);
        Assert.Equal(p, c.Positive);
        Assert.Equal("bad hands", c.Negative);
        Assert.Null(PromptCompiler.RewriteMode(ImageCatalog.PonyV6, "qualquer coisa", false, true));
    }

    [Fact]
    public void Lora_triggers_enter_automatically_once()
    {
        var c = PromptCompiler.Compile(ImageCatalog.RealVisXL, "a lighthouse", "a lighthouse at night", "translate", false, null, [Lora("sdxl", "sdxl", "contrast", "a lighthouse")]);
        Assert.Equal("a lighthouse at night, contrast", c.Positive);
        Assert.Equal(["contrast"], c.Added);
    }

    [Fact]
    public void Rewrite_mode_follows_the_model_strategy()
    {
        Assert.Equal("tags", PromptCompiler.RewriteMode(ImageCatalog.NoobAI, "uma garota", false, false));
        Assert.Null(PromptCompiler.RewriteMode(ImageCatalog.NoobAI, "1girl, solo, silver hair, reading, library", false, false));
        Assert.Equal("pony", PromptCompiler.RewriteMode(ImageCatalog.PonyV6, "um gato", false, false));
        Assert.Equal("translate", PromptCompiler.RewriteMode(ImageCatalog.RealVisXL, "foto de um gato", false, false));
        Assert.Equal("enhance", PromptCompiler.RewriteMode(ImageCatalog.RealVisXL, "a cat photo", true, false));
    }

    [Fact]
    public void Router_prefers_lora_family_then_source_model_then_content()
    {
        bool All(ImageModelDef _) => true;
        Assert.Equal("pony-v6-xl", ImageRouter.Choose("qualquer", "standard", 1 << 20, All, "pony", null).Model!.Id);
        Assert.Equal("noobai-xl-vpred-1", ImageRouter.Choose("qualquer", "standard", 1 << 20, All, "illustrious", null).Model!.Id);
        Assert.Equal("realvisxl-v5", ImageRouter.Choose("qualquer", "standard", 1 << 20, All, null, "realvisxl-v5").Model!.Id);
        Assert.Equal("z-image-turbo", ImageRouter.Choose("um gato no sofá", "standard", 1 << 20, All, null, null).Model!.Id);
        Assert.Equal("flux1-dev", ImageRouter.Choose("um castelo nas nuvens", "high", 1 << 20, All, null, null).Model!.Id);
        // expandir uma foto do Z-Image: o especialista de fotos faz (o Z-Image deixa costura); pincel continua no Z-Image
        Assert.Equal("realvisxl-v5", ImageRouter.Choose("x", "standard", 1 << 20, All, null, "z-image-turbo", "outpaint").Model!.Id);
        Assert.Equal("z-image-turbo", ImageRouter.Choose("x", "standard", 1 << 20, All, null, "z-image-turbo", "inpaint").Model!.Id);
        // sem os especialistas instalados, cai no que houver
        Assert.Equal("z-image-turbo", ImageRouter.Choose("garota anime", "standard", 1 << 20, m => m.Id == "z-image-turbo", null, null).Model!.Id);
    }

    private static string WriteSafetensors(Dictionary<string, string> meta, params string[] keys)
    {
        var header = new JsonObject();
        if (meta.Count > 0) header["__metadata__"] = new JsonObject(meta.Select(kv => KeyValuePair.Create(kv.Key, (JsonNode?)kv.Value)));
        foreach (var k in keys) header[k] = new JsonObject { ["dtype"] = "F16", ["shape"] = new JsonArray(1), ["data_offsets"] = new JsonArray(0, 2) };
        var h = Encoding.UTF8.GetBytes(header.ToJsonString());
        var path = Path.Combine(Path.GetTempPath(), "noc-st-" + Guid.NewGuid().ToString("N")[..8] + ".safetensors");
        using var fs = File.Create(path);
        fs.Write(BitConverter.GetBytes((long)h.Length));
        fs.Write(h);
        fs.Write(new byte[2]);
        return path;
    }

    [Fact]
    public void Detects_sdxl_lora_family_and_triggers_from_kohya_metadata()
    {
        var p = WriteSafetensors(new()
        {
            ["ss_base_model_version"] = "sdxl_base_v1-0", ["ss_sd_model_name"] = "ponyDiffusionV6XL_v6.safetensors",
            ["ss_tag_frequency"] = "{\"10_ohwx\": {\"ohwx\": 40, \"1girl\": 30}}",
        }, "lora_unet_input_blocks_4_1_proj_in.lora_down.weight", "lora_te2_text_model_encoder_layers_0_mlp_fc1.lora_up.weight");
        var d = ModelDetector.Detect(SafetensorsInfo.Read(p)!, "estilo");
        Assert.Equal("lora", d.Kind);
        Assert.Equal("sdxl", d.Arch);
        Assert.Equal("pony", d.Family);
        Assert.Equal(["ohwx"], d.Triggers);
    }

    [Fact]
    public void Kohya_lora_with_diffusers_names_is_sdxl_by_context_width()
    {
        var p = WriteSafetensors([], "lora_unet_down_blocks_1_attentions_0_proj_in.lora_down.weight",
            "lora_unet_down_blocks_2_attentions_0_transformer_blocks_9_attn2_to_k.lora_down.weight");
        Assert.Equal("sdxl", ModelDetector.Detect(SafetensorsInfo.Read(p)!, "papercut").Arch);
    }

    [Fact]
    public void Long_training_captions_never_become_triggers()
    {
        var p = WriteSafetensors(new()
        {
            ["ss_base_model_version"] = "sdxl_base_v1-0", ["ss_num_train_images"] = "40",
            ["ss_tag_frequency"] = "{\"1_px\": {\"pixelbuildings128 a spider with red eyes\": 1, \"pixel art\": 38, \"cat\": 3}}",
        }, "lora_unet_input_blocks_4_1_proj_in.lora_down.weight", "lora_te2_text_model_encoder_layers_0_mlp_fc1.lora_up.weight");
        Assert.Equal(["pixel art"], ModelDetector.Detect(SafetensorsInfo.Read(p)!, "x").Triggers);
    }

    [Fact]
    public void Detects_vpred_checkpoint_and_rejects_non_safetensors_garbage()
    {
        var p = WriteSafetensors([], "model.diffusion_model.input_blocks.0.0.weight", "conditioner.embedders.1.model.ln_final.weight", "v_pred", "ztsnr");
        var d = ModelDetector.Detect(SafetensorsInfo.Read(p)!, "algum-modelo");
        Assert.Equal("checkpoint", d.Kind);
        Assert.Equal("sdxl", d.Arch);
        Assert.Equal("v_prediction", d.Prediction);
        Assert.Equal("noobai", d.Family);
        var junk = Path.GetTempFileName();
        File.WriteAllText(junk, "isto não é safetensors");
        Assert.Null(SafetensorsInfo.Read(junk));
    }

    [Fact]
    public void Lora_compatibility_blocks_wrong_architecture_and_warns_on_other_family()
    {
        Assert.False(ImageLibrary.Compatible(Lora("sd15", "sd15"), ImageCatalog.RealVisXL).Ok);
        Assert.False(ImageLibrary.Compatible(Lora(), ImageCatalog.ZImageTurbo).Ok);
        var (ok, warn) = ImageLibrary.Compatible(Lora("pony"), ImageCatalog.RealVisXL);
        Assert.True(ok);
        Assert.Contains("Pony", warn);
        Assert.Null(ImageLibrary.Compatible(Lora("illustrious"), ImageCatalog.NoobAI).Warning);
    }

    [Fact]
    public void Image_request_round_trips_through_the_job_journal()
    {
        var r = new ImageRequest
        {
            Prompt = "p", Model = "pony-v6-xl", Op = "outpaint", Expand = [0, 64, 128, 0], Loras = [new LoraUse("a", 0.7)], Negative = "n",
            Sampler = "euler_a", ClipSkip = 2, Strength = 0.4, ControlType = "pose", ControlSource = "img:x", Detail = ["face"], Hd = true, FinalPrompt = "f",
        };
        var back = ImageRequest.FromJson(r.ToJson());
        Assert.Equal(r.Expand, back.Expand);
        Assert.Equal(0.7, back.Loras.Single().Weight);
        Assert.Equal("pose", back.ControlType);
        Assert.Equal(2, back.ClipSkip);
        Assert.True(back.Hd);
        Assert.Equal("f", back.FinalPrompt);
    }
}
