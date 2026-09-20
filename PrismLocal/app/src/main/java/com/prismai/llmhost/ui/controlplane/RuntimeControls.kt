package com.prismai.llmhost.ui.controlplane
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prismai.llmhost.DeviceCapabilityProfile
import com.prismai.llmhost.GenerationPerformance
import com.prismai.llmhost.GenerationSettings
import com.prismai.llmhost.ui.components.*
import com.prismai.llmhost.ui.theme.*
import com.prismai.llmhost.ui.formatTokensPerSecond
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun RuntimeControls(
    settings: GenerationSettings,
    performance: GenerationPerformance?,
    enabled: Boolean,
    deviceCapabilityProfile: DeviceCapabilityProfile?,
    currentModel: String?,
    onSettingsChange: (GenerationSettings) -> Unit,
) {
    var advancedVisible by remember { mutableStateOf(false) }
    val behavior = remember(currentModel) { ModelBehaviorProfiles.resolve(currentModel) }
    val supportsReasoning = behavior.reasoningCapability != ReasoningCapability.NONE
    val reasoningEnabled = behavior.isReasoningEnabled(settings.reasoningMode)
    DashboardCard {
        SectionHeader(
            title = "Motor de inferencia",
            subtitle = "Ajusta la longitud, velocidad y estilo de las respuestas",
        )
        SettingSlider(
            label = "Tokens máximos",
            valueText = settings.maxTokens.toString(),
            description = "Límite de longitud de cada respuesta. Un valor mayor permite respuestas más largas, pero tarda más.",
            value = settings.maxTokens.toFloat(),
            valueRange = GenerationSettings.MIN_MAX_TOKENS.toFloat()..GenerationSettings.MAX_MAX_TOKENS.toFloat(),
            steps = 15,
            enabled = enabled,
            onValueChange = { value ->
                onSettingsChange(settings.copy(maxTokens = snapTokens(value)))
            },
        )
        SettingSlider(
            label = "Hilos de CPU",
            valueText = settings.threadCount.toString(),
            description = "Núcleos usados en paralelo. En este Honor 200, 4 hilos suele dar el mejor equilibrio entre velocidad y temperatura.",
            value = settings.threadCount.toFloat(),
            valueRange = GenerationSettings.MIN_THREAD_COUNT.toFloat()..GenerationSettings.MAX_THREAD_COUNT.toFloat(),
            steps = GenerationSettings.MAX_THREAD_COUNT - GenerationSettings.MIN_THREAD_COUNT - 1,
            enabled = enabled,
            onValueChange = { value ->
                onSettingsChange(settings.copy(threadCount = value.roundToInt()))
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Modo de razonamiento",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when (behavior.reasoningCapability) {
                        ReasoningCapability.TOGGLEABLE ->
                            "Qwen3 puede alternar entre chat rápido y thinking. Al activarlo se usan 768 tokens de salida como mínimo."
                        ReasoningCapability.ALWAYS_ON ->
                            if (behavior.key == "deepseek_r1") {
                                "Thinking siempre activo. La versión 1.5B es experimental: puede divagar o mezclar idiomas; para chat factual usa Qwen3."
                            } else {
                                "Este modelo razona siempre. Se recomienda contexto 4096 y al menos 768 tokens de salida."
                            }
                        ReasoningCapability.NONE ->
                            "El modelo seleccionado no declara un modo thinking compatible."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (behavior.reasoningCapability == ReasoningCapability.TOGGLEABLE) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReasoningModeChoice(
                        label = "Normal",
                        selected = settings.reasoningMode == ReasoningMode.NORMAL,
                        enabled = enabled,
                        onClick = {
                            onSettingsChange(behavior.settingsForMode(settings, ReasoningMode.NORMAL))
                        },
                    )
                    ReasoningModeChoice(
                        label = "Thinking",
                        selected = settings.reasoningMode == ReasoningMode.THINKING,
                        enabled = enabled,
                        onClick = {
                            onSettingsChange(behavior.settingsForMode(settings, ReasoningMode.THINKING))
                        },
                    )
                }
            } else {
                Switch(
                    checked = reasoningEnabled,
                    onCheckedChange = null,
                    enabled = false,
                )
            }
        }
        if (supportsReasoning && (
                settings.contextLength < ModelBehaviorProfile.RECOMMENDED_REASONING_CONTEXT ||
                    settings.maxTokens < ModelBehaviorProfile.RECOMMENDED_REASONING_MAX_TOKENS
                )
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = PrismViolet.copy(alpha = 0.10f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, PrismViolet.copy(alpha = 0.30f)),
                onClick = {
                    if (enabled) onSettingsChange(behavior.recommendedSettings(settings))
                },
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(
                        text = "Aplicar perfil recomendado",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Contexto 4096 · salida 768 · temperatura 0.60 · Top P 0.95 · Top K 20",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Herramientas del agente",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Permite que el modelo use funciones locales del teléfono. Las acciones sensibles requieren confirmación.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.agentEnabled,
                onCheckedChange = { isChecked ->
                    onSettingsChange(settings.copy(agentEnabled = isChecked))
                },
                enabled = enabled,
            )
        }
        AnimatedVisibility(visible = settings.agentEnabled) {
            Column {
                SettingSlider(
                    label = "Iteraciones del agente",
                    valueText = settings.maxAgentIterations.toString(),
                    description = "Cantidad máxima de acciones consecutivas antes de detenerse automáticamente.",
                    value = settings.maxAgentIterations.toFloat(),
                    valueRange = GenerationSettings.MIN_MAX_AGENT_ITERATIONS.toFloat()..GenerationSettings.MAX_MAX_AGENT_ITERATIONS.toFloat(),
                    steps = GenerationSettings.MAX_MAX_AGENT_ITERATIONS - GenerationSettings.MIN_MAX_AGENT_ITERATIONS - 1,
                    enabled = enabled,
                    onValueChange = { value ->
                        onSettingsChange(settings.copy(maxAgentIterations = value.roundToInt()))
                    },
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = PrismGlass.copy(alpha = 0.32f),
            contentColor = PrismText,
            border = BorderStroke(1.dp, PrismGlassBorder.copy(alpha = 0.28f)),
            onClick = { advancedVisible = !advancedVisible },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = "Configuración avanzada",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (advancedVisible) "Ocultar" else "Mostrar",
                    style = MaterialTheme.typography.labelSmall,
                    color = PrismBlue,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (advancedVisible) "⌃" else "⌄",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (advancedVisible) {
            val maxContextLimit = when {
                deviceCapabilityProfile == null -> GenerationSettings.MAX_CONTEXT_LENGTH
                deviceCapabilityProfile.totalRamBytes < 6L * 1024L * 1024L * 1024L -> 4096
                deviceCapabilityProfile.totalRamBytes < 8L * 1024L * 1024L * 1024L -> 8192
                else -> GenerationSettings.MAX_CONTEXT_LENGTH
            }
            SettingSlider(
                label = "Contexto",
                valueText = settings.contextLength.toString(),
                description = "Cantidad de conversación que el modelo puede recordar. Más contexto consume más RAM y recarga el modelo.",
                value = minOf(settings.contextLength, maxContextLimit).toFloat(),
                valueRange = GenerationSettings.MIN_CONTEXT_LENGTH.toFloat()..maxContextLimit.toFloat(),
                steps = ((maxContextLimit - GenerationSettings.MIN_CONTEXT_LENGTH) / GenerationSettings.CONTEXT_LENGTH_STEP) - 1,
                enabled = enabled,
                onValueChange = { value ->
                    onSettingsChange(settings.copy(contextLength = snapStep(value, GenerationSettings.CONTEXT_LENGTH_STEP).coerceAtMost(maxContextLimit)))
                },
            )
            SettingSlider(
                label = "Tamaño de lote",
                valueText = settings.batchSize.toString(),
                description = "Bloques usados para leer el prompt. Un lote mayor puede acelerar la entrada, pero utiliza más memoria.",
                value = settings.batchSize.toFloat(),
                valueRange = GenerationSettings.MIN_BATCH_SIZE.toFloat()..GenerationSettings.MAX_BATCH_SIZE.toFloat(),
                steps = ((GenerationSettings.MAX_BATCH_SIZE - GenerationSettings.MIN_BATCH_SIZE) / GenerationSettings.BATCH_SIZE_STEP) - 1,
                enabled = enabled,
                onValueChange = { value ->
                    onSettingsChange(settings.copy(batchSize = snapStep(value, GenerationSettings.BATCH_SIZE_STEP)))
                },
            )
            SettingSlider(
                label = "Temperatura",
                valueText = String.format(Locale.US, "%.2f", settings.temperature),
                description = "Controla la creatividad. Bajo = más preciso y repetible; alto = más variado.",
                value = settings.temperature,
                valueRange = GenerationSettings.MIN_TEMPERATURE..GenerationSettings.MAX_TEMPERATURE,
                steps = 28,
                enabled = enabled,
                onValueChange = { value ->
                    onSettingsChange(settings.copy(temperature = value))
                },
            )
            SettingSlider(
                label = "Top P",
                valueText = String.format(Locale.US, "%.2f", settings.topP),
                description = "Limita las opciones a las palabras que acumulan esta probabilidad. Un valor menor hace la respuesta más enfocada.",
                value = settings.topP,
                valueRange = GenerationSettings.MIN_TOP_P..GenerationSettings.MAX_TOP_P,
                steps = 18,
                enabled = enabled,
                onValueChange = { value ->
                    onSettingsChange(settings.copy(topP = value))
                },
            )
            SettingSlider(
                label = "Top K",
                valueText = settings.topK.toString(),
                description = "Número máximo de palabras candidatas en cada paso. Menos opciones producen respuestas más conservadoras.",
                value = settings.topK.toFloat(),
                valueRange = GenerationSettings.MIN_TOP_K.toFloat()..GenerationSettings.MAX_TOP_K.toFloat(),
                steps = GenerationSettings.MAX_TOP_K - GenerationSettings.MIN_TOP_K - 1,
                enabled = enabled,
                onValueChange = { value ->
                    onSettingsChange(settings.copy(topK = value.roundToInt()))
                },
            )
            SettingSlider(
                label = "Penalización de repetición",
                valueText = String.format(Locale.US, "%.2f", settings.repeatPenalty),
                description = "Reduce frases repetidas. Si se eleva demasiado, el texto puede perder naturalidad.",
                value = settings.repeatPenalty,
                valueRange = GenerationSettings.MIN_REPEAT_PENALTY..GenerationSettings.MAX_REPEAT_PENALTY,
                steps = 49,
                enabled = enabled,
                onValueChange = { value ->
                    onSettingsChange(settings.copy(repeatPenalty = value))
                },
            )
            Text(
                text = "Aceleración activa: CPU optimizada con KleidiAI. El control de capas GPU se ocultó porque Vulkan está desactivado en esta compilación y no tendría efecto.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
        performance?.let { stats ->
            MetricGrid(
                listOf(
                    "Velocidad" to "${formatTokensPerSecond(stats.tokensPerSecond)} tok/s",
                    "Generados" to "${stats.generatedTokens} tok",
                    "Tiempo total" to "${stats.totalMs} ms${stats.terminalSuffix()}",
                    "Prompt/generación" to "${stats.promptEvalMs}/${stats.decodeMs} ms",
                )
            )
        }
    }
}

private fun snapTokens(value: Float): Int {
    val step = GenerationSettings.MAX_TOKEN_STEP
    val snapped = (value / step).roundToInt() * step
    return snapped.coerceIn(GenerationSettings.MIN_MAX_TOKENS, GenerationSettings.MAX_MAX_TOKENS)
}

private fun snapStep(value: Float, step: Int): Int =
    ((value / step).roundToInt() * step)

@Composable
private fun ReasoningModeChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (selected) PrismViolet.copy(alpha = 0.22f) else PrismGlass.copy(alpha = 0.28f),
        contentColor = if (selected) PrismText else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(
            1.dp,
            if (selected) PrismViolet.copy(alpha = 0.65f) else PrismGlassBorder.copy(alpha = 0.30f),
        ),
        onClick = { if (enabled) onClick() },
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (enabled) androidx.compose.ui.graphics.Color.Unspecified
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}

@Composable
private fun SettingSlider(
    label: String,
    valueText: String,
    description: String? = null,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            InfoBadge(text = valueText, color = PrismBlue)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
        )
        description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

private fun GenerationPerformance.terminalSuffix(): String =
    terminalReason?.let { " | $it" }.orEmpty()
