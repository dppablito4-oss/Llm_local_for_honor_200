package com.prismai.llmhost.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(memories: List<MemoryEntity>)

    @Query(
        "SELECT * FROM memories WHERE decayed = 0 AND " +
            "(scope = 'GLOBAL' OR (scope = 'CHAT' AND sourceChatId = :chatId)) " +
            "ORDER BY lastAccessedAt DESC",
    )
    suspend fun activeMemoriesForChat(chatId: String?): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE decayed = 0 ORDER BY lastAccessedAt DESC")
    suspend fun allActiveMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY lastAccessedAt DESC")
    suspend fun allMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    suspend fun memoryById(id: String): MemoryEntity?

    @Query("UPDATE memories SET fact = :fact, confidence = :confidence WHERE id = :id")
    suspend fun updateFact(id: String, fact: String, confidence: Float): Int

    @Query("UPDATE memories SET decayed = 1 WHERE id = :id")
    suspend fun markDecayed(id: String): Int

    @Query("UPDATE memories SET lastAccessedAt = :now, accessCount = accessCount + 1 WHERE id = :id")
    suspend fun touch(id: String, now: Long): Int

    @Query("UPDATE memories SET decayed = 1 WHERE createdAt < :cutoff AND lastAccessedAt < :cutoff AND decayed = 0")
    suspend fun decayOldMemories(cutoff: Long): Int

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT COUNT(*) FROM memories WHERE decayed = 0")
    suspend fun activeCount(): Int

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun totalCount(): Int
}
