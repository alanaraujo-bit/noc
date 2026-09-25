package com.noc.app.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.noc.app.core.net.ConnState
import com.noc.app.core.net.ConnectionManager
import com.noc.app.core.net.NocSession
import com.noc.app.core.net.RpcError
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Estado ao vivo de uma resposta sendo gerada (a UI sobrepõe isso ao que está no banco). */
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
) {
    enum class Phase { WAITING, LOADING, GENERATING }
}

/** Configuração efetiva de uma conversa (preset + ajustes da conversa + prompt padrão). */
data class EffectiveConfig(
    val model: String?,
    val systemPrompt: String?,
    val params: GenerationParams,
    val presetId: String?,
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
    /** Ids das mensagens com geração ativa (para a Home, o serviço e a notificação). */
    val liveIds: StateFlow<Set<String>> = _liveIds.asStateFlow()

    fun liveFlow(messageId: String): StateFlow<LiveReply>? = live[messageId]

    /** Retoma respostas que estavam em andamento quando o app foi fechado. */
    fun resumePending() = scope.launch(Dispatchers.IO) {
        db.messages().markOrphans()
        for (m in db.messages().pending()) {
            val job = m.jobId ?: continue
            startRunner(m, job, request = null, baseContent = m.content, baseReasoning = m.reasoning ?: "", fromSeq = m.lastSeq)
        }
    }

    // ------------------------------------------------------------------ configuração

    suspend fun effectiveConfig(conv: ConversationEntity?): EffectiveConfig {
        val p = prefs.current()
        val presetId = conv?.presetId ?: p.defaultPresetId ?: Library.PRESET_BALANCED
        val preset = db.presets().get(presetId) ?: db.presets().get(Library.PRESET_BALANCED)
        val base = GenerationParams.decode(preset?.paramsJson) ?: GenerationParams()
        val params = base.overlay(GenerationParams.decode(conv?.paramsJson))
        val system = conv?.systemPrompt ?: preset?.systemPrompt ?: db.prompts().getDefault()?.content
        val loaded = connection.status.value?.loaded?.firstOrNull()?.key
        val model = conv?.model ?: preset?.model ?: loaded ?: p.lastModel
            ?: connection.models.value.firstOrNull { it.isLlm }?.key
        return EffectiveConfig(model, system?.takeIf { it.isNotBlank() }, params, preset?.id)
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

    private fun previewOf(s: String): String =
        s.replace(Regex("```[\\s\\S]*?```"), "[código]").replace(Regex("[#*_`>]"), "").replace(Regex("\\s+"), " ").trim().take(140)

    // ------------------------------------------------------------------ enviar / regenerar / editar

    suspend fun send(convId: String, text: String, attachments: List<Attachment>): String = withContext(Dispatchers.IO) {
        val conv = db.conversations().get(convId) ?: error("conversa não existe")
        val cfg = effectiveConfig(conv)
        val model = cfg.model ?: throw NoModel()
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
        if (conv.model == null) db.conversations().setModel(convId, model)
        startAssistant(convId, parentId = user.id, model = model, cfg = cfg, createdAt = now + 1)
    }

    /** Nova resposta para a mesma pergunta (vira um ramo irmão). */
    suspend fun regenerate(assistantId: String, model: String? = null): String = withContext(Dispatchers.IO) {
        val old = db.messages().get(assistantId) ?: error("mensagem não existe")
        val conv = db.conversations().get(old.conversationId)!!
        val cfg = effectiveConfig(conv)
        val useModel = model ?: cfg.model ?: old.model ?: throw NoModel()
        if (model != null) db.conversations().setModel(conv.id, model)
        startAssistant(conv.id, parentId = old.parentId, model = useModel, cfg = cfg)
    }

    /** Edita uma pergunta: cria um ramo novo a partir dela e gera outra resposta. */
    suspend fun edit(userMessageId: String, newText: String): String = withContext(Dispatchers.IO) {
        val old = db.messages().get(userMessageId) ?: error("mensagem não existe")
        val conv = db.conversations().get(old.conversationId)!!
        val cfg = effectiveConfig(conv)
        val model = cfg.model ?: throw NoModel()
        val now = System.currentTimeMillis()
        val user = old.copy(id = UUID.randomUUID().toString(), content = newText.trim(), createdAt = now)
        db.messages().insert(user)
        startAssistant(conv.id, parentId = user.id, model = model, cfg = cfg, createdAt = now + 1)
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
        val request = buildRequest(history, cfg.copy(model = model), model)
        val job = UUID.randomUUID().toString()
        db.messages().setJob(assistantId, job, MessageStatus.WAITING, model)
        startRunner(
            msg.copy(jobId = job, status = MessageStatus.WAITING), job, request,
            baseContent = msg.content, baseReasoning = msg.reasoning ?: "", fromSeq = 0,
        )
    }

    private suspend fun startAssistant(convId: String, parentId: String?, model: String, cfg: EffectiveConfig, createdAt: Long = System.currentTimeMillis()): String {
        val job = UUID.randomUUID().toString()
        val reply = MessageEntity(
            id = UUID.randomUUID().toString(), conversationId = convId, parentId = parentId,
            role = "assistant", content = "", createdAt = createdAt, model = model,
            status = MessageStatus.WAITING, jobId = job,
        )
        db.messages().insert(reply)
        commitLeaf(convId, reply.id)
        prefs.noteModelUsed(model)
        val all = db.messages().all(convId)
        val history = path(all, parentId)
        val request = buildRequest(history, cfg, model)
        startRunner(reply, job, request, baseContent = "", baseReasoning = "", fromSeq = 0)
        return reply.id
    }

    fun stop(messageId: String) {
        val m = live[messageId]?.value ?: return
        scope.launch {
            val msg = db.messages().get(messageId) ?: return@launch
            val job = msg.jobId ?: return@launch
            val res = runCatching { connection.call("chat.cancel", buildJsonObject { put("job", job) }, waitMs = 1_500) }.getOrNull()
            // o PC confirma com ok=true quando o job existe; nos outros casos encerramos aqui mesmo
            if (res?.get("ok")?.toString() != "true") {
                // sem conexão: encerra localmente mantendo o que já chegou
                runners.remove(messageId)?.cancel()
                finalizeLocal(messageId, m, MessageStatus.CANCELLED, "cancelled", null)
            }
        }
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
            append(if (m.role == "user") "**Você**" else "**${m.model ?: "IA"}**").append("\n\n")
            Attachment.decodeList(m.attachmentsJson).forEach { a -> append("> 📎 ").append(a.name).append("\n") }
            append(m.content.trim()).append("\n\n---\n\n")
        }
    }

    private fun autoTitle(text: String, attachments: List<Attachment>): String {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: attachments.firstOrNull()?.name ?: "Nova conversa"
        val clean = line.replace(Regex("[#*_`>]"), "").trim()
        return if (clean.length <= 48) clean else clean.take(46).substringBeforeLast(' ', clean.take(46)) + "…"
    }

    class NoModel : Exception("nenhum modelo")

    // ------------------------------------------------------------------ montagem do pedido

    private fun buildRequest(history: List<MessageEntity>, cfg: EffectiveConfig, model: String): JsonObject {
        val info = connection.models.value.firstOrNull { it.key == model }
        val loadedCtx = connection.status.value?.loaded?.firstOrNull { it.key == model }?.context
        val ctx = loadedCtx ?: cfg.params.contextLength ?: info?.maxContext?.coerceAtMost(16384) ?: 8192
        val vision = info?.vision == true
        val msgs = history.filter { it.role == "user" || (it.role == "assistant" && it.content.isNotBlank()) }

        // Orçamento de contexto: mantém o prompt de sistema e as mensagens mais recentes.
        val reserve = (cfg.params.maxTokens ?: (ctx / 4)).coerceAtMost(ctx / 2)
        var budget = ctx - reserve - estimateTokens(cfg.systemPrompt ?: "")
        val kept = ArrayList<MessageEntity>()
        for (m in msgs.asReversed()) {
            val cost = estimateTokens(m.content) + Attachment.decodeList(m.attachmentsJson).sumOf { estimateTokens(it.text ?: "") + if (it.kind == "image") 800 else 0 }
            if (kept.isNotEmpty() && cost > budget) break
            kept.add(m)
            budget -= cost
        }
        kept.reverse()

        val messages = buildJsonArray {
            cfg.systemPrompt?.let { add(buildJsonObject { put("role", "system"); put("content", it) }) }
            kept.forEach { m -> add(messageJson(m, vision)) }
        }
        return buildJsonObject {
            put("model", model)
            put("messages", messages)
            put("params", cfg.params.toJson())
            cfg.params.contextLength?.let { put("context", it) }
            put("trimmed", kept.size < msgs.size)
        }
    }

    private fun estimateTokens(s: String) = (s.length / 3.2).toInt() + 4

    private fun messageJson(m: MessageEntity, vision: Boolean): JsonObject {
        val atts = Attachment.decodeList(m.attachmentsJson)
        val text = buildString {
            atts.filter { it.kind == "text" }.forEach { a ->
                append("[Arquivo: ").append(a.name).append("]\n```\n").append(a.text ?: "").append("\n```\n\n")
            }
            append(m.content)
        }
        val images = if (vision) atts.filter { it.kind == "image" && it.path != null } else emptyList()
        return buildJsonObject {
            put("role", m.role)
            if (images.isEmpty()) {
                put("content", text)
            } else {
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", text) })
                    images.forEach { img ->
                        dataUrl(File(img.path!!))?.let { url ->
                            add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject { put("url", url) })
                            })
                        }
                    }
                })
            }
        }
    }

    private fun dataUrl(file: File): String? = runCatching {
        val bytes = file.readBytes()
        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()

    // ------------------------------------------------------------------ streaming

    private fun startRunner(
        msg: MessageEntity,
        jobId: String,
        request: JsonObject?,
        baseContent: String,
        baseReasoning: String,
        fromSeq: Int,
    ) {
        val flow = MutableStateFlow(
            LiveReply(
                messageId = msg.id, conversationId = msg.conversationId, content = baseContent, reasoning = baseReasoning,
                seq = fromSeq, phase = LiveReply.Phase.WAITING,
            ),
        )
        live[msg.id] = flow
        _liveIds.update { it + msg.id }
        GenerationService.ensureRunning(context)
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val release = connection.acquire()
            try {
                runJob(msg, jobId, request, flow)
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

    private suspend fun runJob(msg: MessageEntity, jobId: String, request: JsonObject?, flow: MutableStateFlow<LiveReply>) {
        var started = request == null // sem request = retomada: só dá para assinar
        var lastPersist = 0L
        var lastPersistedSeq = flow.value.seq
        while (true) {
            // espera uma sessão aberta
            val session = connection.session.filter { it != null && it.isOpen }.first()!!
            val events = Channel<JsonObject>(Channel.UNLIMITED)
            val collector = scope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                session.events.collect { e ->
                    if (e.name == "job" && e.data.str("job") == jobId) events.send(e.data)
                }
            }
            try {
                val res = try {
                    if (!started) {
                        val p = buildJsonObject {
                            put("job", jobId)
                            request!!.forEach { (k, v) -> if (k != "trimmed") put(k, v) }
                        }
                        session.call("chat.start", p, timeoutMs = 30_000).also { started = true }
                    } else {
                        session.call("chat.subscribe", buildJsonObject { put("job", jobId); put("from", flow.value.seq) })
                    }
                } catch (e: RpcError) {
                    val code = e.code
                    if (code == "job_unknown") {
                        finalizeLocal(msg.id, flow.value, MessageStatus.INTERRUPTED, "interrupted", "job_unknown")
                    } else {
                        finalizeLocal(msg.id, flow.value, MessageStatus.ERROR, "error", code)
                    }
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
                    when (e.str("phase")) {
                        "loading" -> next = next.copy(phase = LiveReply.Phase.LOADING)
                        "generating" -> next = next.copy(phase = LiveReply.Phase.GENERATING)
                    }
                    e.str("r")?.let { r ->
                        next = next.copy(
                            reasoning = next.reasoning + r, phase = LiveReply.Phase.GENERATING,
                            reasoningStartedAt = next.reasoningStartedAt ?: System.currentTimeMillis(),
                        )
                    }
                    e.str("c")?.let { c ->
                        val rMs = next.reasoningMs ?: next.reasoningStartedAt?.let { System.currentTimeMillis() - it }
                        next = next.copy(content = next.content + c, phase = LiveReply.Phase.GENERATING, reasoningMs = rMs)
                    }
                    e.int("n")?.let { next = next.copy(tokens = it) }
                    e.dbl("tps")?.let { next = next.copy(tps = it) }
                    flow.value = next

                    val end = e.obj("end")
                    if (end != null) {
                        finishFromEnd(msg, next, end, session.route.name.lowercase())
                        return
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastPersist > 700 && next.seq != lastPersistedSeq) {
                        lastPersist = now
                        lastPersistedSeq = next.seq
                        db.messages().updateStream(msg.id, next.content, next.reasoning.ifEmpty { null }, next.seq, MessageStatus.STREAMING)
                    }
                }
                // sessão caiu no meio: salva o que tem e volta a esperar conexão
                db.messages().updateStream(msg.id, flow.value.content, flow.value.reasoning.ifEmpty { null }, flow.value.seq, MessageStatus.STREAMING)
                flow.update { it.copy(phase = LiveReply.Phase.WAITING) }
                delay(300)
            } finally {
                collector.cancel()
            }
        }
    }

    private suspend fun finishFromEnd(msg: MessageEntity, state: LiveReply, end: JsonObject, route: String) {
        val reason = end.str("reason") ?: "stop"
        val s = end.obj("stats")
        val stats = GenStats(
            ttftMs = s?.long("ttftMs"), tps = s?.dbl("tps"), totalMs = s?.long("totalMs"),
            promptTokens = s?.int("promptTokens"), completionTokens = s?.int("completionTokens"),
            reasoningTokens = s?.int("reasoningTokens"), context = end.int("context"), route = route,
        )
        val status = when (reason) {
            "cancelled" -> MessageStatus.CANCELLED
            "error" -> MessageStatus.ERROR
            else -> MessageStatus.DONE
        }
        val reasoningMs = state.reasoningMs ?: state.reasoningStartedAt?.let { System.currentTimeMillis() - it }
        db.messages().finish(
            msg.id, state.content, state.reasoning.ifEmpty { null }, state.seq, status,
            stats.encode(), end.str("error")?.let { code -> code + (end.str("detail")?.let { "|$it" } ?: "") }, reason, reasoningMs,
        )
        commitLeafIfActive(msg)
        GenerationService.notifyFinished(context, msg.conversationId, state.content, status)
        maybeGenerateTitle(msg.conversationId)
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
        /** Reduz e comprime uma imagem para envio (lado maior 1536 px, JPEG 85). */
        fun prepareImage(context: Context, bytes: ByteArray, name: String): Attachment? {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0) return null
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > 3072) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val scale = 1536f / maxOf(bmp.width, bmp.height)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val dir = File(context.filesDir, "attachments").apply { mkdirs() }
            val file = File(dir, UUID.randomUUID().toString() + ".jpg")
            file.writeBytes(out.toByteArray())
            return Attachment(kind = "image", name = name, mime = "image/jpeg", size = file.length(), path = file.absolutePath)
        }
    }
}
