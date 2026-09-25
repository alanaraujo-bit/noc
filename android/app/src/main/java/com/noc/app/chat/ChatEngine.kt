package com.noc.app.chat

import android.content.Context
import android.util.Base64
import com.noc.app.core.net.ConnState
import com.noc.app.core.net.ConnectionManager
import com.noc.app.core.net.ModelInfo
import com.noc.app.core.net.NocSession
import com.noc.app.core.net.RpcError
import com.noc.app.core.net.Tier
import com.noc.app.core.net.arr
import com.noc.app.core.net.asObj
import com.noc.app.core.net.bool
import com.noc.app.core.net.dbl
import com.noc.app.core.net.int
import com.noc.app.core.net.long
import com.noc.app.core.net.obj
import com.noc.app.core.net.str
import com.noc.app.data.db.ConversationEntity
import com.noc.app.data.db.MessageEntity
import com.noc.app.data.db.NocDatabase
import com.noc.app.data.prefs.Prefs
import com.noc.app.service.GenerationService
import com.noc.app.service.Notifier
import com.noc.app.service.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Estado ao vivo de uma resposta/tarefa (a UI sobrepõe isso ao que está no banco). */
data class LiveReply(
    val messageId: String,
    val conversationId: String,
    val content: String,
    val reasoning: String,
    val seq: Int,
    val phase: Phase,
    val tokens: Int = 0,
    val tps: Double? = null,
    val reasoningStartedAt: Long? = null,
    val reasoningMs: Long? = null,
    val startedAt: Long = System.currentTimeMillis(),
    /** Quando entrou na etapa atual (para "Carregando… 12 s"). */
    val phaseSince: Long = System.currentTimeMillis(),
    val model: String? = null,
    val modelName: String? = null,
    /** Tarefas na frente desta na fila do PC. */
    val ahead: Int? = null,
    /** Envio das imagens (0..1). */
    val upload: Float? = null,
    val uploadBytes: Long? = null,
    val expectedSeconds: Double? = null,
    val images: Int = 0,
    val jobId: String? = null,
    /** Conexão caiu no meio: a tarefa continua no PC e retomamos ao voltar. */
    val detached: Boolean = false,
) {
    enum class Phase { SENDING, UPLOADING, QUEUED, LOADING, PREPARING, THINKING, GENERATING, RECOVERING }

    /** Fase legível para a interface e a notificação. */
    fun describe(online: Boolean): String = when {
        detached && !online -> "Continuando no seu PC…"
        detached -> "Reconectando…"
        else -> when (phase) {
            Phase.SENDING -> if (online) "Enviando ao PC…" else "Aguardando conexão com o PC…"
            Phase.UPLOADING -> "Enviando " + (if (images > 1) "$images imagens" else "imagem") + (upload?.let { " · ${(it * 100).toInt()}%" } ?: "…")
            Phase.QUEUED -> when (val a = ahead ?: 0) { 0 -> "Na fila"; 1 -> "Na fila · 1 tarefa antes desta"; else -> "Na fila · $a tarefas antes desta" }
            Phase.LOADING -> "Carregando ${modelName ?: "o modelo"}…"
            Phase.PREPARING -> if (images > 0) "Analisando " + (if (images > 1) "as imagens…" else "a imagem…") else "Lendo a conversa…"
            Phase.THINKING -> "Pensando…"
            Phase.GENERATING -> "Gerando…"
            Phase.RECOVERING -> "Retomando no PC…"
        }
    }
}

/** Configuração efetiva de uma conversa (preset + ajustes da conversa + prompt padrão). */
data class EffectiveConfig(
    /** Chave do modelo resolvida (null = ainda não dá para saber, ex.: sem conexão). */
    val model: String?,
    val systemPrompt: String?,
    val params: GenerationParams,
    val presetId: String?,
    /** O que o usuário escolheu: "tier:fast" ou uma chave de modelo. */
    val choice: String? = model,
    val modelName: String? = null,
)

