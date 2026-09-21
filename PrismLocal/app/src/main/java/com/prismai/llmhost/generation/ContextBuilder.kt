package com.prismai.llmhost.generation

import com.prismai.llmhost.TranscriptMessage
import com.prismai.llmhost.TranscriptRole
import com.prismai.llmhost.bridge.ChatMessage
import com.prismai.llmhost.model.ReasoningOutputParser
import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.min

enum class ContextSection {
    SYSTEM,
    MEMORY,
    RAG,
    SUMMARY,
    HISTORY,
    CURRENT_USER,
}

enum class TokenCountSource {
    CONSERVATIVE_ESTIMATE,
    NATIVE_TOKENIZER,
}

data class ContextSectionUsage(
    val section: ContextSection,
    val estimatedTokens: Int,
    val includedItems: Int,
    val droppedItems: Int = 0,
    val truncated: Boolean = false,
)

/**
 * A complete, inspectable snapshot of what will be sent to the model.
 *
 * Section counts are conservative estimates because chat-template overhead is
 * not additive. [totalPromptTokens] is replaced by the exact tokenizer result
 * when a model is loaded, while [estimatedPromptTokens] remains useful for
 * diagnostics and model-free tests.
 */
data class PreparedContext(
    val messages: List<ChatMessage>,
    val sections: List<ContextSectionUsage>,
    val contextLength: Int,
    val reservedOutputTokens: Int,
    val reservedTemplateTokens: Int,
    val promptTokenBudget: Int,
    val estimatedPromptTokens: Int,
    val totalPromptTokens: Int = estimatedPromptTokens,
    val tokenCountSource: TokenCountSource = TokenCountSource.CONSERVATIVE_ESTIMATE,
    val modelId: String? = null,
    val tokenizer: String = "conservative-chars-div-4",
) {
    val droppedHistoryMessages: Int
        get() = sections.firstOrNull { it.section == ContextSection.HISTORY }?.droppedItems ?: 0

    val fitsEstimatedPackingBudget: Boolean
        get() = estimatedPromptTokens <= promptTokenBudget

    val fitsContextWindow: Boolean
        get() = totalPromptTokens <= (contextLength - reservedOutputTokens - 8).coerceAtLeast(0)

    fun withNativeTokenCount(tokens: Int, activeModelId: String? = null): PreparedContext = copy(
        totalPromptTokens = tokens.coerceAtLeast(0),
        tokenCountSource = TokenCountSource.NATIVE_TOKENIZER,
        modelId = activeModelId,
        tokenizer = "llama.cpp-active-model",
    )
}

/** Packs durable text context without depending on any particular model. */
object ContextBuilder {
    private const val MESSAGE_OVERHEAD_TOKENS = 4
    private const val CONVERSATION_OVERHEAD_TOKENS = 2
    private const val MAX_MEMORY_TOKENS = 384
    private const val MAX_RAG_TOKENS = 1_024
    private const val MAX_SUMMARY_TOKENS = 512

