package com.prismai.llmhost.persistence

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prismai.llmhost.ChatSession
import com.prismai.llmhost.TranscriptMessage
import com.prismai.llmhost.TranscriptRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomConversationMirrorTest {
    @Test
    fun rollingSummaryPersistsWithoutDeletingCoveredMessages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, PrismDatabase::class.java).build()
        try {
            val repository = RoomConversationRepository(database)
            val original = session("summary-chat", "Resumen", messageCount = 12)
            val messages = (1L..12L).map { id ->
                message(
                    id,
                    if (id % 2L == 0L) TranscriptRole.ASSISTANT else TranscriptRole.USER,
                    "mensaje-$id",
                )
            }
            repository.syncChat(original, messages)

            assertEquals(true, repository.updateSummary(original.id, "Hechos acumulados", 6L, 500L))

            val restored = repository.loadSnapshot()
            val restoredChat = restored.sessions.single()
            assertEquals("Hechos acumulados", restoredChat.summary)
            assertEquals(6L, restoredChat.summaryUntilMessageId)
            assertEquals(messages, restored.messagesByChat.getValue(original.id))

            repository.syncChat(
                restoredChat.copy(summary = null, summaryUntilMessageId = null, messageCount = 0),
                emptyList(),
            )
            assertNull(database.conversationDao().chatById(original.id)?.summary)
            assertNull(database.conversationDao().chatById(original.id)?.summaryUntilMessageId)
        } finally {
            database.close()
        }
    }

    @Test
    fun mirrorsCreateEditClearAndDeleteWithoutTouchingOtherChat() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, PrismDatabase::class.java).build()
        try {
            val mirror = RoomConversationRepository(database)
            val dao = database.conversationDao()
            val first = session("first", "Primero", messageCount = 2)
            val second = session("second", "Segundo", messageCount = 1)

            mirror.syncSessions(listOf(first, second))
            mirror.syncChat(
                first,
                listOf(
                    message(1, TranscriptRole.USER, "Pregunta"),
                    message(2, TranscriptRole.ASSISTANT, "Respuesta inicial"),
                ),
            )
            mirror.syncChat(
                second,
                listOf(message(1, TranscriptRole.USER, "No debe cambiar")),
            )

            val renamedAndEdited = first.copy(
                title = "Primero renombrado",
                updatedAt = 300L,
                messageCount = 2,
            )
            mirror.syncChat(
                renamedAndEdited,
                listOf(
                    message(1, TranscriptRole.USER, "Pregunta"),
                    message(2, TranscriptRole.ASSISTANT, "Respuesta final"),
                ),
            )
            assertEquals("Primero renombrado", dao.chatById("first")?.title)
            assertEquals("Respuesta final", dao.messagesForChat("first")[1].text)

            val cleared = renamedAndEdited.copy(updatedAt = 400L, messageCount = 0)
            mirror.syncChat(cleared, emptyList())
            assertEquals(emptyList<MessageEntity>(), dao.messagesForChat("first"))
            assertEquals("No debe cambiar", dao.messagesForChat("second").single().text)

            mirror.syncSessions(listOf(second))
            assertNull(dao.chatById("first"))
            assertEquals(listOf("second"), dao.allChatIds())
            assertEquals("No debe cambiar", dao.messagesForChat("second").single().text)

            val restored = mirror.loadSnapshot()
            assertEquals(listOf(second), restored.sessions)
            assertEquals(
                listOf(message(1, TranscriptRole.USER, "No debe cambiar")),
                restored.messagesByChat.getValue("second"),
            )
        } finally {
            database.close()
        }
    }

    private fun session(
        id: String,
        title: String,
        messageCount: Int,
    ) = ChatSession(
        id = id,
        title = title,
        createdAt = 100L,
        updatedAt = 200L,
        modelId = "modelo-prueba",
        messageCount = messageCount,
    )

    private fun message(
        id: Long,
        role: TranscriptRole,
        text: String,
    ) = TranscriptMessage(id = id, role = role, text = text)
}
