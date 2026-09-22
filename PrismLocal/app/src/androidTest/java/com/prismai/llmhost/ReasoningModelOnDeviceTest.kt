package com.prismai.llmhost

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.prismai.llmhost.bridge.ChatMessage
import com.prismai.llmhost.bridge.NativeLlmBridge
import com.prismai.llmhost.model.ModelBehaviorProfile
import com.prismai.llmhost.model.ModelBehaviorProfiles
import com.prismai.llmhost.model.ReasoningOutputParser
import com.prismai.llmhost.storage.ModelStorageManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReasoningModelOnDeviceTest {
    @Test
    fun downloadCatalogModel() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("downloadReasoningModel") == "true")
        val catalogId = requireNotNull(arguments.getString("catalogId"))
        val entry = requireNotNull(HuggingFaceModelCatalog.find(catalogId))
        val context = instrumentation.targetContext
        val storage = ModelStorageManager(context)
        storage.listInstalledModelInfos().firstOrNull { it.fileName == entry.fileName }?.let { installed ->
            println("REASONING_DOWNLOAD already_installed modelId=${installed.id} bytes=${installed.bytes}")
            return@runBlocking
        }

        val workId = enqueueHuggingFaceDownload(context, catalogId)
        val workManager = WorkManager.getInstance(context)
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(90)
        var lastStage = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            val info = workManager.getWorkInfoById(workId).get()
            if (info != null) {
                val stage = info.progress.getString(HuggingFaceDownloadWork.KEY_STAGE).orEmpty()
                val done = info.progress.getLong(HuggingFaceDownloadWork.KEY_BYTES_DONE, 0L)
                val total = info.progress.getLong(HuggingFaceDownloadWork.KEY_TOTAL_BYTES, 0L)
                val progress = "$stage:$done/$total"
                if (progress != lastStage) {
                    println("REASONING_DOWNLOAD catalogId=$catalogId state=${info.state} progress=$progress")
                    lastStage = progress
                }
                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        val modelId = info.outputData.getString(HuggingFaceDownloadWork.KEY_MODEL_ID)
                        println("REASONING_DOWNLOAD success catalogId=$catalogId modelId=$modelId")
                        assertNotNull(modelId)
                        return@runBlocking
                    }
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        val message = info.outputData.getString(HuggingFaceDownloadWork.KEY_MESSAGE)
                        throw AssertionError("Download $catalogId ended as ${info.state}: $message")
                    }
                    else -> Unit
                }
            }
            delay(2_000)
        }
        throw AssertionError("Timed out downloading $catalogId")
    }

    @Test
    fun runRealThinkingTurn() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runReasoningInference") == "true")
        val context = instrumentation.targetContext
        val storage = ModelStorageManager(context)
        val requestedCatalogId = arguments.getString("catalogId")
        val requestedModelId = arguments.getString("modelId")
        val catalogEntry = requestedCatalogId?.let(HuggingFaceModelCatalog::find)
        val model = when {
            requestedModelId != null -> {
                val resolved = storage.resolveActiveModel(requestedModelId)
                assertTrue("Unable to resolve $requestedModelId: $resolved", resolved is ModelStorageManager.ModelResolveResult.Success)
                (resolved as ModelStorageManager.ModelResolveResult.Success).model
            }
            catalogEntry != null -> storage.listInstalledModelInfos().firstOrNull { it.fileName == catalogEntry.fileName }
            else -> null
        }
        assertNotNull("Reasoning model is not installed", model)
        model ?: return@runBlocking

        val behavior = ModelBehaviorProfiles.resolve(model.fileName)
        assertTrue("${model.fileName} was not recognized as a reasoning model", behavior.isReasoningEnabled(ReasoningMode.THINKING))
        val settings = behavior.effectiveSettings(
            GenerationSettings(
                maxTokens = 768,
                threadCount = arguments.getString("threads")?.toIntOrNull() ?: 4,
                contextLength = ModelBehaviorProfile.RECOMMENDED_REASONING_CONTEXT,
                batchSize = 512,
                reasoningMode = ReasoningMode.THINKING,
                useVulkan = false,
                gpuLayers = 0,
            ),
            agentEnabled = false,
        )
        val userPrompt = behavior.prepareUserPrompt(
            "Resuelve con cuidado: Ana tiene 17 monedas, entrega 5 y luego duplica las restantes. ¿Cuántas tiene? Responde en español.",
            ReasoningMode.THINKING,
            agentEnabled = false,
        )
        val messages = buildList {
            if (!behavior.omitSystemPrompt) add(ChatMessage(ChatMessage.ROLE_SYSTEM, "Eres un asistente local preciso."))
            add(ChatMessage(ChatMessage.ROLE_USER, userPrompt))
        }

        val engine = NativeLlmBridge.create(debugHooksEnabled = false)
        try {
            val loadStarted = SystemClock.elapsedRealtime()
            assertTrue("Unable to load ${model.file.absolutePath}", engine.loadModel(model.file.absolutePath, settings))
            val loadMs = SystemClock.elapsedRealtime() - loadStarted
            val generationStarted = SystemClock.elapsedRealtime()
            val chunks = engine.generateChat(messages, settings).toList()
            val elapsedMs = SystemClock.elapsedRealtime() - generationStarted
            val output = chunks.joinToString("") { it.text }
            val terminal = chunks.lastOrNull { it.isTerminal }
            val parsed = ReasoningOutputParser.parse(output)
            val tokens = terminal?.tokenCount?.takeIf { it > 0 } ?: chunks.sumOf { it.tokenCount }
            val measuredTps = terminal?.tokensPerSec?.takeIf { it > 0f }
                ?: if (elapsedMs > 0) tokens * 1000f / elapsedMs else 0f
            val report = "model=${model.fileName} loadMs=$loadMs elapsedMs=$elapsedMs tokens=$tokens " +
                "tps=$measuredTps ttftMs=${terminal?.ttftMs} threads=${terminal?.activeThreads} " +
                "terminal=${terminal?.terminalReason} reasoning=${parsed.hasReasoning} answer=${parsed.answer.take(500)}"
            Log.i("ReasoningOnDevice", report)
            println("REASONING_RESULT $report")
            println("REASONING_RAW ${output.take(2000).replace('\n', ' ')}")

            assertTrue("Generation returned no text", output.isNotBlank())
            assertNotNull("Generation returned no terminal chunk", terminal)
            assertFalse("Generation failed: $report", terminal?.terminalReason == "ERROR")
            assertTrue("Expected a useful Spanish answer: ${parsed.answer}", "24" in parsed.answer || "24" in output)
        } finally {
            engine.destroySafely()
        }
    }

    @Test
    fun deepSeekSecondTurnStaysInSpanishAndFinishesThinking() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runDeepSeekConversationRegression") == "true")
        val context = instrumentation.targetContext
        val storage = ModelStorageManager(context)
        val requestedModelId = arguments.getString("modelId")
        val model = if (requestedModelId != null) {
            val resolved = storage.resolveActiveModel(requestedModelId)
            require(resolved is ModelStorageManager.ModelResolveResult.Success) {
                "Unable to resolve $requestedModelId: $resolved"
            }
            resolved.model
        } else {
            val entry = requireNotNull(HuggingFaceModelCatalog.find("deepseek_r1_distill_qwen_15b_q4km"))
            requireNotNull(storage.listInstalledModelInfos().firstOrNull { it.fileName == entry.fileName })
        }
        val behavior = ModelBehaviorProfiles.resolve(model.fileName)
        val settings = behavior.effectiveSettings(
            GenerationSettings(
                maxTokens = 768,
                threadCount = 4,
                contextLength = 2048,
                batchSize = 512,
                reasoningMode = ReasoningMode.THINKING,
                useVulkan = false,
                gpuLayers = 0,
            ),
            agentEnabled = false,
        )
        val secondTurn = behavior.prepareUserPrompt(
            "Respóndeme en español.",
            ReasoningMode.THINKING,
            agentEnabled = false,
        )
        val messages = listOf(
            ChatMessage(ChatMessage.ROLE_USER, "¿Cuál es el tercer planeta del sistema solar?"),
            ChatMessage(ChatMessage.ROLE_ASSISTANT, "The third planet from the Sun is Earth."),
            ChatMessage(ChatMessage.ROLE_USER, secondTurn),
        )

        val engine = NativeLlmBridge.create(debugHooksEnabled = false)
        try {
            assertTrue("Unable to load ${model.file.absolutePath}", engine.loadModel(model.file.absolutePath, settings))
            val startedAt = SystemClock.elapsedRealtime()
            val chunks = engine.generateChat(messages, settings).toList()
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val output = chunks.joinToString("") { it.text }
            val parsed = ReasoningOutputParser.parse(output)
            val terminal = chunks.lastOrNull { it.isTerminal }
            val report = "elapsedMs=$elapsedMs terminal=${terminal?.terminalReason} " +
                "tps=${terminal?.tokensPerSec} complete=${parsed.reasoningComplete} answer=${parsed.answer.take(500)}"
            Log.i("DeepSeekRegression", report)
            println("DEEPSEEK_SECOND_TURN $report")

            assertNotNull("No terminal chunk", terminal)
            assertTrue("Expected EOF but got $report", terminal?.terminalReason == "EOF")
            assertTrue("Thinking block was not closed: $report", parsed.reasoningComplete)
            assertFalse("Answer stayed in English: $report", "the third planet" in parsed.answer.lowercase())
            assertTrue(
                "Expected a Spanish response: $report",
                listOf("el", "la", "respuesta", "español").any { it in parsed.answer.lowercase().split(Regex("\\W+")) },
            )
        } finally {
            engine.destroySafely()
        }
    }
}
