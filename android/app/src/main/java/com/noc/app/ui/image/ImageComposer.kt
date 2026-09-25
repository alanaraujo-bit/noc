package com.noc.app.ui.image

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noc.app.chat.ImageCaps
import com.noc.app.chat.ImageInput
import com.noc.app.chat.ImageOptions
import com.noc.app.chat.LoraInfo
import com.noc.app.chat.LoraPick
import com.noc.app.ui.chat.NocSheet
import com.noc.app.ui.components.PrimaryButton
import com.noc.app.ui.components.SectionLabel
import com.noc.app.ui.components.pressable
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.Noc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val defaultFormats = listOf(
    ImageCaps.Format("square", "Quadrado", 1024, 1024), ImageCaps.Format("portrait", "Retrato", 832, 1216),
    ImageCaps.Format("landscape", "Paisagem", 1216, 832), ImageCaps.Format("story", "Story", 768, 1344),
    ImageCaps.Format("wallpaper", "Wallpaper", 1344, 768),
)

/** Guias de criação: o que a imagem escolhida vai controlar. */
enum class Guide(val label: String, val sub: String, val control: String?) {
    REFERENCE("Imagem de referência", "Estilo, rosto e clima parecidos com a imagem", null),
    POSE("Copiar pose", "A mesma pose das pessoas da imagem", "pose"),
    DEPTH("Copiar estrutura", "Mesma composição e profundidade", "depth"),
    EDGES("Seguir contornos", "Segue as linhas da imagem (bom para desenhos)", "canny"),
}

/** Rótulo curto do motor escolhido ("◈ RealVisXL V5.0", "⚡ Rápido"). */
fun engineChipLabel(o: ImageOptions, caps: ImageCaps?): String {
    o.model?.let { id -> return "◈ " + (caps?.model(id)?.name ?: "Especialista") }
    return ImageOptions.engineLabel(o.engine)
}

/** Nome do modelo que vai rodar (para o topo do chat). */
fun engineModelName(o: ImageOptions, caps: ImageCaps?): String? =
    caps?.model(o.model)?.name ?: caps?.engine(if (o.engine == "auto") "fast" else o.engine)?.name

/**
 * Barra do modo "Criar imagem", acima do campo de texto. Simples por padrão (motor, formato, qualidade, quantidade);
 * LoRAs, guias e o Estúdio ficam a um toque, sem nenhuma sintaxe técnica.
 */
