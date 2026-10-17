package com.prismai.llmhost.diffusion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class ImageGenerationOutput(
    val file: File,
    val generationTimeSec: Float,
    val steps: Int,
    val seed: Long,
)

class DiffusionManager(private val context: Context) {

    private val mutex = Mutex()
    @Volatile private var bridge: NativeDiffusionBridge? = null
    private val closing = AtomicBoolean(false)
    private var currentModelPath: String? = null

    val imagesDir: File by lazy {
        File(context.filesDir, "generated_images").apply {
            if (!exists()) mkdirs()
        }
    }

    fun resolveTaesdIfPresent(modelPath: String): String {
        val modelFile = File(modelPath)
        val parent = modelFile.parentFile ?: return ""
        val candidates = listOf(
            "taesd_decoder.safetensors",
            "taesd_decoder.gguf",
            "taesd.safetensors",
            "taesd.pth",
            "taesd.gguf"
        )
        for (candidate in candidates) {
            val file = File(parent, candidate)
            if (file.exists() && file.length() > 0) {
                return file.absolutePath
            }
        }
        return ""
    }

    suspend fun loadModel(
        modelPath: String,
        vaePath: String = "",
        taesdPath: String = resolveTaesdIfPresent(modelPath),
        threads: Int = 4,
        onGpu: Boolean = true,
    ): Boolean = mutex.withLock {
        if (closing.get()) return false
        if (bridge == null) {
            bridge = NativeDiffusionBridge()
        }
        val currentBridge = bridge ?: return false
        val ok = currentBridge.loadModel(
            modelPath = modelPath,
            vaePath = vaePath,
            taesdPath = taesdPath,
            threads = threads,
            onGpu = onGpu,
            vaeTiling = true,
        )
        if (ok) {
            currentModelPath = modelPath
        }
        ok
    }

    suspend fun generateImage(
        prompt: String,
        negativePrompt: String = "",
        width: Int = 512,
        height: Int = 512,
        steps: Int = 1,
        cfgScale: Float = 1.0f,
        seed: Long = System.currentTimeMillis(),
    ): Result<ImageGenerationOutput> = mutex.withLock {
        if (closing.get()) {
            return Result.failure(IllegalStateException("El motor de imágenes se está cerrando"))
        }
        val currentBridge = bridge
        if (currentBridge == null || !currentBridge.isLoaded()) {
            return Result.failure(IllegalStateException("El modelo de difusión no está cargado"))
        }

        val outputFile = File(imagesDir, "img_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.png")

        val result = currentBridge.generate(
            prompt = prompt,
            negativePrompt = negativePrompt,
            outputPath = outputFile.absolutePath,
            width = width,
            height = height,
            steps = steps,
            cfgScale = cfgScale,
            seed = seed,
        )

        val output = ImageGenerationOutput(
            file = outputFile,
            generationTimeSec = result.generationTimeSec,
            steps = steps,
            seed = seed,
        )

        if (result.success && outputFile.exists() && outputFile.length() > 0) {
            Result.success(output)
        } else if (result.success && result.pngBytes != null && result.pngBytes.isNotEmpty()) {
            outputFile.writeBytes(result.pngBytes)
            Result.success(output)
        } else {
            Result.failure(RuntimeException(result.errorMessage.ifEmpty { "Error desconocido generando imagen" }))
        }
    }

    fun cancel() {
        bridge?.cancel()
    }

    fun isLoaded(): Boolean = bridge?.isLoaded() == true

    suspend fun unload() {
        cancel()
        // Coroutine cancellation must not abandon a native free. Waiting for
        // generate/load and freeing the context are always off the main thread.
        withContext(Dispatchers.IO + NonCancellable) {
            mutex.withLock {
                bridge?.close()
                bridge = null
                currentModelPath = null
            }
        }
    }

    suspend fun shutdown() {
        closing.set(true)
        unload()
    }
}
