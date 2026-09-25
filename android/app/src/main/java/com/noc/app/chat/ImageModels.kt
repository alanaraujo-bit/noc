package com.noc.app.chat

import com.noc.app.core.net.arr
import com.noc.app.core.net.asObj
import com.noc.app.core.net.bool
import com.noc.app.core.net.dbl
import com.noc.app.core.net.int
import com.noc.app.core.net.long
import com.noc.app.core.net.obj
import com.noc.app.core.net.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Uma imagem usada como entrada (origem de edição, máscara, referência, pose): do PC ("img:") ou deste celular ("blob:"). */
@Serializable
data class ImageInput(
    /** pc | local */
    val kind: String,
    /** Id da imagem no PC (kind = pc). */
    val id: String? = null,
    /** Arquivo neste celular (kind = local), já preparado (JPEG/PNG) e com hash. */
    val path: String? = null,
    val sha256: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Miniatura para mostrar no composer. */
    val thumb: String? = null,
) {
    fun wire(): String = if (kind == "pc") "img:$id" else "blob:$sha256"

    companion object {
        fun pc(id: String, width: Int? = null, height: Int? = null, thumb: String? = null) = ImageInput("pc", id = id, width = width, height = height, thumb = thumb)
    }
}

/** LoRA escolhido (sem sintaxe: id da biblioteca do PC + peso). */
@Serializable
data class LoraPick(val id: String, val name: String, val weight: Double, val family: String? = null)

/** O que a pessoa escolheu na criação. null = padrão do PC (os padrões foram medidos na GPU dele). */
@Serializable
data class ImageOptions(
    /** fast | quality | auto | model (quando [model] foi escolhido) */
    val engine: String = "fast",
    /** Modelo escolhido (especialista SDXL ou checkpoint importado). */
    val model: String? = null,
    /** square | portrait | landscape | story | wallpaper | custom */
    val format: String = "square",
    val width: Int? = null,
    val height: Int? = null,
    /** fast | standard | high */
    val quality: String = "standard",
    val count: Int = 1,
    val steps: Int? = null,
    val guidance: Double? = null,
    val seed: Long? = null,
    val enhance: Boolean = false,
    // ---- fase 4
    val negative: String? = null,
    /** RAW: o prompt vai exatamente como escrito (nem tradução, nem tags, nem negativo padrão). */
    val raw: Boolean = false,
    val sampler: String? = null,
    val schedule: String? = null,
    val clipSkip: Int? = null,
    val guidanceRescale: Double? = null,
    val loras: List<LoraPick> = emptyList(),
    /** Refazer rostos e mãos em alta resolução depois de gerar. */
    val detail: Boolean = false,
    /** Alta Definição: amplia 2x e refina com o próprio modelo. */
    val hd: Boolean = false,
    val reference: ImageInput? = null,
    val referenceScale: Double? = null,
    /** pose | depth | canny */
    val control: String? = null,
    val controlInput: ImageInput? = null,
    val controlScale: Double? = null,
) {
    fun toWire(): JsonObject = buildJsonObject {
        put("engine", if (model != null) "auto" else engine); put("format", format); put("quality", quality); put("count", count); put("enhance", enhance)
        model?.let { put("model", it) }
        if (format == "custom") { width?.let { put("width", it) }; height?.let { put("height", it) } }
        steps?.let { put("steps", it) }
        guidance?.let { put("guidance", it) }
        seed?.let { put("seed", it) }
        negative?.takeIf { it.isNotBlank() }?.let { put("negative", it) }
        if (raw) put("raw", true)
        sampler?.let { put("sampler", it) }
        schedule?.let { put("schedule", it) }
        clipSkip?.let { put("clipSkip", it) }
        guidanceRescale?.let { put("guidanceRescale", it) }
        if (loras.isNotEmpty()) put("loras", buildJsonArray { loras.forEach { l -> add(buildJsonObject { put("id", l.id); put("weight", l.weight) }) } })
        if (detail) put("detail", buildJsonArray { add(JsonPrimitive("face")); add(JsonPrimitive("hand")) })
        if (hd) put("hd", true)
        reference?.let { r -> put("reference", buildJsonObject { put("source", r.wire()); referenceScale?.let { put("scale", it) } }) }
        if (control != null && controlInput != null)
            put("control", buildJsonObject { put("type", control); put("source", controlInput.wire()); controlScale?.let { put("scale", it) } })
    }

    /** Algo além do básico está ligado (o botão do Estúdio fica destacado). */
    val hasAdvanced get() = steps != null || guidance != null || seed != null || format == "custom" || !negative.isNullOrBlank() || raw ||
        sampler != null || schedule != null || clipSkip != null || guidanceRescale != null || detail || hd

    /** Entradas locais que precisam subir para o PC antes do pedido. */
    val localInputs get() = listOfNotNull(reference, controlInput).filter { it.kind == "local" }

    fun encode(): String = NocJson.encodeToString(this)

    companion object {
        fun decode(s: String?): ImageOptions? = s?.let { runCatching { NocJson.decodeFromString<ImageOptions>(it) }.getOrNull() }
        fun engineLabel(e: String) = when (e) { "quality" -> "◆ Qualidade"; "auto" -> "✦ Automático"; "model" -> "◈ Especialista"; else -> "⚡ Rápido" }
    }
}

