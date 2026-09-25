package com.noc.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noc.app.chat.GenStats
import com.noc.app.chat.GenerationParams
import com.noc.app.core.net.ModelInfo
import com.noc.app.core.net.ModelOp
import com.noc.app.data.db.MessageEntity
import com.noc.app.data.db.PresetEntity
import com.noc.app.ui.Texts
import com.noc.app.ui.components.Badge
import com.noc.app.ui.components.GhostButton
import com.noc.app.ui.components.PrimaryButton
import com.noc.app.ui.components.SecondaryButton
import com.noc.app.ui.components.SectionLabel
import com.noc.app.ui.components.StatusDot
import com.noc.app.ui.components.pressable
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.Noc
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NocSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = Noc.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.statusBarsPadding(),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.bg,
        scrimColor = c.scrim,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.surface3))
        },
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) { content() }
    }
}

@Composable
fun SheetAction(icon: ImageVector, text: String, subtitle: String? = null, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val c = Noc.colors
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(enabled = enabled, onClick = onClick)
            .heightIn(min = 54.dp)
            .padding(horizontal = 22.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = when {
            !enabled -> c.text3.copy(alpha = 0.5f)
            danger -> c.err
            else -> c.text
        }
        Icon(icon, null, tint = if (danger) c.err else if (enabled) c.text2 else tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.titleMedium, color = tint)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.text3)
        }
    }
}

fun presetIcon(key: String): ImageVector = when (key) {
    "balance" -> Icons.Rounded.Balance
    "code" -> Icons.Rounded.Code
    "search" -> Icons.Rounded.Search
    "spark" -> Icons.Rounded.AutoAwesome
    "bolt" -> Icons.Rounded.Bolt
    "layers" -> Icons.Rounded.Layers
    else -> Icons.Rounded.Tune
}

