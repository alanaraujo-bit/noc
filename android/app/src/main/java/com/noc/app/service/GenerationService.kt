package com.noc.app.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.noc.app.NocApp
import com.noc.app.chat.LiveReply
import com.noc.app.core.net.ConnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano só enquanto o PC trabalha para você (resposta, imagem, carga de modelo pedida
 * pelo celular). Mantém a conexão para receber o fim na hora, com uma única notificação que se atualiza
 * só quando a etapa muda (o cronômetro corre sozinho). Some quando não há mais nada em andamento.
 * Sem wake lock: o processamento é no PC; o celular só espera.
 */
class GenerationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as NocApp).container
        Notifier.ensureChannels(this)
        val notification = container.chat.notifier.progress(container.chat.liveSnapshot(), emptyMap(), true, com.noc.app.data.prefs.LockPrivacy.NOTICE, container.modelOps.current())
        try {
            ServiceCompat.startForeground(
                this, Notifier.ID_PROGRESS, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (e: Exception) {
            // O Android pode negar em alguns estados; a tarefa segue no PC e o SyncWorker busca o resultado depois.
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop == null) loop = scope.launch { watch() }
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        val container = (application as NocApp).container
        val engine = container.chat
        val nm = getSystemService(NotificationManager::class.java)
        var lastKey = ""
        var lastTokensAt = 0L
        while (true) {
            val tasks = engine.liveSnapshot()
            val op = container.modelOps.current()
            if (tasks.isEmpty() && op == null) {
                delay(600)
                if (engine.liveSnapshot().isEmpty() && container.modelOps.current() == null) break
                continue
            }
            val prefs = container.prefs.current()
            val online = container.connection.state.value is ConnState.Online
            // atualiza quando a etapa muda; a contagem de tokens no máximo a cada 5 s
            val key = tasks.joinToString("|") { "${it.messageId}:${it.phase}:${it.detached}:${it.ahead}:${(it.upload ?: 0f).times(10).toInt()}" } + "|$op|$online"
            val now = System.currentTimeMillis()
            val tokensDue = tasks.any { it.phase == LiveReply.Phase.GENERATING } && now - lastTokensAt > 5_000
            if ((key != lastKey || tokensDue) && prefs.notifyProgress && engine.notifier.canNotify()) {
                lastKey = key
                if (tokensDue) lastTokensAt = now
                val titles = tasks.associate { it.conversationId to container.db.conversations().get(it.conversationId)?.title }
                nm.notify(Notifier.ID_PROGRESS, engine.notifier.progress(tasks, titles, online, prefs.lockPrivacy, op))
            }
            delay(700)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun ensureRunning(context: Context) {
            val app = context.applicationContext
            try {
                ContextCompat.startForegroundService(app, Intent(app, GenerationService::class.java))
            } catch (e: Exception) {
                // Em segundo plano o Android pode recusar; tudo bem, a tarefa continua no PC.
            }
        }
    }
}
