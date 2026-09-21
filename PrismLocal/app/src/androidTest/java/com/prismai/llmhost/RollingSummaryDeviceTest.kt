package com.prismai.llmhost

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prismai.llmhost.persistence.PrismDatabase
import com.prismai.llmhost.persistence.RoomConversationRepository
import com.prismai.llmhost.service.InferenceService
import com.prismai.llmhost.storage.ModelStorageManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RollingSummaryDeviceTest {
    @Test
    fun activeLocalModelCreatesAndPersistsRollingSummary() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = PrismDatabase.getInstance(context)
        val repository = RoomConversationRepository(database)
        val original = repository.loadSnapshot()
        val now = System.currentTimeMillis()
        val chatId = "rolling_summary_device_$now"
        val messages = (1L..20L).map { id ->
            TranscriptMessage(
                id = id,
                role = if (id % 2L == 0L) TranscriptRole.ASSISTANT else TranscriptRole.USER,
                text = if (id == 1L) {
                    "Mi dato durable es ORQUIDEA AZUL. " + "contexto largo ".repeat(18)
                } else {
                    "Turno de prueba $id con información coherente. " + "detalle persistente ".repeat(18)
                },
            )
        }
        val seeded = ChatSession(
            id = chatId,
            title = "Prueba temporal de resumen",
            createdAt = now,
            updatedAt = now,
            modelId = null,
            messageCount = messages.size,
        )
        repository.syncSessions(original.sessions + seeded)
        repository.syncChat(seeded, messages)
        context.getSharedPreferences("llm_host_prefs", Context.MODE_PRIVATE)
            .edit().putString("active_chat", chatId).commit()

        val installed = ModelStorageManager(context).listInstalledModelInfos()
        val model = installed.firstOrNull { "qwen3" in it.id.lowercase() }
            ?: installed.firstOrNull()
        assertNotNull("No installed GGUF model available for real summary test", model)

        val bound = bindService(context)
        try {
            assertTrue(bound.service.switchModel(model!!.id))
            bound.service.updateGenerationSettings(
                bound.service.generationSettings.value.copy(
                    contextLength = 2048,
                    maxTokens = 128,
                    reasoningMode = ReasoningMode.NORMAL,
                    agentEnabled = false,
                ),
            )
            bound.service.generateSafelyAndAwait("¿Qué dato durable mencioné al inicio?")

            val persisted = withTimeout(150_000L) {
                var found: com.prismai.llmhost.persistence.ChatEntity? = null
                while (found?.summary.isNullOrBlank()) {
                    delay(250L)
                    found = database.conversationDao().chatById(chatId)
                }
                found!!
            }
            assertTrue(persisted.summary!!.isNotBlank())
            assertTrue((persisted.summaryUntilMessageId ?: 0L) > 0L)
            assertEquals(22, database.conversationDao().messagesForChat(chatId).size)
        } finally {
            bound.service.cancelGenerationAndJoin("rolling summary device test cleanup")
            bound.service.deleteChat(chatId)
            context.unbindService(bound.connection)
        }
    }

    private suspend fun bindService(context: Context): BoundService {
        val deferred = CompletableDeferred<InferenceService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                deferred.complete((binder as InferenceService.LocalBinder).getService())
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val intent = Intent(context, InferenceService::class.java)
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        return BoundService(withTimeout(15_000L) { deferred.await() }, connection)
    }

    private data class BoundService(
        val service: InferenceService,
        val connection: ServiceConnection,
    )
}
