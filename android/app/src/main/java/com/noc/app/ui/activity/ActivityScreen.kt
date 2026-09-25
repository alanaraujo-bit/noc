package com.noc.app.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.noc.app.AppContainer
import com.noc.app.chat.LiveReply
import com.noc.app.chat.MessageStatus
import com.noc.app.core.net.ConnState
import com.noc.app.data.db.MessageEntity
import com.noc.app.ui.Routes
import com.noc.app.ui.Texts
import com.noc.app.ui.components.EmptyState
import com.noc.app.ui.components.SectionLabel
import com.noc.app.ui.components.StatusDot
import com.noc.app.ui.components.TopBar
import com.noc.app.ui.components.pressable
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.Noc
import kotlinx.coroutines.delay

/**
 * Atividade: o que o PC está fazendo por você agora e o que terminou nas últimas 24 h,
 * de todas as conversas. Tocar abre a conversa na resposta certa.
 */
@Composable
fun ActivityScreen(container: AppContainer, nav: NavHostController) {
    val c = Noc.colors
    val conn by container.connection.state.collectAsState()
    val status by container.connection.status.collectAsState()
    val online = conn is ConnState.Online
    val since = remember { System.currentTimeMillis() - 24 * 3_600_000L }
    val recent by container.db.messages().observeRecentJobs(since).collectAsState(initial = emptyList())
    // estado ao vivo (atualiza a cada 0,7 s só enquanto a tela está aberta)
    val live by produceState(emptyList<LiveReply>()) {
        while (true) {
            value = container.chat.liveSnapshot()
            delay(700)
        }
    }
    val titles by produceState(emptyMap<String, String>(), recent, live) {
        val ids = (recent.map { it.conversationId } + live.map { it.conversationId }).toSet()
        value = ids.associateWith { id -> container.db.conversations().get(id)?.title ?: "Conversa" }
    }
    val myDevice = remember { runCatching { container.connection.deviceKey.deviceId }.getOrNull() }
    val otherDevices = status?.tasks?.filter { it.device != null && it.device != myDevice && !it.finished } ?: emptyList()

    val liveIds = live.map { it.messageId }.toSet()
    val done = recent.filter { it.id !in liveIds && it.status == MessageStatus.DONE }
    val failed = recent.filter { it.id !in liveIds && (it.status == MessageStatus.ERROR || it.status == MessageStatus.INTERRUPTED) }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        TopBar("Atividade", onBack = { nav.popBackStack() }, modifier = Modifier.statusBarsPadding())
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item("counts") {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Counter("Em andamento", live.size + otherDevices.size, c.accent, Modifier.weight(1f))
                    Counter("Concluídas", done.size, c.ok, Modifier.weight(1f))
                    Counter("Com erro", failed.size, if (failed.isEmpty()) c.text3 else c.err, Modifier.weight(1f))
                }
            }
            if (live.isEmpty() && otherDevices.isEmpty() && recent.isEmpty()) {
                item("empty") {
                    EmptyState(Icons.Rounded.TaskAlt, "Nada por aqui ainda", "Quando você pedir algo ao seu PC, acompanha por aqui — mesmo com o app fechado.")
                }
            }
            if (live.isNotEmpty() || otherDevices.isNotEmpty()) {
                item("now-label") { SectionLabel("Agora", Modifier.padding(top = 6.dp)) }
                items(live, key = { "l" + it.messageId }) { t ->
                    LiveRow(t, titles[t.conversationId], online,
                        onOpen = { nav.navigate(Routes.chat(t.conversationId, msg = t.messageId)) },
                        onStop = { container.chat.stop(t.messageId) })
                }
                items(otherDevices, key = { "o" + it.job }) { j ->
                    TaskRow(
                        title = j.name, subtitle = "Em outro aparelho", trailing = phaseLabel(j.phase, j.position),
                        icon = { StatusDot(c.text3, pulsing = true, size = 8.dp) }, onClick = null,
                    )
                }
            }
            if (done.isNotEmpty()) {
                item("done-label") { SectionLabel("Concluídas recentemente", Modifier.padding(top = 10.dp)) }
                items(done.take(20), key = { "d" + it.id }) { m -> DoneRow(m, titles[m.conversationId]) { nav.navigate(Routes.chat(m.conversationId, msg = m.id)) } }
            }
            if (failed.isNotEmpty()) {
                item("fail-label") { SectionLabel("Com erro ou interrompidas", Modifier.padding(top = 10.dp)) }
                items(failed.take(20), key = { "f" + it.id }) { m -> DoneRow(m, titles[m.conversationId]) { nav.navigate(Routes.chat(m.conversationId, msg = m.id)) } }
            }
        }
    }
}

private fun phaseLabel(phase: String, position: Int?): String = when (phase) {
    "queued" -> if ((position ?: 0) > 0) "Na fila · ${position}º" else "Na fila"
    "loading" -> "Carregando modelo"
    "preparing" -> "Lendo"
    "thinking" -> "Pensando"
    "generating" -> "Gerando"
    else -> "Em andamento"
}

@Composable
private fun Counter(label: String, n: Int, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val c = Noc.colors
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(c.surface).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(n.toString(), style = MaterialTheme.typography.headlineLarge, color = if (n > 0) color else c.text3)
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.text3)
    }
}

@Composable
private fun LiveRow(t: LiveReply, title: String?, online: Boolean, onOpen: () -> Unit, onStop: () -> Unit) {
    val c = Noc.colors
    val elapsed by produceState(0L, t.startedAt) { while (true) { value = (System.currentTimeMillis() - t.startedAt) / 1000; delay(1000) } }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).pressable(onClick = onOpen).padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(c.accent, pulsing = true, size = 9.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.modelName ?: "Seu PC", style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            title?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(t.describe(online).removeSuffix("…") + " · %02d:%02d".format(elapsed / 60, elapsed % 60), style = MonoSmall, color = c.text3)
        }
        Box(Modifier.size(40.dp).clip(CircleShape).pressable(onClick = onStop), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Stop, "Parar", tint = c.text2, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun DoneRow(m: MessageEntity, title: String?, onClick: () -> Unit) {
    val c = Noc.colors
    val ok = m.status == MessageStatus.DONE
    val stats = remember(m.statsJson) { com.noc.app.chat.GenStats.decode(m.statsJson) }
    TaskRow(
        title = m.modelName ?: m.model?.substringBefore('@') ?: "Resposta",
        subtitle = title?.let { "“$it”" },
        trailing = when (m.status) {
            MessageStatus.DONE -> (stats?.wallMs ?: stats?.totalMs)?.let { "Concluído em " + Texts.seconds(it) } ?: "Concluído"
            MessageStatus.INTERRUPTED -> "Interrompido"
            else -> "Falhou"
        } + " · " + Texts.relative(m.createdAt),
        icon = {
            Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = if (ok) c.ok else c.err, modifier = Modifier.size(20.dp))
        },
        onClick = onClick,
    )
}

@Composable
private fun TaskRow(title: String, subtitle: String?, trailing: String, icon: @Composable () -> Unit, onClick: (() -> Unit)?) {
    val c = Noc.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(2.dp))
            Text(trailing, style = MonoSmall, color = c.text3)
        }
    }
}
