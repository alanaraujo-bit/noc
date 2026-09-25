package com.noc.app.core.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Por que um WebSocket fechou. `code` segue os códigos do relay (4404 = PC offline etc.). */
data class CloseInfo(val code: Int, val reason: String, val error: Throwable? = null)

class TransportException(val info: CloseInfo) : IOException("ws ${info.code} ${info.reason}", info.error)

/** WebSocket binário com recepção por canal (suspensa). */
class WsTransport private constructor() : WebSocketListener() {
    private lateinit var ws: WebSocket
    val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    val closed = CompletableDeferred<CloseInfo>()
    private var opened: ((Throwable?) -> Unit)? = null

    override fun onOpen(webSocket: WebSocket, response: Response) {
        opened?.invoke(null)
        opened = null
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        incoming.trySend(bytes.toByteArray())
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        // o protocolo é só binário; texto é ignorado
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
        finish(CloseInfo(code, reason))
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(CloseInfo(code, reason))

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        val info = CloseInfo(response?.code ?: -1, response?.message ?: (t.message ?: t.javaClass.simpleName), t)
        opened?.invoke(TransportException(info))
        opened = null
        finish(info)
    }

    private fun finish(info: CloseInfo) {
        if (closed.complete(info)) incoming.close(TransportException(info))
    }

    fun send(bytes: ByteArray): Boolean = ws.send(bytes.toByteString())

    fun close(code: Int = 1000, reason: String? = null) {
        ws.close(code, reason)
        finish(CloseInfo(code, reason ?: "local"))
    }

    fun cancel() {
        ws.cancel()
        finish(CloseInfo(1006, "cancelled"))
    }

    /** Recebe o próximo frame ou lança TransportException com o motivo do fechamento. */
    suspend fun receive(): ByteArray = try {
        incoming.receive()
    } catch (e: TransportException) {
        throw e
    } catch (e: Exception) {
        throw TransportException(closed.takeIf { it.isCompleted }?.getCompleted() ?: CloseInfo(1006, e.message ?: "closed"))
    }

    companion object {
        suspend fun open(client: OkHttpClient, url: String): WsTransport {
            val t = WsTransport()
            return suspendCancellableCoroutine { cont ->
                t.opened = { err -> if (err == null) cont.resume(t) else cont.resumeWithException(err) }
                t.ws = client.newWebSocket(Request.Builder().url(url).build(), t)
                cont.invokeOnCancellation { t.ws.cancel() }
            }
        }
    }
}
