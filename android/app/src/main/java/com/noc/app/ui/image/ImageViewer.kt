package com.noc.app.ui.image

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.noc.app.data.db.CreationEntity
import com.noc.app.ui.Texts
import com.noc.app.ui.chat.NocSheet
import com.noc.app.ui.components.pressable
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.Noc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** O que o visualizador pode fazer além de ver (cada tela liga o que faz sentido). */
class ViewerActions(
    /** Editar, variar, expandir, melhorar, guiar, recriar (fase 4). */
    val creation: CreationActions? = null,
    /** Metadados completos do PC (prompt final, negativo, LoRAs, sampler...). */
    val loadMeta: (suspend (String) -> kotlinx.serialization.json.JsonObject?)? = null,
    val onRegenerate: ((CreationEntity) -> Unit)? = null,
    val onVariation: ((CreationEntity) -> Unit)? = null,
    val onReusePrompt: ((CreationEntity) -> Unit)? = null,
    val onOpenConversation: ((CreationEntity) -> Unit)? = null,
    val onFavorite: ((CreationEntity) -> Unit)? = null,
    val onDelete: ((CreationEntity) -> Unit)? = null,
)

/** Visualizador em tela cheia: pinça e toque duplo para zoom, arrastar quando ampliado, deslizar entre as imagens. */
@Composable
fun ImageViewer(items: List<CreationEntity>, start: Int, actions: ViewerActions, onDismiss: () -> Unit) {
    if (items.isEmpty()) { onDismiss(); return }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = start.coerceIn(0, items.lastIndex)) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<CreationEntity?>(null) }
    /** Folha de ações: vary | improve | guide */
    var actionSheet by remember { mutableStateOf<String?>(null) }
    val current = items.getOrNull(pager.currentPage) ?: items.first()
    val file = current.path?.let { File(it) }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok && file != null) scope.launch { save(context, file) }
        else if (!ok) Toast.makeText(context, "Sem permissão para salvar na galeria.", Toast.LENGTH_SHORT).show()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // tela cheia de verdade: a imagem vai por baixo das barras do sistema
        val window = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        androidx.compose.runtime.SideEffect {
            window?.let {
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it, false)
                it.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                it.setDimAmount(0f)
                @Suppress("DEPRECATION")
                run { it.statusBarColor = android.graphics.Color.TRANSPARENT; it.navigationBarColor = android.graphics.Color.TRANSPARENT }
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
                ZoomableImage(items[page], onTap = { chrome = !chrome }, onZoomChange = { if (page == pager.currentPage) zoomed = it })
            }
            AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Row(
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Round(Icons.Rounded.Close, "Fechar", onDismiss)
                    Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                        Text(current.modelName.ifBlank { "Imagem" }, style = MaterialTheme.typography.titleSmall, color = Color.White)
                        Text(
                            listOfNotNull("${current.width} × ${current.height}", if (items.size > 1) "${pager.currentPage + 1} de ${items.size}" else null).joinToString(" · "),
                            style = MonoSmall, color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    actions.onFavorite?.let { fav ->
                        Round(if (current.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favoritar") { fav(current) }
                    }
                    Round(Icons.Rounded.Info, "Detalhes") { details = current }
                }
            }
            AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Row(
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).navigationBarsPadding()
                        .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    actions.creation?.let { a ->
                        if (file != null) {
                            Labeled(Icons.Rounded.Brush, "Editar") { a.paint(current); onDismiss() }
                            Labeled(Icons.Rounded.Shuffle, "Variar") { actionSheet = "vary" }
                            Labeled(Icons.Rounded.OpenInFull, "Expandir") { a.expand(current); onDismiss() }
                            Labeled(Icons.Rounded.AutoFixHigh, "Melhorar") { actionSheet = "improve" }
                            Labeled(Icons.Rounded.Image, "Referência") { actionSheet = "guide" }
                        }
                        Labeled(Icons.Rounded.EditNote, "Reutilizar") { a.reusePrompt(current); onDismiss() }
                        if (current.op != "inpaint") Labeled(Icons.Rounded.Replay, "Recriar") { a.recreate(current); onDismiss() }
                    }
                    if (file != null) {
                        Labeled(Icons.Rounded.Download, "Salvar") {
                            if (ImageFiles.needsPermission(context)) storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            else scope.launch { save(context, file) }
                        }
                        Labeled(Icons.Rounded.Share, "Enviar") { ImageFiles.share(context, listOf(file)) }
                        Labeled(Icons.Rounded.ContentCopy, "Copiar") {
                            ImageFiles.copy(context, file)
                            Toast.makeText(context, "Imagem copiada", Toast.LENGTH_SHORT).show()
                        }
                    }
                    actions.onRegenerate?.let { f -> Labeled(Icons.Rounded.Refresh, "Refazer") { f(current); onDismiss() } }
                    if (actions.creation == null) {
                        actions.onVariation?.let { f -> Labeled(Icons.Rounded.Shuffle, "Variação") { f(current); onDismiss() } }
                        actions.onReusePrompt?.let { f -> Labeled(Icons.Rounded.EditNote, "Reutilizar") { f(current); onDismiss() } }
                    }
                    actions.onOpenConversation?.takeIf { current.conversationId != null }?.let { f -> Labeled(Icons.AutoMirrored.Rounded.Chat, "Conversa") { f(current); onDismiss() } }
                    actions.onDelete?.let { f -> Labeled(Icons.Rounded.Delete, "Excluir") { f(current); if (items.size <= 1) onDismiss() } }
                }
            }
        }
        details?.let { d -> DetailsSheet(d, actions.loadMeta) { details = null } }
        val act = actions.creation
        if (act != null) when (actionSheet) {
            "vary" -> VarySheet(onPick = { strength, count -> act.variation(current, strength, count); actionSheet = null; onDismiss() }, onDismiss = { actionSheet = null })
            "improve" -> ImproveSheet(
                onHd = { act.hd(current); actionSheet = null; onDismiss() }, onUp = { f -> act.upscale(current, f); actionSheet = null; onDismiss() },
                onDetail = { act.detail(current); actionSheet = null; onDismiss() }, onDismiss = { actionSheet = null },
            )
            "guide" -> GuideUseSheet(onPick = { g -> act.useAsGuide(current, g); actionSheet = null; onDismiss() }, onDismiss = { actionSheet = null })
        }
    }
}

