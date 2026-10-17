package com.prismai.llmhost.persistence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

sealed interface LegacyMemoryImportResult {
    data object NoDatabase : LegacyMemoryImportResult
    data class AlreadyCompleted(val memoryCount: Int) : LegacyMemoryImportResult
    data class Imported(val memoryCount: Int) : LegacyMemoryImportResult
}

class LegacyMemoryImporter(
    private val database: PrismDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MIGRATION_KEY = "legacy_memories_sqlite_v1"
        private const val TAG = "LegacyMemoryImporter"
        private const val LEGACY_DB_NAME = "prism_memory.db"
    }

    suspend fun importIfNeeded(context: Context): LegacyMemoryImportResult {
        val conversationDao = database.conversationDao()
        val memoryDao = database.memoryDao()

        val previous = conversationDao.migrationState(MIGRATION_KEY)
        if (previous != null) {
            return LegacyMemoryImportResult.AlreadyCompleted(previous.sourceMessageCount)
        }

        val dbFile = context.getDatabasePath(LEGACY_DB_NAME)
        if (!dbFile.exists()) {
            return LegacyMemoryImportResult.NoDatabase
        }

        val extracted = readLegacyMemories(dbFile)
        if (extracted.isNotEmpty()) {
            memoryDao.insertAll(extracted)
            Log.i(TAG, "Migrated ${extracted.size} legacy memories into Room prismlocal.db")
        }

        val state = MigrationStateEntity(
            key = MIGRATION_KEY,
            completedAt = now(),
            sourceChatCount = 0,
            sourceMessageCount = extracted.size,
        )
        conversationDao.upsertMigrationState(state)
        return LegacyMemoryImportResult.Imported(extracted.size)
    }

    private fun readLegacyMemories(dbFile: File): List<MemoryEntity> {
        val entities = mutableListOf<MemoryEntity>()
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery(
                "SELECT id, fact, category, confidence, source_chat_id, created_at, " +
                    "last_accessed_at, access_count, decayed FROM memories",
                null,
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val fact = c.getString(1)
                    val category = c.getString(2) ?: "GENERAL"
                    val confidence = c.getFloat(3)
                    val sourceChatId = c.getString(4)
                    val createdAt = c.getLong(5)
                    val lastAccessedAt = c.getLong(6)
                    val accessCount = c.getInt(7)
                    val decayed = c.getInt(8) == 1
                    val scope = if (sourceChatId.isNullOrBlank()) "GLOBAL" else "CHAT"

                    entities += MemoryEntity(
                        id = id,
                        fact = fact,
                        category = category,
                        confidence = confidence,
                        sourceChatId = sourceChatId,
                        scope = scope,
                        createdAt = createdAt,
                        lastAccessedAt = lastAccessedAt,
                        accessCount = accessCount,
                        decayed = decayed,
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading legacy memory database: ${e.message}", e)
        } finally {
            db?.close()
        }
        return entities
    }
}
