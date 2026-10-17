package com.prismai.llmhost

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prismai.llmhost.agent.AgentToolRouter
import com.prismai.llmhost.diffusion.DiffusionManager
import com.prismai.llmhost.service.InferenceService
import com.prismai.llmhost.storage.ModelStorageManager
import com.prismai.llmhost.tools.AgentToolCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Only the newly created chat/model/images belong to this test; originals are restored. */
@RunWith(AndroidJUnit4::class)
class ImageOperationServiceTest {
    @Test
    fun stopReplaceRecoverAndAgentImageShareTheServiceLifecycle() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("llm_host_prefs", Context.MODE_PRIVATE)
        val originalModel = prefs.getString("active_model", null)
        val connected = CompletableDeferred<InferenceService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.complete((binder as InferenceService.LocalBinder).getService())
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, InferenceService::class.java), connection, Context.BIND_AUTO_CREATE))
        val service = withTimeout(60_000) { connected.await() }
        val imagesDir = File(context.filesDir, "generated_images")
        val testImages = mutableListOf<File>()
        val storage = ModelStorageManager(context)
        var testModel: String? = null
        var testChat: String? = null
        var originalChat: String? = null
        val oldSettings = service.generationSettings.value
        try {
            suspend fun waitUntil(timeout: Long = 15_000, condition: () -> Boolean) {
                withTimeout(timeout) { while (!condition()) delay(20) }
            }
            waitUntil(60_000) {
                service.currentChatId.value != null && service.runtimeStatus.value != RuntimeStatus.LOADING_MODEL &&
                    (originalModel == null || service.currentModel.value == originalModel)
            }
            originalChat = service.currentChatId.value
            assertTrue("real SDXS must be installed", storage.listInstalledModels().any { it.contains("sdxs", true) })
            val imported = instrumentation.context.assets.open("smoke-model/tinyllama-v0.q8_0.gguf").use {
                storage.importModelFromStream("stability-smoke-${UUID.randomUUID()}.gguf", 6750304, it)
            }
            assertTrue(imported is ModelStorageManager.ImportResult.Success)
            testModel = (imported as ModelStorageManager.ImportResult.Success).model.id
            assertTrue(service.switchModel(testModel!!))
            service.updateGenerationSettings(oldSettings.copy(maxTokens = 32, topK = 1, agentEnabled = false))
            waitUntil { service.runtimeStatus.value != RuntimeStatus.LOADING_MODEL }
            testChat = service.createChat()
            assertTrue(testChat!!.isNotBlank() && testChat != originalChat)

            val text = withTimeout(30_000) { service.generateSafelyAndAwait("Once upon a time") }
            assertTrue(text.isNotBlank())
            assertFalse(service.isGenerating.value)
            assertFalse(service.debugIsGenerationForegroundActive())

            val mutexField = InferenceService::class.java.getDeclaredField("operationMutex").apply { isAccessible = true }
            val summaryField = InferenceService::class.java.getDeclaredField("rollingSummaryJob").apply { isAccessible = true }
            val summaryEntered = CompletableDeferred<Unit>()
            val summary = launch(Dispatchers.Default) {
                (mutexField.get(service) as Mutex).withLock {
                    summaryEntered.complete(Unit)
                    awaitCancellation()
                }
            }
            summaryEntered.await()
            summaryField.set(service, summary)
            // Same entry point used by the composer Stop button.
            val cancelled = async { runCatching { service.generateSafelyAndAwait("/image --steps 8 a red cube") } }
            waitUntil { service.isGenerating.value && service.transcript.value.lastOrNull()?.text?.contains("Generando imagen") == true }
            assertTrue("image must retire summary before acquiring the shared operation", summary.isCompleted)
            summary.cancelAndJoin()
            delay(300)
            val stopAt = android.os.SystemClock.elapsedRealtime()
            service.cancelGeneration()
            waitUntil(15_000) { !service.isGenerating.value }
            withTimeout(2000) { cancelled.await() }
            assertFalse(service.debugIsGenerationForegroundActive())
            assertTrue(service.transcript.value.any { it.text.contains("imagen cancelada") })
            println("PASS: image Stop and notification cleanup, ms=${android.os.SystemClock.elapsedRealtime() - stopAt}")

            val replaced = async { runCatching { service.generateSafelyAndAwait("/image --steps 8 a blue cube") } }
            waitUntil { service.isGenerating.value }
            delay(300)
            val completed = withTimeout(180_000) {
                service.generateSafelyAndAwait("/IMAGE --steps 1 --seed 42 a red cube on a white background")
            }
            replaced.await()
            assertTrue("replacement image failed: $completed", completed.contains("file://"))
            val path = Regex("file://([^)]*)").find(completed)?.groupValues?.get(1)
                ?: error("Image result missing path")
            testImages.add(File(path))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            assertEquals(512, bounds.outWidth)
            assertEquals(512, bounds.outHeight)
            assertFalse(service.debugIsGenerationForegroundActive())
            println("PASS: image replacement, uppercase command and real 512x512 PNG")

            val imageBeforeText = async { runCatching { service.generateSafelyAndAwait("/sd --steps 8 a green cube") } }
            waitUntil { service.isGenerating.value }
            delay(300)
            val recovered = withTimeout(30_000) { service.generateSafelyAndAwait("Once upon a time") }
            imageBeforeText.await()
            assertTrue(recovered.isNotBlank())
            assertFalse(service.isGenerating.value)
            println("PASS: image replaced by real text, generation recovered")

            val routerField = InferenceService::class.java.getDeclaredField("agentToolRouter").apply { isAccessible = true }
            val router = routerField.get(service) as AgentToolRouter
            router.handleToolCall(AgentToolCall("generate_image", JSONObject().put("prompt", "a yellow cube").put("steps", 8)),
                "test image tool", 0)
            waitUntil { service.isGenerating.value }
            // Let SDXS finish CLIP and enter UNet; Stop must also interrupt a
            // denoising graph, not just prompt encoding. Verify stage in logcat.
            delay(1500)
            service.cancelGeneration()
            waitUntil(15_000) { !service.isGenerating.value }
            delay(400)
            assertFalse("cancelled tool must not start a text follow-up", service.isGenerating.value)
            assertFalse(service.debugIsGenerationForegroundActive())
            assertTrue("tool cancellation must be visible", service.transcript.value.any { it.role == TranscriptRole.TOOL && it.text.contains("Tool cancelled") })
            println("PASS: agent image uses Stop without follow-up after cancellation")
        } finally {
            service.cancelGenerationAndJoin("image operation test cleanup")
            val managerField = InferenceService::class.java.getDeclaredField("diffusionManager").apply { isAccessible = true }
            (managerField.get(service) as DiffusionManager).unload()
            service.updateGenerationSettings(oldSettings)
            if (originalModel != null) assertTrue(service.switchModel(originalModel))
            if (originalChat != null) service.switchChat(originalChat!!)
            testChat?.let { service.deleteChat(it) }
            testChat?.let { id ->
                withTimeout(5000) {
                    val dao = com.prismai.llmhost.persistence.PrismDatabase.getInstance(context).conversationDao()
                    while (dao.chatById(id) != null) delay(20)
                }
            }
            testModel?.let { storage.deleteModel(it) }
            testImages.filter { it.canonicalFile.parentFile == imagesDir.canonicalFile }.forEach { it.delete() }
            context.unbindService(connection)
        }
    }
}
