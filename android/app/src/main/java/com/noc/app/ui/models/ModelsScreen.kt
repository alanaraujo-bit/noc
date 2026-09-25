package com.noc.app.ui.models

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noc.app.AppContainer
import com.noc.app.core.net.ConnState
import com.noc.app.core.net.ModelInfo
import com.noc.app.core.net.PcStatus
import com.noc.app.core.net.RpcError
import com.noc.app.core.net.Tier
import com.noc.app.core.net.arr
import com.noc.app.core.net.asObj
import com.noc.app.core.net.bool
import com.noc.app.core.net.dbl
import com.noc.app.core.net.int
import com.noc.app.core.net.long
import com.noc.app.core.net.obj
import com.noc.app.core.net.str
import com.noc.app.ui.Texts
import com.noc.app.ui.chat.NocSheet
import com.noc.app.ui.chat.capsLine
import com.noc.app.ui.components.Badge
import com.noc.app.ui.components.Chip
import com.noc.app.ui.components.EmptyState
import com.noc.app.ui.components.GhostButton
import com.noc.app.ui.components.IconAction
import com.noc.app.ui.components.PrimaryButton
import com.noc.app.ui.components.SecondaryButton
import com.noc.app.ui.components.SectionLabel
import com.noc.app.ui.components.Skeleton
import com.noc.app.ui.components.StatusDot
import com.noc.app.ui.components.TopBar
import com.noc.app.ui.components.pressable
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.Noc
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Gerenciador de modelos: perfis, modelos do PC, detalhes, teste de desempenho e importações automáticas. */
@Composable
fun ModelsScreen(container: AppContainer, onBack: () -> Unit) {
    val c = Noc.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val models by container.connection.models.collectAsState()
    val status by container.connection.status.collectAsState()
    val conn by container.connection.state.collectAsState()
    val online = conn is ConnState.Online
    var detail by remember { mutableStateOf<String?>(null) }
    var tierPick by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    val library by produceState(emptyList<JsonObject>(), online) {
        if (!online) return@produceState
        runCatching { container.connection.call("models.library") }.onSuccess { r -> value = r.arr("items")?.mapNotNull { it.asObj() } ?: emptyList() }
        container.connection.events.collect { e -> if (e.name == "library") value = e.data.arr("items")?.mapNotNull { it.asObj() } ?: value }
    }
    // teste de desempenho: progresso ao vivo
    var bench by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(Unit) {
        container.connection.events.collect { e ->
            if (e.name != "bench") return@collect
            bench = e.data
            if (e.data.bool("done") == true) {
                container.connection.refreshModels()
                e.data.str("error")?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            }
        }
    }
    LaunchedEffect(online) {
        if (online) { container.connection.refreshModels(); container.connection.refreshStatus() }
    }

    fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()
    fun rpc(method: String, p: JsonObject, ok: String? = null) = scope.launch {
        runCatching { container.connection.call(method, p, timeoutMs = 60_000) }
            .onSuccess { ok?.let { toast(it) }; container.connection.refreshModels(); container.connection.refreshStatus() }
            .onFailure { toast((it as? RpcError)?.message?.takeIf { m -> m.isNotBlank() } ?: "Não foi possível agora.") }
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        TopBar("Modelos", onBack = onBack, subtitle = (conn as? ConnState.Online)?.pc?.name) {
            IconAction(Icons.Rounded.Refresh, "Atualizar", enabled = online) {
                refreshing = true
                scope.launch {
                    runCatching { container.connection.call("models.scan") }
                    container.connection.refreshModels(); container.connection.refreshStatus(); refreshing = false
                }
            }
        }
        if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = c.accent, trackColor = c.bg)
        val llms = models.filter { it.isLlm }
        when {
            !online && models.isEmpty() -> EmptyState(Icons.Rounded.Memory, "Sem conexão com o PC", "Os modelos aparecem aqui assim que o celular se conectar ao seu computador.")
            models.isEmpty() -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) { Skeleton(Modifier.fillMaxWidth().height(84.dp), RoundedCornerShape(20.dp)) }
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.navigationBarsPadding(),
            ) {
                item("now") { NowCard(status, models, onUnload = { k -> rpc("models.unload", buildJsonObject { put("model", k) }) }) }
                if (status?.tiers?.isNotEmpty() == true || online) {
                    item("tiers-label") { SectionLabel("Perfis", Modifier.padding(top = 10.dp)) }
                    item("tiers") {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)) {
                            Tier.all.forEachIndexed { i, t ->
                                val info = status?.tiers?.get(t)
                                Row(
                                    Modifier.fillMaxWidth().pressable { tierPick = t }.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(Tier.symbol(t), style = MaterialTheme.typography.titleLarge, color = c.accent, modifier = Modifier.width(34.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(Tier.label(t), style = MaterialTheme.typography.titleMedium, color = c.text)
                                        Text(info?.name ?: "Escolher modelo", style = MaterialTheme.typography.bodySmall, color = if (info != null) c.text2 else c.text3)
                                    }
                                    if (info?.model != null && info.model == status?.defaultModel) Badge("Padrão", c.accent, c.accentSoft)
                                }
                                if (i < 2) Box(Modifier.padding(start = 50.dp).fillMaxWidth().height(1.dp).background(c.line))
                            }
                        }
                    }
                }
                val imports = library.filter { it.str("state") in setOf("working", "failed") || (it.str("state") == "done" && (it.bool("repaired") == true || it.bool("visionAdded") == true)) }
                if (imports.isNotEmpty()) {
                    item("lib-label") { SectionLabel("Encontrados no seu PC", Modifier.padding(top = 10.dp)) }
                    items(imports, key = { "lib" + it.str("file") }) { LibraryRow(it) }
                }
                item("mine-label") { SectionLabel("Seus modelos", Modifier.padding(top = 10.dp)) }
                val visible = llms.filter { !it.hidden }.sortedWith(compareByDescending<ModelInfo> { it.loaded }.thenByDescending { it.tier != null }.thenByDescending { it.favorite }.thenByDescending { it.fitsGpu != false }.thenBy { it.name })
                items(visible, key = { it.key }) { m ->
                    ModelCard(m, status, benchModel = status?.benchModel, onClick = { detail = m.key }, onFavorite = {
                        rpc("models.update", buildJsonObject { put("model", m.key); put("favorite", !m.favorite) })
                    })
                }
                val hidden = llms.filter { it.hidden }
                if (hidden.isNotEmpty()) {
                    item("hidden") {
                        Row(Modifier.fillMaxWidth().pressable { showHidden = !showHidden }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Ocultos (${hidden.size})", style = MaterialTheme.typography.labelMedium, color = c.text3, modifier = Modifier.weight(1f))
                            Icon(Icons.Rounded.ExpandMore, null, tint = c.text3, modifier = Modifier.rotate(if (showHidden) 180f else 0f))
                        }
                    }
                    if (showHidden) items(hidden, key = { "h" + it.key }) { m -> ModelCard(m, status, null, onClick = { detail = m.key }, onFavorite = {}) }
                }
                val other = models.filter { !it.isLlm }
                if (other.isNotEmpty()) {
                    item("other-label") { SectionLabel("Outros (não usados no chat)", Modifier.padding(top = 12.dp)) }
                    items(other, key = { "x" + it.key }) { m ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
                            Text(m.name, style = MaterialTheme.typography.titleSmall, color = c.text2)
                            Text("${m.type} · ${Texts.bytes(m.sizeBytes)}", style = MonoSmall, color = c.text3)
                        }
                    }
                }
            }
        }
    }

    detail?.let { key ->
        val m = models.firstOrNull { it.key == key }
        if (m == null) detail = null else ModelDetailSheet(
            container = container, m = m, status = status, bench = bench?.takeIf { it.str("model") == key },
            onLoad = { scope.launch { container.modelOps.load(m.key, m.name)?.let { toast(it) } ?: toast("Carregando ${m.name}…") } },
            onUnload = { rpc("models.unload", buildJsonObject { put("model", m.key) }) },
            onUpdate = { p -> rpc("models.update", buildJsonObject { put("model", m.key); p.forEach { (k, v) -> put(k, v) } }) },
            onDefault = { rpc("models.default", buildJsonObject { put("model", m.key); put("preload", true) }, "${m.name} será deixado pronto ao ligar o PC") },
            onBench = { rpc("models.bench", buildJsonObject { put("model", m.key) }) },
            onDismiss = { detail = null },
        )
    }
    tierPick?.let { t ->
        TierPickSheet(t, models.filter { it.isLlm }, status?.tiers?.get(t)?.model, onPick = { key ->
            tierPick = null
            rpc("tiers.set", buildJsonObject { put("tier", t); put("model", key) }, "${Tier.label(t)} agora usa ${models.firstOrNull { it.key == key }?.name ?: key}")
        }, onDismiss = { tierPick = null })
    }
}

