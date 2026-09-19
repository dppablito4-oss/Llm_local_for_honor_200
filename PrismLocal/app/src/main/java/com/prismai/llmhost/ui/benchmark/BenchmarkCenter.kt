package com.prismai.llmhost.ui.benchmark
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prismai.llmhost.BenchmarkRun
import com.prismai.llmhost.BenchmarkPreset
import com.prismai.llmhost.BenchmarkStatus
import com.prismai.llmhost.DeviceCapabilityProfile
import com.prismai.llmhost.ModelReadiness
import com.prismai.llmhost.benchmark.BenchmarkRunAnalytics
import com.prismai.llmhost.model.ModelTierHints
import com.prismai.llmhost.ui.components.*
import com.prismai.llmhost.ui.theme.*
import com.prismai.llmhost.ui.chat.ChatOverflowButton
import com.prismai.llmhost.ui.*
import java.util.Locale

private enum class BenchmarkTab(val label: String) {
    Runs("Ejecuciones"),
    Compare("Comparar"),
    Models("Modelos"),
}

/** UI-facing summary; averages exclude ERROR / QUALITY_ABORT / INTERRUPTED / CANCELLED (B6). */
private data class BenchmarkSummary(
    val totalCount: Int,
    val completedCount: Int,
    val cleanCount: Int,
    val truncatedCount: Int,
    val errorCount: Int,
    val qualityAbortCount: Int,
    val interruptedCount: Int,
    val cancelledCount: Int,
    /** Successful-run average only (EOF / MAX_TOKENS with tokens + decodeMs). */
    val avgCompletedTokensPerSecond: Double?,
    val bestCompletedTokensPerSecond: Double?,
    val avgPromptMs: Long?,
    val avgTotalMs: Long?,
) {
    val failedCount: Int
        get() = errorCount + interruptedCount + qualityAbortCount + cancelledCount

    val reliabilityScore: Double?
        get() = avgCompletedTokensPerSecond?.let { average ->
            if (totalCount == 0) {
                average
            } else {
                average * ((cleanCount + truncatedCount).toDouble() / totalCount.toDouble())
            }
        }

    companion object {
        val Empty = BenchmarkSummary(
            totalCount = 0,
            completedCount = 0,
            cleanCount = 0,
            truncatedCount = 0,
            errorCount = 0,
            qualityAbortCount = 0,
            interruptedCount = 0,
            cancelledCount = 0,
            avgCompletedTokensPerSecond = null,
            bestCompletedTokensPerSecond = null,
            avgPromptMs = null,
            avgTotalMs = null,
        )

        fun fromAnalytics(s: BenchmarkRunAnalytics.Summary): BenchmarkSummary =
            BenchmarkSummary(
                totalCount = s.totalCount,
                completedCount = s.completedCount,
                cleanCount = s.cleanCount,
                truncatedCount = s.truncatedCount,
                errorCount = s.errorCount,
                qualityAbortCount = s.qualityAbortCount,
                interruptedCount = s.interruptedCount,
                cancelledCount = s.cancelledCount,
                avgCompletedTokensPerSecond = s.avgCompletedTokensPerSecond,
                bestCompletedTokensPerSecond = s.bestCompletedTokensPerSecond,
                avgPromptMs = s.avgPromptMs,
                avgTotalMs = s.avgTotalMs,
            )
    }
}

private data class BenchmarkComparisonRow(
    val modelId: String,
    val run: BenchmarkRun?,
    val summary: BenchmarkSummary,
    val readiness: ModelReadiness?,
)

private data class BenchmarkAction(
    val label: String,
    val presetId: String? = null,
    val onClick: () -> Unit,
)

private enum class BenchmarkRunStatus(
    val label: String,
    val color: Color,
) {
    Clean("Correcta", PrismGreen),
    Truncated("Recortada", PrismAmber),
    QualityAbort("Calidad", PrismAmber),
    Error("Error", PrismRed),
    Interrupted("Interrumpida", PrismAmber),
    Partial("Parcial", PrismBlue),
    Unknown("Desconocida", PrismSlate),
}

