package com.noc.app.ui.status

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.noc.app.AppContainer
import com.noc.app.core.net.ConnState
import com.noc.app.core.net.LmState
import com.noc.app.core.net.PcStatus
import com.noc.app.core.net.Route
import com.noc.app.ui.Routes
import com.noc.app.ui.Texts
import com.noc.app.ui.components.GhostButton
import com.noc.app.ui.components.Group
import com.noc.app.ui.components.GroupDivider
import com.noc.app.ui.components.SectionLabel
import com.noc.app.ui.components.StatusDot
import com.noc.app.ui.components.TopBar
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.Noc
import kotlinx.coroutines.delay

/** Estado do seu PC de IA num relance. Todos os números vêm do PC na hora (nada estimado). */
@Composable
fun StatusScreen(container: AppContainer, nav: NavHostController) {
    val c = Noc.colors
    val conn by container.connection.state.collectAsState()
    val status by container.connection.status.collectAsState()
    val latency by container.connection.latencyMs.collectAsState()
    val online = conn as? ConnState.Online
    // enquanto a tela está aberta, pede o estado de novo a cada 3 s (o PC também empurra mudanças)
    LaunchedEffect(online != null) {
        while (online != null) {
            container.connection.refreshStatus()
            delay(3_000)
        }
    }
    val s = status
    Column(Modifier.fillMaxSize().background(c.bg)) {
        TopBar("Status do PC", onBack = { nav.popBackStack() }, modifier = Modifier.statusBarsPadding())
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            SectionLabel("Computador", Modifier.padding(top = 6.dp))
            Group {
                Line("PC", online?.pc?.name?.let { "$it · online" } ?: (conn as? ConnState.Offline)?.pcName?.let { "$it · offline" } ?: "—",
                    dot = if (online != null) c.ok else c.err)
                GroupDivider()
                Line("Conexão", online?.let { (if (it.route == Route.LAN) "Local (Wi-Fi)" else "Remota, cifrada") + (latency?.let { l -> " · $l ms" } ?: "") } ?: "—")
                GroupDivider()
                Line("Companion", if (online != null) "Online" + (s?.companion?.let { " · v$it" } ?: "") else "—", dot = if (online != null) c.ok else c.text3)
                GroupDivider()
                Line("LM Studio", when (s?.lm) {
                    LmState.RUNNING -> "Online"
                    LmState.STOPPED -> "Parado"
                    LmState.NOT_INSTALLED -> "Não instalado"
                    else -> "—"
                }, dot = if (s?.lm == LmState.RUNNING) c.ok else c.warn)
            }

            SectionLabel("GPU", Modifier.padding(top = 18.dp))
            Group {
                Line("GPU", s?.gpu?.name?.removePrefix("NVIDIA ")?.removePrefix("GeForce ") ?: "—")
                GroupDivider()
                s?.gpu?.let { g ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("VRAM", style = MaterialTheme.typography.bodyMedium, color = c.text2, modifier = Modifier.weight(1f))
                            Text("${Texts.gb(g.vramUsedMb)} / ${Texts.gb(g.vramTotalMb)} GB", style = MonoSmall, color = c.text)
                        }
                        Spacer(Modifier.height(8.dp))
                        val frac = if (g.vramTotalMb > 0) g.vramUsedMb.toFloat() / g.vramTotalMb else 0f
                        val anim by animateFloatAsState(frac, tween(500), label = "vram")
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surface2)) {
                            Box(Modifier.fillMaxWidth(anim).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (frac > 0.92f) c.warn else c.text2))
                        }
                        Text("${g.util}% de uso · ${g.tempC}°C", style = MonoSmall, color = c.text3, modifier = Modifier.padding(top = 6.dp))
                    }
                } ?: Line("VRAM", "—")
            }

            SectionLabel("Modelo e inferência", Modifier.padding(top = 18.dp))
            Group {
                val loaded = s?.loaded?.firstOrNull()
                Line("Modelo", when {
                    s?.op?.kind == "loading" -> (s.op.name ?: s.op.model) + " (carregando)"
                    loaded != null -> loaded.name
                    else -> "Nenhum carregado"
                })
                GroupDivider()
                Line("Status", when {
                    s?.op?.kind == "loading" -> "Carregando"
                    s?.op?.kind == "unloading" -> "Descarregando"
                    loaded != null -> "Carregado"
                    else -> "Livre"
                }, dot = if (loaded != null) c.ok else c.text3)
                GroupDivider()
                Line("Contexto", loaded?.context?.let { Texts.tokens(it) + " tokens" } ?: "—")
                GroupDivider()
                val active = s?.tasks?.count { !it.finished } ?: 0
                Line("Inferência", if (active > 0) "Ativa · $active " + (if (active == 1) "tarefa" else "tarefas") else "Inativa", dot = if (active > 0) c.accent else c.text3)
                GroupDivider()
                Line("Velocidade", s?.last?.tps?.let { "%.1f tokens/s".format(it) + (s.last.name.takeIf { n -> n.isNotBlank() }?.let { n -> " · $n" } ?: "") } ?: "—")
                GroupDivider()
                Line("1º token (TTFT)", s?.last?.ttftMs?.let { Texts.seconds(it) } ?: "—")
                GroupDivider()
                Line("Voz", when (s?.stt?.state) {
                    "ready" -> "Pronta (no PC)"
                    "loading" -> "Preparando"
                    "downloading" -> "Baixando modelo de voz" + (s.stt.progress?.let { " · ${(it * 100).toInt()}%" } ?: "")
                    "failed" -> "Indisponível"
                    "off", null -> "Desligada"
                    else -> s.stt.state
                })
            }
            Text(
                "Velocidade e TTFT são da última resposta concluída no PC.",
                style = MaterialTheme.typography.bodySmall, color = c.text3, textAlign = TextAlign.Start,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            )
            GhostButton("Modelos") { nav.navigate(Routes.MODELS) }
            GhostButton("Diagnóstico") { nav.navigate(Routes.DIAGNOSTICS) }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun Line(label: String, value: String, dot: Color? = null) {
    val c = Noc.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.text2, modifier = Modifier.width(130.dp))
        Spacer(Modifier.weight(1f))
        if (dot != null) {
            StatusDot(dot, size = 7.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, color = c.text, textAlign = TextAlign.End)
    }
}
