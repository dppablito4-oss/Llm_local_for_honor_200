package com.prismai.llmhost.model

import com.prismai.llmhost.GenerationSettings
import com.prismai.llmhost.ReasoningMode
import java.util.Locale

enum class ReasoningCapability {
    NONE,
    TOGGLEABLE,
    ALWAYS_ON,
}

data class ModelBehaviorProfile(
    val key: String,
    val reasoningCapability: ReasoningCapability = ReasoningCapability.NONE,
    val omitSystemPrompt: Boolean = false,
    val thinkingCommand: String? = null,
    val normalCommand: String? = null,
    val userInstructionSuffix: String? = null,
) {
    fun isReasoningEnabled(mode: ReasoningMode): Boolean = when (reasoningCapability) {
        ReasoningCapability.NONE -> false
        ReasoningCapability.TOGGLEABLE -> mode == ReasoningMode.THINKING
        ReasoningCapability.ALWAYS_ON -> true
    }

    fun prepareUserPrompt(prompt: String, mode: ReasoningMode, agentEnabled: Boolean): String {
        if (prompt.isBlank()) return prompt
        val instructedPrompt = appendInstruction(prompt, userInstructionSuffix)
        if (agentEnabled && reasoningCapability == ReasoningCapability.TOGGLEABLE) {
            return appendCommand(instructedPrompt, normalCommand)
        }
        return when {
            isReasoningEnabled(mode) -> appendCommand(instructedPrompt, thinkingCommand)
            reasoningCapability == ReasoningCapability.TOGGLEABLE -> appendCommand(instructedPrompt, normalCommand)
            else -> instructedPrompt
        }
    }

    /**
     * Reasoning models frequently spend hundreds of tokens before their final answer.
     * Apply the model-author recommendations for a reasoning turn without mutating
     * the persisted settings chosen for normal chat.
     */
    fun effectiveSettings(settings: GenerationSettings, agentEnabled: Boolean): GenerationSettings {
        if (agentEnabled || !isReasoningEnabled(settings.reasoningMode)) return settings
        return settings.copy(
            maxTokens = maxOf(settings.maxTokens, RECOMMENDED_REASONING_MAX_TOKENS),
            temperature = 0.60f,
            topK = 20,
            topP = 0.95f,
        ).clamped()
    }

    fun recommendedSettings(settings: GenerationSettings): GenerationSettings {
        val enableThinking = reasoningCapability != ReasoningCapability.NONE
        return settings.copy(
            reasoningMode = if (enableThinking) ReasoningMode.THINKING else ReasoningMode.NORMAL,
            contextLength = if (enableThinking) maxOf(settings.contextLength, RECOMMENDED_REASONING_CONTEXT) else settings.contextLength,
            maxTokens = if (enableThinking) maxOf(settings.maxTokens, RECOMMENDED_REASONING_MAX_TOKENS) else settings.maxTokens,
            temperature = if (enableThinking) 0.60f else settings.temperature,
            topK = if (enableThinking) 20 else settings.topK,
            topP = if (enableThinking) 0.95f else settings.topP,
        ).clamped()
    }

    /** Selects normal/thinking defaults for one hybrid GGUF without reloading it. */
    fun settingsForMode(settings: GenerationSettings, mode: ReasoningMode): GenerationSettings {
        val selected = settings.copy(reasoningMode = mode)
        return if (mode == ReasoningMode.THINKING) {
            recommendedSettings(selected)
        } else {
            selected.copy(
                temperature = 0.70f,
                topK = 20,
                topP = 0.80f,
            ).clamped()
        }
    }

    private fun appendCommand(prompt: String, command: String?): String {
        if (command.isNullOrBlank() || prompt.trimEnd().endsWith(command)) return prompt
        return "${prompt.trimEnd()}\n\n$command"
    }

    private fun appendInstruction(prompt: String, instruction: String?): String {
        if (instruction.isNullOrBlank() || instruction in prompt) return prompt
        return "${prompt.trimEnd()}\n\n$instruction"
    }

    companion object {
        const val RECOMMENDED_REASONING_CONTEXT = 4096
        const val RECOMMENDED_REASONING_MAX_TOKENS = 768
    }
}