@Composable
internal fun BenchmarkCenter(
    models: List<String>,
    activeModelInfo: ModelStorageManager.ActiveModelInfo?,
    deviceCapabilityProfile: DeviceCapabilityProfile?,
    runs: List<BenchmarkRun>,
    readiness: List<ModelReadiness>,
    presets: List<BenchmarkPreset>,
    status: BenchmarkStatus,
    isGenerating: Boolean,
    disabledReason: String?,
    onRunPreset: (String) -> Unit,
    onRunThreadSweep: () -> Unit,
    onRunNativeBenchmark: () -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    onClear: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(BenchmarkTab.Runs) }
    var exportMenuExpanded by remember { mutableStateOf(false) }
    val allSummary = benchmarkSummary(runs)
    val currentModelId = activeModelInfo?.id
    val currentSummary = remember(runs, currentModelId) {
        currentModelId
            ?.let { modelId -> benchmarkSummary(runs.filter { it.modelId == modelId }) }
            ?: BenchmarkSummary.Empty
    }
    val enabled = disabledReason == null

    DashboardCard {
        SectionHeader(
            title = "Pruebas de rendimiento",
            subtitle = "Velocidad, estabilidad y comparación de modelos locales",
            action = {
                Box {
                    ChatOverflowButton(
                        enabled = runs.isNotEmpty() && !isGenerating && !status.isRunning,
                        onClick = { exportMenuExpanded = true },
                    )
                    DropdownMenu(
                        expanded = exportMenuExpanded,
                        onDismissRequest = { exportMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Exportar CSV") },
                            enabled = runs.isNotEmpty() && !isGenerating && !status.isRunning,
                            onClick = {
                                exportMenuExpanded = false
                                onExportCsv()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Exportar JSON") },
                            enabled = runs.isNotEmpty() && !isGenerating && !status.isRunning,
                            onClick = {
                                exportMenuExpanded = false
                                onExportJson()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Borrar historial", color = MaterialTheme.colorScheme.error) },
                            enabled = runs.isNotEmpty() && !isGenerating && !status.isRunning,
                            onClick = {
                                exportMenuExpanded = false
                                onClear()
                            },
                        )
                    }
                }
            },
        )
        BenchmarkSummaryPanel(
            currentSummary = currentSummary,
            allSummary = allSummary,
            currentEmptyText = if (currentModelId == null) "No hay un modelo seleccionado" else "No hay pruebas para este modelo",
        )

        if (status.isRunning) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfinityLoadingIndicator(modifier = Modifier.size(24.dp), color = PrismViolet)
                Text(
                text = "Ejecutando ${status.presetName ?: "prueba"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PrismText,
                )
            }
        }
        disabledReason?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = PrismAmber,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BenchmarkTab.values().forEach { tab ->
                val selected = selectedTab == tab
                TextButton(
                    onClick = { selectedTab = tab },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (selected) PrismBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(tab.label)
                }
            }
        }

        when (selectedTab) {
            BenchmarkTab.Runs -> BenchmarkRunsTab(
                runs = runs,
                presets = presets,
                currentModelId = currentModelId,
                enabled = enabled,
                onRunPreset = onRunPreset,
                onRunThreadSweep = onRunThreadSweep,
                onRunNativeBenchmark = onRunNativeBenchmark,
            )
            BenchmarkTab.Compare -> BenchmarkCompareTab(
                runs = runs,
                readiness = readiness,
            )
            BenchmarkTab.Models -> BenchmarkModelsTab(
                models = models,
                activeModelInfo = activeModelInfo,
                deviceCapabilityProfile = deviceCapabilityProfile,
                readiness = readiness,
                runs = runs,
            )
        }
    }
}

@Composable
private fun BenchmarkSummaryPanel(
    currentSummary: BenchmarkSummary,
    allSummary: BenchmarkSummary,
    currentEmptyText: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BenchmarkSummaryGroup(
            title = "Modelo actual",
            summary = currentSummary,
            emptyText = currentEmptyText,
        )
        BenchmarkSummaryGroup(
            title = "Todos los modelos",
            summary = allSummary,
            emptyText = "No hay ejecuciones registradas",
        )
    }
}

