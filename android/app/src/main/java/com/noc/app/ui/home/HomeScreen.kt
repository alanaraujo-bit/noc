package com.noc.app.ui.home

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.noc.app.AppContainer
import com.noc.app.core.net.ConnState
import com.noc.app.core.net.LmState
import com.noc.app.core.net.PcStatus
import com.noc.app.core.net.Problem
import com.noc.app.data.db.ConversationRow
import com.noc.app.data.prefs.AppPrefs
import com.noc.app.ui.Routes
import com.noc.app.ui.Texts
import com.noc.app.ui.chat.presetIcon
import com.noc.app.ui.components.Chip
import com.noc.app.ui.components.IconAction
import com.noc.app.ui.components.PrimaryButton
import com.noc.app.ui.components.SecondaryButton
import com.noc.app.ui.components.SectionLabel
import com.noc.app.ui.components.Skeleton
import com.noc.app.ui.components.StatusDot
import com.noc.app.ui.components.pressable
import com.noc.app.ui.newChat
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.Noc
import com.noc.app.ui.theme.Serif
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(container: AppContainer, prefs: AppPrefs, nav: NavHostController) {
    val c = Noc.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conn by container.connection.state.collectAsState()
    val status by container.connection.status.collectAsState()
    val latency by container.connection.latencyMs.collectAsState()
    val recent by container.db.conversations().observeRecent(6).collectAsState(initial = null)
    val presets by container.db.presets().observeAll().collectAsState(initial = emptyList())
    var startingLm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.statusBarsPadding().fillMaxWidth().height(60.dp).padding(start = 22.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Wordmark()
            Spacer(Modifier.weight(1f))
            IconAction(Icons.Rounded.History, "Conversas") { nav.navigate(Routes.HISTORY) }
            IconAction(Icons.Rounded.Settings, "Ajustes") { nav.navigate(Routes.SETTINGS) }
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "greeting") {
                Column(Modifier.padding(start = 2.dp, bottom = 6.dp)) {
                    Text(Texts.greeting() + ".", style = MaterialTheme.typography.displayLarge, color = c.text)
                    AnimatedContent(Texts.headline(conn, status), transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "h") { h ->
                        Text(h, style = MaterialTheme.typography.displaySmall.copy(fontStyle = FontStyle.Italic), color = c.text2)
                    }
                }
            }

            item(key = "pc") {
                PcPanel(
                    conn = conn,
                    status = status,
                    latency = latency,
                    startingLm = startingLm,
                    onPair = { nav.navigate(Routes.pair()) },
                    onRetry = { container.connection.retryNow() },
                    onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
                    onModels = { nav.navigate(Routes.MODELS) },
                    onStartLm = {
                        startingLm = true
                        scope.launch {
                            val ok = runCatching { container.connection.call("lms.start", timeoutMs = 100_000) }.isSuccess
                            startingLm = false
                            if (!ok) Toast.makeText(context, "Não foi possível iniciar o LM Studio. Abra-o no PC.", Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }

            if (presets.isNotEmpty()) item(key = "quick") {
                Column {
                    SectionLabel("Começar com um perfil")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        presets.forEach { p -> Chip(p.name, icon = presetIcon(p.icon)) { nav.newChat(presetId = p.id) } }
                    }
                }
            }

            val list = recent
            if (list == null) {
                items(3) { Skeleton(Modifier.fillMaxWidth().height(62.dp), RoundedCornerShape(18.dp)) }
            } else if (list.isNotEmpty()) {
                item(key = "recent-label") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("Recentes", Modifier.weight(1f))
                        Text(
                            "Ver todas", style = MaterialTheme.typography.labelMedium, color = c.accent,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).pressable { nav.navigate(Routes.HISTORY) }.padding(8.dp),
                        )
                    }
                }
                items(list, key = { it.id }) { row -> RecentRow(row) { nav.navigate(Routes.chat(row.id)) } }
            }
        }

        Box(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            PrimaryButton(
                "Nova conversa", Modifier.fillMaxWidth(), icon = Icons.Rounded.Add, height = 58.dp,
                enabled = conn !is ConnState.NoPc,
            ) { nav.newChat() }
        }
    }
}

