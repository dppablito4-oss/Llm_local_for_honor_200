package com.prismai.llmhost.storage

import com.prismai.llmhost.MemoryCategory
import com.prismai.llmhost.MemoryFact
import com.prismai.llmhost.MemoryMatch
import com.prismai.llmhost.MemoryRetriever
import com.prismai.llmhost.MemoryScope
import com.prismai.llmhost.MemoryStore
import com.prismai.llmhost.persistence.MemoryDao
import com.prismai.llmhost.persistence.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class RoomMemoryStore(
    private val memoryDao: MemoryDao,
) : MemoryStore {

    override fun insert(fact: MemoryFact): MemoryFact = runBlocking(Dispatchers.IO) {
        val entity = fact.toEntity()
        memoryDao.insert(entity)
        fact
    }

    override fun update(id: String, fact: String, confidence: Float): Boolean = runBlocking(Dispatchers.IO) {
        memoryDao.updateFact(id, fact, confidence) > 0
    }

    override fun markDecayed(id: String): Boolean = runBlocking(Dispatchers.IO) {
        memoryDao.markDecayed(id) > 0
    }

    override fun delete(id: String): Boolean = runBlocking(Dispatchers.IO) {
        memoryDao.delete(id) > 0
    }

    override fun getAllActive(): List<MemoryFact> = runBlocking(Dispatchers.IO) {
        memoryDao.allActiveMemories().map { it.toMemoryFact() }
    }

    override fun getActiveForChat(chatId: String?): List<MemoryFact> = runBlocking(Dispatchers.IO) {
        memoryDao.activeMemoriesForChat(chatId).map { it.toMemoryFact() }
    }

    override fun queryRelevant(query: String, limit: Int): List<MemoryMatch> {
        return queryRelevantForChat(query, chatId = null, limit = limit)
    }

    override fun queryRelevantForChat(query: String, chatId: String?, limit: Int): List<MemoryMatch> {
        val candidates = getActiveForChat(chatId)
        return candidates
            .map { MemoryMatch(it, MemoryRetriever.score(query, it.fact)) }
            .filter { it.score > 0f }
            .sortedByDescending { it.score }
            .take(limit)
    }

    override fun decayOldMemories(olderThanMillis: Long): Int = runBlocking(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        memoryDao.decayOldMemories(cutoff)
    }

    override fun findSimilar(fact: String, threshold: Float): List<MemoryFact> {
        val all = getAllActive()
        return all.filter { existing ->
            MemoryRetriever.score(fact, existing.fact) >= threshold
        }
    }

    override fun activeCount(): Int = runBlocking(Dispatchers.IO) {
        memoryDao.activeCount()
    }

    override fun totalCount(): Int = runBlocking(Dispatchers.IO) {
        memoryDao.totalCount()
    }

    override fun touch(id: String): MemoryFact? = runBlocking(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        memoryDao.touch(id, now)
        memoryDao.memoryById(id)?.toMemoryFact()
    }

    companion object {
        fun MemoryEntity.toMemoryFact(): MemoryFact = MemoryFact(
            id = id,
            fact = fact,
            category = runCatching { MemoryCategory.valueOf(category) }.getOrDefault(MemoryCategory.GENERAL),
            confidence = confidence,
            sourceChatId = sourceChatId,
            scope = if (scope == "CHAT") MemoryScope.CHAT else MemoryScope.GLOBAL,
            createdAt = createdAt,
            lastAccessedAt = lastAccessedAt,
            accessCount = accessCount,
            decayed = decayed,
        )

        fun MemoryFact.toEntity(): MemoryEntity = MemoryEntity(
            id = id,
            fact = fact,
            category = category.name,
            confidence = confidence,
            sourceChatId = sourceChatId,
            scope = scope.name,
            createdAt = createdAt,
            lastAccessedAt = lastAccessedAt,
            accessCount = accessCount,
            decayed = decayed,
        )
    }
}
