package com.noc.app.core.net

import com.noc.app.core.crypto.HandshakeClient
import com.noc.app.core.crypto.HandshakeMode
import com.noc.app.core.crypto.ProtocolException
import com.noc.app.core.crypto.SecureChannel
import com.noc.app.core.crypto.Wire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class Route { LAN, RELAY }

/** Evento vindo do PC: "status", "job", "model", "pc", "bye". */
data class PcEvent(val name: String, val data: JsonObject)

class RpcError(val code: String, message: String) : Exception(message)

/** O PC recusou este celular (revogado, desconhecido, pareamento expirado...). */
class AuthDenied(val code: String) : Exception("denied: $code")

/** A chave do PC não é a que foi fixada no pareamento. Possível interceptação. */
class PinMismatch : Exception("pc key mismatch")

/**
 * Sessão autenticada e cifrada com o Companion (sobre LAN ou relay).
 * Chamadas RPC são suspensas; eventos chegam em [events].
 */
class NocSession private constructor(
    private val transport: WsTransport,
    private val channel: SecureChannel,
    val route: Route,
    val welcome: JsonObject,
    val sas: String,
    val pcSpki: ByteArray,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val sendLock = Any()
    private val _events = MutableSharedFlow<PcEvent>(extraBufferCapacity = 4096)
    val events: SharedFlow<PcEvent> = _events
    val closed = CompletableDeferred<Throwable?>()
    val openedAt = System.currentTimeMillis()

    private fun startReader() = scope.launch {
        try {
            while (true) {
                val msg = JSON.parseToJsonElement(channel.open(transport.receive()).decodeToString()).jsonObject
                when (msg["t"]?.jsonPrimitive?.content) {
                    "res" -> pending.remove(msg["id"]!!.jsonPrimitive.long)?.complete(msg)
                    "evt" -> _events.emit(
                        PcEvent(msg["e"]!!.jsonPrimitive.content, msg["d"] as? JsonObject ?: JsonObject(emptyMap())),
                    )
                }
            }
        } catch (e: Throwable) {
            shutdown(e)
        }
    }

    private fun shutdown(cause: Throwable?) {
        if (!closed.complete(cause)) return
        val err = cause ?: TransportException(CloseInfo(1000, "closed"))
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
        scope.cancel()
    }

    val isOpen get() = !closed.isCompleted

    suspend fun call(method: String, params: JsonObject = EMPTY, timeoutMs: Long = 20_000): JsonObject {
        if (!isOpen) throw TransportException(CloseInfo(1006, "closed"))
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val req = buildJsonObject {
            put("t", "req"); put("id", id); put("m", method); put("p", params)
        }
        send(req)
        val res = try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
        if (res["ok"]?.jsonPrimitive?.content == "true") return res["r"] as? JsonObject ?: EMPTY
        val e = res["e"]?.jsonObject
        throw RpcError(e?.get("code")?.jsonPrimitive?.content ?: "error", e?.get("msg")?.jsonPrimitive?.content ?: "")
    }

    private fun send(msg: JsonElement) {
        val bytes = msg.toString().encodeToByteArray()
        synchronized(sendLock) {
            // selar e enfileirar juntos garante a ordem dos contadores
            if (!transport.send(channel.seal(bytes))) throw TransportException(CloseInfo(1006, "send failed"))
        }
    }

    fun close() {
        transport.close(1000, "bye")
        shutdown(null)
    }

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        val EMPTY = JsonObject(emptyMap())

        /**
         * Abre o WebSocket, faz o handshake e a autenticação.
         * [pinnedSpki]: chave do PC esperada (null só no pareamento por código, protegido pelo SAS).
         * [auth]: monta a mensagem de autenticação a partir do transcript hash.
         */
        suspend fun connect(
            client: OkHttpClient,
            url: String,
            route: Route,
            mode: HandshakeMode,
            pinnedSpki: ByteArray?,
            onSas: (String) -> Unit = {},
            auth: (th: ByteArray) -> JsonObject,
        ): NocSession {
            val transport = WsTransport.open(client, url)
            try {
                val hs = HandshakeClient(mode)
                if (!transport.send(hs.clientHello)) {
                    // O relay pode fechar logo após o upgrade (ex.: 4404 = PC offline): usa o motivo real.
                    throw TransportException(withTimeoutOrNull(2_000) { transport.closed.await() } ?: CloseInfo(1006, "send failed"))
                }
                val done = hs.complete(transport.receive())
                if (pinnedSpki != null && !done.pcSpki.contentEquals(pinnedSpki)) throw PinMismatch()
                onSas(done.sas)
                transport.send(done.channel.seal(auth(done.transcriptHash).toString().encodeToByteArray()))
                val welcome = JSON.parseToJsonElement(done.channel.open(transport.receive()).decodeToString()).jsonObject
                when (welcome["k"]?.jsonPrimitive?.content) {
                    "welcome" -> Unit
                    "denied" -> throw AuthDenied(welcome["code"]?.jsonPrimitive?.content ?: "unknown")
                    else -> throw ProtocolException("bad_welcome")
                }
                return NocSession(transport, done.channel, route, welcome, done.sas, done.pcSpki).also { it.startReader() }
            } catch (e: Throwable) {
                transport.cancel()
                throw e
            }
        }

        fun sessionAuth(deviceId: String, signature: ByteArray) = buildJsonObject {
            put("k", "auth"); put("device", deviceId); put("sig", Wire.b64(signature))
        }
    }
}
