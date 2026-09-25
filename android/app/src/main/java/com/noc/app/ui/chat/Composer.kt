package com.noc.app.ui.chat

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noc.app.chat.Attachment
import com.noc.app.chat.Images
import com.noc.app.ui.Texts
import com.noc.app.ui.components.pressable
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.Noc
import com.noc.app.voice.VoiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun Composer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    attachments: List<Attachment>,
    generating: Boolean,
    presetName: String?,
    modelLabel: String?,
    hasOverrides: Boolean,
    sendWithEnter: Boolean,
    focusRequester: FocusRequester,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (Attachment) -> Unit,
    onTune: () -> Unit,
    onModel: () -> Unit,
    modifier: Modifier = Modifier,
    voice: VoiceState = VoiceState.Idle,
    voiceEnabled: Boolean = true,
    holdToTalk: Boolean = true,
    onMicTap: () -> Unit = {},
    onMicHoldStart: () -> Unit = {},
    onMicHoldEnd: (cancel: Boolean) -> Unit = {},
    onVoiceStop: () -> Unit = {},
    onVoiceCancel: () -> Unit = {},
    onVoiceRetry: () -> Unit = {},
    onVoiceDismiss: () -> Unit = {},
    onVoicePermission: () -> Unit = {},
    /** Aviso de capacidade (ex.: "Este modelo não vê imagens"), com ação opcional. */
    capabilityNote: Pair<String, Pair<String, () -> Unit>?>? = null,
    uploading: Boolean = false,
    /** Envio bloqueado (ex.: imagem anexada e o modelo escolhido não enxerga). */
    blockSend: Boolean = false,
) {
    val c = Noc.colors
    val haptic = LocalHapticFeedback.current
    val canSend = (value.text.isNotBlank() || attachments.isNotEmpty()) && !blockSend
    val recording = voice as? VoiceState.Recording
    val transcribing = voice is VoiceState.Transcribing
    val problem = voice as? VoiceState.Problem

    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
        AnimatedVisibility(problem != null) {
            problem?.let { VoiceProblemBanner(it, onVoiceRetry, onVoiceDismiss, onVoicePermission) }
        }
        AnimatedVisibility(capabilityNote != null) {
            capabilityNote?.let { (text, action) ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(c.warnSoft)
                        .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.VisibilityOff, null, tint = c.warn, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(text, style = MaterialTheme.typography.bodySmall, color = c.text, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
                    action?.let { (label, act) ->
                        Text(label, style = MaterialTheme.typography.labelMedium, color = c.accent,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable(onClick = act).padding(horizontal = 10.dp, vertical = 10.dp))
                    }
                }
            }
        }
        AnimatedVisibility(attachments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { a ->
                    if (a.kind == "image" && a.path != null) ImageChip(a, onRemove = { onRemoveAttachment(a) })
                    else AttachmentPill(a, onRemove = { onRemoveAttachment(a) })
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(c.surface)
                .padding(top = 6.dp),
        ) {
            if (recording != null) {
                RecordingBar(recording, onCancel = onVoiceCancel, onStop = onVoiceStop)
                return@Column
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                if (value.text.isEmpty()) {
                    Text(if (transcribing) "Transcrevendo…" else "Mensagem", style = MaterialTheme.typography.bodyLarge, color = c.text3,
                        modifier = if (transcribing) Modifier.pulsing() else Modifier)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = if (sendWithEnter) ImeAction.Send else ImeAction.Default,
                    ),
                    keyboardActions = KeyboardActions(onSend = { if (canSend && !generating) onSend() }),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 26.dp).focusRequester(focusRequester)
                        .semantics { contentDescription = "Mensagem" },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIcon(Icons.Rounded.Add, "Anexar", onClick = onAttach)
                // Modelo e estilo ficam à mão, sem poluir: um toque abre as opções.
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .pressable(onClick = onModel)
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        listOfNotNull(modelLabel, presetName).joinToString(" · ").ifEmpty { "Escolher modelo" },
                        style = MaterialTheme.typography.labelMedium,
                        color = c.text2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                RoundIcon(Icons.Rounded.Tune, "Ajustes da conversa", tint = if (hasOverrides) c.accent else c.text2, onClick = onTune)
                Spacer(Modifier.width(4.dp))
                val mode = when {
                    generating && !canSend -> 2
                    canSend || !voiceEnabled -> 0
                    else -> 1 // campo vazio: microfone
                }
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = { (scaleIn(tween(160)) + fadeIn(tween(160))) togetherWith (scaleOut(tween(120)) + fadeOut(tween(120))) },
                    label = "send",
                ) { m ->
                    when (m) {
                        1 -> MicButton(transcribing, holdToTalk, onTap = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onMicTap()
                        }, onHoldStart = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onMicHoldStart()
                        }, onHoldEnd = onMicHoldEnd)
                        else -> {
                            val stop = m == 2
                            val enabled = (stop || canSend) && !uploading
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(if (stop) c.text else if (enabled) c.accent else c.surface3)
                                    .pressable(enabled = enabled) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        if (stop) onStop() else onSend()
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (stop) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                                    if (stop) "Parar" else "Enviar",
                                    tint = if (stop) c.bg else if (enabled) c.onAccent else c.text3,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Microfone: toque = começa/termina; segurar = fala enquanto segura (solta para transcrever, arrasta para cancelar). */
@Composable
private fun MicButton(transcribing: Boolean, holdToTalk: Boolean, onTap: () -> Unit, onHoldStart: () -> Unit, onHoldEnd: (Boolean) -> Unit) {
    val c = Noc.colors
    val density = LocalDensity.current
    val cancelDistance = with(density) { 90.dp.toPx() }
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(c.accent)
            .pointerInput(holdToTalk, transcribing) {
                if (transcribing) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = down.position
                    // soltou antes do tempo de toque longo = toque simples
                    val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.changes.all { !it.pressed }) break
                        }
                        true
                    }
                    if (released == true) { onTap(); return@awaitEachGesture }
                    if (!holdToTalk) { onTap(); return@awaitEachGesture }
                    onHoldStart()
                    var cancel = false
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull() ?: break
                        val dx = start.x - ch.position.x
                        val dy = start.y - ch.position.y
                        cancel = dx > cancelDistance || dy > cancelDistance * 1.5f
                        if (!ch.pressed) break
                    }
                    onHoldEnd(cancel)
                }
            }
            .semantics { contentDescription = "Ditar mensagem" },
        contentAlignment = Alignment.Center,
    ) {
        if (transcribing) TypingDots() else Icon(Icons.Rounded.Mic, null, tint = c.onAccent, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun RecordingBar(r: VoiceState.Recording, onCancel: () -> Unit, onStop: () -> Unit) {
    val c = Noc.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(r.startedAt) { while (true) { delay(250); now = System.currentTimeMillis() } }
    val secs = ((now - r.startedAt) / 1000).coerceAtLeast(0)
    Column(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!r.hold) RoundIcon(Icons.Rounded.Close, "Cancelar ditado", onClick = onCancel) else Spacer(Modifier.width(12.dp))
            RecDot()
            Spacer(Modifier.width(8.dp))
            Text("%d:%02d".format(secs / 60, secs % 60), style = MonoSmall, color = c.text2)
            Spacer(Modifier.width(12.dp))
            Waveform(r.levels, Modifier.weight(1f).height(36.dp))
            Spacer(Modifier.width(10.dp))
            if (!r.hold) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(c.accent).pressable(onClick = onStop).semantics { contentDescription = "Terminar e transcrever" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(22.dp)) }
            } else Spacer(Modifier.width(12.dp))
        }
        Text(
            when {
                r.noSignal -> "Não estou ouvindo nada. O microfone pode estar bloqueado."
                r.hold -> "Solte para transcrever · arraste para o lado para cancelar"
                else -> "Ouvindo… toque em ✓ para transcrever"
            },
            style = MaterialTheme.typography.labelSmall, color = if (r.noSignal) c.warn else c.text3,
            modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun RecDot() {
    val t = rememberInfiniteTransition(label = "rec")
    val a by t.animateFloat(1f, 0.3f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(9.dp).alpha(a).clip(CircleShape).background(Noc.colors.err))
}

@Composable
private fun Waveform(levels: List<Float>, modifier: Modifier) {
    val c = Noc.colors
    Canvas(modifier) {
        val bars = 40
        val gap = 3.dp.toPx()
        val w = ((size.width - gap * (bars - 1)) / bars).coerceAtLeast(1f)
        val data = if (levels.size >= bars) levels.takeLast(bars) else List(bars - levels.size) { 0f } + levels
        data.forEachIndexed { i, lv ->
            val h = (size.height * (0.12f + 0.88f * lv)).coerceAtLeast(w)
            drawRoundRect(
                color = if (lv > 0.05f) c.text2 else c.line,
                topLeft = Offset(i * (w + gap), (size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
    }
}

@Composable
private fun VoiceProblemBanner(p: VoiceState.Problem, onRetry: () -> Unit, onDismiss: () -> Unit, onPermission: () -> Unit) {
    val c = Noc.colors
    LaunchedEffect(p) {
        // avisos leves somem sozinhos
        if (p.kind == VoiceState.Problem.Kind.EMPTY) { delay(3500); onDismiss() }
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp))
            .background(if (p.kind == VoiceState.Problem.Kind.EMPTY) c.surface else c.warnSoft)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.MicOff, null, tint = if (p.kind == VoiceState.Problem.Kind.EMPTY) c.text3 else c.warn, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(p.message, style = MaterialTheme.typography.bodySmall, color = c.text, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        val action: Pair<String, () -> Unit>? = when {
            p.kind == VoiceState.Problem.Kind.PERMISSION -> "Permitir" to onPermission
            p.retry -> "Tentar de novo" to onRetry
            else -> null
        }
        action?.let { (label, act) ->
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.accent,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable(onClick = act).padding(horizontal = 10.dp, vertical = 10.dp))
        }
        Box(Modifier.size(36.dp).clip(CircleShape).pressable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Fechar", tint = c.text3, modifier = Modifier.size(16.dp))
        }
    }
}

/** Miniatura da imagem anexada, com tamanho e botão para remover. */
@Composable
fun ImageChip(a: Attachment, onRemove: (() -> Unit)?, size: Int = 64) {
    val c = Noc.colors
    val bmp by produceState<Bitmap?>(null, a.path) { value = withContext(Dispatchers.IO) { a.path?.let { Images.thumbnail(it, size * 3) } } }
    Box(Modifier.size((size + 8).dp)) {
        Box(Modifier.padding(top = 8.dp, end = 8.dp).size(size.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2)) {
            bmp?.let { Image(it.asImageBitmap(), a.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Text(
                Texts.bytes(a.size), style = MonoSmall, color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(Alignment.BottomStart).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f), RoundedCornerShape(topEnd = 8.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        if (onRemove != null) {
            Box(
                Modifier.align(Alignment.TopEnd).size(24.dp).clip(CircleShape).background(c.text).pressable(onClick = onRemove)
                    .semantics { contentDescription = "Remover imagem" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, null, tint = c.bg, modifier = Modifier.size(14.dp)) }
        }
    }
}

@Composable
private fun Modifier.pulsing(): Modifier {
    val t = rememberInfiniteTransition(label = "p")
    val a by t.animateFloat(1f, 0.4f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    return this.alpha(a)
}

@Composable
private fun RoundIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    tint: androidx.compose.ui.graphics.Color = Noc.colors.text2,
    onClick: () -> Unit,
) {
    Box(Modifier.size(42.dp).clip(CircleShape).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(22.dp))
    }
}
