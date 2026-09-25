package com.noc.app.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.noc.app.AppContainer
import com.noc.app.chat.Attachment
import com.noc.app.chat.ChatEngine
import com.noc.app.chat.EffectiveConfig
import com.noc.app.chat.GenerationParams
import com.noc.app.data.db.ConversationEntity
import com.noc.app.data.db.MessageEntity
import com.noc.app.data.db.PresetEntity
import com.noc.app.ui.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Mensagem pronta para exibir, com a posição dela entre as versões irmãs (ramos). */
data class MessageUi(
    val entity: MessageEntity,
    val branchIndex: Int,
    val branchCount: Int,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val c: AppContainer,
    initialId: String,
    initialPreset: String?,
) : ViewModel() {

    private val db = c.db
    private val engine = c.chat

    /** null enquanto a conversa é nova e ainda não foi gravada. */
    val convId = MutableStateFlow(initialId.takeIf { it != Routes.NEW })

    // Configuração escolhida antes da primeira mensagem de uma conversa nova.
    private val pendingPreset = MutableStateFlow(initialPreset)
    private val pendingModel = MutableStateFlow<String?>(null)
    private val pendingParams = MutableStateFlow<GenerationParams?>(null)
    private val pendingSystem = MutableStateFlow<String?>(null)

    val conversation: StateFlow<ConversationEntity?> = convId.flatMapLatest { id ->
        if (id == null) flowOf(null) else db.conversations().observe(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val allMessages: StateFlow<List<MessageEntity>> = convId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else db.messages().observeAll(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** O ramo ativo, da primeira à última mensagem. */
    val messages: StateFlow<List<MessageUi>> = combine(conversation, allMessages) { conv, all ->
        val path = engine.path(all, conv?.activeLeafId)
        val byParent = all.groupBy { it.parentId to it.role }
        path.map { m ->
            val sibs = byParent[m.parentId to m.role]?.sortedBy { it.createdAt } ?: listOf(m)
            MessageUi(m, sibs.indexOfFirst { it.id == m.id }.coerceAtLeast(0), sibs.size)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Primeira carga concluída (para mostrar skeleton só quando precisa). */
    val loaded: StateFlow<Boolean> = combine(convId, conversation) { id, conv -> id == null || conv != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val presets: StateFlow<List<PresetEntity>> = db.presets().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Configuração efetiva (preset + ajustes da conversa), recalculada quando algo muda. */
    val config: StateFlow<EffectiveConfig?> = combine(
        conversation, pendingPreset, pendingModel, pendingParams, pendingSystem,
    ) { conv, pp, pm, ppar, psys -> Quad(conv, pp, pm, ppar to psys) }
        .combine(c.connection.status) { q, _ -> q }
        .combine(c.connection.models) { q, _ -> q }
        .combine(presets) { q, _ -> q }
        .combine(c.prefs.flow) { q, _ -> q }
        .map { (conv, pp, pm, pair) ->
            val (ppar, psys) = pair
            val virtual = conv ?: ConversationEntity(
                id = "", title = "", createdAt = 0, updatedAt = 0, presetId = pp, model = pm,
                paramsJson = ppar?.encode(), systemPrompt = psys,
            )
            engine.effectiveConfig(virtual)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    val attachments = MutableStateFlow<List<Attachment>>(emptyList())

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts

    private val _scrollToBottom = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scrollToBottom: SharedFlow<Unit> = _scrollToBottom

    fun toast(s: String) { _toasts.tryEmit(s) }

    // ------------------------------------------------------------------ envio

    fun send(text: String): Boolean {
        val atts = attachments.value
        if (text.isBlank() && atts.isEmpty()) return false
        if (config.value?.model == null) {
            toast("Nenhum modelo disponível. Conecte ao PC para escolher um modelo.")
            return false
        }
        attachments.value = emptyList()
        viewModelScope.launch {
            try {
                val id = ensureConversation()
                engine.send(id, text.ifBlank { "Veja o anexo." }, atts)
                _scrollToBottom.tryEmit(Unit)
            } catch (e: ChatEngine.NoModel) {
                toast("Escolha um modelo antes de enviar.")
            }
        }
        return true
    }

    private suspend fun ensureConversation(): String {
        convId.value?.let { return it }
        val id = engine.newConversation(presetId = pendingPreset.value, model = pendingModel.value ?: config.value?.model)
        if (pendingParams.value != null || pendingSystem.value != null) {
            db.conversations().setConfig(id, pendingPreset.value, pendingSystem.value, pendingParams.value?.encode())
        }
        convId.value = id
        return id
    }

    fun stop(messageId: String) = engine.stop(messageId)

    fun regenerate(messageId: String, model: String? = null) = viewModelScope.launch {
        runCatching { engine.regenerate(messageId, model) }.onFailure { toast("Não foi possível regenerar.") }
        _scrollToBottom.tryEmit(Unit)
    }

    fun edit(messageId: String, text: String) = viewModelScope.launch {
        if (text.isBlank()) return@launch
        runCatching { engine.edit(messageId, text) }.onFailure { toast("Não foi possível editar.") }
        _scrollToBottom.tryEmit(Unit)
    }

    fun continueReply(messageId: String) = viewModelScope.launch {
        runCatching { engine.continueReply(messageId) }.onFailure { toast("Não foi possível continuar.") }
    }

    fun delete(messageId: String) = viewModelScope.launch { engine.deleteMessage(messageId) }

    fun switchBranch(m: MessageEntity, delta: Int) = viewModelScope.launch { engine.switchBranch(m, delta) }

    // ------------------------------------------------------------------ configuração

    fun setModel(key: String) = viewModelScope.launch {
        val id = convId.value
        if (id == null) pendingModel.value = key else db.conversations().setModel(id, key)
    }

    fun setPreset(presetId: String) = viewModelScope.launch {
        val id = convId.value
        if (id == null) {
            pendingPreset.value = presetId
            pendingParams.value = null
            pendingSystem.value = null
        } else {
            val preset = db.presets().get(presetId)
            db.conversations().setConfig(id, presetId, null, null)
            preset?.model?.let { db.conversations().setModel(id, it) }
        }
    }

    /** Ajustes finos da conversa (sobrepostos ao preset). null = sem ajuste. */
    fun setOverrides(params: GenerationParams?, systemPrompt: String?) = viewModelScope.launch {
        val id = convId.value
        val p = params?.takeUnless { it.isDefault }
        if (id == null) {
            pendingParams.value = p
            pendingSystem.value = systemPrompt
        } else {
            val conv = db.conversations().get(id) ?: return@launch
            db.conversations().setConfig(id, conv.presetId, systemPrompt, p?.encode())
        }
    }

    val overrides: StateFlow<Pair<GenerationParams?, String?>> = combine(conversation, pendingParams, pendingSystem) { conv, pp, ps ->
        if (conv != null) GenerationParams.decode(conv.paramsJson) to conv.systemPrompt else pp to ps
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null to null)

    fun savePreset(name: String, params: GenerationParams, system: String?, model: String?) = viewModelScope.launch {
        val preset = PresetEntity(
            id = java.util.UUID.randomUUID().toString(), name = name.trim().ifBlank { "Meu preset" }, icon = "tune",
            description = "Criado a partir de uma conversa", model = model, systemPrompt = system,
            paramsJson = params.encode(), builtin = false, sort = 100, updatedAt = System.currentTimeMillis(),
        )
        db.presets().upsert(preset)
        setPreset(preset.id)
        toast("Preset “${preset.name}” salvo")
    }

    // ------------------------------------------------------------------ conversa

    fun rename(title: String) = viewModelScope.launch {
        val id = convId.value ?: ensureConversation()
        db.conversations().rename(id, title.trim().ifBlank { "Sem título" }, false)
    }

    fun togglePin() = viewModelScope.launch {
        val conv = conversation.value ?: return@launch
        db.conversations().setPinned(conv.id, !conv.pinned)
        toast(if (conv.pinned) "Conversa desafixada" else "Conversa fixada")
    }

    fun archive(onDone: () -> Unit) = viewModelScope.launch {
        val conv = conversation.value ?: return@launch
        db.conversations().setArchived(conv.id, !conv.archived)
        onDone()
    }

    fun deleteConversation(onDone: () -> Unit) = viewModelScope.launch {
        convId.value?.let { engine.deleteConversation(it) }
        onDone()
    }

    fun duplicate(onDone: (String) -> Unit) = viewModelScope.launch {
        val id = convId.value ?: return@launch
        onDone(engine.duplicate(id))
    }

    fun exportMarkdown(): String? {
        val conv = conversation.value ?: return null
        return engine.exportMarkdown(conv, messages.value.map { it.entity })
    }

    // ------------------------------------------------------------------ anexos

    fun addAttachment(context: Context, uri: Uri, asImage: Boolean) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val cr = context.contentResolver
                var name = "arquivo"
                var size = 0L
                cr.query(uri, null, null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) {
                        val ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = cur.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0) name = cur.getString(ni) ?: name
                        if (si >= 0) size = cur.getLong(si)
                    }
                }
                val mime = cr.getType(uri) ?: "application/octet-stream"
                if (asImage || mime.startsWith("image/")) {
                    if (size > 25_000_000) error("Imagem grande demais (máx. 25 MB).")
                    val bytes = cr.openInputStream(uri)!!.use { it.readBytes() }
                    ChatEngine.prepareImage(context, bytes, name) ?: error("Não foi possível ler a imagem.")
                } else {
                    if (size > 400_000) error("Arquivo grande demais para o contexto (máx. 400 KB de texto).")
                    val bytes = cr.openInputStream(uri)!!.use { it.readBytes() }
                    if (bytes.take(4000).count { it == 0.toByte() } > 0) error("Esse arquivo não é texto. Envie arquivos de texto, código, CSV, JSON ou Markdown.")
                    val text = bytes.decodeToString()
                    Attachment(kind = "text", name = name, mime = mime, size = bytes.size.toLong(), text = text)
                }
            }
        }
        result.onSuccess { a -> attachments.update { it + a } }
            .onFailure { toast(it.message ?: "Não foi possível anexar.") }
    }

    fun removeAttachment(a: Attachment) = attachments.update { it - a }

    fun loadModel(key: String, context: Int?) = viewModelScope.launch {
        runCatching {
            c.connection.call("models.load", buildJsonObject { put("model", key); context?.let { put("context", it) } })
        }.onFailure { toast("Não foi possível carregar o modelo agora.") }
    }

    class Factory(private val c: AppContainer, private val id: String, private val preset: String?) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(c, id, preset) as T
    }
}
