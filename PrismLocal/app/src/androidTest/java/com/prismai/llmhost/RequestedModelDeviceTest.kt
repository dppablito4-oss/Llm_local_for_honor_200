package com.prismai.llmhost

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.os.Debug
import android.os.IBinder
import android.os.PowerManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prismai.llmhost.bridge.NativeLlmBridge
import com.prismai.llmhost.bridge.ChatMessage
import com.prismai.llmhost.diffusion.DiffusionManager
import com.prismai.llmhost.diffusion.ImageGenerationOutput
import com.prismai.llmhost.service.InferenceService
import com.prismai.llmhost.storage.ModelStorageManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Uses installed weights, preserves existing chats and keeps neutral results for review. */
@RunWith(AndroidJUnit4::class)
class RequestedModelDeviceTest {
    @Test fun installedModelLoadsGeneratesAndReleases() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selections = context.getSharedPreferences("llm_host_prefs", Context.MODE_PRIVATE)
        val initialTextSelection = selections.getString("active_model", null)
        val originalTextSelection = InstrumentationRegistry.getArguments().getString("restore_text_model") ?: initialTextSelection
        val originalImageSelection = selections.getString("active_diffusion_model", null)
        val id = requireNotNull(InstrumentationRegistry.getArguments().getString("model_id"))
        val storage = ModelStorageManager(context)
        assertTrue("Model absent from application inventory", id in storage.listInstalledModels())
        val model = requireNotNull(storage.activeModelInfo(id))
        val report = JSONObject().put("id", id).put("kind", model.kind.name)
            .put("bytes", model.bytes).put("sha256", model.sha256).put("detected", true)
            .put("loaded", false).put("inference_passed", false)
        val reports = File(context.filesDir, "model-verification").apply { mkdirs() }
        val reportFile = File(reports, "$id.json")
        reportFile.writeText(report.toString(2))
        val started = SystemClock.elapsedRealtime()
        val body: String
        if (model.kind == ModelStorageManager.ModelKind.TEXT) {
            val bridge = NativeLlmBridge.create(false)
            try {
                val settings = GenerationSettings(maxTokens = 192, threadCount = 4, contextLength = 2048,
                    batchSize = 128, temperature = 0.3f, topK = 40, gpuLayers = 0)
                assertTrue("Text model load failed", bridge.loadModel(model.file.absolutePath, settings))
                report.put("loaded", true)
                report.put("load_ms", SystemClock.elapsedRealtime() - started).put("backend", bridge.getBackendName())
                    .put("process_pss_kb_loaded", Debug.getPss())
                val chunks = withTimeout(180_000) {
                    bridge.generateChat(listOf(ChatMessage("system", "Eres un asistente útil. Responde en español."),
                        ChatMessage("user", "Explica en cuatro frases breves por qué la Luna tiene cráteres.")), settings).toList()
                }
                val text = chunks.joinToString("") { it.text }
                assertTrue("Expected several sentences", text.length > 60 && Regex("[.!?]").findAll(text).count() >= 2)
                assertTrue(chunks.last().terminalReason in setOf("EOF", "MAX_TOKENS"))
                report.put("text", text).put("tokens", chunks.sumOf { it.tokenCount })
                    .put("process_pss_kb_after_inference", Debug.getPss())
                    .put("tokens_per_second", chunks.last().tokensPerSec)
                    .put("terminal", chunks.last().terminalReason)
                body = "## $id\n\n$text"
                bridge.unloadModel()
                assertTrue("Reload after unload failed", bridge.loadModel(model.file.absolutePath, settings))
                bridge.unloadModel()
            } finally { bridge.destroySafely() }
        } else {
            assertEquals(ModelStorageManager.ModelKind.IMAGE, model.kind)
            val manager = DiffusionManager(context)
            val args = InstrumentationRegistry.getArguments()
            val onGpu = args.getString("image_gpu") == "true"
            val steps = args.getString("image_steps")?.toInt() ?: model.diffusionProfile.steps
            val timeoutMs = (args.getString("image_timeout_seconds")?.toLong() ?: 900) * 1000
            val stopReason = AtomicReference<String?>(null)
            val thermal = context.getSystemService(PowerManager::class.java)
            fun tooHot() = Build.VERSION.SDK_INT >= 29 && thermal.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
            assertFalse("Phone must cool before image testing", tooHot())
            val monitor = launch(Dispatchers.Default) {
                while (true) {
                    delay(500)
                    if (tooHot()) stopReason.compareAndSet(null, "Android thermal status SEVERE")
                    if (SystemClock.elapsedRealtime() - started >= timeoutMs) stopReason.compareAndSet(null, "Image test time limit: ${timeoutMs / 1000}s")
                    if (stopReason.get() != null) manager.cancel()
                }
            }
            try {
                assertTrue("Checkpoint load failed", manager.loadModel(model.file.absolutePath, onGpu = onGpu, profile = model.diffusionProfile))
                report.put("loaded", true)
                report.put("load_ms", SystemClock.elapsedRealtime() - started)
                    .put("process_pss_kb_loaded", Debug.getPss())
                    .put("sampler", model.diffusionProfile.sampler).put("steps", steps)
                    .put("cfg", model.diffusionProfile.cfg).put("backend", if (onGpu) "GPUOpenCL" else "CPU")
                    .put("backend_selection", "explicit context and parameter backend; no silent fallback")
                reportFile.writeText(report.toString(2))
                val existingName = InstrumentationRegistry.getArguments().getString("existing_png")
                val output = if (existingName != null) {
                    require(existingName.matches(Regex("[A-Za-z0-9_.-]+\\.png")))
                    val file = File(manager.imagesDir, existingName)
                    require(file.isFile && file.canonicalFile.parentFile == manager.imagesDir.canonicalFile)
                    report.put("recovered_existing_png", true)
                    ImageGenerationOutput(file,
                        InstrumentationRegistry.getArguments().getString("existing_seconds")?.toFloatOrNull() ?: -1f,
                        model.diffusionProfile.steps, 42)
                } else withTimeout(3_600_000) {
                    manager.generateImage("Un gato astronauta en la Luna, iluminación cinematográfica, muy detallado.",
                        steps = steps, cfgScale = model.diffusionProfile.cfg, seed = 42).getOrThrow()
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(output.file.absolutePath, bounds)
                assertEquals(512, bounds.outWidth); assertEquals(512, bounds.outHeight)
                report.put("png_path", output.file.absolutePath).put("png_bytes", output.file.length())
                    .put("process_pss_kb_after_inference", Debug.getPss())
                    .put("generation_seconds", output.generationTimeSec.takeIf { it >= 0 })
                val interrupted = async(Dispatchers.Default) {
                    manager.generateImage("A neutral geometric cube", steps = 8, cfgScale = model.diffusionProfile.cfg)
                }
                delay(1500)
                val stopStarted = SystemClock.elapsedRealtime()
                manager.cancel()
                assertTrue("Cancellation must return a failure result", withTimeout(15_000) { interrupted.await() }.isFailure)
                report.put("cancel_ms", SystemClock.elapsedRealtime() - stopStarted)
                body = "## $id\n\n![Un gato astronauta en la Luna](file://${output.file.absolutePath})\n\n${output.generationTimeSec} s • ${output.steps} pasos • ${model.diffusionProfile.sampler}"
            } catch (error: Throwable) {
                report.put("error", stopReason.get() ?: error.toString())
                    .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                reportFile.writeText(report.toString(2))
                throw error
            } finally { monitor.cancel(); manager.shutdown() }
        }
        report.put("elapsed_ms", SystemClock.elapsedRealtime()-started)
            .put("native_heap_bytes_after_unload", Debug.getNativeHeapAllocatedSize())
            .put("process_pss_kb_after_unload", Debug.getPss()).put("inference_passed", true)
        File(reports, "$id.json").writeText(report.toString(2))
        // Publish only the newly generated neutral output in a dedicated chat.
        val connected = CompletableDeferred<InferenceService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.complete((binder as InferenceService.LocalBinder).getService())
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, InferenceService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            val service = withTimeout(60_000) { connected.await() }
            withTimeout(120_000) {
                while (service.currentChatId.value == null || service.runtimeStatus.value == RuntimeStatus.LOADING_MODEL ||
                    (initialTextSelection != null && service.currentModel.value != initialTextSelection)) delay(50)
            }
            val previous = service.currentChatId.value
            val prefs = context.getSharedPreferences("model_verification_prefs", Context.MODE_PRIVATE)
            val old = prefs.getString("chat_id", null)
            val chat = if (old != null && service.switchChat(old)) old else service.createChat().also {
                service.renameChat(it, "Pruebas de modelos locales")
                prefs.edit().putString("chat_id", it).commit()
            }
            assertTrue("Service selection failed", service.switchModel(id))
            report.put("service_selection_passed", true)
            if (model.kind == ModelStorageManager.ModelKind.TEXT) assertEquals(id, service.currentModel.value)
            val append = InferenceService::class.java.getDeclaredMethod("appendTranscriptMessage", TranscriptRole::class.java, String::class.java).apply { isAccessible = true }
            append.invoke(service, TranscriptRole.ASSISTANT, body)
            delay(1500)
            if (originalTextSelection != null && service.currentModel.value != originalTextSelection) assertTrue(service.switchModel(originalTextSelection))
            if (previous != null && previous != chat) service.switchChat(previous)
            report.put("review_chat_id", chat)
            File(reports, "$id.json").writeText(report.toString(2))
        } finally {
            selections.edit().apply {
                if (originalImageSelection == null) remove("active_diffusion_model") else putString("active_diffusion_model", originalImageSelection)
            }.commit()
            context.unbindService(connection)
        }
        println("PASS: installed model $id, ${model.kind}, inference and resource release")
    }
}
