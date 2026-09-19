package com.prismai.llmhost.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prismai.llmhost.GenerationPerformance
import com.prismai.llmhost.ImportState
import com.prismai.llmhost.RuntimeStatus
import com.prismai.llmhost.ui.formatTokensPerSecond
import com.prismai.llmhost.ui.polishedModelName
import com.prismai.llmhost.ui.theme.PrismBlue
import com.prismai.llmhost.ui.theme.PrismGreen

@Composable
internal fun ChatTopBar(
    runtimeStatus: RuntimeStatus,
    chatTitle: String?,
    modelName: String?,
    models: List<String>,
    importStatus: String,
    importState: ImportState,
    collapsed: Boolean,
    thermalGovernorState: com.prismai.llmhost.util.ThermalGovernorState? = null,
    generationPerformance: GenerationPerformance? = null,
    onOpenChats: () -> Unit,
    onOpenControls: () -> Unit,
    onSwitchModel: (String) -> Unit,
) {
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val canSwitchModel = runtimeStatus != RuntimeStatus.GENERATING &&
        runtimeStatus != RuntimeStatus.LOADING_MODEL &&
        importState !is ImportState.Running

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Black,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (collapsed) 56.dp else 64.dp)
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeaderIconButton(label = "Open sidebar", kind = HeaderIcon.Menu, onClick = onOpenChats)

            Box(modifier = Modifier.weight(1f)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    enabled = canSwitchModel,
                    onClick = { modelMenuExpanded = true },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = modelName?.let(::polishedModelName) ?: "Select a model",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!collapsed) {
                                Text(
                                    text = topBarSubtitle(
                                        runtimeStatus,
                                        importStatus,
                                        importState,
                                        generationPerformance,
                                        thermalGovernorState,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        val chevronColor = MaterialTheme.colorScheme.onSurfaceVariant
                        Canvas(modifier = Modifier.size(14.dp)) {
                            drawLine(
                                color = chevronColor,
                                start = Offset(size.width * 0.20f, size.height * 0.38f),
                                end = Offset(size.width * 0.50f, size.height * 0.68f),
                                strokeWidth = 2.2f,
                                cap = StrokeCap.Round,
                            )
                            drawLine(
                                color = chevronColor,
                                start = Offset(size.width * 0.50f, size.height * 0.68f),
                                end = Offset(size.width * 0.80f, size.height * 0.38f),
                                strokeWidth = 2.2f,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }

                DropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = { modelMenuExpanded = false },
                    modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(
                        text = "Models on this device",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    models.forEach { model ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = polishedModelName(model),
                                        fontWeight = if (model == modelName) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (model == modelName) {
                                        Text("Active", style = MaterialTheme.typography.labelSmall, color = PrismGreen)
                                    }
                                }
                            },
                            enabled = canSwitchModel && model != modelName,
                            onClick = {
                                modelMenuExpanded = false
                                onSwitchModel(model)
                            },
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DropdownMenuItem(
                        text = { Text("Manage and import models", color = PrismBlue) },
                        onClick = {
                            modelMenuExpanded = false
                            onOpenControls()
                        },
                    )
                }
            }

            HeaderIconButton(label = "Models and settings", kind = HeaderIcon.Settings, onClick = onOpenControls)
        }
    }
}

private enum class HeaderIcon { Menu, Settings }

@Composable
private fun HeaderIconButton(label: String, kind: HeaderIcon, onClick: () -> Unit) {
    val iconColor = MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier.size(44.dp).semantics { contentDescription = label },
        shape = RoundedCornerShape(22.dp),
        color = Color.Transparent,
        contentColor = iconColor,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(22.dp)) {
                val stroke = 2.2f
                when (kind) {
                    HeaderIcon.Menu -> {
                        listOf(0.27f, 0.50f, 0.73f).forEach { y ->
                            drawLine(
                                color = iconColor,
                                start = Offset(size.width * 0.18f, size.height * y),
                                end = Offset(size.width * 0.82f, size.height * y),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                    HeaderIcon.Settings -> {
                        listOf(0.28f, 0.50f, 0.72f).forEachIndexed { index, y ->
                            drawLine(
                                color = iconColor,
                                start = Offset(size.width * 0.12f, size.height * y),
                                end = Offset(size.width * 0.88f, size.height * y),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round,
                            )
                            val x = listOf(0.38f, 0.66f, 0.45f)[index]
                            drawCircle(
                                color = Color.Black,
                                radius = size.minDimension * 0.10f,
                                center = Offset(size.width * x, size.height * y),
                            )
                            drawCircle(
                                color = iconColor,
                                radius = size.minDimension * 0.10f,
                                center = Offset(size.width * x, size.height * y),
                                style = Stroke(width = stroke),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun topBarSubtitle(
    status: RuntimeStatus,
    importStatus: String,
    importState: ImportState,
    performance: GenerationPerformance?,
    thermalState: com.prismai.llmhost.util.ThermalGovernorState?,
): String {
    if (importState is ImportState.Running && importStatus.isNotBlank()) return importStatus
    if (status == RuntimeStatus.LOADING_MODEL) return "Loading model"
    if (status == RuntimeStatus.ERROR) return "Model error - open settings"
    if (status == RuntimeStatus.GENERATING) {
        val speed = performance?.takeIf { it.tokensPerSecond > 0.0 }?.let {
            "${formatTokensPerSecond(it.tokensPerSecond)} t/s"
        }
        val threads = performance?.let {
            if (it.activeThreads > 0) it.activeThreads else it.settings.threadCount
        }
        return listOfNotNull("Generating", speed, threads?.let { "${it} threads" }).joinToString("  ·  ")
    }
    val thermal = when {
        thermalState?.isEmergency == true -> "Thermal emergency"
        thermalState?.isThrottled == true -> "Thermal throttling"
        thermalState?.statusLabel == "MODERATE" -> "Warm"
        else -> null
    }
    return listOfNotNull("On-device", "Private", thermal).joinToString("  ·  ")
}
