package com.prismai.llmhost.diffusion

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

class DiffusionManager(private val context: Context) {

    private val mutex = Mutex()
    private var bridge: NativeDiffusionBridge? = null
    private var currentModelPath: String? = null

    val imagesDir: File by lazy {
        File(context.filesDir, "generated_images").apply {
            if (!exists()) mkdirs()
        }
    }

    suspend fun loadModel(
        modelPath: String,
        vaePath: String = "",
        taesdPath: String = "",
        threads: Int = 4,
        onGpu: Boolean = true,
    ): Boolean = mutex.withLock {
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
        negativePrompt: String = "blurry, bad quality, distorted",
        width: Int = 512,
        height: Int = 512,
        steps: Int = 4,
        cfgScale: Float = 1.0f,
        seed: Long = System.currentTimeMillis(),
    ): Result<File> = mutex.withLock {
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

        if (result.success && outputFile.exists() && outputFile.length() > 0) {
            Result.success(outputFile)
        } else if (result.success && result.pngBytes != null && result.pngBytes.isNotEmpty()) {
            outputFile.writeBytes(result.pngBytes)
            Result.success(outputFile)
        } else {
            Result.failure(RuntimeException(result.errorMessage.ifEmpty { "Error desconocido generando imagen" }))
        }
    }

    fun cancel() {
        bridge?.cancel()
    }

    fun isLoaded(): Boolean = bridge?.isLoaded() == true

    fun unload() {
        bridge?.close()
        bridge = null
        currentModelPath = null
    }
}
