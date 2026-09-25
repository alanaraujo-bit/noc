using System.Text.RegularExpressions;

namespace Noc.Core.ImageEngine;

/// <summary>Resultado da compilação: o que vai para o modelo, e como chegou lá.</summary>
public sealed record CompiledPrompt(string Original, string Positive, string Negative, string Strategy, string? RewriteMode, string? Body, List<string> Added);

/// <summary>
/// Prompt Compiler por modelo. A pessoa escreve normalmente em português; cada modelo recebe o que entende melhor:
/// - natural (Z-Image, FLUX, RealVisXL): inglês descritivo (tradução fiel, ou "Melhorar prompt");
/// - tags (NoobAI): tags Danbooru + tags de qualidade do modelo;
/// - pony (Pony V6): score tags + fonte + frase curta com tags.
/// As palavras de ativação dos LoRAs entram sozinhas. RAW: nada é mudado (nem prefixo, nem negativo padrão).
/// O original e o final ficam guardados na metadata de cada imagem.
/// </summary>
public static class PromptCompiler
{
    /// <summary>Qual reescrita o worker deve fazer antes de montar o prompt final (null = nenhuma).</summary>
    public static string? RewriteMode(ImageModelDef m, string prompt, bool enhance, bool raw)
    {
        if (raw) return null;
        var strategy = m.Preset.Strategy;
        switch (strategy)
        {
            case "tags":
                return LooksLikeTags(prompt) ? null : "tags";
            case "pony":
                return LooksLikeTags(prompt) && LooksEnglish(prompt) ? null : "pony";
            default:
                if (enhance) return "enhance";
                return !m.Defaults.NativePortuguese && !LooksEnglish(prompt) ? "translate" : null;
        }
    }

    /// <summary>Monta o prompt final a partir do texto (já reescrito, se foi o caso).</summary>
    public static CompiledPrompt Compile(ImageModelDef m, string original, string? body, string? rewriteMode, bool raw, string? userNegative,
        IEnumerable<LoraEntry> loras)
    {
        var added = new List<string>();
        if (raw)
            return new CompiledPrompt(original, original, userNegative?.Trim() ?? "", "raw", null, null, added);
        var text = (body ?? original).Trim().TrimEnd('.', ',');
        var triggers = loras.Where(l => l.AutoTriggers).SelectMany(l => l.Triggers)
            .Where(t => !text.Contains(t, StringComparison.OrdinalIgnoreCase)).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        added.AddRange(triggers);
        var p = m.Preset;
        string positive;
        switch (p.Strategy)
        {
            case "tags":
            {
                var tags = SplitTags(text);
                tags.RemoveAll(t => QualityTags.Contains(t));
                if (body is not null) tags = DropUnrequestedStyle(tags, original);
                if (!raw && body is not null && tags.Count > 40) tags = tags.Take(40).ToList(); // o reescritor às vezes se repete
                positive = p.Prefix + string.Join(", ", triggers.Concat(tags));
                break;
            }
            case "pony":
            {
                // a fonte vem do que a pessoa pediu (a reescrita pode dizer "cartoon" num pedido de anime)
                var source = PonySource(original);
                var lower = text.ToLowerInvariant();
                var head = p.Prefix + (lower.Contains("source_") ? "" : source + ", ") + (lower.Contains("rating_") ? "" : "rating_safe, ");
                if (body is not null)
                {
                    // frase + no máximo 24 tags, sem repetição e sem estilo que ninguém pediu
                    var parts = DropUnrequestedStyle(SplitTags(text), original);
                    text = string.Join(", ", parts.Take(25));
                }
                positive = head + string.Join(", ", triggers.Append(text).Where(s => s.Length > 0));
                break;
            }
            default:
                positive = string.Join(", ", triggers.Prepend(text).Where(s => s.Length > 0)) + p.Suffix;
                if (p.Prefix.Length > 0) positive = p.Prefix + positive;
                break;
        }
        var neg = string.Join(", ", new[] { p.Negative, userNegative?.Trim() ?? "" }.Where(s => s.Length > 0));
        return new CompiledPrompt(original, positive, neg, p.Strategy, rewriteMode, body, added);
    }