class ChatEngine(
    private val context: Context,
    private val db: NocDatabase,
    private val prefs: Prefs,
    private val connection: ConnectionManager,
    private val scope: CoroutineScope,
) {
    private val live = ConcurrentHashMap<String, MutableStateFlow<LiveReply>>()
    private val runners = ConcurrentHashMap<String, Job>()
    private val _liveIds = MutableStateFlow<Set<String>>(emptySet())
    /** Ids das mensagens com tarefa ativa (para a Home, o serviço e a notificação). */
    val liveIds: StateFlow<Set<String>> = _liveIds.asStateFlow()

    /** Conversa aberta na tela agora (não notificamos o que a pessoa está vendo). */
    val visibleConversation = MutableStateFlow<String?>(null)

    val notifier = Notifier(context, prefs, db, this)

    fun liveFlow(messageId: String): StateFlow<LiveReply>? = live[messageId]

    fun liveSnapshot(): List<LiveReply> = live.values.map { it.value }.sortedBy { it.startedAt }

    /** Retoma respostas que estavam em andamento quando o app foi fechado (ou morto pelo sistema). */
    fun resumePending() = scope.launch(Dispatchers.IO) {
        db.messages().markOrphans()
        for (m in db.messages().pending()) {
            if (live.containsKey(m.id)) continue
            val job = m.jobId ?: continue
            startRunner(m, job, request = null, blobs = emptyList(), baseContent = m.content, baseReasoning = m.reasoning ?: "", fromSeq = m.lastSeq)
        }
    }

    val hasPending: Boolean get() = live.isNotEmpty()

    // ------------------------------------------------------------------ modelos e perfis

    private val pcSupports: (String) -> Boolean = { f -> (connection.state.value as? ConnState.Online)?.pc?.has(f) == true || connection.lastFeatures.contains(f) }

    fun supportsTiers() = pcSupports("tiers")

    /** "tier:fast" → chave do modelo desse perfil no PC (ou null se ainda não sabemos). */
    fun resolve(choice: String?): String? {
        if (choice == null) return null
        val t = Tier.of(choice) ?: return choice
        return connection.status.value?.tiers?.get(t)?.model
    }

    fun modelInfo(key: String?): ModelInfo? = key?.let { k -> connection.models.value.firstOrNull { it.key == k } }

    fun displayName(choice: String?): String? {
        val key = resolve(choice)
        return modelInfo(key)?.name ?: connection.status.value?.tiers?.get(Tier.of(choice) ?: "")?.name ?: key?.substringBefore('@')
    }

    suspend fun effectiveConfig(conv: ConversationEntity?): EffectiveConfig {
        val p = prefs.current()
        val presetId = conv?.presetId ?: p.defaultPresetId ?: Library.PRESET_BALANCED
        val preset = db.presets().get(presetId) ?: db.presets().get(Library.PRESET_BALANCED)
        val base = GenerationParams.decode(preset?.paramsJson) ?: GenerationParams()
        val params = base.overlay(GenerationParams.decode(conv?.paramsJson))
        val system = conv?.systemPrompt ?: preset?.systemPrompt ?: db.prompts().getDefault()?.content
        val status = connection.status.value
        val tiers = supportsTiers()
        val defaultChoice = when {
            p.lastChoice != null && (Tier.of(p.lastChoice) == null || tiers) -> p.lastChoice
            tiers -> Tier.PREFIX + (status?.tiers?.entries?.firstOrNull { it.value.model == status.defaultModel }?.key ?: Tier.FAST)
            else -> null
        }
        val loaded = status?.loaded?.firstOrNull()?.key
        val choice = conv?.model ?: preset?.model ?: defaultChoice ?: loaded ?: p.lastModel
            ?: connection.models.value.firstOrNull { it.isLlm }?.key
        return EffectiveConfig(resolve(choice), system?.takeIf { it.isNotBlank() }, params, preset?.id, choice, displayName(choice))
    }

    // ------------------------------------------------------------------ conversas

    suspend fun newConversation(presetId: String? = null, model: String? = null): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        db.conversations().insert(
            ConversationEntity(id = id, title = "Nova conversa", createdAt = now, updatedAt = now, presetId = presetId, model = model),
        )
        return id
    }

    /** Caminho ativo da conversa (da raiz até a folha escolhida). */
    fun path(all: List<MessageEntity>, leafId: String?): List<MessageEntity> {
        if (all.isEmpty()) return emptyList()
        val byId = all.associateBy { it.id }
        var cur = leafId?.let { byId[it] } ?: latestLeaf(all, null)
        val out = ArrayList<MessageEntity>()
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur.id)) {
            out.add(cur)
            cur = cur.parentId?.let { byId[it] }
        }
        out.reverse()
        return out
    }

    /** Descendo pelos filhos mais recentes a partir de [fromId] (null = raiz). */
    fun latestLeaf(all: List<MessageEntity>, fromId: String?): MessageEntity? {
        val children = all.groupBy { it.parentId }
        var cur: MessageEntity? = fromId?.let { id -> all.firstOrNull { it.id == id } }
            ?: children[null]?.maxByOrNull { it.createdAt }
        while (cur != null) {
            val next = children[cur.id]?.maxByOrNull { it.createdAt } ?: break
            cur = next
        }
        return cur
    }

    fun siblings(all: List<MessageEntity>, m: MessageEntity): List<MessageEntity> =
        all.filter { it.parentId == m.parentId && it.role == m.role }.sortedBy { it.createdAt }

    private suspend fun commitLeaf(convId: String, leafId: String?) {
        val all = db.messages().all(convId)
        val path = path(all, leafId)
        val last = path.lastOrNull()
        val preview = path.lastOrNull { it.content.isNotBlank() }?.content?.let { previewOf(it) }
        db.conversations().setLeaf(convId, last?.id, System.currentTimeMillis(), preview, path.size)
    }

    fun previewOf(s: String): String =
        s.replace(Regex("```[\\s\\S]*?```"), "[código]").replace(Regex("[#*_`>]"), "").replace(Regex("\\s+"), " ").trim().take(140)

    // ------------------------------------------------------------------ enviar / regenerar / editar

    suspend fun send(convId: String, text: String, attachments: List<Attachment>): String = withContext(Dispatchers.IO) {
        val conv = db.conversations().get(convId) ?: error("conversa não existe")
        val cfg = effectiveConfig(conv)
        val choice = cfg.choice ?: throw NoModel()
        val now = System.currentTimeMillis()
        val user = MessageEntity(
            id = UUID.randomUUID().toString(), conversationId = convId, parentId = conv.activeLeafId,
            role = "user", content = text.trim(), createdAt = now,
            attachmentsJson = Attachment.encodeList(attachments),
        )
        db.messages().insert(user)
        if (conv.titleAuto && conv.messageCount == 0) {
            db.conversations().rename(convId, autoTitle(text, attachments), true)
        }
        if (conv.model == null) db.conversations().setModel(convId, choice)
        prefs.setLastChoice(choice)
        startAssistant(convId, parentId = user.id, choice = choice, cfg = cfg, createdAt = now + 1)
    }

    /** Nova resposta para a mesma pergunta (vira um ramo irmão). */
    suspend fun regenerate(assistantId: String, model: String? = null): String = withContext(Dispatchers.IO) {
        val old = db.messages().get(assistantId) ?: error("mensagem não existe")
        val conv = db.conversations().get(old.conversationId)!!
        val cfg = effectiveConfig(conv)
        val choice = model ?: cfg.choice ?: old.model ?: throw NoModel()
        if (model != null) db.conversations().setModel(conv.id, model)
        startAssistant(conv.id, parentId = old.parentId, choice = choice, cfg = cfg)
    }

    /** Edita uma pergunta: cria um ramo novo a partir dela e gera outra resposta. */
    suspend fun edit(userMessageId: String, newText: String): String = withContext(Dispatchers.IO) {
        val old = db.messages().get(userMessageId) ?: error("mensagem não existe")
        val conv = db.conversations().get(old.conversationId)!!
        val cfg = effectiveConfig(conv)
        val choice = cfg.choice ?: throw NoModel()
        val now = System.currentTimeMillis()
        val user = old.copy(id = UUID.randomUUID().toString(), content = newText.trim(), createdAt = now)
        db.messages().insert(user)
        startAssistant(conv.id, parentId = user.id, choice = choice, cfg = cfg, createdAt = now + 1)
    }

    /** Continua uma resposta cortada (limite de tokens, interrupção, parada manual). */
    suspend fun continueReply(assistantId: String) = withContext(Dispatchers.IO) {
        val msg = db.messages().get(assistantId) ?: return@withContext
        if (runners.containsKey(assistantId)) return@withContext
        val conv = db.conversations().get(msg.conversationId)!!
        val cfg = effectiveConfig(conv)
        val model = msg.model ?: cfg.model ?: throw NoModel()
        val all = db.messages().all(conv.id)
        val history = path(all, assistantId) // inclui a própria resposta parcial por último
        val (request, blobs) = buildRequest(history, cfg, model, model)
        val job = UUID.randomUUID().toString()
        db.messages().setJob(assistantId, job, MessageStatus.WAITING, model)
        startRunner(
            msg.copy(jobId = job, status = MessageStatus.WAITING), job, request, blobs,
            baseContent = msg.content, baseReasoning = msg.reasoning ?: "", fromSeq = 0,
        )
    }

    private suspend fun startAssistant(convId: String, parentId: String?, choice: String, cfg: EffectiveConfig, createdAt: Long = System.currentTimeMillis()): String {
        val job = UUID.randomUUID().toString()
        val resolved = resolve(choice)
        val reply = MessageEntity(
            id = UUID.randomUUID().toString(), conversationId = convId, parentId = parentId,
            role = "assistant", content = "", createdAt = createdAt, model = resolved ?: choice,
            status = MessageStatus.WAITING, jobId = job, modelName = displayName(choice),
        )
        db.messages().insert(reply)
        commitLeaf(convId, reply.id)
        resolved?.let { prefs.noteModelUsed(it) }
        val all = db.messages().all(convId)
        val history = path(all, parentId)
        // O PC resolve o perfil sozinho; mandamos "tier:x" para que a escolha valha mesmo se o mapa aqui estiver velho.
        val wire = if (Tier.of(choice) != null && supportsTiers()) choice else resolved ?: choice
        val (request, blobs) = buildRequest(history, cfg, wire, resolved)
        startRunner(reply, job, request, blobs, baseContent = "", baseReasoning = "", fromSeq = 0)
        return reply.id
    }

    fun stop(messageId: String) {
        val m = live[messageId]?.value ?: return
        scope.launch {
            val msg = db.messages().get(messageId) ?: return@launch
            val job = msg.jobId ?: return@launch
            val res = runCatching { connection.call("chat.cancel", buildJsonObject { put("job", job) }, waitMs = 1_500) }.getOrNull()
            // o PC confirma com ok=true quando a tarefa existe e manda o "fim"; senão encerramos aqui mesmo
            if (res?.bool("ok") != true) {
                runners.remove(messageId)?.cancel()
                finalizeLocal(messageId, m, MessageStatus.CANCELLED, "cancelled", null)
            }
        }
    }

    /** Para a partir da notificação (a tarefa pode nem estar mais na memória do app). */
    suspend fun stopFromNotification(messageId: String) {
        if (live.containsKey(messageId)) { stop(messageId); return }
        val msg = db.messages().get(messageId) ?: return
        val job = msg.jobId ?: return
        runCatching { connection.call("chat.cancel", buildJsonObject { put("job", job) }, waitMs = 6_000) }
    }

    suspend fun switchBranch(message: MessageEntity, delta: Int) = withContext(Dispatchers.IO) {
        val all = db.messages().all(message.conversationId)
        val sibs = siblings(all, message)
        val idx = sibs.indexOfFirst { it.id == message.id }
        val target = sibs.getOrNull(idx + delta) ?: return@withContext
        val leaf = latestLeaf(all, target.id)
        commitLeaf(message.conversationId, leaf?.id ?: target.id)
    }

    /** Exclui a mensagem e tudo que veio depois dela neste ramo. */
    suspend fun deleteMessage(messageId: String) = withContext(Dispatchers.IO) {
        val msg = db.messages().get(messageId) ?: return@withContext
        val all = db.messages().all(msg.conversationId)
        val children = all.groupBy { it.parentId }
        val doomed = ArrayList<String>()
        fun collect(id: String) {
            doomed += id
            children[id]?.forEach { collect(it.id) }
        }
        collect(messageId)
        doomed.forEach { id -> runners.remove(id)?.cancel(); live.remove(id) }
        _liveIds.value = live.keys.toSet()
        db.messages().delete(doomed)
        val rest = all.filter { it.id !in doomed }
        val sibling = rest.filter { it.parentId == msg.parentId && it.role == msg.role }.maxByOrNull { it.createdAt }
        val leaf = if (sibling != null) latestLeaf(rest, sibling.id) else msg.parentId?.let { pid -> rest.firstOrNull { it.id == pid } }
        commitLeaf(msg.conversationId, leaf?.id)
    }

    suspend fun deleteConversation(id: String) = withContext(Dispatchers.IO) {
        db.messages().all(id).forEach { m -> runners.remove(m.id)?.cancel(); live.remove(m.id) }
        _liveIds.value = live.keys.toSet()
        db.messages().deleteAll(id)
        db.conversations().delete(id)
    }

    /** Duplica o ramo ativo numa conversa nova e independente. */
    suspend fun duplicate(id: String): String = withContext(Dispatchers.IO) {
        val conv = db.conversations().get(id)!!
        val path = path(db.messages().all(id), conv.activeLeafId).filter { it.status == MessageStatus.DONE || it.content.isNotBlank() }
        val newId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val idMap = HashMap<String, String>()
        val copies = path.mapIndexed { i, m ->
            val nid = UUID.randomUUID().toString()
            idMap[m.id] = nid
            m.copy(
                id = nid, conversationId = newId, parentId = m.parentId?.let { idMap[it] },
                status = if (m.status == MessageStatus.STREAMING || m.status == MessageStatus.WAITING) MessageStatus.INTERRUPTED else m.status,
                jobId = null, createdAt = m.createdAt + i,
            )
        }
        db.conversations().insert(
            conv.copy(id = newId, title = conv.title + " (cópia)", titleAuto = false, createdAt = now, updatedAt = now, pinned = false, archived = false, activeLeafId = copies.lastOrNull()?.id),
        )
        db.messages().insertAll(copies)
        newId
    }

    fun exportMarkdown(conv: ConversationEntity, path: List<MessageEntity>): String = buildString {
        append("# ").append(conv.title).append("\n\n")
        path.forEach { m ->
            append(if (m.role == "user") "**Você**" else "**${m.modelName ?: m.model ?: "IA"}**").append("\n\n")
            Attachment.decodeList(m.attachmentsJson).forEach { a -> append("> 📎 ").append(a.name).append("\n") }
            append(m.content.trim()).append("\n\n---\n\n")
        }
    }

    private fun autoTitle(text: String, attachments: List<Attachment>): String {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: attachments.firstOrNull()?.let { if (it.kind == "image") "Imagem" else it.name } ?: "Nova conversa"
        val clean = line.replace(Regex("[#*_`>]"), "").trim()
        return if (clean.length <= 48) clean else clean.take(46).substringBeforeLast(' ', clean.take(46)) + "…"
    }

    class NoModel : Exception("nenhum modelo")

    // ------------------------------------------------------------------ montagem do pedido

    /** Arquivo de imagem que precisa estar no PC antes de pedir a resposta. */
    data class BlobRef(val sha256: String, val path: String)

    /**
     * Monta o pedido. [wireModel] vai no fio ("tier:x" ou chave); [resolved] é a chave conhecida aqui,
     * usada para contexto e visão. Imagens vão por referência (hash) quando o PC suporta; senão, embutidas.
     */
    private fun buildRequest(history: List<MessageEntity>, cfg: EffectiveConfig, wireModel: String, resolved: String?): Pair<JsonObject, List<BlobRef>> {
        val info = modelInfo(resolved)
        val loadedCtx = connection.status.value?.loaded?.firstOrNull { it.key == resolved }?.context
        val ctx = loadedCtx ?: cfg.params.contextLength ?: info?.maxContext?.coerceAtMost(16384) ?: 8192
        // Sem saber o modelo ainda (offline), manda as imagens: o PC recusa com clareza se ele não enxergar.
        val vision = info?.vision ?: (resolved == null)
        val useBlobs = pcSupports("blobs")
        val msgs = history.filter { it.role == "user" || (it.role == "assistant" && it.content.isNotBlank()) }

        // Orçamento de contexto: mantém o prompt de sistema e as mensagens mais recentes.
        val reserve = (cfg.params.maxTokens ?: (ctx / 4)).coerceAtMost(ctx / 2)
        var budget = ctx - reserve - estimateTokens(cfg.systemPrompt ?: "")
        val kept = ArrayList<MessageEntity>()
        for (m in msgs.asReversed()) {
            val cost = estimateTokens(m.content) + Attachment.decodeList(m.attachmentsJson).sumOf { estimateTokens(it.text ?: "") + if (it.kind == "image") 900 else 0 }
            if (kept.isNotEmpty() && cost > budget) break
            kept.add(m)
            budget -= cost
        }
        kept.reverse()

        val blobs = LinkedHashMap<String, BlobRef>()
        val messages = buildJsonArray {
            cfg.systemPrompt?.let { add(buildJsonObject { put("role", "system"); put("content", it) }) }
            kept.forEach { m -> add(messageJson(m, vision, useBlobs, blobs)) }
        }
        val req = buildJsonObject {
            put("model", wireModel)
            put("messages", messages)
            put("params", cfg.params.toJson())
            cfg.params.contextLength?.let { put("context", it) }
            put("trimmed", kept.size < msgs.size)
        }
        return req to blobs.values.toList()
    }

    private fun estimateTokens(s: String) = (s.length / 3.2).toInt() + 4

    private fun messageJson(m: MessageEntity, vision: Boolean, useBlobs: Boolean, blobs: MutableMap<String, BlobRef>): JsonObject {
        val atts = Attachment.decodeList(m.attachmentsJson).map { Images.ensureHash(it) }
        val text = buildString {
            atts.filter { it.kind == "text" }.forEach { a ->
                append("[Arquivo: ").append(a.name).append("]\n```\n").append(a.text ?: "").append("\n```\n\n")
            }
            append(m.content)
        }
        val images = if (vision) atts.filter { it.kind == "image" && it.path != null && File(it.path).exists() } else emptyList()
        return buildJsonObject {
            put("role", m.role)
            if (images.isEmpty()) {
                val omitted = atts.count { it.kind == "image" }
                put("content", if (omitted > 0 && m.role == "user") "$text\n[$omitted imagem(ns) anexada(s); o modelo atual não vê imagens]" else text)
            } else {
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", text) })
                    images.forEach { img ->
                        if (useBlobs && img.sha256 != null) {
                            blobs[img.sha256] = BlobRef(img.sha256, img.path!!)
                            add(buildJsonObject { put("type", "image_ref"); put("hash", img.sha256); put("mime", img.mime) })
                        } else {
                            dataUrl(File(img.path!!))?.let { url ->
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject { put("url", url) })
                                })
                            }
                        }
                    }
                })
            }
        }
    }

    private fun dataUrl(file: File): String? = runCatching {
        "data:image/jpeg;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }.getOrNull()

    /** Envia ao PC as imagens que ele ainda não tem, em pedaços, informando o progresso real. */
    private suspend fun uploadBlobs(session: NocSession, blobs: List<BlobRef>, onProgress: (Float, Long) -> Unit) {
        if (blobs.isEmpty()) return
        val has = session.call("blob.has", buildJsonObject { put("hashes", buildJsonArray { blobs.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.sha256)) } }) })
        val missing = has.arr("missing")?.mapNotNull { runCatching { (it as kotlinx.serialization.json.JsonPrimitive).content }.getOrNull() }?.toSet() ?: emptySet()
        val partial = has.obj("partial")
        val todo = blobs.filter { it.sha256 in missing }
        if (todo.isEmpty()) return
        val files = todo.map { it to File(it.path).readBytes() }
        val total = files.sumOf { it.second.size.toLong() }
        var sent = 0L
        onProgress(0f, total)
        for ((ref, bytes) in files) {
            var off = partial?.long(ref.sha256)?.toInt()?.coerceIn(0, bytes.size) ?: 0
            sent += off
            while (off < bytes.size) {
                val n = minOf(CHUNK, bytes.size - off)
                val r = session.call("blob.put", buildJsonObject {
                    put("hash", ref.sha256); put("total", bytes.size); put("offset", off)
                    put("data", Base64.encodeToString(bytes, off, n, Base64.NO_WRAP))
                }, timeoutMs = 60_000)
                off += n
                sent += n
                onProgress((sent.toFloat() / total).coerceAtMost(1f), total)
                if (r.bool("done") == true) break
            }
        }
    }

    // ------------------------------------------------------------------ streaming

    private fun startRunner(
        msg: MessageEntity,
        jobId: String,
        request: JsonObject?,
        blobs: List<BlobRef>,
        baseContent: String,
        baseReasoning: String,
        fromSeq: Int,
    ) {
        val flow = MutableStateFlow(
            LiveReply(
                messageId = msg.id, conversationId = msg.conversationId, content = baseContent, reasoning = baseReasoning,
                seq = fromSeq, phase = LiveReply.Phase.SENDING, model = msg.model, modelName = msg.modelName, jobId = jobId,
                images = blobs.size,
            ),
        )
        live[msg.id] = flow
        _liveIds.update { it + msg.id }
        GenerationService.ensureRunning(context)
        SyncWorker.schedule(context)
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val release = connection.acquire()
            try {
                runJob(msg, jobId, request, blobs, flow)
            } finally {
                release()
                live.remove(msg.id)
                runners.remove(msg.id)
                _liveIds.update { it - msg.id }
            }
        }
        runners[msg.id] = job
        job.start()
    }

    private suspend fun runJob(msg0: MessageEntity, jobId0: String, request: JsonObject?, blobs: List<BlobRef>, flow: MutableStateFlow<LiveReply>) {
        var msg = msg0
        var jobId = jobId0
        var started = request == null // sem request = retomada: só dá para assinar
        var blobRetry = false
        var lastPersist = 0L
        var lastPersistedSeq = flow.value.seq
        while (true) {
            // espera uma sessão aberta (sem conexão, mostra que está esperando)
            val session = withTimeoutOrNull(1_500) { connection.session.filter { it != null && it.isOpen }.first() }
                ?: run {
                    flow.update { it.copy(detached = started) }
                    connection.session.filter { it != null && it.isOpen }.first()
                }!!
            flow.update { it.copy(detached = false) }
            android.util.Log.i("Noc", "tarefa ${jobId.take(8)}: ${if (started) "reassinando" else "enviando"} via ${session.route} a partir de ${flow.value.seq}")
            val events = Channel<JsonObject>(Channel.UNLIMITED)
            val collector = scope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                session.events.collect { e ->
                    if (e.name == "job" && e.data.str("job") == jobId) events.send(e.data)
                }
            }
            try {
                val res = try {
                    if (!started) {
                        if (blobs.isNotEmpty()) {
                            flow.update { it.copy(phase = LiveReply.Phase.UPLOADING, phaseSince = System.currentTimeMillis()) }
                            uploadBlobs(session, blobs) { p, total -> flow.update { it.copy(upload = p, uploadBytes = total) } }
                        }
                        val convTitle = db.conversations().get(msg.conversationId)?.title
                        val p = buildJsonObject {
                            put("job", jobId)
                            request!!.forEach { (k, v) -> if (k != "trimmed") put(k, v) }
                            put("meta", buildJsonObject { put("conv", msg.conversationId); put("msg", msg.id) })
                            convTitle?.let { put("label", it.take(80)) }
                        }
                        session.call("chat.start", p, timeoutMs = 30_000).also {
                            started = true
                            it.str("model")?.let { key -> if (key != msg.model) { db.messages().setModelInfo(msg.id, key, displayName(key) ?: msg.modelName); msg = msg.copy(model = key) } }
                            val pos = it.int("position")
                            if (pos != null && pos >= 0) flow.update { s -> s.copy(phase = LiveReply.Phase.QUEUED, ahead = s.ahead ?: pos, phaseSince = System.currentTimeMillis()) }
                        }
                    } else {
                        session.call("chat.subscribe", buildJsonObject { put("job", jobId); put("from", flow.value.seq) })
                    }
                } catch (e: RpcError) {
                    when (e.code) {
                        "job_unknown" -> finalizeLocal(msg.id, flow.value, MessageStatus.INTERRUPTED, "interrupted", "job_unknown")
                        else -> finalizeLocal(msg.id, flow.value, MessageStatus.ERROR, "error", e.code + "|" + e.message)
                    }
                    notifyEnd(msg, flow.value, if (e.code == "job_unknown") MessageStatus.INTERRUPTED else MessageStatus.ERROR, e.code)
                    return
                }
                if (res.str("job") == null && !session.isOpen) continue

                // consome eventos até o fim ou até a sessão cair
                while (true) {
                    val e = select<JsonObject?> {
                        events.onReceive { it }
                        session.closed.onAwait { null }
                    } ?: break
                    val seq = e.int("seq") ?: continue
                    val cur = flow.value
                    if (seq <= cur.seq) continue // duplicado (replay)
                    var next = cur.copy(seq = seq)
                    val now = System.currentTimeMillis()
                    fun phase(p: LiveReply.Phase) { if (next.phase != p) next = next.copy(phase = p, phaseSince = now) }
                    when (e.str("phase")) {
                        "queued" -> { phase(LiveReply.Phase.QUEUED); next = next.copy(ahead = e.int("ahead") ?: next.ahead) }
                        "loading" -> {
                            phase(LiveReply.Phase.LOADING)
                            next = next.copy(expectedSeconds = e.dbl("expectedSeconds") ?: next.expectedSeconds, modelName = e.str("name") ?: next.modelName)
                        }
                        "preparing" -> {
                            phase(LiveReply.Phase.PREPARING)
                            next = next.copy(images = e.int("images") ?: next.images, modelName = e.str("name") ?: next.modelName)
                        }
                        "thinking" -> phase(LiveReply.Phase.THINKING)
                        "generating" -> phase(LiveReply.Phase.GENERATING)
                        "recovering" -> phase(LiveReply.Phase.RECOVERING)
                    }
                    if (e.bool("reset") == true) next = next.copy(content = "", reasoning = "", tokens = 0, reasoningStartedAt = null, reasoningMs = null)
                    e.str("r")?.let { r ->
                        next = next.copy(reasoning = next.reasoning + r, reasoningStartedAt = next.reasoningStartedAt ?: now)
                        if (next.phase != LiveReply.Phase.THINKING && next.content.isEmpty()) phase(LiveReply.Phase.THINKING)
                    }
                    e.str("c")?.let { c ->
                        val rMs = next.reasoningMs ?: next.reasoningStartedAt?.let { now - it }
                        next = next.copy(content = next.content + c, reasoningMs = rMs)
                        phase(LiveReply.Phase.GENERATING)
                    }
                    e.int("n")?.let { next = next.copy(tokens = it) }
                    e.dbl("tps")?.let { next = next.copy(tps = it) }
                    flow.value = next

                    val end = e.obj("end")
                    if (end != null) {
                        // a imagem sumiu do PC (ex.: guardada há mais de 7 dias): reenvia tudo uma vez e pede de novo
                        if (end.str("error") == "blob_missing" && !blobRetry && request != null) {
                            blobRetry = true
                            val all = blobs.map { it }
                            runCatching { uploadBlobsForce(session, all) }
                            jobId = UUID.randomUUID().toString()
                            db.messages().setJob(msg.id, jobId, MessageStatus.WAITING, msg.model)
                            flow.value = flow.value.copy(seq = 0, jobId = jobId, content = "", reasoning = "")
                            started = false
                            break
                        }
                        finishFromEnd(msg, next, end, session.route.name.lowercase())
                        return
                    }
                    if (now - lastPersist > 1_500 && next.seq != lastPersistedSeq) {
                        lastPersist = now
                        lastPersistedSeq = next.seq
                        db.messages().updateStream(msg.id, next.content, next.reasoning.ifEmpty { null }, next.seq, MessageStatus.STREAMING)
                    }
                }
                if (!started) continue
                // sessão caiu no meio: salva o que tem; a tarefa segue no PC e retomamos do mesmo ponto
                db.messages().updateStream(msg.id, flow.value.content, flow.value.reasoning.ifEmpty { null }, flow.value.seq, MessageStatus.STREAMING)
                flow.update { it.copy(detached = true) }
                delay(300)
            } finally {
                collector.cancel()
            }
        }
    }

    private suspend fun uploadBlobsForce(session: NocSession, blobs: List<BlobRef>) = uploadBlobs(session, blobs) { _, _ -> }

    private suspend fun finishFromEnd(msg: MessageEntity, state: LiveReply, end: JsonObject, route: String) {
        val reason = end.str("reason") ?: "stop"
        val s = end.obj("stats")
        val modelKey = end.str("model") ?: msg.model
        val modelName = end.str("name") ?: state.modelName ?: msg.modelName
        val stats = GenStats(
            ttftMs = s?.long("ttftMs"), tps = s?.dbl("tps"), totalMs = s?.long("totalMs"),
            promptTokens = s?.int("promptTokens"), completionTokens = s?.int("completionTokens"),
            reasoningTokens = s?.int("reasoningTokens"), context = end.int("context"), route = route,
            wallMs = s?.long("wallMs"), queuedMs = s?.long("queuedMs"), modelName = modelName,
        )
        val status = when (reason) {
            "cancelled" -> MessageStatus.CANCELLED
            "error" -> MessageStatus.ERROR
            "interrupted" -> MessageStatus.INTERRUPTED
            else -> MessageStatus.DONE
        }
        val reasoningMs = state.reasoningMs ?: state.reasoningStartedAt?.let { System.currentTimeMillis() - it }
        db.messages().finish(
            msg.id, state.content, state.reasoning.ifEmpty { null }, state.seq, status,
            stats.encode(), end.str("error")?.let { code -> code + (end.str("detail")?.let { "|$it" } ?: "") }, reason, reasoningMs,
        )
        db.messages().setModelInfo(msg.id, modelKey, modelName)
        commitLeafIfActive(msg)
        notifyEnd(msg.copy(modelName = modelName), state, status, end.str("error"), stats)
        if (status == MessageStatus.DONE) maybeGenerateTitle(msg.conversationId)
    }

    private suspend fun notifyEnd(msg: MessageEntity, state: LiveReply, status: String, error: String?, stats: GenStats? = null) {
        notifier.onFinished(msg, state.content, status, error, stats)
    }

    private suspend fun finalizeLocal(id: String, state: LiveReply, status: String, reason: String, error: String?) {
        db.messages().finish(id, state.content, state.reasoning.ifEmpty { null }, state.seq, status, null, error, reason, state.reasoningMs)
        db.messages().get(id)?.let { commitLeafIfActive(it) }
    }

    private suspend fun commitLeafIfActive(msg: MessageEntity) {
        val conv = db.conversations().get(msg.conversationId) ?: return
        if (conv.activeLeafId == msg.id) commitLeaf(conv.id, msg.id)
    }

    /** Pede ao próprio modelo um título curto depois da primeira resposta (sem raciocínio, barato). */
    private fun maybeGenerateTitle(convId: String) = scope.launch(Dispatchers.IO) {
        val conv = db.conversations().get(convId) ?: return@launch
        if (!conv.titleAuto) return@launch
        val all = db.messages().all(convId)
        val path = path(all, conv.activeLeafId)
        // só na primeira troca da conversa (editar/regenerar depois não muda o título)
        if (all.size != 2 || path.size != 2 || path[1].status != MessageStatus.DONE) return@launch
        val model = path[1].model ?: return@launch
        val prompt = "Crie um título curto (máximo 5 palavras, sem aspas, sem ponto final) em português para esta conversa.\n\n" +
            "Pergunta: ${path[0].content.take(600)}\n\nResposta: ${path[1].content.take(600)}"
        runCatching {
            val out = quickGenerate(
                model,
                JsonArray(listOf(buildJsonObject { put("role", "user"); put("content", prompt) })),
                buildJsonObject { put("reasoning", "off"); put("max_tokens", 24); put("temperature", 0.3) },
                timeoutMs = 60_000,
                background = true,
            ).content
            val title = out.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                ?.trim('"', '\'', '.', '*', '#', ' ')?.take(60)
            val fresh = db.conversations().get(convId)
            if (!title.isNullOrBlank() && fresh?.titleAuto == true) db.conversations().rename(convId, title, true)
        }
    }

    /** Resultado de uma geração curta (título, teste de conexão, diagnóstico). */
    data class QuickResult(val content: String, val end: JsonObject)

    /**
     * Geração curta fora de uma conversa. Sobrevive à troca de sessão (ex.: relay → rede local):
     * se a conexão mudar no meio, reassina o mesmo job a partir do último evento recebido.
     */
    suspend fun quickGenerate(
        model: String,
        messages: JsonArray,
        params: JsonObject,
        timeoutMs: Long = 180_000,
        background: Boolean = false,
        onDelta: (String) -> Unit = {},
    ): QuickResult = kotlinx.coroutines.withTimeout(timeoutMs) {
        val job = UUID.randomUUID().toString()
        var seq = 0
        var started = false
        val sb = StringBuilder()
        val release = connection.acquire()
        try {
            while (true) {
                val session = connection.session.filter { it != null && it.isOpen }.first()!!
                val events = Channel<JsonObject>(Channel.UNLIMITED)
                val collector = scope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    session.events.collect { e -> if (e.name == "job" && e.data.str("job") == job) events.send(e.data) }
                }
                try {
                    if (!started) {
                        session.call("chat.start", buildJsonObject {
                            put("job", job); put("model", model); put("messages", messages); put("params", params)
                            if (background) put("background", true)
                        }, timeoutMs = 30_000)
                        started = true
                    } else {
                        session.call("chat.subscribe", buildJsonObject { put("job", job); put("from", seq) })
                    }
                    while (true) {
                        val e = select<JsonObject?> {
                            events.onReceive { it }
                            session.closed.onAwait { null }
                        } ?: break
                        val s = e.int("seq") ?: continue
                        if (s <= seq) continue
                        seq = s
                        if (e.bool("reset") == true) sb.clear()
                        e.str("c")?.let { sb.append(it); onDelta(sb.toString()) }
                        e.obj("end")?.let { return@withTimeout QuickResult(sb.toString(), it) }
                    }
                } finally {
                    collector.cancel()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("inalcançável")
        } finally {
            release()
        }
    }

    companion object {
        private const val CHUNK = 96 * 1024

        /** Mantido para chamadas antigas: agora usa o pipeline de [Images]. */
        fun prepareImage(context: Context, bytes: ByteArray, name: String): Attachment? = Images.prepare(context, bytes, name, null)
    }
}

/** Ajuda para ler listas de tarefas do PC. */
fun JsonObject.jobList(): List<com.noc.app.core.net.JobInfo> =
    arr("jobs")?.mapNotNull { it.asObj() }?.map { com.noc.app.core.net.JobInfo.parse(it) } ?: emptyList()