/** Uma edição de imagem (variação, pincel, expandir, ampliar, detalhes, alta definição, recriar). */
@Serializable
data class ImageEdit(
    /** img2img | inpaint | outpaint | upscale | detail | hd | recreate */
    val op: String,
    val source: ImageInput? = null,
    val mask: ImageInput? = null,
    val strength: Double? = null,
    /** outpaint: esquerda, cima, direita, baixo (px) */
    val expand: List<Int>? = null,
    val upscale: Int = 0,
    /** Recriar: id da imagem no PC. */
    val recreate: String? = null,
    /** Modelo (null = o da imagem original / automático). */
    val model: String? = null,
    val count: Int = 1,
) {
    fun toWire(): JsonObject = buildJsonObject {
        if (op == "recreate") { put("recreate", recreate ?: ""); return@buildJsonObject }
        put("op", op)
        source?.let { put("source", it.wire()); it.width?.let { w -> put("width", w) }; it.height?.let { h -> put("height", h) } }
        mask?.let { put("mask", it.wire()) }
        strength?.let { put("strength", it) }
        expand?.let { e -> put("expand", buildJsonArray { e.forEach { add(JsonPrimitive(it)) } }) }
        if (upscale > 0) put("upscale", upscale)
        model?.let { put("model", it) }
        if (count > 1) put("count", count)
    }

    val label get() = when (op) {
        "img2img" -> "Variação"; "inpaint" -> "Edição"; "outpaint" -> "Expandir"; "upscale" -> "Ampliar ${upscale.coerceAtLeast(2)}x"
        "detail" -> "Rostos e mãos"; "hd" -> "Alta definição"; "recreate" -> "Recriar"; else -> "Edição"
    }
}

/** Uma imagem de um pedido (como o PC descreve; [local]/[thumb] preenchidos quando chegou ao celular). */
@Serializable
data class GenImage(
    val id: String,
    val width: Int = 0,
    val height: Int = 0,
    val seed: Long? = null,
    val bytes: Long = 0,
    val sha256: String? = null,
    val local: String? = null,
    val thumb: String? = null,
    /** O PC não tem mais a imagem e ela nunca chegou aqui. */
    val gone: Boolean = false,
)

