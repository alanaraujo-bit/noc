package com.noc.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noc.app.chat.Attachment
import com.noc.app.chat.GenStats
import com.noc.app.chat.LiveReply
import com.noc.app.chat.MessageStatus
import com.noc.app.data.db.MessageEntity
import com.noc.app.ui.Texts
import com.noc.app.ui.components.pressable
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.Noc
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
fun UserMessage(
    ui: MessageUi,
    onLongPress: () -> Unit,
    onBranch: (Int) -> Unit,
) {
    val c = Noc.colors
    val m = ui.entity
    val atts = remember(m.attachmentsJson) { Attachment.decodeList(m.attachmentsJson) }
    var showTime by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End) {
        val images = atts.filter { it.kind == "image" && it.path != null }
        if (images.isNotEmpty()) {
            Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                images.take(4).forEach { a -> ImageChip(a, onRemove = null, size = if (images.size == 1) 150 else 88) }
            }
        }
        atts.filter { it.kind != "image" || it.path == null }.forEach { a -> AttachmentPill(a, Modifier.padding(bottom = 6.dp)) }
        if (m.content.isNotBlank()) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 8.dp))
                    .background(c.surface2)
                    .pressable(onLongClick = onLongPress) { showTime = !showTime }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            ) {
                Text(m.content, style = MaterialTheme.typography.bodyLarge, color = c.text)
            }
        }
        AnimatedVisibility(showTime || ui.branchCount > 1) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ui.branchCount > 1) BranchSwitcher(ui, onBranch)
                if (showTime) Text(Texts.time(m.createdAt), style = MonoSmall, color = c.text3, modifier = Modifier.padding(horizontal = 6.dp))
            }
        }
    }
}

@Composable
fun AttachmentPill(a: Attachment, modifier: Modifier = Modifier, onRemove: (() -> Unit)? = null) {
    val c = Noc.colors
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface)
            .padding(start = 10.dp, end = if (onRemove != null) 2.dp else 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (a.kind == "image") Icons.Rounded.Image else Icons.AutoMirrored.Rounded.InsertDriveFile, null, tint = c.text2, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.widthIn(max = 200.dp)) {
            Text(a.name, style = MaterialTheme.typography.labelMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Texts.bytes(a.size), style = MonoSmall, color = c.text3)
        }
        if (onRemove != null) {
            Box(Modifier.size(32.dp).clip(CircleShape).pressable(onClick = onRemove), contentAlignment = Alignment.Center) {
                Text("×", style = MaterialTheme.typography.titleMedium, color = c.text3)
            }
        }
    }
}

@Composable
fun BranchSwitcher(ui: MessageUi, onBranch: (Int) -> Unit) {
    val c = Noc.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(CircleShape).pressable(enabled = ui.branchIndex > 0) { onBranch(-1) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ChevronLeft, "Versão anterior", tint = if (ui.branchIndex > 0) c.text2 else c.line, modifier = Modifier.size(18.dp))
        }
        Text("${ui.branchIndex + 1}/${ui.branchCount}", style = MonoSmall, color = c.text2)
        Box(Modifier.size(30.dp).clip(CircleShape).pressable(enabled = ui.branchIndex < ui.branchCount - 1) { onBranch(1) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ChevronRight, "Próxima versão", tint = if (ui.branchIndex < ui.branchCount - 1) c.text2 else c.line, modifier = Modifier.size(18.dp))
        }
    }
}

/** Ações do rodapé de uma resposta. */
data class ReplyActions(
    val onCopy: () -> Unit,
    val onRegenerate: () -> Unit,
    val onMore: () -> Unit,
    val onStats: () -> Unit,
    val onContinue: () -> Unit,
    val onRetry: () -> Unit,
    val onBranch: (Int) -> Unit,
)

