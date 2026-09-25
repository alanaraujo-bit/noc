package com.noc.app.ui.image

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.noc.app.AppContainer
import com.noc.app.chat.ImageEdit
import com.noc.app.chat.ImageInput
import com.noc.app.chat.ImageOptions
import com.noc.app.data.db.CreationEntity
import com.noc.app.ui.Routes
import com.noc.app.ui.components.Chip
import com.noc.app.ui.components.PrimaryButton
import com.noc.app.ui.components.TopBar
import com.noc.app.ui.components.pressable
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.Noc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Um traço do pincel, em coordenadas da imagem (independe do tamanho da tela). */
private data class Stroke2(val points: List<Offset>, val radius: Float, val erase: Boolean)

/**
 * Editor visual: "paint" = pintar a área a mudar e dizer o que colocar (inpaint); "expand" = aumentar a imagem para os
 * lados (outpaint). O resultado entra na conversa da imagem, como qualquer pedido — com fila, progresso e notificação.
 */
@Composable
fun ImageEditorScreen(container: AppContainer, nav: NavHostController, creationId: String, mode: String) {
    val c = Noc.colors
    val scope = rememberCoroutineScope()
    val creation by produceState<CreationEntity?>(null, creationId) { value = container.db.creations().get(creationId) }
    val cr = creation
    val caps by container.chat.images.caps.collectAsState()
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(cr?.id) { if (cr != null && mode == "expand") prompt = cr.prompt }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar(if (mode == "expand") "Expandir imagem" else "Editar com pincel", onBack = { nav.popBackStack() },
            subtitle = cr?.let { "${it.modelName.ifBlank { "Imagem" }} · ${it.width} × ${it.height}" })
        if (cr == null) return@Column
        val path = cr.path ?: cr.thumbPath
        val bmp by produceState<Bitmap?>(null, path) { value = withContext(Dispatchers.IO) { path?.let { decodeSampled(it, 1600) } } }
        val model = caps?.model(cr.model)
        // o PC escolhe quem faz a edição: o próprio modelo, ou um especialista quando ele não sabe (ex.: expandir no Z-Image)
        val op = if (mode == "expand") "outpaint" else "inpaint"
        val doer = when {
            model == null || model.caps.isEmpty() || model.can(op) -> model
            else -> caps?.models?.filter { it.ready && it.can(op) }?.let { ready ->
                val want = when (model.category) { "anime" -> "pony-v6-xl"; "illustration" -> "noobai-xl-vpred-1"; else -> "realvisxl-v5" }
                ready.firstOrNull { it.id == want } ?: ready.firstOrNull { it.sdxl } ?: ready.firstOrNull()
            }
        }
        val canEdit = model == null || model.caps.isEmpty() || doer != null
        val via = doer?.takeIf { model != null && it.id != model.id }?.name
        if (via != null) Text("Esta edição será feita pelo $via, que faz melhor que o ${model?.name}.", style = MaterialTheme.typography.bodySmall,
            color = Noc.colors.text3, modifier = Modifier.padding(horizontal = 16.dp))
        if (mode == "expand") ExpandEditor(cr, bmp, prompt, { prompt = it }, busy, canEdit) { expand ->
            busy = true
            scope.launch {
                val convId = container.chat.images.edit(cr, ImageEdit("outpaint", source = ImageInput.pc(cr.id, cr.width, cr.height, cr.thumbPath), expand = expand), prompt, ImageOptions(),
                    newConversation = { container.chat.newConversation() })
                nav.navigate(Routes.chat(convId)) { popUpTo(Routes.IMAGE_EDIT) { inclusive = true } }
            }
        } else PaintEditor(cr, bmp, prompt, { prompt = it }, busy, canEdit) { maskBmp, strength ->
            busy = true
            scope.launch {
                val mask = container.chat.images.inputFromPng(maskBmp)
                val convId = container.chat.images.edit(cr, ImageEdit("inpaint", source = ImageInput.pc(cr.id, cr.width, cr.height, cr.thumbPath), mask = mask, strength = strength),
                    prompt, ImageOptions(), newConversation = { container.chat.newConversation() })
                nav.navigate(Routes.chat(convId)) { popUpTo(Routes.IMAGE_EDIT) { inclusive = true } }
            }
        }
    }
}