/** Estado de uma mensagem de imagem, guardado em messages.imageJson. */
@Serializable
data class ImageJob(
    val prompt: String,
    val options: ImageOptions,
    val images: List<GenImage> = emptyList(),
    /** Ainda há imagens para baixar do PC. */
    val pending: Boolean = false,
    val finalPrompt: String? = null,
    /** translate | enhance | tags | pony | null */
    val rewrite: String? = null,
    val note: String? = null,
    val model: String? = null,
    val modelName: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val guidance: Double? = null,
    val seed: Long? = null,
    val wallMs: Long? = null,
    val generateMs: Long? = null,
    val error: String? = null,
    val detail: String? = null,
    // ---- fase 4
    val edit: ImageEdit? = null,
    val negative: String? = null,
    /** natural | tags | pony | raw | recreate */
    val strategy: String? = null,
    val loraNames: List<String> = emptyList(),
    val sampler: String? = null,
    val schedule: String? = null,
    /** Palavras de ativação dos LoRAs acrescentadas sozinhas. */
    val added: List<String> = emptyList(),
) {
    fun encode(): String = NocJson.encodeToString(this)

    val ready get() = images.filter { it.local != null }
    val op get() = edit?.op ?: "txt2img"

    companion object {
        fun decode(s: String?): ImageJob? = s?.let { runCatching { NocJson.decodeFromString<ImageJob>(it) }.getOrNull() }

        fun parseImage(o: JsonObject) = GenImage(
            id = o.str("id") ?: "", width = o.int("width") ?: 0, height = o.int("height") ?: 0, seed = o.long("seed"),
            bytes = o.long("bytes") ?: 0, sha256 = o.str("sha256"),
        )
    }
}