    /// <summary>
    /// O reescritor às vezes inventa estilo ("realistic art style", "3d") — isso muda o modelo inteiro de direção.
    /// Só fica se a pessoa pediu algo de estilo.
    /// </summary>
    private static List<string> DropUnrequestedStyle(List<string> tags, string original)
    {
        var o = original.ToLowerInvariant();
        if (Regex.IsMatch(o, @"\b(estilo|style|realis|3d|render|aquarela|watercolor|pixel|sketch|esbo[çc]o|pintura|painting)")) return tags;
        return tags.Where(t => !Regex.IsMatch(t, @"\b(style|realistic|photorealistic|3d|render|cgi|art)\b", RegexOptions.IgnoreCase)).ToList();
    }

    private static readonly HashSet<string> QualityTags = new(StringComparer.OrdinalIgnoreCase)
        { "masterpiece", "best quality", "high quality", "newest", "absurdres", "highres", "safe" };

    /// <summary>Palavras da própria instrução que o reescritor às vezes devolve como se fossem tags.</summary>
    private static readonly HashSet<string> Junk = new(StringComparer.OrdinalIgnoreCase)
        { "clothing", "action", "setting", "subject", "tags", "tag", "girl", "boy", "hair", "details", "key details", "background" };

    private static List<string> SplitTags(string s) =>
        s.Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
            .Select(t => t.Replace('_', ' '))
            // "1witch", "1robot" não existem: só 1girl/1boy/1other levam número colado
            .Select(t => Regex.IsMatch(t, @"^1(?!girl|boy|other)[a-z]+$", RegexOptions.IgnoreCase) ? t[1..] : t)
            .Where(t => !Junk.Contains(t))
            .Distinct(StringComparer.OrdinalIgnoreCase).ToList();

    /// <summary>Fonte do Pony: anime (padrão), cartoon, furry ou pony, pelo que foi pedido.</summary>
    public static string PonySource(string text)
    {
        var t = text.ToLowerInvariant();
        if (Regex.IsMatch(t, @"\b(furry|antropom[óo]rfic|anthro)")) return "source_furry";
        if (Regex.IsMatch(t, @"\b(p[ôo]nei|pony|my little pony)\b")) return "source_pony";
        if (Regex.IsMatch(t, @"\b(cartoon|desenho animado|estilo disney|pixar|comic|quadrinhos)\b")) return "source_cartoon";
        return "source_anime";
    }

    /// <summary>Já está em tags? (muitas vírgulas, itens curtos, sem frases longas)</summary>
    public static bool LooksLikeTags(string s)
    {
        var parts = s.Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length < 4) return false;
        var shortOnes = parts.Count(p => p.Split(' ', StringSplitOptions.RemoveEmptyEntries).Length <= 4);
        return shortOnes >= parts.Length * 0.8 && LooksEnglish(s.Replace(',', ' ') + " the with");
    }

    /// <summary>Heurística simples: o texto parece inglês (não precisa de tradução)?</summary>
    public static bool LooksEnglish(string text)
    {
        var t = " " + Regex.Replace(text.ToLowerInvariant(), @"[^\p{L}\s]", " ") + " ";
        if (Regex.IsMatch(t, "[ãõçáéíóúâêôà]")) return false;
        string[] pt = [" de ", " da ", " do ", " uma ", " um ", " com ", " em ", " no ", " na ", " que ", " para ", " os ", " as ", " e ", " imagem ", " crie ", " gere ", " foto ", " menina ", " garota ", " cabelo "];
        string[] en = [" the ", " a ", " an ", " of ", " with ", " in ", " on ", " and ", " at ", " photo ", " image ", " style ", " lighting ", " girl ", " hair ", " solo "];
        var p = pt.Count(w => t.Contains(w));
        var e = en.Count(w => t.Contains(w));
        return e >= 2 && e > p;
    }
}

