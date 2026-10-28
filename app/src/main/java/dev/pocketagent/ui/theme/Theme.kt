package dev.pocketagent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BE9C1),
    onPrimary = Color(0xFF062D22),
    primaryContainer = Color(0xFF173D32),
    onPrimaryContainer = Color(0xFFD5FAEC),
    secondary = Color(0xFFAFCCBF),
    onSecondary = Color(0xFF17352A),
    secondaryContainer = Color(0xFF233C32),
    onSecondaryContainer = Color(0xFFE0EEE6),
    tertiary = Color(0xFFF3C677),
    onTertiary = Color(0xFF3A2700),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFF2F6FA),
    surface = Color(0xFF151B23),
    onSurface = Color(0xFFF0F5FA),
    surfaceVariant = Color(0xFF202832),
    onSurfaceVariant = Color(0xFFC2CFDC),
    outline = Color(0xFF657181),
    outlineVariant = Color(0xFF303A46),
    surfaceDim = Color(0xFF0D1117),
    surfaceBright = Color(0xFF303A46),
    surfaceContainerLowest = Color(0xFF090D12),
    surfaceContainerLow = Color(0xFF11171F),
    surfaceContainer = Color(0xFF19212B),
    surfaceContainerHigh = Color(0xFF202A35),
    surfaceContainerHighest = Color(0xFF283340),
    surfaceTint = Color(0xFF8BE9C1),
    inverseSurface = Color(0xFFE1E7ED),
    inverseOnSurface = Color(0xFF19212B),
    inversePrimary = Color(0xFF087F5B),
    errorContainer = Color(0xFF492923),
    onErrorContainer = Color(0xFFFFDAD5),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = TextStyle(
        fontSize = 16.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium,
    ),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF087F5B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8F5E8),
    onPrimaryContainer = Color(0xFF073A2B),
    secondary = Color(0xFF49675A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0EEE6),
    onSecondaryContainer = Color(0xFF243C31),
    tertiary = Color(0xFF805600),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE7B3),
    onTertiaryContainer = Color(0xFF3D2A00),
    background = Color(0xFFF7F9FB),
    onBackground = Color(0xFF17212B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17212B),
    surfaceVariant = Color(0xFFEDF1F5),
    onSurfaceVariant = Color(0xFF4B5B6B),
    outline = Color(0xFF758191),
    outlineVariant = Color(0xFFDCE2E8),
    surfaceDim = Color(0xFFE7ECF0),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F9FB),
    surfaceContainer = Color(0xFFF0F3F6),
    surfaceContainerHigh = Color(0xFFE9EEF2),
    surfaceContainerHighest = Color(0xFFE2E8EE),
    surfaceTint = Color(0xFF087F5B),
    inverseSurface = Color(0xFF24303C),
    inverseOnSurface = Color(0xFFF0F3F6),
    inversePrimary = Color(0xFF8BE9C1),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        fun fromStorage(value: String?): ThemePreference =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

@Composable
fun isDarkTheme(preference: ThemePreference): Boolean = when (preference) {
    ThemePreference.SYSTEM -> isSystemInDarkTheme()
    ThemePreference.LIGHT -> false
    ThemePreference.DARK -> true
}

@Composable
fun PocketAgentTheme(
    preference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = isDarkTheme(preference)
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