/** O que o PC oferece para criar imagens (só o que funciona de verdade nele). */
data class ImageCaps(
    val enabled: Boolean,
    val state: String,
    val formats: List<Format>,
    val engines: List<Engine>,
    val models: List<ModelState>,
    val counts: List<Int>,
    val hfUser: String?,
    val samplers: List<Pair<String, String>> = emptyList(),
    val schedules: List<Pair<String, String>> = emptyList(),
    val tools: Map<String, Boolean> = emptyMap(),
    val loraCount: Int = 0,
    val installQueue: List<String> = emptyList(),
    val imports: List<ImportState> = emptyList(),
) {
    data class Format(val id: String, val label: String, val width: Int, val height: Int)
    data class Engine(
        val choice: String, val model: String, val name: String, val label: String, val installed: Boolean,
        val stepsMin: Int, val stepsMax: Int, val stepPresets: List<Int>,
        val guidanceMin: Double?, val guidanceMax: Double?, val guidanceDefault: Double?, val maxPixels: Long,
    )
    data class ModelState(
        val id: String, val name: String, val profile: String, val state: String, val installed: Boolean, val gated: Boolean,
        val sizeBytes: Long, val done: Long?, val total: Long?, val bps: Double?, val etaSeconds: Long?, val detail: String?,
        val updateAvailable: Boolean, val licenseUrl: String?, val license: String?,
        // fase 4
        val engine: String = "", val family: String = "", val tagline: String = "", val category: String = "", val version: String = "",
        val origin: String = "", val licenseNote: String = "", val local: Boolean = false, val ready: Boolean = installed,
        val caps: Set<String> = emptySet(), val stepsMin: Int = 1, val stepsMax: Int = 50, val stepPresets: List<Int> = emptyList(),
        val guidanceMin: Double? = null, val guidanceMax: Double? = null, val guidanceDefault: Double? = null, val maxPixels: Long = 1536L * 1536,
        val preset: Preset? = null, val vramGiB: Double? = null, val loadSeconds: Double? = null,
    ) {
        val sdxl get() = engine == "sdxl"
        val specialist get() = profile == "specialist" || profile == "custom"
        fun can(cap: String) = cap in caps
    }
    data class Preset(val strategy: String, val sampler: String, val schedule: String, val prediction: String, val prefix: String, val negative: String, val guidanceRescale: Double, val clipSkip: Int?)
    data class ImportState(val key: String, val url: String, val name: String?, val state: String, val done: Long?, val total: Long?, val kind: String?, val id: String?, val detail: String?)

    fun engine(choice: String) = engines.firstOrNull { it.choice == choice }
    fun model(id: String?) = id?.let { i -> models.firstOrNull { it.id == i } }
    val anyInstalled get() = engines.any { it.installed } || models.any { it.ready }
    val specialists get() = models.filter { it.specialist }
    fun tool(t: String) = tools[t] == true

    /** O modelo que um pedido vai usar (escolhido, ou o do motor fast/quality). */
    fun modelFor(o: ImageOptions): ModelState? = model(o.model) ?: when (o.engine) {
        "quality" -> model("flux1-dev"); "auto" -> null; else -> model("z-image-turbo")
    }

    companion object {
        fun parse(o: JsonObject) = ImageCaps(
            enabled = o.bool("enabled") ?: true,
            state = o.str("state") ?: "off",
            formats = o.arr("formats")?.mapNotNull { it.asObj() }?.map { Format(it.str("id") ?: "", it.str("label") ?: "", it.int("width") ?: 1024, it.int("height") ?: 1024) } ?: emptyList(),
            engines = o.arr("engines")?.mapNotNull { it.asObj() }?.map { e ->
                val st = e.obj("steps")
                val g = e.obj("guidance")
                Engine(
                    e.str("choice") ?: "", e.str("model") ?: "", e.str("name") ?: "", e.str("label") ?: "", e.bool("installed") ?: false,
                    st?.int("min") ?: 1, st?.int("max") ?: 50, st?.arr("presets")?.mapNotNull { runCatching { it.toString().toInt() }.getOrNull() } ?: emptyList(),
                    g?.dbl("min"), g?.dbl("max"), g?.dbl("default"), e.long("maxPixels") ?: (1536L * 1536),
                )
            } ?: emptyList(),
            models = parseModels(o),
            counts = o.arr("counts")?.mapNotNull { runCatching { it.toString().toInt() }.getOrNull() } ?: listOf(1, 2, 4),
            hfUser = o.str("hfUser")?.takeIf { it.isNotBlank() },
            samplers = pairs(o.arr("samplers")),
            schedules = pairs(o.arr("schedules")),
            tools = o.obj("tools")?.let { t -> t.keys.associateWith { k -> t.bool(k) == true } } ?: emptyMap(),
            loraCount = o.int("loraCount") ?: 0,
            installQueue = o.arr("installQueue")?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
            imports = parseImports(o.arr("imports")),
        )

        private fun pairs(a: JsonArray?) = a?.mapNotNull { it.asObj() }?.map { (it.str("id") ?: "") to (it.str("label") ?: "") } ?: emptyList()

        fun parseImports(a: JsonArray?) = a?.mapNotNull { it.asObj() }?.map { i ->
            ImportState(i.str("key") ?: "", i.str("url") ?: "", i.str("name"), i.str("state") ?: "", i.long("done"), i.long("total"), i.str("kind"), i.str("id"), i.str("detail"))
        } ?: emptyList()

        fun parseModels(o: JsonObject) = o.arr("models")?.mapNotNull { it.asObj() }?.map { m ->
            val p = m.obj("progress")
            val st = m.obj("steps")
            val g = m.obj("guidance")
            val pr = m.obj("preset")
            val caps = m.obj("caps")
            ModelState(
                m.str("id") ?: "", m.str("name") ?: "", m.str("profile") ?: "", m.str("state") ?: "", m.bool("installed") ?: false,
                m.bool("gated") ?: false, m.long("sizeBytes") ?: 0, p?.long("done"), p?.long("total"), p?.dbl("bps"), p?.long("etaSeconds"),
                m.str("detail"), m.bool("updateAvailable") ?: false, m.str("licenseUrl"), m.str("license"),
                engine = m.str("engine") ?: "", family = m.str("family") ?: "", tagline = m.str("tagline") ?: "", category = m.str("category") ?: "",
                version = m.str("version") ?: "", origin = m.str("origin") ?: "", licenseNote = m.str("licenseNote") ?: "", local = m.bool("local") ?: false,
                ready = m.bool("ready") ?: (m.bool("installed") ?: false),
                caps = caps?.keys?.filter { caps.bool(it) == true }?.toSet() ?: emptySet(),
                stepsMin = st?.int("min") ?: 1, stepsMax = st?.int("max") ?: 50,
                stepPresets = st?.arr("presets")?.mapNotNull { runCatching { it.toString().toInt() }.getOrNull() } ?: emptyList(),
                guidanceMin = g?.dbl("min"), guidanceMax = g?.dbl("max"), guidanceDefault = g?.dbl("default"), maxPixels = m.long("maxPixels") ?: (1536L * 1536),
                preset = pr?.let {
                    Preset(it.str("strategy") ?: "natural", it.str("sampler") ?: "", it.str("schedule") ?: "", it.str("prediction") ?: "",
                        it.str("prefix") ?: "", it.str("negative") ?: "", it.dbl("guidanceRescale") ?: 0.0, it.int("clipSkip"))
                },
                vramGiB = m.dbl("vramGiB"), loadSeconds = m.dbl("loadSeconds"),
            )
        } ?: emptyList()
    }
}

