package com.noc.app.ui.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noc.app.chat.GenImage
import com.noc.app.chat.ImageJob
import com.noc.app.chat.LiveReply
import com.noc.app.chat.MessageStatus
import com.noc.app.data.db.MessageEntity
import com.noc.app.ui.Texts
import com.noc.app.ui.chat.BranchSwitcher
import com.noc.app.ui.chat.MessageUi
import com.noc.app.ui.components.pressable
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.Noc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Ações de uma imagem na conversa. */
class ImageActions(
    val onOpen: (index: Int) -> Unit,
    val onShare: (GenImage) -> Unit,
    val onSave: (GenImage) -> Unit,
    val onRegenerate: () -> Unit,
    val onVariation: () -> Unit,
    val onReusePrompt: () -> Unit,
    val onRetry: () -> Unit,
    val onBranch: (Int) -> Unit,
)

/** O pedido do usuário ("Criar imagem"): o texto como ele escreveu, com um rótulo discreto. */
@Composable
fun ImageRequestBubble(ui: MessageUi, onLongPress: () -> Unit, onBranch: (Int) -> Unit) {
    val c = Noc.colors
    val m = ui.entity
    val job = remember(m.imageJson) { ImageJob.decode(m.imageJson) }
    val o = job?.options
    val edit = job?.edit
    Column(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp, end = 4.dp)) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = c.accent, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                if (edit != null) listOfNotNull(edit.label, edit.count.takeIf { it > 1 }?.let { "$it versões" }).joinToString(" · ")
                else listOfNotNull(
                    "Criar imagem", o?.let { if (it.model != null) "Especialista" else com.noc.app.chat.ImageOptions.engineLabel(it.engine).substringAfter(' ') },
                    o?.count?.takeIf { it > 1 }?.let { "$it imagens" }, "melhorada".takeIf { o?.enhance == true }, "RAW".takeIf { o?.raw == true },
                    o?.loras?.size?.takeIf { it > 0 }?.let { if (it == 1) "1 LoRA" else "$it LoRAs" },
                    "com guia".takeIf { o?.reference != null || o?.control != null },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = c.text3,
            )
        }
        // edição: miniatura da imagem de origem, para saber de onde veio
        edit?.source?.thumb?.let { t ->
            val bmp by produceState<android.graphics.Bitmap?>(null, t) { value = withContext(Dispatchers.IO) { decodeSampled(t, 240) } }
            bmp?.let {
                Image(it.asImageBitmap(), "Imagem de origem", contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(bottom = 6.dp).size(72.dp).clip(RoundedCornerShape(12.dp)))
            }
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 8.dp))
                .background(c.surface2)
                .pressable(onLongClick = onLongPress) {}
                .padding(horizontal = 16.dp, vertical = 11.dp),
        ) {
            Text(m.content, style = MaterialTheme.typography.bodyLarge, color = c.text)
        }
        if (ui.branchCount > 1) Row(Modifier.padding(top = 4.dp)) { BranchSwitcher(ui, onBranch) }
    }
}

/**
 * A resposta de imagem: enquanto o PC trabalha, a etapa real ("Preparando gerador…", "Criando sua imagem · 45%")
 * com a prévia verdadeira do modelo; pronta, a imagem (ou a grade) com metadados discretos e ações.
 */
@Composable
fun ImageMessage(ui: MessageUi, live: StateFlow<LiveReply>?, online: Boolean, actions: ImageActions, onLongPress: () -> Unit) {
    val c = Noc.colors
    val m = ui.entity
    val finalStatus = m.status != MessageStatus.WAITING && m.status != MessageStatus.STREAMING
    val state = live?.collectAsState()?.value?.takeUnless { finalStatus }
    val job = remember(m.imageJson) { ImageJob.decode(m.imageJson) }
    val count = job?.options?.count ?: 1
    val w = job?.width ?: formatSize(job)?.first ?: 1024
    val h = job?.height ?: formatSize(job)?.second ?: 1024
    val images = job?.images.orEmpty()

    Column(Modifier.fillMaxWidth().pressable(onLongClick = onLongPress) {}) {
        when {
            state != null -> Working(state, online, images, count, w, h, actions)
            m.status == MessageStatus.DONE && images.isNotEmpty() -> Done(m, job!!, actions)
            m.status == MessageStatus.CANCELLED -> Note("Geração cancelada.", actionLabel = "Gerar de novo", onAction = actions.onRetry, images = images, job = job, actions = actions)
            m.status == MessageStatus.WAITING || m.status == MessageStatus.STREAMING -> Working(null, online, images, count, w, h, actions)
            else -> Failed(m, job, actions)
        }
        if (ui.branchCount > 1) Row(Modifier.padding(top = 6.dp)) { BranchSwitcher(ui, actions.onBranch) }
    }
}

