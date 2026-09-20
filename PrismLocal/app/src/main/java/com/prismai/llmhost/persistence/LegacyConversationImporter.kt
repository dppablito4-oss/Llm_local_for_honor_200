package com.prismai.llmhost.persistence

import androidx.room.withTransaction
import com.prismai.llmhost.AgentSanitizer
import com.prismai.llmhost.ChatSession
import com.prismai.llmhost.TranscriptRole
import org.json.JSONArray
import java.io.File

sealed interface LegacyConversationImportResult {
    data object NoChats : LegacyConversationImportResult
    data class AlreadyCompleted(
        val chatCount: Int,
        val messageCount: Int,
    ) : LegacyConversationImportResult

    data class Imported(
        val chatCount: Int,
        val messageCount: Int,
    ) : LegacyConversationImportResult
}

/**
 * One-way, non-destructive import of the current JSON conversation store.
 *
 * JSON remains the application's active backend during this phase. The import
 * runs in one Room transaction, verifies every source row, and only then writes
 * its completion marker. Source files are never modified or deleted.
 */
class LegacyConversationImporter(
    private val database: PrismDatabase,
    private val legacyFilesRoot: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MIGRATION_KEY = "legacy_conversations_json_v1"
        private const val CHAT_DIRECTORY = "chats"
    }

    suspend fun importIfNeeded(sessions: List<ChatSession>): LegacyConversationImportResult {
        val dao = database.conversationDao()
        if (sessions.isEmpty()) {
            return dao.migrationState(MIGRATION_KEY)?.let { state ->
                LegacyConversationImportResult.AlreadyCompleted(
                    chatCount = state.sourceChatCount,
                    messageCount = state.sourceMessageCount,
                )
            } ?: LegacyConversationImportResult.NoChats
        }

        val snapshot = sessions.map { session ->
            LegacyChatSnapshot(
                chat = session.toChatEntity(),
                messages = readMessages(session),
            )
        }
        val expectedMessageCount = snapshot.sumOf { it.messages.size }
        val previousState = dao.migrationState(MIGRATION_KEY)
        if (
            previousState?.sourceChatCount == snapshot.size &&
            previousState.sourceMessageCount == expectedMessageCount &&
            snapshotMatches(dao, snapshot)
        ) {
            return LegacyConversationImportResult.AlreadyCompleted(
                chatCount = snapshot.size,
                messageCount = expectedMessageCount,
            )
        }

        database.withTransaction {
            dao.insertChatsIfAbsent(snapshot.map { it.chat })
            dao.updateChats(snapshot.map { it.chat })
            snapshot.forEach { chat ->
                // JSON is authoritative during this phase. Replacing one chat's
                // Room rows inside the same transaction also mirrors clears and
                // edited/streamed assistant messages without touching the source.
                dao.deleteMessagesForChat(chat.chat.id)
                if (chat.messages.isNotEmpty()) {
                    dao.replaceMessages(chat.messages)
                }
            }

            verifySnapshot(dao, snapshot)
            dao.upsertMigrationState(
                MigrationStateEntity(
                    key = MIGRATION_KEY,
                    completedAt = now(),
                    sourceChatCount = snapshot.size,
                    sourceMessageCount = expectedMessageCount,
                )
            )
        }

        val completed = requireNotNull(dao.migrationState(MIGRATION_KEY)) {
            "Legacy conversation import ended without a completion marker"
        }
        return LegacyConversationImportResult.Imported(
            chatCount = completed.sourceChatCount,
            messageCount = completed.sourceMessageCount,
        )
    }

    private fun readMessages(session: ChatSession): List<MessageEntity> {
        val chatId = AgentSanitizer.sanitizeChatId(session.id)
        val file = File(File(legacyFilesRoot, CHAT_DIRECTORY), "$chatId.json")
        if (!file.isFile) {
            check(session.messageCount == 0) {
                "Missing transcript for chat=${session.id} expected=${session.messageCount} messages"
            }
            return emptyList()
        }

        val array = JSONArray(file.readText())
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val role = runCatching {
                    TranscriptRole.valueOf(item.getString("role"))
                }.getOrDefault(TranscriptRole.ASSISTANT)
                add(
                    com.prismai.llmhost.TranscriptMessage(
                        id = item.getLong("id"),
                        role = role,
                        text = item.optString("text", ""),
                        summary = item.optString("summary").takeIf { it.isNotBlank() },
                    ).toMessageEntity(session.id)
                )
            }
        }
    }

    private suspend fun verifySnapshot(
        dao: ConversationDao,
        snapshot: List<LegacyChatSnapshot>,
    ) {
        val expectedChats = snapshot.associateBy { it.chat.id }
        val storedChats = dao.chatsByIds(expectedChats.keys.toList()).associateBy { it.id }
        check(storedChats.size == expectedChats.size) {
            "Room import verification found a different chat count"
        }
        expectedChats.forEach { (chatId, source) ->
            check(storedChats[chatId] == source.chat) {
                "Room import verification failed for chat=$chatId"
            }
        }

        snapshot.forEach { expected ->
            val stored = dao.messagesForChat(expected.chat.id)
            check(stored == expected.messages) {
                "Room import verification failed for messages in chat=${expected.chat.id}"
            }
        }
    }

    private suspend fun snapshotMatches(
        dao: ConversationDao,
        snapshot: List<LegacyChatSnapshot>,
    ): Boolean = runCatching {
        verifySnapshot(dao, snapshot)
        true
    }.getOrDefault(false)

    private data class LegacyChatSnapshot(
        val chat: ChatEntity,
        val messages: List<MessageEntity>,
    )
}
