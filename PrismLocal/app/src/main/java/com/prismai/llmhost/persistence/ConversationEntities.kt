package com.prismai.llmhost.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "chats", primaryKeys = ["id"])
data class ChatEntity(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val modelId: String?,
    val messageCount: Int,
    val summary: String? = null,
    val summaryUntilMessageId: Long? = null,
    val systemPrompt: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
)

@Entity(
    tableName = "messages",
    primaryKeys = ["chatId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["chatId"])],
)
data class MessageEntity(
    val chatId: String,
    val id: Long,
    val role: String,
    val text: String,
    val summary: String? = null,
    val createdAt: Long? = null,
    val modelId: String? = null,
    val status: String = STATUS_COMPLETE,
    val tokenCount: Int? = null,
    val parentMessageId: Long? = null,
) {
    companion object {
        const val STATUS_COMPLETE = "COMPLETE"
    }
}

@Entity(tableName = "migration_state", primaryKeys = ["key"])
data class MigrationStateEntity(
    val key: String,
    val completedAt: Long,
    val sourceChatCount: Int,
    val sourceMessageCount: Int,
)

@Entity(
    tableName = "memories",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["scope", "sourceChatId"]),
        Index(value = ["lastAccessedAt"]),
    ],
)
data class MemoryEntity(
    val id: String,
    val fact: String,
    val category: String,
    val confidence: Float,
    val sourceChatId: String? = null,
    val scope: String,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val accessCount: Int = 0,
    val decayed: Boolean = false,
)