@Composable
private fun NowCard(status: PcStatus?, models: List<ModelInfo>, onUnload: (String) -> Unit) {
    val c = Noc.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.surface).animateContentSize().padding(18.dp)) {
        val op = status?.op
        val loaded = status?.loaded?.firstOrNull()
        Text("CARREGADO AGORA", style = MaterialTheme.typography.labelSmall, color = c.text3)
        Spacer(Modifier.height(4.dp))
        when {
            op?.kind == "loading" -> {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(op) { while (true) { delay(500); now = System.currentTimeMillis() } }
                val secs = op.since?.let { (now - it) / 1000 } ?: 0
                Text("Carregando ${op.name ?: op.model}…", style = MaterialTheme.typography.titleMedium, color = c.text)
                Text("$secs s" + (op.expectedSeconds?.let { " de ~${it.toInt()} s" } ?: ""), style = MonoSmall, color = c.text3)
                Spacer(Modifier.height(10.dp))
                val exp = op.expectedSeconds
                if (exp != null && exp > 0) {
                    LinearProgressIndicator(progress = { (secs / exp).toFloat().coerceIn(0f, 0.97f) }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = c.accent, trackColor = c.surface2, drawStopIndicator = {})
                } else LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = c.accent, trackColor = c.surface2)
            }
            loaded != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(loaded.name, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${Texts.tokens(loaded.context)} de contexto" + (if (loaded.vision) " · visão" else ""), style = MonoSmall, color = c.text3)
                }
                SecondaryButton("Descarregar") { onUnload(loaded.key) }
            }
            else -> {
                Text("Nenhum modelo carregado", style = MaterialTheme.typography.titleMedium, color = c.text)
                Text("O modelo escolhido carrega sozinho quando você envia uma mensagem.", style = MaterialTheme.typography.bodySmall, color = c.text3)
            }
        }
        status?.gpu?.let { g ->
            Spacer(Modifier.height(14.dp))
            val frac = if (g.vramTotalMb > 0) g.vramUsedMb.toFloat() / g.vramTotalMb else 0f
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = if (frac > 0.92f) c.warn else c.text2, trackColor = c.surface2, drawStopIndicator = {})
            Spacer(Modifier.height(6.dp))
            Text("${g.name.removePrefix("NVIDIA ").removePrefix("GeForce ")} · ${Texts.gb(g.vramUsedMb)} de ${Texts.gb(g.vramTotalMb)} GB de VRAM", style = MonoSmall, color = c.text3)
        }
    }
}