@Composable
fun ImageModeBar(
    options: ImageOptions,
    caps: ImageCaps?,
    /** Recursos da fase 4 que o PC anuncia (LoRAs, guias). */
    phase4: Boolean = true,
    onChange: (ImageOptions) -> Unit,
    onClose: () -> Unit,
    onStudio: () -> Unit,
    onEngine: () -> Unit,
    onLoras: () -> Unit,
    onGuide: () -> Unit,
) {
    val c = Noc.colors
    val formats = caps?.formats?.takeIf { it.isNotEmpty() } ?: defaultFormats
    val model = caps?.modelFor(options)
    val lorasOk = phase4 && (options.engine == "auto" && options.model == null || model?.can("loras") == true)
    val guidesOk = phase4 && (options.engine == "auto" && options.model == null || model?.can("controlnet") == true)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = c.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Criar imagem", style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
            // Estúdio sempre visível (não se perde no fim da fila de opções)
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(if (options.hasAdvanced) c.accentSoft else c.surface2).pressable(onClick = onStudio)
                    .padding(horizontal = 12.dp, vertical = 7.dp).semantics { contentDescription = "Estúdio: controles avançados" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Tune, null, tint = if (options.hasAdvanced) c.accent else c.text2, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (options.raw) "Estúdio · RAW" else "Estúdio", style = MaterialTheme.typography.labelMedium, color = if (options.hasAdvanced) c.accent else c.text)
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(36.dp).clip(CircleShape).pressable(onClick = onClose).semantics { contentDescription = "Sair do modo imagem" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, null, tint = c.text3, modifier = Modifier.size(18.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PillButton(engineChipLabel(options, caps), "Motor", accent = options.model != null, onClick = onEngine)
            if (lorasOk) PillButton(if (options.loras.isEmpty()) "LoRAs" else "LoRAs · ${options.loras.size}", "LoRAs", icon = Icons.Rounded.Layers, accent = options.loras.isNotEmpty(), onClick = onLoras)
            if (guidesOk) PillButton("Guia", "Guiar com uma imagem", icon = Icons.Rounded.Image, accent = options.reference != null || options.control != null, onClick = onGuide)
            Selector(formats.firstOrNull { it.id == options.format }?.label ?: if (options.format == "custom") "Personalizado" else "Quadrado", "Formato") { close ->
                formats.forEach { f ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FormatGlyph(f.width, f.height)
                                Spacer(Modifier.width(10.dp))
                                Text(f.label, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
                                Text("${f.width}×${f.height}", style = MonoSmall, color = c.text3)
                            }
                        },
                        onClick = { onChange(options.copy(format = f.id, width = null, height = null)); close() },
                    )
                }
                DropdownMenuItem(text = { Text("Personalizado…", style = MaterialTheme.typography.bodyMedium, color = c.text) }, onClick = { close(); onStudio() })
            }
            Selector(when (options.quality) { "fast" -> "Rápida"; "high" -> "Alta"; else -> "Padrão" }, "Qualidade") { close ->
                listOf("fast" to "Rápida", "standard" to "Padrão", "high" to "Alta").forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(label, style = MaterialTheme.typography.bodyMedium, color = c.text) }, onClick = { onChange(options.copy(quality = id, steps = null)); close() })
                }
            }
            Selector(if (options.count == 1) "1 imagem" else "${options.count} imagens", "Quantidade") { close ->
                (caps?.counts ?: listOf(1, 2, 4)).forEach { n ->
                    DropdownMenuItem(text = { Text(if (n == 1) "1 imagem" else "$n imagens", style = MaterialTheme.typography.bodyMedium, color = c.text) }, onClick = { onChange(options.copy(count = n)); close() })
                }
            }
            if (!options.raw) Toggle("Melhorar prompt", options.enhance) { onChange(options.copy(enhance = !options.enhance)) }
        }
        // o que está ativo além do básico, visível e fácil de tirar
        val active = options.loras.isNotEmpty() || options.reference != null || options.controlInput != null
        if (active) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.reference?.let { r ->
                    ActiveChip("Referência", r.thumb ?: r.path) { onChange(options.copy(reference = null, referenceScale = null)) }
                }
                if (options.control != null && options.controlInput != null) {
                    val g = Guide.entries.firstOrNull { it.control == options.control }
                    ActiveChip(g?.label ?: "Guia", options.controlInput.thumb ?: options.controlInput.path) { onChange(options.copy(control = null, controlInput = null, controlScale = null)) }
                }
                options.loras.forEach { l ->
                    ActiveChip("${l.name} · ${"%.1f".format(l.weight)}", null) { onChange(options.copy(loras = options.loras - l)) }
                }
            }
        }
    }
}

@Composable
private fun ActiveChip(label: String, thumb: String?, onRemove: () -> Unit) {
    val c = Noc.colors
    val bmp by produceState<Bitmap?>(null, thumb) { value = withContext(Dispatchers.IO) { thumb?.let { decodeSampled(it, 96) } } }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(c.accentSoft).padding(start = if (bmp != null) 4.dp else 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bmp?.let {
            Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(24.dp).clip(CircleShape))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        Box(Modifier.size(26.dp).clip(CircleShape).pressable(onClick = onRemove).semantics { contentDescription = "Remover $label" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, null, tint = c.accent, modifier = Modifier.size(14.dp))
        }
    }
}


@Composable
private fun PillButton(label: String, desc: String, icon: ImageVector? = null, accent: Boolean = false, onClick: () -> Unit) {
    val c = Noc.colors
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (accent) c.accentSoft else c.surface2).pressable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).semantics { contentDescription = "$desc: $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (accent) c.accent else c.text2, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (accent) c.accent else c.text, maxLines = 1)
        Icon(Icons.Rounded.ExpandMore, null, tint = c.text3, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun FormatGlyph(w: Int, h: Int) {
    val c = Noc.colors
    val max = 16f
    val bw = if (w >= h) max else max * w / h
    val bh = if (h >= w) max else max * h / w
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(bw.dp, bh.dp).clip(RoundedCornerShape(2.dp)).background(c.text3))
    }
}

