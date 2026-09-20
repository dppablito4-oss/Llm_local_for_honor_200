package com.prismai.llmhost

import com.prismai.llmhost.bridge.ChatMessage
import com.prismai.llmhost.generation.PromptBuilder
import com.prismai.llmhost.generation.ContextBuilder
import com.prismai.llmhost.generation.ContextSection
import com.prismai.llmhost.generation.TokenCountSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure (no device) tests for [PromptBuilder.assembleChatMessages], the
 * role-preserving counterpart to the legacy string prompt path. These cover the
 * assembly contract: system framing, newest-first history budgeting, exclusion
 * of blank/active turns, and the always-present final user message.
 */
class PromptBuilderMessagesTest {

    private fun message(
        id: Long,
        role: TranscriptRole,
        text: String,
        summary: String? = null,
    ): TranscriptMessage = TranscriptMessage(id = id, role = role, text = text, summary = summary)

    @Test
    fun rolesAndOrderPreserveHistoryInterleaving() {
        val transcript = listOf(
            message(1, TranscriptRole.USER, "hello"),
            message(2, TranscriptRole.ASSISTANT, "hi there"),
            message(3, TranscriptRole.USER, "how are you?"),
        )

        val result = PromptBuilder.assembleChatMessages("what's up?", transcript, null, "", 4096)

        assertEquals(
            listOf(
                ChatMessage.ROLE_SYSTEM,
                ChatMessage.ROLE_USER,
                ChatMessage.ROLE_ASSISTANT,
                ChatMessage.ROLE_USER,
                ChatMessage.ROLE_USER,
            ),
            result.map { it.role },
        )
        assertEquals("hello", result[1].content)
        assertEquals("hi there", result[2].content)
        assertEquals("how are you?", result[3].content)
        assertEquals(ChatMessage.ROLE_USER, result.last().role)
        assertEquals("what's up?", result.last().content)
    }

    @Test
    fun activeAssistantTurnIsExcluded() {
        val transcript = listOf(
            message(1, TranscriptRole.USER, "question"),
            message(2, TranscriptRole.ASSISTANT, "partial answer"),
            message(3, TranscriptRole.USER, "follow up"),
        )

        val result = PromptBuilder.assembleChatMessages("next", transcript, 2L, "", 4096)

        assertTrue(result.none { it.content == "partial answer" })
        assertEquals(
            listOf(ChatMessage.ROLE_SYSTEM, ChatMessage.ROLE_USER, ChatMessage.ROLE_USER, ChatMessage.ROLE_USER),
            result.map { it.role },
        )
        assertEquals("question", result[1].content)
        assertEquals("follow up", result[2].content)
    }

    @Test
    fun blankTranscriptEntriesAreExcluded() {
        val transcript = listOf(
            message(1, TranscriptRole.USER, "real"),
            message(2, TranscriptRole.ASSISTANT, "   "),
            message(3, TranscriptRole.USER, ""),
        )

        val result = PromptBuilder.assembleChatMessages("q", transcript, null, "", 4096)

        assertEquals(
            listOf(ChatMessage.ROLE_SYSTEM, ChatMessage.ROLE_USER, ChatMessage.ROLE_USER),
            result.map { it.role },
        )
        assertEquals("real", result[1].content)
        assertEquals("q", result.last().content)
    }

    @Test
    fun toolTurnBecomesUserWithToolResultPrefix() {
        val transcript = listOf(message(1, TranscriptRole.TOOL, "42 degrees"))

        val result = PromptBuilder.assembleChatMessages("thanks", transcript, null, "", 4096)

        assertEquals(3, result.size)
        assertEquals(ChatMessage.ROLE_USER, result[1].role)
        assertTrue(result[1].content.startsWith("[tool result]"))
        assertEquals("[tool result] 42 degrees", result[1].content)
        assertEquals("thanks", result.last().content)
    }

    @Test
    fun emptyTranscriptAndMemoryStillFrameFinalUser() {
        val result = PromptBuilder.assembleChatMessages("just this", emptyList(), null, "", 4096)

        assertEquals(2, result.size)
        assertEquals(ChatMessage.ROLE_SYSTEM, result.first().role)
        assertEquals(ChatMessage.ROLE_USER, result.last().role)
        assertEquals("just this", result.last().content)
    }

    @Test
    fun tinyTokenBudgetKeepsFramingAndFinalUserLast() {
        val result = PromptBuilder.assembleChatMessages("hello world", emptyList(), null, "", 1)

        assertEquals(2, result.size)
        assertEquals(ChatMessage.ROLE_SYSTEM, result.first().role)
        assertEquals(ChatMessage.ROLE_USER, result.last().role)
        assertEquals("hello world", result.last().content)
    }

    @Test
    fun budgetDropsOlderTurnsButKeepsNewest() {
        val older = "x".repeat(400)  // ~100 estimated tokens
        val newest = "y".repeat(400) // ~100 estimated tokens
        val transcript = listOf(
            message(1, TranscriptRole.USER, older),
            message(2, TranscriptRole.USER, newest),
        )

        val result = PromptBuilder.assembleChatMessages("q", transcript, null, "", 150)

        // The newest turn fits and is always kept; the older turn would exceed the budget.
        assertEquals(3, result.size)
        assertEquals(newest, result[1].content)
        assertEquals("q", result.last().content)
    }

    @Test
    fun systemPromptCanBeOmittedForModelsThatDoNotSupportIt() {
        val result = PromptBuilder.assembleChatMessages(
            newPrompt = "resuelve esto",
            transcript = emptyList(),
            activeAssistantTranscriptId = null,
            memoryContext = "Dato recordado",
            tokenBudget = 4096,
            systemPrompt = null,
        )

        assertEquals(1, result.size)
        assertEquals(ChatMessage.ROLE_USER, result.single().role)
        assertEquals("Dato recordado\n\nresuelve esto", result.single().content)
    }

