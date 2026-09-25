package com.noc.app.ui.history

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.noc.app.AppContainer
import com.noc.app.data.db.ConversationRow
import com.noc.app.ui.Routes
import com.noc.app.ui.Texts
import com.noc.app.ui.chat.NocSheet
import com.noc.app.ui.chat.RenameSheet
import com.noc.app.ui.chat.SheetAction
import com.noc.app.ui.components.Chip
import com.noc.app.ui.components.EmptyState
import com.noc.app.ui.components.IconAction
import com.noc.app.ui.components.PrimaryButton
import com.noc.app.ui.components.TopBar
import com.noc.app.ui.components.pressable
import com.noc.app.ui.newChat
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.Noc
import kotlinx.coroutines.launch

private sealed interface Filter {
    data object All : Filter
    data object Pinned : Filter
    data object Archived : Filter
    data class Folder(val name: String) : Filter
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(container: AppContainer, nav: NavHostController) {
    val c = Noc.colors
    val dao = container.db.conversations()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by remember { mutableStateOf<Filter>(Filter.All) }
    var actionFor by remember { mutableStateOf<ConversationRow?>(null) }
    var renaming by remember { mutableStateOf<ConversationRow?>(null) }
    var moving by remember { mutableStateOf<ConversationRow?>(null) }
    var confirmDelete by remember { mutableStateOf<ConversationRow?>(null) }

    val active by dao.observeList(false).collectAsState(initial = null)
    val archived by dao.observeList(true).collectAsState(initial = emptyList())
    val folders by dao.observeFolders().collectAsState(initial = emptyList())
    var searchResults by remember { mutableStateOf<List<ConversationRow>?>(null) }
    LaunchedEffect(query) {
        if (query.isBlank()) { searchResults = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(220) // espera a digitação assentar
        searchResults = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.noc.app.data.db.Search.run(dao, container.db.messages(), query)
        }
    }

    val list: List<ConversationRow>? = searchResults ?: when (val f = filter) {
        Filter.All -> active
        Filter.Pinned -> active?.filter { it.pinned }
        Filter.Archived -> archived
        is Filter.Folder -> active?.filter { it.folder == f.name }
    }

    fun archive(row: ConversationRow) = scope.launch {
        dao.setArchived(row.id, !row.archived)
        val r = snackbar.showSnackbar(if (row.archived) "Conversa restaurada" else "Conversa arquivada", "Desfazer", duration = SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) dao.setArchived(row.id, row.archived)
    }

    Scaffold(
        containerColor = c.bg,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).statusBarsPadding()) {
            TopBar("Conversas", onBack = { nav.popBackStack() })
            // busca
            Row(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = c.text3, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Buscar em títulos e mensagens", style = MaterialTheme.typography.bodyLarge, color = c.text3)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                }
                if (query.isNotEmpty()) IconAction(Icons.Rounded.Close, "Limpar", size = 28.dp, iconSize = 18.dp) { query = "" }
            }
            if (query.isBlank()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Chip("Todas", selected = filter == Filter.All) { filter = Filter.All }
                    Chip("Fixadas", selected = filter == Filter.Pinned, icon = Icons.Rounded.PushPin) { filter = Filter.Pinned }
                    folders.forEach { f -> Chip(f, selected = filter == Filter.Folder(f), icon = Icons.Rounded.Folder) { filter = Filter.Folder(f) } }
                    Chip("Arquivadas" + if (archived.isNotEmpty()) " · ${archived.size}" else "", selected = filter == Filter.Archived, icon = Icons.Rounded.Archive) { filter = Filter.Archived }
                }
            } else Spacer(Modifier.height(12.dp))

            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(
                    icon = if (query.isNotBlank()) Icons.Rounded.Search else Icons.Rounded.ChatBubbleOutline,
                    title = when {
                        query.isNotBlank() -> "Nada encontrado"
                        filter == Filter.Archived -> "Nenhuma conversa arquivada"
                        filter == Filter.Pinned -> "Nenhuma conversa fixada"
                        else -> "Nenhuma conversa ainda"
                    },
                    body = when {
                        query.isNotBlank() -> "Tente outras palavras."
                        filter == Filter.Archived -> "Arraste uma conversa para a esquerda para arquivar."
                        filter == Filter.Pinned -> "Fixe as conversas importantes para achá-las rápido."
                        else -> "Suas conversas ficam guardadas neste celular."
                    },
                    action = if (query.isBlank() && filter == Filter.All) ({ PrimaryButton("Nova conversa", height = 46.dp) { nav.newChat() } }) else null,
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    modifier = Modifier.navigationBarsPadding(),
                ) {
                    var lastLabel: String? = null
                    list.forEach { row ->
                        val label = if (row.pinned && filter == Filter.All && query.isBlank()) "Fixadas" else Texts.dayLabel(row.updatedAt)
                        if (label != lastLabel) {
                            val l = label
                            item(key = "h-$l-${row.id}") {
                                Text(l.uppercase(), style = MaterialTheme.typography.labelSmall, color = c.text3, modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp))
                            }
                            lastLabel = label
                        }
                        item(key = row.id) {
                            val state = rememberSwipeToDismissBoxState(
                                confirmValueChange = { v ->
                                    if (v == SwipeToDismissBoxValue.EndToStart) { archive(row); true } else false
                                },
                            )
                            SwipeToDismissBox(
                                state = state,
                                enableDismissFromStartToEnd = false,
                                modifier = Modifier.animateItem(),
                                backgroundContent = {
                                    val bg by animateColorAsState(if (state.targetValue == SwipeToDismissBoxValue.EndToStart) c.accentSoft else c.bg, label = "bg")
                                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(bg).padding(horizontal = 22.dp), contentAlignment = Alignment.CenterEnd) {
                                        Icon(if (row.archived) Icons.Rounded.Unarchive else Icons.Rounded.Archive, null, tint = c.accent)
                                    }
                                },
                            ) {
                                ConversationItem(row, onClick = { nav.navigate(Routes.chat(row.id)) }, onLongClick = { actionFor = row })
                            }
                        }
                    }
                }
            }
        }
    }

    actionFor?.let { row ->
        NocSheet({ actionFor = null }) {
            Text(row.title, style = MaterialTheme.typography.titleLarge, color = c.text, maxLines = 2, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
            SheetAction(Icons.Rounded.Edit, "Renomear") { actionFor = null; renaming = row }
            SheetAction(Icons.Rounded.PushPin, if (row.pinned) "Desafixar" else "Fixar") { actionFor = null; scope.launch { dao.setPinned(row.id, !row.pinned) } }
            SheetAction(Icons.Rounded.Folder, "Mover para pasta", row.folder?.let { "Agora em “$it”" }) { actionFor = null; moving = row }
            SheetAction(Icons.Rounded.ContentCopy, "Duplicar") {
                actionFor = null
                scope.launch { val id = container.chat.duplicate(row.id); nav.navigate(Routes.chat(id)) }
            }
            SheetAction(if (row.archived) Icons.Rounded.Unarchive else Icons.Rounded.Archive, if (row.archived) "Desarquivar" else "Arquivar") { actionFor = null; archive(row) }
            SheetAction(Icons.Rounded.Delete, "Excluir", danger = true) { actionFor = null; confirmDelete = row }
        }
    }
    renaming?.let { row -> RenameSheet(row.title, onRename = { t -> scope.launch { dao.rename(row.id, t.trim(), false) } }, onDismiss = { renaming = null }) }
    moving?.let { row ->
        FolderSheet(folders, row.folder, onPick = { f -> scope.launch { dao.setFolder(row.id, f) }; moving = null }, onDismiss = { moving = null })
    }
    confirmDelete?.let { row ->
        NocSheet({ confirmDelete = null }) {
            Column(Modifier.padding(horizontal = 22.dp)) {
                Text("Excluir “${row.title}”?", style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.padding(vertical = 8.dp))
                Text("A conversa será apagada deste celular. Isso não pode ser desfeito.", style = MaterialTheme.typography.bodyMedium, color = c.text2)
                Spacer(Modifier.height(18.dp))
                PrimaryButton("Excluir", Modifier.fillMaxWidth(), icon = Icons.Rounded.Delete, height = 48.dp) {
                    confirmDelete = null
                    scope.launch { container.chat.deleteConversation(row.id) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ConversationItem(row: ConversationRow, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Noc.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(c.bg)
            .pressable(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.pinned) {
                    Icon(Icons.Rounded.PushPin, "Fixada", tint = c.accent, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(row.title, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            Text(
                row.preview ?: "Sem mensagens",
                style = MaterialTheme.typography.bodySmall, color = c.text3, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(row.model?.substringBefore('@'), row.folder, "${row.messageCount} msg".takeIf { row.messageCount > 0 })
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MonoSmall, color = c.text3, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(Texts.relative(row.updatedAt), style = MonoSmall, color = c.text3)
    }
}

@Composable
private fun FolderSheet(folders: List<String>, current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val c = Noc.colors
    var name by remember { mutableStateOf("") }
    NocSheet(onDismiss) {
        Text("Mover para pasta", style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
        if (current != null) SheetAction(Icons.Rounded.Close, "Tirar da pasta") { onPick(null) }
        folders.forEach { f -> SheetAction(Icons.Rounded.Folder, f, if (f == current) "Pasta atual" else null) { onPick(f) } }
        Row(Modifier.padding(horizontal = 22.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(c.surface).padding(14.dp)) {
                if (name.isEmpty()) Text("Nova pasta", style = MaterialTheme.typography.bodyLarge, color = c.text3)
                BasicTextField(name, { name = it.take(30) }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(10.dp))
            PrimaryButton("Criar", height = 48.dp, enabled = name.isNotBlank()) { onPick(name.trim()) }
        }
    }
}
