package com.noc.app.ui.library

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.noc.app.AppContainer
import com.noc.app.chat.GenerationParams
import com.noc.app.data.db.PresetEntity
import com.noc.app.data.db.PromptEntity
import com.noc.app.ui.Routes
import com.noc.app.ui.chat.Segmented
import com.noc.app.ui.chat.presetIcon
import com.noc.app.ui.components.Badge
import com.noc.app.ui.components.Chip
import com.noc.app.ui.components.EmptyState
import com.noc.app.ui.components.IconAction
import com.noc.app.ui.components.PrimaryButton
import com.noc.app.ui.components.SectionLabel
import com.noc.app.ui.components.TopBar
import com.noc.app.ui.components.pressable
import com.noc.app.ui.newChat
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.Noc
import kotlinx.coroutines.launch
import java.util.UUID

// ------------------------------------------------------------------ perfis (presets)

@Composable
fun PresetsScreen(container: AppContainer, nav: NavHostController) {
    val c = Noc.colors
    val presets by container.db.presets().observeAll().collectAsState(initial = emptyList())
    val prefs by container.prefs.flow.collectAsState(initial = null)
    val defaultId = prefs?.defaultPresetId ?: com.noc.app.chat.Library.PRESET_BALANCED

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        TopBar("Perfis", onBack = { nav.popBackStack() }) {
            IconAction(Icons.Rounded.Add, "Novo perfil") { nav.navigate(Routes.preset("new")) }
        }
        Text(
            "Um perfil guarda instruções, parâmetros e o modelo preferido para um tipo de tarefa.",
            style = MaterialTheme.typography.bodyMedium, color = c.text2, modifier = Modifier.padding(horizontal = 20.dp),
        )
        LazyColumn(
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            items(presets, key = { it.id }) { p ->
                val params = GenerationParams.decode(p.paramsJson) ?: GenerationParams()
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
                        .pressable(onLongClick = { nav.newChat(presetId = p.id) }) { nav.navigate(Routes.preset(p.id)) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) {
                        Icon(presetIcon(p.icon), null, tint = c.text, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium, color = c.text)
                            if (p.id == defaultId) { Spacer(Modifier.width(8.dp)); Badge("Padrão", c.accent, c.accentSoft) }
                        }
                        Text(p.description, style = MaterialTheme.typography.bodySmall, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(summary(params, p.model), style = MonoSmall, color = c.text3)
                    }
                }
            }
            item {
                Text(
                    "Toque para editar · Toque e segure para abrir uma conversa com o perfil",
                    style = MaterialTheme.typography.bodySmall, color = c.text3, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private fun summary(p: GenerationParams, model: String?): String = listOfNotNull(
    p.temperature?.let { "temp %.1f".format(it) },
    when (p.reasoning) { "off" -> "sem raciocínio"; "on" -> "com raciocínio"; else -> null },
    p.maxTokens?.let { "máx $it" },
    p.contextLength?.let { "ctx ${it / 1024}k" },
    model?.substringBefore('@'),
).joinToString(" · ").ifEmpty { "padrões do modelo" }

@Composable
fun PresetEditScreen(container: AppContainer, id: String, nav: NavHostController) {
    val c = Noc.colors
    val scope = rememberCoroutineScope()
    val models by container.connection.models.collectAsState()
    val prefs by container.prefs.flow.collectAsState(initial = null)
    var loaded by remember { mutableStateOf(id == "new") }
    var original by remember { mutableStateOf<PresetEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var system by remember { mutableStateOf("") }
    var model by remember { mutableStateOf<String?>(null) }
    var params by remember { mutableStateOf(GenerationParams()) }

    LaunchedEffect(id) {
        if (id != "new") container.db.presets().get(id)?.let { p ->
            original = p
            name = p.name; desc = p.description; system = p.systemPrompt ?: ""; model = p.model
            params = GenerationParams.decode(p.paramsJson) ?: GenerationParams()
        }
        loaded = true
    }

    fun save() = scope.launch {
        val p = PresetEntity(
            id = original?.id ?: UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Meu perfil" }, icon = original?.icon ?: "tune",
            description = desc.trim().ifBlank { "Perfil personalizado" }, model = model,
            systemPrompt = system.takeIf { it.isNotBlank() }, paramsJson = params.encode(),
            builtin = original?.builtin ?: false, sort = original?.sort ?: 100, updatedAt = System.currentTimeMillis(),
        )
        container.db.presets().upsert(p)
        nav.popBackStack()
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().imePadding()) {
        TopBar(if (id == "new") "Novo perfil" else "Editar perfil", onBack = { nav.popBackStack() }) {
            if (original != null) {
                IconAction(Icons.Rounded.ContentCopy, "Duplicar") {
                    scope.launch {
                        val copy = original!!.copy(id = UUID.randomUUID().toString(), name = original!!.name + " (cópia)", builtin = false, sort = 100, updatedAt = System.currentTimeMillis())
                        container.db.presets().upsert(copy)
                        nav.popBackStack()
                        nav.navigate(Routes.preset(copy.id))
                    }
                }
                if (original?.builtin == false) IconAction(Icons.Rounded.Delete, "Excluir", tint = c.err) {
                    scope.launch { container.db.presets().delete(id); nav.popBackStack() }
                }
            }
        }
        if (!loaded) return@Column
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Field("Nome", name, { name = it.take(40) })
            Field("Descrição", desc, { desc = it.take(80) })
            Field("Instruções (prompt de sistema)", system, { system = it }, minHeight = 120, placeholder = "Opcional. Ex.: Você é um revisor técnico exigente…")

            SectionLabel("Modelo preferido", Modifier.padding(top = 16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("O que estiver carregado", selected = model == null) { model = null }
                models.filter { it.isLlm }.forEach { m -> Chip(m.name + (m.quant?.let { " · $it" } ?: ""), selected = model == m.key) { model = m.key } }
            }

            SectionLabel("Raciocínio", Modifier.padding(top = 16.dp))
            Segmented(listOf(null to "Padrão", "on" to "Ligado", "off" to "Desligado"), params.reasoning) { params = params.copy(reasoning = it) }

            SectionLabel("Parâmetros", Modifier.padding(top = 16.dp))
            NumberRow("Temperatura", params.temperature?.toString() ?: "", "padrão") { params = params.copy(temperature = it.replace(',', '.').toDoubleOrNull()?.coerceIn(0.0, 2.0)) }
            NumberRow("Top P", params.topP?.toString() ?: "", "padrão") { params = params.copy(topP = it.replace(',', '.').toDoubleOrNull()?.coerceIn(0.0, 1.0)) }
            NumberRow("Top K", params.topK?.toString() ?: "", "padrão") { params = params.copy(topK = it.toIntOrNull()) }
            NumberRow("Min P", params.minP?.toString() ?: "", "padrão") { params = params.copy(minP = it.replace(',', '.').toDoubleOrNull()?.coerceIn(0.0, 1.0)) }
            NumberRow("Penalidade de repetição", params.repeatPenalty?.toString() ?: "", "padrão") { params = params.copy(repeatPenalty = it.replace(',', '.').toDoubleOrNull()) }
            NumberRow("Máximo de tokens", params.maxTokens?.toString() ?: "", "sem limite") { params = params.copy(maxTokens = it.toIntOrNull()) }
            NumberRow("Contexto ao carregar", params.contextLength?.toString() ?: "", "padrão") { params = params.copy(contextLength = it.toIntOrNull()) }

            if (original != null) {
                val isDefault = (prefs?.defaultPresetId ?: com.noc.app.chat.Library.PRESET_BALANCED) == original!!.id
                Row(
                    Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface)
                        .pressable { scope.launch { container.prefs.setDefaultPreset(original!!.id) } }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = if (isDefault) c.accent else c.surface3)
                    Spacer(Modifier.width(12.dp))
                    Text(if (isDefault) "Perfil padrão das novas conversas" else "Usar como padrão nas novas conversas", style = MaterialTheme.typography.titleSmall, color = c.text)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        Box(Modifier.navigationBarsPadding().padding(20.dp)) {
            PrimaryButton("Salvar", Modifier.fillMaxWidth(), enabled = name.isNotBlank()) { save() }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, minHeight: Int = 0, placeholder: String? = null) {
    val c = Noc.colors
    Column(Modifier.padding(top = 14.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.text2, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(14.dp)) {
            if (value.isEmpty() && placeholder != null) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.text3)
            BasicTextField(
                value, onChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = minHeight.dp),
            )
        }
    }
}

@Composable
private fun NumberRow(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    val c = Noc.colors
    var text by remember(value) { mutableStateOf(value) }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
        Box(Modifier.width(120.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (text.isEmpty()) Text(placeholder, style = MonoSmall, color = c.text3)
            BasicTextField(text, { t -> text = t.filter { it.isDigit() || it == '.' || it == ',' }.take(9); onChange(text) }, singleLine = true, textStyle = MonoSmall.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
        }
    }
}

// ------------------------------------------------------------------ prompts de sistema

@Composable
fun PromptsScreen(container: AppContainer, nav: NavHostController, pickMode: Boolean) {
    val c = Noc.colors
    val prompts by container.db.prompts().observeAll().collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf<String?>(null) }
    val categories = prompts?.mapNotNull { it.category }?.distinct()?.sorted() ?: emptyList()

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        TopBar(if (pickMode) "Escolher instruções" else "Biblioteca de prompts", onBack = { nav.popBackStack() }) {
            IconAction(Icons.Rounded.Add, "Novo prompt") { nav.navigate(Routes.prompt("new")) }
        }
        if (categories.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Todos", selected = category == null) { category = null }
                Chip("Favoritos", selected = category == "★", icon = Icons.Rounded.Star) { category = "★" }
                categories.forEach { cat -> Chip(cat, selected = category == cat) { category = cat } }
            }
        }
        val list = prompts?.filter { p -> category == null || (category == "★" && p.favorite) || p.category == category }
        if (list != null && list.isEmpty()) {
            EmptyState(Icons.Rounded.TextSnippet, "Nenhum prompt aqui", "Crie instruções reutilizáveis para mudar a personalidade da IA em um toque.") {
                PrimaryButton("Criar prompt", height = 46.dp) { nav.navigate(Routes.prompt("new")) }
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            items(list ?: emptyList(), key = { it.id }) { p ->
                PromptCard(
                    p,
                    onClick = {
                        if (pickMode) {
                            scope.launch { container.db.prompts().touch(p.id, System.currentTimeMillis()) }
                            nav.previousBackStackEntry?.savedStateHandle?.set("picked_prompt", p.content)
                            nav.popBackStack()
                        } else nav.navigate(Routes.prompt(p.id))
                    },
                    onFavorite = { scope.launch { container.db.prompts().setFavorite(p.id, !p.favorite) } },
                )
            }
        }
    }
}

@Composable
private fun PromptCard(p: PromptEntity, onClick: () -> Unit, onFavorite: () -> Unit) {
    val c = Noc.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).pressable(onClick = onClick).padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.title, style = MaterialTheme.typography.titleMedium, color = c.text)
                if (p.isDefault) { Spacer(Modifier.width(8.dp)); Badge("Padrão", c.accent, c.accentSoft) }
            }
            p.category?.let { Text(it, style = MonoSmall, color = c.text3) }
            Spacer(Modifier.height(4.dp))
            Text(p.content, style = MaterialTheme.typography.bodySmall, color = c.text2, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        IconAction(if (p.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (p.favorite) "Desfavoritar" else "Favoritar", tint = if (p.favorite) c.accent else c.text3, onClick = onFavorite)
    }
}

@Composable
fun PromptEditScreen(container: AppContainer, id: String, onBack: () -> Unit) {
    val c = Noc.colors
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<PromptEntity?>(null) }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var isDefault by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        if (id != "new") container.db.prompts().get(id)?.let { p ->
            original = p; title = p.title; content = p.content; category = p.category ?: ""; isDefault = p.isDefault
        }
    }

    fun save() = scope.launch {
        val now = System.currentTimeMillis()
        val p = PromptEntity(
            id = original?.id ?: UUID.randomUUID().toString(), title = title.trim(), content = content.trim(),
            favorite = original?.favorite ?: false, isDefault = false, category = category.trim().ifBlank { null },
            createdAt = original?.createdAt ?: now, updatedAt = now, lastUsedAt = original?.lastUsedAt,
        )
        container.db.prompts().upsert(p)
        if (isDefault) container.db.prompts().makeDefault(p.id)
        else if (original?.isDefault == true) container.db.prompts().makeDefault(null)
        onBack()
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().imePadding()) {
        TopBar(if (id == "new") "Novo prompt" else "Editar prompt", onBack = onBack) {
            if (original != null) IconAction(Icons.Rounded.Delete, "Excluir", tint = c.err) {
                scope.launch { container.db.prompts().delete(id); onBack() }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Field("Título", title, { title = it.take(50) })
            Field("Categoria", category, { category = it.take(24) }, placeholder = "Ex.: Programação")
            Field("Instruções", content, { content = it }, minHeight = 220, placeholder = "Descreva como a IA deve se comportar…")
            Row(
                Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).pressable { isDefault = !isDefault }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.CheckCircle, null, tint = if (isDefault) c.accent else c.surface3)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Usar como padrão", style = MaterialTheme.typography.titleSmall, color = c.text)
                    Text("Vale para conversas cujo perfil não tem instruções próprias.", style = MaterialTheme.typography.bodySmall, color = c.text3)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        Box(Modifier.navigationBarsPadding().padding(20.dp)) {
            PrimaryButton("Salvar", Modifier.fillMaxWidth(), enabled = title.isNotBlank() && content.isNotBlank()) { save() }
        }
    }
}