@Composable
fun Wordmark() {
    val c = Noc.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(c.accent))
        Spacer(Modifier.width(8.dp))
        Text("noc", style = MaterialTheme.typography.headlineLarge.copy(fontFamily = Serif), color = c.text)
    }
}

@Composable
private fun PcPanel(
    conn: ConnState,
    status: PcStatus?,
    latency: Long?,
    startingLm: Boolean,
    onPair: () -> Unit,
    onRetry: () -> Unit,
    onDiagnostics: () -> Unit,
    onModels: () -> Unit,
    onStartLm: () -> Unit,
) {
    val c = Noc.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(c.surface)
            .animateContentSize()
            .padding(20.dp),
    ) {
        when (conn) {
            ConnState.NoPc -> {
                Text("Conecte seu computador", style = MaterialTheme.typography.titleLarge, color = c.text)
                Spacer(Modifier.height(6.dp))
                Text(
                    "O Noc conversa com as IAs que rodam no LM Studio do seu PC. Abra o Noc Companion no computador e pareie em segundos.",
                    style = MaterialTheme.typography.bodyMedium, color = c.text2,
                )
                Spacer(Modifier.height(16.dp))
                PrimaryButton("Parear computador", icon = Icons.Rounded.Link, height = 48.dp, onClick = onPair)
            }
            is ConnState.Offline -> {
                PcHeader(conn.pcName, dot = c.err, pulsing = false, route = null, latency = null)
                Spacer(Modifier.height(14.dp))
                val e = Texts.problem(conn.problem)
                Text(e.title, style = MaterialTheme.typography.titleMedium, color = c.text)
                Spacer(Modifier.height(4.dp))
                Text(e.body, style = MaterialTheme.typography.bodyMedium, color = c.text2)
                RetryCountdown(conn.retryAt)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val fatal = conn.problem.fatal
                    if (fatal && conn.problem !is Problem.Incompatible) {
                        PrimaryButton("Parear novamente", height = 44.dp, onClick = onPair)
                    } else {
                        SecondaryButton("Tentar agora", onClick = onRetry)
                    }
                    SecondaryButton("Diagnóstico", onClick = onDiagnostics)
                }
            }
            is ConnState.Connecting -> {
                PcHeader(conn.pcName, dot = c.warn, pulsing = true, route = null, latency = null)
                Spacer(Modifier.height(16.dp))
                Skeleton(Modifier.fillMaxWidth(0.7f).height(18.dp))
                Spacer(Modifier.height(8.dp))
                Skeleton(Modifier.fillMaxWidth(0.45f).height(14.dp))
            }
            ConnState.Paused -> {
                Skeleton(Modifier.fillMaxWidth(0.5f).height(20.dp))
                Spacer(Modifier.height(10.dp))
                Skeleton(Modifier.fillMaxWidth(0.8f).height(14.dp))
            }
            is ConnState.Online -> {
                val generating = status?.jobs?.isNotEmpty() == true
                PcHeader(conn.pc.name, dot = c.ok, pulsing = generating, route = Texts.route(conn.route), latency = latency)
                Spacer(Modifier.height(16.dp))
                OnlineBody(status, startingLm, onModels, onStartLm)
            }
        }
    }
}