@Composable
private fun PaintEditor(
    cr: CreationEntity, bmp: Bitmap?, prompt: String, onPrompt: (String) -> Unit, busy: Boolean, canEdit: Boolean,
    onApply: (Bitmap, Double) -> Unit,
) {
    val c = Noc.colors
    val strokes = remember { mutableStateListOf<Stroke2>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var brush by remember { mutableFloatStateOf(0.06f) } // fração do lado maior
    var erase by remember { mutableStateOf(false) }
    var strength by remember { mutableFloatStateOf(0.95f) }
    val iw = cr.width.toFloat().coerceAtLeast(1f)
    val ih = cr.height.toFloat().coerceAtLeast(1f)
    val radiusPx = brush * max(iw, ih)

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val bw = with(density) { maxWidth.toPx() }
            val bh = with(density) { maxHeight.toPx() }
            val k = min(bw / iw, bh / ih)
            val dw = iw * k
            val dh = ih * k
            val ox = (bw - dw) / 2
            val oy = (bh - dh) / 2
            fun toImage(p: Offset) = Offset(((p.x - ox) / k).coerceIn(0f, iw), ((p.y - oy) / k).coerceIn(0f, ih))
            Box(Modifier.fillMaxSize().pointerInput(k, brush, erase) {
                detectDragGestures(
                    onDragStart = { current = listOf(toImage(it)) },
                    onDrag = { ch, _ -> current = current + toImage(ch.position) },
                    onDragEnd = { if (current.isNotEmpty()) strokes.add(Stroke2(current, radiusPx, erase)); current = emptyList() },
                    onDragCancel = { current = emptyList() },
                )
            }.pointerInput(k, brush, erase) {
                detectTapGestures { p -> strokes.add(Stroke2(listOf(toImage(p)), radiusPx, erase)) }
            }.semantics { contentDescription = "Pinte a área que deve mudar" }) {
                bmp?.let {
                    Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                Canvas(Modifier.fillMaxSize()) {
                    val all = strokes + if (current.isNotEmpty()) listOf(Stroke2(current, radiusPx, erase)) else emptyList()
                    // camada de máscara: traços que apagam cortam os anteriores
                    drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height), androidx.compose.ui.graphics.Paint().apply { alpha = 0.55f })
                    for (s in all) {
                        val color = if (s.erase) Color.Transparent else Color(0xFFFF4D6D)
                        val blend = if (s.erase) androidx.compose.ui.graphics.BlendMode.Clear else androidx.compose.ui.graphics.BlendMode.SrcOver
                        if (s.points.size == 1) {
                            val p = s.points[0]
                            drawCircle(color, radius = s.radius * k, center = Offset(ox + p.x * k, oy + p.y * k), blendMode = blend)
                        } else {
                            val path = Path().apply {
                                moveTo(ox + s.points[0].x * k, oy + s.points[0].y * k)
                                s.points.drop(1).forEach { lineTo(ox + it.x * k, oy + it.y * k) }
                            }
                            drawPath(path, color, style = Stroke(width = s.radius * 2 * k, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = blend)
                        }
                    }
                    drawContext.canvas.restore()
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (!canEdit) Text("Nenhum modelo pronto no PC edita com pincel ainda.", style = MaterialTheme.typography.bodySmall, color = c.err)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToolButton(Icons.Rounded.Brush, "Pincel", !erase) { erase = false }
                ToolButton(Icons.Rounded.CleaningServices, "Borracha", erase) { erase = true }
                ToolButton(Icons.AutoMirrored.Rounded.Undo, "Desfazer", false, enabled = strokes.isNotEmpty()) { strokes.removeAt(strokes.lastIndex) }
                ToolButton(Icons.Rounded.Delete, "Limpar", false, enabled = strokes.isNotEmpty()) { strokes.clear() }
                Spacer(Modifier.width(4.dp))
                Slider(value = brush, onValueChange = { brush = it }, valueRange = 0.015f..0.15f, modifier = Modifier.weight(1f).semantics { contentDescription = "Tamanho do pincel" },
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent))
            }
            PromptBox(prompt, "O que colocar na área pintada (ex.: um gorro vermelho)", onPrompt)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Quanto mudar · ${(strength * 100).roundToInt()}%", style = MonoSmall, color = c.text2, modifier = Modifier.width(140.dp))
                Slider(value = strength, onValueChange = { strength = it }, valueRange = 0.4f..1f, modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent))
            }
            PrimaryButton(if (busy) "Enviando…" else "Aplicar edição", Modifier.fillMaxWidth(), height = 50.dp,
                enabled = !busy && canEdit && strokes.any { !it.erase } && prompt.isNotBlank()) {
                onApply(renderMask(strokes, cr.width, cr.height), strength.toDouble())
            }
            if (strokes.none { !it.erase }) Text("Pinte com o dedo a parte que deve mudar.", style = MaterialTheme.typography.bodySmall, color = c.text3, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** A máscara no tamanho real da imagem: branco = mudar, preto = manter. */
private fun renderMask(strokes: List<Stroke2>, w: Int, h: Int): Bitmap {
    val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(bmp)
    cv.drawColor(android.graphics.Color.BLACK)
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true; style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
    }
    val dot = android.graphics.Paint().apply { isAntiAlias = true; style = android.graphics.Paint.Style.FILL }
    for (s in strokes) {
        val color = if (s.erase) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        paint.color = color; dot.color = color
        paint.strokeWidth = s.radius * 2
        if (s.points.size == 1) cv.drawCircle(s.points[0].x, s.points[0].y, s.radius, dot)
        else {
            val p = android.graphics.Path()
            p.moveTo(s.points[0].x, s.points[0].y)
            s.points.drop(1).forEach { p.lineTo(it.x, it.y) }
            cv.drawPath(p, paint)
        }
    }
    return bmp
}

