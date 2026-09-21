package com.prismai.llmhost.generation

import com.prismai.llmhost.TranscriptMessage
import com.prismai.llmhost.TranscriptRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBuilderSummaryIntegrationTest {
    @Test
    fun accumulatedSummaryReplacesCoveredMessagesWithoutExceedingBudget() {
        val transcript = (1L..120L).map { id ->
            TranscriptMessage(
                id = id,
                role = if (id % 2L == 0L) TranscriptRole.ASSISTANT else TranscriptRole.USER,
                text = "contenido-unico-$id " + "detalle ".repeat(8),
            )
        }
        val summary = "El usuario vive en Lima, trabaja en impresión y decidió usar el modelo Qwen."

        val prepared = ContextBuilder.prepare(
            newPrompt = "¿Qué recuerdas?",
            transcript = transcript,
            activeAssistantTranscriptId = null,
            memoryContext = "",
            summaryContext = summary,
            summaryUntilMessageId = 110L,
            promptTokenBudget = 1000,
            contextLength = 1536,
            reservedOutputTokens = 384,
            reservedTemplateTokens = 64,
            systemPrompt = "Responde en español.",
        )

        val summaryUsage = prepared.sections.single { it.section == ContextSection.SUMMARY }
        val historyUsage = prepared.sections.single { it.section == ContextSection.HISTORY }
        assertEquals(1, summaryUsage.includedItems)
        assertEquals(10, historyUsage.includedItems)
        assertEquals(0, historyUsage.droppedItems)
        assertTrue(prepared.estimatedPromptTokens <= prepared.promptTokenBudget)
        assertTrue(prepared.messages.first().content.contains(summary))
        assertFalse(prepared.messages.any { it.content.contains("contenido-unico-110 ") })
        assertTrue(prepared.messages.any { it.content.contains("contenido-unico-111 ") })
    }
}
