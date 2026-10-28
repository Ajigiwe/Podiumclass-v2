package online.podiumclass.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Brand Accents
val IndigoPrimary = Color(0xFF1845D4)
val IndigoHover = Color(0xFF0F2FA8)
val BlueAccent = Color(0xFF3B82F6)
val EmeraldSuccess = Color(0xFF10B981)
val AmberWarning = Color(0xFFF59E0B)
val RedUrgent = Color(0xFFEF4444)

// Dark Theme Colors
val BackgroundDark = Color(0xFF090B11)
val SurfaceDark = Color(0xFF111522)
val SurfaceElevatedDark = Color(0xFF171C2E)
val CardBorderDark = Color(0x1AFFFFFF) // rgba(255,255,255,0.10)
val TextPrimaryDark = Color(0xFFFFFFFF)
val TextSecondaryDark = Color(0xFF8888A8)
val TextTertiaryDark = Color(0xFF5E6582)

// Light Theme Colors (Matching web app #F8FAFC and clean whites)
val BackgroundLight = Color(0xFFF8FAFC)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceElevatedLight = Color(0xFFF1F5F9)
val CardBorderLight = Color(0xFFDDE0F0)
val TextPrimaryLight = Color(0xFF0D0D1A)
val TextSecondaryLight = Color(0xFF64748B)
val TextTertiaryLight = Color(0xFF94A3B8)

// Backwards compatibility aliases
val CardBorder = CardBorderDark
val TextPrimary = TextPrimaryDark
val TextSecondary = TextSecondaryDark
val TextTertiary = TextTertiaryDark
val SurfaceElevated = SurfaceElevatedDark

data class PodiumColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val cardBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val primary: Color,
    val primaryHover: Color,
    val accent: Color,
    val success: Color,
    val warning: Color,
    val error: Color
)

val DarkPodiumColors = PodiumColors(
    isDark = true,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceElevated = SurfaceElevatedDark,
    cardBorder = CardBorderDark,
    textPrimary = TextPrimaryDark,
    textSecondary = TextSecondaryDark,
    textTertiary = TextTertiaryDark,
    primary = IndigoPrimary,
    primaryHover = IndigoHover,
    accent = BlueAccent,
    success = EmeraldSuccess,
    warning = AmberWarning,
    error = RedUrgent
)

val LightPodiumColors = PodiumColors(
    isDark = false,
    background = BackgroundLight,
    surface = SurfaceLight,
    surfaceElevated = SurfaceElevatedLight,
    cardBorder = CardBorderLight,
    textPrimary = TextPrimaryLight,
    textSecondary = TextSecondaryLight,
    textTertiary = TextTertiaryLight,
    primary = IndigoPrimary,
    primaryHover = IndigoHover,
    accent = BlueAccent,
    success = EmeraldSuccess,
    warning = AmberWarning,
    error = RedUrgent
)

val LocalPodiumColors = staticCompositionLocalOf { DarkPodiumColors }

object AppTheme {
    val colors: PodiumColors
        @Composable
        @ReadOnlyComposable
        get() = LocalPodiumColors.current
}
