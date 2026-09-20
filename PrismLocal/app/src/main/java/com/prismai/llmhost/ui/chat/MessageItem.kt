package com.prismai.llmhost.ui.chat
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prismai.llmhost.GenerationPerformance
import com.prismai.llmhost.ui.components.InfinityLoadingIndicator
import com.prismai.llmhost.ui.theme.*
import com.prismai.llmhost.ui.formatTokensPerSecond

@Composable
internal fun MessageBubble(
    label: String,
    text: String,
    isUser: Boolean,
    showLoading: Boolean,
    performance: GenerationPerformance? = null,
) {
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val context = LocalContext.current
    val view = LocalView.current
    val copyLabel = if (isUser) "prompt" else "response"
    var showReportDialog by remember { mutableStateOf(false) }
    var reasoningExpanded by remember { mutableStateOf(false) }
    val parsedOutput = remember(text, isUser) {
        if (isUser) null else ReasoningOutputParser.parse(text)
    }

    if (showReportDialog) {
        ReportAiContentDialog(
            onDismiss = { showReportDialog = false },
            onSubmitReport = { reason ->
                Toast.makeText(context, "Report saved locally: $reason", Toast.LENGTH_SHORT).show()
            }
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        if (isUser) {
            Column(
                modifier = Modifier.widthIn(max = maxWidth * 0.82f),
                horizontalAlignment = Alignment.End,
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = userBubbleColor(),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    SelectionContainer {
                        Text(
                            text = text,
                            modifier = Modifier.padding(horizontal = 17.dp, vertical = 11.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (text.isNotBlank()) {
                    MessageAction(label = "Copy") {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        copyTextToClipboard(context, label, text)
                        Toast.makeText(context, "Copied $copyLabel", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistantBadge()
                    Text(
                        text = "Prism",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    PerformancePill(
                        modifier = Modifier.weight(1f, fill = false),
                        performance = performance,
                        loading = showLoading,
                    )
                    if (showLoading) {
                        InfinityLoadingIndicator(
                            modifier = Modifier.size(18.dp),
                            color = PrismViolet,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                if (parsedOutput?.hasReasoning == true) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        border = BorderStroke(1.dp, prismGlassBorderColor()),
                        onClick = { reasoningExpanded = !reasoningExpanded },
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                            Text(
                                text = when {
                                    !parsedOutput.reasoningComplete && showLoading -> {
                                        val generated = performance?.generatedTokens?.takeIf { it > 0 }
                                        if (generated != null) "Razonando… $generated tokens" else "Razonando…"
                                    }
                                    parsedOutput.reasoningComplete -> "Razonamiento ${if (reasoningExpanded) "⌃" else "⌄"}"
                                    else -> "Razonamiento incompleto ${if (reasoningExpanded) "⌃" else "⌄"}"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (reasoningExpanded && parsedOutput.reasoning.isNotBlank()) {
                                Spacer(modifier = Modifier.height(7.dp))
                                SelectionContainer {
                                    Text(
                                        text = parsedOutput.reasoning,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (!parsedOutput.reasoningComplete && !showLoading) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "El razonamiento no llegó a una respuesta final. Puedes abrir el bloque para revisarlo y usar Continuar.",
                            style = MaterialTheme.typography.labelSmall,
                            color = PrismAmber,
                        )
                    }
                    if (parsedOutput.answer.isNotBlank()) Spacer(modifier = Modifier.height(10.dp))
                }
                val visibleAnswer = parsedOutput?.answer ?: text
                if (visibleAnswer.isNotBlank()) {
                    SelectionContainer {
                        EnhancedMarkdownText(text = visibleAnswer)
                    }
                }
                if (text.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MessageAction(label = "Copy") {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            copyTextToClipboard(context, label, visibleAnswer.ifBlank { text })
                            Toast.makeText(context, "Copied $copyLabel", Toast.LENGTH_SHORT).show()
                        }
                        MessageAction(label = "Report") {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            showReportDialog = true
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageAction(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = onClick,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ReportAiContentDialog(
    onDismiss: () -> Unit,
    onSubmitReport: (reason: String) -> Unit,
) {
    val reportReasons = listOf(
        "Offensive or hateful content",
        "Sexually explicit content",
        "Dangerous or harmful instructions",
        "Inaccurate or hallucinated response",
        "Other policy violation"
    )
    var selectedReasonIndex by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report AI Generated Response") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Google Play AI policy requires in-app user reporting for generated content. Select the issue with this response:",
                    style = MaterialTheme.typography.bodySmall
                )
                reportReasons.forEachIndexed { index, reason ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedReasonIndex = index }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedReasonIndex == index),
                            onClick = { selectedReasonIndex = index }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = reason, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSubmitReport(reportReasons[selectedReasonIndex])
                    onDismiss()
                }
            ) {
                Text("Submit Report")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun AssistantBadge() {
    Surface(
        modifier = Modifier.size(28.dp),
        shape = RoundedCornerShape(14.dp),
        color = PrismViolet.copy(alpha = 0.12f),
        contentColor = PrismViolet,
        border = BorderStroke(1.dp, PrismViolet.copy(alpha = 0.36f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(14.dp)) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    val w = size.width
                    val h = size.height
                    moveTo(w * 0.5f, 0f)
                    quadraticTo(w * 0.5f, h * 0.5f, w, h * 0.5f)
                    quadraticTo(w * 0.5f, h * 0.5f, w * 0.5f, h)
                    quadraticTo(w * 0.5f, h * 0.5f, 0f, h * 0.5f)
                    quadraticTo(w * 0.5f, h * 0.5f, w * 0.5f, 0f)
                    close()
                }
                drawPath(path = path, color = PrismViolet)
            }
        }
    }
}

@Composable
private fun PerformancePill(performance: GenerationPerformance?) {
    PerformancePill(modifier = Modifier, performance = performance, loading = false)
}

@Composable
private fun PerformancePill(
    modifier: Modifier,
    performance: GenerationPerformance?,
    loading: Boolean,
) {
    Surface(
        modifier = modifier.widthIn(min = 72.dp, max = 150.dp),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, prismGlassBorderColor()),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Canvas(modifier = Modifier.size(7.dp)) {
                drawCircle(color = PrismGreen)
            }
            Text(
                text = when {
                    performance != null -> {
                        val tpsStr = "${formatTokensPerSecond(performance.tokensPerSecond)} t/s"
                        val ttftStr = if (performance.ttftMs > 0) " · ${performance.ttftMs}ms" else ""
                        val threadsStr = if (performance.activeThreads > 0) " · ${performance.activeThreads}th" else ""
                        "$tpsStr$ttftStr$threadsStr"
                    }
                    loading -> "typing"
                    else -> "Local LLM"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ToolEventCard(rawText: String) {
    val event = remember(rawText) { AgentToolProtocol.parseToolEvent(rawText) }
    val statusStr = event?.optString("status")?.takeIf { it.isNotBlank() } ?: "done"
    val toolName = event?.optString("tool")?.takeIf { it.isNotBlank() } ?: "tool"
    val summary = event?.optString("summary")?.takeIf { it.isNotBlank() } ?: rawText
    val latencyMs = event?.optLong("latency_ms")?.takeIf { it > 0 }
    val argsJson = event?.optString("arguments")?.takeIf { it.isNotBlank() }
    val error = event?.optString("error")?.takeIf { it.isNotBlank() }

    val status = when (statusStr) {
        "pending", "running" -> com.prismai.llmhost.ui.components.ToolStatus.RUNNING
        "failed" -> com.prismai.llmhost.ui.components.ToolStatus.FAILED
        else -> com.prismai.llmhost.ui.components.ToolStatus.SUCCESS
    }

    val item = com.prismai.llmhost.ui.components.ToolExecutionItem(
        toolName = toolName,
        status = status,
        latencyMs = latencyMs,
        argumentsJson = argsJson,
        resultPreview = summary,
        errorMessage = error
    )

    com.prismai.llmhost.ui.components.ToolExecutionCard(item = item)
}

@Composable
internal fun AgentToolConfirmationDialog(
    action: PendingAgentToolAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = action.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PrismText,
                )
                if (action.changes.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        action.changes.forEach { change ->
                            Text(
                                text = change,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (action.riskNotes.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        action.riskNotes.forEach { note ->
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (action.destructive) PrismRed else PrismAmber,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Text(
                    text = action.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = action.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = action.argumentsJson,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(action.confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(action.cancelLabel)
            }
        },
    )
}

private fun copyTextToClipboard(context: android.content.Context, label: String, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
}

internal fun GenerationPerformance.terminalSuffix(): String =
    terminalReason?.let { reason ->
        " | " + when (reason) {
            "MAX_TOKENS" -> "token limit"
            else -> reason
        }
    }.orEmpty()