    @Test
    fun reasoningIsRemovedFromAssistantHistory() {
        val transcript = listOf(
            message(1, TranscriptRole.ASSISTANT, "<think>secreto interno</think>Respuesta final"),
        )

        val result = PromptBuilder.assembleChatMessages("continúa", transcript, null, "", 4096)

        assertEquals("Respuesta final", result[1].content)
        assertTrue(result.none { "secreto interno" in it.content })
    }

    @Test
    fun incompleteReasoningIsNotAddedAsAnEmptyAssistantTurn() {
        val transcript = listOf(
            message(1, TranscriptRole.USER, "pregunta"),
            message(2, TranscriptRole.ASSISTANT, "<think>sigue pensando"),
        )

        val result = PromptBuilder.assembleChatMessages("nuevo intento", transcript, null, "", 4096)

        assertEquals(
            listOf(ChatMessage.ROLE_SYSTEM, ChatMessage.ROLE_USER, ChatMessage.ROLE_USER),
            result.map { it.role },
        )
        assertTrue(result.none { it.content.isBlank() })
    }

    @Test
    fun persistedTextHistoryCanBeRebuiltForAnotherModelWithoutKvState() {
        val persistedTranscript = listOf(
            message(1, TranscriptRole.USER, "Recuerda la clave ORQUIDEA"),
            message(2, TranscriptRole.ASSISTANT, "La clave es ORQUIDEA"),
        )

        // A model switch discards native KV state. The next model must receive
        // the durable text transcript again, preserving roles and order.
        val rebuiltForNewModel = PromptBuilder.assembleChatMessages(
            newPrompt = "Cual era la clave?",
            transcript = persistedTranscript,
            activeAssistantTranscriptId = null,
            memoryContext = "",
            tokenBudget = 4096,
        )

        assertEquals(
            listOf(
                ChatMessage.ROLE_SYSTEM,
                ChatMessage.ROLE_USER,
                ChatMessage.ROLE_ASSISTANT,
                ChatMessage.ROLE_USER,
            ),
            rebuiltForNewModel.map { it.role },
        )
        assertEquals("Recuerda la clave ORQUIDEA", rebuiltForNewModel[1].content)
        assertEquals("La clave es ORQUIDEA", rebuiltForNewModel[2].content)
        assertEquals("Cual era la clave?", rebuiltForNewModel.last().content)
    }

    @Test
    fun preparedContextReportsIncludedAndDroppedHistory() {
        val transcript = listOf(
            message(1, TranscriptRole.USER, "a".repeat(400)),
            message(2, TranscriptRole.ASSISTANT, "b".repeat(120)),
            message(3, TranscriptRole.USER, "c".repeat(120)),
        )

        val prepared = ContextBuilder.prepare(
            newPrompt = "pregunta actual",
            transcript = transcript,
            activeAssistantTranscriptId = null,
            memoryContext = "",
            promptTokenBudget = 110,
            contextLength = 512,
            reservedOutputTokens = 128,
            reservedTemplateTokens = 64,
            systemPrompt = "sistema",
        )

        val history = prepared.sections.single { it.section == ContextSection.HISTORY }
        assertEquals(2, history.includedItems)
        assertEquals(1, history.droppedItems)
        assertEquals("pregunta actual", prepared.messages.last().content)
        assertEquals(512, prepared.contextLength)
        assertEquals(128, prepared.reservedOutputTokens)
    }

    @Test
    fun oversizedMemoryIsBoundedBeforeRecentHistory() {
        val recent = "turno reciente"
        val prepared = ContextBuilder.prepare(
            newPrompt = "continua",
            transcript = listOf(message(1, TranscriptRole.USER, recent)),
            activeAssistantTranscriptId = null,
            memoryContext = "memoria ".repeat(200),
            promptTokenBudget = 160,
            systemPrompt = "sistema",
        )

        val memory = prepared.sections.single { it.section == ContextSection.MEMORY }
        assertTrue(memory.truncated)
        assertTrue(prepared.messages.any { it.content == recent })
        assertEquals("continua", prepared.messages.last().content)
    }

    @Test
    fun currentQuestionSurvivesEvenWhenFixedContextExceedsBudget() {
        val prepared = ContextBuilder.prepare(
            newPrompt = "pregunta imprescindible",
            transcript = listOf(message(1, TranscriptRole.USER, "historial")),
            activeAssistantTranscriptId = null,
            memoryContext = "memoria",
            promptTokenBudget = 0,
            systemPrompt = "sistema",
        )

        assertEquals("pregunta imprescindible", prepared.messages.last().content)
        assertEquals(1, prepared.droppedHistoryMessages)
        assertTrue(prepared.estimatedPromptTokens > prepared.promptTokenBudget)
    }

    @Test
    fun nativeTokenizerCountCanBeAttachedWithoutLosingEstimate() {
        val prepared = ContextBuilder.prepare(
            newPrompt = "hola",
            transcript = emptyList(),
            activeAssistantTranscriptId = null,
            memoryContext = "",
            promptTokenBudget = 100,
            systemPrompt = "sistema",
        )

        val exact = prepared.withNativeTokenCount(17, "qwen-test")

        assertEquals(TokenCountSource.NATIVE_TOKENIZER, exact.tokenCountSource)
        assertEquals(17, exact.totalPromptTokens)
        assertEquals("qwen-test", exact.modelId)
        assertEquals("llama.cpp-active-model", exact.tokenizer)
        assertEquals(prepared.estimatedPromptTokens, exact.estimatedPromptTokens)
    }
}
