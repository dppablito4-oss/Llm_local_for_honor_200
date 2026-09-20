package com.prismai.llmhost.persistence

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prismai.llmhost.ChatSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LegacyConversationImporterTest {
    @Test
    fun importsJsonOnceAndPreservesRolesAndText() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "legacy-import-${System.nanoTime()}")
        val chatsDir = File(root, "chats").apply { mkdirs() }
        File(chatsDir, "chat_alpha.json").writeText(
            """
            [
              {"id":1,"role":"USER","text":"Hola"},
              {"id":2,"role":"ASSISTANT","text":"Que tal","summary":"saludo"}
            ]
            """.trimIndent()
        )
        val database = Room.inMemoryDatabaseBuilder(context, PrismDatabase::class.java).build()
        try {
            val importer = LegacyConversationImporter(database, root) { 1234L }
            val sessions = listOf(session(id = "chat_alpha", messageCount = 2))

            assertEquals(
                LegacyConversationImportResult.Imported(chatCount = 1, messageCount = 2),
                importer.importIfNeeded(sessions),
            )
            assertEquals(
                LegacyConversationImportResult.AlreadyCompleted(chatCount = 1, messageCount = 2),
                importer.importIfNeeded(sessions),
            )

            File(chatsDir, "chat_alpha.json").writeText(
                """
                [
                  {"id":1,"role":"USER","text":"Hola"},
                  {"id":2,"role":"ASSISTANT","text":"Respuesta corregida"},
                  {"id":3,"role":"USER","text":"Nueva pregunta"}
                ]
                """.trimIndent()
            )
            val updatedSessions = listOf(
                session(id = "chat_alpha", messageCount = 3, title = "Título actualizado")
            )
            assertEquals(
                LegacyConversationImportResult.Imported(chatCount = 1, messageCount = 3),
                importer.importIfNeeded(updatedSessions),
            )

            val dao = database.conversationDao()
            assertEquals(1, dao.chatCount())
            assertEquals(3, dao.messageCount())
            val messages = dao.messagesForChat("chat_alpha")
            assertEquals(listOf("USER", "ASSISTANT", "USER"), messages.map { it.role })
            assertEquals(listOf("Hola", "Respuesta corregida", "Nueva pregunta"), messages.map { it.text })
            assertNull(messages[1].summary)
            assertEquals(1234L, dao.migrationState(LegacyConversationImporter.MIGRATION_KEY)?.completedAt)
            assertEquals(3, dao.migrationState(LegacyConversationImporter.MIGRATION_KEY)?.sourceMessageCount)
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedSourceDoesNotMarkMigrationComplete() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "legacy-import-bad-${System.nanoTime()}")
        val chatsDir = File(root, "chats").apply { mkdirs() }
        File(chatsDir, "chat_broken.json").writeText("[{broken")
        val database = Room.inMemoryDatabaseBuilder(context, PrismDatabase::class.java).build()
        try {
            val importer = LegacyConversationImporter(database, root)
            val failure = runCatching {
                importer.importIfNeeded(listOf(session(id = "chat_broken", messageCount = 1)))
            }.exceptionOrNull()

            assertNotNull(failure)
            assertNull(database.conversationDao().migrationState(LegacyConversationImporter.MIGRATION_KEY))
            assertEquals(0, database.conversationDao().chatCount())
            assertEquals(0, database.conversationDao().messageCount())
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    private fun session(
        id: String,
        messageCount: Int,
        title: String = "Chat de prueba",
    ) = ChatSession(
        id = id,
        title = title,
        createdAt = 100L,
        updatedAt = 200L,
        modelId = "modelo-prueba",
        messageCount = messageCount,
    )
}
