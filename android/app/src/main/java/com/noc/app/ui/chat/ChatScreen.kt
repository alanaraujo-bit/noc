package com.noc.app.ui.chat

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.noc.app.AppContainer
import com.noc.app.chat.MessageStatus
import com.noc.app.core.net.ConnState
import com.noc.app.core.net.Tier
import com.noc.app.data.db.MessageEntity
import com.noc.app.data.prefs.AppPrefs
import com.noc.app.ui.Routes
import com.noc.app.ui.Texts
import com.noc.app.ui.components.IconAction
import com.noc.app.ui.components.StatusDot
import com.noc.app.ui.components.TaskPill
import com.noc.app.ui.components.pressable
import com.noc.app.ui.newChat
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.Noc
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ChatScreen(
    container: AppContainer,
    prefs: AppPrefs,
    conversationId: String,
    initialDraft: String?,
    initialPreset: String?,
    nav: NavHostController,
    initialMessageId: String? = null,
    startImageMode: Boolean = false,
) {
    val vm: ChatViewModel = viewModel(key = conversationId, factory = ChatViewModel.Factory(container, conversationId, initialPreset))
    val c = Noc.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    val conv by vm.conversation.collectAsState()
    val messages by vm.messages.collectAsState()
    val cfg by vm.config.collectAsState()
    val overrides by vm.overrides.collectAsState()
    val attachments by vm.attachments.collectAsState()
    val presets by vm.presets.collectAsState()
    val models by container.connection.models.collectAsState()
    val status by container.connection.status.collectAsState()
    val conn by container.connection.state.collectAsState()
    val liveIds by container.chat.liveIds.collectAsState()
    val voice by container.voice.state.collectAsState()
    val online = conn is ConnState.Online

    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialDraft ?: "", TextRange((initialDraft ?: "").length)))
    }
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MessageEntity?>(null) }
    var switchedTo by remember { mutableStateOf<String?>(null) }

    // ---- imagens
    var imageMode by rememberSaveable { mutableStateOf(startImageMode) }
    val savedImageOptions by vm.imageOptions.collectAsState()
    var imageOverride by remember { mutableStateOf<com.noc.app.chat.ImageOptions?>(null) }
    val imageOptions = imageOverride ?: savedImageOptions
    val imageCaps by container.chat.images.caps.collectAsState()
    var imageAdvanced by remember { mutableStateOf(false) }
    /** Folha aberta do modo imagem: engine | loras | guide | creations */
    var imageSheet by remember { mutableStateOf<String?>(null) }
    var pendingGuide by remember { mutableStateOf<com.noc.app.ui.image.Guide?>(null) }
    // pedido vindo da galeria ("Usar como referência", "Reutilizar prompt")
    LaunchedEffect(Unit) {
        container.chat.images.pending.value?.let { (o, text) ->
            container.chat.images.pending.value = null
            imageOverride = o
            imageMode = true
            if (!text.isNullOrBlank()) draft = TextFieldValue(text, TextRange(text.length))
        }
    }
    var viewer by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var pendingSave by remember { mutableStateOf<java.io.File?>(null) }
    LaunchedEffect(imageMode) {
        if (imageMode) {
            container.chat.images.refreshCaps()
            container.chat.images.prepare(imageOptions)
        }
    }
    fun setImageOptions(o: com.noc.app.chat.ImageOptions) {
        imageOverride = o
        // guias apontam para fotos desta conversa: valem para a sessão, não ficam guardados como padrão
        vm.saveImageOptions(o.copy(reference = null, referenceScale = null, control = null, controlInput = null, controlScale = null))
    }
    fun applyGuide(g: com.noc.app.ui.image.Guide, input: com.noc.app.chat.ImageInput) {
        val o = imageOptions
        setImageOptions(
            if (g.control == null) o.copy(reference = input)
            else o.copy(control = g.control, controlInput = input, controlScale = null),
        )
    }
    val guidePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val g = pendingGuide
        pendingGuide = null
        if (uri != null && g != null) scope.launch {
            val input = container.chat.images.inputFromUri(uri)
            if (input == null) vm.toast("Não foi possível abrir esta imagem.") else applyGuide(g, input)
        }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val f = pendingSave
        pendingSave = null
        if (granted && f != null) scope.launch {
            val r = com.noc.app.ui.image.ImageFiles.saveToGallery(context, f)
            vm.toast(if (r.isSuccess) "Salva na galeria (Imagens/Noc)" else "Não foi possível salvar na galeria.")
        } else if (!granted) vm.toast("Sem permissão para salvar na galeria.")
    }
    fun saveImage(path: String?) {
        val f = path?.let { java.io.File(it) }?.takeIf { it.exists() } ?: run { vm.toast("A imagem ainda está chegando do PC."); return }
        if (com.noc.app.ui.image.ImageFiles.needsPermission(context)) { pendingSave = f; storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE); return }
        scope.launch {
            val r = com.noc.app.ui.image.ImageFiles.saveToGallery(context, f)
            vm.toast(if (r.isSuccess) "Salva na galeria (Imagens/Noc)" else "Não foi possível salvar na galeria.")
        }
    }
    fun reusePrompt(job: com.noc.app.chat.ImageJob) {
        draft = TextFieldValue(job.prompt, TextRange(job.prompt.length))
        imageOverride = job.options.copy(seed = null)
        imageMode = true
        runCatching { focus.requestFocus() }
    }

    // A conversa aberta na tela não gera notificação de "resposta pronta".
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val convIdNow = conv?.id
    DisposableEffect(lifecycle, convIdNow) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> container.chat.visibleConversation.value = convIdNow
                Lifecycle.Event.ON_PAUSE -> if (container.chat.visibleConversation.value == convIdNow) container.chat.visibleConversation.value = null
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) container.chat.visibleConversation.value = convIdNow
        onDispose {
            lifecycle.removeObserver(obs)
            if (container.chat.visibleConversation.value == convIdNow) container.chat.visibleConversation.value = null
        }
    }
    // abrir a conversa limpa as notificações dela
    LaunchedEffect(messages.size) {
        messages.lastOrNull()?.entity?.id?.let { container.chat.notifier.cancelFor(it) }
    }

    // Prompt escolhido na biblioteca (volta pela pilha de navegação).
    val picked = nav.currentBackStackEntry?.savedStateHandle?.getStateFlow<String?>("picked_prompt", null)?.collectAsState()
    LaunchedEffect(picked?.value) {
        picked?.value?.let { text ->
            vm.setOverrides(overrides.first, text)
            nav.currentBackStackEntry?.savedStateHandle?.set("picked_prompt", null)
            vm.toast("Instruções aplicadas a esta conversa")
        }
    }

    LaunchedEffect(Unit) {
        vm.toasts.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(Unit) {
        vm.scrollToBottom.collect { listState.animateScrollToItem(0) }
    }
    LaunchedEffect(Unit) {
        vm.modelSwitched.collect { name ->
            switchedTo = name
            delay(4500)
            if (switchedTo == name) switchedTo = null
        }
    }
    // Voz: o texto transcrito entra no campo, para editar antes de enviar.
    LaunchedEffect(Unit) {
        container.voice.results.collect { r ->
            val cur = draft.text
            val joined = if (cur.isBlank()) r.text else cur.trimEnd() + " " + r.text
            draft = TextFieldValue(joined, TextRange(joined.length))
            runCatching { focus.requestFocus() }
            r.hint?.let { vm.toast(it) }
        }
    }
    // Imagens compartilhadas de outro app
    LaunchedEffect(Unit) {
        val shared = container.pendingShare.value
        if (shared.isNotEmpty()) {
            container.pendingShare.value = emptyList()
            vm.addAttachments(context, shared, asImage = true)
        }
    }
    // Conversa nova: já abre com o teclado pronto para digitar.
    LaunchedEffect(Unit) {
        if (conversationId == Routes.NEW && container.pendingShare.value.isEmpty()) {
            delay(350)
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    // Veio de uma notificação: posiciona na resposta.
    var scrolledTo by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(messages.size, initialMessageId) {
        if (initialMessageId == null || scrolledTo || messages.isEmpty()) return@LaunchedEffect
        val idx = messages.asReversed().indexOfFirst { it.entity.id == initialMessageId }
        if (idx >= 0) {
            listState.scrollToItem(idx)
            // resposta maior que a tela: posiciona no começo dela, não no fim
            delay(50)
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == idx }
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            if (item != null && item.size > viewport) listState.scrollToItem(idx, item.size - viewport + 40)
            scrolledTo = true
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ChatViewModel.MAX_IMAGES)) { uris ->
        if (uris.isNotEmpty()) vm.addAttachments(context, uris, asImage = true)
    }
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        cameraUri?.let { if (ok) vm.addAttachment(context, it, asImage = true) }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val u = vm.newCameraUri(context)
            cameraUri = u
            camera.launch(u)
        } else vm.toast("Sem permissão da câmera.")
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.addAttachment(context, it, asImage = false) }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        container.voice.dismissProblem()
        // negado: start() mostra o aviso com “Permitir” (abre as configurações do app)
        container.voice.start(hold = false)
    }

    val choice = cfg?.choice
    val currentModel = models.firstOrNull { it.key == cfg?.model }
    val tier = Tier.of(choice) ?: status?.tiers?.entries?.firstOrNull { it.value.model == cfg?.model }?.key
    val modelLabel = currentModel?.name ?: cfg?.modelName ?: cfg?.model?.substringBefore('@')
    val headerLabel = listOfNotNull(tier?.let { Tier.symbol(it) + " " + Tier.label(it) }, modelLabel).joinToString(" · ").ifEmpty { null }
    val presetName = presets.firstOrNull { it.id == cfg?.presetId }?.name
    val generatingId = messages.lastOrNull()?.entity?.id?.takeIf { it in liveIds }
    val reversed = remember(messages) { messages.asReversed() }
    val otherTasks = liveIds.count { id -> messages.none { it.entity.id == id } }

    // visão: o modelo escolhido entende imagens? (desconhecido = deixa, o PC explica se não)
    val vision = currentModel?.vision ?: (cfg?.model == null)
    val visionTier = status?.tiers?.entries?.firstOrNull { (_, t) -> models.firstOrNull { it.key == t.model }?.vision == true }
    val hasImages = attachments.any { it.kind == "image" }
    val capabilityNote: Pair<String, Pair<String, () -> Unit>?>? = if (hasImages && !vision) {
        "${modelLabel ?: "Este modelo"} não entende imagens." to visionTier?.let { (t, info) ->
            "Usar ${info.name}" to { vm.setModel(Tier.PREFIX + t); Unit }
        }
    } else null

    fun share(text: String, subject: String? = null) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        context.startActivity(Intent.createChooser(send, "Compartilhar"))
    }

    fun doSend() {
        if (imageMode) {
            if (vm.createImage(draft.text, imageOptions)) {
                draft = TextFieldValue("")
                // seed escolhida vale para um pedido; o resto das escolhas fica
                if (imageOptions.seed != null) imageOverride = imageOptions.copy(seed = null)
            }
            return
        }
        val e = editing
        if (e != null) {
            vm.edit(e.id, draft.text)
            editing = null
            draft = TextFieldValue("")
            return
        }
        if (vm.send(draft.text)) draft = TextFieldValue("")
    }

    fun startVoice(hold: Boolean) {
        if (!container.voice.hasPermission()) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        container.voice.start(hold)
    }

    Scaffold(
        containerColor = c.bg,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            ChatTopBar(
                title = conv?.title ?: "Nova conversa",
                model = if (imageMode) "✦ Criar imagem · " + (if (imageOptions.model == null && imageOptions.engine == "auto") "Automático"
                    else com.noc.app.ui.image.engineModelName(imageOptions, imageCaps) ?: com.noc.app.chat.ImageOptions.engineLabel(imageOptions.engine).substringAfter(' ')) else headerLabel,
                pinned = conv?.pinned == true,
                conn = conn,
                otherTasks = otherTasks,
                onTasks = { nav.navigate(Routes.ACTIVITY) },
                onBack = { nav.popBackStack() },
                onTitle = { if (conv != null) sheet = Sheet.Rename },
                onModel = { sheet = Sheet.Model },
                onNew = { nav.newChat() },
                menuOpen = menu,
                onMenu = { menu = it },
                hasConversation = conv != null,
                onPin = { vm.togglePin(); menu = false },
                onDuplicate = { menu = false; vm.duplicate { id -> nav.navigate(Routes.chat(id)) } },
                onExport = {
                    menu = false
                    vm.exportMarkdown()?.let { md -> exportFile(context, conv?.title ?: "conversa", md) }
                },
                onArchive = { menu = false; vm.archive { nav.popBackStack() } },
                onDelete = { menu = false; sheet = Sheet.ConfirmDelete },
                onRename = { menu = false; sheet = Sheet.Rename },
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding()) {
                AnimatedVisibility(switchedTo != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
                    Text(
                        "Próximas respostas: ${switchedTo ?: ""}",
                        style = MaterialTheme.typography.labelMedium, color = c.text,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 60.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(50)).background(c.surface2).padding(vertical = 8.dp),
                    )
                }
                AnimatedVisibility(editing != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Edit, null, tint = c.accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Editando mensagem — cria uma nova versão", style = MaterialTheme.typography.labelMedium, color = c.text2, modifier = Modifier.weight(1f))
                        Text("Cancelar", style = MaterialTheme.typography.labelMedium, color = c.accent, modifier = Modifier.pressable { editing = null; draft = TextFieldValue("") }.padding(6.dp))
                    }
                }
                Composer(
                    value = draft,
                    onValueChange = { draft = it },
                    attachments = attachments,
                    generating = generatingId != null,
                    presetName = presetName,
                    modelLabel = modelLabel,
                    hasOverrides = overrides.first != null || overrides.second != null,
                    sendWithEnter = prefs.sendWithEnter,
                    focusRequester = focus,
                    onSend = ::doSend,
                    onStop = { generatingId?.let { vm.stop(it) } },
                    onAttach = { sheet = Sheet.Attach },
                    onRemoveAttachment = { vm.removeAttachment(it) },
                    onTune = { sheet = Sheet.Tune },
                    onModel = { sheet = Sheet.Model },
                    voice = voice,
                    voiceEnabled = container.voice.unavailableReason() == null,
                    holdToTalk = prefs.holdToTalk,
                    onMicTap = { startVoice(hold = false) },
                    onMicHoldStart = { startVoice(hold = true) },
                    onMicHoldEnd = { cancel -> if (cancel) container.voice.cancel() else container.voice.stop() },
                    onVoiceStop = { container.voice.stop() },
                    onVoiceCancel = { container.voice.cancel() },
                    onVoiceRetry = { container.voice.retry() },
                    onVoiceDismiss = { container.voice.dismissProblem() },
                    onVoicePermission = {
                        container.voice.dismissProblem()
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    },
                    capabilityNote = if (imageMode) imageNote(imageCaps, imageOptions, container.chat.images.supported()) { nav.navigate(Routes.MODELS) } else capabilityNote,
                    blockSend = if (imageMode) imageCaps?.let { cp -> val m = cp.modelFor(imageOptions); (m != null && !m.ready) || !cp.anyInstalled } == true
                        else capabilityNote != null,
                    topSlot = if (imageMode) {
                        {
                            com.noc.app.ui.image.ImageModeBar(
                                options = imageOptions, caps = imageCaps,
                                onChange = { o -> setImageOptions(o); container.chat.images.prepare(o) },
                                onClose = { imageMode = false },
                                onStudio = { imageAdvanced = true },
                                onEngine = { imageSheet = "engine" },
                                onLoras = { imageSheet = "loras" },
                                onGuide = { imageSheet = "guide" },
                            )
                        }
                    } else null,
                    placeholder = if (imageMode) "Descreva o que deseja criar…" else "Mensagem",
                    showModelRow = !imageMode,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (messages.isEmpty() && imageMode) {
                EmptyImage(compact = voice !is com.noc.app.voice.VoiceState.Idle) { s ->
                    draft = TextFieldValue(s, TextRange(s.length))
                    focus.requestFocus()
                    keyboard?.show()
                }
            } else if (messages.isEmpty()) {
                EmptyChat(
                    model = modelLabel,
                    online = online,
                    compact = attachments.isNotEmpty() || voice !is com.noc.app.voice.VoiceState.Idle,
                    onSuggestion = { s ->
                        draft = TextFieldValue(s, TextRange(s.length))
                        focus.requestFocus()
                        keyboard?.show()
                    },
                )
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    itemsIndexed(reversed, key = { _, m -> m.entity.id }, contentType = { _, m -> m.entity.role + (m.entity.kind ?: "") }) { idx, ui ->
                        val m = ui.entity
                        if (m.kind == "image_request") {
                            com.noc.app.ui.image.ImageRequestBubble(ui, onLongPress = { sheet = Sheet.Actions(m) }, onBranch = { d -> vm.switchBranch(m, d) })
                        } else if (m.kind == "image") {
                            val job = remember(m.imageJson) { com.noc.app.chat.ImageJob.decode(m.imageJson) }
                            com.noc.app.ui.image.ImageMessage(
                                ui = ui,
                                live = if (m.id in liveIds) container.chat.liveFlow(m.id) else null,
                                online = online,
                                actions = com.noc.app.ui.image.ImageActions(
                                    onOpen = { i -> viewer = m.id to i },
                                    onShare = { img -> img.local?.let { com.noc.app.ui.image.ImageFiles.share(context, listOf(java.io.File(it))) } },
                                    onSave = { img -> saveImage(img.local) },
                                    onRegenerate = { vm.regenerate(m.id) },
                                    onVariation = { scope.launch { runCatching { container.chat.images.variation(m) } } },
                                    onReusePrompt = { job?.let { reusePrompt(it) } },
                                    onRetry = { scope.launch { runCatching { container.chat.images.retry(m) } } },
                                    onBranch = { d -> vm.switchBranch(m, d) },
                                ),
                                onLongPress = { sheet = Sheet.Actions(m) },
                            )
                        } else if (m.role == "user") {
                            UserMessage(ui, onLongPress = { sheet = Sheet.Actions(m) }, onBranch = { d -> vm.switchBranch(m, d) })
                        } else {
                            // quando o modelo muda no meio da conversa, uma linha discreta marca a troca
                            val prevAssistant = reversed.drop(idx + 1).firstOrNull { it.entity.role == "assistant" }?.entity
                            val name = m.modelName ?: m.model?.substringBefore('@')
                            val prevName = prevAssistant?.let { it.modelName ?: it.model?.substringBefore('@') }
                            Column {
                                if (prevName != null && name != null && name != prevName) ModelDivider(name)
                                AssistantMessage(
                                    ui = ui,
                                    live = if (m.id in liveIds) container.chat.liveFlow(m.id) else null,
                                    isLast = idx == 0,
                                    online = online,
                                    showStats = prefs.showStats,
                                    showReasoning = prefs.showReasoning,
                                    modelName = { key -> models.firstOrNull { it.key == key }?.name ?: key?.substringBefore('@') ?: "o modelo" },
                                    actions = ReplyActions(
                                        onCopy = {},
                                        onRegenerate = { vm.regenerate(m.id) },
                                        onMore = { sheet = Sheet.Actions(m) },
                                        onStats = { sheet = Sheet.Stats(m) },
                                        onContinue = { vm.continueReply(m.id) },
                                        onRetry = { vm.regenerate(m.id) },
                                        onBranch = { d -> vm.switchBranch(m, d) },
                                    ),
                                    onLongPress = { sheet = Sheet.Actions(m) },
                                    leaveHint = idx == 0 && prefs.leaveHintCount < 3,
                                    onLeaveHintShown = { scope.launch { container.prefs.noteLeaveHint() } },
                                )
                            }
                        }
                    }
                }
            }

            val showJump by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 400 } }
            AnimatedVisibility(
                showJump && messages.isNotEmpty(),
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            ) {
                Box(
                    Modifier
                        .shadow(6.dp, CircleShape)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(c.surface)
                        .pressable { scope.launch { listState.animateScrollToItem(0) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Ir para o fim", tint = c.text)
                }
            }
        }
    }

    // ------------------------------------------------------------------ imagens: avançado e visualizador
    if (imageAdvanced) {
        com.noc.app.ui.image.ImageStudioSheet(imageOptions, imageCaps, onApply = { setImageOptions(it) }, onDismiss = { imageAdvanced = false })
    }
    when (imageSheet) {
        "engine" -> com.noc.app.ui.image.EngineSheet(imageOptions, imageCaps, onPick = { o -> setImageOptions(o); container.chat.images.prepare(o) },
            onManage = { nav.navigate(Routes.MODELS) }, onDismiss = { imageSheet = null })
        "loras" -> com.noc.app.ui.image.LoraPickerSheet(imageOptions, imageCaps, load = { container.chat.images.loras(imageCaps?.modelFor(imageOptions)?.id) },
            onChange = { setImageOptions(it) }, onLibrary = { nav.navigate(Routes.LORAS) }, onDismiss = { imageSheet = null })
        "guide" -> com.noc.app.ui.image.GuideSheet(imageOptions, imageCaps, onPick = { g, fromCreations ->
            pendingGuide = g
            if (fromCreations) imageSheet = "creations"
            else { imageSheet = null; guidePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        }, onDismiss = { if (imageSheet == "guide") imageSheet = null })
        "creations" -> com.noc.app.ui.image.CreationPickerSheet(container.db, "Escolher criação", onPick = { cr ->
            pendingGuide?.let { g -> applyGuide(g, com.noc.app.chat.ImageInput.pc(cr.id, cr.width, cr.height, cr.thumbPath)) }
            pendingGuide = null
        }, onDismiss = { imageSheet = null })
    }
    viewer?.let { (messageId, index) ->
        val items by container.db.creations().observeByMessage(messageId).collectAsState(initial = null)
        val msg = messages.firstOrNull { it.entity.id == messageId }?.entity
        items?.let { list ->
            com.noc.app.ui.image.ImageViewer(
                items = list.filter { it.path != null || it.thumbPath != null }, start = index,
                actions = com.noc.app.ui.image.ViewerActions(
                    onRegenerate = msg?.let { m -> { _ -> vm.regenerate(m.id); Unit } },
                    onVariation = msg?.let { m -> { _ -> scope.launch { runCatching { container.chat.images.variation(m) } }; Unit } },
                    onReusePrompt = msg?.let { m -> { _ -> com.noc.app.chat.ImageJob.decode(m.imageJson)?.let { reusePrompt(it) }; Unit } },
                    onFavorite = { cr -> scope.launch { container.db.creations().setFavorite(cr.id, !cr.favorite) } },
                ),
                onDismiss = { viewer = null },
            )
        }
    }

    // ------------------------------------------------------------------ folhas
    when (val s = sheet) {
        Sheet.Model -> ModelPickerSheet(
            tiers = status?.tiers ?: emptyMap(),
            models = models,
            choice = choice,
            op = status?.op,
            online = online,
            presets = presets,
            selectedPreset = cfg?.presetId,
            onChoose = { ch -> vm.setModel(ch); sheet = null },
            onPreset = { p -> vm.setPreset(p.id) },
            onManage = { sheet = null; nav.navigate(Routes.MODELS) },
            onDismiss = { sheet = null },
        )
        Sheet.Tune -> TuneSheet(
            base = presets.firstOrNull { it.id == cfg?.presetId }?.let { com.noc.app.chat.GenerationParams.decode(it.paramsJson) } ?: com.noc.app.chat.GenerationParams(),
            overrides = overrides.first,
            systemPrompt = overrides.second,
            presetSystem = presets.firstOrNull { it.id == cfg?.presetId }?.systemPrompt,
            model = currentModel,
            onApply = { p, sys -> vm.setOverrides(p, sys) },
            onPickPrompt = { sheet = null; nav.navigate(Routes.prompts(pick = true)) },
            onSavePreset = { name, p, sys -> vm.savePreset(name, p, sys, choice) },
            onDismiss = { sheet = null },
        )
        Sheet.Attach -> AttachSheet(
            onCreateImage = if (container.chat.images.supported()) {
                {
                    imageMode = true
                    scope.launch { delay(250); runCatching { focus.requestFocus() }; keyboard?.show() }
                }
            } else null,
            visionEnabled = vision,
            modelName = modelLabel,
            visionAlternative = visionTier?.value?.name,
            onCamera = { cameraPermission.launch(Manifest.permission.CAMERA) },
            onGallery = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onPaste = { if (!vm.pasteImage(context)) vm.toast("Não há imagem copiada.") },
            onFile = { filePicker.launch(arrayOf("text/*", "application/json", "application/xml", "application/javascript", "application/x-sh", "application/octet-stream")) },
            onSwitchToVision = { visionTier?.let { vm.setModel(Tier.PREFIX + it.key) }; sheet = Sheet.Attach },
            onDismiss = { sheet = null },
        )
        is Sheet.Actions -> MessageActionsSheet(
            m = s.m,
            canContinue = s.m.role == "assistant" && s.m.content.isNotBlank() && s.m.id !in liveIds &&
                (s.m.finishReason == "length" || s.m.status == MessageStatus.CANCELLED || s.m.status == MessageStatus.INTERRUPTED),
            onCopy = { clipboard.setText(AnnotatedString(s.m.content)); vm.toast("Copiado") },
            onSelect = { sheet = Sheet.Select(if (s.m.role == "assistant") MarkdownParsing.plainText(s.m.content) else s.m.content) },
            onEdit = {
                editing = s.m
                draft = TextFieldValue(s.m.content, TextRange(s.m.content.length))
                focus.requestFocus()
            },
            onRegenerate = { vm.regenerate(s.m.id) },
            onRegenerateWith = { sheet = Sheet.RegenerateWith(s.m) },
            onContinue = { vm.continueReply(s.m.id) },
            onShare = { share(s.m.content) },
            onDelete = { vm.delete(s.m.id) },
            onDismiss = { if (sheet == s) sheet = null },
        )
        is Sheet.RegenerateWith -> ModelPickerSheet(
            tiers = status?.tiers ?: emptyMap(), models = models, choice = null, op = status?.op, online = online,
            presets = emptyList(), selectedPreset = null,
            onChoose = { ch -> vm.regenerate(s.m.id, ch); sheet = null },
            onPreset = {}, onManage = { sheet = null; nav.navigate(Routes.MODELS) },
            onDismiss = { sheet = null },
        )
        is Sheet.Select -> SelectTextSheet(s.text) { sheet = null }
        is Sheet.Stats -> StatsSheet(s.m) { sheet = null }
        Sheet.Rename -> RenameSheet(conv?.title ?: "", onRename = { vm.rename(it) }, onDismiss = { sheet = null })
        Sheet.ConfirmDelete -> NocSheet({ sheet = null }) {
            Column(Modifier.padding(horizontal = 22.dp)) {
                Text("Excluir conversa?", style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.padding(vertical = 8.dp))
                Text("Ela será apagada deste celular. Isso não pode ser desfeito.", style = MaterialTheme.typography.bodyMedium, color = c.text2)
                Spacer(Modifier.height(18.dp))
                com.noc.app.ui.components.PrimaryButton("Excluir", Modifier.fillMaxWidth(), icon = Icons.Rounded.Delete, height = 48.dp) {
                    sheet = null
                    vm.deleteConversation { nav.popBackStack() }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        null -> Unit
    }
}

private sealed interface Sheet {
    data object Model : Sheet
    data object Tune : Sheet
    data object Attach : Sheet
    data object Rename : Sheet
    data object ConfirmDelete : Sheet
    data class Actions(val m: MessageEntity) : Sheet
    data class RegenerateWith(val m: MessageEntity) : Sheet
    data class Select(val text: String) : Sheet
    data class Stats(val m: MessageEntity) : Sheet
}

@Composable
private fun ModelDivider(name: String) {
    val c = Noc.colors
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(c.line))
        Text(name, style = MonoSmall, color = c.text3, modifier = Modifier.padding(horizontal = 10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(c.line))
    }
}

private fun exportFile(context: android.content.Context, title: String, markdown: String) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val safe = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifBlank { "conversa" }.take(60)
    val file = File(dir, "$safe.md")
    file.writeText(markdown)
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Exportar conversa"))
}

@Composable
private fun ChatTopBar(
    title: String,
    model: String?,
    pinned: Boolean,
    conn: ConnState,
    otherTasks: Int,
    onTasks: () -> Unit,
    onBack: () -> Unit,
    onTitle: () -> Unit,
    onModel: () -> Unit,
    onNew: () -> Unit,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    hasConversation: Boolean,
    onPin: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    val c = Noc.colors
    Column(Modifier.background(c.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar", onClick = onBack)
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    title, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.pressable(onClick = onTitle),
                )
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).pressable(onClick = onModel).padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val (dot, pulse) = when (conn) {
                        is ConnState.Online -> c.ok to false
                        is ConnState.Connecting -> c.warn to true
                        is ConnState.Offline -> c.err to false
                        else -> c.text3 to false
                    }
                    StatusDot(dot, pulse, 6.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(model ?: "Escolher modelo", style = MaterialTheme.typography.bodySmall, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Rounded.KeyboardArrowDown, "Trocar modelo", tint = c.text3, modifier = Modifier.size(16.dp))
                }
            }
            if (otherTasks > 0) TaskPill(otherTasks, compact = true, onClick = onTasks)
            IconAction(Icons.Rounded.EditNote, "Nova conversa", onClick = onNew)
            Box {
                IconAction(Icons.Rounded.MoreVert, "Mais opções", enabled = hasConversation) { onMenu(true) }
                DropdownMenu(menuOpen, onDismissRequest = { onMenu(false) }, containerColor = c.surface) {
                    MenuItem("Renomear", Icons.Rounded.Edit, onRename)
                    MenuItem(if (pinned) "Desafixar" else "Fixar", Icons.Rounded.PushPin, onPin)
                    MenuItem("Duplicar", Icons.Rounded.ContentCopy, onDuplicate)
                    MenuItem("Exportar (.md)", Icons.Rounded.IosShare, onExport)
                    MenuItem("Arquivar", Icons.Rounded.Archive, onArchive)
                    MenuItem("Excluir", Icons.Rounded.Delete, onDelete, danger = true)
                }
            }
        }
        if (conn is ConnState.Offline || conn is ConnState.Connecting) {
            val text = when (conn) {
                is ConnState.Offline -> Texts.problem(conn.problem).title + " — suas mensagens esperam aqui."
                is ConnState.Connecting -> "Conectando ao seu PC…"
                else -> ""
            }
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = if (conn is ConnState.Offline) c.warn else c.text2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(if (conn is ConnState.Offline) c.warnSoft else c.surface).padding(vertical = 7.dp, horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, danger: Boolean = false) {
    val c = Noc.colors
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyLarge, color = if (danger) c.err else c.text) },
        leadingIcon = { Icon(icon, null, tint = if (danger) c.err else c.text2) },
        onClick = onClick,
    )
}

