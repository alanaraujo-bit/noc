package com.noc.app.service

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.noc.app.MainActivity
import com.noc.app.R
import com.noc.app.chat.ChatEngine
import com.noc.app.chat.GenStats
import com.noc.app.chat.LiveReply
import com.noc.app.chat.MessageStatus
import com.noc.app.data.db.MessageEntity
import com.noc.app.data.db.NocDatabase
import com.noc.app.data.prefs.LockPrivacy
import com.noc.app.data.prefs.Prefs
import com.noc.app.ui.Texts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Todas as notificações do Noc. Três canais (o usuário controla cada um nas configurações do Android):
 *  - Respostas: resposta pronta, falhou, interrompida;
 *  - Tarefas em andamento: uma única notificação atualizada enquanto o PC trabalha;
 *  - Seu PC e modelos: modelo pronto/falhou, PC ficou online/offline (opcionais).
 * Nada de spam: quem está vendo a conversa não recebe aviso; várias respostas juntas viram um grupo.
 */
class Notifier(
    private val context: Context,
    private val prefs: Prefs,
    private val db: NocDatabase,
    private val engine: ChatEngine,
) {
    private val nm get() = context.getSystemService(NotificationManager::class.java)

    fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun inForeground() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /** Uma tarefa terminou. Decide se e como avisar. */
    suspend fun onFinished(msg: MessageEntity, content: String, status: String, error: String?, stats: GenStats?) = withContext(Dispatchers.IO) {
        val p = prefs.current()
        if (!p.notifyWhenDone || !canNotify()) return@withContext
        // parar foi decisão sua: não precisa de aviso
        if (status == MessageStatus.CANCELLED) return@withContext
        val foreground = inForeground()
        // está olhando para essa conversa: a resposta aparece na tela, não precisa de aviso
        if (foreground && engine.visibleConversation.value == msg.conversationId) return@withContext
        ensureChannels(context)
        val conv = db.conversations().get(msg.conversationId)
        val model = msg.modelName ?: stats?.modelName ?: "Seu PC"
        val (title, text) = when (status) {
            MessageStatus.ERROR -> "Não foi possível concluir" to (Texts.replyError(error) + " Toque para tentar de novo.")
            MessageStatus.CANCELLED -> "Resposta interrompida" to "Você parou esta resposta."
            MessageStatus.INTERRUPTED -> if (error == "pc_restarted" || error == "job_unknown") "Seu PC reiniciou antes do fim" to "Toque para continuar ou tentar de novo."
                else "Resposta interrompida" to "Toque para continuar."
            else -> "$model terminou" to plain(content).take(300).ifBlank { "Sua resposta está pronta." }
        }
        val duration = stats?.wallMs ?: stats?.totalMs
        val sub = listOfNotNull(conv?.title, duration?.takeIf { status == MessageStatus.DONE && it > 15_000 }?.let { "em " + Texts.seconds(it) }).joinToString(" · ")
        val notice = if (status == MessageStatus.DONE) "Sua resposta está pronta." else "Uma resposta precisa da sua atenção."
        // "Só o aviso" com o celular bloqueado: o Android só esconde o conteúdo de notificações privadas
        // se o usuário escolheu isso no sistema, então o Noc nem coloca o conteúdo
        if (p.lockPrivacy == LockPrivacy.NOTICE && locked()) {
            val g = generic(notice, openIntent(msg.conversationId, msg.id), foreground)
            nm.notify(idFor(msg.id), g)
            updateSummary(p.lockPrivacy)
            return@withContext
        }
        val b = NotificationCompat.Builder(context, CH_REPLIES)
            .setSmallIcon(R.drawable.ic_stat_noc)
            .setColor(0xFFC45A1A.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(sub.ifBlank { null })
            .setAutoCancel(true)
            .setGroup(GROUP_REPLIES)
            .setCategory(if (status == MessageStatus.ERROR) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openIntent(msg.conversationId, msg.id))
        // no app, em outra conversa: aviso discreto (sem som)
        if (foreground) b.setSilent(true).setPriority(NotificationCompat.PRIORITY_LOW)
        applyPrivacy(b, p.lockPrivacy, notice)
        if (p.lockPrivacy == LockPrivacy.NOTICE) b.addExtras(Bundle().apply { putString(EXTRA_NOTICE, notice) })
        when (status) {
            MessageStatus.DONE -> if (content.isNotBlank()) b.addAction(0, "Copiar", actionIntent(TaskActionReceiver.COPY, msg.id))
            MessageStatus.ERROR, MessageStatus.INTERRUPTED -> b.addAction(0, "Tentar de novo", actionIntent(TaskActionReceiver.RETRY, msg.id))
        }
        nm.notify(idFor(msg.id), b.build())
        updateSummary(p.lockPrivacy)
    }

    private fun locked() = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun generic(text: String, open: PendingIntent?, silent: Boolean): Notification =
        NotificationCompat.Builder(context, CH_REPLIES)
            .setSmallIcon(R.drawable.ic_stat_noc)
            .setColor(0xFFC45A1A.toInt())
            .setContentTitle("Noc")
            .setContentText(text)
            .setAutoCancel(true)
            .setGroup(GROUP_REPLIES)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setSilent(silent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    /**
     * A tela apagou (vai bloquear): no modo "Só o aviso", as respostas avisadas com o celular em uso
     * trocam o conteúdo pelo aviso genérico, sem tocar de novo. Chamado pelo receptor de tela apagada.
     */
    fun redactForLock(privacy: LockPrivacy) {
        if (privacy != LockPrivacy.NOTICE) return
        runCatching {
            nm.activeNotifications.forEach { sbn ->
                val notice = sbn.notification.extras.getString(EXTRA_NOTICE) ?: return@forEach
                nm.notify(sbn.tag, sbn.id, generic(notice, sbn.notification.contentIntent, silent = true))
            }
        }
    }

    private fun updateSummary(privacy: LockPrivacy) {
        val active = nm.activeNotifications.count { it.notification.group == GROUP_REPLIES && it.id != ID_SUMMARY }
        if (active < 2) return
        val s = NotificationCompat.Builder(context, CH_REPLIES)
            .setSmallIcon(R.drawable.ic_stat_noc)
            .setColor(0xFFC45A1A.toInt())
            .setContentTitle("$active respostas prontas")
            .setGroup(GROUP_REPLIES)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(openIntent(null, null))
            .setVisibility(if (privacy == LockPrivacy.HIDDEN) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        nm.notify(ID_SUMMARY, s)
    }

    fun cancelFor(messageId: String) = nm.cancel(idFor(messageId))

    /** Notificação única de progresso (usada pelo serviço em primeiro plano). */
    fun progress(tasks: List<LiveReply>, titles: Map<String, String?>, online: Boolean, privacy: LockPrivacy, modelOp: String?): Notification {
        ensureChannels(context)
        val first = tasks.minByOrNull { it.startedAt }
        val title = when {
            first == null && modelOp != null -> "Carregando $modelOp…"
            first == null -> "Noc"
            tasks.size > 1 -> "${tasks.size} tarefas no seu PC"
            else -> when (first.phase) {
                LiveReply.Phase.GENERATING, LiveReply.Phase.THINKING -> if (first.modelName != null) "${first.modelName} está respondendo" else "Gerando resposta"
                else -> first.describe(online)
            }
        }
        val text = when {
            first == null -> "Pode fechar o app; avisamos quando terminar."
            tasks.size > 1 -> tasks.joinToString(" · ") { it.describe(online).removeSuffix("…") }
            first.phase == LiveReply.Phase.GENERATING || first.phase == LiveReply.Phase.THINKING ->
                listOfNotNull(first.describe(online).removeSuffix("…"), first.tokens.takeIf { it > 0 }?.let { "$it tokens" }).joinToString(" · ")
            else -> titles[first.conversationId]?.takeIf { privacy == LockPrivacy.FULL } ?: "Pode fechar o app; avisamos quando terminar."
        }
        val b = NotificationCompat.Builder(context, CH_TASKS)
            .setSmallIcon(R.drawable.ic_stat_noc)
            .setColor(0xFFC45A1A.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setWhen(first?.startedAt ?: System.currentTimeMillis())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(if (first != null && tasks.size == 1) openIntent(first.conversationId, first.messageId) else openActivity())
        if (first?.phase == LiveReply.Phase.UPLOADING && first.upload != null) b.setProgress(100, (first.upload * 100).toInt(), false)
        if (first != null && tasks.size == 1) b.addAction(0, "Parar", actionIntent(TaskActionReceiver.STOP, first.messageId))
        applyPrivacy(b, privacy, "Seu PC está trabalhando")
        return b.build()
    }

    /** Modelo que o usuário pediu para carregar terminou (ou falhou). */
    suspend fun onModelEvent(name: String, ok: Boolean, seconds: Double?, error: String?) = withContext(Dispatchers.IO) {
        val p = prefs.current()
        if (!p.notifyModels || !canNotify() || inForeground()) return@withContext
        ensureChannels(context)
        val b = NotificationCompat.Builder(context, CH_PC)
            .setSmallIcon(R.drawable.ic_stat_noc)
            .setColor(0xFFC45A1A.toInt())
            .setContentTitle(if (ok) "$name pronto" else "Não foi possível carregar $name")
            .setContentText(if (ok) seconds?.let { "Modelo carregado em ${"%.1f".format(it).replace('.', ',')} s." } ?: "Modelo carregado." else error ?: "Abra os detalhes no app.")
            .setAutoCancel(true)
            .setContentIntent(openRoute("models"))
        nm.notify(ID_MODEL, b.build())
    }

    suspend fun onPcStatus(online: Boolean, pcName: String) = withContext(Dispatchers.IO) {
        val p = prefs.current()
        if (!p.notifyPcStatus || !canNotify() || inForeground()) return@withContext
        ensureChannels(context)
        val b = NotificationCompat.Builder(context, CH_PC)
            .setSmallIcon(R.drawable.ic_stat_noc)
            .setColor(0xFFC45A1A.toInt())
            .setContentTitle(if (online) "Seu PC de IA está disponível" else "Seu PC ficou offline")
            .setContentText(pcName)
            .setAutoCancel(true)
            .setContentIntent(openIntent(null, null))
        nm.notify(ID_PC, b.build())
    }

    private fun applyPrivacy(b: NotificationCompat.Builder, privacy: LockPrivacy, publicText: String) {
        when (privacy) {
            LockPrivacy.FULL -> b.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            LockPrivacy.NOTICE -> b.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(
                NotificationCompat.Builder(context, CH_REPLIES).setSmallIcon(R.drawable.ic_stat_noc)
                    .setContentTitle("Noc").setContentText(publicText).build(),
            )
            LockPrivacy.HIDDEN -> b.setVisibility(NotificationCompat.VISIBILITY_SECRET)
        }
    }

    private fun openIntent(conversationId: String?, messageId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            conversationId?.let { putExtra(MainActivity.EXTRA_CONVERSATION, it) }
            messageId?.let { putExtra(MainActivity.EXTRA_MESSAGE, it) }
        }
        return PendingIntent.getActivity(
            context, (messageId ?: conversationId)?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openActivity() = openRoute("activity")

    private fun openRoute(route: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_ROUTE, route)
        }
        return PendingIntent.getActivity(context, route.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionIntent(action: String, messageId: String): PendingIntent {
        val intent = Intent(context, TaskActionReceiver::class.java).apply {
            this.action = action
            putExtra(TaskActionReceiver.EXTRA_MESSAGE, messageId)
        }
        return PendingIntent.getBroadcast(context, (action + messageId).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun plain(s: String) = s.replace(Regex("```[\\s\\S]*?```"), "[código]").replace(Regex("[#*_`>|]"), "").replace(Regex("\\s+"), " ").trim()

    companion object {
        const val CH_REPLIES = "replies"
        /** Marca as respostas que devem virar só o aviso quando a tela bloquear (modo "Só o aviso"). */
        private const val EXTRA_NOTICE = "noc.notice"
        const val CH_TASKS = "generation"
        const val CH_PC = "pc"
        private const val GROUP_REPLIES = "noc.replies"
        const val ID_PROGRESS = 101
        private const val ID_SUMMARY = 102
        private const val ID_MODEL = 103
        private const val ID_PC = 104

        fun idFor(messageId: String) = 1000 + (messageId.hashCode() and 0x7ffffff)

        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_REPLIES, "Respostas", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Quando uma resposta fica pronta (ou não pôde ser concluída) e você não está vendo a conversa"
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_TASKS, "Tarefas em andamento", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Uma notificação discreta enquanto seu PC está trabalhando"
                    setShowBadge(false)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_PC, "Seu PC e modelos", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Modelo pronto, falha ao carregar, PC ficou disponível ou offline"
                },
            )
        }
    }
}
