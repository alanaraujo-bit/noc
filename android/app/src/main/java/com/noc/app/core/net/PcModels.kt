package com.noc.app.core.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// Leitura tolerante de JSON: campos ausentes viram null/padrão.
fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray
fun JsonElement.asObj(): JsonObject? = this as? JsonObject

data class PcInfo(
    val id: String,
    val name: String,
    val version: String,
    val lan: List<String>,
    val relay: String?,
    /** Recursos do Companion (vazio = Companion 1.0, sem perfis/fila nova/voz/imagens por blob). */
    val features: Set<String> = emptySet(),
) {
    fun has(feature: String) = feature in features

    companion object {
        fun parse(o: JsonObject) = PcInfo(
            id = o.str("id") ?: "",
            name = o.str("name") ?: "PC",
            version = o.str("version") ?: "",
            lan = o.arr("lan")?.mapNotNull { it.jsonPrimitive.content } ?: emptyList(),
            relay = o.str("relay"),
            features = o.arr("features")?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }?.toSet() ?: emptySet(),
        )
    }
}

/** Perfis de modelo do PC. */
object Tier {
    const val FAST = "fast"
    const val SMART = "smart"
    const val DEEP = "deep"
    val all = listOf(FAST, SMART, DEEP)
    const val PREFIX = "tier:"

    fun label(t: String) = when (t) { FAST -> "Rápido"; SMART -> "Inteligente"; DEEP -> "Profundo"; else -> t }
    fun symbol(t: String) = when (t) { FAST -> "⚡"; SMART -> "◆"; DEEP -> "◈"; else -> "•" }
    fun blurb(t: String) = when (t) {
        FAST -> "Respostas imediatas"
        SMART -> "Equilíbrio entre qualidade e velocidade"
        DEEP -> "Máxima qualidade, mais devagar"
        else -> ""
    }
    fun of(modelOrTier: String?): String? = modelOrTier?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
}

data class TierInfo(val tier: String, val model: String, val name: String)

/** Tarefa no PC, como o Companion descreve (jobs.list / status). */
data class JobInfo(
    val job: String,
    val model: String,
    val name: String,
    val tier: String?,
    val state: String,
    /** queued | starting | loading | preparing | thinking | generating | recovering | done */
    val phase: String,
    val position: Int?,
    val createdAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
    val tokens: Int,
    val tps: Double?,
    val images: Int,
    val reason: String?,
    val error: String?,
    val lastSeq: Int,
    val device: String?,
    val mine: Boolean?,
    val label: String?,
    val meta: JsonObject?,
) {
    val finished get() = state == "finished" || phase == "done"

    companion object {
        fun parse(o: JsonObject) = JobInfo(
            job = o.str("job") ?: "", model = o.str("model") ?: "", name = o.str("name") ?: o.str("model") ?: "",
            tier = o.str("tier"), state = o.str("state") ?: "", phase = o.str("phase") ?: "",
            position = o.int("position"), createdAt = o.long("createdAt") ?: 0, startedAt = o.long("startedAt"),
            finishedAt = o.long("finishedAt"), tokens = o.int("tokens") ?: 0, tps = o.dbl("tps"), images = o.int("images") ?: 0,
            reason = o.str("reason"), error = o.str("error"), lastSeq = o.int("lastSeq") ?: 0, device = o.str("device"),
            mine = o.bool("mine"), label = o.str("label"), meta = o.obj("meta"),
        )
    }
}

/** Estado da transcrição de voz no PC. */
data class SttInfo(val state: String, val progress: Double?, val error: String?) {
    val ready get() = state == "ready"
    val available get() = state != "off" && state != "failed"
}

data class LastRun(val model: String, val name: String, val tps: Double?, val ttftMs: Long?, val at: Long?)

enum class LmState { RUNNING, STOPPED, NOT_INSTALLED, UNKNOWN }

data class LoadedModel(val key: String, val name: String, val context: Int, val vision: Boolean, val tier: String? = null)

data class GpuInfo(val name: String, val vramTotalMb: Int, val vramUsedMb: Int, val util: Int, val tempC: Int)

data class ActiveJob(val job: String, val model: String, val state: String, val tps: Double?, val tokens: Int)

data class ModelOp(val kind: String, val model: String, val since: Long?, val name: String? = null, val expectedSeconds: Double? = null)

