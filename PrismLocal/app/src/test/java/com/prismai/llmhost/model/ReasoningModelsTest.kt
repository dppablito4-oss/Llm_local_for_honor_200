package com.prismai.llmhost.model

import com.prismai.llmhost.GenerationSettings
import com.prismai.llmhost.ReasoningMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningModelsTest {
    @Test
    fun resolvesSupportedModelFamilies() {
        assertEquals(ReasoningCapability.TOGGLEABLE, ModelBehaviorProfiles.resolve("Qwen3-1.7B-Q4_K_M.gguf").reasoningCapability)
        assertEquals(ReasoningCapability.ALWAYS_ON, ModelBehaviorProfiles.resolve("DeepSeek-R1-Distill-Qwen-1.5B.gguf").reasoningCapability)
        assertEquals(ReasoningCapability.NONE, ModelBehaviorProfiles.resolve("Qwen2.5-1.5B.gguf").reasoningCapability)
    }

    @Test
    fun qwen3AddsTheSelectedCommandOnlyOnce() {
        val profile = ModelBehaviorProfiles.resolve("Qwen3-1.7B")
        assertEquals("Pregunta\n\n/think", profile.prepareUserPrompt("Pregunta", ReasoningMode.THINKING, false))
        assertEquals("Pregunta\n\n/no_think", profile.prepareUserPrompt("Pregunta", ReasoningMode.NORMAL, false))
        assertEquals("Pregunta /think", profile.prepareUserPrompt("Pregunta /think", ReasoningMode.THINKING, false))
    }

    @Test
    fun deepSeekIsAlwaysReasoningAndOmitsSystemPrompt() {
        val profile = ModelBehaviorProfiles.resolve("deepseek-r1-distill-qwen")
        assertTrue(profile.omitSystemPrompt)
        assertTrue(profile.isReasoningEnabled(ReasoningMode.NORMAL))
        val prepared = profile.prepareUserPrompt("¿Cuál es el tercer planeta?", ReasoningMode.THINKING, false)
        assertTrue("Responde en español" in prepared)
        assertTrue("Razona brevemente" in prepared)
    }

    @Test
    fun reasoningUsesSafeMinimumOutputAndRecommendedSampling() {
        val profile = ModelBehaviorProfiles.resolve("qwen3-1.7b")
        val effective = profile.effectiveSettings(
            GenerationSettings(maxTokens = 128, reasoningMode = ReasoningMode.THINKING),
            agentEnabled = false,
        )
        assertEquals(1024, effective.maxTokens)
        assertEquals(0.60f, effective.temperature, 0f)
        assertEquals(20, effective.topK)
        assertEquals(0.95f, effective.topP, 0f)
        assertEquals(1.15f, effective.repeatPenalty, 0f)
    }

    @Test
    fun outputLimitsMatchHonorProfileAndKeepPromptHeadroom() {
        assertEquals(512, GenerationSettings.DEFAULT_MAX_TOKENS)
        assertEquals(2048, GenerationSettings.MAX_MAX_TOKENS)
        assertEquals(64, GenerationSettings.MAX_TOKEN_STEP)
        assertEquals(1024, GenerationSettings.maxUiOutputTokensForContext(2048))
        assertEquals(2048, GenerationSettings.maxUiOutputTokensForContext(3072))
        assertEquals(2048, GenerationSettings.maxUiOutputTokensForContext(4096))
    }

    @Test
    fun qwen3ModesSelectDifferentProfilesWithoutChangingTheGguf() {
        val profile = ModelBehaviorProfiles.resolve("Qwen3-1.7B-Q4_K_M.gguf")
        val base = GenerationSettings(
            maxTokens = 256,
            contextLength = 2048,
            temperature = 1.10f,
            topK = 40,
            topP = 0.50f,
        )

        val thinking = profile.settingsForMode(base, ReasoningMode.THINKING)
        val normal = profile.settingsForMode(thinking, ReasoningMode.NORMAL)

        assertEquals(ReasoningMode.THINKING, thinking.reasoningMode)
        assertEquals(4096, thinking.contextLength)
        assertEquals(1024, thinking.maxTokens)
        assertEquals(0.60f, thinking.temperature, 0f)
        assertEquals(0.95f, thinking.topP, 0f)
        assertEquals("pregunta\n\n/think", profile.prepareUserPrompt("pregunta", thinking.reasoningMode, false))

        assertEquals(ReasoningMode.NORMAL, normal.reasoningMode)
        assertEquals(0.70f, normal.temperature, 0f)
        assertEquals(0.80f, normal.topP, 0f)
        assertEquals("pregunta\n\n/no_think", profile.prepareUserPrompt("pregunta", normal.reasoningMode, false))
    }

    @Test
    fun parserSeparatesCompletedReasoningFromAnswer() {
        val parsed = ReasoningOutputParser.parse("<think>paso uno</think>La respuesta es 4.")
        assertTrue(parsed.hasReasoning)
        assertTrue(parsed.reasoningComplete)
        assertEquals("paso uno", parsed.reasoning)
        assertEquals("La respuesta es 4.", parsed.answer)
        assertEquals("La respuesta es 4.", ReasoningOutputParser.answerForHistory("<think>paso uno</think>La respuesta es 4."))
    }

    @Test
    fun parserMarksStreamingReasoningAsIncomplete() {
        val parsed = ReasoningOutputParser.parse("<think>todavía pensando")
        assertTrue(parsed.hasReasoning)
        assertFalse(parsed.reasoningComplete)
        assertEquals("todavía pensando", parsed.reasoning)
        assertEquals("", parsed.answer)
    }

    @Test
    fun disclosureAutoExpandsOnlyDuringLiveThinkingAndHonorsManualChoice() {
        val streaming = ReasoningOutputParser.parse("<think>todavía pensando")
        val completed = ReasoningOutputParser.parse("<think>listo</think>Respuesta")

        assertTrue(ReasoningDisclosurePolicy.isExpanded(null, streaming, showLoading = true))
        assertFalse(ReasoningDisclosurePolicy.isExpanded(null, completed, showLoading = true))
        assertFalse(ReasoningDisclosurePolicy.isExpanded(null, streaming, showLoading = false))
        assertFalse(ReasoningDisclosurePolicy.isExpanded(false, streaming, showLoading = true))
        assertTrue(ReasoningDisclosurePolicy.isExpanded(true, completed, showLoading = false))
    }
}