@Composable
private fun ModelCard(m: ModelInfo, status: PcStatus?, benchModel: String?, onClick: () -> Unit, onFavorite: () -> Unit) {
    val c = Noc.colors
    val loading = status?.op?.kind == "loading" && status.op.model == m.key
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.surface).pressable(onClick = onClick).padding(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    m.tier?.let { Text(Tier.symbol(it) + " ", style = MaterialTheme.typography.titleMedium, color = c.accent) }
                    Text(m.name, style = MaterialTheme.typography.titleMedium, color = if (m.fitsGpu == false) c.text2 else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (m.loaded) { Spacer(Modifier.width(8.dp)); StatusDot(c.ok, size = 7.dp) }
                }
                Text(m.capsLine(), style = MonoSmall, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(40.dp).clip(CircleShape).pressable(onClick = onFavorite), contentAlignment = Alignment.Center) {
                Icon(if (m.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (m.favorite) "Desfavoritar" else "Favoritar", tint = if (m.favorite) c.accent else c.text3, modifier = Modifier.size(20.dp))
            }
        }
        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                loading -> Badge("Carregando…", c.warn, c.warnSoft)
                benchModel == m.key -> Badge("Testando…", c.warn, c.warnSoft)
                m.loaded -> Badge("Carregado · ${Texts.tokens(m.context ?: 0)}", c.ok, c.okSoft)
                m.lastError != null -> Badge("Não carregou", c.err, c.errSoft)
                m.fitsGpu == false -> Badge("Não cabe na GPU", c.warn, c.warnSoft)
                else -> Badge("Pronto no disco", c.text3, c.surface2)
            }
            m.tier?.let { Badge(Tier.label(it), c.text2, c.surface2) }
            if (m.isDefault) Badge("Padrão", c.accent, c.accentSoft)
            m.bench?.dbl("tps")?.let { Badge("%.0f tok/s".format(it), c.text2, c.surface2) }
        }
    }
}

