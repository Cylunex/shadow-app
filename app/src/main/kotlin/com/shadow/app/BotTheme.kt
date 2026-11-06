package com.shadow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Same neutral foundation as Media; never inherit Material's purple surface tint.
private val BotColors = darkColorScheme(
    primary = Color(0xFFF4F4F4), onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF303030), onPrimaryContainer = Color(0xFFF4F4F4),
    secondary = Color(0xFFC4C4C4), onSecondary = Color(0xFF181818),
    secondaryContainer = Color(0xFF252525), onSecondaryContainer = Color(0xFFF4F4F4),
    tertiary = Color(0xFFB8B8B8), onTertiary = Color(0xFF171717),
    tertiaryContainer = Color(0xFF303030), onTertiaryContainer = Color(0xFFF4F4F4),
    background = Color(0xFF101010), onBackground = Color(0xFFF4F4F4),
    surface = Color(0xFF171717), onSurface = Color(0xFFF4F4F4),
    surfaceVariant = Color(0xFF252525), onSurfaceVariant = Color(0xFFA8A8A8),
    surfaceDim = Color(0xFF101010), surfaceBright = Color(0xFF333333),
    surfaceContainerLowest = Color(0xFF0C0C0C), surfaceContainerLow = Color(0xFF161616),
    surfaceContainer = Color(0xFF1B1B1B), surfaceContainerHigh = Color(0xFF242424),
    surfaceContainerHighest = Color(0xFF2D2D2D),
    outline = Color(0xFF666666), outlineVariant = Color(0xFF333333), surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFE8E8E8), inverseOnSurface = Color(0xFF202020), inversePrimary = Color(0xFF242424),
    error = Color(0xFFFFB4AB), onError = Color(0xFF300909),
    errorContainer = Color(0xFF5B1D1D), onErrorContainer = Color(0xFFFFDAD6),
)

@Composable internal fun BotTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BotColors, shapes = Shapes(
        small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
    ), content = content)
}

@Composable internal fun BotAvatar(name: String, id: String, size: Dp = 48.dp) {
    val tones = listOf(0xFF303030, 0xFF383838, 0xFF424242, 0xFF4A4A4A)
    Box(Modifier.size(size).background(Color(tones[Math.floorMod(id.hashCode(), tones.size)]), RoundedCornerShape(size * .34f)),
        contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), fontWeight = FontWeight.SemiBold,
            style = if (size > 40.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall)
    }
}
