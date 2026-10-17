package com.prismai.llmhost.ui.composer
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import com.prismai.llmhost.GenerationPerformance
import com.prismai.llmhost.PromptAttachment
import com.prismai.llmhost.ui.theme.*
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import com.prismai.llmhost.ui.formatBytes
import com.prismai.llmhost.ui.formatTokensPerSecond

private enum class ComposerAction {
    Add,
    Send,
    Stop,
    More,
    Voice,
}

@Composable
internal fun PromptComposer(
    modifier: Modifier = Modifier,
    prompt: String,
    enabled: Boolean,
    hasModel: Boolean,
    isGenerating: Boolean,
    performance: GenerationPerformance?,
    attachments: List<PromptAttachment>,
    canContinue: Boolean,
    onPromptChange: (String) -> Unit,
    onAddAttachment: () -> Unit,
    onRemoveAttachment: (PromptAttachment) -> Unit,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
    onSend: () -> Unit,
    onVoiceClick: (() -> Unit)? = null,
    voiceState: com.prismai.llmhost.tools.VoiceState? = null,
) {
    val placeholder = when {
        !enabled -> "Starting Prism Local..."
        hasModel -> "Message Prism..."
        else -> "Ask for model help or import a GGUF"
    }
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        color = Color(0xFF18181B),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, Color(0xFF2B2B30)),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ComposerIconButton(
                    action = ComposerAction.Add,
                    enabled = enabled && !isGenerating,
                    onClick = onAddAttachment,
                )
                ComposerTextInput(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp, max = 120.dp),
                    value = prompt,
                    onValueChange = onPromptChange,
                    placeholder = placeholder,
                    enabled = enabled,
                )
                val hasPrompt = prompt.isNotBlank()
                val hasAttachments = attachments.isNotEmpty()
                when {
                    isGenerating -> {
                        ComposerIconButton(
                            action = ComposerAction.Stop,
                            enabled = enabled,
                            onClick = onCancel,
                        )
                    }
                    hasPrompt || hasAttachments -> {
                        ComposerIconButton(
                            action = ComposerAction.Send,
                            enabled = enabled,
                            onClick = onSend,
                        )
                    }
                    onVoiceClick != null && voiceState?.sttAvailable == true -> {
                        ComposerIconButton(
                            action = ComposerAction.Voice,
                            enabled = enabled,
                            onClick = onVoiceClick,
                        )
                    }
                    canContinue -> {
                        ComposerIconButton(
                            action = ComposerAction.More,
                            enabled = enabled,
                            onClick = onContinue,
                        )
                    }
                    else -> {
                        ComposerIconButton(
                            action = ComposerAction.Send,
                            enabled = false,
                            onClick = {},
                        )
                    }
                }
            }
            AttachmentTray(
                attachments = attachments,
                onRemove = onRemoveAttachment,
            )
            if (!imeVisible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Canvas(modifier = Modifier.size(5.dp)) { drawCircle(color = PrismGreen) }
                        Text(
                            text = "Local · Privado",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                    performance?.takeIf { it.tokensPerSecond > 0.0 }?.let {
                        Text(
                            text = "${formatTokensPerSecond(it.tokensPerSecond)} t/s",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentTray(
    attachments: List<PromptAttachment>,
    onRemove: (PromptAttachment) -> Unit,
) {
    if (attachments.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.forEach { attachment ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.54f),
                contentColor = PrismText,
                border = BorderStroke(1.dp, PrismGlassBorder.copy(alpha = 0.70f)),
                shadowElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (attachment.isImage) "Image" else "File",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (attachment.isImage) PrismViolet else PrismBlue,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Text(
                        modifier = Modifier.weight(1f),
                        text = attachment.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = PrismText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    attachment.sizeBytes?.let { size ->
                        Text(
                            text = formatBytes(size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    TextButton(onClick = { onRemove(attachment) }) {
                        Text("Remove", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        if (attachments.any { it.isImage }) {
            Text(
                text = "Images are attached as metadata in this build; true vision needs the native multimodal image bridge.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ComposerTextInput(
    modifier: Modifier = Modifier,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = null,
        shadowElevation = 0.dp,
    ) {
        BasicTextField(
            modifier = Modifier.fillMaxWidth(),
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            maxLines = 4,
            textStyle = MaterialTheme.typography.bodyMedium.merge(
                TextStyle(
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                )
            ),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

private val ComposerAction.contentDescription: String
    get() = when (this) {
        ComposerAction.Add -> "Add attachment"
        ComposerAction.Send -> "Send message"
        ComposerAction.Stop -> "Stop generation"
        ComposerAction.Voice -> "Voice input"
        ComposerAction.More -> "More options"
    }

@Composable
private fun ComposerIconButton(
    action: ComposerAction,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    val buttonColor = when (action) {
        ComposerAction.Send -> if (enabled) Color.White else Color(0xFF262629)
        ComposerAction.Stop -> Color(0xFFEF4444)
        ComposerAction.Add -> Color(0xFF26262A)
        ComposerAction.Voice -> Color.Transparent
        ComposerAction.More -> Color(0xFF26262A)
    }
    Surface(
        modifier = Modifier
            .size(36.dp)
            .semantics {
                contentDescription = action.contentDescription
                role = Role.Button
            },
        shape = RoundedCornerShape(18.dp),
        color = buttonColor,
        shadowElevation = 0.dp,
        enabled = enabled,
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onClick()
        },
    ) {
        Box(modifier = Modifier.padding(6.dp), contentAlignment = Alignment.Center) {
            ComposerActionGlyph(action = action, enabled = enabled)
        }
    }
}

@Composable
private fun ComposerActionGlyph(
    action: ComposerAction,
    enabled: Boolean,
) {
    val color = when (action) {
        ComposerAction.Send -> if (enabled) Color.Black else Color(0xFF6B6B72)
        ComposerAction.Stop -> Color.White
        ComposerAction.Add -> MaterialTheme.colorScheme.onSurface
        ComposerAction.Voice -> if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        ComposerAction.More -> MaterialTheme.colorScheme.onSurface
    }
    Canvas(modifier = Modifier.size(20.dp)) {
        val strokeWidth = 2.2f
        when (action) {
            ComposerAction.Add -> {
                drawLine(
                    color = color,
                    start = Offset(center.x, size.height * 0.22f),
                    end = Offset(center.x, size.height * 0.78f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.22f, center.y),
                    end = Offset(size.width * 0.78f, center.y),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
            ComposerAction.Send -> {
                // Modern Upward Arrow (↑) like ChatGPT / Gemini
                drawLine(
                    color = color,
                    start = Offset(center.x, size.height * 0.76f),
                    end = Offset(center.x, size.height * 0.24f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(center.x, size.height * 0.24f),
                    end = Offset(size.width * 0.28f, size.height * 0.46f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(center.x, size.height * 0.24f),
                    end = Offset(size.width * 0.72f, size.height * 0.46f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
            ComposerAction.Stop -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.26f, size.height * 0.26f),
                    size = Size(size.width * 0.48f, size.height * 0.48f),
                    cornerRadius = CornerRadius(4f, 4f),
                )
            }
            ComposerAction.More -> {
                repeat(3) { index ->
                    drawCircle(
                        color = color,
                        radius = size.minDimension * 0.08f,
                        center = Offset(size.width * (0.30f + index * 0.20f), center.y),
                    )
                }
            }
            ComposerAction.Voice -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.35f, size.height * 0.18f),
                    size = Size(size.width * 0.30f, size.height * 0.44f),
                    cornerRadius = CornerRadius(8f, 8f),
                    style = Stroke(width = strokeWidth),
                )
                drawArc(
                    color = color,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.22f, size.height * 0.32f),
                    size = Size(size.width * 0.56f, size.height * 0.42f),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                drawLine(
                    color = color,
                    start = Offset(center.x, size.height * 0.74f),
                    end = Offset(center.x, size.height * 0.88f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun ComposerChip(label: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.20f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Canvas(modifier = Modifier.size(7.dp)) {
                drawCircle(color = accent)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