@Composable
private fun Selector(label: String, desc: String, items: @Composable (close: () -> Unit) -> Unit) {
    val c = Noc.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(c.surface2).pressable { open = true }.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                .semantics { contentDescription = "$desc: $label" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.text)
            Icon(Icons.Rounded.ExpandMore, null, tint = c.text3, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { items { open = false } }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Noc.colors
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (on) c.accentSoft else c.surface2).pressable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = if (on) c.accent else c.text3, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) c.accent else c.text)
    }
}

// ------------------------------------------------------------------ motor

/**
 * Escolha do motor: Automático, ⚡ Rápido (Z-Image), ◆ Qualidade (FLUX) e ◈ Especialistas (RealVisXL, NoobAI, Pony e
 * checkpoints importados). O que não está pronto no PC aparece com o motivo (instalando, autorização) e não é escolhível.
 */
@Composable
fun EngineSheet(options: ImageOptions, caps: ImageCaps?, onPick: (ImageOptions) -> Unit, onManage: () -> Unit, onDismiss: () -> Unit) {
    val c = Noc.colors
    NocSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
            Text("Motor", style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            EngineRow("✦", "Automático", "Escolhe o melhor para cada pedido", options.engine == "auto" && options.model == null, true, null) {
                onPick(options.copy(engine = "auto", model = null, steps = null, guidance = null)); onDismiss()
            }
            val z = caps?.model("z-image-turbo")
            EngineRow("⚡", "Rápido", "Z-Image Turbo · " + (z?.tagline?.ifBlank { null } ?: "rápido, fotos e texto na imagem"), options.engine == "fast" && options.model == null,
                z?.ready != false, z?.let { stateLine(it, caps) }) {
                onPick(options.copy(engine = "fast", model = null, steps = null, guidance = null, loras = emptyList(), control = null, controlInput = null, reference = null)); onDismiss()
            }
            val f = caps?.model("flux1-dev")
            EngineRow("◆", "Qualidade", "FLUX.1 Dev · qualidade máxima e composição", options.engine == "quality" && options.model == null,
                f?.ready == true, f?.let { stateLine(it, caps) }) {
                onPick(options.copy(engine = "quality", model = null, steps = null, guidance = null, loras = emptyList(), control = null, controlInput = null, reference = null)); onDismiss()
            }
            val specialists = caps?.specialists.orEmpty()
            if (specialists.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SectionLabel("◈ Especialistas", Modifier.padding(start = 4.dp))
                specialists.forEach { m ->
                    EngineRow("◈", m.name, m.tagline.ifBlank { m.family }, options.model == m.id, m.ready, stateLine(m, caps)) {
                        // LoRAs de outra família ficam; o PC avisa se algum não servir
                        onPick(options.copy(engine = "model", model = m.id, steps = null, guidance = null)); onDismiss()
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Modelos, LoRAs e importação", style = MaterialTheme.typography.labelLarge, color = c.accent,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).pressable { onDismiss(); onManage() }.padding(12.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun stateLine(m: ImageCaps.ModelState, caps: ImageCaps?): String? = when {
    m.ready -> null
    m.state in setOf("downloading", "verifying", "checking") -> m.total?.takeIf { it > 0 }?.let { t -> "Instalando no PC · ${((m.done ?: 0) * 100 / t)}%" } ?: "Instalando no PC"
    m.state in setOf("auth_required", "terms_required") || (m.gated && caps?.hfUser == null) -> "Precisa da sua conta do Hugging Face"
    caps?.installQueue?.contains(m.id) == true -> "Na fila de instalação"
    m.state == "no_space" -> "Sem espaço no disco do PC"
    else -> "Não instalado"
}

@Composable
private fun EngineRow(glyph: String, name: String, sub: String, selected: Boolean, enabled: Boolean, state: String?, onClick: () -> Unit) {
    val c = Noc.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) c.accentSoft else c.surface)
            .then(if (selected) Modifier.border(1.dp, c.accent, RoundedCornerShape(16.dp)) else Modifier)
            .pressable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { contentDescription = "$name: $sub" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph, style = MaterialTheme.typography.titleMedium, color = if (enabled) c.accent else c.text3, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = if (enabled) c.text else c.text3)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = c.text3, maxLines = 2)
            if (state != null) Text(state, style = MaterialTheme.typography.labelSmall, color = c.warn)
        }
    }
}

// ------------------------------------------------------------------ LoRAs

/**
 * LoRAs para esta criação: lista da biblioteca do PC com a compatibilidade em relação ao modelo escolhido.
 * Tocar adiciona (com o peso sugerido); o peso é ajustado no controle deslizante. Nenhuma sintaxe.
 */
