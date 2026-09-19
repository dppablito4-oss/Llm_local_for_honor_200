package com.prismai.llmhost

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prismai.llmhost.bridge.NativeLlmBridge
import com.prismai.llmhost.storage.ModelStorageManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in benchmark against a model already imported into the target app.
 *
 * Run with:
 *   adb shell am instrument -w \
 *     -e class com.prismai.llmhost.OnDevicePerformanceTest \
 *     -e runPerformanceBenchmark true \
 *     com.prismai.llmhost.dev.debug.test/androidx.test.runner.AndroidJUnitRunner
 *
 * It is skipped during the regular connected-test suite so CI does not depend on
 * a large, separately downloaded GGUF file.
 */
@RunWith(AndroidJUnit4::class)
class OnDevicePerformanceTest {
    @Test
    fun benchmarkActiveImportedModel() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runPerformanceBenchmark") == "true")

        val context = instrumentation.targetContext
        val requestedModelId = arguments.getString("modelId")
            ?: context.getSharedPreferences("llm_host_prefs", 0)
                .getString("active_model", null)
        assertFalse("No active model is configured", requestedModelId.isNullOrBlank())

        val resolved = ModelStorageManager(context).resolveActiveModel(requireNotNull(requestedModelId))
        assertTrue("Unable to resolve model '$requestedModelId': $resolved", resolved is ModelStorageManager.ModelResolveResult.Success)
        val model = (resolved as ModelStorageManager.ModelResolveResult.Success).model

        val threads = arguments.getString("threads")?.toIntOrNull() ?: 4
        val engine = NativeLlmBridge.create(debugHooksEnabled = false)
        try {
            assertTrue("Unable to load ${model.file.absolutePath}", engine.loadModel(model.file.absolutePath))
            val result = engine.runNativeBenchmark(
                settings = GenerationSettings(threadCount = threads, useVulkan = false, gpuLayers = 0),
                promptTokens = 16,
                generationTokens = 16,
                repetitions = 1,
            )
            Log.i("OnDevicePerformance", "model=$requestedModelId result=$result")
            println("ON_DEVICE_PERFORMANCE model=$requestedModelId result=$result")
            assertFalse("Native benchmark failed: $result", result.contains("\"error\""))
        } finally {
            engine.destroySafely()
        }
    }
}