private suspend fun save(context: android.content.Context, file: File) {
    val r = ImageFiles.saveToGallery(context, file)
    withContext(Dispatchers.Main) {
        Toast.makeText(context, if (r.isSuccess) "Salva na galeria (Imagens/Noc)" else "Não foi possível salvar: ${r.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun ZoomableImage(item: CreationEntity, onTap: () -> Unit, onZoomChange: (Boolean) -> Unit) {
    val path = item.path ?: item.thumbPath
    val bmp by produceState<Bitmap?>(null, path) { value = withContext(Dispatchers.IO) { path?.let { decodeSampled(it, 2048) } } }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    fun setScale(s: Float) {
        scale = s.coerceIn(1f, 6f)
        if (scale == 1f) offset = Offset.Zero
        onZoomChange(scale > 1.01f)
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { at ->
                        if (scale > 1.01f) setScale(1f)
                        else {
                            setScale(2.5f)
                            offset = Offset((size.width / 2f - at.x) * 1.5f, (size.height / 2f - at.y) * 1.5f)
                        }
                    },
                )
            }
            .pointerInput(item.id) {
                // pinça sempre; arrastar só quando ampliada (senão o gesto é do pager)
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pointers = event.changes.count { it.pressed }
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (pointers >= 2 || scale > 1.01f) {
                            if (zoom != 1f) setScale(scale * zoom)
                            if (scale > 1.01f) {
                                val maxX = size.width * (scale - 1) / 2
                                val maxY = size.height * (scale - 1) / 2
                                offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .semantics { contentDescription = "Imagem ampliada. Toque duas vezes para aproximar." },
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let {
            Image(
                it.asImageBitmap(), null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
            )
        }
    }
}

@Composable
private fun DetailsSheet(c: CreationEntity, loadMeta: (suspend (String) -> kotlinx.serialization.json.JsonObject?)?, onDismiss: () -> Unit) {
    val col = Noc.colors
    val meta by produceState<kotlinx.serialization.json.JsonObject?>(null, c.id) { value = loadMeta?.invoke(c.id) }
    fun m(k: String): String? = (meta?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
    NocSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 22.dp).verticalScroll(rememberScrollState())) {
            Text("Detalhes", style = MaterialTheme.typography.headlineLarge, color = col.text, modifier = Modifier.padding(vertical = 8.dp))
            SelectionContainer {
                Column {
                    Field("Pedido", c.prompt.ifBlank { m("prompt") ?: "—" })
                    val final = m("finalPrompt") ?: c.finalPrompt
                    if (!final.isNullOrBlank() && final != c.prompt) Field("Prompt usado pelo modelo" + (strategyLabel(m("strategy") ?: c.strategy)?.let { " · $it" } ?: ""), final)
                    (m("negative") ?: c.negative)?.takeIf { it.isNotBlank() }?.let { Field("Prompt negativo", it) }
                    val hash = m("modelSha256")?.let { "hash ${it.take(12)}" } ?: m("modelRevision")?.let { "revisão ${it.take(12)}" }
                    Field("Modelo", listOfNotNull(c.modelName.ifBlank { c.model }, m("modelVersion"), hash).joinToString(" · "))
                    val loras = (meta?.get("loras") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
                        ?.joinToString("\n") { l -> "${(l["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content} · ${(l["weight"] as? kotlinx.serialization.json.JsonPrimitive)?.content}" }
                        ?.takeIf { it.isNotBlank() } ?: c.loras
                    if (!loras.isNullOrBlank()) Field("LoRAs", loras)
                    opLabel(m("op") ?: c.op)?.let { Field("Operação", it) }
                    Field("Tamanho", "${c.width} × ${c.height}")
                    c.seed?.let { Field("Seed", it.toString()) }
                    (m("sampler") ?: c.sampler)?.let { smp -> Field("Sampler", smp + (m("schedule")?.let { " · $it" } ?: "")) }
                    c.steps?.let { Field("Passos", it.toString()) }
                    c.guidance?.takeIf { it > 0 }?.let { Field(if (m("engine") == "sdxl") "CFG" else "Guidance", "%.1f".format(it)) }
                    m("clipSkip")?.let { Field("Clip skip", it) }
                    c.genMs?.let { Field("Tempo no PC", Texts.seconds(it)) }
                    Field("Criada", Texts.relative(c.createdAt))
                    if (c.bytes > 0) Field("Arquivo", "PNG · " + Texts.bytes(c.bytes))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun strategyLabel(s: String?) = when (s) {
    "tags" -> "tags do modelo"; "pony" -> "score tags do Pony"; "raw" -> "RAW (sem mudanças)"; "recreate" -> "recriada"; "natural" -> "descrição em inglês"; else -> null
}

private fun opLabel(op: String?) = when (op) {
    "img2img" -> "Variação"; "inpaint" -> "Edição com pincel"; "outpaint" -> "Expansão"; "upscale" -> "Ampliação"; "detail" -> "Rostos e mãos refeitos"
    "hd" -> "Alta definição"; else -> null
}

@Composable
private fun VarySheet(onPick: (Double, Int) -> Unit, onDismiss: () -> Unit) {
    val col = Noc.colors
    NocSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text("Variar", style = MaterialTheme.typography.headlineLarge, color = col.text, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            OptionRow(Icons.Rounded.Shuffle, "Variação sutil", "Mesma cena, pequenos detalhes diferentes · 2 versões") { onPick(0.35, 2) }
            OptionRow(Icons.Rounded.Shuffle, "Variação forte", "Mesma ideia, composição mais livre · 2 versões") { onPick(0.6, 2) }
            OptionRow(Icons.Rounded.Shuffle, "Quatro variações", "Sutis, para escolher a melhor") { onPick(0.4, 4) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ImproveSheet(onHd: () -> Unit, onUp: (Int) -> Unit, onDetail: () -> Unit, onDismiss: () -> Unit) {
    val col = Noc.colors
    NocSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text("Melhorar", style = MaterialTheme.typography.headlineLarge, color = col.text, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            OptionRow(Icons.Rounded.AutoFixHigh, "Alta definição", "Amplia 2x e refaz os detalhes finos com o modelo", onClick = onHd)
            OptionRow(Icons.Rounded.Face, "Rostos e mãos", "Encontra rostos e mãos e refaz cada um com mais detalhe", onClick = onDetail)
            OptionRow(Icons.Rounded.OpenInFull, "Ampliar 2x", "Mais pixels, rápido, sem mudar nada", onClick = { onUp(2) })
            OptionRow(Icons.Rounded.OpenInFull, "Ampliar 4x", "Para imprimir ou recortar", onClick = { onUp(4) })
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun GuideUseSheet(onPick: (Guide) -> Unit, onDismiss: () -> Unit) {
    val col = Noc.colors
    NocSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text("Usar como guia", style = MaterialTheme.typography.headlineLarge, color = col.text, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            OptionRow(Icons.Rounded.Image, Guide.REFERENCE.label, Guide.REFERENCE.sub) { onPick(Guide.REFERENCE) }
            OptionRow(Icons.Rounded.AccessibilityNew, Guide.POSE.label, Guide.POSE.sub) { onPick(Guide.POSE) }
            OptionRow(Icons.Rounded.Terrain, Guide.DEPTH.label, Guide.DEPTH.sub) { onPick(Guide.DEPTH) }
            OptionRow(Icons.Rounded.Gesture, Guide.EDGES.label, Guide.EDGES.sub) { onPick(Guide.EDGES) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    val c = Noc.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = c.text3)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = c.text)
    }
}

@Composable
private fun Round(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).pressable(onClick = onClick).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Labeled(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).pressable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp).width(72.dp)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
    }
}