@Composable
private fun ExpandEditor(
    cr: CreationEntity, bmp: Bitmap?, prompt: String, onPrompt: (String) -> Unit, busy: Boolean, canEdit: Boolean,
    onApply: (List<Int>) -> Unit,
) {
    val c = Noc.colors
    val w = cr.width
    val h = cr.height
    // presets: formato final ou lados
    val presets = listOf(
        "Paisagem 16:9" to 16.0 / 9, "Retrato 9:16" to 9.0 / 16, "Quadrado" to 1.0, "Retrato 4:5" to 4.0 / 5,
        "Para os lados" to -1.0, "Para cima e baixo" to -2.0, "Todos os lados" to -3.0,
    ).filter { (_, r) -> r < 0 || kotlin.math.abs(r - w.toDouble() / h) > 0.05 }
    var choice by remember { mutableStateOf(presets.first().first) }
    val ratio = presets.firstOrNull { it.first == choice }?.second ?: -1.0
    val expand = remember(choice) { expandFor(w, h, ratio) }
    val nw = w + expand[0] + expand[2]
    val nh = h + expand[1] + expand[3]
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val bw = with(density) { maxWidth.toPx() }
            val bh = with(density) { maxHeight.toPx() }
            val k = min(bw / nw, bh / nh)
            val frameW = with(density) { (nw * k).toDp() }
            val frameH = with(density) { (nh * k).toDp() }
            Box(Modifier.size(frameW, frameH).clip(RoundedCornerShape(4.dp)).background(c.surface2)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(c.accent.copy(alpha = 0.08f))
                    drawRect(c.accent, style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))))
                }
                bmp?.let {
                    Image(
                        it.asImageBitmap(), null, contentScale = ContentScale.FillBounds,
                        modifier = Modifier.offset { IntOffset((expand[0] * k).roundToInt(), (expand[1] * k).roundToInt()) }
                            .size(with(density) { (w * k).toDp() }, with(density) { (h * k).toDp() }),
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (!canEdit) Text("Nenhum modelo pronto no PC expande imagens ainda.", style = MaterialTheme.typography.bodySmall, color = c.err)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.forEach { (label, _) -> Chip(label, selected = choice == label) { choice = label } }
            }
            Text("$w × $h → $nw × $nh", style = MonoSmall, color = c.text3, modifier = Modifier.padding(vertical = 6.dp))
            PromptBox(prompt, "O que aparece na área nova (opcional)", onPrompt)
            PrimaryButton(if (busy) "Enviando…" else "Expandir", Modifier.fillMaxWidth(), height = 50.dp, enabled = !busy && canEdit) { onApply(expand) }
        }
    }
}

/** Pixels a acrescentar (esquerda, cima, direita, baixo), múltiplos de 8, até ~1,5x em cada eixo. */
private fun expandFor(w: Int, h: Int, ratio: Double): List<Int> {
    fun r8(v: Double) = (v / 8).roundToInt() * 8
    return when {
        ratio == -1.0 -> { val x = r8(w * 0.25); listOf(x, 0, x, 0) }
        ratio == -2.0 -> { val y = r8(h * 0.25); listOf(0, y, 0, y) }
        ratio == -3.0 -> { val x = r8(w * 0.2); val y = r8(h * 0.2); listOf(x, y, x, y) }
        ratio > w.toDouble() / h -> { val nw = h * ratio; val x = r8((nw - w) / 2).coerceAtMost(r8(w * 0.75)); listOf(x, 0, x, 0) }
        else -> { val nh = w / ratio; val y = r8((nh - h) / 2).coerceAtMost(r8(h * 0.75)); listOf(0, y, 0, y) }
    }
}

@Composable
private fun PromptBox(value: String, placeholder: String, onChange: (String) -> Unit) {
    val c = Noc.colors
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(14.dp)) {
        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.text3)
        BasicTextField(value, { onChange(it.take(2000)) }, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text), cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = placeholder })
    }
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Noc.colors
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) c.accentSoft else c.surface).pressable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (!enabled) c.text3 else if (selected) c.accent else c.text2, modifier = Modifier.size(20.dp)) }
}
