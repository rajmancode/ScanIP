package com.scanip.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// RajmanTech brand palette (see the logo in CameraMapper3D/branding).
val BrandTeal = Color(0xFF0D9488)
val BrandTealLight = Color(0xFF2DD4BF)
val BrandNavy = Color(0xFF0F2A44)
val BrandAmber = Color(0xFFF59E0B)

private val Light = lightColorScheme(
    primary = BrandTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCF3EE),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = BrandNavy,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E4F2),
    onSecondaryContainer = BrandNavy,
    tertiary = Color(0xFFB45309),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF451A03),
)

private val Dark = darkColorScheme(
    primary = BrandTealLight,
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF0B5D56),
    onPrimaryContainer = Color(0xFFCCF3EE),
    secondary = Color(0xFFA9C7E6),
    onSecondary = BrandNavy,
    secondaryContainer = Color(0xFF1E3A5A),
    onSecondaryContainer = Color(0xFFD6E4F2),
    tertiary = BrandAmber,
    onTertiary = Color(0xFF451A03),
    tertiaryContainer = Color(0xFF78350F),
    onTertiaryContainer = Color(0xFFFEF3C7),
    background = Color(0xFF0B1724),
    surface = Color(0xFF0B1724),
    surfaceContainer = Color(0xFF132233),
    surfaceContainerLow = Color(0xFF10202F),
    surfaceContainerHigh = Color(0xFF1A2B3D),
    surfaceContainerHighest = Color(0xFF223548),
)

val OnlineGreen = Color(0xFF16A34A)

@Composable
fun ScanIPTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