@Composable
fun LoraPickerSheet(
    options: ImageOptions,
    caps: ImageCaps?,
    load: suspend () -> List<LoraInfo>,
    onChange: (ImageOptions) -> Unit,
    onLibrary: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Noc.colors
    var all by remember { mutableStateOf<List<LoraInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var picks by remember { mutableStateOf(options.loras) }
    LaunchedEffect(Unit) { runCatching { all = load() }.onFailure { error = "Não foi possível carregar a biblioteca do PC." } }
    val model = caps?.modelFor(options)
    NocSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text("LoRAs", style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.weight(1f))
                Text("Biblioteca", style = MaterialTheme.typography.labelLarge, color = c.accent,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).pressable { onDismiss(); onLibrary() }.padding(10.dp))
            }
            Text(
                if (model != null) "Para ${model.name}. Os de outra família funcionam, mas com resultado diferente." else "No Automático, o motor segue a família do LoRA escolhido.",
                style = MaterialTheme.typography.bodySmall, color = c.text3, modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            SearchBox(query, "Buscar LoRA") { query = it }
            Spacer(Modifier.height(8.dp))
            val list = all
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                when {
                    error != null -> Text(error!!, style = MaterialTheme.typography.bodyMedium, color = c.err, modifier = Modifier.padding(12.dp))
                    list == null -> Text("Carregando…", style = MaterialTheme.typography.bodyMedium, color = c.text3, modifier = Modifier.padding(12.dp))
                    list.isEmpty() -> Text(
                        "Nenhum LoRA ainda. Abra a Biblioteca para importar por link (Civitai ou Hugging Face), ou salve um .safetensors na pasta Downloads do PC.",
                        style = MaterialTheme.typography.bodyMedium, color = c.text2, modifier = Modifier.padding(12.dp),
                    )
                    else -> {
                        val q = query.trim().lowercase()
                        list.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.triggers.any { t -> t.lowercase().contains(q) } || it.familyLabel.lowercase().contains(q) }
                            .sortedWith(compareByDescending<LoraInfo> { l -> picks.any { it.id == l.id } }.thenByDescending { it.compatible }.thenByDescending { it.favorite }.thenByDescending { it.lastUsed ?: it.addedAt })
                            .forEach { l ->
                                val pick = picks.firstOrNull { it.id == l.id }
                                LoraRow(l, pick, onToggle = {
                                    picks = if (pick != null) picks - pick else (picks + LoraPick(l.id, l.name, l.weight, l.family)).takeLast(5)
                                }, onWeight = { w -> picks = picks.map { if (it.id == l.id) it.copy(weight = w) else it } })
                            }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(if (picks.isEmpty()) (if (options.loras.isEmpty()) "Concluído" else "Tirar os LoRAs") else "Usar ${picks.size} LoRA${if (picks.size > 1) "s" else ""}", Modifier.fillMaxWidth(), height = 48.dp) {
                onChange(options.copy(loras = picks)); onDismiss()
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun LoraRow(l: LoraInfo, pick: LoraPick?, onToggle: () -> Unit, onWeight: (Double) -> Unit) {
    val c = Noc.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(if (pick != null) c.accentSoft else c.surface)
            .pressable(enabled = l.compatible, onClick = onToggle).padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics { contentDescription = "LoRA ${l.name}" + if (pick != null) ", ativo" else "" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(l.name, style = MaterialTheme.typography.titleSmall, color = if (l.compatible) c.text else c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(l.familyLabel, l.triggers.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "ativa com: ")).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                l.warning?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (l.compatible) c.warn else c.err, maxLines = 2) }
            }
            if (l.favorite) Icon(Icons.Rounded.Favorite, null, tint = c.accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Switch(
                checked = pick != null, onCheckedChange = { onToggle() }, enabled = l.compatible,
                colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent, uncheckedTrackColor = c.surface3, uncheckedThumbColor = c.text3),
            )
        }
        if (pick != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Força ${"%.2f".format(pick.weight)}", style = MonoSmall, color = c.text2, modifier = Modifier.width(88.dp))
                Slider(
                    value = pick.weight.toFloat(), onValueChange = { onWeight((it * 20).toInt() / 20.0) }, valueRange = 0f..1.5f,
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent), modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun SearchBox(value: String, placeholder: String, onChange: (String) -> Unit) {
    val c = Noc.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = c.text3, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.text3)
            BasicTextField(value, onChange, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder })
        }
    }
}

