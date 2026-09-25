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
import androidx.compose.material.icons.rounded.Close
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
                    actions.onVariation?.let { f -> Labeled(Icons.Rounded.Shuffle, "Variação") { f(current); onDismiss() } }
                    actions.onReusePrompt?.let { f -> Labeled(Icons.Rounded.EditNote, "Reutilizar") { f(current); onDismiss() } }
                    actions.onOpenConversation?.takeIf { current.conversationId != null }?.let { f -> Labeled(Icons.AutoMirrored.Rounded.Chat, "Conversa") { f(current); onDismiss() } }
                    actions.onDelete?.let { f -> Labeled(Icons.Rounded.Delete, "Excluir") { f(current); if (items.size <= 1) onDismiss() } }
                }
            }
        }
        details?.let { d -> DetailsSheet(d) { details = null } }
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
private fun DetailsSheet(c: CreationEntity, onDismiss: () -> Unit) {
    val col = Noc.colors
    NocSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 22.dp).verticalScroll(rememberScrollState())) {
            Text("Detalhes", style = MaterialTheme.typography.headlineLarge, color = col.text, modifier = Modifier.padding(vertical = 8.dp))
            SelectionContainer {
                Column {
                    Field("Pedido", c.prompt)
                    if (!c.finalPrompt.isNullOrBlank() && c.finalPrompt != c.prompt) Field("Prompt usado pelo modelo", c.finalPrompt)
                    Field("Modelo", c.modelName.ifBlank { c.model })
                    Field("Tamanho", "${c.width} × ${c.height}")
                    c.seed?.let { Field("Seed", it.toString()) }
                    c.steps?.let { Field("Passos", it.toString()) }
                    c.guidance?.takeIf { it > 0 }?.let { Field("Guidance", "%.1f".format(it)) }
                    c.genMs?.let { Field("Tempo no PC", Texts.seconds(it)) }
                    Field("Criada", Texts.relative(c.createdAt))
                    if (c.bytes > 0) Field("Arquivo", "PNG · " + Texts.bytes(c.bytes))
                }
            }
            Spacer(Modifier.height(20.dp))
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
