package com.prismai.llmhost.generation

import android.util.Log
import com.prismai.llmhost.ChatSession
import com.prismai.llmhost.GenerationSettings
import com.prismai.llmhost.ReasoningMode
import com.prismai.llmhost.TranscriptMessage
import com.prismai.llmhost.TranscriptRole
import com.prismai.llmhost.bridge.ChatMessage
import com.prismai.llmhost.bridge.NativeLlmBridge
import com.prismai.llmhost.chat.ChatManager
import com.prismai.llmhost.model.ModelBehaviorProfiles
import com.prismai.llmhost.model.ReasoningOutputParser
import kotlinx.coroutines.flow.collect
import kotlin.math.max

/**
 * Creates and persists an incremental summary of old chat turns.
 *
 * The original messages remain untouched in Room. Only the boundary stored in
 * [ChatSession.summaryUntilMessageId] changes what is packed into a future
 * model prompt; the UI can therefore continue showing the complete transcript.
 */
class ConversationSummarizer(
    private val engine: NativeLlmBridge,
    private val chatManager: ChatManager,
) {
    data class SummaryPlan(
        val chatId: String,
        val previousSummary: String?,
        val messages: List<TranscriptMessage>,
        val untilMessageId: Long,
    )

    data class SummaryResult(
        val persisted: Boolean,
        val terminalReason: String,
        val summary: String = "",
    )

    fun plan(
        session: ChatSession,
        transcript: List<TranscriptMessage>,
        preparedContext: PreparedContext,
    ): SummaryPlan? = createPlan(session, transcript, preparedContext)

    suspend fun summarize(
        plan: SummaryPlan,
        modelId: String?,
        baseSettings: GenerationSettings,
    ): SummaryResult {
        val behavior = ModelBehaviorProfiles.resolve(modelId)
        val request = behavior.prepareUserPrompt(
            prompt = buildPrompt(plan),
            mode = ReasoningMode.NORMAL,
            agentEnabled = false,
        )
        val messages = buildList {
            if (!behavior.omitSystemPrompt) {
                add(ChatMessage(ChatMessage.ROLE_SYSTEM, SYSTEM_PROMPT))
            }
            add(ChatMessage(ChatMessage.ROLE_USER, request))
        }
        val settings = baseSettings.copy(
            maxTokens = SUMMARY_OUTPUT_TOKENS,
            temperature = 0.20f,
            topK = 20,
            topP = 0.90f,
            repeatPenalty = max(1.05f, baseSettings.repeatPenalty),
            reasoningMode = ReasoningMode.NORMAL,
            agentEnabled = false,
        ).clamped()

        val output = StringBuilder()
        var terminalReason = "NONE"
        return try {
            engine.generateChat(messages, settings).collect { chunk ->
                if (!chunk.isTerminal) output.append(chunk.text)
                else terminalReason = chunk.terminalReason
            }
            val summary = ReasoningOutputParser.answerForHistory(output.toString()).trim()
            if (terminalReason != "EOF" || summary.length < MIN_SUMMARY_CHARS) {
                Log.w(TAG, "rolling summary rejected reason=$terminalReason chars=${summary.length}")
                SummaryResult(false, terminalReason, summary)
            } else {
                val persisted = chatManager.updateSummary(
                    chatId = plan.chatId,
                    summary = summary,
                    untilId = plan.untilMessageId,
                )
                SummaryResult(persisted, terminalReason, summary)
            }
        } finally {
            runCatching { engine.resetConversation() }
                .onFailure { Log.w(TAG, "failed to reset native context after rolling summary", it) }
        }
    }

    companion object {
        private const val TAG = "ConversationSummarizer"
        private const val RECENT_MESSAGES_TO_KEEP = 8
        private const val MIN_MESSAGES_PER_SUMMARY = 2
        private const val SUMMARY_OUTPUT_TOKENS = 384
        private const val SUMMARY_PROMPT_HEADROOM_TOKENS = 256
        private const val MIN_SOURCE_TOKENS = 256
        private const val MIN_SUMMARY_CHARS = 24
        private const val TRIGGER_PERCENT = 75

        private const val SYSTEM_PROMPT =
            "Resume conversaciones con fidelidad. Conserva hechos, decisiones, preferencias, " +
                "nombres, cifras y asuntos pendientes. No inventes nada. Responde solo con el resumen en español."

        fun createPlan(
            session: ChatSession,
            transcript: List<TranscriptMessage>,
            preparedContext: PreparedContext,
        ): SummaryPlan? {
        if (!shouldSummarize(preparedContext)) return null

        val unsummarized = transcript.filter { message ->
            message.text.isNotBlank() &&
                (session.summaryUntilMessageId == null || message.id > session.summaryUntilMessageId)
        }
        if (unsummarized.size <= RECENT_MESSAGES_TO_KEEP + 1) return null

        val summarizable = unsummarized.dropLast(RECENT_MESSAGES_TO_KEEP)
        if (summarizable.size < MIN_MESSAGES_PER_SUMMARY) return null

        val outputTokens = SUMMARY_OUTPUT_TOKENS.coerceAtMost(
            (preparedContext.contextLength / 3).coerceAtLeast(GenerationSettings.MIN_MAX_TOKENS),
        )
        val sourceBudget = (
            preparedContext.contextLength - outputTokens - SUMMARY_PROMPT_HEADROOM_TOKENS
        ).coerceAtLeast(MIN_SOURCE_TOKENS)
        var usedTokens = ContextBuilder.estimateTextTokens(session.summary.orEmpty())
        val selected = ArrayList<TranscriptMessage>()
        for (message in summarizable) {
            val tokens = ContextBuilder.estimateTextTokens(formatMessage(message)) + 4
            if (selected.isNotEmpty() && usedTokens + tokens > sourceBudget) break
            selected += message
            usedTokens += tokens
        }
        if (selected.size < MIN_MESSAGES_PER_SUMMARY) return null

        return SummaryPlan(
            chatId = session.id,
            previousSummary = session.summary,
            messages = selected,
            untilMessageId = selected.last().id,
        )
        }

        fun shouldSummarize(context: PreparedContext): Boolean =
            context.droppedHistoryMessages > 0 ||
                (
                    context.promptTokenBudget > 0 &&
                        context.totalPromptTokens.toLong() * 100L >=
                        context.promptTokenBudget.toLong() * TRIGGER_PERCENT
                )

        fun buildPrompt(plan: SummaryPlan): String = buildString {
            appendLine("Actualiza el resumen acumulativo usando los mensajes nuevos de abajo.")
            appendLine("No omitas datos que puedan ser necesarios en preguntas futuras.")
            appendLine()
            appendLine("RESUMEN ANTERIOR:")
            appendLine(plan.previousSummary?.takeIf { it.isNotBlank() } ?: "(ninguno)")
            appendLine()
            appendLine("MENSAJES NUEVOS:")
            plan.messages.forEach { appendLine(formatMessage(it)) }
            append("RESUMEN ACTUALIZADO:")
        }

        private fun formatMessage(message: TranscriptMessage): String = when (message.role) {
            TranscriptRole.USER -> "Usuario: ${message.text}"
            TranscriptRole.ASSISTANT ->
                "Asistente: ${ReasoningOutputParser.answerForHistory(message.text)}"
            TranscriptRole.TOOL -> "Herramienta: ${message.summary ?: message.text}"
        }
    }
}
