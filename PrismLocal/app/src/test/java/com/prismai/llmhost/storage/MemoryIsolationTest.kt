package com.prismai.llmhost.storage

import com.prismai.llmhost.MemoryCategory
import com.prismai.llmhost.MemoryFact
import com.prismai.llmhost.MemoryScope
import com.prismai.llmhost.persistence.MemoryDao
import com.prismai.llmhost.persistence.MemoryEntity
import com.prismai.llmhost.storage.RoomMemoryStore.Companion.toEntity
import com.prismai.llmhost.storage.RoomMemoryStore.Companion.toMemoryFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryIsolationTest {

    private class FakeMemoryDao : MemoryDao {
        val storage = mutableMapOf<String, MemoryEntity>()

        override suspend fun insert(memory: MemoryEntity) {
            storage[memory.id] = memory
        }

        override suspend fun insertAll(memories: List<MemoryEntity>) {
            memories.forEach { storage[it.id] = it }
        }

        override suspend fun activeMemoriesForChat(chatId: String?): List<MemoryEntity> {
            return storage.values
                .filter { !it.decayed }
                .filter { it.scope == "GLOBAL" || (chatId != null && it.sourceChatId == chatId) }
                .sortedByDescending { it.lastAccessedAt }
        }

        override suspend fun allActiveMemories(): List<MemoryEntity> {
            return storage.values.filter { !it.decayed }.sortedByDescending { it.lastAccessedAt }
        }

        override suspend fun allMemories(): List<MemoryEntity> = storage.values.toList()

        override suspend fun memoryById(id: String): MemoryEntity? = storage[id]

        override suspend fun updateFact(id: String, fact: String, confidence: Float): Int {
            val curr = storage[id] ?: return 0
            storage[id] = curr.copy(fact = fact, confidence = confidence)
            return 1
        }

        override suspend fun markDecayed(id: String): Int {
            val curr = storage[id] ?: return 0
            storage[id] = curr.copy(decayed = true)
            return 1
        }

        override suspend fun touch(id: String, now: Long): Int {
            val curr = storage[id] ?: return 0
            storage[id] = curr.copy(lastAccessedAt = now, accessCount = curr.accessCount + 1)
            return 1
        }

        override suspend fun decayOldMemories(cutoff: Long): Int {
            var count = 0
            storage.entries.forEach { (k, v) ->
                if (v.createdAt < cutoff && v.lastAccessedAt < cutoff && !v.decayed) {
                    storage[k] = v.copy(decayed = true)
                    count++
                }
            }
            return count
        }

        override suspend fun delete(id: String): Int = if (storage.remove(id) != null) 1 else 0

        override suspend fun activeCount(): Int = storage.values.count { !it.decayed }

        override suspend fun totalCount(): Int = storage.size
    }

    @Test
    fun chatPrivateMemoryNeverLeaksToAnotherChat() {
        val dao = FakeMemoryDao()
        val store = RoomMemoryStore(dao)

        val chatAFact = MemoryFact(
            id = "mem-1",
            fact = "Prefiero programar en Kotlin y Python",
            sourceChatId = "chat-A",
            scope = MemoryScope.CHAT,
        )
        val chatBFact = MemoryFact(
            id = "mem-2",
            fact = "Mi perro se llama Firulais",
            sourceChatId = "chat-B",
            scope = MemoryScope.CHAT,
        )
        val globalFact = MemoryFact(
            id = "mem-3",
            fact = "El usuario se llama Pablo",
            sourceChatId = null,
            scope = MemoryScope.GLOBAL,
        )

        store.insert(chatAFact)
        store.insert(chatBFact)
        store.insert(globalFact)

        // Querying for Chat A
        val chatAMemories = store.getActiveForChat("chat-A")
        assertEquals(2, chatAMemories.size)
        assertTrue(chatAMemories.any { it.id == "mem-1" })
        assertTrue(chatAMemories.any { it.id == "mem-3" })
        assertFalse("Chat A must never see Chat B private facts", chatAMemories.any { it.id == "mem-2" })

        // Querying for Chat B
        val chatBMemories = store.getActiveForChat("chat-B")
        assertEquals(2, chatBMemories.size)
        assertTrue(chatBMemories.any { it.id == "mem-2" })
        assertTrue(chatBMemories.any { it.id == "mem-3" })
        assertFalse("Chat B must never see Chat A private facts", chatBMemories.any { it.id == "mem-1" })

        // Querying without chat scope gets only global facts
        val neutralMemories = store.getActiveForChat(null)
        assertEquals(1, neutralMemories.size)
        assertEquals("mem-3", neutralMemories.first().id)
    }

    @Test
    fun queryRelevantForChatExcludesForeignChatMemories() {
        val dao = FakeMemoryDao()
        val store = RoomMemoryStore(dao)

        store.insert(
            MemoryFact(
                id = "python-a",
                fact = "Usa Python para scripts de automatización",
                sourceChatId = "chat-dev",
                scope = MemoryScope.CHAT,
            ),
        )
        store.insert(
            MemoryFact(
                id = "python-b",
                fact = "Odia Python y prefiere Java en proyectos legacy",
                sourceChatId = "chat-other",
                scope = MemoryScope.CHAT,
            ),
        )

        val matchesForDev = store.queryRelevantForChat("Python scripts", "chat-dev")
        assertEquals(1, matchesForDev.size)
        assertEquals("python-a", matchesForDev.first().fact.id)

        val matchesForOther = store.queryRelevantForChat("Python", "chat-other")
        assertEquals(1, matchesForOther.size)
        assertEquals("python-b", matchesForOther.first().fact.id)
    }

    @Test
    fun entityAndModelBidirectionalMappingPreservesScope() {
        val original = MemoryFact(
            id = "id-123",
            fact = "Dato de prueba",
            category = MemoryCategory.PREFERENCE,
            confidence = 0.85f,
            sourceChatId = "chat-x",
            scope = MemoryScope.CHAT,
            accessCount = 4,
            decayed = false,
        )

        val entity = original.toEntity()
        assertEquals("CHAT", entity.scope)
        assertEquals("chat-x", entity.sourceChatId)

        val restored = entity.toMemoryFact()
        assertEquals(original, restored)
    }
}