@Composable
private fun RetryCountdown(retryAt: Long?) {
    if (retryAt == null) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(retryAt) {
        while (now < retryAt) { delay(500); now = System.currentTimeMillis() }
    }
    val secs = ((retryAt - now) / 1000).coerceAtLeast(0)
    Text(
        if (secs > 0) "Tentando de novo em $secs s" else "Tentando de novo…",
        style = MonoSmall, color = Noc.colors.text3, modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun PcHeader(name: String, dot: Color, pulsing: Boolean, route: String?, latency: Long?) {
    val c = Noc.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(dot, pulsing, 9.dp)
        Spacer(Modifier.width(10.dp))
        Text(name, style = MaterialTheme.typography.titleLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (route != null) {
            Text(
                route + (latency?.let { " · $it ms" } ?: ""),
                style = MonoSmall, color = c.text3,
            )
        }
    }
}

@Composable
private fun OnlineBody(status: PcStatus?, startingLm: Boolean, onModels: () -> Unit, onStartLm: () -> Unit) {
    val c = Noc.colors
    if (status == null) {
        Skeleton(Modifier.fillMaxWidth(0.6f).height(18.dp)); return
    }
    when {
        status.lm == LmState.NOT_INSTALLED -> {
            Text("LM Studio não encontrado", style = MaterialTheme.typography.titleMedium, color = c.text)
            Text("Instale o LM Studio no PC e baixe um modelo. O Companion detecta sozinho.", style = MaterialTheme.typography.bodyMedium, color = c.text2)
            return
        }
        status.lm != LmState.RUNNING -> {
            Text("LM Studio não está disponível", style = MaterialTheme.typography.titleMedium, color = c.text)
            Spacer(Modifier.height(4.dp))
            Text("O servidor local do LM Studio está desligado. Posso ligá-lo para você, direto do PC.", style = MaterialTheme.typography.bodyMedium, color = c.text2)
            Spacer(Modifier.height(14.dp))
            PrimaryButton(if (startingLm) "Iniciando…" else "Iniciar LM Studio", height = 44.dp, enabled = !startingLm, onClick = onStartLm)
            return
        }
    }
    val loaded = status.loaded.firstOrNull()
    val op = status.op
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).pressable(onClick = onModels).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("MODELO", style = MaterialTheme.typography.labelSmall, color = c.text3)
            Spacer(Modifier.height(3.dp))
            when {
                op?.kind == "loading" -> {
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(op) { while (true) { delay(1000); now = System.currentTimeMillis() } }
                    Text("Carregando ${op.model.substringBefore('@')}…", style = MaterialTheme.typography.titleMedium, color = c.text)
                    op.since?.let { Text("${(now - it) / 1000} s", style = MonoSmall, color = c.text3) }
                }
                loaded != null -> {
                    Text(loaded.name, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${Texts.tokens(loaded.context)} de contexto" + (if (status.loaded.size > 1) " · +${status.loaded.size - 1} carregado" else ""),
                        style = MonoSmall, color = c.text3,
                    )
                }
                else -> {
                    Text("Nenhum modelo carregado", style = MaterialTheme.typography.titleMedium, color = c.text)
                    Text("Escolha um — ou ele carrega sozinho quando você enviar.", style = MaterialTheme.typography.bodySmall, color = c.text3)
                }
            }
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Modelos", tint = c.text3, modifier = Modifier.size(20.dp))
    }

    AnimatedVisibility(status.jobs.isNotEmpty()) {
        val j = status.jobs.firstOrNull()
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(c.accent, pulsing = true, size = 7.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                (if ((j?.state ?: "") == "loading") "Carregando para responder" else "Gerando resposta") +
                    (j?.tps?.let { " · %.0f tok/s".format(it) } ?: ""),
                style = MaterialTheme.typography.bodyMedium, color = c.text2,
            )
        }
    }

    status.gpu?.let { g ->
        Spacer(Modifier.height(16.dp))
        val frac = if (g.vramTotalMb > 0) g.vramUsedMb.toFloat() / g.vramTotalMb else 0f
        val anim by animateFloatAsState(frac, tween(600), label = "vram")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("VRAM", style = MaterialTheme.typography.labelSmall, color = c.text3, modifier = Modifier.width(44.dp))
            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surface2)) {
                Box(Modifier.fillMaxWidth(anim).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (frac > 0.92f) c.warn else c.text2))
            }
            Spacer(Modifier.width(10.dp))
            Text("${Texts.gb(g.vramUsedMb)}/${Texts.gb(g.vramTotalMb)} GB", style = MonoSmall, color = c.text3)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${g.name.removePrefix("NVIDIA ").removePrefix("GeForce ")} · ${g.util}% uso · ${g.tempC}°C",
            style = MonoSmall, color = c.text3,
        )
    }
}

@Composable
private fun RecentRow(row: ConversationRow, onClick: () -> Unit) {
    val c = Noc.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .pressable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.pinned) {
                    Icon(Icons.Rounded.PushPin, "Fixada", tint = c.text3, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(row.title, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            row.preview?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(12.dp))
        Text(Texts.relative(row.updatedAt), style = MonoSmall, color = c.text3)
    }
}
