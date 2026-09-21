package com.prismai.llmhost.persistence

import androidx.room.withTransaction
import com.prismai.llmhost.ChatSession
import com.prismai.llmhost.TranscriptMessage
import com.prismai.llmhost.TranscriptRole

/**
 * Secondary conversation store used while JSON remains the read backend.
 *
 * Every operation is transactional and replaces the complete state in its
 * scope. This makes retries safe after process death and mirrors edits,
 * clears, renames, and deletes without relying on fragile incremental deltas.
 */
data class ConversationSnapshot(
    val sessions: List<ChatSession>,
    val messagesByChat: Map<String, List<TranscriptMessage>>,
)

interface ConversationRepository {
    suspend fun loadSnapshot(): ConversationSnapshot

    suspend fun syncSessions(sessions: List<ChatSession>)

    suspend fun syncChat(session: ChatSession, messages: List<TranscriptMessage>)

    suspend fun updateSummary(chatId: String, summary: String, untilId: Long, updatedAt: Long): Boolean
}

class RoomConversationRepository(
    private val database: PrismDatabase,
) : ConversationRepository {
    override suspend fun loadSnapshot(): ConversationSnapshot = database.withTransaction {
        val dao = database.conversationDao()
        val chats = dao.allChats()
        ConversationSnapshot(
            sessions = chats.map(ChatEntity::toChatSession),
            messagesByChat = chats.associate { chat ->
                chat.id to dao.messagesForChat(chat.id).map(MessageEntity::toTranscriptMessage)
            },
        )
    }

    override suspend fun syncSessions(sessions: List<ChatSession>) {
        val expected = sessions.map(ChatSession::toChatEntity)
        val expectedIds = expected.mapTo(mutableSetOf()) { it.id }

        database.withTransaction {
            val dao = database.conversationDao()
            if (expected.isNotEmpty()) {
                dao.insertChatsIfAbsent(expected)
                dao.updateChats(expected)
            }
            dao.allChatIds()
                .filterNot(expectedIds::contains)
                .forEach { dao.deleteChat(it) }

            check(dao.allChatIds().toSet() == expectedIds) {
                "Room session mirror verification failed"
            }
            expected.forEach { chat ->
                check(dao.chatById(chat.id) == chat) {
                    "Room session mirror verification failed for chat=${chat.id}"
                }
            }
        }
    }

    override suspend fun syncChat(
        session: ChatSession,
        messages: List<TranscriptMessage>,
    ) {
        val expectedChat = session.toChatEntity()
        val expectedMessages = messages.map { it.toMessageEntity(session.id) }

        database.withTransaction {
            val dao = database.conversationDao()
            dao.insertChatsIfAbsent(listOf(expectedChat))
            dao.updateChats(listOf(expectedChat))
            dao.deleteMessagesForChat(session.id)
            if (expectedMessages.isNotEmpty()) {
                dao.replaceMessages(expectedMessages)
            }

            check(dao.chatById(session.id) == expectedChat) {
                "Room chat mirror verification failed for chat=${session.id}"
            }
            check(dao.messagesForChat(session.id) == expectedMessages) {
                "Room message mirror verification failed for chat=${session.id}"
            }
        }
    }

    override suspend fun updateSummary(
        chatId: String,
        summary: String,
        untilId: Long,
        updatedAt: Long,
    ): Boolean = database.withTransaction {
        val dao = database.conversationDao()
        val current = dao.chatById(chatId) ?: return@withTransaction false
        val currentUntil = current.summaryUntilMessageId
        if (currentUntil != null && currentUntil >= untilId) return@withTransaction false
        dao.updateSummary(chatId, summary, untilId, updatedAt) == 1
    }
}

internal fun ChatSession.toChatEntity(): ChatEntity = ChatEntity(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    modelId = modelId,
    messageCount = messageCount,
    summary = summary,
    summaryUntilMessageId = summaryUntilMessageId,
)

internal fun TranscriptMessage.toMessageEntity(chatId: String): MessageEntity = MessageEntity(
    chatId = chatId,
    id = id,
    role = role.name,
    text = text,
    summary = summary,
)

internal fun ChatEntity.toChatSession(): ChatSession = ChatSession(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    modelId = modelId,
    messageCount = messageCount,
    summary = summary,
    summaryUntilMessageId = summaryUntilMessageId,
)

internal fun MessageEntity.toTranscriptMessage(): TranscriptMessage = TranscriptMessage(
    id = id,
    role = runCatching { TranscriptRole.valueOf(role) }.getOrDefault(TranscriptRole.ASSISTANT),
    text = text,
    summary = summary,
)
