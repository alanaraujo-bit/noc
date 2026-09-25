package com.noc.app.ui.image

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.noc.app.AppContainer
import com.noc.app.data.db.CreationEntity
import com.noc.app.data.db.Search
import com.noc.app.ui.Routes
import com.noc.app.ui.chat.NocSheet
import com.noc.app.ui.components.EmptyState
import com.noc.app.ui.components.PrimaryButton
import com.noc.app.ui.components.SecondaryButton
import com.noc.app.ui.components.TopBar
import com.noc.app.ui.components.pressable
import com.noc.app.ui.theme.Noc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Criações: todas as imagens feitas no PC, guardadas neste celular. */
@Composable
fun CreationsScreen(container: AppContainer, nav: NavHostController) {
    val c = Noc.colors
    val scope = rememberCoroutineScope()
    val all by container.db.creations().observeAll().collectAsState(initial = null)
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var viewer by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf<CreationEntity?>(null) }

    val models = all.orEmpty().map { it.modelName }.filter { it.isNotBlank() }.distinct()
    val q = Search.normalize(query.trim())
    val shown = all.orEmpty().filter { cr ->
        (filter == "all" || (filter == "fav" && cr.favorite) || cr.modelName == filter) &&
            (q.isEmpty() || Search.normalize(cr.prompt).contains(q) || Search.normalize(cr.finalPrompt ?: "").contains(q))
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        TopBar("Criações", onBack = { nav.popBackStack() }, subtitle = all?.let { if (it.isEmpty()) null else "${it.size} imagens" })
        if (all?.isNotEmpty() == true) {
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = c.text3, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Buscar pelo pedido", style = MaterialTheme.typography.bodyLarge, color = c.text3)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                        cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Buscar criações" })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip("Todas", filter == "all") { filter = "all" }
                FilterChip("Favoritas", filter == "fav") { filter = "fav" }
                models.forEach { m -> FilterChip(m, filter == m) { filter = m } }
            }
        }
        when {
            all == null -> Unit
            all!!.isEmpty() -> EmptyState(
                Icons.Rounded.AutoAwesome, "Nenhuma criação ainda",
                "Peça uma imagem numa conversa (toque em + e depois em Criar imagem). Ela é feita no seu PC e fica guardada aqui.",
                action = { PrimaryButton("Criar imagem") { nav.navigate(Routes.chat(Routes.NEW, image = true)) } },
            )
            shown.isEmpty() -> EmptyState(Icons.Rounded.Search, "Nada encontrado", "Nenhuma imagem combina com a busca ou o filtro.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            ) {
                itemsIndexed(shown, key = { _, it -> it.id }) { i, cr -> Cell(cr) { viewer = i } }
            }
        }
    }

    viewer?.let { start ->
        ImageViewer(
            items = shown, start = start,
            actions = ViewerActions(
                onReusePrompt = { cr -> nav.navigate(Routes.chat(cr.conversationId ?: Routes.NEW, draft = cr.prompt, image = true)) },
                onVariation = { cr -> scope.launch { runCatching { container.db.messages().get(cr.messageId ?: "")?.let { container.chat.images.variation(it) } } } ; cr.conversationId?.let { nav.navigate(Routes.chat(it)) } },
                onOpenConversation = { cr -> cr.conversationId?.let { nav.navigate(Routes.chat(it, msg = cr.messageId)) } },
                onFavorite = { cr -> scope.launch { container.db.creations().setFavorite(cr.id, !cr.favorite) } },
                onDelete = { cr -> confirmDelete = cr },
            ),
            onDismiss = { viewer = null },
        )
    }
    confirmDelete?.let { cr ->
        NocSheet({ confirmDelete = null }) {
            Column(Modifier.padding(horizontal = 22.dp)) {
                Text("Excluir imagem?", style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.padding(vertical = 8.dp))
                Text("Ela sai deste celular. Você pode apagar também a cópia guardada no PC.", style = MaterialTheme.typography.bodyMedium, color = c.text2)
                Spacer(Modifier.padding(8.dp))
                PrimaryButton("Excluir daqui e do PC", Modifier.fillMaxWidth(), height = 48.dp) {
                    scope.launch { container.chat.images.delete(listOf(cr.id), alsoPc = true) }
                    confirmDelete = null
                    viewer = null
                }
                Spacer(Modifier.padding(4.dp))
                SecondaryButton("Excluir só deste celular", Modifier.fillMaxWidth()) {
                    scope.launch { container.chat.images.delete(listOf(cr.id), alsoPc = false) }
                    confirmDelete = null
                    viewer = null
                }
                Spacer(Modifier.padding(10.dp))
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Noc.colors
    Text(
        label, style = MaterialTheme.typography.labelMedium, color = if (selected) c.onAccent else c.text,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) c.accent else c.surface2).pressable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun Cell(cr: CreationEntity, onClick: () -> Unit) {
    val c = Noc.colors
    val path = cr.thumbPath ?: cr.path
    val bmp by produceState<Bitmap?>(null, path) { value = withContext(Dispatchers.IO) { path?.let { decodeSampled(it, 360) } } }
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(c.surface2).pressable(onClick = onClick)
            .semantics { contentDescription = "Imagem: ${cr.prompt.take(60)}" },
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (cr.favorite) Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp))
    }
}
