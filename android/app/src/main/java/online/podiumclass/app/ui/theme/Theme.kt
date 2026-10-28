package online.podiumclass.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val DarkColorScheme = darkColorScheme(
    primary = IndigoPrimary,
    onPrimary = TextPrimaryDark,
    secondary = EmeraldSuccess,
    onSecondary = TextPrimaryDark,
    tertiary = AmberWarning,
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceElevatedDark,
    onSurfaceVariant = TextSecondaryDark,
    error = RedUrgent,
    onError = TextPrimaryDark
)

private val LightColorScheme = lightColorScheme(
    primary = IndigoPrimary,
    onPrimary = TextPrimaryDark,
    secondary = EmeraldSuccess,
    onSecondary = TextPrimaryDark,
    tertiary = AmberWarning,
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceElevatedLight,
    onSurfaceVariant = TextSecondaryLight,
    error = RedUrgent,
    onError = TextPrimaryDark
)

@Composable
fun PodiumClassTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit
) {
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themeMode.lowercase()) {
        "dark" -> true
        "light" -> false
        else -> isSystemDark
    }

    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme
    val podiumColors = if (isDark) DarkPodiumColors else LightPodiumColors

    CompositionLocalProvider(LocalPodiumColors provides podiumColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
