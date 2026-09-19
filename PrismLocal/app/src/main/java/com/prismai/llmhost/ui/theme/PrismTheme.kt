package com.prismai.llmhost.ui.theme
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.prismai.llmhost.R

internal val PrismCyan = Color(0xFF68D8FF)
internal val PrismBlue = Color(0xFF7AA7FF)
internal val PrismViolet = Color(0xFFB99CFF)
internal val PrismGreen = Color(0xFF69D5A7)
internal val PrismAmber = Color(0xFFFFC46B)
internal val PrismRed = Color(0xFFFF8A91)
internal val PrismText = Color(0xFFF3F5FA)
internal val PrismSlate = Color(0xFF20232D)
internal val PrismOnDark = Color(0xFFF8FAFF)
internal val PrismCanvas = Color.Black
internal val PrismGlass = Color(0xFF0D0D0F)
internal val PrismGlassBorder = Color(0xFF2A2A2E)
internal val UserBubble = Color(0xFF242428)
internal val AssistantBubble = Color.Transparent

private val InterFontFamily = FontFamily(
    Font(R.font.inter_variable, weight = FontWeight.Normal),
    Font(R.font.inter_variable, weight = FontWeight.Medium),
    Font(R.font.inter_variable, weight = FontWeight.SemiBold),
    Font(R.font.inter_variable, weight = FontWeight.Bold),
)

private val PrismTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = InterFontFamily),
        displayMedium = displayMedium.copy(fontFamily = InterFontFamily),
        displaySmall = displaySmall.copy(fontFamily = InterFontFamily),
        headlineLarge = headlineLarge.copy(fontFamily = InterFontFamily),
        headlineMedium = headlineMedium.copy(fontFamily = InterFontFamily),
        headlineSmall = headlineSmall.copy(fontFamily = InterFontFamily),
        titleLarge = titleLarge.copy(fontFamily = InterFontFamily),
        titleMedium = titleMedium.copy(fontFamily = InterFontFamily),
        titleSmall = titleSmall.copy(fontFamily = InterFontFamily),
        bodyLarge = bodyLarge.copy(fontFamily = InterFontFamily),
        bodyMedium = bodyMedium.copy(fontFamily = InterFontFamily),
        bodySmall = bodySmall.copy(fontFamily = InterFontFamily),
        labelLarge = labelLarge.copy(fontFamily = InterFontFamily),
        labelMedium = labelMedium.copy(fontFamily = InterFontFamily),
        labelSmall = labelSmall.copy(fontFamily = InterFontFamily),
    )
}

internal val LlmHostPrismaticDarkColorScheme = darkColorScheme(
    primary = PrismBlue,
    onPrimary = Color(0xFF08101F),
    primaryContainer = Color(0xFF20242F),
    onPrimaryContainer = Color(0xFFDDE8FF),
    secondary = PrismViolet,
    onSecondary = Color(0xFF160B2B),
    secondaryContainer = Color(0xFF292331),
    onSecondaryContainer = Color(0xFFEBDDFF),
    tertiary = PrismCyan,
    onTertiary = Color(0xFF001F29),
    tertiaryContainer = Color(0xFF123641),
    onTertiaryContainer = Color(0xFFD0F3FF),
    background = Color.Black,
    onBackground = PrismText,
    surface = Color(0xFF0D0D0F),
    onSurface = PrismText,
    surfaceVariant = Color(0xFF1A1A1E),
    onSurfaceVariant = Color(0xFFB7B7BD),
    outline = Color(0xFF55555C),
    outlineVariant = Color(0xFF2A2A2E),
    error = PrismRed,
    onError = Color(0xFF33060A),
)

@Composable
internal fun PrismLocalTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = LlmHostPrismaticDarkColorScheme,
        typography = PrismTypography,
        content = content,
    )
}

@Composable
internal fun prismCanvasColor(): Color = PrismCanvas

@Composable
internal fun prismGlassColor(): Color = PrismGlass

@Composable
internal fun prismGlassBorderColor(): Color = PrismGlassBorder

@Composable
internal fun userBubbleColor(): Color = UserBubble

@Composable
internal fun assistantBubbleColor(): Color = AssistantBubble
