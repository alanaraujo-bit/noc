package com.noc.app.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.noc.app.chat.Attachment
import com.noc.app.ui.components.pressable
import com.noc.app.ui.theme.Noc

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
) {
    val c = Noc.colors
    val haptic = LocalHapticFeedback.current
    val canSend = value.text.isNotBlank() || attachments.isNotEmpty()

    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
        AnimatedVisibility(attachments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { a -> AttachmentPill(a, onRemove = { onRemoveAttachment(a) }) }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(c.surface)
                .padding(top = 6.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                if (value.text.isEmpty()) {
                    Text("Mensagem", style = MaterialTheme.typography.bodyLarge, color = c.text3)
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
                // Modelo e preset ficam à mão, sem poluir: um toque abre as opções.
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
                AnimatedContent(
                    targetState = generating && !canSend,
                    transitionSpec = { (scaleIn(tween(160)) + fadeIn(tween(160))) togetherWith (scaleOut(tween(120)) + fadeOut(tween(120))) },
                    label = "send",
                ) { stop ->
                    val enabled = stop || canSend
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

