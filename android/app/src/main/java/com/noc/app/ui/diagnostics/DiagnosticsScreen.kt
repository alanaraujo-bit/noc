package com.noc.app.ui.diagnostics

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.noc.app.AppContainer
import com.noc.app.core.net.ConnState
import com.noc.app.core.net.LmState
import com.noc.app.core.net.Problem
import com.noc.app.core.net.Route
import com.noc.app.core.net.dbl
import com.noc.app.core.net.long
import com.noc.app.core.net.obj
import com.noc.app.core.net.str
import com.noc.app.ui.Routes
import com.noc.app.ui.Texts
import com.noc.app.ui.components.PrimaryButton
import com.noc.app.ui.components.SecondaryButton
import com.noc.app.ui.components.TopBar
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.Noc
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private enum class St { WAIT, RUN, OK, WARN, FAIL, SKIP }

private data class Item(val title: String, var st: St = St.WAIT, var detail: String? = null, var fix: String? = null)

/**
 * Verifica, em ordem, cada elo entre o celular e o modelo — e explica o que fazer quando um falha.
 */
@Composable
fun DiagnosticsScreen(container: AppContainer, nav: NavHostController) {
    val c = Noc.colors
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<Item>() }
    var running by remember { mutableStateOf(false) }
    var needsPair by remember { mutableStateOf(false) }

    fun set(i: Int, st: St, detail: String? = null, fix: String? = null) {
        items[i] = items[i].copy(st = st, detail = detail, fix = fix)
    }

    fun run() {
        if (running) return
        running = true
        needsPair = false
        items.clear()
        listOf(
            "Internet no celular", "Serviço de conexão remota", "PC online", "Companion respondendo",
            "Autenticação", "LM Studio", "Modelo", "Rede local", "Geração de teste",
            "Perfis e modelos", "Imagens (visão)", "Ditado por voz", "Notificações",
        ).forEach { items.add(Item(it)) }
        scope.launch {
            val cm = container.connection
            val pc = cm.activePc.value
            // 1. internet
            set(0, St.RUN)
            val internet = cm.hasInternetReally()
            if (internet) set(0, St.OK, if (cm.onWifiLike()) "Wi-Fi" else "Dados móveis")
            else set(0, St.FAIL, "Sem acesso à internet", "Conecte-se a uma rede. Na mesma Wi-Fi do PC, a conexão direta ainda pode funcionar.")

            if (pc == null) {
                (1 until items.size).forEach { set(it, St.SKIP) }
                set(2, St.FAIL, "Nenhum PC pareado", "Pareie seu computador para começar.")
                needsPair = true
                running = false
                return@launch
            }
            // 2. relay
            set(1, St.RUN)
            val relay = pc.relayUrl
            val relayMs = relay?.let { cm.relayLatency(it) }
            when {
                relay == null -> set(1, St.SKIP, "Acesso remoto desativado neste PC")
                relayMs != null -> set(1, St.OK, "$relayMs ms")
                !internet -> set(1, St.SKIP, "Sem internet")
                else -> set(1, St.FAIL, "Não respondeu", "O serviço de conexão pode estar instável. Tente de novo em instantes; na rede local o Noc continua funcionando.")
            }
            // 3. presença
            set(2, St.RUN)
            val presence = if (relayMs != null) cm.presence(pc) else null
            when {
                presence == null -> set(2, St.SKIP, "Não foi possível consultar")
                presence.first -> set(2, St.OK, "${pc.name} conectado ao serviço")
                else -> set(2, St.FAIL, presence.second?.let { "Visto por último ${Texts.relative(it)}" } ?: "Nunca visto online",
                    "Ligue o computador, verifique a internet dele e abra o Noc Companion (ele pode iniciar junto com o Windows).")
            }
            // 4-5. sessão
            set(3, St.RUN); set(4, St.RUN)
            cm.retryNow()
            val state = withTimeoutOrNull(20_000) {
                cm.state.first { it is ConnState.Online || (it is ConnState.Offline) }
            }
            when (state) {
                is ConnState.Online -> {
                    val t0 = System.currentTimeMillis()
                    val ok = runCatching { cm.call("ping", timeoutMs = 8_000) }.isSuccess
                    set(3, if (ok) St.OK else St.WARN, if (ok) "${System.currentTimeMillis() - t0} ms · ${Texts.route(state.route)}" else "Lento para responder")
                    set(4, St.OK, "Chaves conferidas nos dois lados")
                }
                is ConnState.Offline -> {
                    val e = Texts.problem(state.problem)
                    val authProblem = state.problem is Problem.Revoked || state.problem is Problem.PcForgotDevice || state.problem is Problem.SecurityMismatch
                    if (authProblem) {
                        set(3, St.OK, "O PC respondeu")
                        set(4, St.FAIL, e.title, e.body)
                        needsPair = true
                    } else {
                        set(3, St.FAIL, e.title, e.body)
                        set(4, St.SKIP)
                    }
                }
                else -> { set(3, St.FAIL, "Tempo esgotado", "O PC não respondeu em 20 s."); set(4, St.SKIP) }
            }
            if (state !is ConnState.Online) {
                (5 until items.size - 1).forEach { set(it, St.SKIP) }
                if (container.chat.notifier.canNotify()) set(items.size - 1, St.OK) else set(items.size - 1, St.WARN, "Bloqueadas")
                running = false
                return@launch
            }
            // 6. LM Studio
            set(5, St.RUN)
            val status = runCatching { com.noc.app.core.net.PcStatus.parse(cm.call("status")) }.getOrNull()
            when (status?.lm) {
                LmState.RUNNING -> set(5, St.OK, "Servidor local ativo")
                LmState.STOPPED -> set(5, St.FAIL, "Servidor desligado", "Toque em “Iniciar LM Studio” na tela inicial ou abra o LM Studio no PC.")
                LmState.NOT_INSTALLED -> set(5, St.FAIL, "Não encontrado", "Instale o LM Studio no PC (lmstudio.ai) e baixe um modelo.")
                else -> set(5, St.FAIL, "Sem resposta do LM Studio", "Reinicie o LM Studio no PC.")
            }
            // 7. modelo
            set(6, St.RUN)
            val models = runCatching { cm.refreshModels(); cm.models.value }.getOrDefault(emptyList()).filter { it.isLlm }
            when {
                status?.lm != LmState.RUNNING -> set(6, St.SKIP)
                status.loaded.isNotEmpty() -> set(6, St.OK, "${status.loaded.first().name} · ${Texts.tokens(status.loaded.first().context)} ctx")
                models.isNotEmpty() -> set(6, St.WARN, "Nenhum carregado (${models.size} disponível)", "Tudo bem: o modelo é carregado sozinho ao enviar. Para ser mais rápido, carregue um em Modelos.")
                else -> set(6, St.FAIL, "Nenhum modelo no PC", "Baixe um modelo de conversa no LM Studio.")
            }
            // 8. LAN
            set(7, St.RUN)
            when {
                state.route == Route.LAN -> set(7, St.OK, "Conectado direto pela Wi-Fi")
                container.prefs.current().forceRelay -> set(7, St.WARN, "Conexão remota forçada nos Ajustes", "Desligue “Sempre usar conexão remota” em Ajustes para usar a Wi-Fi direta em casa.")
                !cm.onWifiLike() -> set(7, St.SKIP, "Fora da Wi-Fi: usando conexão remota cifrada")
                pc.lanEndpoints.isEmpty() -> set(7, St.WARN, "PC sem endereço local conhecido")
                else -> set(7, St.WARN, "Em outra rede ou bloqueado pelo firewall", "Se estiver na mesma Wi-Fi, permita o Noc Companion no Firewall do Windows (redes privadas). O remoto continua funcionando.")
            }
            // 9. geração
            if (status?.lm == LmState.RUNNING && models.isNotEmpty()) {
                set(8, St.RUN, "Gerando…")
                val model = status.loaded.firstOrNull()?.key ?: models.firstOrNull { it.fitsGpu != false }?.key ?: models.first().key
                val result = runCatching {
                    container.chat.quickGenerate(
                        model,
                        JsonArray(listOf(buildJsonObject { put("role", "user"); put("content", "Responda só: ok") })),
                        buildJsonObject { put("reasoning", "off"); put("max_tokens", 8); put("temperature", 0) },
                    ).end
                }
                result.onSuccess { e ->
                    val s = e.obj("stats")
                    if (e.str("reason") == "error") set(8, St.FAIL, Texts.replyError(e.str("error")))
                    else set(8, St.OK, listOfNotNull(
                        s?.long("ttftMs")?.let { "1º token em ${Texts.seconds(it)}" },
                        s?.dbl("tps")?.let { "%.0f tok/s".format(it) },
                    ).joinToString(" · "))
                }.onFailure { set(8, St.FAIL, "O teste não terminou", "Veja se o PC não está sobrecarregado e tente de novo.") }
            } else set(8, St.SKIP)
            // 10. perfis: cada um com modelo, sem erro de carga
            set(9, St.RUN)
            val fresh = cm.models.value
            val tierIssues = com.noc.app.core.net.Tier.all.mapNotNull { t ->
                val key = status?.tiers?.get(t)?.model ?: return@mapNotNull "${com.noc.app.core.net.Tier.label(t)} sem modelo"
                val m = fresh.firstOrNull { it.key == key } ?: return@mapNotNull "${com.noc.app.core.net.Tier.label(t)}: modelo não encontrado no PC"
                m.lastError?.let { "${com.noc.app.core.net.Tier.label(t)} (${m.name}): $it" }
                    ?: if (m.fitsGpu == false) "${com.noc.app.core.net.Tier.label(t)} (${m.name}): não cabe na GPU" else null
            }
            when {
                !(state.pc.has("tiers")) -> set(9, St.WARN, "Companion antigo", "Atualize o Noc Companion no PC para ter perfis, imagens e voz.")
                tierIssues.isEmpty() -> set(9, St.OK, status?.tiers?.values?.joinToString(" · ") { it.name })
                else -> set(9, St.WARN, tierIssues.joinToString("\n"), "Abra Modelos para escolher outro modelo ou ver o detalhe do erro.")
            }
            // 11. visão
            set(10, St.RUN)
            val visionModels = fresh.filter { it.isLlm && it.vision && it.fitsGpu != false }
            if (visionModels.isNotEmpty()) set(10, St.OK, visionModels.joinToString(" · ") { it.name })
            else set(10, St.WARN, "Nenhum modelo com visão", "Baixe um modelo com visão (ex.: Qwen 3.5 VL, Gemma 4) no LM Studio ou coloque o GGUF e o mmproj no Downloads: o Companion adiciona sozinho.")
            // 12. voz
            set(11, St.RUN)
            val stt = status?.stt
            when {
                !container.voice.hasPermission() -> set(11, St.WARN, "Sem permissão do microfone", "Toque no microfone numa conversa e permita o acesso.")
                stt == null || stt.state == "off" -> set(11, St.WARN, "Desligado no PC", "Ative “Ditado por voz” nos Ajustes do Noc Companion.")
                stt.state == "ready" -> set(11, St.OK, "Transcrição pronta no PC")
                stt.state == "failed" -> set(11, St.FAIL, stt.error ?: "Transcrição indisponível", "Reinicie o Noc Companion no PC.")
                else -> set(11, St.WARN, "Preparando no PC" + (stt.progress?.let { " · ${(it * 100).toInt()}%" } ?: ""))
            }
            // 13. notificações
            set(12, St.RUN)
            if (container.chat.notifier.canNotify()) set(12, St.OK, "Avisamos quando uma resposta terminar")
            else set(12, St.WARN, "Bloqueadas", "Permita as notificações do Noc para saber quando uma resposta fica pronta com o app fechado.")
            running = false
        }
    }

    LaunchedEffect(Unit) { run() }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        TopBar("Diagnóstico", onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(
                "Verifico cada etapa entre este celular e o modelo no seu PC.",
                style = MaterialTheme.typography.bodyMedium, color = c.text2, modifier = Modifier.padding(bottom = 12.dp),
            )
            items.forEach { ItemRow(it) }
            Spacer(Modifier.height(24.dp))
        }
        Row(Modifier.navigationBarsPadding().padding(20.dp)) {
            if (needsPair) {
                PrimaryButton("Parear novamente", Modifier.weight(1f)) { nav.navigate(Routes.pair()) }
                Spacer(Modifier.width(10.dp))
            }
            SecondaryButton(if (running) "Verificando…" else "Verificar de novo", Modifier.weight(1f), enabled = !running) { run() }
        }
    }
}

@Composable
private fun ItemRow(item: Item) {
    val c = Noc.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (item.st == St.FAIL) c.errSoft else c.bg)
            .animateContentSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (item.st) {
                St.RUN -> CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                St.OK -> Icon(Icons.Rounded.CheckCircle, "OK", tint = c.ok)
                St.WARN -> Icon(Icons.Rounded.ErrorOutline, "Atenção", tint = c.warn)
                St.FAIL -> Icon(Icons.Rounded.ErrorOutline, "Falhou", tint = c.err)
                St.SKIP -> Icon(Icons.Rounded.RemoveCircleOutline, "Não verificado", tint = c.text3)
                St.WAIT -> Icon(Icons.Rounded.RadioButtonUnchecked, "Aguardando", tint = c.surface3)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, color = if (item.st == St.WAIT || item.st == St.SKIP) c.text3 else c.text)
            item.detail?.let { Text(it, style = MonoSmall, color = if (item.st == St.FAIL) c.err else c.text3) }
            item.fix?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.text2, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}