// ------------------------------------------------------------------ guias (referência, pose, estrutura, contornos)

/** Escolha do guia e de onde vem a imagem (galeria do celular ou Criações). */
@Composable
fun GuideSheet(options: ImageOptions, caps: ImageCaps?, onPick: (Guide, fromCreations: Boolean) -> Unit, onDismiss: () -> Unit) {
    val c = Noc.colors
    var guide by remember { mutableStateOf<Guide?>(null) }
    NocSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text(if (guide == null) "Guiar com uma imagem" else guide!!.label, style = MaterialTheme.typography.headlineLarge, color = c.text,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            if (guide == null) {
                Text("Funciona nos especialistas (RealVisXL, NoobAI, Pony). No Automático, eles são escolhidos sozinhos.",
                    style = MaterialTheme.typography.bodySmall, color = c.text3, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                val icons = mapOf(Guide.REFERENCE to Icons.Rounded.Image, Guide.POSE to Icons.Rounded.AccessibilityNew, Guide.DEPTH to Icons.Rounded.Terrain, Guide.EDGES to Icons.Rounded.Gesture)
                Guide.entries.forEach { g ->
                    val tool = when (g) { Guide.REFERENCE -> "reference"; Guide.POSE -> "pose"; Guide.DEPTH -> "depth"; Guide.EDGES -> "canny" }
                    val ok = caps?.tool(tool) != false
                    OptionRow(icons[g]!!, g.label, if (ok) g.sub else "O componente ainda está sendo instalado no PC", ok) { guide = g }
                }
            } else {
                OptionRow(Icons.Rounded.PhotoLibrary, "Do celular", "Galeria, câmera ou arquivos", true) { onPick(guide!!, false); onDismiss() }
                OptionRow(Icons.Rounded.AutoAwesome, "Das minhas criações", "Uma imagem já feita no PC", true) { onPick(guide!!, true); onDismiss() }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun OptionRow(icon: ImageVector, title: String, sub: String?, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Noc.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp)).background(c.surface)
            .pressable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp).semantics { contentDescription = title },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (enabled) c.accent else c.text3, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) c.text else c.text3)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = c.text3)
        }
    }
}

// ------------------------------------------------------------------ Estúdio

/**
 * Modo Estúdio: tudo o que o modelo escolhido realmente usa — tamanho, passos, CFG/guidance, sampler, scheduler, seed,
 * clip skip, prompt negativo, RAW, força das guias, detalhe de rostos/mãos e Alta Definição. Os padrões vêm do preset
 * medido na GPU do PC; "(padrão)" mostra o que vale sem mexer.
 */
