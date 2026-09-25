package com.noc.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.noc.app.AppContainer
import com.noc.app.data.prefs.AppPrefs
import com.noc.app.data.prefs.LockPrivacy
import com.noc.app.ui.chat.Segmented
import com.noc.app.ui.components.Chip
import com.noc.app.ui.components.GhostButton
import com.noc.app.ui.components.Group
import com.noc.app.ui.components.GroupDivider
import com.noc.app.ui.components.RowItem
import com.noc.app.ui.components.SecondaryButton
import com.noc.app.ui.components.SectionLabel
import com.noc.app.ui.components.ToggleRow
import com.noc.app.ui.components.TopBar
import com.noc.app.ui.theme.Noc
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceSettingsScreen(container: AppContainer, prefs: AppPrefs, onBack: () -> Unit) {
    val c = Noc.colors
    val scope = rememberCoroutineScope()
    val status by container.connection.status.collectAsState()
    var newTerm by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        TopBar("Voz e ditado", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(
                "Toque no microfone para ditar. O áudio vai cifrado para o seu PC, que transcreve na GPU — nada passa por serviços externos e nenhuma gravação é guardada.",
                style = MaterialTheme.typography.bodyMedium, color = c.text2, modifier = Modifier.padding(vertical = 8.dp),
            )
            SectionLabel("No seu PC", Modifier.padding(top = 10.dp))
            Group {
                val stt = status?.stt
                RowItem(
                    "Transcrição",
                    when (stt?.state) {
                        "ready" -> "Pronta · Whisper large-v3-turbo"
                        "loading" -> "Preparando…"
                        "downloading" -> "Baixando o modelo de voz" + (stt.progress?.let { " · ${(it * 100).toInt()}%" } ?: "")
                        "failed" -> stt.error ?: "Indisponível"
                        "off" -> "Desligada no Companion"
                        else -> "Conecte-se ao PC para ver"
                    },
                    icon = Icons.Rounded.Mic, trailing = null,
                )
            }
            SectionLabel("Jeito de falar", Modifier.padding(top = 22.dp))
            Group {
                ToggleRow("Segurar para falar", "Segure o microfone enquanto fala e solte para transcrever. O toque simples também funciona.", prefs.holdToTalk, null) {
                    scope.launch { container.prefs.setHoldToTalk(it) }
                }
            }
            SectionLabel("Dicionário pessoal", Modifier.padding(top = 22.dp))
            Text(
                "Nomes, marcas e termos técnicos que você fala com frequência. Ajudam a transcrição a escrever do jeito certo.",
                style = MaterialTheme.typography.bodySmall, color = c.text3, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(c.surface).padding(horizontal = 16.dp, vertical = 14.dp)) {
                    if (newTerm.isEmpty()) Text("Novo termo (ex.: Kubernetes)", style = MaterialTheme.typography.bodyLarge, color = c.text3)
                    BasicTextField(newTerm, { newTerm = it.take(40) }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.width(8.dp))
                SecondaryButton("Adicionar") {
                    val t = newTerm.trim()
                    if (t.isNotEmpty()) scope.launch { container.prefs.setVocab(prefs.vocab + t); newTerm = "" }
                }
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                prefs.vocab.forEach { term ->
                    Chip("$term  ×") { scope.launch { container.prefs.setVocab(prefs.vocab - term) } }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
fun NotificationSettingsScreen(container: AppContainer, prefs: AppPrefs, onBack: () -> Unit) {
    val c = Noc.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch { container.prefs.setNotifyWhenDone(granted) }
    }
    val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        TopBar("Notificações", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            if (!granted) {
                Row(Modifier.padding(vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.warnSoft).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("As notificações estão bloqueadas para o Noc.", style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
                    GhostButton("Permitir") { if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                }
            }
            SectionLabel("Avisar", Modifier.padding(top = 10.dp))
            Group {
                ToggleRow("Resposta pronta", "Quando você não está vendo a conversa", prefs.notifyWhenDone, Icons.Rounded.Notifications) { on ->
                    if (on && !granted && Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else scope.launch { container.prefs.setNotifyWhenDone(on) }
                }
                GroupDivider()
                ToggleRow("Progresso enquanto o PC trabalha", "Uma única notificação discreta, com tempo e etapa", prefs.notifyProgress, null) { scope.launch { container.prefs.setNotifyProgress(it) } }
                GroupDivider()
                ToggleRow("Modelos", "“Qwen 3.8 pronto”, ou por que não carregou", prefs.notifyModels, null) { scope.launch { container.prefs.setNotifyModels(it) } }
                GroupDivider()
                ToggleRow("Status do PC", "“Seu PC ficou offline” ou “disponível”", prefs.notifyPcStatus, null) { scope.launch { container.prefs.setNotifyPcStatus(it) } }
            }
            SectionLabel("Conteúdo na tela bloqueada", Modifier.padding(top = 22.dp))
            Segmented(
                listOf(LockPrivacy.FULL.name to "Completo", LockPrivacy.NOTICE.name to "Só o aviso", LockPrivacy.HIDDEN.name to "Ocultar"),
                prefs.lockPrivacy.name,
            ) { v -> scope.launch { container.prefs.setLockPrivacy(LockPrivacy.valueOf(v!!)) } }
            Text(
                when (prefs.lockPrivacy) {
                    LockPrivacy.FULL -> "Mostra o modelo e um trecho da resposta."
                    LockPrivacy.NOTICE -> "Mostra só “Sua resposta está pronta”, sem pergunta nem resposta."
                    LockPrivacy.HIDDEN -> "Nada aparece com a tela bloqueada."
                },
                style = MaterialTheme.typography.bodySmall, color = c.text3, modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
            Spacer(Modifier.height(18.dp))
            GhostButton("Categorias no Android") {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