@Composable
private fun LibraryRow(item: JsonObject) {
    val c = Noc.colors
    val state = item.str("state")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (state == "failed") Icons.Rounded.ErrorOutline else Icons.Rounded.Download, null, tint = if (state == "failed") c.err else c.text2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(item.str("file") ?: "", style = MaterialTheme.typography.bodySmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Text(
            when (state) {
                "working" -> item.str("step") ?: "Adicionando…"
                "failed" -> item.str("detail") ?: "Não foi possível adicionar"
                else -> listOfNotNull("Adicionado", "ajustado para o LM Studio".takeIf { item.bool("repaired") == true }, "visão ativada".takeIf { item.bool("visionAdded") == true }).joinToString(" · ")
            },
            style = MaterialTheme.typography.bodySmall, color = if (state == "failed") c.err else c.text3, modifier = Modifier.padding(top = 4.dp),
        )
        if (state == "working") {
            Spacer(Modifier.height(8.dp))
            val p = item.dbl("progress")
            if (p != null) LinearProgressIndicator(progress = { p.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = c.accent, trackColor = c.surface2, drawStopIndicator = {})
            else LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = c.accent, trackColor = c.surface2)
        }
    }
}

@Composable
private fun TierPickSheet(tier: String, llms: List<ModelInfo>, current: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Noc.colors
    NocSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("${Tier.symbol(tier)} ${Tier.label(tier)}", style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
            Text(Tier.blurb(tier) + ". Escolha qual modelo responde neste perfil.", style = MaterialTheme.typography.bodyMedium, color = c.text2, modifier = Modifier.padding(horizontal = 22.dp).padding(bottom = 8.dp))
            llms.filter { !it.hidden }.sortedWith(compareByDescending<ModelInfo> { it.fitsGpu != false }.thenBy { it.sizeBytes }).forEach { m ->
                Row(Modifier.fillMaxWidth().pressable { onPick(m.key) }.padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.titleSmall, color = if (m.fitsGpu == false) c.text3 else c.text)
                        Text(if (m.fitsGpu == false) "Não cabe na GPU · ${m.capsLine()}" else m.capsLine(), style = MonoSmall, color = c.text3)
                    }
                    if (m.key == current) Text("✓", style = MaterialTheme.typography.titleMedium, color = c.accent)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ModelDetailSheet(
    container: AppContainer,
    m: ModelInfo,
    status: PcStatus?,
    bench: JsonObject?,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onUpdate: (Map<String, kotlinx.serialization.json.JsonElement>) -> Unit,
    onDefault: () -> Unit,
    onBench: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Noc.colors
    val loading = status?.op?.kind == "loading" && status.op.model == m.key
    val plan by produceState<JsonObject?>(null, m.key, m.perfContext) {
        value = runCatching { container.connection.call("models.plan", buildJsonObject { put("model", m.key) }, timeoutMs = 60_000) }.getOrNull()
    }
    var renaming by remember { mutableStateOf(false) }
    var alias by remember(m.key) { mutableStateOf(m.alias ?: m.name) }
    val running = bench != null && bench.bool("done") != true || status?.benchModel == m.key
    NocSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            if (renaming) {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).padding(14.dp)) {
                    BasicTextField(alias, { alias = it.take(40) }, singleLine = true, textStyle = MaterialTheme.typography.titleLarge.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Salvar nome", height = 42.dp) { onUpdate(mapOf("alias" to JsonPrimitive(alias.trim()))); renaming = false }
                    SecondaryButton("Nome automático") { onUpdate(mapOf("alias" to kotlinx.serialization.json.JsonNull)); renaming = false }
                }
            } else {
                Text(m.name, style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.padding(top = 8.dp).pressable { renaming = true })
            }
            Text((m.technical ?: m.name) + " · " + m.key, style = MonoSmall, color = c.text3, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (m.vision) Badge("Visão", c.text2, c.surface2)
                if (m.supportsReasoning) Badge("Raciocínio", c.text2, c.surface2)
                if (m.toolUse) Badge("Ferramentas", c.text2, c.surface2)
                m.tier?.let { Badge(Tier.label(it), c.accent, c.accentSoft) }
                if (m.isDefault) Badge("Padrão", c.accent, c.accentSoft)
            }
            m.lastError?.let {
                Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.errSoft).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = c.err, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = c.err)
                }
            }
            Spacer(Modifier.height(10.dp))
            Info("Parâmetros", m.params)
            Info("Quantização", m.quant)
            Info("Arquitetura", m.arch)
            Info("Tamanho no disco", m.sizeBytes.takeIf { it > 0 }?.let { Texts.bytes(it) })
            Info("Contexto máximo", m.maxContext.takeIf { it > 0 }?.let { Texts.tokens(it) + " tokens" })
            Info("Contexto agora", m.context?.let { Texts.tokens(it) + " tokens" })
            plan?.let { p ->
                val est = p.dbl("estimateGiB")
                Info(
                    "Memória ao carregar",
                    listOfNotNull(est?.let { "~%.1f GB".format(it) }, p.int("context")?.let { "com ${Texts.tokens(it)}" }).joinToString(" ") +
                        (if (p.bool("fits") == false) " · não cabe inteiro na GPU" else " · cabe na GPU"),
                )
                p.str("note")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.text3, modifier = Modifier.padding(vertical = 2.dp)) }
            }
            Info("Última carga", m.lastLoadSeconds?.let { "%.1f s".format(it) })

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    loading -> SecondaryButton("Carregando…") {}
                    m.loaded -> SecondaryButton("Descarregar", onClick = onUnload)
                    else -> PrimaryButton("Carregar", height = 44.dp, onClick = onLoad)
                }
                if (!m.isDefault) SecondaryButton("Deixar pronto ao ligar", onClick = onDefault)
            }

            // desempenho real neste PC
            Spacer(Modifier.height(18.dp))
            SectionLabel("Desempenho neste PC")
            val result = bench?.obj("result") ?: m.bench
            if (running) {
                Text(bench?.str("step") ?: status?.benchStep ?: "Testando…", style = MaterialTheme.typography.bodyMedium, color = c.text2)
                Spacer(Modifier.height(8.dp))
                val p = bench?.dbl("progress")
                if (p != null) LinearProgressIndicator(progress = { p.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = c.accent, trackColor = c.surface2, drawStopIndicator = {})
                else LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = c.accent, trackColor = c.surface2)
            } else if (result != null) {
                Info("Velocidade", result.dbl("tps")?.let { "%.1f tokens/s".format(it) })
                Info("Primeiro token", result.long("ttftMs")?.let { Texts.seconds(it) })
                Info("Leitura de texto longo", result.long("prefillTps")?.let { "$it tokens/s" + (result.int("longPromptTokens")?.let { n -> " (${Texts.tokens(n)} tokens)" } ?: "") })
                Info("Tempo de carga", result.dbl("loadSeconds")?.let { "%.1f s".format(it) })
                Info("VRAM em uso", result.dbl("vramUsedGiB")?.let { v -> "%.1f".format(v) + (result.dbl("vramTotalGiB")?.let { t -> " de %.1f GB".format(t) } ?: " GB") })
                Info("Estável", result.bool("stable")?.let { if (it) "Sim (3 rodadas parecidas)" else "Variou entre as rodadas" })
                result.obj("vision")?.let { v -> Info("Visão", if (v.bool("ok") == true) "Leu a imagem de teste (${v.long("ms")?.let { Texts.seconds(it) } ?: ""})" else "Não leu a imagem de teste") }
                result.long("at")?.let { Text("Medido ${Texts.relative(it)}", style = MonoSmall, color = c.text3, modifier = Modifier.padding(top = 4.dp)) }
            } else {
                Text("Mede carga, primeiro token, tokens/s, leitura de texto longo, VRAM e visão — com números reais do seu PC.", style = MaterialTheme.typography.bodySmall, color = c.text3)
            }
            if (!running) GhostButton(if (result != null) "Testar de novo" else "Testar desempenho") { onBench() }

            // modo avançado
            Spacer(Modifier.height(12.dp))
            var adv by remember { mutableStateOf(m.perfContext != null || (m.perfReasoning != null && m.perfReasoning != "auto")) }
            Row(Modifier.fillMaxWidth().pressable { adv = !adv }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Speed, null, tint = c.text3, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Ajustes avançados", style = MaterialTheme.typography.titleSmall, color = c.text2, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ExpandMore, null, tint = c.text3, modifier = Modifier.rotate(if (adv) 180f else 0f))
            }
            AnimatedVisibility(adv) {
                Column {
                    Text("Contexto ao carregar", style = MaterialTheme.typography.labelMedium, color = c.text2)
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Automático", selected = m.perfContext == null) { onUpdate(mapOf("context" to kotlinx.serialization.json.JsonNull)) }
                        listOf(8192, 16384, 32768, 65536, 131072).filter { it <= (m.maxContext.takeIf { x -> x > 0 } ?: 32768) }.forEach { v ->
                            Chip(Texts.tokens(v), selected = m.perfContext == v) { onUpdate(mapOf("context" to JsonPrimitive(v))) }
                        }
                    }
                    Text("Automático escolhe o maior contexto que cabe na GPU. Vale na próxima carga.", style = MaterialTheme.typography.bodySmall, color = c.text3)
                    if (m.supportsReasoning) {
                        Spacer(Modifier.height(12.dp))
                        Text("Raciocínio padrão", style = MaterialTheme.typography.labelMedium, color = c.text2)
                        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Do perfil", selected = m.perfReasoning == null || m.perfReasoning == "auto") { onUpdate(mapOf("reasoning" to JsonPrimitive("auto"))) }
                            if (m.supportsReasoningToggle) Chip("Desligado", selected = m.perfReasoning == "off") { onUpdate(mapOf("reasoning" to JsonPrimitive("off"))) }
                            Chip("Ligado", selected = m.perfReasoning == "on") { onUpdate(mapOf("reasoning" to JsonPrimitive("on"))) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    GhostButton(if (m.hidden) "Mostrar na lista" else "Ocultar este modelo") { onUpdate(mapOf("hidden" to JsonPrimitive(!m.hidden))) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Info(label: String, value: String?) {
    if (value == null) return
    val c = Noc.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.text2, modifier = Modifier.weight(1f))
        Text(value, style = MonoSmall, color = c.text)
    }
}