@Composable
fun ImageStudioSheet(options: ImageOptions, caps: ImageCaps?, onApply: (ImageOptions) -> Unit, onDismiss: () -> Unit) {
    val c = Noc.colors
    val model = caps?.modelFor(options)
    val legacy = caps?.engine(if (options.engine == "auto" || options.engine == "model") "fast" else options.engine)
    var o by remember { mutableStateOf(options) }
    var w by remember { mutableStateOf((options.width ?: 1024).toString()) }
    var h by remember { mutableStateOf((options.height ?: 1024).toString()) }
    var seed by remember { mutableStateOf(options.seed?.toString() ?: "") }
    var negative by remember { mutableStateOf(options.negative ?: "") }
    val sdxl = model?.sdxl == true
    NocSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 22.dp).verticalScroll(rememberScrollState())) {
            Text("Estúdio", style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.padding(top = 8.dp))
            Text(
                when {
                    model == null -> "No Automático o modelo é escolhido na hora; os ajustes finos valem quando você escolhe o motor."
                    model.preset != null && sdxl -> "Preset do ${model.name}: ${samplerLabel(caps, model.preset.sampler)} ${model.preset.schedule.takeIf { it != "normal" }?.let { scheduleLabel(caps, it) } ?: ""} · CFG ${"%.1f".format(model.guidanceDefault ?: 0.0)} · ${model.stepPresets.getOrNull(1) ?: ""} passos" +
                        if (model.preset.prediction == "v_prediction") " · v-prediction" else ""
                    else -> "Os padrões já foram ajustados para a GPU do seu PC. Mude só se quiser algo específico."
                },
                style = MaterialTheme.typography.bodySmall, color = c.text3, modifier = Modifier.padding(bottom = 14.dp, top = 4.dp),
            )

            StudioSwitch("RAW", "O prompt vai exatamente como você escrever: sem tradução, sem tags automáticas e sem negativo padrão.", o.raw) { o = o.copy(raw = it, enhance = if (it) false else o.enhance) }
            if (model?.can("negative") == true || model == null) {
                Label("Prompt negativo")
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(14.dp)) {
                    if (negative.isEmpty()) Text(if (o.raw) "O que evitar (vazio = nada)" else "O que evitar, além do padrão do modelo", style = MaterialTheme.typography.bodyLarge, color = c.text3)
                    BasicTextField(negative, { negative = it.take(2000) }, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                        cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Prompt negativo" })
                }
                if (!o.raw && model?.preset?.negative?.isNotBlank() == true)
                    Text("Padrão do modelo: " + model.preset.negative, style = MonoSmall, color = c.text3, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(16.dp))
            }

            Label("Tamanho")
            Row(verticalAlignment = Alignment.CenterVertically) {
                NumberBox(w, "Largura", Modifier.weight(1f)) { w = it; o = o.copy(format = "custom") }
                Text("×", color = c.text3, modifier = Modifier.padding(horizontal = 10.dp))
                NumberBox(h, "Altura", Modifier.weight(1f)) { h = it; o = o.copy(format = "custom") }
            }
            Text("Múltiplos de 16, de 256 a 2048. O PC ajusta ao limite do modelo.", style = MonoSmall, color = c.text3, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(16.dp))

            val stepsMin = model?.stepsMin ?: legacy?.stepsMin
            val stepsMax = model?.stepsMax ?: legacy?.stepsMax
            val presets = model?.stepPresets?.takeIf { it.isNotEmpty() } ?: legacy?.stepPresets
            if (stepsMin != null && stepsMax != null && (model != null || options.engine != "auto")) {
                val def = presets?.getOrNull(1) ?: 9
                val steps = o.steps ?: def
                Label("Passos · $steps" + if (o.steps == null) " (padrão)" else "")
                Slider(
                    value = steps.toFloat(), onValueChange = { o = o.copy(steps = it.toInt()) },
                    valueRange = stepsMin.toFloat()..stepsMax.toFloat(), steps = (stepsMax - stepsMin - 1).coerceAtLeast(0),
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
                )
                val gMin = model?.guidanceMin ?: legacy?.guidanceMin
                val gMax = model?.guidanceMax ?: legacy?.guidanceMax
                if (gMin != null && gMax != null) {
                    val g = o.guidance ?: model?.guidanceDefault ?: legacy?.guidanceDefault ?: gMin
                    Label((if (sdxl) "CFG" else "Guidance") + " · ${"%.1f".format(g)}" + if (o.guidance == null) " (padrão)" else "")
                    Slider(
                        value = g.toFloat(), onValueChange = { o = o.copy(guidance = (it * 10).toInt() / 10.0) },
                        valueRange = gMin.toFloat()..gMax.toFloat(), colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
                    )
                }
            }
            if (sdxl && caps != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Label("Sampler")
                        Dropdown(samplerLabel(caps, o.sampler ?: model?.preset?.sampler ?: "euler") + if (o.sampler == null) " ·" else "", caps.samplers) { o = o.copy(sampler = it) }
                    }
                    Column(Modifier.weight(1f)) {
                        Label("Scheduler")
                        Dropdown(scheduleLabel(caps, o.schedule ?: model?.preset?.schedule ?: "normal") + if (o.schedule == null) " ·" else "", caps.schedules) { o = o.copy(schedule = it) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val cs = o.clipSkip ?: 2
                Label("Clip skip · $cs" + if (o.clipSkip == null) " (padrão)" else "")
                Slider(value = cs.toFloat(), onValueChange = { o = o.copy(clipSkip = it.toInt().coerceIn(1, 4).takeIf { v -> v != 2 }) }, valueRange = 1f..4f, steps = 2,
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent))
                if (model?.preset?.prediction == "v_prediction") {
                    val gr = o.guidanceRescale ?: model.preset.guidanceRescale
                    Label("Rescale do CFG · ${"%.2f".format(gr)}" + if (o.guidanceRescale == null) " (padrão)" else "")
                    Slider(value = gr.toFloat(), onValueChange = { o = o.copy(guidanceRescale = (it * 20).toInt() / 20.0) }, valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent))
                }
            }
            if (o.reference != null) {
                val s = o.referenceScale ?: 0.5
                Label("Força da referência · ${"%.2f".format(s)}")
                Slider(value = s.toFloat(), onValueChange = { o = o.copy(referenceScale = (it * 20).toInt() / 20.0) }, valueRange = 0.1f..1f,
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent))
            }
            if (o.control != null) {
                val s = o.controlScale ?: if (o.control == "pose") 0.9 else 0.7
                Label("Força do guia · ${"%.2f".format(s)}")
                Slider(value = s.toFloat(), onValueChange = { o = o.copy(controlScale = (it * 20).toInt() / 20.0) }, valueRange = 0.2f..1.2f,
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent))
            }

            Label("Seed")
            NumberBox(seed, "Aleatória", Modifier.fillMaxWidth()) { seed = it }
            Text("A mesma seed com o mesmo pedido repete a imagem. Vazio = uma nova a cada vez.", style = MonoSmall, color = c.text3, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(16.dp))
            StudioSwitch("Refazer rostos e mãos", "Depois de gerar, detecta rostos e mãos e refaz cada um em alta resolução.", o.detail) { o = o.copy(detail = it) }
            StudioSwitch("Alta Definição", "Amplia 2x e refina os detalhes com o próprio modelo (leva mais tempo).", o.hd) { o = o.copy(hd = it) }
            Spacer(Modifier.height(10.dp))
            PrimaryButton("Aplicar", Modifier.fillMaxWidth(), height = 48.dp) {
                val custom = o.format == "custom"
                onApply(
                    o.copy(
                        width = if (custom) w.toIntOrNull()?.coerceIn(256, 2048) else null,
                        height = if (custom) h.toIntOrNull()?.coerceIn(256, 2048) else null,
                        seed = seed.toLongOrNull()?.coerceIn(0, Int.MAX_VALUE.toLong() - 8),
                        negative = negative.trim().ifEmpty { null },
                    ),
                )
                onDismiss()
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Restaurar padrões", style = MaterialTheme.typography.labelMedium, color = c.accent,
                modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(10.dp))
                    .pressable {
                        onApply(options.copy(steps = null, guidance = null, seed = null, width = null, height = null, negative = null, raw = false, sampler = null,
                            schedule = null, clipSkip = null, guidanceRescale = null, detail = false, hd = false, referenceScale = null, controlScale = null,
                            format = if (options.format == "custom") "square" else options.format))
                        onDismiss()
                    }
                    .padding(12.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun samplerLabel(caps: ImageCaps?, id: String) = caps?.samplers?.firstOrNull { it.first == id }?.second ?: id
private fun scheduleLabel(caps: ImageCaps?, id: String) = caps?.schedules?.firstOrNull { it.first == id }?.second ?: id

@Composable
private fun StudioSwitch(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Noc.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).pressable { onChange(!on) }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = c.text3)
        }
        Switch(checked = on, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent, uncheckedTrackColor = c.surface3, uncheckedThumbColor = c.text3))
    }
}

@Composable
private fun Dropdown(label: String, items: List<Pair<String, String>>, onPick: (String) -> Unit) {
    val c = Noc.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).pressable { open = true }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
            Icon(Icons.Rounded.ExpandMore, null, tint = c.text3, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (id, l) -> DropdownMenuItem(text = { Text(l, color = c.text) }, onClick = { onPick(id); open = false }) }
        }
    }
}

@Composable
private fun Label(t: String) {
    Text(t, style = MaterialTheme.typography.labelLarge, color = Noc.colors.text2, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun NumberBox(value: String, placeholder: String, modifier: Modifier, onChange: (String) -> Unit) {
    val c = Noc.colors
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(c.surface).padding(14.dp)) {
        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.text3)
        BasicTextField(
            value, { v -> onChange(v.filter { it.isDigit() }.take(10)) }, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text), cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Uma imagem escolhida para guiar/editar, já preparada para subir ao PC (JPEG de até 2048 px, com hash). */
fun localInput(path: String, sha256: String, width: Int?, height: Int?) = ImageInput("local", path = path, sha256 = sha256, width = width, height = height, thumb = path)
