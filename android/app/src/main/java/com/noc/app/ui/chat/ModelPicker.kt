package com.noc.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noc.app.core.net.ModelInfo
import com.noc.app.core.net.ModelOp
import com.noc.app.core.net.Tier
import com.noc.app.core.net.TierInfo
import com.noc.app.data.db.PresetEntity
import com.noc.app.ui.Texts
import com.noc.app.ui.components.Chip
import com.noc.app.ui.components.GhostButton
import com.noc.app.ui.components.SectionLabel
import com.noc.app.ui.components.StatusDot
import com.noc.app.ui.components.pressable
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.Noc

/** Linha curta de capacidades: "visão · raciocínio · Q4_K_M · 6,6 GB". */
fun ModelInfo.capsLine(): String = listOfNotNull(
    "visão".takeIf { vision },
    "raciocínio".takeIf { supportsReasoning },
    quant,
    sizeBytes.takeIf { it > 0 }?.let { Texts.bytes(it) },
).joinToString(" · ")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelPickerSheet(
    tiers: Map<String, TierInfo>,
    models: List<ModelInfo>,
    choice: String?,
    op: ModelOp?,
    online: Boolean,
    presets: List<PresetEntity>,
    selectedPreset: String?,
    onChoose: (String) -> Unit,
    onPreset: (PresetEntity) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Noc.colors
    val chosenKey = Tier.of(choice)?.let { tiers[it]?.model } ?: choice
    NocSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Modelo", style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
            if (!online && models.isEmpty()) {
                Text(
                    "Conecte-se ao PC para ver os modelos. Você pode escrever agora: a mensagem vai quando ele voltar.",
                    style = MaterialTheme.typography.bodyMedium, color = c.text2,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                )
            }
            if (tiers.isNotEmpty() || models.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tier.all.forEach { t ->
                        val info = tiers[t]
                        val m = models.firstOrNull { it.key == info?.model }
                        TierCard(
                            tier = t, info = info, model = m,
                            selected = choice == Tier.PREFIX + t,
                            loading = op?.kind == "loading" && op.model == info?.model,
                            onClick = { if (info != null) onChoose(Tier.PREFIX + t) },
                        )
                    }
                }
            }
            val others = models.filter { it.isLlm && !it.hidden && tiers.values.none { t -> t.model == it.key } }
                .sortedWith(compareByDescending<ModelInfo> { it.favorite }.thenByDescending { it.fitsGpu != false }.thenBy { it.name })
            if (others.isNotEmpty()) {
                var open by remember { mutableStateOf(Tier.of(choice) == null && choice != null) }
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp).pressable { open = !open }.padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Outros modelos", style = MaterialTheme.typography.titleSmall, color = c.text2, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.ExpandMore, null, tint = c.text3, modifier = Modifier.rotate(if (open) 180f else 0f))
                }
                AnimatedVisibility(open) {
                    Column {
                        others.forEach { m ->
                            OtherModelRow(m, selected = chosenKey == m.key && Tier.of(choice) == null, loading = op?.kind == "loading" && op.model == m.key) { onChoose(m.key) }
                        }
                    }
                }
            }
            GhostButton("Gerenciar modelos", Modifier.padding(horizontal = 12.dp)) { onManage() }
            if (presets.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                SectionLabel("Estilo da conversa", Modifier.padding(horizontal = 22.dp))
                FlowRow(
                    Modifier.padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    presets.forEach { p -> Chip(p.name, selected = p.id == selectedPreset, icon = presetIcon(p.icon)) { onPreset(p) } }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun TierCard(tier: String, info: TierInfo?, model: ModelInfo?, selected: Boolean, loading: Boolean, onClick: () -> Unit) {
    val c = Noc.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) c.accentSoft else c.surface)
            .border(1.5.dp, if (selected) c.accent else c.surface, RoundedCornerShape(20.dp))
            .pressable(enabled = info != null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(if (selected) c.accent else c.surface2), contentAlignment = Alignment.Center) {
            Text(Tier.symbol(tier), style = MaterialTheme.typography.titleLarge, color = if (selected) c.onAccent else c.text)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Tier.label(tier), style = MaterialTheme.typography.titleMedium, color = c.text)
                if (model?.loaded == true || loading) {
                    Spacer(Modifier.width(8.dp))
                    StatusDot(if (loading) c.warn else c.ok, pulsing = loading, size = 7.dp)
                }
            }
            Text(
                info?.name ?: "Nenhum modelo neste perfil",
                style = MaterialTheme.typography.bodyMedium, color = if (info != null) c.text2 else c.text3,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (loading) "Carregando no PC…" else Tier.blurb(tier),
                style = MaterialTheme.typography.bodySmall, color = c.text3,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (model?.vision == true) Icon(Icons.Rounded.Visibility, "Entende imagens", tint = c.text3, modifier = Modifier.size(18.dp))
            if (model?.supportsReasoning == true) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.Psychology, "Raciocina", tint = c.text3, modifier = Modifier.size(18.dp))
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Rounded.Check, "Selecionado", tint = c.accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun OtherModelRow(m: ModelInfo, selected: Boolean, loading: Boolean, onClick: () -> Unit) {
    val c = Noc.colors
    Row(
        Modifier.fillMaxWidth().pressable(onClick = onClick).padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (m.favorite) {
                    Icon(Icons.Rounded.Star, "Favorito", tint = c.accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(m.name, style = MaterialTheme.typography.titleSmall, color = if (m.fitsGpu == false) c.text3 else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (m.loaded || loading) {
                    Spacer(Modifier.width(8.dp))
                    StatusDot(if (loading) c.warn else c.ok, pulsing = loading, size = 7.dp)
                }
            }
            Text(
                if (m.fitsGpu == false) "Não cabe na memória da GPU · ${m.capsLine()}" else m.capsLine(),
                style = MonoSmall, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) Icon(Icons.Rounded.Check, "Selecionado", tint = c.accent, modifier = Modifier.size(20.dp))
    }
}