// ------------------------------------------------------------------ modelo

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelSheet(
    models: List<ModelInfo>,
    selected: String?,
    op: ModelOp?,
    recent: List<String>,
    presets: List<PresetEntity>,
    selectedPreset: String?,
    online: Boolean,
    onSelect: (ModelInfo) -> Unit,
    onPreset: (PresetEntity) -> Unit,
    onLoad: (ModelInfo) -> Unit,
    onUnload: (ModelInfo) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Noc.colors
    NocSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Modelo", style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
            val llms = models.filter { it.isLlm }.sortedWith(compareByDescending<ModelInfo> { it.loaded }.thenBy { recent.indexOf(it.key).let { i -> if (i < 0) 99 else i } })
            if (!online && llms.isEmpty()) {
                Text(
                    "Conecte-se ao PC para ver os modelos disponíveis.",
                    style = MaterialTheme.typography.bodyMedium, color = c.text2,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
            llms.forEach { m ->
                ModelRow(m, selected == m.key, op, onClick = { onSelect(m) }, onLoad = { onLoad(m) }, onUnload = { onUnload(m) })
            }
            GhostButton("Gerenciar modelos", Modifier.padding(horizontal = 12.dp)) { onManage() }

            Spacer(Modifier.height(18.dp))
            SectionLabel("Perfil de uso", Modifier.padding(horizontal = 22.dp))
            FlowRow(
                Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEach { p ->
                    com.noc.app.ui.components.Chip(p.name, selected = p.id == selectedPreset, icon = presetIcon(p.icon)) { onPreset(p) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun ModelRow(m: ModelInfo, selected: Boolean, op: ModelOp?, onClick: () -> Unit, onLoad: () -> Unit, onUnload: () -> Unit) {
    val c = Noc.colors
    val loading = op?.kind == "loading" && op.model == m.key
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) c.surface else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (selected) c.line else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(18.dp))
            .pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.name, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (m.loaded) {
                    Spacer(Modifier.width(8.dp))
                    StatusDot(c.ok)
                }
            }
            Text(
                listOfNotNull(m.params, m.quant, Texts.bytes(m.sizeBytes).takeIf { m.sizeBytes > 0 }, "até ${Texts.tokens(m.maxContext)} ctx".takeIf { m.maxContext > 0 }).joinToString(" · "),
                style = MonoSmall, color = c.text3,
            )
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    loading -> Badge("Carregando…", c.warn, c.warnSoft)
                    m.loaded -> Badge("Carregado · ${Texts.tokens(m.context ?: 0)}", c.ok, c.okSoft)
                }
                if (m.vision) Badge("Visão", c.text2, c.surface2)
                if (m.reasoning.isNotEmpty()) Badge("Raciocínio", c.text2, c.surface2)
                if (m.toolUse) Badge("Ferramentas", c.text2, c.surface2)
                if (m.fitsGpu == false) Badge("Maior que a GPU", c.warn, c.warnSoft)
            }
        }
        if (!loading) {
            Text(
                if (m.loaded) "Descarregar" else "Carregar",
                style = MaterialTheme.typography.labelMedium,
                color = if (m.loaded) c.text2 else c.accent,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable { if (m.loaded) onUnload() else onLoad() }.padding(horizontal = 10.dp, vertical = 10.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ ajustes (playground)

@Composable
fun TuneSheet(
    base: GenerationParams,
    overrides: GenerationParams?,
    systemPrompt: String?,
    presetSystem: String?,
    model: ModelInfo?,
    onApply: (GenerationParams?, String?) -> Unit,
    onPickPrompt: () -> Unit,
    onSavePreset: (String, GenerationParams, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Noc.colors
    var p by remember { mutableStateOf(overrides ?: GenerationParams()) }
    var system by remember { mutableStateOf(systemPrompt ?: "") }
    var saveName by remember { mutableStateOf<String?>(null) }
    val eff = base.overlay(p)

    NocSheet({ onApply(p.takeUnless { it.isDefault }, system.takeIf { it.isNotBlank() }); onDismiss() }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ajustes", style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.weight(1f))
                GhostButton("Restaurar") { p = GenerationParams(); system = "" }
            }
            Text(
                "Valem só para esta conversa. Em branco, usa o perfil escolhido e o padrão do modelo.",
                style = MaterialTheme.typography.bodySmall, color = c.text3,
            )
            Spacer(Modifier.height(18.dp))

            SectionLabel("Instruções (prompt de sistema)")
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(14.dp)) {
                if (system.isEmpty()) Text(presetSystem?.let { "Do perfil: " + it.take(160) } ?: "Ex.: Responda como um especialista em finanças, de forma direta.", style = MaterialTheme.typography.bodyMedium, color = c.text3, maxLines = 4, overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    system, { system = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 220.dp),
                )
            }
            Row { GhostButton("Escolher da biblioteca") { onPickPrompt() } }

            if (model?.supportsReasoningToggle == true) {
                Spacer(Modifier.height(10.dp))
                SectionLabel("Raciocínio")
                Segmented(
                    listOf(null to "Padrão", "on" to "Ligado", "off" to "Desligado"),
                    p.reasoning,
                ) { p = p.copy(reasoning = it) }
                Text("Desligado responde mais rápido; ligado pensa antes de responder.", style = MaterialTheme.typography.bodySmall, color = c.text3, modifier = Modifier.padding(top = 6.dp))
            }

            Spacer(Modifier.height(18.dp))
            SectionLabel("Amostragem")
            ParamSlider("Temperatura", "Mais alto = mais criativo e variado", p.temperature, eff.temperature ?: 0.8, 0.0..2.0, 0.05) { p = p.copy(temperature = it) }
            ParamSlider("Top P", "Considera só os tokens mais prováveis até essa soma", p.topP, eff.topP ?: 0.95, 0.0..1.0, 0.01) { p = p.copy(topP = it) }
            ParamSlider("Top K", "Limita a escolha aos K tokens mais prováveis", p.topK?.toDouble(), (eff.topK ?: 40).toDouble(), 0.0..200.0, 1.0, integer = true) { p = p.copy(topK = it?.roundToInt()) }
            ParamSlider("Min P", "Descarta tokens muito improváveis", p.minP, eff.minP ?: 0.05, 0.0..0.5, 0.01) { p = p.copy(minP = it) }

            Spacer(Modifier.height(10.dp))
            SectionLabel("Repetição")
            ParamSlider("Penalidade de repetição", "Evita repetir trechos", p.repeatPenalty, eff.repeatPenalty ?: 1.1, 0.8..2.0, 0.01) { p = p.copy(repeatPenalty = it) }
            ParamSlider("Presence penalty", "Incentiva assuntos novos", p.presencePenalty, eff.presencePenalty ?: 0.0, -2.0..2.0, 0.05) { p = p.copy(presencePenalty = it) }
            ParamSlider("Frequency penalty", "Reduz palavras muito repetidas", p.frequencyPenalty, eff.frequencyPenalty ?: 0.0, -2.0..2.0, 0.05) { p = p.copy(frequencyPenalty = it) }

            Spacer(Modifier.height(10.dp))
            SectionLabel("Tamanho")
            NumberField("Máximo de tokens na resposta", p.maxTokens, eff.maxTokens, "sem limite") { p = p.copy(maxTokens = it?.takeIf { v -> v > 0 }) }
            ContextPicker(p.contextLength, model) { p = p.copy(contextLength = it) }

            Spacer(Modifier.height(10.dp))
            SectionLabel("Reprodutibilidade")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { NumberField("Seed", p.seed, null, "aleatória") { p = p.copy(seed = it) } }
                Box(Modifier.clip(RoundedCornerShape(12.dp)).pressable { p = p.copy(seed = (0..999_999).random()) }.padding(12.dp)) {
                    Icon(Icons.Rounded.Casino, "Sortear seed", tint = c.text2)
                }
            }
            StopField(p.stop) { p = p.copy(stop = it) }

            Spacer(Modifier.height(20.dp))
            if (saveName == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Salvar como perfil", Modifier.weight(1f)) { saveName = "" }
                    PrimaryButton("Aplicar", Modifier.weight(1f), height = 46.dp) {
                        onApply(p.takeUnless { it.isDefault }, system.takeIf { it.isNotBlank() })
                        onDismiss()
                    }
                }
            } else {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(14.dp)) {
                    if (saveName!!.isEmpty()) Text("Nome do perfil", style = MaterialTheme.typography.bodyLarge, color = c.text3)
                    BasicTextField(saveName!!, { saveName = it }, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text), cursorBrush = SolidColor(c.accent), singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Salvar perfil", Modifier.fillMaxWidth(), enabled = saveName!!.isNotBlank(), height = 46.dp) {
                    onSavePreset(saveName!!, eff, system.takeIf { it.isNotBlank() } ?: presetSystem)
                    onDismiss()
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun Segmented(options: List<Pair<String?, String>>, selected: String?, onSelect: (String?) -> Unit) {
    val c = Noc.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(3.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (on) c.surface else androidx.compose.ui.graphics.Color.Transparent)
                    .pressable { onSelect(value) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) c.text else c.text2)
            }
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    help: String,
    value: Double?,
    fallback: Double,
    range: ClosedFloatingPointRange<Double>,
    step: Double,
    integer: Boolean = false,
    onChange: (Double?) -> Unit,
) {
    val c = Noc.colors
    val shown = value ?: fallback
    var local by remember(value) { mutableFloatStateOf(shown.toFloat()) }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = c.text)
                Text(help, style = MaterialTheme.typography.bodySmall, color = c.text3)
            }
            val txt = if (value == null) "auto" else if (integer) local.roundToInt().toString() else "%.2f".format(local)
            Text(
                txt, style = MonoSmall, color = if (value == null) c.text3 else c.accent,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).pressable { onChange(if (value == null) shown else null) }.padding(8.dp),
            )
        }
        Slider(
            value = local,
            onValueChange = { local = ((it / step).roundToInt() * step).toFloat() },
            onValueChangeFinished = { onChange(local.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = if (value == null) c.text3 else c.accent,
                activeTrackColor = if (value == null) c.surface3 else c.accent,
                inactiveTrackColor = c.surface2,
            ),
        )
    }
}

