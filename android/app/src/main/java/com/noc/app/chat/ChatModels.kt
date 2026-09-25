package com.noc.app.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val NocJson = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

/** Parâmetros de geração. null = padrão do modelo/LM Studio. */
@Serializable
data class GenerationParams(
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val minP: Double? = null,
    val repeatPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val maxTokens: Int? = null,
    val seed: Int? = null,
    val stop: List<String> = emptyList(),
    /** on | off | low | medium | high (null = padrão do modelo). */
    val reasoning: String? = null,
    /** Contexto usado ao carregar o modelo (só vale quando for preciso carregar). */
    val contextLength: Int? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        temperature?.let { put("temperature", it) }
        topP?.let { put("top_p", it) }
        topK?.let { put("top_k", it) }
        minP?.let { put("min_p", it) }
        repeatPenalty?.let { put("repeat_penalty", it) }
        presencePenalty?.let { put("presence_penalty", it) }
        frequencyPenalty?.let { put("frequency_penalty", it) }
        maxTokens?.let { put("max_tokens", it) }
        seed?.let { put("seed", it) }
        if (stop.isNotEmpty()) put("stop", buildJsonArray { stop.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        reasoning?.let { put("reasoning", it) }
    }

    /** Sobrepõe apenas os campos definidos em [o]. */
    fun overlay(o: GenerationParams?): GenerationParams = if (o == null) this else GenerationParams(
        temperature = o.temperature ?: temperature,
        topP = o.topP ?: topP,
        topK = o.topK ?: topK,
        minP = o.minP ?: minP,
        repeatPenalty = o.repeatPenalty ?: repeatPenalty,
        presencePenalty = o.presencePenalty ?: presencePenalty,
        frequencyPenalty = o.frequencyPenalty ?: frequencyPenalty,
        maxTokens = o.maxTokens ?: maxTokens,
        seed = o.seed ?: seed,
        stop = o.stop.ifEmpty { stop },
        reasoning = o.reasoning ?: reasoning,
        contextLength = o.contextLength ?: contextLength,
    )

    val isDefault get() = this == GenerationParams()

    fun encode(): String = NocJson.encodeToString(this)

    companion object {
        fun decode(s: String?): GenerationParams? = s?.let { runCatching { NocJson.decodeFromString<GenerationParams>(it) }.getOrNull() }
    }
}

/** Estatísticas de uma resposta. */
@Serializable
data class GenStats(
    val ttftMs: Long? = null,
    val tps: Double? = null,
    val totalMs: Long? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val context: Int? = null,
    val route: String? = null,
    /** Tempo de parede desde que o PC começou a tarefa (inclui carregar modelo). */
    val wallMs: Long? = null,
    /** Tempo esperando na fila do PC. */
    val queuedMs: Long? = null,
    val modelName: String? = null,
) {
    fun encode(): String = NocJson.encodeToString(this)

    companion object {
        fun decode(s: String?): GenStats? = s?.let { runCatching { NocJson.decodeFromString<GenStats>(it) }.getOrNull() }
    }
}

/** Anexo de mensagem. Texto é embutido no prompt; imagem vai como data URL (só para modelos com visão). */
@Serializable
data class Attachment(
    val kind: String, // text | image
    val name: String,
    val mime: String,
    val size: Long,
    /** Conteúdo textual (kind=text). */
    val text: String? = null,
    /** Caminho do arquivo local em cache (kind=image). */
    val path: String? = null,
    /** SHA-256 do arquivo (kind=image): o PC guarda a imagem por esse hash. */
    val sha256: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Tamanho original antes da otimização. */
    val originalSize: Long? = null,
) {
    companion object {
        fun decodeList(s: String?): List<Attachment> =
            s?.let { runCatching { NocJson.decodeFromString<List<Attachment>>(it) }.getOrNull() } ?: emptyList()

        fun encodeList(list: List<Attachment>): String? = if (list.isEmpty()) null else NocJson.encodeToString(list)
    }
}

object MessageStatus {
    const val DONE = "done"
    const val STREAMING = "streaming"
    const val WAITING = "waiting"
    const val ERROR = "error"
    const val CANCELLED = "cancelled"
    const val INTERRUPTED = "interrupted"
}
