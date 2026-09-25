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
) {
    companion object {
        fun parse(o: JsonObject) = PcInfo(
            id = o.str("id") ?: "",
            name = o.str("name") ?: "PC",
            version = o.str("version") ?: "",
            lan = o.arr("lan")?.mapNotNull { it.jsonPrimitive.content } ?: emptyList(),
            relay = o.str("relay"),
        )
    }
}

enum class LmState { RUNNING, STOPPED, NOT_INSTALLED, UNKNOWN }

data class LoadedModel(val key: String, val name: String, val context: Int, val vision: Boolean)

data class GpuInfo(val name: String, val vramTotalMb: Int, val vramUsedMb: Int, val util: Int, val tempC: Int)

data class ActiveJob(val job: String, val model: String, val state: String, val tps: Double?, val tokens: Int)

data class ModelOp(val kind: String, val model: String, val since: Long?)

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
                    LoadedModel(it.str("model") ?: "", it.str("name") ?: "", it.int("context") ?: 0, it.bool("vision") ?: false)
                } ?: emptyList(),
                op = o.obj("op")?.let { ModelOp(it.str("kind") ?: "", it.str("model") ?: "", it.long("since")) },
                gpu = o.obj("gpu")?.let {
                    GpuInfo(it.str("name") ?: "", it.int("vramTotalMb") ?: 0, it.int("vramUsedMb") ?: 0, it.int("util") ?: 0, it.int("tempC") ?: 0)
                },
                jobs = o.arr("jobs")?.mapNotNull { it.asObj() }?.map {
                    ActiveJob(it.str("job") ?: "", it.str("model") ?: "", it.str("state") ?: "", it.dbl("tps"), it.int("tokens") ?: 0)
                } ?: emptyList(),
                sessions = o.int("sessions") ?: 0,
                remote = o.str("remote") ?: "",
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
) {
    val isLlm get() = type == "llm"
    val supportsReasoningToggle get() = reasoning.contains("off")

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
