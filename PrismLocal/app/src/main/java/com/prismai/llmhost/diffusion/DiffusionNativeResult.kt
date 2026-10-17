package com.prismai.llmhost.diffusion

/**
 * Native result structure matching the C++ JNI layout in diffusion_jni.cpp.
 */
data class DiffusionNativeResult(
    val success: Boolean,
    val width: Int,
    val height: Int,
    val channels: Int,
    val outputPath: String,
    val pngBytes: ByteArray?,
    val errorMessage: String,
    val generationTimeSec: Float,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DiffusionNativeResult

        if (success != other.success) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (channels != other.channels) return false
        if (outputPath != other.outputPath) return false
        if (pngBytes != null) {
            if (other.pngBytes == null) return false
            if (!pngBytes.contentEquals(other.pngBytes)) return false
        } else if (other.pngBytes != null) return false
        if (errorMessage != other.errorMessage) return false
        if (generationTimeSec != other.generationTimeSec) return false

        return true
    }

    override fun hashCode(): Int {
        var result = success.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + channels
        result = 31 * result + outputPath.hashCode()
        result = 31 * result + (pngBytes?.contentHashCode() ?: 0)
        result = 31 * result + errorMessage.hashCode()
        result = 31 * result + generationTimeSec.hashCode()
        return result
    }
}
