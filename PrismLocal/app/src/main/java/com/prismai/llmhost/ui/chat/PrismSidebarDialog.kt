package com.prismai.llmhost.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.prismai.llmhost.ChatSession
import com.prismai.llmhost.ui.theme.PrismBlue
import com.prismai.llmhost.ui.theme.PrismViolet

@Composable
internal fun PrismSidebarDialog(
    sessions: List<ChatSession>,
    currentChatId: String?,
    isGenerating: Boolean,
    hasCurrentTranscript: Boolean,
    onDismiss: () -> Unit,
    onNewChat: () -> Unit,
    onSwitchChat: (String) -> Unit,
    onRenameChat: (String, String) -> Unit,
    onDeleteChat: (String) -> Unit,
    onClearCurrentChat: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenDocuments: () -> Unit,
    onOpenMemory: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterStart,
        ) {
            val drawerWidth = if (maxWidth < 410.dp) maxWidth * 0.88f else 360.dp
            Row(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier
                        .width(drawerWidth)
                        .fillMaxHeight(),
                    color = Color(0xFF0D0D0F),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shadowElevation = 12.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Prism Local",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Surface(
                                modifier = Modifier.size(40.dp),
                                shape = RoundedCornerShape(20.dp),
                                color = Color.Transparent,
                                onClick = onDismiss,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    val color = MaterialTheme.colorScheme.onSurfaceVariant
                                    Canvas(modifier = Modifier.size(18.dp)) {
                                        drawLine(color, Offset(size.width * .22f, size.height * .22f), Offset(size.width * .78f, size.height * .78f), 2.2f, StrokeCap.Round)
                                        drawLine(color, Offset(size.width * .78f, size.height * .22f), Offset(size.width * .22f, size.height * .78f), 2.2f, StrokeCap.Round)
                                    }
                                }
                            }
                        }

                        ChatListSheet(
                            sessions = sessions,
                            currentChatId = currentChatId,
                            isGenerating = isGenerating,
                            hasCurrentTranscript = hasCurrentTranscript,
                            onNewChat = onNewChat,
                            onSwitchChat = onSwitchChat,
                            onRenameChat = onRenameChat,
                            onDeleteChat = onDeleteChat,
                            onClearCurrentChat = onClearCurrentChat,
                        )

                        Box(modifier = Modifier.weight(1f))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SidebarAction("Models and runtime", SidebarIcon.Models, PrismBlue, onOpenModels)
                        SidebarAction("Documents", SidebarIcon.Documents, MaterialTheme.colorScheme.onSurfaceVariant, onOpenDocuments)
                        SidebarAction("Memory", SidebarIcon.Memory, PrismViolet, onOpenMemory)
                        Text(
                            text = "Private and offline on this device",
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                )
            }
        }
    }
}

private enum class SidebarIcon { Models, Documents, Memory }

@Composable
private fun SidebarAction(label: String, icon: SidebarIcon, tint: Color, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Canvas(modifier = Modifier.size(20.dp)) {
                val stroke = Stroke(width = 2f, cap = StrokeCap.Round)
                when (icon) {
                    SidebarIcon.Models -> {
                        drawRoundRect(tint, Offset(size.width * .10f, size.height * .14f), androidx.compose.ui.geometry.Size(size.width * .80f, size.height * .28f), style = stroke)
                        drawRoundRect(tint, Offset(size.width * .10f, size.height * .58f), androidx.compose.ui.geometry.Size(size.width * .80f, size.height * .28f), style = stroke)
                    }
                    SidebarIcon.Documents -> {
                        drawRect(tint, Offset(size.width * .22f, size.height * .10f), androidx.compose.ui.geometry.Size(size.width * .56f, size.height * .80f), style = stroke)
                        drawLine(tint, Offset(size.width * .34f, size.height * .40f), Offset(size.width * .66f, size.height * .40f), 2f, StrokeCap.Round)
                        drawLine(tint, Offset(size.width * .34f, size.height * .58f), Offset(size.width * .66f, size.height * .58f), 2f, StrokeCap.Round)
                    }
                    SidebarIcon.Memory -> {
                        drawCircle(tint, size.minDimension * .28f, center, style = stroke)
                        repeat(4) { index ->
                            val vertical = index < 2
                            val sign = if (index % 2 == 0) -1f else 1f
                            val start = if (vertical) Offset(center.x, center.y + sign * size.height * .28f) else Offset(center.x + sign * size.width * .28f, center.y)
                            val end = if (vertical) Offset(center.x, center.y + sign * size.height * .43f) else Offset(center.x + sign * size.width * .43f, center.y)
                            drawLine(tint, start, end, 2f, StrokeCap.Round)
                        }
                    }
                }
            }
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}
