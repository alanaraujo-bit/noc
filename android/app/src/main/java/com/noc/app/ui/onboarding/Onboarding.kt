package com.noc.app.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.noc.app.AppContainer
import com.noc.app.core.net.ConnState
import com.noc.app.core.net.LmState
import com.noc.app.core.net.dbl
import com.noc.app.core.net.obj
import com.noc.app.core.net.str
import com.noc.app.ui.Routes
import com.noc.app.ui.Texts
import com.noc.app.ui.components.PrimaryButton
import com.noc.app.ui.components.SecondaryButton
import com.noc.app.ui.home.Wordmark
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.Noc
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private data class Page(val title: String, val body: String, val art: Int)

private val pages = listOf(
    Page("Sua IA.\nSeu computador.\nSeus dados.", "Converse de qualquer lugar com os modelos que rodam no LM Studio do seu PC — como num app de IA, mas tudo seu.", 0),
    Page("O trabalho pesado\nfica no PC.", "Seu celular envia a pergunta; o computador pensa e responde em tempo real. Por isso ele precisa estar ligado, com o LM Studio instalado.", 1),
    Page("Três passos\nno computador.", "1. Instale o LM Studio e baixe um modelo.\n2. Instale e abra o Noc Companion — ele encontra o LM Studio sozinho.\n3. No Companion, toque em “Parear celular”.", 2),
    Page("Privado de\nponta a ponta.", "A conversa sai cifrada do celular e só abre no seu PC. Nem o serviço que conecta vocês pela internet consegue ler. Nada vai para a nuvem.", 3),
)

@Composable
fun OnboardingScreen(onPair: () -> Unit) {
    val c = Noc.colors
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp)) { Wordmark() }
        HorizontalPager(pager, Modifier.weight(1f)) { i ->
            val p = pages[i]
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Art(p.art, Modifier.fillMaxWidth().height(220.dp))
                Spacer(Modifier.height(28.dp))
                Text(p.title, style = MaterialTheme.typography.displayLarge, color = c.text)
                Spacer(Modifier.height(16.dp))
                Text(p.body, style = MaterialTheme.typography.bodyLarge, color = c.text2)
            }
        }
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                repeat(pages.size) { i ->
                    val on = pager.currentPage == i
                    val w by animateDpAsState(if (on) 22.dp else 7.dp, label = "w")
                    val col by animateColorAsState(if (on) c.accent else c.surface3, label = "c")
                    Box(Modifier.height(7.dp).width(w).clip(CircleShape).background(col))
                }
            }
            val last = pager.currentPage == pages.lastIndex
            PrimaryButton(if (last) "Parear agora" else "Continuar", height = 52.dp) {
                if (last) onPair() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            }
        }
    }
}

/** Ilustrações geométricas com a marca: ponto (sua máquina) + anel (conexão). */
@Composable
private fun Art(kind: Int, modifier: Modifier) {
    val c = Noc.colors
    Canvas(modifier) {
        val cy = size.height / 2
        val stroke = 3.dp.toPx()
        when (kind) {
            0 -> {
                val cx = size.width / 2
                drawCircle(c.accent, 26.dp.toPx(), Offset(cx, cy))
                drawCircle(c.text, 66.dp.toPx(), Offset(cx, cy), style = Stroke(stroke))
                drawCircle(c.line, 100.dp.toPx(), Offset(cx, cy), style = Stroke(1.dp.toPx()))
            }
            1 -> {
                val left = Offset(size.width * 0.22f, cy)
                val right = Offset(size.width * 0.78f, cy)
                // celular
                drawRoundRect(c.text, Offset(left.x - 26.dp.toPx(), cy - 46.dp.toPx()), Size(52.dp.toPx(), 92.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()), style = Stroke(stroke))
                // PC
                drawRoundRect(c.text, Offset(right.x - 56.dp.toPx(), cy - 40.dp.toPx()), Size(112.dp.toPx(), 74.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()), style = Stroke(stroke))
                drawCircle(c.accent, 14.dp.toPx(), Offset(right.x, cy - 3.dp.toPx()))
                drawLine(c.text2, Offset(left.x + 34.dp.toPx(), cy), Offset(right.x - 64.dp.toPx(), cy), stroke / 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 12f)))
            }
            2 -> {
                val step = size.width / 4
                for (i in 0..2) {
                    val x = step * (i + 1)
                    drawCircle(if (i == 2) c.accent else c.surface3, 22.dp.toPx(), Offset(x, cy))
                    if (i < 2) drawLine(c.line, Offset(x + 28.dp.toPx(), cy), Offset(x + step - 28.dp.toPx(), cy), stroke)
                }
            }
            else -> {
                val cx = size.width / 2
                drawRoundRect(c.text, Offset(cx - 44.dp.toPx(), cy - 14.dp.toPx()), Size(88.dp.toPx(), 72.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()), style = Stroke(stroke))
                drawArc(c.text, 180f, 180f, false, Offset(cx - 28.dp.toPx(), cy - 58.dp.toPx()), Size(56.dp.toPx(), 88.dp.toPx()), style = Stroke(stroke))
                drawCircle(c.accent, 9.dp.toPx(), Offset(cx, cy + 20.dp.toPx()))
            }
        }
    }
}

