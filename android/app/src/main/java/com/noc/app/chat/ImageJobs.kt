package com.noc.app.chat

import android.content.Context
import android.util.Base64
import com.noc.app.core.net.ConnState
import com.noc.app.core.net.ConnectionManager
import com.noc.app.core.net.NocSession
import com.noc.app.core.net.RpcError
import com.noc.app.core.net.arr
import com.noc.app.core.net.asObj
import com.noc.app.core.net.bool
import com.noc.app.core.net.dbl
import com.noc.app.core.net.int
import com.noc.app.core.net.long
import com.noc.app.core.net.obj
import com.noc.app.core.net.str
import com.noc.app.data.db.CreationEntity
import com.noc.app.data.db.MessageEntity
import com.noc.app.data.db.NocDatabase
import com.noc.app.data.prefs.Prefs
import com.noc.app.service.GenerationService
import com.noc.app.service.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Imagens criadas no PC. Seguem o mesmo caminho das respostas: a tarefa pertence ao PC (image.start, id gerado aqui,
 * idempotente), o celular acompanha pelos eventos (fases reais, passos, prévias) e retoma do último evento se a
 * conexão cair. Cada imagem pronta é baixada em pedaços pelo canal cifrado (miniatura primeiro, depois o original,
 * conferido por SHA-256) para files/creations — fora do cache, não some sozinha.
 */
