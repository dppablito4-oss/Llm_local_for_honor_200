package com.prismai.llmhost.diffusion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class NativeDiffusionBridge : Closeable {

    companion object {
        init {
            System.loadLibrary("llmhost")
        }
    }

    private var nativeHandle: Long = nativeCreate()
    private val isDestroyed = AtomicBoolean(false)

    suspend fun loadModel(
        modelPath: String,
        vaePath: String = "",
        taesdPath: String = "",
        threads: Int = 4,
        onGpu: Boolean = true,
        vaeTiling: Boolean = true,
    ): Boolean = withContext(Dispatchers.IO) {
        if (isDestroyed.get() || nativeHandle == 0L) return@withContext false
        nativeLoadModel(nativeHandle, modelPath, vaePath, taesdPath, threads, onGpu, vaeTiling)
    }

    suspend fun generate(
        prompt: String,
        negativePrompt: String = "",
        outputPath: String = "",
        width: Int = 512,
        height: Int = 512,
        steps: Int = 4,
        cfgScale: Float = 1.0f,
        seed: Long = System.currentTimeMillis(),
    ): DiffusionNativeResult = withContext(Dispatchers.IO) {
        if (isDestroyed.get() || nativeHandle == 0L) {
            return@withContext DiffusionNativeResult(
                success = false,
                width = 0,
                height = 0,
                channels = 0,
                outputPath = "",
                pngBytes = null,
                errorMessage = "Diffusion bridge is closed or invalid",
                generationTimeSec = 0f,
            )
        }
        nativeGenerate(
            nativeHandle,
            prompt,
            negativePrompt,
            outputPath,
            width,
            height,
            steps,
            cfgScale,
            seed,
        )
    }

    fun cancel() {
        if (!isDestroyed.get() && nativeHandle != 0L) {
            nativeCancel(nativeHandle)
        }
    }

    fun isLoaded(): Boolean {
        if (isDestroyed.get() || nativeHandle == 0L) return false
        return nativeIsLoaded(nativeHandle)
    }

    override fun close() {
        if (isDestroyed.compareAndSet(false, true)) {
            val handle = nativeHandle
            nativeHandle = 0L
            if (handle != 0L) {
                nativeDestroy(handle)
            }
        }
    }

    // --- JNI declarations ---
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeLoadModel(
        handle: Long,
        modelPath: String,
        vaePath: String,
        taesdPath: String,
        threads: Int,
        onGpu: Boolean,
        vaeTiling: Boolean,
    ): Boolean

    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        negativePrompt: String,
        outputPath: String,
        width: Int,
        height: Int,
        steps: Int,
        cfgScale: Float,
        seed: Long,
    ): DiffusionNativeResult

    private external fun nativeCancel(handle: Long)
    private external fun nativeIsLoaded(handle: Long): Boolean
}