    fun prepare(
        newPrompt: String,
        transcript: List<TranscriptMessage>,
        activeAssistantTranscriptId: Long?,
        memoryContext: String,
        ragContext: String = "",
        summaryContext: String = "",
        summaryUntilMessageId: Long? = null,
        promptTokenBudget: Int,
        contextLength: Int = promptTokenBudget,
        reservedOutputTokens: Int = 0,
        reservedTemplateTokens: Int = 0,
        systemPrompt: String?,
    ): PreparedContext {
        val budget = promptTokenBudget.coerceAtLeast(0)
        val systemText = systemPrompt.orEmpty()
        val systemTokens = estimateTextTokens(systemText)
        val userTokens = estimateMessageTokens(newPrompt)

        // Memory, RAG, and summary have independent ceilings, so a large
        // document or memory list cannot evict the whole recent conversation.
        val fixedTokens = CONVERSATION_OVERHEAD_TOKENS +
            (if (systemPrompt != null) MESSAGE_OVERHEAD_TOKENS + systemTokens else 0) +
            userTokens
        var remaining = (budget - fixedTokens).coerceAtLeast(0)

        val memoryLimit = min(MAX_MEMORY_TOKENS, maxOf(64, budget / 5))
        val memory = fitSection(memoryContext, min(memoryLimit, remaining))
        remaining = (remaining - memory.tokens).coerceAtLeast(0)

        val ragLimit = min(MAX_RAG_TOKENS, maxOf(128, budget * 35 / 100))
        val rag = fitSection(ragContext, min(ragLimit, remaining))
        remaining = (remaining - rag.tokens).coerceAtLeast(0)

        val summaryLimit = min(MAX_SUMMARY_TOKENS, maxOf(96, budget / 5))
        val summary = fitSection(summaryContext, min(summaryLimit, remaining))
        remaining = (remaining - summary.tokens).coerceAtLeast(0)

        val candidates = transcript.asSequence()
            .filter {
                it.id != activeAssistantTranscriptId &&
                    it.text.isNotBlank() &&
                    (summaryUntilMessageId == null || it.id > summaryUntilMessageId)
            }
            .mapNotNull { it.toChatMessageOrNull() }
            .toList()
        val selected = ArrayDeque<ChatMessage>()
        var historyTokens = 0
        for (message in candidates.asReversed()) {
            val tokens = estimateMessageTokens(message.content)
            if (historyTokens + tokens > remaining) break
            selected.addFirst(message)
            historyTokens += tokens
        }

        val memoryAndKnowledge = listOf(memory.text, rag.text, summary.text)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val messages = ArrayList<ChatMessage>(selected.size + 2)
        if (systemPrompt != null) {
            val content = listOf(systemText, memoryAndKnowledge)
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
            messages += ChatMessage(ChatMessage.ROLE_SYSTEM, content)
        }
        messages.addAll(selected)
        val finalUser = if (systemPrompt == null && memoryAndKnowledge.isNotBlank()) {
            "$memoryAndKnowledge\n\n$newPrompt"
        } else {
            newPrompt
        }
        messages += ChatMessage(ChatMessage.ROLE_USER, finalUser)

        val estimatedTotal = CONVERSATION_OVERHEAD_TOKENS + messages.sumOf {
            estimateMessageTokens(it.content)
        }
        return PreparedContext(
            messages = messages,
            sections = listOf(
                ContextSectionUsage(
                    ContextSection.SYSTEM,
                    if (systemPrompt != null) systemTokens + MESSAGE_OVERHEAD_TOKENS else 0,
                    if (systemPrompt != null) 1 else 0,
                ),
                memory.usage(ContextSection.MEMORY),
                rag.usage(ContextSection.RAG),
                summary.usage(ContextSection.SUMMARY),
                ContextSectionUsage(
                    section = ContextSection.HISTORY,
                    estimatedTokens = historyTokens,
                    includedItems = selected.size,
                    droppedItems = candidates.size - selected.size,
                ),
                ContextSectionUsage(ContextSection.CURRENT_USER, userTokens, 1),
            ),
            contextLength = contextLength,
            reservedOutputTokens = reservedOutputTokens,
            reservedTemplateTokens = reservedTemplateTokens,
            promptTokenBudget = budget,
            estimatedPromptTokens = estimatedTotal,
        )
    }

    fun estimateTextTokens(text: String): Int = when {
        text.isEmpty() -> 0
        else -> ceil(text.length.toDouble() / GenerationBudget.CHARS_PER_TOKEN).toInt()
    }

    private fun estimateMessageTokens(text: String): Int =
        MESSAGE_OVERHEAD_TOKENS + estimateTextTokens(text)

    private data class FittedSection(
        val text: String,
        val tokens: Int,
        val wasPresent: Boolean,
        val truncated: Boolean,
    ) {
        fun usage(section: ContextSection) = ContextSectionUsage(
            section = section,
            estimatedTokens = tokens,
            includedItems = if (text.isNotBlank()) 1 else 0,
            droppedItems = if (wasPresent && text.isBlank()) 1 else 0,
            truncated = truncated,
        )
    }

    private fun fitSection(text: String, tokenLimit: Int): FittedSection {
        if (text.isBlank()) return FittedSection("", 0, false, false)
        if (tokenLimit <= 0) return FittedSection("", 0, true, true)
        val estimated = estimateTextTokens(text)
        if (estimated <= tokenLimit) return FittedSection(text, estimated, true, false)
        val maxChars = tokenLimit * GenerationBudget.CHARS_PER_TOKEN
        var fitted = text.take(maxChars)
        if (fitted.lastOrNull()?.isHighSurrogate() == true) fitted = fitted.dropLast(1)
        fitted = fitted.trimEnd()
        return FittedSection(fitted, estimateTextTokens(fitted), true, true)
    }

    private fun TranscriptMessage.toChatMessageOrNull(): ChatMessage? {
        val message = when (role) {
            TranscriptRole.USER -> ChatMessage(ChatMessage.ROLE_USER, text)
            TranscriptRole.ASSISTANT -> ChatMessage(
                ChatMessage.ROLE_ASSISTANT,
                ReasoningOutputParser.answerForHistory(text),
            )
            TranscriptRole.TOOL -> ChatMessage(
                ChatMessage.ROLE_USER,
                "[tool result] ${if (text.isBlank()) summary.orEmpty() else text}",
            )
        }
        return message.takeIf { it.content.isNotBlank() }
    }
}
