package com.prismai.llmhost

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prismai.llmhost.diffusion.DiffusionBackend
import com.prismai.llmhost.diffusion.NativeDiffusionBridge
import com.prismai.llmhost.storage.ModelStorageManager
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Stages are invoked separately: runtime -> GGML -> CPU image -> GPU image. */
@RunWith(AndroidJUnit4::class)
class OpenClDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun save(name: String, report: JSONObject) {
        File(context.filesDir, "opencl-verification").apply { mkdirs() }
            .resolve("$name.json").writeText(report.toString(2))
        File(context.getExternalFilesDir(null), "opencl-verification").apply { mkdirs() }
            .resolve("$name.json").writeText(report.toString(2))
    }

    private fun verifyNonBlankPng(file: File): Int {
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
        try {
            assertEquals(512, bitmap.width); assertEquals(512, bitmap.height)
            val colors = HashSet<Int>()
            for (y in 0 until bitmap.height step 16) for (x in 0 until bitmap.width step 16) {
                colors.add(bitmap.getPixel(x, y))
            }
            assertTrue("Generated PNG is blank or almost uniform", colors.size >= 16)
            return colors.size
        } finally { bitmap.recycle() }
    }

    @Test fun importVerifiedSdxs() {
        val expected = requireNotNull(InstrumentationRegistry.getArguments().getString("model_sha256"))
        require(expected.matches(Regex("[a-f0-9]{64}")))
        val storage = ModelStorageManager(context)
        val filename = "sdxs-512-tinySDdistilled_Q8_0.gguf"
        val existing = storage.listInstalledModelInfos().firstOrNull { it.id == filename.removeSuffix(".gguf") }
        if (existing != null) {
            assertEquals("Refusing to replace an existing model", expected, existing.sha256)
            return
        }
        val source = File(context.getExternalFilesDir(null), "opencl-smoke/$filename")
        assertTrue(source.isFile)
        val imported = source.inputStream().use { storage.importModelFromStream(filename, source.length(), it) }
        assertTrue("SDXS import failed", imported is ModelStorageManager.ImportResult.Success)
        val model = (imported as ModelStorageManager.ImportResult.Success).model
        assertEquals(expected, model.sha256)
        save("model-import", JSONObject().put("id", model.id).put("sha256", model.sha256).put("bytes", model.bytes))
    }

    @Test fun runtimeEnumeratesGpu(): Unit = runBlocking {
        NativeDiffusionBridge().use { bridge ->
            val report = JSONObject(bridge.gpuDiagnostics(false))
            save("runtime", report)
            assertTrue("OpenCL runtime unavailable: $report", report.optBoolean("runtime_available"))
            assertTrue(report.getString("ggml_devices").contains("GPUOpenCL"))
            assertEquals("GPU_DETECTADA_SIN_COMPUTO", report.getString("status"))
        }
    }

    @Test fun ggmlMatrixMatchesCpu(): Unit = runBlocking {
        NativeDiffusionBridge().use { bridge ->
            val report = JSONObject(bridge.gpuDiagnostics(true))
            save("ggml-matrix", report)
            assertTrue("GPU matrix not verified: $report", report.optBoolean("compute_verified"))
            assertEquals("GPU", report.getJSONObject("gpu_matrix").getString("device_type"))
            assertEquals("GPU_COMPUTO_VERIFICADO", report.getString("status"))
        }
    }

    @Test fun sdxsImageAndRecovery(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val backend = DiffusionBackend.valueOf(args.getString("diffusion_backend") ?: "CPU")
        val hybrid = args.getString("hybrid") == "true"
        val storage = ModelStorageManager(context)
        val model = requireNotNull(storage.listInstalledModelInfos().firstOrNull {
            it.id.startsWith("sdxs-512-tinySDdistilled_Q8_0")
        }) { "Install the verified SDXS smoke model into the experimental package first" }
        val report = JSONObject().put("requested", backend.name).put("hybrid", hybrid)
            .put("model_id", model.id).put("sha256", model.sha256).put("seed", 42)
            .put("steps", 1).put("cfg", 1.0).put("width", 512).put("height", 512)
            .put("prompt", "A small red cube on a white background, studio lighting.")
            .put("png_verified", false)
        val name = "sdxs-${backend.name}-${if (hybrid) "hybrid" else "all"}"
        val maxPss = AtomicLong(Debug.getPss())
        val maxThermal = AtomicInteger(0)
        val timedOut = AtomicBoolean(false)
        val thermal = context.getSystemService(PowerManager::class.java)
        assertTrue("Wait for the phone to cool", Build.VERSION.SDK_INT < 29 || thermal.currentThermalStatus < 3)
        val started = SystemClock.elapsedRealtime()
        NativeDiffusionBridge().use { bridge ->
            val monitor = launch(Dispatchers.Default) {
                while (true) {
                    delay(1000)
                    maxPss.updateAndGet { maxOf(it, Debug.getPss()) }
                    if (Build.VERSION.SDK_INT >= 29) maxThermal.updateAndGet { maxOf(it, thermal.currentThermalStatus) }
                    if (SystemClock.elapsedRealtime() - started > 180_000) timedOut.set(true)
                    if (maxThermal.get() >= 3 || timedOut.get()) bridge.cancel()
                }
            }
            try {
                assertTrue(bridge.loadModel(model.file.absolutePath, backend = backend, hybrid = hybrid))
                report.put("load_ms", SystemClock.elapsedRealtime() - started)
                    .put("load_backend", JSONObject(bridge.backendInfo()))
                save(name, report)
                val dir = File(context.filesDir, "generated_images").apply { mkdirs() }
                val output = File(dir, "${name}_${System.currentTimeMillis()}.png")
                val generationStarted = SystemClock.elapsedRealtime()
                val result = bridge.generate(report.getString("prompt"), outputPath = output.absolutePath,
                    width = 512, height = 512, steps = 1, cfgScale = 1f, seed = 42)
                report.put("generation_wall_seconds", (SystemClock.elapsedRealtime() - generationStarted) / 1000.0)
                report.put("generation_seconds", result.generationTimeSec).put("error", result.errorMessage)
                    .put("executed_backend", JSONObject(bridge.backendInfo()))
                assertTrue("Image generation failed: ${result.errorMessage}", result.success)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(output.absolutePath, bounds)
                assertEquals(512, bounds.outWidth); assertEquals(512, bounds.outHeight)
                assertTrue(output.length() > 1024)
                report.put("sample_unique_colors", verifyNonBlankPng(output))
                report.put("png_verified", true).put("png_path", output.absolutePath).put("png_bytes", output.length())
                output.copyTo(File(context.getExternalFilesDir(null), "opencl-verification/${output.name}"), overwrite = false)
                if (backend == DiffusionBackend.GPU_OPENCL) assertEquals("STABLE_DIFFUSION_GPU_VERIFICADO",
                    JSONObject(bridge.backendInfo()).getString("status"))
            } catch (error: Throwable) {
                report.put("failure", error.toString())
                throw error
            } finally {
                monitor.cancel()
                report.put("max_process_pss_kb", maxPss.get()).put("max_android_thermal_status", maxThermal.get())
                    .put("time_limit_reached", timedOut.get()).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                save(name, report)
            }
        }
        report.put("process_pss_kb_after_close", Debug.getPss())
        save(name, report)
    }

    /** Host sends this test-only broadcast after observing the requested phase in Logcat. */
    @Test fun cancelAtObservedPhaseAndRecoverOnCpu(): Unit = runBlocking {
        val phase = requireNotNull(InstrumentationRegistry.getArguments().getString("cancel_phase"))
        require(phase in setOf("CLIP", "UNET", "VAE"))
        val model = requireNotNull(ModelStorageManager(context).listInstalledModelInfos().firstOrNull {
            it.id.startsWith("sdxs-512-tinySDdistilled_Q8_0")
        })
        val report = JSONObject().put("requested_phase", phase).put("cancel_verified", false).put("cpu_recovery_verified", false)
        NativeDiffusionBridge().use { bridge ->
            assertTrue(bridge.loadModel(model.file.absolutePath, backend = DiffusionBackend.GPU_OPENCL, hybrid = true))
            val requested = AtomicBoolean(false)
            val cancelTime = java.util.concurrent.atomic.AtomicLong(0)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.getStringExtra("phase") != phase) return
                    requested.set(true)
                    cancelTime.compareAndSet(0, SystemClock.elapsedRealtime())
                    android.util.Log.i("PrismGPU", "Test cancellation received: phase=$phase")
                    bridge.cancel()
                }
            }
            val filter = IntentFilter("${context.packageName}.TEST_CANCEL_DIFFUSION")
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
            val started = SystemClock.elapsedRealtime()
            val watchdog = launch(Dispatchers.Default) {
                while (true) {
                    delay(250)
                    val thermal = context.getSystemService(PowerManager::class.java)
                    val hot = Build.VERSION.SDK_INT >= 29 && thermal.currentThermalStatus >= 3
                    if (requested.get() || hot || SystemClock.elapsedRealtime() - started > 180_000) bridge.cancel()
                }
            }
            try {
                android.util.Log.i("PrismGPU", "Test cancellation receiver ready: phase=$phase")
                val prompt = "A small red cube on a white background, studio lighting."
                // Several CLIP chunks leave a measurable window for host-triggered
                // cancellation. Ordinary image benchmarks keep the short prompt.
                val cancelled = bridge.generate(if (phase == "CLIP") "$prompt ".repeat(256) else prompt,
                    steps = 1, cfgScale = 1f, seed = 42)
                assertTrue("No observed-phase cancellation request arrived", requested.get())
                assertFalse("Cancelled generation must fail", cancelled.success)
                val latency = SystemClock.elapsedRealtime() - cancelTime.get()
                report.put("cancel_ms", latency).put("cancel_backend", JSONObject(bridge.backendInfo()))
                assertTrue("Cancellation exceeded 15 seconds", latency < 15_000)
                report.put("cancel_verified", true)
                watchdog.cancelAndJoin()
                // Joining the first native call precedes releasing its context and loading CPU.
                assertTrue(bridge.loadModel(model.file.absolutePath, backend = DiffusionBackend.CPU))
                val dir = File(context.filesDir, "generated_images").apply { mkdirs() }
                val output = File(dir, "recovery_${phase}_${System.currentTimeMillis()}.png")
                val recovered = bridge.generate("A small red cube on a white background, studio lighting.",
                    outputPath = output.absolutePath, steps = 1, cfgScale = 1f, seed = 42)
                assertTrue("CPU recovery image failed", recovered.success)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(output.absolutePath, bounds)
                assertEquals(512, bounds.outWidth); assertEquals(512, bounds.outHeight)
                verifyNonBlankPng(output)
                assertEquals("CPU_IMAGE_VERIFIED", JSONObject(bridge.backendInfo()).getString("status"))
                report.put("cpu_recovery_verified", true).put("cpu_png_path", output.absolutePath)
                val exported = File(context.getExternalFilesDir(null), "opencl-verification").apply { mkdirs() }
                output.copyTo(File(exported, output.name), overwrite = false)
            } catch (error: Throwable) {
                report.put("failure", error.toString()); throw error
            } finally {
                watchdog.cancel(); context.unregisterReceiver(receiver)
                save("cancel-$phase", report)
            }
        }
    }
}
