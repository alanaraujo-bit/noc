package com.noc.app.ui.models

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.noc.app.AppContainer
import com.noc.app.core.net.ConnState
import com.noc.app.core.net.ModelInfo
import com.noc.app.ui.Texts
import com.noc.app.ui.components.Badge
import com.noc.app.ui.components.Chip
import com.noc.app.ui.components.EmptyState
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun ModelsScreen(container: AppContainer, onBack: () -> Unit) {
    val c = Noc.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val models by container.connection.models.collectAsState()
    val status by container.connection.status.collectAsState()
    val conn by container.connection.state.collectAsState()
    var expanded by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(conn is ConnState.Online) {
        if (conn is ConnState.Online) container.connection.refreshModels()
    }

    fun load(m: ModelInfo, ctx: Int) = scope.launch {
        runCatching { container.connection.call("models.load", buildJsonObject { put("model", m.key); put("context", ctx) }) }
            .onSuccess { Toast.makeText(context, "Carregando ${m.name}…", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, "Não foi possível carregar agora.", Toast.LENGTH_SHORT).show() }
    }

    fun unload(m: ModelInfo) = scope.launch {
        runCatching { container.connection.call("models.unload", buildJsonObject { put("model", m.key) }, timeoutMs = 60_000) }
            .onFailure { Toast.makeText(context, "Não foi possível descarregar.", Toast.LENGTH_SHORT).show() }
        container.connection.refreshModels()
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        TopBar("Modelos", onBack = onBack, subtitle = (conn as? ConnState.Online)?.pc?.name) {
            IconAction(Icons.Rounded.Refresh, "Atualizar", enabled = conn is ConnState.Online) {
                refreshing = true
                scope.launch { container.connection.refreshModels(); refreshing = false }
            }
        }
        if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = c.accent, trackColor = c.bg)

        val llms = models.filter { it.isLlm }
        val other = models.filter { !it.isLlm }
        when {
            conn !is ConnState.Online && models.isEmpty() -> EmptyState(
                Icons.Rounded.Memory, "Sem conexão com o PC",
                "Os modelos aparecem aqui assim que o celular se conectar ao seu computador.",
            )
            models.isEmpty() -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) { Skeleton(Modifier.fillMaxWidth().height(84.dp), RoundedCornerShape(20.dp)) }
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.navigationBarsPadding(),
            ) {
                status?.gpu?.let { g ->
                    item {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).padding(16.dp)) {
                            Text(g.name, style = MaterialTheme.typography.titleSmall, color = c.text)
                            Spacer(Modifier.height(8.dp))
                            val frac = if (g.vramTotalMb > 0) g.vramUsedMb.toFloat() / g.vramTotalMb else 0f
                            LinearProgressIndicator(
                                progress = { frac }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = if (frac > 0.92f) c.warn else c.text2, trackColor = c.surface2, drawStopIndicator = {},
                            )
                            Spacer(Modifier.height(6.dp))
                            Text("${Texts.gb(g.vramUsedMb)} de ${Texts.gb(g.vramTotalMb)} GB de memória de vídeo · ${g.tempC}°C", style = MonoSmall, color = c.text3)
                        }
                    }
                }
                item { SectionLabel("Modelos de conversa", Modifier.padding(top = 8.dp)) }
                items(llms, key = { it.key }) { m ->
                    ModelCard(
                        m = m,
                        loading = status?.op?.kind == "loading" && status?.op?.model == m.key,
                        unloading = status?.op?.kind == "unloading" && status?.op?.model == m.key,
                        expanded = expanded == m.key,
                        onToggle = { expanded = if (expanded == m.key) null else m.key },
                        onLoad = { ctx -> load(m, ctx) },
                        onUnload = { unload(m) },
                    )
                }
                if (other.isNotEmpty()) {
                    item { SectionLabel("Outros (não usados no chat)", Modifier.padding(top = 12.dp)) }
                    items(other, key = { it.key }) { m ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
                            Text(m.name, style = MaterialTheme.typography.titleSmall, color = c.text2)
                            Text("${m.type} · ${Texts.bytes(m.sizeBytes)}", style = MonoSmall, color = c.text3)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    m: ModelInfo,
    loading: Boolean,
    unloading: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onLoad: (Int) -> Unit,
    onUnload: () -> Unit,
) {
    val c = Noc.colors
    val max = m.maxContext.takeIf { it > 0 } ?: 32768
    val options = listOf(4096, 8192, 16384, 32768, 65536, 131072, 262144).filter { it <= max }
    var ctx by remember(m.key) { mutableIntStateOf(m.context ?: options.firstOrNull { it >= 16384 } ?: options.last()) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(c.surface)
            .pressable(onClick = onToggle)
            .animateContentSize()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name, style = MaterialTheme.typography.titleMedium, color = c.text, modifier = Modifier.weight(1f, fill = false))
                    if (m.loaded) { Spacer(Modifier.width(8.dp)); StatusDot(c.ok) }
                }
                Text(listOfNotNull(m.params, m.quant, m.arch, Texts.bytes(m.sizeBytes)).joinToString(" · "), style = MonoSmall, color = c.text3)
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                loading -> Badge("Carregando…", c.warn, c.warnSoft)
                unloading -> Badge("Descarregando…", c.warn, c.warnSoft)
                m.loaded -> Badge("Carregado · ${Texts.tokens(m.context ?: 0)} ctx", c.ok, c.okSoft)
                else -> Badge("No disco", c.text3, c.surface2)
            }
            if (m.vision) Badge("Visão", c.text2, c.surface2)
            if (m.reasoning.isNotEmpty()) Badge("Raciocínio", c.text2, c.surface2)
            if (m.toolUse) Badge("Ferramentas", c.text2, c.surface2)
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp).height(3.dp).clip(RoundedCornerShape(2.dp)), color = c.accent, trackColor = c.surface2)

        AnimatedVisibility(expanded && !loading && !unloading) {
            Column(Modifier.padding(top = 14.dp)) {
                Text("Contexto máximo do modelo: ${Texts.tokens(max)} tokens.", style = MaterialTheme.typography.bodySmall, color = c.text2)
                if (m.fitsGpu == false) {
                    Spacer(Modifier.height(6.dp))
                    Text("Este modelo é maior que a memória da sua GPU: parte vai rodar na CPU e ficará bem mais lento.", style = MaterialTheme.typography.bodySmall, color = c.warn)
                }
                Spacer(Modifier.height(12.dp))
                Text("Contexto ao carregar", style = MaterialTheme.typography.labelMedium, color = c.text2)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { o -> Chip(Texts.tokens(o), selected = ctx == o) { ctx = o } }
                }
                Text("Mais contexto = conversas mais longas, porém mais memória de vídeo.", style = MaterialTheme.typography.bodySmall, color = c.text3)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (m.loaded) {
                        SecondaryButton("Descarregar", icon = Icons.Rounded.Upload, onClick = onUnload)
                        if (m.context != ctx) PrimaryButton("Recarregar com ${Texts.tokens(ctx)}", height = 46.dp) { onLoad(ctx) }
                    } else {
                        PrimaryButton("Carregar", icon = Icons.Rounded.Download, height = 46.dp) { onLoad(ctx) }
                    }
                }
            }
        }
    }
}