@Composable
private fun EmptyChat(model: String?, online: Boolean, compact: Boolean = false, onSuggestion: (String) -> Unit) {
    val c = Noc.colors
    val suggestions = listOf(
        "Explique de um jeito simples: " to "Explicar algo difícil",
        "Revise este código e aponte problemas:\n\n" to "Revisar código",
        "Escreva um e-mail curto e educado para " to "Escrever um e-mail",
        "Resuma os pontos principais do texto abaixo:\n\n" to "Resumir um texto",
    )
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text("Pergunte\nqualquer coisa.", style = MaterialTheme.typography.displayMedium, color = c.text)
        Spacer(Modifier.height(10.dp))
        Text(
            if (model != null) "Respondendo com $model, direto do seu PC." else if (online) "Escolha um modelo para começar." else "Você pode escrever agora; enviamos quando o PC conectar.",
            style = MaterialTheme.typography.bodyMedium, color = c.text2,
        )
        Spacer(Modifier.height(24.dp))
        if (!compact) suggestions.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (prompt, label) ->
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(c.surface)
                            .pressable { onSuggestion(prompt) }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                    ) {
                        Text(label, style = MaterialTheme.typography.titleSmall, color = c.text)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}


/** Conversa nova no modo imagem: o convite e algumas ideias (tocar preenche o campo, dá para editar antes). */
@Composable
private fun EmptyImage(compact: Boolean, onSuggestion: (String) -> Unit) {
    val c = Noc.colors
    val ideas = listOf(
        "Uma mansão brutalista no topo de uma montanha durante uma tempestade" to "Arquitetura",
        "Retrato realista de um pescador idoso ao entardecer, luz dourada" to "Retrato",
        "Frasco de perfume de vidro sobre mármore preto, foto de produto em estúdio" to "Produto",
        "Ilustração estilo anime de uma cidade flutuante ao pôr do sol" to "Ilustração",
    )
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Bottom) {
        Text("Crie\nqualquer imagem.", style = MaterialTheme.typography.displayMedium, color = c.text)
        Spacer(Modifier.height(10.dp))
        Text("Descreva em português. A imagem é feita na GPU do seu PC e chega aqui.", style = MaterialTheme.typography.bodyMedium, color = c.text2)
        Spacer(Modifier.height(24.dp))
        if (!compact) ideas.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (prompt, label) ->
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(c.surface).pressable { onSuggestion(prompt) }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                    ) { Text(label, style = MaterialTheme.typography.titleSmall, color = c.text) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Aviso no modo imagem quando o PC ainda não pode criar com o motor escolhido. */
private fun imageNote(
    caps: com.noc.app.chat.ImageCaps?, o: com.noc.app.chat.ImageOptions, supported: Boolean, onOpen: () -> Unit,
): Pair<String, Pair<String, () -> Unit>?>? {
    if (!supported) return "O Noc Companion deste PC ainda não cria imagens. Atualize o Companion." to null
    caps ?: return null
    if (!caps.enabled) return "A geração de imagens está desligada no Companion do PC." to null
    if (o.model == null && o.engine == "auto") return if (caps.anyInstalled) null else "Nenhum modelo de imagem pronto no PC ainda." to ("Ver" to onOpen)
    val m = caps.modelFor(o) ?: return null
    if (m.ready) return null
    val text = when {
        m.state == "downloading" || m.state == "verifying" || m.state == "checking" -> {
            val pct = if (m.total != null && m.total > 0 && m.done != null) " · ${(m.done * 100 / m.total)}%" else ""
            "O ${m.name} está sendo instalado no seu PC$pct."
        }
        caps.installQueue.contains(m.id) -> "O ${m.name} está na fila de instalação do PC."
        m.gated -> "O ${m.name} precisa de autorização no Hugging Face (veja no Companion do PC)."
        else -> "O ${m.name} ainda não está instalado no PC."
    }
    return text to ("Ver" to onOpen)
}
