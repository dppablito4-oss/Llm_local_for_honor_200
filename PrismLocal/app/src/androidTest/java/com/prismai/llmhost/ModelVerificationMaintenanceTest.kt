package com.prismai.llmhost

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prismai.llmhost.persistence.PrismDatabase
import com.prismai.llmhost.storage.ModelStorageManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Explicit, guarded rollback of a deleted temporary test model's annotation only. */
@RunWith(AndroidJUnit4::class)
class ModelVerificationMaintenanceTest {
    @Test fun restoreOwnedTemporaryModelReference(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val chat = requireNotNull(args.getString("repair_chat_id"))
        val temporary = requireNotNull(args.getString("temporary_model_id"))
        val restored = requireNotNull(args.getString("restore_text_model"))
        require(temporary.matches(Regex("stability-smoke-[a-f0-9-]{36}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = ModelStorageManager(context)
        assertNull("Never alter a reference to an installed model", storage.activeModelInfo(temporary))
        assertEquals(ModelStorageManager.ModelKind.TEXT, storage.activeModelInfo(restored)?.kind)
        val db = PrismDatabase.getInstance(context)
        val dao = db.conversationDao()
        val before = requireNotNull(dao.chatById(chat))
        assertEquals("Unexpected chat annotation: stop without editing", temporary, before.modelId)
        fun messageDigest(): String {
            val rows = JSONArray()
            db.openHelper.readableDatabase.query("SELECT * FROM messages WHERE chatId=? ORDER BY id", arrayOf(chat)).use { c ->
                while (c.moveToNext()) {
                    val row = JSONArray()
                    for (i in 0 until c.columnCount) row.put(if (c.isNull(i)) JSONObject.NULL else c.getString(i))
                    rows.put(row)
                }
            }
            return MessageDigest.getInstance("SHA-256").digest(rows.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
        val messagesBefore = messageDigest()
        // UPDATE one field: REPLACE on the parent table could cascade-delete
        // messages, so it must never be used for this maintenance operation.
        db.runInTransaction {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE chats SET modelId=? WHERE id=? AND modelId=?", arrayOf(restored, chat, temporary))
        }
        assertEquals(before.copy(modelId = restored), dao.chatById(chat))
        assertEquals("All messages must remain byte-equivalent", messagesBefore, messageDigest())
        val report = JSONObject().put("repaired_test_model_reference", true).put("messages_unchanged", true)
        File(context.filesDir, "model-verification").apply { mkdirs() }.resolve("metadata-repair.json").writeText(report.toString())
    }
}
