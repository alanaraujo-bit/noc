package com.noc.app.chat

import android.content.Context
import com.noc.app.core.net.ConnectionManager
import com.noc.app.core.net.dbl
import com.noc.app.core.net.str
import com.noc.app.service.GenerationService
import com.noc.app.service.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Cargas de modelo pedidas pelo celular. Enquanto uma está em andamento, a conexão fica viva (com a notificação
 * "Carregando Qwen 3.8…") para avisar "pronto em 11,4 s" ou explicar a falha — mesmo com o app fechado.
 */
class ModelOps(
    private val context: Context,
    private val connection: ConnectionManager,
    private val scope: CoroutineScope,
    private val notifier: () -> Notifier,
) {
    private data class Op(val key: String, val name: String, val release: () -> Unit, val timeout: Job)

    private val _op = MutableStateFlow<String?>(null)
    /** Nome do modelo carregando por pedido deste celular (ou null). */
    val op: StateFlow<String?> = _op.asStateFlow()
    private var current: Op? = null

    fun current(): String? = _op.value

    init {
        scope.launch {
            connection.events.collect { e ->
                if (e.name != "model") return@collect
                val cur = current ?: return@collect
                if (e.data.str("model") != cur.key) return@collect
                when (e.data.str("state")) {
                    "loaded" -> finish(true, e.data.dbl("seconds"), null)
                    "failed" -> finish(false, null, e.data.str("error"))
                }
            }
        }
    }

    /** Pede ao PC para carregar. Devolve o erro legível, ou null se o pedido foi aceito. */
    suspend fun load(key: String, name: String, contextLength: Int? = null): String? {
        val res = runCatching {
            connection.call("models.load", buildJsonObject { put("model", key); contextLength?.let { put("context", it) } })
        }
        res.exceptionOrNull()?.let { e ->
            return (e as? com.noc.app.core.net.RpcError)?.message?.takeIf { it.isNotBlank() } ?: "Não foi possível pedir a carga agora."
        }
        current?.let { it.timeout.cancel(); it.release() }
        val release = connection.acquire()
        val timeout = scope.launch {
            delay(5 * 60_000)
            finish(false, null, "O PC demorou demais para responder.")
        }
        current = Op(key, name, release, timeout)
        _op.value = name
        GenerationService.ensureRunning(context)
        return null
    }

    private suspend fun finish(ok: Boolean, seconds: Double?, error: String?) {
        val cur = current ?: return
        current = null
        _op.value = null
        cur.timeout.cancel()
        cur.release()
        notifier().onModelEvent(cur.name, ok, seconds, error)
    }
}
