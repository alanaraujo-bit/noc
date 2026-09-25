package com.noc.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.noc.app.MainActivity
import com.noc.app.NocApp
import com.noc.app.R
import com.noc.app.chat.MessageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Mantém o app vivo enquanto uma resposta está sendo gerada, mesmo com a tela bloqueada
 * ou o app em segundo plano. Some sozinho quando não há mais geração.
 */
class GenerationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels(this)
        val notification = progressNotification(this, null, 1)
        try {
            ServiceCompat.startForeground(
                this, ID_PROGRESS, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (e: Exception) {
            // Android pode negar FGS em alguns estados; a geração segue no PC e é retomada ao voltar.
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop == null) loop = scope.launch { watch() }
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        val engine = (application as NocApp).container.chat
        val nm = getSystemService(NotificationManager::class.java)
        while (true) {
            val ids = engine.liveIds.value
            if (ids.isEmpty()) {
                delay(400)
                if (engine.liveIds.value.isEmpty()) break
                continue
            }
            val live = ids.mapNotNull { engine.liveFlow(it)?.value }
            val tps = live.firstNotNullOfOrNull { it.tps }
            if (hasPermission(this)) nm.notify(ID_PROGRESS, progressNotification(this, tps, ids.size))
            delay(1_000)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CH_PROGRESS = "generation"
        private const val CH_DONE = "replies"
        private const val ID_PROGRESS = 101

        fun ensureRunning(context: Context) {
            val app = context.applicationContext
            try {
                ContextCompat.startForegroundService(app, Intent(app, GenerationService::class.java))
            } catch (e: Exception) {
                // Em segundo plano o Android pode recusar; tudo bem, a geração continua no PC.
            }
        }

        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_PROGRESS, "Gerando resposta", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Aparece enquanto seu PC está respondendo"
                    setShowBadge(false)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_DONE, "Respostas prontas", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Avisa quando uma resposta termina com o app em segundo plano"
                },
            )
        }

        private fun hasPermission(context: Context) =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        private fun openApp(context: Context, conversationId: String?): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                conversationId?.let { putExtra(MainActivity.EXTRA_CONVERSATION, it) }
            }
            return PendingIntent.getActivity(
                context, conversationId?.hashCode() ?: 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun progressNotification(context: Context, tps: Double?, count: Int): Notification =
            NotificationCompat.Builder(context, CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat_noc)
                .setContentTitle(if (count > 1) "Gerando $count respostas" else "Gerando resposta")
                .setContentText(tps?.let { "No seu PC · ${"%.0f".format(it)} tokens/s" } ?: "No seu PC")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setProgress(0, 0, true)
                .setContentIntent(openApp(context, null))
                .build()

        /** Avisa que a resposta ficou pronta — só se o app não estiver na tela. */
        fun notifyFinished(context: Context, conversationId: String, content: String, status: String) {
            val app = context.applicationContext as NocApp
            val inForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (inForeground || !hasPermission(context)) return
            CoroutineScope(Dispatchers.IO).launch {
                if (!app.container.prefs.flow.first().notifyWhenDone) return@launch
                val title = app.container.db.conversations().get(conversationId)?.title ?: "Noc"
                ensureChannels(context)
                val text = when (status) {
                    MessageStatus.ERROR -> "Não foi possível concluir a resposta."
                    MessageStatus.CANCELLED -> "Resposta interrompida."
                    else -> content.replace(Regex("[#*_`>]"), "").replace(Regex("\\s+"), " ").trim().take(200)
                }
                val n = NotificationCompat.Builder(context, CH_DONE)
                    .setSmallIcon(R.drawable.ic_stat_noc)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(openApp(context, conversationId))
                    .build()
                context.getSystemService(NotificationManager::class.java).notify(conversationId.hashCode(), n)
            }
        }
    }
}