class ImageJobs(
    private val context: Context,
    private val db: NocDatabase,
    private val prefs: Prefs,
    private val connection: ConnectionManager,
    private val engine: ChatEngine,
    private val scope: CoroutineScope,
) {
    private val _caps = MutableStateFlow<ImageCaps?>(null)
    /** O que o PC oferece agora (formatos, motores instalados, estado dos modelos). */
    val caps: StateFlow<ImageCaps?> = _caps.asStateFlow()
    private val jobLocks = ConcurrentHashMap<String, Mutex>()
    /** Um download por imagem de cada vez (a retomada e o fim da tarefa podem pedir a mesma imagem). */
    private val dlLocks = ConcurrentHashMap<String, Mutex>()

    val dir: File get() = File(context.filesDir, "creations").apply { mkdirs() }

    init {
        // o PC avisa quando algo do motor de imagem muda (download, instalação, estado)
        scope.launch {
            connection.events.collect { e ->
                if (e.name == "image") _caps.update { old -> old?.copy(models = ImageCaps.parseModels(e.data), state = e.data.str("state") ?: old.state, hfUser = e.data.str("hfUser")) ?: ImageCaps.parse(e.data) }
            }
        }
        scope.launch {
            connection.state.collect { s -> if (s is ConnState.Online && s.pc.has("image")) refreshCaps() }
        }
    }

    fun supported(): Boolean = (connection.state.value as? ConnState.Online)?.pc?.has("image") == true || connection.lastFeatures.contains("image")

    suspend fun refreshCaps(): ImageCaps? {
        if (!supported()) return null
        return runCatching { ImageCaps.parse(connection.call("image.status", waitMs = 4_000)) }.getOrNull()?.also { _caps.value = it }
    }

    /** O usuário abriu "Criar imagem": o PC já liga o gerador (sem tirar o modelo de conversa da GPU). */
    fun prepare(engineChoice: String) = scope.launch {
        runCatching { connection.call("image.prepare", buildJsonObject { put("engine", engineChoice) }, waitMs = 2_000) }
    }

    private fun lock(messageId: String) = jobLocks.getOrPut(messageId) { Mutex() }

    private suspend fun updateJob(messageId: String, f: (ImageJob) -> ImageJob): ImageJob? = lock(messageId).withLock {
        val m = db.messages().get(messageId) ?: return@withLock null
        val cur = ImageJob.decode(m.imageJson) ?: return@withLock null
        val next = f(cur)
        db.messages().setImage(messageId, next.encode())
        next
    }

    // ------------------------------------------------------------------ criar

    /** Pedido novo: mensagem do usuário ("Criar imagem") + resposta de imagem, e a tarefa vai para o PC. */
    suspend fun create(convId: String, prompt: String, options: ImageOptions): String = withContext(Dispatchers.IO) {
        val conv = db.conversations().get(convId) ?: error("conversa não existe")
        val now = System.currentTimeMillis()
        val job = ImageJob(prompt = prompt.trim(), options = options)
        val user = MessageEntity(
            id = UUID.randomUUID().toString(), conversationId = convId, parentId = conv.activeLeafId, role = "user",
            content = prompt.trim(), createdAt = now, kind = "image_request", imageJson = job.encode(),
        )
        db.messages().insert(user)
        if (conv.titleAuto && conv.messageCount == 0) db.conversations().rename(convId, title(prompt), true)
        startReply(convId, user.id, job, now + 1)
    }

    /** "Gerar novamente": outra versão para o mesmo pedido (ramo ‹1/2›), com seed nova. */
    suspend fun regenerate(old: MessageEntity): String = withContext(Dispatchers.IO) {
        val job = ImageJob.decode(old.imageJson) ?: error("mensagem sem imagem")
        startReply(old.conversationId, old.parentId, ImageJob(job.prompt, job.options.copy(seed = null)))
    }

    /** "Criar variação": o mesmo pedido de novo, no fim da conversa (as duas ficam visíveis). */
    suspend fun variation(old: MessageEntity): String {
        val job = ImageJob.decode(old.imageJson) ?: error("mensagem sem imagem")
        return create(old.conversationId, job.prompt, job.options.copy(seed = null))
    }

    /** Tentar de novo depois de um erro: mesma mensagem, mesmos parâmetros (inclusive a seed, se havia). */
    suspend fun retry(old: MessageEntity) = withContext(Dispatchers.IO) {
        val job = ImageJob.decode(old.imageJson) ?: return@withContext
        val fresh = ImageJob(job.prompt, job.options.copy(seed = job.options.seed ?: job.seed))
        val jobId = UUID.randomUUID().toString()
        db.messages().setJob(old.id, jobId, MessageStatus.WAITING, old.model)
        db.messages().setImage(old.id, fresh.encode())
        run(old.copy(jobId = jobId, status = MessageStatus.WAITING, imageJson = fresh.encode()), jobId, start = true, fromSeq = 0)
    }

    private suspend fun startReply(convId: String, parentId: String?, job: ImageJob, createdAt: Long = System.currentTimeMillis()): String {
        val jobId = UUID.randomUUID().toString()
        val name = _caps.value?.engine(job.options.engine)?.name
        val reply = MessageEntity(
            id = UUID.randomUUID().toString(), conversationId = convId, parentId = parentId, role = "assistant", content = "",
            createdAt = createdAt, status = MessageStatus.WAITING, jobId = jobId, kind = "image", imageJson = job.encode(),
            modelName = name, model = _caps.value?.engine(job.options.engine)?.model,
        )
        db.messages().insert(reply)
        engine.commitLeaf(convId, reply.id)
        run(reply, jobId, start = true, fromSeq = 0)
        return reply.id
    }

    private fun title(prompt: String): String {
        val clean = prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.replace(Regex("[#*_`>]"), "") ?: "Imagem"
        return if (clean.length <= 48) clean else clean.take(46).substringBeforeLast(' ', clean.take(46)) + "…"
    }

    /** Retoma uma tarefa de imagem que estava em andamento quando o app fechou. */
    fun resume(m: MessageEntity) {
        val jobId = m.jobId ?: return
        run(m, jobId, start = false, fromSeq = m.lastSeq)
    }

    // ------------------------------------------------------------------ acompanhar

    private fun run(msg: MessageEntity, jobId: String, start: Boolean, fromSeq: Int) {
        val job = ImageJob.decode(msg.imageJson)
        val flow = MutableStateFlow(
            LiveReply(
                messageId = msg.id, conversationId = msg.conversationId, content = "", reasoning = "", seq = fromSeq,
                phase = LiveReply.Phase.SENDING, model = msg.model, modelName = msg.modelName, jobId = jobId, isImage = true,
                imageCount = job?.options?.count ?: 1, imagesReady = job?.ready?.size ?: 0,
            ),
        )
        engine.live[msg.id] = flow
        engine._liveIds.update { it + msg.id }
        GenerationService.ensureRunning(context)
        SyncWorker.schedule(context)
        val j = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val release = connection.acquire()
            try {
                loop(msg, jobId, start, flow)
            } finally {
                release()
                engine.live.remove(msg.id)
                engine.runners.remove(msg.id)
                engine._liveIds.update { it - msg.id }
            }
        }
        engine.runners[msg.id] = j
        j.start()
    }

    private suspend fun loop(msg: MessageEntity, jobId: String, start: Boolean, flow: MutableStateFlow<LiveReply>) {
        var started = !start
        var lastPersist = 0L
        val downloads = ArrayList<kotlinx.coroutines.Deferred<Boolean>>()
        while (true) {
            val session = withTimeoutOrNull(1_500) { connection.session.filter { it != null && it.isOpen }.first() }
                ?: run {
                    flow.update { it.copy(detached = started) }
                    connection.session.filter { it != null && it.isOpen }.first()
                }!!
            flow.update { it.copy(detached = false) }
            val events = Channel<JsonObject>(Channel.UNLIMITED)
            val collector = scope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                session.events.collect { e -> if (e.name == "job" && e.data.str("job") == jobId) events.send(e.data) }
            }
            try {
                try {
                    if (!started) {
                        val job = ImageJob.decode(db.messages().get(msg.id)?.imageJson) ?: return
                        val conv = db.conversations().get(msg.conversationId)
                        val r = session.call("image.start", buildJsonObject {
                            put("job", jobId)
                            put("prompt", job.prompt)
                            job.options.toWire().forEach { (k, v) -> put(k, v) }
                            put("meta", buildJsonObject { put("conv", msg.conversationId); put("msg", msg.id) })
                            conv?.title?.let { put("label", it.take(80)) }
                        }, timeoutMs = 30_000)
                        started = true
                        val name = r.str("name")
                        if (name != null) db.messages().setModelInfo(msg.id, r.str("model"), name)
                        updateJob(msg.id) {
                            it.copy(model = r.str("model") ?: it.model, modelName = name ?: it.modelName, width = r.int("width"), height = r.int("height"),
                                steps = r.int("steps"), seed = r.long("seed"), note = r.str("note") ?: it.note)
                        }
                        val pos = r.int("position")
                        flow.update { s ->
                            s.copy(modelName = name ?: s.modelName, steps = r.int("steps") ?: s.steps, imageCount = r.int("count") ?: s.imageCount,
                                phase = if (pos != null && pos >= 0) LiveReply.Phase.QUEUED else s.phase, ahead = pos?.takeIf { it >= 0 } ?: s.ahead)
                        }
                    } else {
                        session.call("chat.subscribe", buildJsonObject { put("job", jobId); put("from", flow.value.seq) })
                    }
                } catch (e: RpcError) {
                    // o PC não conhece mais a tarefa (ex.: dias depois): o que já chegou fica; o resto é interrompido
                    val code = if (e.code == "job_unknown") "job_unknown" else e.code
                    finish(msg, flow.value, JsonObject(mapOf()), code, e.message, downloads)
                    return
                }

                while (true) {
                    val e = select<JsonObject?> {
                        events.onReceive { it }
                        session.closed.onAwait { null }
                    } ?: break
                    if (e.bool("transient") == true) {
                        e.str("preview")?.let { p -> flow.update { it.copy(preview = p) } }
                        continue
                    }
                    val seq = e.int("seq") ?: continue
                    val cur = flow.value
                    if (seq <= cur.seq) continue
                    var next = cur.copy(seq = seq)
                    val now = System.currentTimeMillis()
                    fun phase(p: LiveReply.Phase) { if (next.phase != p) next = next.copy(phase = p, phaseSince = now, stageProgress = null) }
                    when (e.str("phase")) {
                        "queued" -> { phase(LiveReply.Phase.QUEUED); next = next.copy(ahead = e.int("ahead") ?: next.ahead) }
                        "starting" -> phase(LiveReply.Phase.LOADING)
                        "preparing_engine" -> phase(LiveReply.Phase.PREPARING_ENGINE)
                        "optimizing" -> { phase(LiveReply.Phase.OPTIMIZING); next = next.copy(stageProgress = e.dbl("progress")) }
                        "freeing" -> phase(LiveReply.Phase.FREEING)
                        "loading" -> { phase(LiveReply.Phase.LOADING); next = next.copy(expectedSeconds = e.dbl("expectedSeconds") ?: next.expectedSeconds, modelName = e.str("name") ?: next.modelName) }
                        "prompt" -> { phase(LiveReply.Phase.PROMPT); next = next.copy(promptMode = e.str("mode")) }
                        "generating" -> { phase(LiveReply.Phase.GENERATING); next = next.copy(imageCount = e.int("count") ?: next.imageCount, modelName = e.str("name") ?: next.modelName) }
                        "recovering" -> phase(LiveReply.Phase.RECOVERING)
                    }
                    e.int("step")?.let { st ->
                        phase(LiveReply.Phase.GENERATING)
                        next = next.copy(step = st, steps = e.int("steps") ?: next.steps, imageIndex = e.int("image") ?: next.imageIndex, imageCount = e.int("count") ?: next.imageCount)
                    }
                    if (e.str("stage") == "saving") phase(LiveReply.Phase.SAVING)
                    e.obj("imageReady")?.let { o ->
                        val img = ImageJob.parseImage(o)
                        val job = updateJob(msg.id) { j -> if (j.images.any { it.id == img.id }) j else j.copy(images = j.images + img, pending = true) }
                        if (job != null) {
                            upsertCreation(msg, job, img)
                            downloads += scope.async(Dispatchers.IO) { download(msg.id, img) { flow.update { s -> s.copy(imagesReady = s.imagesReady + 1) } } }
                        }
                        // a próxima imagem começa do zero
                        next = next.copy(step = null, preview = null)
                    }
                    flow.value = next
                    val end = e.obj("end")
                    if (end != null) {
                        finish(msg, next, end, end.str("error"), end.str("detail"), downloads)
                        return
                    }
                    if (now - lastPersist > 1_500) {
                        lastPersist = now
                        db.messages().setSeq(msg.id, next.seq, MessageStatus.STREAMING)
                    }
                }
                if (!started) continue
                db.messages().setSeq(msg.id, flow.value.seq, MessageStatus.STREAMING)
                flow.update { it.copy(detached = true) }
                delay(300)
            } finally {
                collector.cancel()
            }
        }
    }

    private suspend fun finish(msg: MessageEntity, state: LiveReply, end: JsonObject, error: String?, detail: String?, downloads: List<kotlinx.coroutines.Deferred<Boolean>>) {
        val reason = end.str("reason") ?: if (error != null) "error" else "stop"
        val status = when (reason) {
            "cancelled" -> MessageStatus.CANCELLED
            "error" -> MessageStatus.ERROR
            "interrupted" -> MessageStatus.INTERRUPTED
            "stop" -> MessageStatus.DONE
            else -> if (error == "job_unknown") MessageStatus.INTERRUPTED else MessageStatus.ERROR
        }
        val finalImages = end.arr("images")?.mapNotNull { it.asObj() }?.map { ImageJob.parseImage(it) } ?: emptyList()
        val prompt = end.obj("prompt")
        val stats = end.obj("stats")
        val name = end.str("name")
        val job = updateJob(msg.id) { j ->
            val merged = (j.images + finalImages.filter { f -> j.images.none { it.id == f.id } })
            j.copy(
                images = merged, pending = merged.any { it.local == null && !it.gone }, finalPrompt = prompt?.str("final") ?: j.finalPrompt,
                rewrite = prompt?.str("mode") ?: j.rewrite, note = prompt?.str("note") ?: j.note, model = end.str("model") ?: j.model,
                modelName = name ?: j.modelName, width = stats?.int("width") ?: j.width, height = stats?.int("height") ?: j.height,
                steps = stats?.int("steps") ?: j.steps, guidance = stats?.dbl("guidance") ?: j.guidance, seed = stats?.long("seed") ?: j.seed,
                wallMs = stats?.long("wallMs"), generateMs = stats?.long("generateMs"),
                error = if (status == MessageStatus.DONE) null else error, detail = if (status == MessageStatus.DONE) null else detail,
            )
        }
        // imagens anunciadas só no fim (ex.: retomada depois de o app fechar): baixa agora
        val all = ArrayList(downloads)
        if (job != null) {
            for (img in job.images.filter { it.local == null && !it.gone }) {
                upsertCreation(msg, job, img)
                all += scope.async(Dispatchers.IO) { download(msg.id, img) {} }
            }
        }
        if (all.isNotEmpty()) {
            engine.live[msg.id]?.update { it.copy(phase = LiveReply.Phase.DOWNLOADING) }
            withTimeoutOrNull(5 * 60_000) { all.awaitAll() }
        }
        val fresh = lock(msg.id).withLock {
            val f = ImageJob.decode(db.messages().get(msg.id)?.imageJson) ?: job
            db.messages().finishImage(msg.id, f?.encode(), status, if (status == MessageStatus.DONE) null else listOfNotNull(error, detail).joinToString("|").ifEmpty { null },
                reason, state.seq, name)
            f
        }
        engine.commitLeaf(msg.conversationId, db.conversations().get(msg.conversationId)?.activeLeafId)
        if (fresh?.pending == true) SyncWorker.schedule(context, delayMinutes = 1)
        engine.notifier.onImageFinished(msg.copy(modelName = name ?: msg.modelName), fresh, status, error, detail)
    }

    /** Parada que o PC não confirmou (ex.: sem conexão): encerra aqui. */
    suspend fun finalizeCancelled(msg: MessageEntity) {
        db.messages().finishImage(msg.id, msg.imageJson, MessageStatus.CANCELLED, null, "cancelled", msg.lastSeq, null)
    }

    // ------------------------------------------------------------------ baixar

    private suspend fun upsertCreation(msg: MessageEntity, job: ImageJob, img: GenImage) {
        val existing = db.creations().get(img.id)
        db.creations().upsert(
            CreationEntity(
                id = img.id, conversationId = msg.conversationId, messageId = msg.id, jobId = msg.jobId,
                pcId = prefs.current().activePcId, prompt = job.prompt, finalPrompt = job.finalPrompt, model = job.model ?: "",
                modelName = job.modelName ?: msg.modelName ?: "", width = img.width, height = img.height, steps = job.steps,
                guidance = job.guidance, seed = img.seed, format = job.options.format, quality = job.options.quality,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(), genMs = job.generateMs, bytes = img.bytes, sha256 = img.sha256,
                path = existing?.path, thumbPath = existing?.thumbPath, favorite = existing?.favorite ?: false,
            ),
        )
    }

    fun fileOf(id: String) = File(dir, "$id.png")
    fun thumbOf(id: String) = File(dir, "$id.thumb.jpg")

    /**
     * Baixa uma imagem do PC (miniatura e original), em pedaços de 256 KB com 3 pedidos em paralelo,
     * continuando de onde parou se a conexão cair. Devolve false se o PC não tiver mais a imagem.
     */
    private suspend fun download(messageId: String, img: GenImage, onDone: () -> Unit): Boolean =
        dlLocks.getOrPut(img.id) { Mutex() }.withLock {
            var attempt = 0
            while (true) {
                try {
                    val thumb = thumbOf(img.id)
                    if (!thumb.exists()) fetchFile(img.id, "thumb", thumb, null)
                    updateJob(messageId) { j -> j.copy(images = j.images.map { if (it.id == img.id) it.copy(thumb = thumb.path) else it }) }
                    val full = fileOf(img.id)
                    if (!full.exists()) fetchFile(img.id, "full", full, img.sha256)
                    updateJob(messageId) { j ->
                        val imgs = j.images.map { if (it.id == img.id) it.copy(local = full.path, thumb = thumb.path) else it }
                        j.copy(images = imgs, pending = imgs.any { it.local == null && !it.gone })
                    }
                    db.creations().setFiles(img.id, full.path, thumb.path, full.length())
                    onDone()
                    return@withLock true
                } catch (e: RpcError) {
                    if (e.code == "image_unknown") {
                        // o PC não tem mais esta imagem (apagada lá): para de tentar
                        updateJob(messageId) { j ->
                            val imgs = j.images.map { if (it.id == img.id) it.copy(gone = true) else it }
                            j.copy(images = imgs, pending = imgs.any { it.local == null && !it.gone })
                        }
                        return@withLock false
                    }
                    attempt++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attempt++
                }
                // sem conexão: espera a sessão voltar e continua do ponto (o .part fica)
                withTimeoutOrNull(60_000) { connection.session.filter { it != null && it.isOpen }.first() }
                delay((1_000L * attempt).coerceAtMost(15_000))
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }

    private suspend fun fetchFile(id: String, kind: String, dest: File, sha256: String?) {
        val part = File(dest.path + ".part")
        val chunk = 256 * 1024
        var total = -1L
        RandomAccessFile(part, "rw").use { raf ->
            var have = raf.length()
            // primeiro pedaço diz o tamanho total
            if (total < 0) {
                val r = connection.call("image.get", buildJsonObject { put("id", id); put("kind", kind); put("offset", have); put("length", chunk) }, timeoutMs = 60_000)
                total = r.long("total") ?: 0
                val data = Base64.decode(r.str("data") ?: "", Base64.NO_WRAP)
                raf.seek(have); raf.write(data)
                have += data.size
            }
            while (have < total) {
                // três pedidos em voo (o relay aguenta; latência do 4G/5G deixa de dominar)
                val offsets = (0 until 3).map { have + it.toLong() * chunk }.filter { it < total }
                val parts = kotlinx.coroutines.coroutineScope {
                    offsets.map { off ->
                        async {
                            val r = connection.call("image.get", buildJsonObject { put("id", id); put("kind", kind); put("offset", off); put("length", chunk) }, timeoutMs = 60_000)
                            off to Base64.decode(r.str("data") ?: "", Base64.NO_WRAP)
                        }
                    }.awaitAll()
                }
                for ((off, data) in parts.sortedBy { it.first }) {
                    if (data.isEmpty()) error("pedaço vazio")
                    raf.seek(off); raf.write(data)
                    have = maxOf(have, off + data.size)
                }
            }
            raf.setLength(total)
        }
        if (sha256 != null) {
            val actual = MessageDigest.getInstance("SHA-256").digest(part.readBytes()).joinToString("") { "%02x".format(it) }
            if (actual != sha256) {
                part.delete()
                error("imagem corrompida no caminho")
            }
        }
        if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); part.delete() }
    }

    /** Imagens prontas no PC que não chegaram (app fechado no meio, sem internet): busca agora. */
    fun fetchMissing() = scope.launch(Dispatchers.IO) {
        for (m in db.messages().imagesToFetch()) {
            if (engine.live.containsKey(m.id)) continue
            val job = ImageJob.decode(m.imageJson) ?: continue
            for (img in job.images.filter { it.local == null && !it.gone }) {
                upsertCreation(m, job, img)
                launch { download(m.id, img) {} }
            }
        }
    }

    /** Apaga do celular (e, se [alsoPc], do PC). */
    suspend fun delete(ids: List<String>, alsoPc: Boolean) = withContext(Dispatchers.IO) {
        for (id in ids) {
            fileOf(id).delete(); thumbOf(id).delete()
            if (alsoPc) runCatching { connection.call("image.delete", buildJsonObject { put("id", id) }, waitMs = 3_000) }
        }
        db.creations().delete(ids)
    }
}
