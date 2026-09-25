package com.noc.app.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.TaskAlt
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.noc.app.AppContainer
import com.noc.app.BuildConfig
import com.noc.app.core.net.ConnState
import com.noc.app.core.net.DeviceInfo
import com.noc.app.core.net.SecurityEventInfo
import com.noc.app.core.net.arr
import com.noc.app.core.net.asObj
import com.noc.app.data.db.PcEntity
import com.noc.app.data.prefs.AppPrefs
import com.noc.app.data.prefs.ThemeMode
import com.noc.app.ui.Routes
import com.noc.app.ui.Texts
import com.noc.app.ui.chat.NocSheet
import com.noc.app.ui.chat.Segmented
import com.noc.app.ui.components.GhostButton
import com.noc.app.ui.components.Group
import com.noc.app.ui.components.GroupDivider
import com.noc.app.ui.components.PrimaryButton
import com.noc.app.ui.components.RowItem
import com.noc.app.ui.components.SecondaryButton
import com.noc.app.ui.components.SectionLabel
import com.noc.app.ui.components.StatusDot
import com.noc.app.ui.components.ToggleRow
import com.noc.app.ui.components.TopBar
import com.noc.app.ui.components.pressable
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.Noc
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun SettingsScreen(container: AppContainer, prefs: AppPrefs, nav: NavHostController) {
    val c = Noc.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conn by container.connection.state.collectAsState()
    val pc by container.connection.activePc.collectAsState()
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch { container.prefs.setNotifyWhenDone(granted) }
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        TopBar("Ajustes", onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            SectionLabel("Computador", Modifier.padding(top = 8.dp))
            Group {
                RowItem(
                    pc?.name ?: "Nenhum PC pareado",
                    subtitle = when (val s = conn) {
                        is ConnState.Online -> "Conectado · ${Texts.route(s.route)}"
                        is ConnState.Connecting -> "Conectando…"
                        is ConnState.Offline -> Texts.problem(s.problem).title
                        ConnState.NoPc -> "Toque para parear"
                        ConnState.Paused -> "Pronto"
                    },
                    icon = Icons.Rounded.Computer,
                ) { nav.navigate(if (pc == null) Routes.pair() else Routes.COMPUTERS) }
                GroupDivider()
                RowItem("Status do PC", "GPU, VRAM, modelo, velocidade", icon = Icons.Rounded.Speed) { nav.navigate(Routes.STATUS) }
                GroupDivider()
                RowItem("Atividade", "Tarefas em andamento e concluídas", icon = Icons.Rounded.TaskAlt) { nav.navigate(Routes.ACTIVITY) }
                GroupDivider()
                RowItem("Diagnóstico", "Descubra por que algo não está funcionando", icon = Icons.Rounded.MonitorHeart) { nav.navigate(Routes.DIAGNOSTICS) }
            }

            SectionLabel("IA", Modifier.padding(top = 22.dp))
            Group {
                RowItem("Modelos", "Rápido, Inteligente, Profundo e todos os seus modelos", icon = Icons.Rounded.Memory) { nav.navigate(Routes.MODELS) }
                GroupDivider()
                RowItem("Estilos", "Programação, Pesquisa, Criatividade…", icon = Icons.Rounded.Tune) { nav.navigate(Routes.PRESETS) }
                GroupDivider()
                RowItem("Voz e ditado", "Transcrição no seu PC, dicionário pessoal", icon = Icons.Rounded.Mic) { nav.navigate(Routes.VOICE) }
                GroupDivider()
                RowItem("Biblioteca de prompts", "Instruções de sistema reutilizáveis", icon = Icons.Rounded.TextSnippet) { nav.navigate(Routes.prompts()) }
            }

            SectionLabel("Conversa", Modifier.padding(top = 22.dp))
            Group {
                ToggleRow("Mostrar estatísticas", "Tokens por segundo e tempo de resposta", prefs.showStats, Icons.Rounded.Speed) { scope.launch { container.prefs.setShowStats(it) } }
                GroupDivider()
                ToggleRow("Mostrar raciocínio", "Exibe o “pensamento” do modelo, recolhido", prefs.showReasoning, Icons.Rounded.Psychology) { scope.launch { container.prefs.setShowReasoning(it) } }
                GroupDivider()
                ToggleRow("Enviar com Enter", "Enter envia em vez de pular linha", prefs.sendWithEnter, Icons.Rounded.KeyboardReturn) { scope.launch { container.prefs.setSendWithEnter(it) } }
                GroupDivider()
                RowItem("Notificações", if (prefs.notifyWhenDone) "Avisos, progresso e privacidade na tela bloqueada" else "Desligadas", icon = Icons.Rounded.Notifications) { nav.navigate(Routes.NOTIFICATIONS) }
            }

            SectionLabel("Aparência", Modifier.padding(top = 22.dp))
            Segmented(
                listOf(ThemeMode.SYSTEM.name to "Sistema", ThemeMode.LIGHT.name to "Claro", ThemeMode.DARK.name to "Escuro"),
                prefs.theme.name,
            ) { v -> scope.launch { container.prefs.setTheme(ThemeMode.valueOf(v!!)) } }

            SectionLabel("Privacidade e segurança", Modifier.padding(top = 22.dp))
            Group {
                RowItem("Segurança", "Identidades, criptografia e registro de acessos", icon = Icons.Rounded.Shield) { nav.navigate(Routes.SECURITY) }
                GroupDivider()
                ToggleRow("Sempre usar conexão remota", "Para testes: ignora a rede local e passa pelo relay cifrado", prefs.forceRelay, Icons.Rounded.Public) {
                    scope.launch { container.prefs.setForceRelay(it); container.connection.reconnect() }
                }
            }

            Spacer(Modifier.height(28.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Minha IA. Meu computador. Meus dados.", style = MaterialTheme.typography.headlineLarge.copy(fontStyle = FontStyle.Italic), color = c.text2)
                Spacer(Modifier.height(6.dp))
                Text("Noc ${BuildConfig.VERSION_NAME} · conversas guardadas só neste celular", style = MonoSmall, color = c.text3)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

// ------------------------------------------------------------------ computadores e aparelhos

@Composable
fun ComputersScreen(container: AppContainer, nav: NavHostController) {
    val c = Noc.colors
    val scope = rememberCoroutineScope()
    val pcs by container.db.pcs().observeAll().collectAsState(initial = emptyList())
    val active by container.connection.activePc.collectAsState()
    val conn by container.connection.state.collectAsState()
    var devices by remember { mutableStateOf<List<DeviceInfo>?>(null) }
    var forget by remember { mutableStateOf<PcEntity?>(null) }
    var renamePc by remember { mutableStateOf(false) }
    var revoke by remember { mutableStateOf<DeviceInfo?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(conn is ConnState.Online, reload) {
        if (conn is ConnState.Online) {
            devices = runCatching {
                container.connection.call("devices.list").arr("devices")?.mapNotNull { it.asObj() }?.map { DeviceInfo.parse(it) }
            }.getOrNull()
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        TopBar("Computadores", onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            SectionLabel("Pareados com este celular", Modifier.padding(top = 8.dp))
            Group {
                pcs.forEachIndexed { i, pc ->
                    if (i > 0) GroupDivider()
                    val isActive = pc.id == active?.id
                    RowItem(
                        pc.name,
                        subtitle = listOfNotNull(
                            if (isActive) (conn as? ConnState.Online)?.let { "Conectado · ${Texts.route(it.route)}" } ?: "Em uso" else "Toque para usar",
                            pc.lastConnectedAt?.let { "visto ${Texts.relative(it)}" },
                        ).joinToString(" · "),
                        icon = Icons.Rounded.Computer,
                        iconTint = if (isActive) c.accent else null,
                        trailing = {
                            Box(Modifier.clip(RoundedCornerShape(10.dp)).pressable { forget = pc }.padding(10.dp)) {
                                Icon(Icons.Rounded.Delete, "Esquecer", tint = c.text3, modifier = Modifier.size(20.dp))
                            }
                        },
                    ) { if (!isActive) scope.launch { container.connection.switchPc(pc.id) } }
                }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Parear outro PC", icon = Icons.Rounded.Add) { nav.navigate(Routes.pair()) }
                if (conn is ConnState.Online) SecondaryButton("Renomear PC", icon = Icons.Rounded.Edit) { renamePc = true }
            }

            SectionLabel("Aparelhos com acesso a ${active?.name ?: "este PC"}", Modifier.padding(top = 26.dp))
            when {
                conn !is ConnState.Online -> Text("Conecte-se ao PC para ver e gerenciar os aparelhos autorizados.", style = MaterialTheme.typography.bodyMedium, color = c.text3, modifier = Modifier.padding(4.dp))
                devices == null -> CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.padding(8.dp).size(20.dp))
                else -> Group {
                    devices!!.filter { !it.revoked }.forEachIndexed { i, d ->
                        if (i > 0) GroupDivider()
                        RowItem(
                            d.name + if (d.current) " (este)" else "",
                            subtitle = listOfNotNull(
                                d.model.takeIf { it.isNotBlank() },
                                if (d.online) "online agora" else d.lastSeen?.let { "visto ${Texts.relative(it)}" },
                                d.route?.let { if (it == "lan") "rede local" else "remoto" },
                            ).joinToString(" · "),
                            icon = Icons.Rounded.Smartphone,
                            iconTint = if (d.online) c.ok else null,
                            trailing = { GhostButton(if (d.current) "Desvincular" else "Revogar", color = c.err) { revoke = d } },
                        )
                    }
                }
            }
            val revoked = devices?.filter { it.revoked }.orEmpty()
            if (revoked.isNotEmpty()) {
                Text("${revoked.size} aparelho(s) revogado(s) não conseguem mais se conectar.", style = MaterialTheme.typography.bodySmall, color = c.text3, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    forget?.let { pc ->
        NocSheet({ forget = null }) {
            Column(Modifier.padding(horizontal = 22.dp)) {
                Text("Esquecer ${pc.name}?", style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "Este celular deixa de acessar esse PC e, se estiver conectado, o acesso também é revogado lá. As conversas continuam aqui.",
                    style = MaterialTheme.typography.bodyMedium, color = c.text2,
                )
                Spacer(Modifier.height(18.dp))
                PrimaryButton("Esquecer", Modifier.fillMaxWidth(), height = 48.dp) {
                    forget = null
                    scope.launch {
                        container.connection.forgetPc(pc, revokeRemote = true)
                        if (container.db.pcs().get(pc.id) == null && pcs.size <= 1) nav.navigate(Routes.HOME) { popUpTo(0) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    revoke?.let { d ->
        NocSheet({ revoke = null }) {
            Column(Modifier.padding(horizontal = 22.dp)) {
                Text(if (d.current) "Desvincular este celular?" else "Revogar ${d.name}?", style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    if (d.current) "O PC vai recusar este celular a partir de agora. Para voltar, será preciso parear de novo."
                    else "As sessões abertas desse aparelho caem na hora e ele não consegue mais se conectar.",
                    style = MaterialTheme.typography.bodyMedium, color = c.text2,
                )
                Spacer(Modifier.height(18.dp))
                PrimaryButton(if (d.current) "Desvincular" else "Revogar acesso", Modifier.fillMaxWidth(), height = 48.dp) {
                    revoke = null
                    scope.launch {
                        runCatching { container.connection.call("devices.revoke", buildJsonObject { put("id", d.id) }) }
                        reload++
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    if (renamePc) {
        var name by remember { mutableStateOf(active?.name ?: "") }
        NocSheet({ renamePc = false }) {
            Column(Modifier.padding(horizontal = 22.dp)) {
                Text("Nome do computador", style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.padding(vertical = 8.dp))
                Text("Um nome amigável, como “Estúdio” ou “PC do quarto”. Muda também no Companion.", style = MaterialTheme.typography.bodyMedium, color = c.text2)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(16.dp)) {
                    BasicTextField(name, { name = it.take(40) }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(14.dp))
                PrimaryButton("Salvar", Modifier.fillMaxWidth(), enabled = name.isNotBlank(), height = 48.dp) {
                    renamePc = false
                    scope.launch { runCatching { container.connection.call("pc.rename", buildJsonObject { put("name", name.trim()) }) } }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ segurança

@Composable
fun SecurityScreen(container: AppContainer, onBack: () -> Unit) {
    val c = Noc.colors
    val conn by container.connection.state.collectAsState()
    val pc by container.connection.activePc.collectAsState()
    var events by remember { mutableStateOf<List<SecurityEventInfo>?>(null) }

    LaunchedEffect(conn is ConnState.Online) {
        if (conn is ConnState.Online) events = runCatching {
            container.connection.call("security.log", buildJsonObject { put("max", 80) }).arr("events")?.mapNotNull { it.asObj() }?.map { SecurityEventInfo.parse(it) }
        }.getOrNull()
    }

    fun fp(id: String) = id.take(16).chunked(4).joinToString(" ")

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        TopBar("Segurança", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.surface).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = c.ok, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Cifrado de ponta a ponta", style = MaterialTheme.typography.titleMedium, color = c.text)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cada conexão cria chaves novas (ECDH P-256) e cifra tudo com AES-256-GCM. O PC prova quem é com a chave fixada no pareamento, e este celular prova quem é com uma chave guardada no chip de segurança do aparelho, que nunca sai dele. O relay na internet só repassa bytes cifrados.",
                    style = MaterialTheme.typography.bodyMedium, color = c.text2,
                )
            }
            SectionLabel("Identidades", Modifier.padding(top = 22.dp))
            Group {
                RowItem("Este celular", fp(container.connection.deviceKey.deviceId), icon = Icons.Rounded.Smartphone, trailing = null)
                if (pc != null) {
                    GroupDivider()
                    RowItem(pc!!.name, fp(pc!!.id) + " · confira no Companion", icon = Icons.Rounded.Computer, trailing = null)
                }
            }
            SectionLabel("Registro de acessos do PC", Modifier.padding(top = 22.dp))
            when {
                conn !is ConnState.Online -> Text("Conecte-se ao PC para ver o registro.", style = MaterialTheme.typography.bodyMedium, color = c.text3, modifier = Modifier.padding(4.dp))
                events == null -> CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.padding(8.dp).size(20.dp))
                events!!.isEmpty() -> Text("Nada registrado ainda.", style = MaterialTheme.typography.bodyMedium, color = c.text3)
                else -> Group {
                    events!!.forEachIndexed { i, e ->
                        if (i > 0) GroupDivider()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = 6.dp)) {
                                StatusDot(when (e.level) { "alert" -> c.err; "warning" -> c.warn; else -> c.text3 }, size = 7.dp)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.msg, style = MaterialTheme.typography.bodyMedium, color = c.text)
                                Text(
                                    listOfNotNull(Texts.relative(e.at), e.route?.let { if (it == "lan") "rede local" else "remoto" }).joinToString(" · "),
                                    style = MonoSmall, color = c.text3,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

