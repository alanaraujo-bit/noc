package com.noc.app.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.noc.app.core.crypto.DeviceKey
import com.noc.app.core.crypto.HandshakeClient
import com.noc.app.core.crypto.HandshakeMode
import com.noc.app.core.crypto.ProtocolException
import com.noc.app.core.crypto.Wire
import com.noc.app.data.db.NocDatabase
import com.noc.app.data.db.PcEntity
import com.noc.app.data.prefs.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Motivo, em termos humanos, de não estar conectado. */
sealed interface Problem {
    data object NoInternet : Problem
    data class PcOffline(val lastSeen: Long?) : Problem
    data object CompanionNotResponding : Problem
    data object RelayUnreachable : Problem
    data object LanUnreachable : Problem
    data object Revoked : Problem
    data object PcForgotDevice : Problem
    data object SecurityMismatch : Problem
    data object Incompatible : Problem
    data object RateLimited : Problem
    data object Timeout : Problem
    data class Other(val detail: String) : Problem

    /** Problemas que exigem ação do usuário: não adianta ficar tentando sozinho. */
    val fatal get() = this is Revoked || this is PcForgotDevice || this is SecurityMismatch || this is Incompatible
}

sealed interface ConnState {
    data object NoPc : ConnState
    data class Connecting(val pcName: String, val attempt: Int) : ConnState
    data class Online(val route: Route, val pc: PcInfo, val since: Long) : ConnState
    data class Offline(val pcName: String, val problem: Problem, val retryAt: Long?) : ConnState
    data object Paused : ConnState
}

/** Link de pareamento lido do QR Code (noc://pair?...). */
data class PairingLink(
    val pcSpki: ByteArray,
    val pairingId: String,
    val secret: ByteArray,
    val name: String,
    val relay: String?,
    val lan: List<String>,
) {
    val pcId get() = Wire.idFromSpki(pcSpki)

    companion object {
        fun parse(text: String): PairingLink? = runCatching {
            val uri = android.net.Uri.parse(text.trim())
            if (uri.scheme != "noc" || uri.host != "pair") return null
            PairingLink(
                pcSpki = Wire.unb64Url(uri.getQueryParameter("k")!!),
                pairingId = uri.getQueryParameter("p")!!,
                secret = Wire.unb64Url(uri.getQueryParameter("s")!!),
                name = uri.getQueryParameter("n") ?: "PC",
                relay = uri.getQueryParameter("r"),
                lan = uri.getQueryParameter("l")?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
            )
        }.getOrNull()
    }
}

class PairingFailed(val reason: String) : Exception(reason)

/**
 * Mantém a conexão com o PC ativo: escolhe a melhor rota (LAN primeiro, relay em paralelo),
 * reconecta sozinho, troca de rota quando a rede muda e explica o problema quando não dá.
 */
