package com.prismai.llmhost.generation

import com.prismai.llmhost.ChatSession
import com.prismai.llmhost.TranscriptMessage
import com.prismai.llmhost.TranscriptRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSummarizerTest {
    @Test
    fun extractsOnlyUnsummarizedPrefixAndKeepsRecentTail() {
        val transcript = (1L..24L).map { id ->
            TranscriptMessage(
                id = id,
                role = if (id % 2L == 0L) TranscriptRole.ASSISTANT else TranscriptRole.USER,
                text = "mensaje-$id dato importante",
            )
        }
        val session = session(summary = "El usuario vive en Lima.", untilId = 4L)

        val plan = ConversationSummarizer.createPlan(session, transcript, pressuredContext())

        assertNotNull(plan)
        assertEquals(5L, plan!!.messages.first().id)
        assertEquals(16L, plan.messages.last().id)
        assertEquals(16L, plan.untilMessageId)
        assertEquals((5L..16L).toList(), plan.messages.map { it.id })
        val prompt = ConversationSummarizer.buildPrompt(plan)
        assertTrue(prompt.contains("El usuario vive en Lima."))
        assertTrue(prompt.contains("mensaje-5"))
        assertFalse(prompt.contains("mensaje-17"))
    }

    @Test
    fun triggerUsesSeventyFivePercentOrDroppedHistory() {
        assertTrue(ConversationSummarizer.shouldSummarize(pressuredContext(total = 750, budget = 1000)))
        assertFalse(ConversationSummarizer.shouldSummarize(pressuredContext(total = 749, budget = 1000)))
        assertTrue(
            ConversationSummarizer.shouldSummarize(
                pressuredContext(total = 200, budget = 1000, dropped = 1),
            ),
        )
    }

    private fun session(summary: String?, untilId: Long?) = ChatSession(
        id = "chat-test",
        title = "Prueba",
        createdAt = 1L,
        updatedAt = 2L,
        modelId = "Qwen3-1.7B",
        messageCount = 24,
        summary = summary,
        summaryUntilMessageId = untilId,
    )

    private fun pressuredContext(
        total: Int = 900,
        budget: Int = 1000,
        dropped: Int = 0,
    ) = PreparedContext(
        messages = emptyList(),
        sections = listOf(
            ContextSectionUsage(ContextSection.HISTORY, 600, 10, droppedItems = dropped),
        ),
        contextLength = 2048,
        reservedOutputTokens = 384,
        reservedTemplateTokens = 64,
        promptTokenBudget = budget,
        estimatedPromptTokens = total,
        totalPromptTokens = total,
    )
}
