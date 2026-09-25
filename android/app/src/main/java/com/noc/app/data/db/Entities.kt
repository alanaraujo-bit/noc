package com.noc.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Um PC pareado. Guarda só a chave pública (não é segredo) e rotas conhecidas. */
@Entity(tableName = "pcs")
data class PcEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** SPKI do PC em base64 — chave fixada no pareamento. */
    val spki: String,
    val relayUrl: String?,
    /** "ip:porta" separados por vírgula. */
    val lan: String,
    val pairedAt: Long,
    val lastConnectedAt: Long? = null,
    val lastRoute: String? = null,
) {
    val lanEndpoints get() = lan.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

@Entity(tableName = "conversations", indices = [Index("updatedAt"), Index("archived")])
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** Título ainda automático (gerado da primeira mensagem); vira false se o usuário renomear. */
    val titleAuto: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val folder: String? = null,
    val model: String? = null,
    val presetId: String? = null,
    /** Prompt de sistema desta conversa (null = usa o do preset/padrão). */
    val systemPrompt: String? = null,
    /** GenerationParams em JSON; null = do preset. */
    val paramsJson: String? = null,
    /** Última mensagem do ramo ativo (conversas são árvores por causa de editar/regenerar). */
    val activeLeafId: String? = null,
    val preview: String? = null,
    val messageCount: Int = 0,
)

@Entity(
    tableName = "messages",
    indices = [Index("conversationId"), Index("parentId"), Index("status")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val parentId: String?,
    val role: String, // user | assistant
    val content: String,
    val reasoning: String? = null,
    val createdAt: Long,
    val model: String? = null,
    /** done | streaming | waiting | error | cancelled | interrupted */
    val status: String = "done",
    val jobId: String? = null,
    val lastSeq: Int = 0,
    /** GenStats em JSON. */
    val statsJson: String? = null,
    val error: String? = null,
    /** List<Attachment> em JSON. */
    val attachmentsJson: String? = null,
    /** finish_reason: stop | length | cancelled | error */
    val finishReason: String? = null,
    val reasoningMs: Long? = null,
)

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String,
    val description: String,
    val model: String? = null,
    val systemPrompt: String? = null,
    val paramsJson: String,
    val builtin: Boolean = false,
    val sort: Int = 0,
    val updatedAt: Long,
)

@Entity(tableName = "prompts")
data class PromptEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val favorite: Boolean = false,
    val isDefault: Boolean = false,
    val category: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long? = null,
)