private fun formatSize(job: ImageJob?): Pair<Int, Int>? = when (job?.options?.format) {
    "portrait" -> 832 to 1216
    "landscape" -> 1216 to 832
    "story" -> 768 to 1344
    "wallpaper" -> 1344 to 768
    "custom" -> (job.options.width ?: 1024) to (job.options.height ?: 1024)
    else -> 1024 to 1024
}

@Composable
private fun Working(state: LiveReply?, online: Boolean, images: List<GenImage>, count: Int, w: Int, h: Int, actions: ImageActions) {
    val c = Noc.colors
    val label = state?.describe(online) ?: if (online) "Preparando gerador…" else "Continuando no seu PC…"
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val secs = state?.let { ((now - it.startedAt) / 1000).coerceAtLeast(0) }
    // imagens que já chegaram aparecem; as que faltam ficam como moldura com a prévia/andamento
    Grid(count, w, h) { i, mod ->
        val img = images.getOrNull(i)
        if (img?.thumb != null || img?.local != null) ImageTile(img, mod) { actions.onOpen(i) }
        else Placeholder(mod, preview = state?.preview?.takeIf { (state.imageIndex) == i }, active = state == null || state.imageIndex == i)
    }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.text2, modifier = Modifier.weight(1f))
        secs?.let { Text(Texts.seconds(it * 1000), style = MonoSmall, color = c.text3) }
    }
    state?.imageProgress?.let { p ->
        val anim by animateFloatAsState(p, tween(400), label = "p")
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.surface2)) {
            Box(Modifier.fillMaxWidth(anim).height(3.dp).background(c.accent))
        }
    }
}

@Composable
private fun Grid(count: Int, w: Int, h: Int, cell: @Composable (Int, Modifier) -> Unit) {
    val ratio = (w.toFloat() / h.toFloat()).coerceIn(0.4f, 2.5f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // imagem única em retrato não ocupa a tela inteira
        val single = if (ratio < 1f) Modifier.fillMaxWidth(0.78f) else Modifier.fillMaxWidth()
        when (count) {
            1 -> cell(0, single.aspectRatio(ratio).clip(RoundedCornerShape(18.dp)))
            2 -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0 until 2) cell(i, Modifier.weight(1f).aspectRatio(ratio).clip(RoundedCornerShape(14.dp)))
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in 0 until 2) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (col in 0 until 2) cell(r * 2 + col, Modifier.weight(1f).aspectRatio(ratio).clip(RoundedCornerShape(14.dp)))
                }
            }
        }
    }
}

@Composable
private fun Placeholder(mod: Modifier, preview: String?, active: Boolean) {
    val c = Noc.colors
    val t = rememberInfiniteTransition(label = "ph")
    val a by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "a")
    val bmp = remember(preview) {
        preview?.let { p -> runCatching { Base64.decode(p, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull() }
    }
    Box(mod.background(c.surface2), contentAlignment = Alignment.Center) {
        Crossfade(bmp, label = "prev") { b ->
            if (b != null) {
                // prévia real do modelo (baixa resolução): desfocada, fica clara quando a imagem chega
                Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(10.dp).alpha(0.9f))
            } else {
                Box(Modifier.fillMaxSize().alpha(if (active) a else 0.6f).background(c.surface3))
            }
        }
        if (bmp == null) Icon(Icons.Rounded.AutoAwesome, null, tint = c.text3, modifier = Modifier.size(26.dp).alpha(if (active) a else 0.5f))
    }
}