/// <summary>
/// Roteamento do modo Automático: escolhe o melhor motor instalado para o pedido e explica por quê.
/// Sempre é possível escolher à mão; LoRAs escolhidos mandam na família.
/// </summary>
public static class ImageRouter
{
    private static readonly Regex Text = new(@"[""“”]|\b(escrit[oa]|texto|letreiro|placa|logo|logotipo|t[íi]tulo|legenda|tipografia)\b", RegexOptions.IgnoreCase);
    private static readonly Regex Anime = new(@"\b(anime|mang[áa]|chibi|waifu|kawaii|cartoon|desenho animado|estilo japon[êe]s|furry|p[ôo]nei|pony|cel shading|toon|estilo ghibli|ghibli)\b", RegexOptions.IgnoreCase);
    private static readonly Regex Illustration = new(@"\b(ilustra[çc][ãa]o|personage(m|ns)|fanart|fan art|arte digital|digital art|concept art|pintura digital|character|oc\b|1girl|1boy|genshin|hololive|vtuber|pixiv|light novel|visual novel|rpg)\b", RegexOptions.IgnoreCase);
    private static readonly Regex Photo = new(@"\b(foto|fotografia|fotogr[áa]fic[oa]|realista|realismo|fotorrealista|hiper-?realista|retrato|headshot|ensaio|35 ?mm|50 ?mm|85 ?mm|lente|dslr|cinematogr[áa]fic[oa]|produto|still|editorial|photo|photograph|realistic|photorealistic)\b", RegexOptions.IgnoreCase);

    public sealed record Choice(ImageModelDef? Model, string? Note, string? Error);

    public static Choice Choose(string prompt, string quality, long pixels, Func<ImageModelDef, bool> installed, string? loraFamily, string? sourceModel)
    {
        var z = ImageCatalog.ZImageTurbo;
        var f = ImageCatalog.FluxDev;
        var rv = ImageCatalog.RealVisXL;
        var noob = ImageCatalog.NoobAI;
        var pony = ImageCatalog.PonyV6;
        bool Ok(ImageModelDef m) => installed(m) && pixels <= m.Defaults.MaxPixels;

        // LoRAs escolhidos: a família do LoRA decide
        if (loraFamily is not null)
        {
            var byFamily = loraFamily switch
            {
                "pony" => pony,
                "noobai" or "illustrious" => noob,
                _ => rv,
            };
            if (Ok(byFamily)) return new Choice(byFamily, $"LoRA escolhido: {byFamily.Name}", null);
            var anySdxl = ImageCatalog.Models.FirstOrDefault(m => m.Engine == "sdxl" && Ok(m));
            if (anySdxl is not null) return new Choice(anySdxl, $"LoRA escolhido: {anySdxl.Name}", null);
        }
        // edição de uma imagem: fica no mesmo modelo quando ele sabe fazer
        if (sourceModel is not null && ImageCatalog.Find(sourceModel) is { } src && Ok(src))
            return new Choice(src, $"Mesmo modelo da imagem original: {src.Name}", null);

        var wantsText = Text.IsMatch(prompt);
        var anime = Anime.IsMatch(prompt);
        var illus = Illustration.IsMatch(prompt);
        var photo = Photo.IsMatch(prompt);
        if (wantsText && Ok(z)) return new Choice(z, "Texto na imagem: Z-Image Turbo desenha letras melhor", null);
        if (anime && Ok(pony) && !(illus && Ok(noob) && !Regex.IsMatch(prompt, @"\b(furry|p[ôo]nei|pony|cartoon)\b", RegexOptions.IgnoreCase)))
            return new Choice(pony, "Anime e estilos: Pony V6", null);
        if ((illus || anime) && Ok(noob)) return new Choice(noob, "Personagens e ilustração: NoobAI", null);
        if (anime && Ok(pony)) return new Choice(pony, "Anime e estilos: Pony V6", null);
        if (quality == "high" && Ok(f) && !photo) return new Choice(f, "Qualidade alta pedida: FLUX.1 Dev", null);
        if (photo && quality == "high" && Ok(rv)) return new Choice(rv, "Fotografia em alta qualidade: RealVisXL", null);
        if (photo && Ok(rv) && Regex.IsMatch(prompt, @"\b(realista|realismo|fotorrealista|hiper-?realista|retrato|pele|ensaio|photorealistic|realistic)\b", RegexOptions.IgnoreCase))
            return new Choice(rv, "Fotografia e realismo: RealVisXL", null);
        if (Ok(z)) return new Choice(z, "Z-Image Turbo: rápido e com ótima qualidade", null);
        var any = new[] { rv, f, noob, pony }.FirstOrDefault(Ok) ?? ImageCatalog.Models.FirstOrDefault(Ok);
        if (any is not null) return new Choice(any, $"Motor disponível: {any.Name}", null);
        return new Choice(null, null, "model_not_installed:" + z.Id);
    }
}