data class PcStatus(
    val lm: LmState,
    val lmError: String?,
    val llmCount: Int,
    val loaded: List<LoadedModel>,
    val op: ModelOp?,
    val gpu: GpuInfo?,
    val jobs: List<ActiveJob>,
    val sessions: Int,
    val remote: String,
    val receivedAt: Long = System.currentTimeMillis(),
    val tiers: Map<String, TierInfo> = emptyMap(),
    val defaultModel: String? = null,
    val stt: SttInfo? = null,
    val tasks: List<JobInfo> = emptyList(),
    val last: LastRun? = null,
    val importing: Boolean = false,
    val benchModel: String? = null,
    val benchStep: String? = null,
    val companion: String? = null,
) {
    companion object {
        fun parse(o: JsonObject): PcStatus {
            val lm = o.obj("lm")
            return PcStatus(
                lm = when (lm?.str("state")) {
                    "running" -> LmState.RUNNING
                    "stopped" -> LmState.STOPPED
                    "not_installed" -> LmState.NOT_INSTALLED
                    else -> LmState.UNKNOWN
                },
                lmError = lm?.str("error"),
                llmCount = lm?.int("llms") ?: 0,
                loaded = o.arr("loaded")?.mapNotNull { it.asObj() }?.map {
                    LoadedModel(it.str("model") ?: "", it.str("name") ?: "", it.int("context") ?: 0, it.bool("vision") ?: false, it.str("tier"))
                } ?: emptyList(),
                op = o.obj("op")?.let { ModelOp(it.str("kind") ?: "", it.str("model") ?: "", it.long("since"), it.str("name"), it.dbl("expectedSeconds")) },
                gpu = o.obj("gpu")?.let {
                    GpuInfo(it.str("name") ?: "", it.int("vramTotalMb") ?: 0, it.int("vramUsedMb") ?: 0, it.int("util") ?: 0, it.int("tempC") ?: 0)
                },
                jobs = o.arr("jobs")?.mapNotNull { it.asObj() }?.map {
                    ActiveJob(it.str("job") ?: "", it.str("model") ?: "", it.str("state") ?: "", it.dbl("tps"), it.int("tokens") ?: 0)
                } ?: emptyList(),
                sessions = o.int("sessions") ?: 0,
                remote = o.str("remote") ?: "",
                tiers = o.obj("tiers")?.entries?.mapNotNull { (k, v) ->
                    (v as? JsonObject)?.let { t -> k to TierInfo(k, t.str("model") ?: return@mapNotNull null, t.str("name") ?: "") }
                }?.toMap() ?: emptyMap(),
                defaultModel = o.str("defaultModel"),
                stt = o.obj("stt")?.let { SttInfo(it.str("state") ?: "off", it.dbl("progress"), it.str("error")) },
                tasks = o.arr("jobs")?.mapNotNull { it.asObj() }?.map { JobInfo.parse(it) } ?: emptyList(),
                last = o.obj("last")?.let { LastRun(it.str("model") ?: "", it.str("name") ?: "", it.dbl("tps"), it.long("ttftMs"), it.long("at")) },
                importing = o.bool("importing") ?: false,
                benchModel = o.obj("bench")?.str("model"),
                benchStep = o.obj("bench")?.str("step"),
                companion = o.str("companion"),
            )
        }
    }
}

data class ModelInfo(
    val key: String,
    val name: String,
    val type: String,
    val arch: String?,
    val quant: String?,
    val sizeBytes: Long,
    val params: String?,
    val maxContext: Int,
    val vision: Boolean,
    val toolUse: Boolean,
    val reasoning: List<String>,
    val reasoningDefault: String?,
    val loaded: Boolean,
    val context: Int?,
    val fitsGpu: Boolean?,
    /** Nome técnico do LM Studio (o [name] é o amigável). */
    val technical: String? = null,
    val alias: String? = null,
    val favorite: Boolean = false,
    val hidden: Boolean = false,
    val tier: String? = null,
    val isDefault: Boolean = false,
    val lastError: String? = null,
    val lastLoadSeconds: Double? = null,
    val bench: JsonObject? = null,
    val perfContext: Int? = null,
    val perfReasoning: String? = null,
) {
    val isLlm get() = type == "llm"
    val supportsReasoningToggle get() = reasoning.contains("off")
    val supportsReasoning get() = reasoning.isNotEmpty()

    companion object {
        fun parse(o: JsonObject) = ModelInfo(
            key = o.str("key") ?: "",
            name = o.str("name") ?: o.str("key") ?: "",
            type = o.str("type") ?: "llm",
            arch = o.str("arch"),
            quant = o.str("quant"),
            sizeBytes = o.long("size") ?: 0,
            params = o.str("params"),
            maxContext = o.int("maxContext") ?: 0,
            vision = o.bool("vision") ?: false,
            toolUse = o.bool("toolUse") ?: false,
            reasoning = o.arr("reasoning")?.map { it.jsonPrimitive.content } ?: emptyList(),
            reasoningDefault = o.str("reasoningDefault"),
            loaded = o.bool("loaded") ?: false,
            context = o.int("context"),
            fitsGpu = o.bool("fitsGpu"),
            technical = o.str("technical"),
            alias = o.str("alias"),
            favorite = o.bool("favorite") ?: false,
            hidden = o.bool("hidden") ?: false,
            tier = o.str("tier"),
            isDefault = o.bool("isDefault") ?: false,
            lastError = o.str("lastError"),
            lastLoadSeconds = o.dbl("lastLoadSeconds"),
            bench = o.obj("bench"),
            perfContext = o.obj("perf")?.int("context"),
            perfReasoning = o.obj("perf")?.str("reasoning"),
        )
    }
}

data class DeviceInfo(
    val id: String,
    val name: String,
    val model: String,
    val pairedAt: Long,
    val lastSeen: Long?,
    val route: String?,
    val revoked: Boolean,
    val current: Boolean,
    val online: Boolean,
) {
    companion object {
        fun parse(o: JsonObject) = DeviceInfo(
            id = o.str("id") ?: "", name = o.str("name") ?: "", model = o.str("model") ?: "",
            pairedAt = o.long("pairedAt") ?: 0, lastSeen = o.long("lastSeen"), route = o.str("route"),
            revoked = o.bool("revoked") ?: false, current = o.bool("current") ?: false, online = o.bool("online") ?: false,
        )
    }
}

data class SecurityEventInfo(val at: Long, val level: String, val kind: String, val msg: String, val route: String?) {
    companion object {
        fun parse(o: JsonObject) = SecurityEventInfo(
            o.long("at") ?: 0, o.str("level") ?: "info", o.str("kind") ?: "", o.str("msg") ?: "", o.str("route"),
        )
    }
}
