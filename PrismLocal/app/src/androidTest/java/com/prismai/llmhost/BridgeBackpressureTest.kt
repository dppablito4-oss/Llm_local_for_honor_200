package com.prismai.llmhost

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prismai.llmhost.bridge.NativeLlmBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BridgeBackpressureTest {
    @Test
    fun slowConsumerReceivesEveryNativeTokenInOrder() = runBlocking {
        val engine = NativeLlmBridge.create(debugHooksEnabled = true)
        try {
            assertTrue(engine.loadModel("DEBUG_MOCK_MODEL"))
            val chunks = withTimeout(15_000) {
                engine.generate("DEBUG_SIMULATE_RING").buffer(0)
                    .onEach { if (!it.isTerminal) delay(350) }.toList()
            }
            assertEquals(1000, chunks.sumOf { it.tokenCount })
            val received = Regex("T\\d+").findAll(chunks.joinToString("") { it.text })
                .map { it.value }.toList()
            assertEquals((10000 until 11000).map { "T$it" }, received)
            assertEquals(1, chunks.count { it.isTerminal })
            assertEquals("EOF", chunks.last().terminalReason)
        } finally { engine.destroySafely() }
    }

    @Test
    fun slowConsumerMatchesExactRealGgufText() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val model = File.createTempFile("backpressure-", ".gguf", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("smoke-model/tinyllama-v0.q8_0.gguf").use { input ->
                model.outputStream().use { input.copyTo(it) }
            }
            suspend fun generate(slow: Boolean): List<GenerationChunk> {
                val engine = NativeLlmBridge.create(debugHooksEnabled = false)
                try {
                    val settings = GenerationSettings(maxTokens = 256, threadCount = 2,
                        contextLength = 2048, batchSize = 128, topK = 1, temperature = 0.05f)
                    assertTrue(engine.loadModel(model.absolutePath, settings))
                    return withTimeout(15_000) {
                        engine.generate("Once upon a time", settings).buffer(0)
                            .onEach { if (slow && !it.isTerminal) delay(350) }.toList()
                    }
                } finally { engine.destroySafely() }
            }
            val fast = generate(slow = false)
            val slow = generate(slow = true)
            assertTrue(fast.sumOf { it.tokenCount } > 0)
            assertEquals(fast.sumOf { it.tokenCount }, slow.sumOf { it.tokenCount })
            assertEquals(fast.joinToString("") { it.text }, slow.joinToString("") { it.text })
            assertEquals(1, slow.count { it.isTerminal })
            assertEquals(fast.last().terminalReason, slow.last().terminalReason)
            assertTrue(slow.last().terminalReason in setOf("EOF", "MAX_TOKENS"))
        } finally { model.delete() }
    }

    @Test
    fun cancelledBackpressuredCollectorWaitsForNativeCleanupAndCanRestart() = runBlocking {
        val engine = NativeLlmBridge.create(debugHooksEnabled = true)
        try {
            assertTrue(engine.loadModel("DEBUG_MOCK_MODEL"))
            val first = CompletableDeferred<Int>()
            val collector = launch {
                engine.generate("DEBUG_SIMULATE_RING").buffer(0).collect { chunk ->
                    if (!chunk.isTerminal) {
                        first.complete(chunk.generationId)
                        awaitCancellation()
                    }
                }
            }
            val id = withTimeout(2000) { first.await() }
            delay(150) // Force the producer to wait on the rendezvous channel.
            val field = NativeLlmBridge::class.java.getDeclaredField("genMutex").apply { isAccessible = true }
            val mutex = field.get(engine) as Mutex
            mutex.lock()
            try {
                collector.cancel()
                delay(50)
                assertFalse("cleanup must wait for genMutex, even when cancelled", collector.isCompleted)
            } finally { mutex.unlock() }
            withTimeout(2000) { collector.cancelAndJoin() }
            // cancelGeneration retires the worker and deliberately preserves
            // CANCELLED for native waiters; EOF ack may also leave TOMBSTONED.
            assertTrue("native worker must have stopped", engine.debugStateForTesting(id) in setOf(4, 6))
            assertTrue("cancelled ring must be empty", engine.debugDrainTokensForTesting(id, 128).isEmpty())
            val restarted = withTimeout(5000) { engine.generate("DEBUG_SIMULATE_RING").toList() }
            assertEquals(1000, restarted.sumOf { it.tokenCount })
            assertEquals("EOF", restarted.last().terminalReason)
        } finally { engine.destroySafely() }
    }
}
