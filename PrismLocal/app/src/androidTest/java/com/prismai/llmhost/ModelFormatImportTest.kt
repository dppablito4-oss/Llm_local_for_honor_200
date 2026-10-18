package com.prismai.llmhost

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prismai.llmhost.storage.ModelStorageManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ModelFormatImportTest {
    private val storage = ModelStorageManager(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun ggufTensorNamesClassifyImageDespiteTextLookingFilename() {
        val header = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        fun string(value: String) { val b = value.toByteArray(); header.putLong(b.size.toLong()); header.put(b) }
        header.put("GGUF".toByteArray()).putInt(3).putLong(1).putLong(0)
        string("model.diffusion_model.input_blocks.0.0.weight")
        header.putInt(1).putLong(1).putInt(0).putLong(0)
        val bytes = ByteArray(1024 * 1024)
        header.array().copyInto(bytes)
        val result = storage.importModelFromStream("text-model-${UUID.randomUUID()}.gguf", bytes.size.toLong(), bytes.inputStream())
        assertTrue(result is ModelStorageManager.ImportResult.Success)
        val model = (result as ModelStorageManager.ImportResult.Success).model
        try { assertEquals(ModelStorageManager.ModelKind.IMAGE, model.kind) }
        finally { storage.deleteModel(model.id) }
    }

    private fun safetensors(invalid: Boolean): ByteArray {
        val payload = 1024 * 1024
        val header = JSONObject()
            .put("model.diffusion_model.weight", JSONObject().put("dtype", "F32")
                .put("shape", JSONArray().put(payload / 4)).put("data_offsets", JSONArray().put(0).put(if (invalid) payload + 1000 else payload)))
            .put("model_ema.decay", JSONObject().put("dtype", "F32")
                .put("shape", JSONArray()).put("data_offsets", JSONArray().put(payload).put(payload + 4)))
            .toString().toByteArray()
        return ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(header.size.toLong()).array())
            write(header); write(ByteArray(payload + 4))
        }.toByteArray()
    }

    @Test fun safetensorsScalarSurvivesImportAndKeepsItsExtension() {
        val bytes = safetensors(false)
        val result = storage.importModelFromStream("checkpoint-${UUID.randomUUID()}.safetensors", bytes.size.toLong(), bytes.inputStream())
        assertTrue(result is ModelStorageManager.ImportResult.Success)
        val model = (result as ModelStorageManager.ImportResult.Success).model
        try {
            assertEquals(ModelStorageManager.ModelKind.IMAGE, model.kind)
            assertEquals("SAFETENSORS", model.validation.format)
            assertEquals("safetensors", model.file.extension)
            assertEquals(ModelStorageManager.ModelKind.IMAGE, storage.activeModelInfo(model.id)?.kind)
        } finally { storage.deleteModel(model.id) }
    }

    @Test fun safetensorsOutOfBoundsDataIsRejected() {
        val bytes = safetensors(true)
        val result = storage.importModelFromStream("invalid-${UUID.randomUUID()}.safetensors", bytes.size.toLong(), bytes.inputStream())
        assertTrue(result is ModelStorageManager.ImportResult.Failure)
    }
}