@Composable
private fun NumberField(label: String, value: Int?, fallback: Int?, placeholder: String, onChange: (Int?) -> Unit) {
    val c = Noc.colors
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
        Box(Modifier.width(120.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (text.isEmpty()) Text(fallback?.toString() ?: placeholder, style = MonoSmall, color = c.text3)
            BasicTextField(
                text,
                { t -> text = t.filter { it.isDigit() || it == '-' }.take(9); onChange(text.toIntOrNull()) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MonoSmall.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ContextPicker(value: Int?, model: ModelInfo?, onChange: (Int?) -> Unit) {
    val c = Noc.colors
    val max = model?.maxContext?.takeIf { it > 0 } ?: 131072
    val options = listOf(4096, 8192, 16384, 32768, 65536, 131072).filter { it <= max }
    Column(Modifier.padding(vertical = 6.dp)) {
        Text("Contexto ao carregar o modelo", style = MaterialTheme.typography.titleSmall, color = c.text)
        Text(
            "Vale quando o PC precisar carregar o modelo. Mais contexto usa mais memória de vídeo." +
                (model?.context?.let { " Carregado agora com ${Texts.tokens(it)}." } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = c.text3,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.noc.app.ui.components.Chip("Padrão", selected = value == null) { onChange(null) }
            options.forEach { o -> com.noc.app.ui.components.Chip(Texts.tokens(o), selected = value == o) { onChange(o) } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StopField(stops: List<String>, onChange: (List<String>) -> Unit) {
    val c = Noc.colors
    var input by remember { mutableStateOf("") }
    Column(Modifier.padding(vertical = 6.dp)) {
        Text("Sequências de parada", style = MaterialTheme.typography.titleSmall, color = c.text)
        Text("A geração para quando o modelo escrever um destes textos.", style = MaterialTheme.typography.bodySmall, color = c.text3)
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            stops.forEach { s ->
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(c.surface).pressable { onChange(stops - s) }.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.replace("\n", "\\n"), style = MonoSmall, color = c.text)
                    Spacer(Modifier.width(6.dp))
                    Text("×", color = c.text3)
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(c.surface).padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (input.isEmpty()) Text("Adicionar…", style = MonoSmall, color = c.text3)
                BasicTextField(input, { input = it.take(64) }, singleLine = true, textStyle = MonoSmall.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
            }
            GhostButton("Adicionar") {
                if (input.isNotEmpty() && stops.size < 8) onChange(stops + input.replace("\\n", "\n"))
                input = ""
            }
        }
    }
}

// ------------------------------------------------------------------ ações de mensagem

@Composable
fun MessageActionsSheet(
    m: MessageEntity,
    canContinue: Boolean,
    onCopy: () -> Unit,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onRegenerateWith: () -> Unit,
    onContinue: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    NocSheet(onDismiss) {
        val user = m.role == "user"
        SheetAction(Icons.Rounded.ContentCopy, "Copiar") { onCopy(); onDismiss() }
        SheetAction(Icons.Rounded.SelectAll, "Selecionar texto") { onSelect(); onDismiss() }
        if (user) SheetAction(Icons.Rounded.Edit, "Editar e reenviar", "Cria uma nova versão da conversa a partir daqui") { onEdit(); onDismiss() }
        if (!user) {
            SheetAction(Icons.Rounded.Refresh, "Gerar outra resposta") { onRegenerate(); onDismiss() }
            SheetAction(Icons.Rounded.SwapHoriz, "Gerar com outro modelo") { onRegenerateWith(); onDismiss() }
            if (canContinue) SheetAction(Icons.Rounded.PlayArrow, "Continuar resposta") { onContinue(); onDismiss() }
            SheetAction(Icons.Rounded.Share, "Compartilhar") { onShare(); onDismiss() }
        }
        SheetAction(Icons.Rounded.Delete, "Excluir", "Remove esta mensagem e o que veio depois dela", danger = true) { onDelete(); onDismiss() }
    }
}

@Composable
fun SelectTextSheet(text: String, onDismiss: () -> Unit) {
    val c = Noc.colors
    NocSheet(onDismiss) {
        Text("Selecionar texto", style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = c.text,
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
fun StatsSheet(m: MessageEntity, onDismiss: () -> Unit) {
    val c = Noc.colors
    val s = GenStats.decode(m.statsJson) ?: GenStats()
    NocSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 22.dp)) {
            Text("Esta resposta", style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.padding(vertical = 8.dp))
            val rows = listOfNotNull(
                m.model?.let { "Modelo" to it },
                s.tps?.let { "Velocidade" to "%.1f tokens/s".format(it) },
                s.ttftMs?.let { "Primeiro token" to Texts.seconds(it) },
                s.totalMs?.let { "Tempo total" to Texts.seconds(it) },
                s.completionTokens?.let { "Tokens gerados" to it.toString() },
                s.reasoningTokens?.takeIf { it > 0 }?.let { "Tokens de raciocínio" to it.toString() },
                s.promptTokens?.let { "Tokens de entrada" to it.toString() },
                if (s.context != null && s.promptTokens != null && s.completionTokens != null) {
                    val used = s.promptTokens + s.completionTokens
                    "Contexto usado" to "${Texts.tokens(used)} de ${Texts.tokens(s.context)} (${(used * 100 / s.context.coerceAtLeast(1))}%)"
                } else null,
                s.route?.let { "Conexão" to if (it == "lan") "Rede local (direta)" else "Remota (cifrada ponta a ponta)" },
                "Horário" to Texts.dayLabel(m.createdAt) + ", " + Texts.time(m.createdAt),
            )
            rows.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                    Text(k, style = MaterialTheme.typography.bodyMedium, color = c.text2, modifier = Modifier.weight(1f))
                    Text(v, style = MonoSmall, color = c.text)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun AttachSheet(visionEnabled: Boolean, modelName: String?, onImage: () -> Unit, onFile: () -> Unit, onDismiss: () -> Unit) {
    NocSheet(onDismiss) {
        Text("Anexar", style = MaterialTheme.typography.headlineLarge, color = Noc.colors.text, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
        SheetAction(
            Icons.Rounded.Image, "Imagem",
            if (visionEnabled) "Foto ou captura para o modelo analisar" else "${modelName ?: "O modelo atual"} não entende imagens",
            enabled = visionEnabled,
        ) { onImage(); onDismiss() }
        SheetAction(Icons.AutoMirrored.Rounded.Notes, "Arquivo de texto ou código", "TXT, MD, CSV, JSON, código-fonte… até 400 KB") { onFile(); onDismiss() }
    }
}

@Composable
fun RenameSheet(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Noc.colors
    var text by remember { mutableStateOf(current) }
    NocSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 22.dp)) {
            Text("Renomear", style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.padding(vertical = 8.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(16.dp)) {
                BasicTextField(text, { text = it.take(80) }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Salvar", Modifier.fillMaxWidth(), enabled = text.isNotBlank(), height = 48.dp) { onRename(text); onDismiss() }
            Spacer(Modifier.height(8.dp))
        }
    }
}

