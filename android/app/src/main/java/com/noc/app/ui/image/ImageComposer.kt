package com.noc.app.ui.image

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noc.app.chat.ImageCaps
import com.noc.app.chat.ImageOptions
import com.noc.app.ui.chat.NocSheet
import com.noc.app.ui.components.PrimaryButton
import com.noc.app.ui.components.pressable
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.Noc

private val defaultFormats = listOf(
    ImageCaps.Format("square", "Quadrado", 1024, 1024), ImageCaps.Format("portrait", "Retrato", 832, 1216),
    ImageCaps.Format("landscape", "Paisagem", 1216, 832), ImageCaps.Format("story", "Story", 768, 1344),
    ImageCaps.Format("wallpaper", "Wallpaper", 1344, 768),
)

/** Barra do modo "Criar imagem", acima do campo de texto. Simples por padrão; o resto fica em "Avançado". */
@Composable
fun ImageModeBar(options: ImageOptions, caps: ImageCaps?, onChange: (ImageOptions) -> Unit, onClose: () -> Unit, onAdvanced: () -> Unit) {
    val c = Noc.colors
    val formats = caps?.formats?.takeIf { it.isNotEmpty() } ?: defaultFormats
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = c.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Criar imagem", style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
            Box(Modifier.size(36.dp).clip(CircleShape).pressable(onClick = onClose).semantics { contentDescription = "Sair do modo imagem" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, null, tint = c.text3, modifier = Modifier.size(18.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val engines = listOf("fast", "quality", "auto")
            Selector(ImageOptions.engineLabel(options.engine), "Motor") { close ->
                engines.forEach { e ->
                    val info = caps?.engine(e)
                    val available = e == "auto" || info?.installed != false
                    val sub = when (e) {
                        "auto" -> "Escolhe o melhor para o pedido"
                        else -> info?.name?.let { if (available) it else "$it · " + gatedText(caps, info.model) } ?: ""
                    }
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(ImageOptions.engineLabel(e), style = MaterialTheme.typography.bodyMedium, color = if (available) c.text else c.text3)
                                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.labelSmall, color = c.text3)
                            }
                        },
                        enabled = available,
                        onClick = { onChange(options.copy(engine = e, steps = null, guidance = null)); close() },
                    )
                }
            }
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
                DropdownMenuItem(text = { Text("Personalizado…", style = MaterialTheme.typography.bodyMedium, color = c.text) }, onClick = { close(); onAdvanced() })
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
            Toggle("Melhorar prompt", options.enhance) { onChange(options.copy(enhance = !options.enhance)) }
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (options.hasAdvanced) c.accentSoft else c.surface2).pressable(onClick = onAdvanced)
                    .padding(horizontal = 12.dp, vertical = 8.dp).semantics { contentDescription = "Ajustes avançados" },
            ) { Icon(Icons.Rounded.Tune, null, tint = if (options.hasAdvanced) c.accent else c.text2, modifier = Modifier.size(18.dp)) }
        }
    }
}

private fun gatedText(caps: ImageCaps?, model: String): String {
    val m = caps?.models?.firstOrNull { it.id == model } ?: return "não instalado"
    return when (m.state) {
        "downloading", "verifying", "checking" -> "instalando no PC"
        "auth_required", "terms_required" -> "autorização necessária"
        else -> if (m.gated && caps.hfUser == null) "autorização necessária" else "não instalado"
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

/**
 * Ajustes avançados — só aparecem os que o motor escolhido realmente usa
 * (o Z-Image Turbo não tem guidance nem prompt negativo; o FLUX.1 Dev tem guidance destilado).
 */
@Composable
fun ImageAdvancedSheet(options: ImageOptions, caps: ImageCaps?, onApply: (ImageOptions) -> Unit, onDismiss: () -> Unit) {
    val c = Noc.colors
    val engine = caps?.engine(if (options.engine == "auto") "fast" else options.engine)
    var o by remember { mutableStateOf(options) }
    var w by remember { mutableStateOf((options.width ?: 1024).toString()) }
    var h by remember { mutableStateOf((options.height ?: 1024).toString()) }
    var seed by remember { mutableStateOf(options.seed?.toString() ?: "") }
    NocSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 22.dp).verticalScroll(rememberScrollState())) {
            Text("Avançado", style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.padding(vertical = 8.dp))
            Text("Os padrões já foram ajustados para a GPU do seu PC. Mude só se quiser algo específico.", style = MaterialTheme.typography.bodySmall, color = c.text3)
            Spacer(Modifier.height(16.dp))

            Label("Tamanho")
            Row(verticalAlignment = Alignment.CenterVertically) {
                NumberBox(w, "Largura", Modifier.weight(1f)) { w = it; o = o.copy(format = "custom") }
                Text("×", color = c.text3, modifier = Modifier.padding(horizontal = 10.dp))
                NumberBox(h, "Altura", Modifier.weight(1f)) { h = it; o = o.copy(format = "custom") }
            }
            Text("Múltiplos de 16, de 256 a 2048. O PC ajusta ao limite do modelo.", style = MonoSmall, color = c.text3, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(16.dp))

            if (engine != null && options.engine != "auto") {
                val def = engine.stepPresets.getOrNull(1) ?: 9
                val steps = o.steps ?: def
                Label("Passos · $steps" + if (o.steps == null) " (padrão)" else "")
                Slider(
                    value = steps.toFloat(), onValueChange = { o = o.copy(steps = it.toInt()) },
                    valueRange = engine.stepsMin.toFloat()..engine.stepsMax.toFloat(), steps = (engine.stepsMax - engine.stepsMin - 1).coerceAtLeast(0),
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
                )
                if (engine.guidanceMin != null && engine.guidanceMax != null) {
                    val g = o.guidance ?: engine.guidanceDefault ?: engine.guidanceMin
                    Label("Guidance · ${"%.1f".format(g)}" + if (o.guidance == null) " (padrão)" else "")
                    Slider(
                        value = g.toFloat(), onValueChange = { o = o.copy(guidance = (it * 10).toInt() / 10.0) },
                        valueRange = engine.guidanceMin.toFloat()..engine.guidanceMax.toFloat(),
                        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
                    )
                }
            }

            Label("Seed")
            NumberBox(seed, "Aleatória", Modifier.fillMaxWidth()) { seed = it }
            Text("A mesma seed com o mesmo pedido repete a imagem. Vazio = uma nova a cada vez.", style = MonoSmall, color = c.text3, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(18.dp))
            PrimaryButton("Aplicar", Modifier.fillMaxWidth(), height = 48.dp) {
                val custom = o.format == "custom"
                onApply(
                    o.copy(
                        width = if (custom) w.toIntOrNull()?.coerceIn(256, 2048) else null,
                        height = if (custom) h.toIntOrNull()?.coerceIn(256, 2048) else null,
                        seed = seed.toLongOrNull()?.coerceIn(0, Int.MAX_VALUE.toLong() - 8),
                    ),
                )
                onDismiss()
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Restaurar padrões", style = MaterialTheme.typography.labelMedium, color = c.accent,
                modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(10.dp))
                    .pressable { onApply(options.copy(steps = null, guidance = null, seed = null, width = null, height = null, format = if (options.format == "custom") "square" else options.format)); onDismiss() }
                    .padding(12.dp),
            )
            Spacer(Modifier.height(12.dp))
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
