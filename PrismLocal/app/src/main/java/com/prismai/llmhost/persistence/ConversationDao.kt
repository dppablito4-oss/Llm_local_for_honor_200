package com.prismai.llmhost.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChatsIfAbsent(chats: List<ChatEntity>): List<Long>

    @Update
    suspend fun updateChats(chats: List<ChatEntity>): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceMessages(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMigrationState(state: MigrationStateEntity)

    @Query("SELECT * FROM migration_state WHERE `key` = :key LIMIT 1")
    suspend fun migrationState(key: String): MigrationStateEntity?

    @Query("SELECT * FROM chats WHERE id IN (:ids)")
    suspend fun chatsByIds(ids: List<String>): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE id = :chatId LIMIT 1")
    suspend fun chatById(chatId: String): ChatEntity?

    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    suspend fun allChats(): List<ChatEntity>

    @Query("SELECT id FROM chats")
    suspend fun allChatIds(): List<String>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id ASC")
    suspend fun messagesForChat(chatId: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteMessagesForChat(chatId: String): Int

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteChat(chatId: String): Int

    @Query("SELECT COUNT(*) FROM chats")
    suspend fun chatCount(): Int

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun messageCount(): Int
}
