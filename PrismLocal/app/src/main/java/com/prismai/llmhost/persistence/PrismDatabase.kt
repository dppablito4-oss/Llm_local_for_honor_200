package com.prismai.llmhost.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
        MigrationStateEntity::class,
        MemoryEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class PrismDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun memoryDao(): MemoryDao

    companion object {
        const val DATABASE_NAME = "prismlocal.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memories` (
                        `id` TEXT NOT NULL,
                        `fact` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `sourceChatId` TEXT,
                        `scope` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastAccessedAt` INTEGER NOT NULL,
                        `accessCount` INTEGER NOT NULL,
                        `decayed` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_scope_sourceChatId` ON `memories` (`scope`, `sourceChatId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_lastAccessedAt` ON `memories` (`lastAccessedAt`)")
            }
        }

        @Volatile
        private var instance: PrismDatabase? = null

        fun getInstance(context: Context): PrismDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PrismDatabase::class.java,
                    DATABASE_NAME,
                )
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
            }
    }
}
