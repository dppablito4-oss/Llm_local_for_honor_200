package com.prismai.llmhost.agent.tools

import com.prismai.llmhost.tools.*
import com.prismai.llmhost.diffusion.DiffusionManager
import com.prismai.llmhost.storage.ModelStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DiffusionTools(
    private val diffusionManager: DiffusionManager,
    private val modelStorageManager: ModelStorageManager,
) {
    suspend fun generateImage(call: AgentToolCall): AgentToolResult = withContext(Dispatchers.IO) {
        val prompt = call.arguments.optString("prompt", "").trim()
        if (prompt.isBlank()) {
            return@withContext AgentToolResult(
                call = call,
                success = false,
                summary = "El prompt para generar la imagen no puede estar vacío.",
                errorCode = AgentToolErrorCode.INVALID_ARGUMENT,
            )
        }

        val steps = call.arguments.optInt("steps", 4).coerceIn(1, 20)
        val width = call.arguments.optInt("width", 512).coerceIn(256, 1024)
        val height = call.arguments.optInt("height", 512).coerceIn(256, 1024)
        val cfgScale = call.arguments.optDouble("cfg_scale", 1.0).toFloat()

        if (!diffusionManager.isLoaded()) {
            val installed = modelStorageManager.listInstalledModels()
            val diffModelId = installed.firstOrNull { id ->
                id.contains("sd", ignoreCase = true) ||
                id.contains("diffusion", ignoreCase = true) ||
                id.contains("turbo", ignoreCase = true)
            }

            if (diffModelId == null) {
                return@withContext AgentToolResult(
                    call = call,
                    success = false,
                    summary = "No hay ningún modelo de imagen (Stable Diffusion) instalado en el dispositivo. Descarga 'SDXS 512' o 'SD-Turbo' desde el menú de Modelos.",
                    errorCode = AgentToolErrorCode.NOT_FOUND,
                )
            }

            val activeModel = modelStorageManager.activeModelInfo(diffModelId)
                ?: return@withContext AgentToolResult(
                    call = call,
                    success = false,
                    summary = "No se pudo resolver el archivo del modelo de difusión $diffModelId.",
                    errorCode = AgentToolErrorCode.FAILED,
                )

            val loaded = diffusionManager.loadModel(
                modelPath = activeModel.file.absolutePath,
                threads = 4,
                onGpu = true,
            )

            if (!loaded) {
                return@withContext AgentToolResult(
                    call = call,
                    success = false,
                    summary = "Falló la inicialización del motor Stable Diffusion.",
                    errorCode = AgentToolErrorCode.FAILED,
                )
            }
        }

        val result = diffusionManager.generateImage(
            prompt = prompt,
            width = width,
            height = height,
            steps = steps,
            cfgScale = cfgScale,
        )

        result.fold(
            onSuccess = { file ->
                AgentToolResult(
                    call = call,
                    success = true,
                    summary = "Imagen generada con éxito:\n\n![${prompt.take(40)}](file://${file.absolutePath})",
                    details = JSONObject().apply {
                        put("image_path", file.absolutePath)
                        put("file_uri", "file://${file.absolutePath}")
                        put("prompt", prompt)
                        put("width", width)
                        put("height", height)
                        put("steps", steps)
                    },
                )
            },
            onFailure = { error ->
                AgentToolResult(
                    call = call,
                    success = false,
                    summary = "Error generando la imagen: ${error.message ?: "Fallo desconocido"}",
                    errorCode = AgentToolErrorCode.FAILED,
                )
            }
        )
    }
}