// ------------------------------------------------------------------ verificação após parear

private enum class Check { PENDING, RUNNING, OK, FAIL }

@Composable
fun SetupCheckScreen(container: AppContainer, onDone: (String?) -> Unit) {
    val c = Noc.colors
    val conn by container.connection.state.collectAsState()
    val status by container.connection.status.collectAsState()
    val scope = rememberCoroutineScope()
    var reply by remember { mutableStateOf("") }
    var tps by remember { mutableStateOf<Double?>(null) }
    var testState by remember { mutableStateOf(Check.PENDING) }
    var testError by remember { mutableStateOf<String?>(null) }
    var startingLm by remember { mutableStateOf(false) }

    val connected = conn is ConnState.Online
    val lmOk = status?.lm == LmState.RUNNING
    val hasModel = (status?.llmCount ?: 0) > 0

    fun runTest() {
        testState = Check.RUNNING
        testError = null
        reply = ""
        scope.launch {
            try {
                val models = container.connection.models.first { it.isNotEmpty() }
                val model = status?.loaded?.firstOrNull()?.key
                    ?: models.firstOrNull { it.isLlm && it.fitsGpu != false }?.key
                    ?: models.first { it.isLlm }.key
                val r = container.chat.quickGenerate(
                    model,
                    JsonArray(listOf(buildJsonObject {
                        put("role", "user"); put("content", "Diga olá e se apresente em uma frase curta, em português.")
                    })),
                    buildJsonObject { put("reasoning", "off"); put("max_tokens", 60); put("temperature", 0.6) },
                ) { reply = it }
                tps = r.end.obj("stats")?.dbl("tps")
                if (r.end.str("reason") == "error") testError = Texts.replyError(r.end.str("error"))
                testState = if (testError == null && reply.isNotBlank()) Check.OK else Check.FAIL
            } catch (e: Exception) {
                testState = Check.FAIL
                testError = testError ?: "O teste não terminou. Verifique o PC e tente de novo."
            }
        }
    }

    LaunchedEffect(connected, lmOk, hasModel) {
        if (connected && lmOk && hasModel && testState == Check.PENDING) runTest()
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Testando a\nconexão.", style = MaterialTheme.typography.displayLarge, color = c.text)
        Spacer(Modifier.height(24.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CheckRow("Pareado com o PC", if (connected) Check.OK else Check.RUNNING, (conn as? ConnState.Online)?.let { "${it.pc.name} · ${Texts.route(it.route)}" })
            CheckRow("LM Studio ativo", when { !connected -> Check.PENDING; lmOk -> Check.OK; status == null -> Check.RUNNING; else -> Check.FAIL },
                if (connected && status != null && !lmOk) "O servidor local está desligado." else null)
            if (connected && status != null && !lmOk && status?.lm != LmState.NOT_INSTALLED) {
                SecondaryButton(if (startingLm) "Iniciando…" else "Iniciar LM Studio no PC", enabled = !startingLm, modifier = Modifier.padding(start = 40.dp)) {
                    startingLm = true
                    scope.launch { runCatching { container.connection.call("lms.start", timeoutMs = 100_000) }; startingLm = false }
                }
            }
            CheckRow("Modelos disponíveis", when { !lmOk -> Check.PENDING; hasModel -> Check.OK; else -> Check.FAIL },
                if (lmOk && !hasModel) "Baixe um modelo no LM Studio." else status?.let { if (hasModel) "${it.llmCount} modelo(s) no PC" else null })
            CheckRow("Primeira resposta", testState, testError ?: tps?.let { "%.0f tokens/s".format(it) })
            if (reply.isNotBlank()) {
                Text(
                    "“${reply.trim()}”",
                    style = MaterialTheme.typography.headlineLarge.copy(fontStyle = FontStyle.Italic),
                    color = c.text,
                    modifier = Modifier.padding(start = 40.dp, top = 8.dp, end = 8.dp),
                )
            }
            if (testState == Check.FAIL) {
                SecondaryButton("Testar de novo", modifier = Modifier.padding(start = 40.dp, top = 8.dp)) { runTest() }
            }
        }
        PrimaryButton(
            if (testState == Check.OK) "Começar a conversar" else "Pular por agora",
            Modifier.fillMaxWidth(), height = 56.dp,
        ) { onDone(if (testState == Check.OK) Routes.NEW else null) }
    }
}

@Composable
private fun CheckRow(title: String, state: Check, detail: String?) {
    val c = Noc.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (state) {
                Check.RUNNING -> CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Check.OK -> Icon(Icons.Rounded.CheckCircle, null, tint = c.ok)
                Check.FAIL -> Icon(Icons.Rounded.ErrorOutline, null, tint = c.err)
                Check.PENDING -> Icon(Icons.Rounded.RadioButtonUnchecked, null, tint = c.surface3)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (state == Check.PENDING) c.text3 else c.text)
            detail?.let { Text(it, style = MonoSmall, color = if (state == Check.FAIL) c.err else c.text3) }
        }
    }
}