/** Um LoRA da biblioteca do PC. */
data class LoraInfo(
    val id: String, val name: String, val arch: String, val family: String, val baseModel: String?, val size: Long, val sha256: String,
    val triggers: List<String>, val autoTriggers: Boolean, val weight: Double, val favorite: Boolean, val source: String?, val license: String?,
    val description: String?, val creator: String?, val tags: List<String>, val addedAt: Long, val lastUsed: Long?, val uses: Int,
    val compatible: Boolean, val warning: String?,
) {
    val familyLabel get() = when (family) { "pony" -> "Pony"; "noobai" -> "NoobAI"; "illustrious" -> "Illustrious"; "sdxl" -> "SDXL"; "flux" -> "FLUX"; "sd15" -> "SD 1.5"; else -> family }

    companion object {
        fun parse(o: JsonObject) = LoraInfo(
            o.str("id") ?: "", o.str("name") ?: "", o.str("arch") ?: "", o.str("family") ?: "", o.str("baseModel"), o.long("size") ?: 0,
            o.str("sha256") ?: "", o.arr("triggers")?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(), o.bool("autoTriggers") ?: true,
            o.dbl("weight") ?: 0.8, o.bool("favorite") ?: false, o.str("source"), o.str("license"), o.str("description"), o.str("creator"),
            o.arr("tags")?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(), o.long("addedAt") ?: 0, o.long("lastUsed"), o.int("uses") ?: 0,
            o.bool("compatible") ?: true, o.str("warning"),
        )
    }
}

/** Reconhece um pedido explícito de imagem digitado no chat ("Gere uma imagem de…", "Crie uma foto…"). */
object ImageIntent {
    private val verbs = "(gere|gera|gerar|crie|cria|criar|desenhe|desenha|desenhar|fa[çc]a|faz|fazer|produza|ilustre|renderize|pinte|me (gere|gera|crie|cria|fa[çc]a|faz|desenhe|desenha|mostre))"
    private val nouns = "(imagem|imagens|foto|fotos|fotografia|ilustra[çc][ãa]o|desenho|arte|pintura|wallpaper|papel de parede|render|logo|logotipo|retrato|poster|pôster|capa)"
    private val re = Regex("^\\s*(por favor,?\\s*)?$verbs\\s+(uma?s?\\s+|o\\s+|a\\s+|\\d+\\s+)?$nouns\\b", RegexOption.IGNORE_CASE)
    private val en = Regex("^\\s*(please\\s+)?(generate|create|draw|make|render)\\s+(an?\\s+|\\d+\\s+)?(image|picture|photo|illustration|drawing|wallpaper)\\b", RegexOption.IGNORE_CASE)

    fun matches(text: String): Boolean = re.containsMatchIn(text) || en.containsMatchIn(text)

    /** "Gere 4 imagens de…" → 4 (só 1, 2 ou 4). */
    fun count(text: String): Int? = Regex("^\\s*\\S+\\s+(\\d)\\s+", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in listOf(2, 4) }
}