@Composable
private fun BenchmarkSummaryGroup(
    title: String,
    summary: BenchmarkSummary,
    emptyText: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = PrismGlass.copy(alpha = 0.34f),
        contentColor = PrismText,
        border = BorderStroke(1.dp, PrismGlassBorder.copy(alpha = 0.30f)),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = PrismSlate,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.totalCount == 0) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                MetricGrid(
                    listOf(
            "Ejecuciones" to summary.totalCount.toString(),
            "Completadas" to summary.completedCount.toString(),
            "Fallidas" to summary.failedCount.toString(),
            "Corte por calidad" to summary.qualityAbortCount.toString(),
            "Recortadas" to summary.truncatedCount.toString(),
            "Promedio correcto" to formatOptionalTps(summary.avgCompletedTokensPerSecond),
            "Mejor" to formatOptionalTps(summary.bestCompletedTokensPerSecond),
            "Primer token prom." to formatOptionalMs(summary.avgPromptMs),
                    )
                )
                Text(
                text = "Los promedios excluyen errores, cancelaciones, cortes por calidad y pruebas interrumpidas.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun BenchmarkRunsTab(
    runs: List<BenchmarkRun>,
    presets: List<BenchmarkPreset>,
    currentModelId: String?,
    enabled: Boolean,
    onRunPreset: (String) -> Unit,
    onRunThreadSweep: () -> Unit,
    onRunNativeBenchmark: () -> Unit,
) {
    val actions = listOf(
        BenchmarkAction("Probar 2/4/6/8 hilos", onClick = onRunThreadSweep),
        BenchmarkAction("Prueba nativa PP/TG", onClick = onRunNativeBenchmark),
    ) + presets.map { preset ->
        BenchmarkAction(preset.name, presetId = preset.id) { onRunPreset(preset.id) }
    }
    var selectedActionIndex by remember(actions.size) {
        mutableIntStateOf(actions.indexOfFirst { it.label == "Python Coding" }.coerceAtLeast(0))
    }
    var successfulOnly by remember { mutableStateOf(false) }
    val selectedAction = actions.getOrNull(selectedActionIndex) ?: actions.first()
    val codingWarning = remember(selectedAction.presetId, currentModelId) {
        if (selectedAction.presetId == "coding") {
            ModelTierHints.codingBenchmarkWarning(currentModelId)
        } else {
            null
        }
    }
    val visibleRuns = remember(runs, successfulOnly) {
        val filtered = if (successfulOnly) {
            runs.filter { BenchmarkRunAnalytics.isSuccessfulForAverages(it) }
        } else {
            runs
        }
        filtered.take(8)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(
            title = "Tipo de prueba",
            subtitle = "Elige una tarea y ejecútala con el modelo actual.",
        )
        actions.chunked(2).forEach { rowActions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowActions.forEach { action ->
                    val actionIndex = actions.indexOf(action)
                    BenchmarkPresetTile(
                        modifier = Modifier.weight(1f),
                        label = action.label,
                        enabled = enabled,
                        selected = actionIndex == selectedActionIndex,
                        onClick = { selectedActionIndex = actionIndex },
                    )
                }
                if (rowActions.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
        codingWarning?.let { warning ->
            Text(
                text = warning,
                style = MaterialTheme.typography.bodySmall,
                color = PrismAmber,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
            enabled = enabled,
            onClick = selectedAction.onClick,
        ) {
            Text("Ejecutar ${selectedAction.label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Ejecuciones recientes",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = { successfulOnly = !successfulOnly }) {
                Text(
                    text = if (successfulOnly) "Solo correctas" else "Todas",
                    maxLines = 1,
                )
            }
        }
        if (visibleRuns.isEmpty()) {
            Text(
                text = if (runs.isEmpty()) {
                "Ejecuta una prueba o envía un mensaje para recopilar datos de rendimiento local."
                } else {
                "Todavía no hay ejecuciones correctas; se ocultaron errores y cortes por calidad."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            visibleRuns.forEach { run -> BenchmarkRunRow(run) }
        }
    }
}

@Composable
private fun BenchmarkPresetTile(
    modifier: Modifier = Modifier,
    label: String,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.heightIn(min = 42.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) PrismBlue.copy(alpha = 0.10f) else PrismGlass.copy(alpha = 0.28f),
        contentColor = if (selected) PrismBlue else PrismSlate,
        border = BorderStroke(1.dp, if (selected) PrismBlue.copy(alpha = 0.34f) else PrismGlassBorder.copy(alpha = 0.36f)),
        enabled = enabled,
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BenchmarkCompareTab(
    runs: List<BenchmarkRun>,
    readiness: List<ModelReadiness>,
) {
    val runRows = runs
        .groupBy { it.comparisonKey() }
        .map { (_, modelRuns) ->
            val representative = modelRuns.maxByOrNull { it.createdAt } ?: return@map null
            val modelId = representative.modelId ?: "Unknown model"
            val modelReadiness = readiness.firstOrNull { it.info.id == representative.modelId }
            BenchmarkComparisonRow(
                modelId = modelId,
                run = representative,
                summary = benchmarkSummary(modelRuns),
                readiness = modelReadiness,
            )
        }
        .filterNotNull()
    val modelsWithRuns = runRows.map { it.modelId }.toSet()
    val readinessRows = readiness
        .filter { it.info.id !in modelsWithRuns }
        .map { modelReadiness ->
            BenchmarkComparisonRow(
                modelId = modelReadiness.info.id,
                run = null,
                summary = BenchmarkSummary.Empty,
                readiness = modelReadiness,
            )
        }
    val byModel = (runRows + readinessRows)
        .sortedByDescending { it.summary.reliabilityScore ?: it.readiness?.prediction?.maxTokensPerSecond ?: 0.0 }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (byModel.isEmpty()) {
            Text(
                text = "Todavía no hay datos para comparar",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            byModel.take(8).forEach { row ->
                val summary = row.summary
                val modelReadiness = row.readiness
                val actual = summary.avgCompletedTokensPerSecond?.let { "${formatTokensPerSecond(it)} tok/s completed" } ?: "No actual yet"
                val predicted = modelReadiness?.prediction?.let { prediction ->
                    "predicted ${predictionRange(prediction)}"
                } ?: "prediction pending"
                val runtimeDetail = row.run?.settingsLabel() ?: modelReadiness?.fit?.quantization ?: "settings pending"
                BenchmarkMetricRow(
                    label = row.run?.let { "${polishedModelName(row.modelId)} • ${it.shortHashLabel()}" } ?: polishedModelName(row.modelId),
                    value = actual,
                    detail = "$predicted • $runtimeDetail • ${summaryStatusLine(summary)} • first ${formatOptionalMs(summary.avgPromptMs)}",
                )
            }
        }
    }
}

@Composable
private fun BenchmarkModelsTab(
    models: List<String>,
    activeModelInfo: ModelStorageManager.ActiveModelInfo?,
    deviceCapabilityProfile: DeviceCapabilityProfile?,
    readiness: List<ModelReadiness>,
    runs: List<BenchmarkRun>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        deviceCapabilityProfile?.let { profile ->
            Text(
            text = "RAM disponible ${formatBytes(profile.availableRamBytes)} | límite de la app ${formatBytes(profile.appHeapMaxBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (models.isEmpty()) {
            Text(
            text = "No hay modelos instalados",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            models.forEach { modelId ->
                val summary = benchmarkSummary(runs.filter { it.modelId == modelId })
                val modelRuns = runs.filter { it.modelId == modelId }
                val runVariants = modelRuns.map { it.comparisonKey() }.distinct().size
                val activeSize = activeModelInfo?.takeIf { it.id == modelId }?.bytes
                val modelReadiness = readiness.firstOrNull { it.info.id == modelId }
                BenchmarkMetricRow(
                    label = polishedModelName(modelId),
            value = modelReadiness?.performance?.label ?: "Desconocido",
                    detail = listOfNotNull(
                        activeSize?.let { formatBytes(it) } ?: modelReadiness?.info?.bytes?.let { formatBytes(it) },
                        modelReadiness?.fit?.quantization,
                        modelReadiness?.performance?.averageTokensPerSecond?.let { "actual ${formatTokensPerSecond(it)} tok/s" },
                        modelReadiness?.fit?.requiredRamBytes?.let { "needs ${formatBytes(it)}" },
                        modelReadiness?.prediction?.let { "expected ${predictionRange(it)}" },
                        summaryStatusLine(summary),
                        if (runVariants > 1) "$runVariants variants" else null,
                    ).joinToString(" • "),
                )
            }
        }
    }
}

@Composable
private fun BenchmarkRunRow(run: BenchmarkRun) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = run.presetName ?: run.source.replaceFirstChar { it.titlecase(Locale.US) },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatChatTimestamp(run.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    text = "${formatTokensPerSecond(run.tokensPerSecond)} tok/s",
                    style = MaterialTheme.typography.bodySmall,
                    color = PrismBlue,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Text(
                text = polishedModelName(run.modelId),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "ctx ${run.contextLength} • batch ${run.batchSize} • threads ${run.threadCount} • temp ${String.format(Locale.US, "%.2f", run.temperature)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BenchmarkStatusChip(status = run.status())
                Text(
                    text = "${run.runtimeBackend} • ${run.generatedTokens}/${run.maxTokens} tok • first ${run.promptEvalMs} ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            run.terminalDetail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (run.status()) {
                        BenchmarkRunStatus.Error -> PrismRed
                        BenchmarkRunStatus.QualityAbort -> PrismAmber
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BenchmarkMetricRow(
    label: String,
    value: String,
    detail: String,
    status: BenchmarkRunStatus? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                status?.let { runStatus ->
                    BenchmarkStatusChip(status = runStatus)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = PrismBlue,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BenchmarkStatusChip(status: BenchmarkRunStatus) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = status.color.copy(alpha = 0.12f),
        contentColor = status.color,
        border = BorderStroke(1.dp, status.color.copy(alpha = 0.32f)),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            text = status.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

internal fun benchmarkDisabledReason(
    serviceAvailable: Boolean,
    performanceBuild: Boolean,
    currentModel: String?,
    isGenerating: Boolean,
    status: BenchmarkStatus,
): String? = when {
    !serviceAvailable -> "Las pruebas no están disponibles mientras el servicio se reconecta"
    !performanceBuild -> "Selecciona la variante benchmark o profile en Android Studio para medir rendimiento"
    currentModel == null -> "Selecciona un modelo antes de ejecutar pruebas"
    status.isRunning -> "Ya hay una prueba en ejecución: ${status.presetName ?: "ejecución actual"}"
    isGenerating -> "Detén la generación actual antes de iniciar otra prueba"
    else -> null
}

private fun summaryStatusLine(summary: BenchmarkSummary): String =
    "${summary.totalCount} runs / ${summary.completedCount} ok / ${summary.failedCount} failed"

private fun benchmarkSummary(runs: List<BenchmarkRun>): BenchmarkSummary =
    BenchmarkSummary.fromAnalytics(BenchmarkRunAnalytics.summarize(runs))

private fun BenchmarkRun.comparisonKey(): String =
    listOf(
        modelId.orEmpty(),
        modelSha256Prefix.orEmpty(),
        contextLength,
        batchSize,
        threadCount,
        gpuLayers,
        runtimeBackend,
        presetId.orEmpty(),
        source,
    ).joinToString("|")

private fun BenchmarkRun.shortHashLabel(): String =
    modelSha256Prefix?.takeIf { it.isNotBlank() }?.let { "hash ${shortHash(it)}" } ?: "hash pending"

private fun BenchmarkRun.status(): BenchmarkRunStatus =
    when (BenchmarkRunAnalytics.statusOf(this)) {
        BenchmarkRunAnalytics.RunStatus.Clean -> BenchmarkRunStatus.Clean
        BenchmarkRunAnalytics.RunStatus.Truncated -> BenchmarkRunStatus.Truncated
        BenchmarkRunAnalytics.RunStatus.QualityAbort -> BenchmarkRunStatus.QualityAbort
        BenchmarkRunAnalytics.RunStatus.Error -> BenchmarkRunStatus.Error
        BenchmarkRunAnalytics.RunStatus.Interrupted -> BenchmarkRunStatus.Interrupted
        BenchmarkRunAnalytics.RunStatus.Partial -> BenchmarkRunStatus.Partial
        BenchmarkRunAnalytics.RunStatus.Unknown -> BenchmarkRunStatus.Unknown
    }

private fun BenchmarkRun.settingsLabel(): String =
    "ctx $contextLength | batch $batchSize | th $threadCount | gpu $gpuLayers | $runtimeBackend"
