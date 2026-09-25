package com.noc.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.noc.app.core.net.ConnState
import com.noc.app.core.net.ConnectionManager
import com.noc.app.core.net.RpcError
import com.noc.app.core.net.bool
import com.noc.app.core.net.long
import com.noc.app.core.net.str
import com.noc.app.data.prefs.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.math.log10
import kotlin.math.sqrt

/** Buffer de áudio que cresce sem recopiar tudo a cada pedaço. */
internal class GrowBuffer {
    private var data = ByteArray(16000 * 2 * 30)
    private var len = 0
    fun write(b: ByteArray, off: Int = 0, n: Int = b.size) {
        if (len + n > data.size) data = data.copyOf(maxOf(data.size * 2, len + n))
        System.arraycopy(b, off, data, len, n)
        len += n
    }
    fun size() = len
    fun reset() { len = 0 }
    fun toByteArray(): ByteArray = data.copyOf(len)
    fun slice(from: Int, n: Int): ByteArray = data.copyOfRange(from, from + n)
}

/** Estado do ditado, para a interface. */
sealed interface VoiceState {
    data object Idle : VoiceState
    data class Recording(
        val startedAt: Long,
        /** Últimos níveis (0..1), para a forma de onda. */
        val levels: List<Float>,
        /** Segurando o botão (solta = envia para transcrever). */
        val hold: Boolean,
        /** Nada chegando do microfone (bloqueado por outro app, ou mudo). */
        val noSignal: Boolean = false,
    ) : VoiceState
    data class Transcribing(val audioMs: Long, val since: Long = System.currentTimeMillis()) : VoiceState
    /** Algo deu errado. [retry]: o áudio ficou guardado na memória e pode ser reenviado. */
    data class Problem(val message: String, val kind: Kind, val retry: Boolean = false) : VoiceState {
        enum class Kind { PERMISSION, MIC_BUSY, PC, EMPTY, UNAVAILABLE }
    }
}

data class VoiceResult(val text: String, val hint: String?)

/**
 * Ditado: grava no celular (16 kHz, mono, PCM), vai mandando os pedaços pelo canal cifrado enquanto a pessoa
 * fala e, ao parar, o PC transcreve com o Whisper na GPU e devolve o texto em ~0,3 s.
 *
 * Trata interrupções: ligação/outro app pegando o áudio (perda de foco) e app indo para o fundo param a
 * gravação e transcrevem o que já foi dito; queda de rede guarda o áudio e reenvia; microfone ocupado ou
 * sem permissão explicam o que fazer. Nunca fica preso em "ouvindo": há limite de 5 min e toda saída volta a Idle.
 *
 * O áudio só existe na memória até virar texto; nada é gravado em disco.
 * (Base para o futuro modo de conversa por voz: o mesmo fluxo de captura + um canal de volta com TTS.)
 */
