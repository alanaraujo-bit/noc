package com.noc.app.ui.image

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.noc.app.AppContainer
import com.noc.app.chat.ImageCaps
import com.noc.app.chat.LoraInfo
import com.noc.app.core.net.RpcError
import com.noc.app.ui.Texts
import com.noc.app.ui.chat.NocSheet
import com.noc.app.ui.components.Chip
import com.noc.app.ui.components.EmptyState
import com.noc.app.ui.components.IconAction
import com.noc.app.ui.components.PrimaryButton
import com.noc.app.ui.components.SecondaryButton
import com.noc.app.ui.components.TopBar
import com.noc.app.ui.components.pressable
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.Noc
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

/**
 * Biblioteca de LoRAs (no PC). Importar por link (Civitai/Hugging Face) ou salvando o .safetensors na pasta Downloads
 * do PC; o PC confere o hash, descobre a família e as palavras de ativação. Aqui: buscar, favoritar, ajustar o peso
 * padrão e as palavras, e excluir. Na criação, basta ligar o LoRA — nenhuma sintaxe.
 */
@Composable
fun LoraLibraryScreen(container: AppContainer, nav: NavHostController) {
    val c = Noc.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val caps by container.chat.images.caps.collectAsState()
    var loras by remember { mutableStateOf<List<LoraInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var open by remember { mutableStateOf<LoraInfo?>(null) }
    var importing by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()

    LaunchedEffect(reload) {
        runCatching { loras = container.chat.images.loras() }.onFailure { error = "Não foi possível falar com o PC agora." }
    }
    // enquanto há importação em andamento, acompanha (o PC avisa pelo evento "image"; aqui atualiza a lista no fim)
    val imports = caps?.imports.orEmpty()
    LaunchedEffect(imports.map { it.key to it.state }) {
        if (imports.any { it.state == "done" }) reload++
        while (imports.any { it.state in setOf("checking", "downloading", "verifying") }) {
            delay(1500)
            runCatching { loras = container.chat.images.loras() }
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        TopBar("LoRAs", onBack = { nav.popBackStack() }, subtitle = loras?.let { if (it.isEmpty()) "Biblioteca do PC" else "${it.size} na biblioteca do PC" }) {
            IconAction(Icons.Rounded.Add, "Importar por link") { importing = true }
        }
        imports.filter { it.state != "done" || true }.forEach { imp -> ImportRow(imp) { scope.launch { container.chat.images.clearImport(imp.key); container.chat.images.refreshCaps() } } }
        val all = loras
        when {
            error != null && all == null -> EmptyState(Icons.Rounded.Layers, "Sem conexão", error!!)
            all == null -> Unit
            all.isEmpty() -> EmptyState(
                Icons.Rounded.Layers, "Nenhum LoRA ainda",
                "Toque em + e cole o link de um LoRA do Civitai ou do Hugging Face. Ou salve o arquivo .safetensors na pasta Downloads do PC: ele entra sozinho.",
                action = { PrimaryButton("Importar por link", icon = Icons.Rounded.Link) { importing = true } },
            )
            else -> {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SearchBox(query, "Buscar por nome, família ou palavra") { query = it }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Todos", selected = filter == "all") { filter = "all" }
                        Chip("Favoritos", selected = filter == "fav") { filter = "fav" }
                        all.map { it.familyLabel }.distinct().forEach { f -> Chip(f, selected = filter == f) { filter = f } }
                    }
                }
                val q = query.trim().lowercase()
                val shown = all.filter { l ->
                    (filter == "all" || (filter == "fav" && l.favorite) || l.familyLabel == filter) &&
                        (q.isEmpty() || l.name.lowercase().contains(q) || l.familyLabel.lowercase().contains(q) || l.triggers.any { it.lowercase().contains(q) } ||
                            l.tags.any { it.lowercase().contains(q) })
                }
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
                    items(shown, key = { it.id }) { l ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).pressable { open = l }
                                .padding(horizontal = 14.dp, vertical = 12.dp).semantics { contentDescription = "LoRA ${l.name}" },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(l.name, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(l.familyLabel, Texts.bytes(l.size), l.triggers.takeIf { it.isNotEmpty() }?.joinToString(", ")).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).pressable {
                                scope.launch {
                                    runCatching { container.chat.images.updateLora(l.id) { put("favorite", !l.favorite) } }
                                        .onSuccess { u -> loras = loras?.map { if (it.id == u.id) u.copy(compatible = it.compatible, warning = it.warning) else it } }
                                }
                            }.semantics { contentDescription = if (l.favorite) "Desfavoritar" else "Favoritar" }, contentAlignment = Alignment.Center) {
                                Icon(if (l.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = if (l.favorite) c.accent else c.text3, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    open?.let { l ->
        LoraDetailSheet(l, onSave = { name, weight, triggers, auto ->
            scope.launch {
                runCatching {
                    container.chat.images.updateLora(l.id) {
                        put("name", name); put("weight", weight); put("autoTriggers", auto)
                        put("triggers", kotlinx.serialization.json.buildJsonArray { triggers.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                    }
                }.onSuccess { u -> loras = loras?.map { if (it.id == u.id) u else it }; open = null }
                    .onFailure { toast((it as? RpcError)?.message ?: "Não foi possível salvar.") }
            }
        }, onDelete = {
            scope.launch {
                runCatching { container.chat.images.deleteLora(l.id) }
                    .onSuccess { loras = loras?.filter { it.id != l.id }; open = null; toast("LoRA removido do PC") }
                    .onFailure { toast("Não foi possível excluir.") }
            }
        }, onDismiss = { open = null })
    }
    if (importing) ImportLinkSheet(onImport = { url ->
        scope.launch {
            runCatching { container.chat.images.importLink(url) }
                .onSuccess { importing = false; container.chat.images.refreshCaps(); toast("O PC está baixando e conferindo o arquivo.") }
                .onFailure { toast((it as? RpcError)?.message ?: "Não foi possível importar este link.") }
        }
    }, onDismiss = { importing = false })
}

@Composable
private fun ImportRow(imp: ImageCaps.ImportState, onClear: () -> Unit) {
    val c = Noc.colors
    val (label, color) = when (imp.state) {
        "checking" -> "Conferindo o link…" to c.accent
        "downloading" -> "Baixando no PC" + (imp.total?.takeIf { it > 0 }?.let { " · ${((imp.done ?: 0) * 100 / it)}%" } ?: "…") to c.accent
        "verifying" -> "Conferindo o hash…" to c.accent
        "done" -> (if (imp.kind == "checkpoint") "Checkpoint adicionado aos especialistas" else "LoRA adicionado") to c.ok
        "failed" -> (imp.detail ?: "Não foi possível importar") to c.err
        else -> imp.state to c.text3
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(imp.name ?: imp.url, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 2)
        }
        if (imp.state in setOf("done", "failed"))
            Text("OK", style = MaterialTheme.typography.labelLarge, color = c.accent, modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable(onClick = onClear).padding(10.dp))
    }
}

@Composable
fun ImportLinkSheet(onImport: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Noc.colors
    val clipboard = LocalClipboardManager.current
    var url by remember { mutableStateOf(clipboard.getText()?.text?.takeIf { it.startsWith("https://") && (it.contains("civitai.com") || it.contains("huggingface.co")) } ?: "") }
    NocSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 22.dp).imePadding()) {
            Text("Importar por link", style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.padding(vertical = 8.dp))
            Text(
                "Cole o link da página do Civitai ou do arquivo no Hugging Face. O PC baixa só o .safetensors, confere o hash publicado, " +
                    "descobre se é LoRA ou checkpoint e para qual família ele serve.",
                style = MaterialTheme.typography.bodySmall, color = c.text3,
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(14.dp)) {
                if (url.isEmpty()) Text("https://civitai.com/models/…", style = MaterialTheme.typography.bodyLarge, color = c.text3)
                BasicTextField(url, { url = it.trim() }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Link do modelo" })
            }
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Importar", Modifier.fillMaxWidth(), height = 48.dp, enabled = url.startsWith("https://")) { onImport(url) }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun LoraDetailSheet(l: LoraInfo, onSave: (String, Double, List<String>, Boolean) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val c = Noc.colors
    var name by remember { mutableStateOf(l.name) }
    var weight by remember { mutableStateOf(l.weight) }
    var triggers by remember { mutableStateOf(l.triggers.joinToString(", ")) }
    var auto by remember { mutableStateOf(l.autoTriggers) }
    var confirm by remember { mutableStateOf(false) }
    NocSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 22.dp).verticalScroll(rememberScrollState()).imePadding()) {
            Text(l.name, style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.padding(vertical = 8.dp), maxLines = 2)
            Text(listOfNotNull(l.familyLabel, l.baseModel, Texts.bytes(l.size), "sha ${l.sha256.take(10)}").joinToString(" · "), style = MonoSmall, color = c.text3)
            l.license?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.text3, modifier = Modifier.padding(top = 4.dp)) }
            l.source?.let { Text(it, style = MonoSmall, color = c.accent, modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(14.dp))
            Field("Nome", name) { name = it }
            Text("Força padrão · ${"%.2f".format(weight)}", style = MaterialTheme.typography.labelLarge, color = c.text2, modifier = Modifier.padding(top = 12.dp))
            Slider(value = weight.toFloat(), onValueChange = { weight = (it * 20).toInt() / 20.0 }, valueRange = 0f..1.5f,
                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent))
            Field("Palavras de ativação (separe com vírgulas)", triggers) { triggers = it }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).pressable { auto = !auto }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Colocar as palavras sozinho", style = MaterialTheme.typography.titleSmall, color = c.text)
                    Text("Entram no prompt quando o LoRA está ligado", style = MaterialTheme.typography.bodySmall, color = c.text3)
                }
                Switch(checked = auto, onCheckedChange = { auto = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent, uncheckedTrackColor = c.surface3, uncheckedThumbColor = c.text3))
            }
            Text("Usado ${l.uses} vez${if (l.uses == 1) "" else "es"}" + (l.lastUsed?.let { " · último uso ${Texts.relative(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = c.text3)
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Salvar", Modifier.fillMaxWidth(), height = 48.dp) {
                onSave(name.trim().ifEmpty { l.name }, weight, triggers.split(',').map { it.trim() }.filter { it.isNotEmpty() }, auto)
            }
            Spacer(Modifier.height(6.dp))
            SecondaryButton(if (confirm) "Toque de novo para excluir do PC" else "Excluir", Modifier.fillMaxWidth(), tint = c.err) {
                if (confirm) onDelete() else confirm = true
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    val c = Noc.colors
    Text(label, style = MaterialTheme.typography.labelLarge, color = c.text2, modifier = Modifier.padding(bottom = 6.dp, top = 6.dp))
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(14.dp)) {
        BasicTextField(value, onChange, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text), cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
    }
}