object ModelBehaviorProfiles {
    private val STANDARD = ModelBehaviorProfile(key = "standard")
    private val QWEN3_HYBRID = ModelBehaviorProfile(
        key = "qwen3_hybrid",
        reasoningCapability = ReasoningCapability.TOGGLEABLE,
        thinkingCommand = "/think",
        normalCommand = "/no_think",
    )
    private val DEEPSEEK_R1 = ModelBehaviorProfile(
        key = "deepseek_r1",
        reasoningCapability = ReasoningCapability.ALWAYS_ON,
        omitSystemPrompt = true,
        userInstructionSuffix = "Responde en español. Razona brevemente, evita repetir ideas y termina con una respuesta final clara y directa.",
    )
    private val QWEN3_THINKING_ONLY = ModelBehaviorProfile(
        key = "qwen3_thinking_only",
        reasoningCapability = ReasoningCapability.ALWAYS_ON,
    )
    private val QWEN3_INSTRUCT_ONLY = ModelBehaviorProfile(
        key = "qwen3_instruct_only",
        reasoningCapability = ReasoningCapability.NONE,
    )

    fun resolve(modelId: String?): ModelBehaviorProfile {
        val normalized = modelId.orEmpty().lowercase(Locale.US)
        return when {
            "deepseek-r1" in normalized || "deepseek_r1" in normalized -> DEEPSEEK_R1
            "qwen3" in normalized && "thinking" in normalized -> QWEN3_THINKING_ONLY
            "qwen3" in normalized && "instruct" in normalized -> QWEN3_INSTRUCT_ONLY
            "qwen3" in normalized -> QWEN3_HYBRID
            else -> STANDARD
        }
    }
}

data class ParsedReasoningOutput(
    val reasoning: String,
    val answer: String,
    val hasReasoning: Boolean,
    val reasoningComplete: Boolean,
)

object ReasoningOutputParser {
    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"

    fun parse(text: String): ParsedReasoningOutput {
        val openIndex = text.indexOf(OPEN, ignoreCase = true)
        val closeIndex = text.indexOf(CLOSE, ignoreCase = true)

        if (openIndex < 0 && closeIndex < 0) {
            return ParsedReasoningOutput("", text, hasReasoning = false, reasoningComplete = true)
        }

        if (openIndex < 0 && closeIndex >= 0) {
            return ParsedReasoningOutput(
                reasoning = text.substring(0, closeIndex).trim(),
                answer = text.substring(closeIndex + CLOSE.length).trimStart(),
                hasReasoning = true,
                reasoningComplete = true,
            )
        }

        val reasoningStart = openIndex + OPEN.length
        if (closeIndex < reasoningStart) {
            return ParsedReasoningOutput(
                reasoning = text.substring(reasoningStart).trim(),
                answer = text.substring(0, openIndex).trim(),
                hasReasoning = true,
                reasoningComplete = false,
            )
        }

        val prefix = text.substring(0, openIndex).trim()
        val suffix = text.substring(closeIndex + CLOSE.length).trimStart()
        val answer = listOf(prefix, suffix).filter { it.isNotBlank() }.joinToString("\n\n")
        return ParsedReasoningOutput(
            reasoning = text.substring(reasoningStart, closeIndex).trim(),
            answer = answer,
            hasReasoning = true,
            reasoningComplete = true,
        )
    }

    /** Keep private chain-of-thought out of future turns while retaining the final answer. */
    fun answerForHistory(text: String): String {
        val parsed = parse(text)
        return when {
            !parsed.hasReasoning -> text
            parsed.reasoningComplete -> parsed.answer
            else -> ""
        }.trim()
    }
}