class VoiceController(
    private val context: Context,
    private val connection: ConnectionManager,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()
    private val _results = MutableSharedFlow<VoiceResult>(extraBufferCapacity = 4)
    val results: SharedFlow<VoiceResult> = _results

    private var recorder: PcmSource? = null

    /** De onde vem o áudio (microfone de verdade; no build de desenvolvimento pode ser um WAV). */
    private interface PcmSource {
        fun read(buf: ShortArray): Int
        fun stop()
    }

    private class MicSource(val rec: AudioRecord) : PcmSource {
        override fun read(buf: ShortArray) = rec.read(buf, 0, buf.size)
        override fun stop() { runCatching { rec.stop() }; rec.release() }
    }

    private class WavSource(file: java.io.File) : PcmSource {
        private val data: ShortArray
        private var pos = 0
        private var t0 = 0L
        init {
            val b = file.readBytes()
            var o = 12
            var start = 44
            var len = b.size - 44
            while (o + 8 <= b.size) {
                val id = String(b, o, 4)
                val sz = java.nio.ByteBuffer.wrap(b, o + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                if (id == "data") { start = o + 8; len = minOf(sz, b.size - start); break }
                o += 8 + sz
            }
            val bb = java.nio.ByteBuffer.wrap(b, start, len).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            data = ShortArray(bb.remaining()).also { bb.get(it) }
        }
        override fun read(buf: ShortArray): Int {
            if (t0 == 0L) t0 = System.currentTimeMillis()
            // entrega no ritmo real (16 kHz); depois do fim, silêncio
            val due = ((System.currentTimeMillis() - t0) * RATE / 1000).toInt()
            if (pos + buf.size > due) Thread.sleep(((pos + buf.size - due) * 1000L / RATE).coerceAtLeast(1))
            for (i in buf.indices) buf[i] = if (pos + i < data.size) data[pos + i] else 0
            pos += buf.size
            return buf.size
        }
        override fun stop() {}
    }
    private var captureJob: Job? = null
    private var senderJob: Job? = null
    private val pcm = GrowBuffer()
    private val lock = Any()
    private var sessionId: String? = null
    private var sentBytes = 0
    private var nextSeq = 0
    private var startedAt = 0L
    private var focusRequest: AudioFocusRequest? = null
    private var releaseConn: (() -> Unit)? = null
    /** Áudio da última tentativa que falhou (para "tentar de novo"). */
    private var pendingAudio: ByteArray? = null

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // app foi para o fundo / tela bloqueou: aproveita o que já foi dito
                if (_state.value is VoiceState.Recording) stop()
            }
        })
    }

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** O PC consegue transcrever? (null = pode tentar) */
    fun unavailableReason(): String? {
        val online = connection.state.value as? ConnState.Online
        if (online != null && !online.pc.has("stt")) return "Atualize o Noc Companion no PC para usar o ditado."
        val stt = connection.status.value?.stt
        if (stt?.state == "off") return "A transcrição de voz está desligada no PC."
        return null
    }

    @SuppressLint("MissingPermission")
    fun start(hold: Boolean) {
        if (_state.value is VoiceState.Recording || _state.value is VoiceState.Transcribing) return
        if (!hasPermission()) {
            _state.value = VoiceState.Problem("Permita o uso do microfone para ditar.", VoiceState.Problem.Kind.PERMISSION)
            return
        }
        unavailableReason()?.let {
            _state.value = VoiceState.Problem(it, VoiceState.Problem.Kind.UNAVAILABLE)
            return
        }
        val debugWav = java.io.File(context.filesDir, "debug-mic.wav")
        if (com.noc.app.BuildConfig.DEBUG && debugWav.exists()) {
            // só no build de desenvolvimento: fala gravada tocada em tempo real no lugar do microfone (o emulador não tem microfone)
            begin(WavSource(debugWav), hold)
            return
        }
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        } catch (e: Exception) {
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            _state.value = VoiceState.Problem("Não foi possível abrir o microfone.", VoiceState.Problem.Kind.MIC_BUSY)
            return
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            _state.value = VoiceState.Problem("O microfone está sendo usado por outro app.", VoiceState.Problem.Kind.MIC_BUSY)
            return
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            _state.value = VoiceState.Problem("O microfone está sendo usado por outro app.", VoiceState.Problem.Kind.MIC_BUSY)
            return
        }
        begin(MicSource(rec), hold)
    }

    private fun begin(source: PcmSource, hold: Boolean) {
        requestFocus()
        recorder = source
        synchronized(lock) { pcm.reset(); sentBytes = 0; nextSeq = 0 }
        pendingAudio = null
        startedAt = System.currentTimeMillis()
        _state.value = VoiceState.Recording(startedAt, emptyList(), hold)
        releaseConn = connection.acquire()
        sessionId = UUID.randomUUID().toString()
        captureJob = scope.launch(Dispatchers.IO) { capture(source) }
        senderJob = scope.launch(Dispatchers.IO) { streamToPc() }
    }

    private suspend fun capture(rec: PcmSource) {
        val frame = ShortArray(RATE / 20) // 50 ms
        val bytes = ByteArray(frame.size * 2)
        var levels = ArrayDeque<Float>()
        var peak = 0.0
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val n = rec.read(frame)
            if (n <= 0) {
                if (n == AudioRecord.ERROR_DEAD_OBJECT || n == AudioRecord.ERROR_INVALID_OPERATION || n < 0) {
                    withContext(Dispatchers.Main) { stop() }
                    return
                }
                continue
            }
            var acc = 0.0
            for (i in 0 until n) {
                val s = frame[i].toInt()
                acc += (s * s).toDouble()
                bytes[i * 2] = (s and 0xff).toByte()
                bytes[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
            }
            synchronized(lock) { pcm.write(bytes, 0, n * 2) }
            val rms = sqrt(acc / n) / 32768.0
            if (rms > peak) peak = rms
            // escala perceptiva: -55 dB → 0, -10 dB → 1
            val db = 20 * log10(rms.coerceAtLeast(1e-6))
            levels.addLast(((db + 55) / 45).toFloat().coerceIn(0f, 1f))
            while (levels.size > 48) levels.removeFirst()
            val elapsed = System.currentTimeMillis() - startedAt
            val cur = _state.value
            if (cur is VoiceState.Recording) {
                _state.value = cur.copy(levels = levels.toList(), noSignal = elapsed > 2500 && peak < 0.0005)
            }
            if (elapsed > MAX_MS) {
                withContext(Dispatchers.Main) { stop() }
                return
            }
        }
    }

    /** Vai mandando o áudio enquanto a pessoa fala: ao parar, só falta o último pedaço. */
    private suspend fun streamToPc() {
        var begun = false
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            delay(500)
            if (!begun) begun = runCatching { begin(sessionId!!) }.isSuccess
            if (!begun) continue
            runCatching { sendPending(final = false) }.onFailure { begun = false; sessionId = UUID.randomUUID().toString(); synchronized(lock) { sentBytes = 0; nextSeq = 0 } }
        }
    }

    private suspend fun begin(id: String) {
        connection.call("stt.begin", buildJsonObject {
            put("id", id)
            put("vocab", buildJsonArray { prefs.current().vocab.forEach { add(JsonPrimitive(it)) } })
        }, timeoutMs = 10_000, waitMs = 2_000)
    }

    private suspend fun sendPending(final: Boolean) {
        while (true) {
            val (chunk, seq) = synchronized(lock) {
                val all = pcm.size()
                val available = all - sentBytes
                if (available <= 0 || (!final && available < CHUNK / 2)) return
                val len = minOf(CHUNK, available)
                val arr = pcm.slice(sentBytes, len)
                arr to nextSeq
            }
            connection.call("stt.chunk", buildJsonObject {
                put("id", sessionId!!); put("seq", seq); put("data", Base64.encodeToString(chunk, Base64.NO_WRAP))
            }, timeoutMs = 15_000, waitMs = 2_000)
            synchronized(lock) { sentBytes += chunk.size; nextSeq++ }
        }
    }

    /** Termina de ouvir e transcreve. */
    fun stop() {
        val rec = recorder ?: return
        recorder = null
        captureJob?.cancel()
        senderJob?.cancel()
        rec.stop()
        abandonFocus()
        val audio = synchronized(lock) { pcm.toByteArray() }
        val ms = audio.size / 2 * 1000L / RATE
        if (ms < 350) {
            finishConn()
            _state.value = VoiceState.Idle
            return
        }
        _state.value = VoiceState.Transcribing(ms)
        scope.launch(Dispatchers.IO) { transcribe(audio) }
    }

    /** Cancela sem transcrever (e descarta o áudio). */
    fun cancel() {
        val rec = recorder
        recorder = null
        captureJob?.cancel()
        senderJob?.cancel()
        rec?.stop()
        abandonFocus()
        val id = sessionId
        synchronized(lock) { pcm.reset() }
        pendingAudio = null
        finishConn()
        _state.value = VoiceState.Idle
        if (id != null) scope.launch { runCatching { connection.call("stt.cancel", buildJsonObject { put("id", id) }, waitMs = 500) } }
    }

    fun dismissProblem() {
        if (_state.value is VoiceState.Problem) _state.value = VoiceState.Idle
        pendingAudio = null
    }

    /** Reenvia o áudio guardado depois de uma falha de conexão. */
    fun retry() {
        val audio = pendingAudio ?: return
        _state.value = VoiceState.Transcribing(audio.size / 2 * 1000L / RATE)
        releaseConn = releaseConn ?: connection.acquire()
        scope.launch(Dispatchers.IO) { transcribe(audio) }
    }

    private suspend fun transcribe(audio: ByteArray) {
        try {
            val r = withRetry(audio)
            synchronized(lock) { pcm.reset() }
            pendingAudio = null
            val text = r.str("text").orEmpty()
            if (r.bool("empty") == true || text.isBlank()) {
                _state.value = VoiceState.Problem(r.str("hint") ?: "Não ouvi nenhuma fala.", VoiceState.Problem.Kind.EMPTY)
            } else {
                _results.emit(VoiceResult(text, r.str("hint")))
                _state.value = VoiceState.Idle
            }
        } catch (e: Exception) {
            pendingAudio = audio
            val msg = when {
                e is RpcError && e.code == "stt_off" -> "A transcrição de voz está desligada no PC."
                e is RpcError && e.code == "stt_unavailable" -> "A transcrição ainda está sendo preparada no PC. Tente de novo em instantes."
                connection.state.value !is ConnState.Online -> "Sem conexão com o PC. Sua fala ficou guardada: toque para tentar de novo."
                else -> "Não foi possível transcrever agora. Toque para tentar de novo."
            }
            _state.value = VoiceState.Problem(msg, VoiceState.Problem.Kind.PC, retry = true)
        } finally {
            finishConn()
        }
    }

    /**
     * Fecha a gravação no PC. Se a conexão caiu no meio (ou a gravação no PC sumiu), abre outra e manda
     * o áudio inteiro de novo — até 3 tentativas, esperando a conexão voltar.
     */
    private suspend fun withRetry(audio: ByteArray): kotlinx.serialization.json.JsonObject {
        var attempt = 0
        var fresh = false
        while (true) {
            try {
                if (fresh) {
                    sessionId = UUID.randomUUID().toString()
                    synchronized(lock) { pcm.reset(); pcm.write(audio); sentBytes = 0; nextSeq = 0 }
                    begin(sessionId!!)
                }
                sendPending(final = true)
                return connection.call("stt.end", buildJsonObject { put("id", sessionId!!) }, timeoutMs = 120_000, waitMs = 15_000)
            } catch (e: Exception) {
                if (e is RpcError && e.code in setOf("stt_off", "stt_unavailable")) throw e
                if (++attempt >= 3) throw e
                fresh = true
                delay(1500L * attempt)
            }
        }
    }

    private fun finishConn() {
        releaseConn?.invoke()
        releaseConn = null
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // ligação chegando, outro app tocando áudio ou gravando: para e aproveita o que foi dito
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            scope.launch(Dispatchers.Main) { if (_state.value is VoiceState.Recording) stop() }
        }
    }

    private fun requestFocus() {
        val am = context.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        }
    }

    private fun abandonFocus() {
        val am = context.getSystemService(AudioManager::class.java)
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    companion object {
        const val RATE = 16000
        private const val CHUNK = 32000 // 1 s de áudio
        private const val MAX_MS = 5 * 60_000L
    }
}
