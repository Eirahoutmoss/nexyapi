package com.eirahoutmoss.nexyapi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.eirahoutmoss.nexyapi.core.model.ModuleType

// Konsept görseline göre palet: koyu lacivert zemin, turuncu ana eylem, parlak mavi rapor düğmesi.
object NexColors {
    val Navy = Color(0xFF1E3A5F)
    val NavyDeep = Color(0xFF0B1320)
    val NavySurface = Color(0xFF131E30)
    val NavySurfaceHigh = Color(0xFF1B2940)
    val NavyOutline = Color(0xFF2A3A55)
    val Blue = Color(0xFF2F80ED)
    val BlueLight = Color(0xFF6EA8FF)
    val Orange = Color(0xFFF07A2E)
    val Green = Color(0xFF4CAF7D)
    val Amber = Color(0xFFF2B134)
    val Teal = Color(0xFF2EC4B6)
    val Red = Color(0xFFE5484D)
    val OffWhite = Color(0xFFF5F3EF)
}

/** Her modülün konseptteki kendi vurgu rengi. */
val ModuleType.accent: Color
    get() = when (this) {
        ModuleType.VIBRATION -> NexColors.Green
        ModuleType.CRACK -> NexColors.Amber
        ModuleType.MOTION -> NexColors.Teal
        ModuleType.BEFORE_DRILL -> NexColors.BlueLight
        ModuleType.ACOUSTICS -> NexColors.Orange
        ModuleType.HEAT_LEAK -> NexColors.Red
    }

private val Light = lightColorScheme(
    primary = NexColors.Navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3F5),
    onPrimaryContainer = NexColors.Navy,
    secondary = NexColors.Orange,
    onSecondary = Color.White,
    tertiary = NexColors.Blue,
    onTertiary = Color.White,
    background = NexColors.OffWhite,
    onBackground = Color(0xFF1A1D21),
    surface = Color.White,
    onSurface = Color(0xFF1A1D21),
    surfaceVariant = Color(0xFFE9E6E0),
    onSurfaceVariant = Color(0xFF5F666D),
    outline = Color(0xFFC9CDD2),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = NexColors.BlueLight,
    onPrimary = Color(0xFF06203F),
    primaryContainer = NexColors.NavySurfaceHigh,
    onPrimaryContainer = Color(0xFFDCE6F5),
    secondary = NexColors.Orange,
    onSecondary = Color.White,
    tertiary = NexColors.Blue,
    onTertiary = Color.White,
    background = NexColors.NavyDeep,
    onBackground = Color(0xFFE6EAF0),
    surface = NexColors.NavySurface,
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = NexColors.NavySurfaceHigh,
    onSurfaceVariant = Color(0xFF9AA6B8),
    surfaceContainer = NexColors.NavySurface,
    surfaceContainerHigh = NexColors.NavySurfaceHigh,
    outline = NexColors.NavyOutline,
    error = Color(0xFFFF8A80),
)

private val NexTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Ölçüm değerlerini göstermek için tek stil. */
val MeasurementValueStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)
val BigValueStyle = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold)

/** Tema telefonun açık/koyu ayarını izler. */
@Composable
fun NexYapiTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = NexTypography,
        content = content,
    )
}
