package com.prismai.llmhost.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
        MigrationStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PrismDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao

    companion object {
        const val DATABASE_NAME = "prismlocal.db"

        @Volatile
        private var instance: PrismDatabase? = null

        fun getInstance(context: Context): PrismDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PrismDatabase::class.java,
                    DATABASE_NAME,
                ).build().also { instance = it }
            }
    }
}
