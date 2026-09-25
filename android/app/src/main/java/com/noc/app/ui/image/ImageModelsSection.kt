package com.noc.app.ui.image

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.noc.app.AppContainer
import com.noc.app.chat.ImageCaps
import com.noc.app.core.net.RpcError
import com.noc.app.ui.Texts
import com.noc.app.ui.components.SectionLabel
import com.noc.app.ui.components.StatusDot
import com.noc.app.ui.components.pressable
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.Noc
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Modelos de imagem do PC: estado real e instalação remota. O download acontece no PC; o celular só acompanha
 * (dezenas de GB nunca passam pelo celular).
 */
@Composable
fun ImageModelsSection(container: AppContainer) {
    val c = Noc.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val caps by container.chat.images.caps.collectAsState()
    LaunchedEffect(Unit) { container.chat.images.refreshCaps() }
    val models = caps?.models ?: return
    Column {
        SectionLabel("Imagem", Modifier.padding(top = 10.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)) {
            models.forEachIndexed { i, m ->
                ImageModelRow(m, caps!!) { method ->
                    scope.launch {
                        runCatching { container.connection.call(method, buildJsonObject { put("model", m.id) }, timeoutMs = 30_000) }
                            .onSuccess { container.chat.images.refreshCaps() }
                            .onFailure { Toast.makeText(context, (it as? RpcError)?.message ?: "Não foi possível agora.", Toast.LENGTH_SHORT).show() }
                    }
                }
                if (i < models.lastIndex) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(c.line))
            }
        }
        Text(
            "Os modelos rodam na GPU do seu PC. Downloads e atualizações acontecem no PC; aqui você acompanha.",
            style = MaterialTheme.typography.bodySmall, color = c.text3, modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
    }
}

@Composable
private fun ImageModelRow(m: ImageCaps.ModelState, caps: ImageCaps, onAction: (String) -> Unit) {
    val c = Noc.colors
    val busy = m.state in setOf("downloading", "verifying", "checking")
    val (status, color) = when {
        m.state == "installed" && m.updateAvailable -> "Atualização disponível" to c.accent
        m.state == "installed" -> "Instalado · pronto" to c.ok
        busy -> (if (m.state == "verifying") "Conferindo os arquivos" else "Baixando no PC") to c.accent
        m.state == "auth_required" || (m.gated && caps.hfUser == null && !m.installed) -> "Autorização necessária" to c.warn
        m.state == "terms_required" -> "Aceite os termos no Hugging Face" to c.warn
        m.state == "no_space" -> "Espaço insuficiente no PC" to c.err
        m.state == "paused" -> "Pausado" to c.text3
        m.state == "failed" -> "Falhou" to c.err
        else -> "Não instalado" to c.text3
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (m.profile == "fast") "⚡" else "◆", style = MaterialTheme.typography.titleLarge, color = c.accent, modifier = Modifier.width(34.dp))
            Column(Modifier.weight(1f)) {
                Text(m.name, style = MaterialTheme.typography.titleMedium, color = c.text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color, pulsing = busy)
                    Spacer(Modifier.width(6.dp))
                    Text(status, style = MaterialTheme.typography.bodySmall, color = c.text2)
                }
            }
            val action = when {
                busy -> "Pausar" to "image.pause"
                m.state == "installed" && m.updateAvailable -> "Atualizar" to "image.update"
                m.state == "installed" -> null
                m.gated && caps.hfUser == null -> null
                else -> (if (m.state == "paused" || m.state == "failed") "Continuar" else "Instalar") to "image.install"
            }
            action?.let { (label, method) ->
                Text(label, style = MaterialTheme.typography.labelMedium, color = c.accent,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).pressable { onAction(method) }.padding(horizontal = 12.dp, vertical = 10.dp))
            }
        }
        if (busy && m.total != null && m.total > 0) {
            val p = ((m.done ?: 0).toFloat() / m.total).coerceIn(0f, 1f)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.padding(start = 34.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.surface2)) {
                Box(Modifier.fillMaxWidth(p).height(4.dp).background(c.accent))
            }
            Text(
                listOfNotNull("${Texts.bytes(m.done ?: 0)} / ${Texts.bytes(m.total)}", "${(p * 100).toInt()}%",
                    m.bps?.takeIf { it > 1 }?.let { "${(it / 1_000_000).toInt()} MB/s" }).joinToString(" · "),
                style = MonoSmall, color = c.text3, modifier = Modifier.padding(start = 34.dp, top = 4.dp),
            )
        } else {
            val detail = m.detail ?: when {
                m.state == "installed" -> Texts.bytes(m.sizeBytes) + (m.license?.let { " · $it" } ?: "")
                m.gated && caps.hfUser == null -> "Precisa aceitar os termos com a sua conta do Hugging Face. Conecte a conta no Companion do PC (página Imagens)."
                else -> "Download de ${Texts.bytes(m.sizeBytes)} feito pelo PC"
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.text3, modifier = Modifier.padding(start = 34.dp, top = 6.dp))
        }
    }
}
