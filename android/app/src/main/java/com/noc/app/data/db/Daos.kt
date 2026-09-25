package com.noc.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PcDao {
    @Query("SELECT * FROM pcs ORDER BY pairedAt")
    fun observeAll(): Flow<List<PcEntity>>

    @Query("SELECT * FROM pcs WHERE id = :id")
    suspend fun get(id: String): PcEntity?

    @Query("SELECT * FROM pcs WHERE id = :id")
    fun observe(id: String): Flow<PcEntity?>

    @Upsert
    suspend fun upsert(pc: PcEntity)

    @Query("UPDATE pcs SET lastConnectedAt = :at, lastRoute = :route, lan = :lan, name = :name, relayUrl = COALESCE(:relay, relayUrl) WHERE id = :id")
    suspend fun updateAfterConnect(id: String, at: Long, route: String, lan: String, name: String, relay: String?)

    @Query("DELETE FROM pcs WHERE id = :id")
    suspend fun delete(id: String)
}

/** Linha de lista de conversas (sem carregar mensagens). */
data class ConversationRow(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val pinned: Boolean,
    val archived: Boolean,
    val folder: String?,
    val model: String?,
    val preview: String?,
    val messageCount: Int,
)

data class MessageText(val conversationId: String, val content: String)

/** Busca que ignora acentos e maiúsculas ("autotrof" encontra "autotróficos"). */
object Search {
    private val marks = Regex("\\p{Mn}+")

    fun normalize(s: String): String =
        marks.replace(java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD), "").lowercase()

    suspend fun run(dao: ConversationDao, messages: MessageDao, query: String): List<ConversationRow> {
        val q = normalize(query.trim())
        if (q.isEmpty()) return emptyList()
        val hits = HashSet<String>()
        messages.allText().forEach { if (it.conversationId !in hits && normalize(it.content).contains(q)) hits += it.conversationId }
        return dao.allRows().filter { it.id in hits || normalize(it.title).contains(q) }.take(100)
    }
}

@Dao
interface ConversationDao {
    @Query(
        """SELECT id, title, updatedAt, pinned, archived, folder, model, preview, messageCount FROM conversations
           WHERE archived = :archived ORDER BY pinned DESC, updatedAt DESC""",
    )
    fun observeList(archived: Boolean): Flow<List<ConversationRow>>

    @Query(
        """SELECT id, title, updatedAt, pinned, archived, folder, model, preview, messageCount FROM conversations
           WHERE archived = 0 ORDER BY updatedAt DESC LIMIT :limit""",
    )
    fun observeRecent(limit: Int): Flow<List<ConversationRow>>

    @Query("SELECT id, title, updatedAt, pinned, archived, folder, model, preview, messageCount FROM conversations ORDER BY updatedAt DESC")
    suspend fun allRows(): List<ConversationRow>