@Composable
fun AssistantMessage(
    ui: MessageUi,
    live: StateFlow<LiveReply>?,
    isLast: Boolean,
    online: Boolean,
    showStats: Boolean,
    showReasoning: Boolean,
    modelName: (String?) -> String,
    actions: ReplyActions,
    onLongPress: () -> Unit,
    /** Mostrar (uma vez) "pode sair daqui, avisamos quando terminar". */
    leaveHint: Boolean = false,
    onLeaveHintShown: () -> Unit = {},
) {
    val m = ui.entity
    // o banco é a verdade quando a resposta já terminou (o estado ao vivo pode ainda não ter sido solto)
    val finalStatus = m.status != MessageStatus.WAITING && m.status != MessageStatus.STREAMING
    val liveState = live?.collectAsState()?.value?.takeUnless { finalStatus }
    val content = liveState?.content ?: m.content
    val reasoning = liveState?.reasoning ?: m.reasoning.orEmpty()
    val streaming = liveState != null
    val c = Noc.colors

    Column(
        Modifier
            .fillMaxWidth()
            .pressable(onLongClick = onLongPress, onClick = {})
            .animateContentSize(spring(stiffness = 900f)),
    ) {
        if (reasoning.isNotBlank() && showReasoning) {
            ReasoningBlock(
                text = reasoning,
                thinking = streaming && content.isEmpty(),
                durationMs = liveState?.reasoningMs ?: m.reasoningMs,
                startedAt = liveState?.reasoningStartedAt,
            )
            Spacer(Modifier.padding(top = 10.dp))
        } else if (reasoning.isNotBlank() && streaming && content.isEmpty()) {
            PhaseLine("Pensando…", pulsing = true)
        }

        when {
            content.isNotEmpty() -> MarkdownText(content, streaming = streaming)
            streaming && reasoning.isEmpty() -> PendingIndicator(liveState!!, online, liveState.modelName ?: m.modelName ?: modelName(m.model))
        }

        if (streaming && content.isNotEmpty()) {
            Spacer(Modifier.padding(top = 8.dp))
            if (liveState!!.detached) {
                Text(liveState.describe(online), style = MaterialTheme.typography.bodySmall, color = c.text3)
            } else TypingDots()
        }
        if (streaming && leaveHint) LeaveHint(liveState!!, onLeaveHintShown)

        if (!streaming) {
            StatusNote(m, onContinue = actions.onContinue, onRetry = actions.onRetry)
            if (m.status != MessageStatus.WAITING) {
                Footer(ui, isLast, showStats, actions)
            }
        } else if (liveState!!.tps != null && showStats) {
            Text(
                "%.0f tok/s".format(liveState.tps),
                style = MonoSmall, color = c.text3, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun PhaseLine(text: String, pulsing: Boolean) {
    val c = Noc.colors
    val t = rememberInfiniteTransition(label = "phase")
    val a by t.animateFloat(1f, if (pulsing) 0.4f else 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Text(text, style = MaterialTheme.typography.bodyMedium, color = c.text2, modifier = Modifier.alpha(a).padding(vertical = 4.dp))
}

@Composable
private fun PendingIndicator(s: LiveReply, online: Boolean, model: String) {
    val c = Noc.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.phase) {
        while (true) { delay(1000); now = System.currentTimeMillis() }
    }
    val inPhase = ((now - s.phaseSince) / 1000).coerceAtLeast(0)
    val base = s.copy(modelName = s.modelName ?: model).describe(online)
    val text = when {
        s.detached -> base
        s.phase == LiveReply.Phase.LOADING -> base + " $inPhase s" + (s.expectedSeconds?.let { " de ~${it.toInt()} s" } ?: "")
        s.phase == LiveReply.Phase.PREPARING && inPhase >= 3 -> "$base $inPhase s"
        s.phase == LiveReply.Phase.SENDING && !online -> "Sem conexão agora — envio assim que o PC voltar."
        else -> base
    }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypingDots()
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (!online && (s.phase == LiveReply.Phase.SENDING || s.detached)) c.warn else c.text2)
        }
        if (s.phase == LiveReply.Phase.UPLOADING && s.upload != null) {
            Spacer(Modifier.padding(top = 8.dp))
            Box(Modifier.fillMaxWidth(0.6f).heightIn(min = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.surface2)) {
                Box(Modifier.fillMaxWidth(s.upload).heightIn(min = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
            }
            s.uploadBytes?.let { Text(Texts.bytes(it), style = MonoSmall, color = c.text3, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

/** "Pode sair daqui": aparece uma vez numa tarefa que está demorando, e só nas primeiras vezes. */
@Composable
private fun LeaveHint(s: LiveReply, onShown: () -> Unit) {
    val c = Noc.colors
    var show by remember(s.messageId) { mutableStateOf(false) }
    LaunchedEffect(s.messageId) {
        delay(8_000)
        show = true
        onShown()
    }
    AnimatedVisibility(show, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Text(
            "Pode sair daqui ou bloquear a tela — a resposta continua no seu PC e avisamos quando terminar.",
            style = MaterialTheme.typography.bodySmall, color = c.text3,
            modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).padding(horizontal = 12.dp, vertical = 9.dp),
        )
    }
}

@Composable
fun TypingDots() {
    val c = Noc.colors
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by t.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(tween(520, delayMillis = i * 140), RepeatMode.Reverse), label = "d$i",
            )
            Box(Modifier.size(6.dp).alpha(a).clip(CircleShape).background(c.accent))
        }
    }
}

@Composable
private fun ReasoningBlock(text: String, thinking: Boolean, durationMs: Long?, startedAt: Long?) {
    val c = Noc.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(thinking) {
        while (thinking) { delay(500); now = System.currentTimeMillis() }
    }
    val secs = when {
        durationMs != null -> Texts.seconds(durationMs)
        startedAt != null -> Texts.seconds(now - startedAt)
        else -> null
    }
    val label = if (thinking) "Pensando" + (secs?.let { " · $it" } ?: "…") else "Pensou" + (secs?.let { " por $it" } ?: "")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)) {
        Row(
            Modifier.fillMaxWidth().pressable { expanded = !expanded }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (thinking) {
                TypingDots()
                Spacer(Modifier.width(10.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.text2, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ExpandMore, if (expanded) "Recolher" else "Expandir", tint = c.text3, modifier = Modifier.size(18.dp).rotate(if (expanded) 180f else 0f))
        }
        // Enquanto pensa, mostra só o fim do raciocínio; expandido, mostra tudo.
        AnimatedVisibility(expanded || thinking, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            val shown = if (expanded) text.trim() else text.trim().takeLast(420)
            Text(
                (if (!expanded && text.length > 420) "…" else "") + shown,
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Normal),
                color = c.text3,
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun StatusNote(m: MessageEntity, onContinue: () -> Unit, onRetry: () -> Unit) {
    val c = Noc.colors
    val (text, action, isError) = when {
        m.status == MessageStatus.ERROR -> Triple(Texts.replyError(m.error), "Tentar de novo" to onRetry, true)
        m.status == MessageStatus.CANCELLED -> Triple("Você interrompeu esta resposta.", (if (m.content.isNotBlank()) "Continuar" to onContinue else "Gerar de novo" to onRetry), false)
        m.status == MessageStatus.INTERRUPTED -> Triple(if (m.error?.startsWith("pc_restarted") == true || m.error?.startsWith("job_unknown") == true) "Seu PC foi reiniciado antes da conclusão." else "A resposta foi interrompida antes do fim.", (if (m.content.isNotBlank()) "Continuar" to onContinue else "Gerar de novo" to onRetry), false)
        m.finishReason == "length" -> Triple("A resposta atingiu o limite de tokens.", "Continuar" to onContinue, false)
        else -> return
    }
    Row(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isError) c.errSoft else c.surface)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isError) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = c.err, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (isError) c.err else c.text2, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
        Text(
            action.first,
            style = MaterialTheme.typography.labelMedium,
            color = c.accent,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable(onClick = action.second).padding(horizontal = 10.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun Footer(ui: MessageUi, isLast: Boolean, showStats: Boolean, a: ReplyActions) {
    val c = Noc.colors
    val m = ui.entity
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val stats = remember(m.statsJson) { GenStats.decode(m.statsJson) }
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = 40.dp).alpha(if (isLast) 1f else 0.85f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FooterIcon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, "Copiar resposta", if (copied) c.ok else c.text3) {
            clipboard.setText(AnnotatedString(m.content))
            copied = true
            scope.launch { delay(1500); copied = false }
            a.onCopy()
        }
        FooterIcon(Icons.Rounded.Refresh, "Gerar outra resposta", c.text3, a.onRegenerate)
        if (ui.branchCount > 1) BranchSwitcher(ui, a.onBranch)
        FooterIcon(Icons.Rounded.MoreHoriz, "Mais ações", c.text3, a.onMore)
        val name = m.modelName ?: stats?.modelName
        if (showStats && stats != null) {
            val wall = stats.wallMs ?: stats.totalMs
            val parts = listOfNotNull(
                stats.tps?.let { "%.0f tok/s".format(it) },
                stats.ttftMs?.let { "${Texts.seconds(it)} p/ 1º token" }?.takeIf { stats.tps == null },
                wall?.takeIf { it >= 20_000 }?.let { "em " + Texts.seconds(it) },
            )
            val label = (listOfNotNull(name) + parts).joinToString(" · ")
            if (label.isEmpty()) Spacer(Modifier.weight(1f))
            if (label.isNotEmpty()) {
                Text(
                    label,
                    style = MonoSmall, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).pressable(onClick = a.onStats).padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        } else if (name != null) {
            Text(name, style = MonoSmall, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).pressable(onClick = a.onStats).padding(horizontal = 8.dp, vertical = 8.dp))
        } else Spacer(Modifier.weight(1f))
        Text(Texts.time(m.createdAt), style = MonoSmall, color = c.text3, modifier = Modifier.padding(end = 4.dp))
    }
}

@Composable
private fun FooterIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(CircleShape).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(17.dp))
    }
}