class ConnectionManager(
    private val context: Context,
    private val db: NocDatabase,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val defaultRelay: String,
) {
    val deviceKey: DeviceKey by lazy { DeviceKey.loadOrCreate() }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val lanClient = client.newBuilder().connectTimeout(2500, TimeUnit.MILLISECONDS).build()
    private val httpClient = client.newBuilder().pingInterval(0, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()

    private val _state = MutableStateFlow<ConnState>(ConnState.Paused)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _session = MutableStateFlow<NocSession?>(null)
    val session: StateFlow<NocSession?> = _session.asStateFlow()

    /** Último status conhecido do PC (mantido quando cai, para a UI mostrar "visto por último"). */
    private val _status = MutableStateFlow<PcStatus?>(null)
    val status: StateFlow<PcStatus?> = _status.asStateFlow()

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()

    private val _events = MutableSharedFlow<PcEvent>(extraBufferCapacity = 4096)
    val events: SharedFlow<PcEvent> = _events

    private val runtimePrefs = context.getSharedPreferences("noc_runtime", Context.MODE_PRIVATE)

    /** Recursos do Companion na última conexão (vale mesmo offline, para o app não esquecer os perfis). */
    @Volatile var lastFeatures: Set<String> = runtimePrefs.getStringSet("features", emptySet()) ?: emptySet()
        private set

    private val _latencyMs = MutableStateFlow<Long?>(null)
    val latencyMs: StateFlow<Long?> = _latencyMs.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val keepAlive = AtomicInteger(0)
    @Volatile private var foreground = false
    @Volatile private var lastForegroundAt = 0L
    @Volatile private var fatal: Problem? = null
    private val connectLock = Mutex()
    private var sessionJob: Job? = null
    /** De qual PC é a sessão atual (para derrubá-la se o usuário trocar de PC). */
    @Volatile private var sessionPcId: String? = null
    private var lastNetwork: Network? = null

    /** PC ativo. flatMapLatest: trocar de PC cancela a observação do anterior (fluxos do Room nunca terminam). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activePc: StateFlow<PcEntity?> = MutableStateFlow<PcEntity?>(null).also { flow ->
        scope.launch {
            prefs.flow.map { it.activePcId }.distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) kotlinx.coroutines.flow.flowOf(null) else db.pcs().observe(id) }
                .collect { pc ->
                    val changed = flow.value?.id != pc?.id
                    flow.value = pc
                    if (changed) poke()
                }
        }
    }

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground = true
                lastForegroundAt = System.currentTimeMillis()
                poke()
            }

            override fun onStop(owner: LifecycleOwner) {
                foreground = false
                lastForegroundAt = System.currentTimeMillis()
                poke()
            }
        })
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val changed = lastNetwork != null && lastNetwork != network
                lastNetwork = network
                if (changed) onNetworkChanged() else poke()
            }

            override fun onLost(network: Network) {
                if (network == lastNetwork) lastNetwork = null
            }
        })
        scope.launch { mainLoop() }
        scope.launch { maintenanceLoop() }
    }

    /** Acorda o laço principal (troca de PC, "tentar agora", app voltou ao primeiro plano...). */
    fun poke() {
        wake.trySend(Unit)
    }

    /** Derruba a sessão atual e conecta de novo, escolhendo a rota do zero (ex.: mudou "sempre remoto"). */
    fun reconnect() {
        fatal = null
        dropSession()
        poke()
    }

    fun retryNow() {
        fatal = null
        poke()
    }

    /** Mantém a conexão viva mesmo em segundo plano (ex.: resposta sendo gerada). */
    fun acquire(): () -> Unit {
        keepAlive.incrementAndGet()
        poke()
        var released = false
        return {
            if (!released) {
                released = true
                keepAlive.decrementAndGet()
                poke()
            }
        }
    }

    private val shouldBeActive: Boolean
        get() = foreground || keepAlive.get() > 0 || System.currentTimeMillis() - lastForegroundAt < BACKGROUND_GRACE_MS

    // ------------------------------------------------------------------ laço principal

    private suspend fun mainLoop() {
        var attempt = 0
        while (true) {
            val pc = activePc.value ?: db.pcs().let { dao -> prefs.current().activePcId?.let { dao.get(it) } }
            when {
                pc == null -> {
                    dropSession()
                    _state.value = ConnState.NoPc
                    waitWake(null)
                    continue
                }
                !shouldBeActive -> {
                    dropSession()
                    _state.value = ConnState.Paused
                    waitWake(if (keepAlive.get() == 0 && !foreground) BACKGROUND_GRACE_MS else null)
                    continue
                }
            }
            pc!!
            if (_session.value != null && sessionPcId != pc.id) dropSession()
            val current = _session.value
            if (current != null && current.isOpen) {
                // conectado: espera cair, ou um cutucão
                select {
                    current.closed.onAwait { }
                    wake.onReceive { }
                }
                if (!current.isOpen) {
                    // só limpa se ainda for a mesma sessão (outra rota pode ter instalado uma nova enquanto esperávamos)
                    if (_session.value === current) _session.value = null
                    android.util.Log.i("Noc", "sessão ${current.route} caiu")
                    attempt = 0
                }
                continue
            }
            val blocked = fatal
            if (blocked != null) {
                _state.value = ConnState.Offline(pc.name, blocked, null)
                waitWake(null)
                continue
            }

            attempt++
            _state.value = ConnState.Connecting(pc.name, attempt)
            // Outra rota (pareamento, troca de rede) pode ter conectado enquanto esperávamos o lock.
            val result = connectLock.withLock {
                _session.value?.takeIf { it.isOpen }?.let { Result.success(it) } ?: connect(pc, allowLan = true)
            }
            result.fold(
                onSuccess = { s ->
                    attempt = 0
                    if (s !== _session.value) install(pc, s)
                },
                onFailure = { e ->
                    val problem = classify(e)
                    if (problem.fatal) fatal = problem
                    val backoff = if (problem.fatal) null else backoffMs(attempt)
                    _state.value = ConnState.Offline(pc.name, problem, backoff?.let { System.currentTimeMillis() + it })
                    waitWake(backoff)
                },
            )
        }
    }

    private suspend fun waitWake(timeoutMs: Long?) {
        if (timeoutMs == null) wake.receive() else withTimeoutOrNull(timeoutMs) { wake.receive() }
    }

    private fun backoffMs(attempt: Int): Long = when (attempt) {
        1 -> 1_000L
        2 -> 2_000L
        3 -> 4_000L
        4 -> 8_000L
        else -> 15_000L
    }

    private fun dropSession() {
        _session.value?.close()
        _session.value = null
        sessionJob?.cancel()
    }

    private suspend fun install(pc: PcEntity, s: NocSession) {
        val old = _session.value
        android.util.Log.i("Noc", "sessão nova (${s.route}); antiga=${old?.route}")
        sessionJob?.cancel()
        sessionPcId = pc.id
        _session.value = s
        if (old !== s) old?.close()
        val info = PcInfo.parse(s.welcome["pc"]!!.jsonObject)
        lastFeatures = info.features
        runtimePrefs.edit().putStringSet("features", info.features).apply()
        s.welcome["status"]?.jsonObject?.let { _status.value = PcStatus.parse(it) }
        _state.value = ConnState.Online(s.route, info, System.currentTimeMillis())
        withContext(Dispatchers.IO) {
            db.pcs().updateAfterConnect(
                pc.id, System.currentTimeMillis(), s.route.name.lowercase(),
                info.lan.joinToString(","), info.name, info.relay,
            )
        }
        sessionJob = scope.launch {
            launch { refreshModels() }
            s.events.collect { e ->
                when (e.name) {
                    "status" -> _status.value = PcStatus.parse(e.data)
                    "model" -> launch { refreshModels() }
                    "pc" -> {
                        val p = PcInfo.parse(e.data)
                        (state.value as? ConnState.Online)?.let { _state.value = it.copy(pc = p) }
                        db.pcs().updateAfterConnect(pc.id, System.currentTimeMillis(), s.route.name.lowercase(), p.lan.joinToString(","), p.name, p.relay)
                    }
                    "bye" -> if (e.data["reason"]?.toString()?.contains("revoked") == true) {
                        fatal = Problem.Revoked
                    }
                }
                _events.emit(e)
            }
        }
    }

    /** Pede o estado ao PC agora (telas de status/modelos). */
    suspend fun refreshStatus() {
        val s = _session.value?.takeIf { it.isOpen } ?: return
        runCatching { s.call("status") }.onSuccess { _status.value = PcStatus.parse(it) }
    }

    suspend fun refreshModels() {
        val s = _session.value ?: return
        runCatching { s.call("models.list") }.onSuccess { r ->
            _models.value = r.arr("models")?.mapNotNull { it.asObj() }?.map { ModelInfo.parse(it) } ?: emptyList()
        }
    }

    /** Chamada RPC na sessão atual, esperando conectar por até [waitMs]. */
    suspend fun call(method: String, params: JsonObject = NocSession.EMPTY, timeoutMs: Long = 20_000, waitMs: Long = 8_000): JsonObject {
        val s = _session.value?.takeIf { it.isOpen }
            ?: withTimeoutOrNull(waitMs) { session.first { it != null && it.isOpen } }
            ?: throw NotConnected()
        return s.call(method, params, timeoutMs)
    }

    class NotConnected : Exception("not connected")

    // ------------------------------------------------------------------ rede mudou / LAN

    private fun onNetworkChanged() {
        // Rede nova (Wi-Fi <-> 4G): conecta pela nova rota antes de largar a antiga (make-before-break).
        scope.launch {
            val pc = activePc.value ?: return@launch
            if (!shouldBeActive) return@launch
            delay(600) // deixa a rede estabilizar (DHCP/DNS)
            val r = connectLock.withLock { connect(pc, allowLan = true) }
            r.onSuccess { install(pc, it) }.onFailure { poke() }
        }
    }

    fun onWifiLike(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun hasInternet(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Mede latência e tenta subir de relay para LAN quando der. */
    private suspend fun maintenanceLoop() {
        var tick = 0
        while (true) {
            delay(15_000)
            tick++
            val s = _session.value ?: continue
            if (!s.isOpen) continue
            val t0 = System.currentTimeMillis()
            runCatching { s.call("ping", timeoutMs = 8_000) }
                .onSuccess { _latencyMs.value = System.currentTimeMillis() - t0 }
            if (s.route == Route.RELAY && tick % 3 == 0 && onWifiLike() && !prefs.current().forceRelay) {
                val pc = activePc.value ?: continue
                if (pc.lanEndpoints.isEmpty()) continue
                val up = connectLock.withLock { connectLan(pc) }
                if (up != null) install(pc, up)
            }
        }
    }

    // ------------------------------------------------------------------ conexão

    private fun relayConnectUrl(relay: String, pcId: String) = relay.trimEnd('/') + "/v1/connect?pc=" + pcId
    private fun lanUrl(endpoint: String) = "ws://$endpoint/v1/ws"

    private fun sessionAuth(th: ByteArray) =
        NocSession.sessionAuth(deviceKey.deviceId, deviceKey.sign(HandshakeClient.clientSignatureInput(th)))

    private suspend fun connectLan(pc: PcEntity): NocSession? {
        val spki = Wire.unb64(pc.spki)
        return race(pc.lanEndpoints.map { ep ->
            0L to suspend {
                withTimeout(4_000) {
                    NocSession.connect(lanClient, lanUrl(ep), Route.LAN, HandshakeMode.SESSION, spki) { sessionAuth(it) }
                }
            }
        }).getOrNull()
    }

    /**
     * "Happy eyeballs": tenta os endereços LAN e, 400 ms depois, o relay; a primeira sessão autenticada vence.
     */
    private suspend fun connect(pc: PcEntity, allowLan: Boolean): Result<NocSession> {
        val forceRelay = prefs.current().forceRelay
        val spki = Wire.unb64(pc.spki)
        val useLan = allowLan && !forceRelay && pc.lanEndpoints.isNotEmpty() && onWifiLike()
        val relay = pc.relayUrl
        val attempts = mutableListOf<Pair<Long, suspend () -> NocSession>>()
        if (useLan) pc.lanEndpoints.forEach { ep ->
            attempts += 0L to suspend {
                withTimeout(4_000) {
                    NocSession.connect(lanClient, lanUrl(ep), Route.LAN, HandshakeMode.SESSION, spki) { sessionAuth(it) }
                }
            }
        }
        if (relay != null) attempts += (if (useLan) 400L else 0L) to suspend {
            withTimeout(15_000) {
                NocSession.connect(client, relayConnectUrl(relay, pc.id), Route.RELAY, HandshakeMode.SESSION, spki) { sessionAuth(it) }
            }
        }
        if (attempts.isEmpty()) return Result.failure(LanOnly())
        return race(attempts)
    }

    private class LanOnly : Exception("lan only")

    /**
     * Corre as tentativas em paralelo (cada uma com seu atraso inicial). A primeira sessão vence;
     * sessões que terminarem depois são fechadas. Se todas falharem, devolve a falha mais informativa.
     */
    private suspend fun race(attempts: List<Pair<Long, suspend () -> NocSession>>): Result<NocSession> = supervisorScope {
        val results = Channel<Result<NocSession>>(Channel.UNLIMITED)
        val won = AtomicBoolean(false)
        val jobs = attempts.map { (stagger, attempt) ->
            launch(Dispatchers.IO) {
                val r = try {
                    if (stagger > 0) delay(stagger)
                    Result.success(attempt())
                } catch (e: CancellationException) {
                    if (won.get()) return@launch
                    Result.failure(if (e is TimeoutCancellationException) e else IOException("cancelada"))
                } catch (e: Throwable) {
                    Result.failure(e)
                }
                val s = r.getOrNull()
                if (s != null && !won.compareAndSet(false, true)) {
                    s.close() // outra rota já venceu
                    return@launch
                }
                results.send(r)
            }
        }
        val failures = mutableListOf<Throwable>()
        repeat(attempts.size) {
            val r = results.receive()
            if (r.isSuccess) {
                jobs.forEach { it.cancel() }
                return@supervisorScope r
            }
            failures += r.exceptionOrNull()!!
        }
        val best = failures.firstOrNull { it is AuthDenied || it is PinMismatch || it is ProtocolException }
            ?: failures.firstOrNull { it is TransportException && it.info.code in 4000..4999 }
            ?: failures.lastOrNull()
        Result.failure(best ?: IOException("sem rota"))
    }

    private suspend fun classify(e: Throwable): Problem {
        val pc = activePc.value
        android.util.Log.i("Noc", "conexão falhou: ${e.javaClass.simpleName}: ${e.message} ${(e as? TransportException)?.info}")
        return when {
            e is AuthDenied && e.code == "revoked" -> Problem.Revoked
            e is AuthDenied && e.code == "unknown" -> Problem.PcForgotDevice
            e is AuthDenied && e.code == "rate" -> Problem.RateLimited
            e is PinMismatch -> Problem.SecurityMismatch
            e is ProtocolException && (e.code == "version" || e.code == "bad_welcome") -> Problem.Incompatible
            e is ProtocolException && e.code == "rate" -> Problem.RateLimited
            e is ProtocolException && e.code == "server_signature" -> Problem.SecurityMismatch
            e is LanOnly -> Problem.LanUnreachable
            e is TransportException && e.info.code == 4404 ->
                Problem.PcOffline(e.info.reason.substringAfter("pc_offline:", "").toLongOrNull())
            e is TransportException && e.info.code == 4408 -> Problem.CompanionNotResponding
            e is TransportException && (e.info.code == 4429 || e.info.code == 429) -> Problem.RateLimited
            !hasInternet() -> Problem.NoInternet
            e is TimeoutCancellationException -> if (pc?.relayUrl != null && !relayHealthy(pc.relayUrl)) Problem.RelayUnreachable else Problem.Timeout
            e is TransportException && (e.info.error is UnknownHostException || e.info.error is ConnectException) ->
                if (pc?.relayUrl != null && !relayHealthy(pc.relayUrl)) {
                    if (hasInternetReally()) Problem.RelayUnreachable else Problem.NoInternet
                } else Problem.Other(e.message ?: "")
            e is TransportException && e.info.error is SocketTimeoutException -> Problem.Timeout
            else -> Problem.Other(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Latência do relay em ms, ou null se não respondeu. */
    suspend fun relayLatency(relay: String): Long? = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        if (relayHealthy(relay)) System.currentTimeMillis() - t0 else null
    }

    private suspend fun relayHealthy(relay: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            httpClient.newCall(Request.Builder().url(relay.replace("wss://", "https://").replace("ws://", "http://").trimEnd('/') + "/health").build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun hasInternetReally(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            httpClient.newCall(Request.Builder().url("https://clients3.google.com/generate_204").build()).execute().use { it.code == 204 }
        }.getOrDefault(false)
    }

    /** Presença do PC no relay: diz se o Companion está online e quando foi visto por último. */
    suspend fun presence(pc: PcEntity): Pair<Boolean, Long?>? = withContext(Dispatchers.IO) {
        val relay = pc.relayUrl ?: return@withContext null
        runCatching {
            httpClient.newCall(Request.Builder().url(relay.replace("wss://", "https://").replace("ws://", "http://").trimEnd('/') + "/v1/presence/" + pc.id).build())
                .execute().use { res ->
                    val o = Json.parseToJsonElement(res.body!!.string()).jsonObject
                    (o.bool("online") ?: false) to o.long("lastSeen")
                }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ pareamento

    private fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private fun pairAuth(th: ByteArray, macSecret: ByteArray, idKey: String, idValue: String): JsonObject {
        val spki = deviceKey.publicSpki
        return buildJsonObject {
            put("k", "pair")
            put(idKey, idValue)
            put("pub", Wire.b64(spki))
            put("name", deviceName())
            put("model", deviceModel())
            put("mac", Wire.b64(HandshakeClient.pairMac(macSecret, th, spki)))
            put("sig", Wire.b64(deviceKey.sign(HandshakeClient.clientSignatureInput(th))))
        }
    }

    /** Pareamento pelo QR: a chave do PC vem no próprio QR, então a conexão já nasce fixada. */
    suspend fun pairWithLink(link: PairingLink): PcEntity = connectLock.withLock {
        // O QR é de uso único: tenta a LAN primeiro e só usa o relay se ela não resolver.
        val lanResult = if (link.lan.isNotEmpty()) race(link.lan.map { ep ->
            0L to suspend {
                withTimeout(4_000) {
                    NocSession.connect(lanClient, lanUrl(ep), Route.LAN, HandshakeMode.PAIR_QR, link.pcSpki) {
                        pairAuth(it, link.secret, "pairing", link.pairingId)
                    }
                }
            }
        }) else Result.failure(LanOnly())
        val session = lanResult.getOrNull() ?: run {
            val lanErr = lanResult.exceptionOrNull()
            if (lanErr is AuthDenied) throw PairingFailed(lanErr.code)
            if (lanErr is PinMismatch) throw PairingFailed("security")
            val relay = link.relay ?: throw PairingFailed("unreachable")
            try {
                withTimeout(20_000) {
                    NocSession.connect(client, relayConnectUrl(relay, link.pcId), Route.RELAY, HandshakeMode.PAIR_QR, link.pcSpki) {
                        pairAuth(it, link.secret, "pairing", link.pairingId)
                    }
                }
            } catch (e: AuthDenied) {
                throw PairingFailed(e.code)
            } catch (e: PinMismatch) {
                throw PairingFailed("security")
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                throw PairingFailed(if (classify(e) is Problem.PcOffline) "pc_offline" else if (hasInternet()) "unreachable" else "no_internet")
            }
        }
        adoptPaired(session, link.pcSpki, link.relay)
    }

    /** Resolve o código "ABCD-EFGH" no relay e pareia (o PC pede aprovação mostrando o SAS). */
    suspend fun pairWithCode(rawCode: String, relayUrl: String = defaultRelay, onSas: (String) -> Unit): PcEntity {
        val code = rawCode.uppercase().filter { it.isLetterOrDigit() }
        if (code.length != 8) throw PairingFailed("bad_code")
        val lookup = code.substring(0, 4)
        val secret = code.substring(4)
        val pcId = withContext(Dispatchers.IO) {
            val url = relayUrl.replace("wss://", "https://").replace("ws://", "http://").trimEnd('/') + "/v1/pair/" + lookup
            try {
                httpClient.newCall(Request.Builder().url(url).build()).execute().use { res ->
                    when (res.code) {
                        200 -> Json.parseToJsonElement(res.body!!.string()).jsonObject.str("pcId")!!
                        404 -> throw PairingFailed("code_not_found")
                        429 -> throw PairingFailed("rate")
                        else -> throw PairingFailed("relay")
                    }
                }
            } catch (e: IOException) {
                throw PairingFailed(if (hasInternet()) "relay" else "no_internet")
            }
        }
        return connectLock.withLock {
            val session = try {
                withTimeout(150_000) {
                    NocSession.connect(client, relayConnectUrl(relayUrl, pcId), Route.RELAY, HandshakeMode.PAIR_CODE, null, onSas) {
                        pairAuth(it, secret.encodeToByteArray(), "code", lookup)
                    }
                }
            } catch (e: AuthDenied) {
                throw PairingFailed(e.code)
            } catch (e: TimeoutCancellationException) {
                throw PairingFailed("timeout")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw PairingFailed("unreachable")
            }
            if (Wire.idFromSpki(session.pcSpki) != pcId) {
                session.close()
                throw PairingFailed("security")
            }
            adoptPaired(session, session.pcSpki, relayUrl)
        }
    }

    private suspend fun adoptPaired(session: NocSession, spki: ByteArray, relay: String?): PcEntity {
        val info = PcInfo.parse(session.welcome["pc"]!!.jsonObject)
        val pc = PcEntity(
            id = Wire.idFromSpki(spki),
            name = info.name,
            spki = Wire.b64(spki),
            relayUrl = info.relay ?: relay,
            lan = info.lan.joinToString(","),
            pairedAt = System.currentTimeMillis(),
            lastConnectedAt = System.currentTimeMillis(),
            lastRoute = session.route.name.lowercase(),
        )
        withContext(Dispatchers.IO) { db.pcs().upsert(pc) }
        fatal = null
        prefs.setActivePc(pc.id)
        (activePc as MutableStateFlow).value = pc
        install(pc, session)
        return pc
    }

    /** Remove o PC deste celular (e, se conectado, revoga este aparelho lá também). */
    suspend fun forgetPc(pc: PcEntity, revokeRemote: Boolean) {
        if (revokeRemote && activePc.value?.id == pc.id) {
            runCatching { call("devices.revoke", buildJsonObject { put("id", deviceKey.deviceId) }, waitMs = 1_000) }
        }
        if (activePc.value?.id == pc.id) {
            dropSession()
            _status.value = null
            _models.value = emptyList()
        }
        db.pcs().delete(pc.id)
        val others = db.pcs().observeAll().first()
        prefs.setActivePc(others.firstOrNull()?.id)
        fatal = null
        poke()
    }

    suspend fun switchPc(id: String) {
        dropSession()
        _status.value = null
        _models.value = emptyList()
        fatal = null
        prefs.setActivePc(id)
        poke()
    }

    companion object {
        private const val BACKGROUND_GRACE_MS = 45_000L
    }
}