    @Query("SELECT DISTINCT folder FROM conversations WHERE folder IS NOT NULL AND archived = 0 ORDER BY folder")
    fun observeFolders(): Flow<List<String>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observe(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(c: ConversationEntity)

    @Upsert
    suspend fun upsert(c: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, titleAuto = :auto, updatedAt = updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, auto: Boolean)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE conversations SET archived = :archived, pinned = 0 WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    @Query("UPDATE conversations SET folder = :folder WHERE id = :id")
    suspend fun setFolder(id: String, folder: String?)

    @Query("UPDATE conversations SET activeLeafId = :leaf, updatedAt = :at, preview = :preview, messageCount = :count WHERE id = :id")
    suspend fun setLeaf(id: String, leaf: String?, at: Long, preview: String?, count: Int)

    @Query("UPDATE conversations SET activeLeafId = :leaf WHERE id = :id")
    suspend fun setLeafOnly(id: String, leaf: String?)

    @Query("UPDATE conversations SET model = :model WHERE id = :id")
    suspend fun setModel(id: String, model: String?)

    @Query("UPDATE conversations SET presetId = :presetId, systemPrompt = :systemPrompt, paramsJson = :paramsJson WHERE id = :id")
    suspend fun setConfig(id: String, presetId: String?, systemPrompt: String?, paramsJson: String?)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM conversations WHERE archived = 0")
    fun observeCount(): Flow<Int>
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY createdAt")
    fun observeAll(cid: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY createdAt")
    suspend fun all(cid: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    /** Só o necessário para a busca (sem carregar raciocínio, anexos etc.). */
    @Query("SELECT conversationId, content FROM messages")
    suspend fun allText(): List<MessageText>

    @Query("SELECT * FROM messages WHERE status IN ('streaming','waiting')")
    suspend fun pending(): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(m: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(m: List<MessageEntity>)

    @Query("UPDATE messages SET content = :content, reasoning = :reasoning, lastSeq = :seq, status = :status WHERE id = :id")
    suspend fun updateStream(id: String, content: String, reasoning: String?, seq: Int, status: String)

    @Query(
        """UPDATE messages SET content = :content, reasoning = :reasoning, lastSeq = :seq, status = :status,
           statsJson = :stats, error = :error, finishReason = :finish, reasoningMs = :reasoningMs WHERE id = :id""",
    )
    suspend fun finish(
        id: String, content: String, reasoning: String?, seq: Int, status: String,
        stats: String?, error: String?, finish: String?, reasoningMs: Long?,
    )

    @Query("SELECT * FROM messages WHERE jobId = :job LIMIT 1")
    suspend fun byJob(job: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE jobId IS NOT NULL AND createdAt > :since ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentJobs(since: Long, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE jobId IS NOT NULL AND createdAt > :since ORDER BY createdAt DESC LIMIT 100")
    fun observeRecentJobs(since: Long): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET modelName = :name WHERE id = :id")
    suspend fun setModelName(id: String, name: String?)

    @Query("UPDATE messages SET model = COALESCE(:model, model), modelName = COALESCE(:name, modelName) WHERE id = :id")
    suspend fun setModelInfo(id: String, model: String?, name: String?)

    @Query("UPDATE messages SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: String, status: String, error: String?)

    @Query("UPDATE messages SET jobId = :job, status = :status, lastSeq = 0, model = :model WHERE id = :id")
    suspend fun setJob(id: String, job: String, status: String, model: String?)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("DELETE FROM messages WHERE conversationId = :cid")
    suspend fun deleteAll(cid: String)

    @Query("UPDATE messages SET status = 'interrupted' WHERE status IN ('streaming','waiting') AND jobId IS NULL")
    suspend fun markOrphans()
}

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY sort, name")
    fun observeAll(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM presets WHERE id = :id")
    suspend fun get(id: String): PresetEntity?

    @Query("SELECT COUNT(*) FROM presets")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(p: PresetEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(p: List<PresetEntity>)

    @Query("DELETE FROM presets WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface PromptDao {
    @Query("SELECT * FROM prompts ORDER BY favorite DESC, COALESCE(lastUsedAt, updatedAt) DESC")
    fun observeAll(): Flow<List<PromptEntity>>

    @Query("SELECT * FROM prompts WHERE id = :id")
    suspend fun get(id: String): PromptEntity?

    @Query("SELECT * FROM prompts WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): PromptEntity?

    @Query("SELECT * FROM prompts WHERE isDefault = 1 LIMIT 1")
    fun observeDefault(): Flow<PromptEntity?>

    @Query("SELECT COUNT(*) FROM prompts")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(p: PromptEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(p: List<PromptEntity>)

    @Query("UPDATE prompts SET isDefault = 0")
    suspend fun clearDefault()

    @Transaction
    suspend fun makeDefault(id: String?) {
        clearDefault()
        if (id != null) setDefault(id)
    }

    @Query("UPDATE prompts SET isDefault = 1 WHERE id = :id")
    suspend fun setDefault(id: String)

    @Query("UPDATE prompts SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: String, fav: Boolean)

    @Query("UPDATE prompts SET lastUsedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("DELETE FROM prompts WHERE id = :id")
    suspend fun delete(id: String)
}
