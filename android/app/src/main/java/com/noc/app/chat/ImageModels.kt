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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** O que a pessoa escolheu na criação. null = padrão do PC (os padrões foram medidos na GPU dele). */
@Serializable
data class ImageOptions(
    /** fast | quality | auto */
    val engine: String = "fast",
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
) {
    fun toWire(): JsonObject = buildJsonObject {
        put("engine", engine); put("format", format); put("quality", quality); put("count", count); put("enhance", enhance)
        if (format == "custom") { width?.let { put("width", it) }; height?.let { put("height", it) } }
        steps?.let { put("steps", it) }
        guidance?.let { put("guidance", it) }
        seed?.let { put("seed", it) }
    }

    val hasAdvanced get() = steps != null || guidance != null || seed != null || format == "custom"

    fun encode(): String = NocJson.encodeToString(this)

    companion object {
        fun decode(s: String?): ImageOptions? = s?.let { runCatching { NocJson.decodeFromString<ImageOptions>(it) }.getOrNull() }
        fun engineLabel(e: String) = when (e) { "quality" -> "◆ Qualidade"; "auto" -> "✦ Automático"; else -> "⚡ Rápido" }
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
    /** translate | enhance | null */
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
) {
    fun encode(): String = NocJson.encodeToString(this)

    val ready get() = images.filter { it.local != null }

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
    )

    fun engine(choice: String) = engines.firstOrNull { it.choice == choice }
    val anyInstalled get() = engines.any { it.installed }

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
        )

        fun parseModels(o: JsonObject) = o.arr("models")?.mapNotNull { it.asObj() }?.map { m ->
            val p = m.obj("progress")
            ModelState(
                m.str("id") ?: "", m.str("name") ?: "", m.str("profile") ?: "", m.str("state") ?: "", m.bool("installed") ?: false,
                m.bool("gated") ?: false, m.long("sizeBytes") ?: 0, p?.long("done"), p?.long("total"), p?.dbl("bps"), p?.long("etaSeconds"),
                m.str("detail"), m.bool("updateAvailable") ?: false, m.str("licenseUrl"), m.str("license"),
            )
        } ?: emptyList()
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