@Composable
fun ImageTile(img: GenImage, mod: Modifier, onClick: () -> Unit) {
    val c = Noc.colors
    val path = img.thumb ?: img.local
    val bmp by produceState<Bitmap?>(null, path) { value = withContext(Dispatchers.IO) { path?.let { decodeSampled(it, 900) } } }
    Box(mod.background(c.surface2).pressable(onClick = onClick).semantics { contentDescription = "Imagem gerada. Toque para abrir." }) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun Done(m: MessageEntity, job: ImageJob, actions: ImageActions) {
    val c = Noc.colors
    val images = job.images
    Grid(images.size.coerceIn(1, 4).let { if (it == 3) 4 else it }, (images.firstOrNull { it.width > 0 }?.width ?: job.width ?: 1024), (images.firstOrNull { it.width > 0 }?.height ?: job.height ?: 1024)) { i, mod ->
        val img = images.getOrNull(i)
        when {
            img == null -> Box(mod)
            img.thumb != null || img.local != null -> ImageTile(img, mod) { actions.onOpen(i) }
            img.gone -> Box(mod.background(c.surface2), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.BrokenImage, null, tint = c.text3) }
            else -> Placeholder(mod, null, active = true)
        }
    }
    Spacer(Modifier.height(8.dp))
    // metadados discretos: tamanho · modelo · tempo
    val meta = listOfNotNull(
        (images.firstOrNull { it.width > 0 }?.let { "${it.width} × ${it.height}" } ?: job.width?.let { "$it × ${job.height}" }), job.modelName ?: m.modelName,
        (job.wallMs ?: job.generateMs)?.let { "gerada em " + Texts.seconds(it) },
        "baixando…".takeIf { job.pending },
    ).joinToString(" · ")
    Text(meta, style = MonoSmall, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        val first = images.firstOrNull { it.local != null }
        if (first != null) {
            Action(Icons.Rounded.Share, "Compartilhar") { actions.onShare(first) }
            Action(Icons.Rounded.Download, "Salvar na galeria") { actions.onSave(first) }
        }
        Action(Icons.Rounded.Refresh, "Gerar novamente") { actions.onRegenerate() }
        Action(Icons.Rounded.Shuffle, "Criar variação") { actions.onVariation() }
        Text("Reutilizar prompt", style = MaterialTheme.typography.labelMedium, color = c.text2,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable(onClick = actions.onReusePrompt).padding(horizontal = 10.dp, vertical = 10.dp))
    }
}

@Composable
private fun Action(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    val c = Noc.colors
    Box(Modifier.size(38.dp).clip(CircleShape).pressable(onClick = onClick).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = c.text2, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun Failed(m: MessageEntity, job: ImageJob?, actions: ImageActions) {
    val detail = job?.detail ?: m.error?.substringAfter('|', "")?.takeIf { it.isNotBlank() } ?: Texts.imageError(m.error?.substringBefore('|'))
    Note(detail, actionLabel = "Tentar novamente", onAction = actions.onRetry, images = job?.images.orEmpty(), job = job, actions = actions, error = true)
}

@Composable
private fun Note(text: String, actionLabel: String, onAction: () -> Unit, images: List<GenImage>, job: ImageJob?, actions: ImageActions, error: Boolean = false) {
    val c = Noc.colors
    if (images.any { it.thumb != null || it.local != null } && job != null) {
        Grid(images.size.coerceIn(1, 4).let { if (it == 3) 4 else it }, (images.firstOrNull { it.width > 0 }?.width ?: job.width ?: 1024), (images.firstOrNull { it.width > 0 }?.height ?: job.height ?: 1024)) { i, mod ->
            images.getOrNull(i)?.let { if (it.thumb != null || it.local != null) ImageTile(it, mod) { actions.onOpen(i) } else Box(mod) } ?: Box(mod)
        }
        Spacer(Modifier.height(8.dp))
    }
    Row(
        Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(16.dp)).background(if (error) c.errSoft else c.surface)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (error) { Icon(Icons.Rounded.ErrorOutline, null, tint = c.err, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)) }
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.text, modifier = Modifier.weight(1f, fill = false).padding(vertical = 8.dp))
        Spacer(Modifier.width(8.dp))
        Text(actionLabel, style = MaterialTheme.typography.labelMedium, color = c.accent,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable(onClick = onAction).padding(horizontal = 10.dp, vertical = 10.dp))
    }
}

/** Decodifica com amostragem (miniaturas leves na lista; o original só no visualizador). */
fun decodeSampled(path: String, maxSide: Int): Bitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
}.getOrNull()
