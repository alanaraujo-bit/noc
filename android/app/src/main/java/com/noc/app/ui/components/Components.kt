package com.noc.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noc.app.ui.theme.Noc

/** Leve encolhimento ao pressionar: resposta tátil visual sem ripple chamativo. */
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    haptic: Boolean = false,
    role: Role = Role.Button,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val h = LocalHapticFeedback.current
    val base = this.scale(if (pressed && enabled) 0.97f else 1f)
    return if (onLongClick != null) {
        base.then(
            Modifier.combinedClickableCompat(source, enabled, role, onLongClick = {
                h.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick()
            }) { if (haptic) h.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
        )
    } else {
        base.clickable(source, indication = null, enabled = enabled, role = role) {
            if (haptic) h.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(
    source: MutableInteractionSource,
    enabled: Boolean,
    role: Role,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) = this.then(
    Modifier.combinedClickable(
        interactionSource = source, indication = null, enabled = enabled, role = role,
        onLongClick = onLongClick, onClick = onClick,
    ),
)

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 54.dp,
    onClick: () -> Unit,
) {
    val c = Noc.colors
    Row(
        modifier
            .heightIn(min = height)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (enabled) c.accent else c.surface3)
            .pressable(enabled, haptic = true, onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) c.onAccent else c.text3, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) c.onAccent else c.text3)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val c = Noc.colors
    Row(
        modifier
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(c.surface2)
            .pressable(enabled, onClick = onClick)
            .padding(horizontal = 18.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint ?: c.text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = tint ?: c.text)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, color: Color? = null, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color ?: Noc.colors.accent,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).pressable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
    )
}

@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    background: Color = Color.Transparent,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .pressable(enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint ?: Noc.colors.text, modifier = Modifier.size(iconSize))
    }
}

/** Ponto de estado; pulsa suavemente quando algo está acontecendo. */
@Composable
fun StatusDot(color: Color, pulsing: Boolean = false, size: Dp = 8.dp) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(
        initialValue = 1f, targetValue = if (pulsing) 0.35f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a",
    )
    Box(Modifier.size(size).alpha(if (pulsing) a else 1f).clip(CircleShape).background(color))
}

@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar", onClick = onBack)
        else Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Noc.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Noc.colors.text3, maxLines = 1)
        }
        actions()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Noc.colors.text3,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

/** Grupo de linhas com fundo único (sem cartões dentro de cartões). */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Noc.colors.surface)) { content() }
}

@Composable
fun GroupDivider() {
    Box(Modifier.padding(start = 56.dp).fillMaxWidth().height(1.dp).background(Noc.colors.line))
}

@Composable
fun RowItem(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    trailing: @Composable (() -> Unit)? = { Icon(Icons.Rounded.ChevronRight, null, tint = Noc.colors.text3) },
    titleColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = Noc.colors
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .heightIn(min = 58.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = iconTint ?: c.text2, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor ?: c.text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.text3)
        }
        trailing?.invoke()
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    RowItem(
        title = title, subtitle = subtitle, icon = icon,
        trailing = {
            Switch(
                checked = checked, onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Noc.colors.accent, checkedThumbColor = Noc.colors.onAccent,
                    uncheckedTrackColor = Noc.colors.surface3, uncheckedBorderColor = Color.Transparent,
                    uncheckedThumbColor = Noc.colors.text3,
                ),
            )
        },
        onClick = { onChange(!checked) },
    )
}

@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val c = Noc.colors
    Row(
        modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) c.text else c.surface)
            .border(1.dp, if (selected) c.text else c.line, RoundedCornerShape(percent = 50))
            .pressable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected) c.bg else c.text2, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) c.bg else c.text)
    }
}

@Composable
fun Badge(text: String, color: Color, background: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Medium,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(background).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val c = Noc.colors
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.text2, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = c.text)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.text2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** Bloco de carregamento com brilho discreto. */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(10.dp)) {
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "a")
    Box(modifier.clip(shape).background(Noc.colors.surface3.copy(alpha = a)))
}

val ScreenPadding = PaddingValues(horizontal = 20.dp)

/** Indicador global discreto: "1 tarefa em andamento". Toque abre a Atividade. */
@Composable
fun TaskPill(count: Int, compact: Boolean = false, onClick: () -> Unit) {
    val c = Noc.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(c.accentSoft)
            .pressable(onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(c.accent, pulsing = true, size = 7.dp)
        Spacer(Modifier.width(7.dp))
        Text(
            if (compact) "$count" else if (count == 1) "1 tarefa em andamento" else "$count tarefas em andamento",
            style = MaterialTheme.typography.labelMedium, color = c.text,
        )
    }
}
