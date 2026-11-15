@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package gg.seven.kit

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Port of iOS SevenKitCore/Palette.swift. Native Material controls use ink; data has its own hues.
private val Light =
    lightColorScheme(
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = Color(0xFFFFFDF8),
        surfaceContainerLow = Color(0xFFF8F4EC),
        // Material's container roles step down from paper so toggles, chips and the search bar
        // read as surfaces (cards stay on the lighter Lowest/Low).
        surfaceContainer = Color(0xFFE9E4DA),
        surfaceContainerHigh = Color(0xFFE3DDD2),
        surfaceContainerHighest = Color(0xFFDCD6CA),
        inverseSurface = Color(0xFF1C1A18),
        inverseOnSurface = Color(0xFFEEE9DF),
        inversePrimary = Color(0xFFEEE9DF),
        primary = Color(0xFF161513),
        onPrimary = Color(0xFFF3EFE6),
        secondary = Color(0xFF55504A),
        onSecondary = Color.White,
        primaryContainer = Color(0xFFE4DED3),
        onPrimaryContainer = Color(0xFF161513),
        secondaryContainer = Color(0xFFE4DED3),
        onSecondaryContainer = Color(0xFF161513),
        background = Color(0xFFF3EFE6),
        onBackground = Color(0xFF161513),
        surface = Color(0xFFFFFDF8),
        onSurface = Color(0xFF161513),
        surfaceVariant = Color(0xFFE4DED3),
        onSurfaceVariant = Color(0xFF55504A),
        error = Color(0xFFB0201A),
        outline = Color(0xFF77716A),
        outlineVariant = Color(0xFFCAC3B8),
    )
private val Dark =
    darkColorScheme(
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = Color(0xFF121110),
        surfaceContainerLow = Color(0xFF1C1A18),
        surfaceContainer = Color(0xFF24211D),
        surfaceContainerHigh = Color(0xFF2B2723),
        surfaceContainerHighest = Color(0xFF36322D),
        inverseSurface = Color(0xFFF3EFE6),
        inverseOnSurface = Color(0xFF161513),
        inversePrimary = Color(0xFF161513),
        primary = Color(0xFFEEE9DF),
        onPrimary = Color(0xFF161513),
        secondary = Color(0xFFA8A196),
        onSecondary = Color(0xFF121110),
        primaryContainer = Color(0xFF36322D),
        onPrimaryContainer = Color(0xFFEEE9DF),
        secondaryContainer = Color(0xFF36322D),
        onSecondaryContainer = Color(0xFFEEE9DF),
        background = Color(0xFF121110),
        onBackground = Color(0xFFEEE9DF),
        surface = Color(0xFF1C1A18),
        onSurface = Color(0xFFEEE9DF),
        surfaceVariant = Color(0xFF36322D),
        onSurfaceVariant = Color(0xFFA8A196),
        error = Color(0xFFFF7468),
        outline = Color(0xFF8C857A),
        outlineVariant = Color(0xFF504A42),
    )

@Composable
fun SevenTheme(content: @Composable () -> Unit) {
    // Material 3 Expressive: its components, shapes and springier motion, with the Sevens' fixed
    // ink-and-paper colours (never dynamic colour) and type roles.
    MaterialExpressiveTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        motionScheme = MotionScheme.expressive(),
        shapes = Shapes(),
        typography =
            Typography(
                displayLarge =
                    TextStyle(
                        fontSize = 56.sp,
                        lineHeight = 64.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                headlineLarge =
                    TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
                // §4.1: title = headlineMedium bold (the large app-bar title), section = titleLarge bold.
                headlineMedium =
                    TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
                titleLarge =
                    TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
                titleMedium =
                    TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.15.sp,
                    ),
            ),
        content = content,
    )
}

object SevenColors {
    val time: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFFF5913A) else Color(0xFFB8480A)

    val water: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF5EA8F2) else Color(0xFF1F68C9)

    val unlock: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFF5D50) else Color(0xFFD11F16)

    val onUnlock: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF121110) else Color.White
}
