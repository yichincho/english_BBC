package com.yichincho.englishbbc.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF4353C8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E5FF),
    onPrimaryContainer = Color(0xFF1B2678),
    secondaryContainer = Color(0xFFE6E9F7),
    onSecondaryContainer = Color(0xFF2A3050),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF171A24),
    surface = Color(0xFFF7F8FC),
    onSurface = Color(0xFF171A24),
    surfaceVariant = Color(0xFFECEEF6),
    onSurfaceVariant = Color(0xFF5A6075),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    outline = Color(0xFFB9BDCD),
    outlineVariant = Color(0xFFE1E3EC),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB7BFFF),
    onPrimary = Color(0xFF16206E),
    primaryContainer = Color(0xFF2B3585),
    onPrimaryContainer = Color(0xFFE1E5FF),
    secondaryContainer = Color(0xFF2A2F42),
    onSecondaryContainer = Color(0xFFDDE1F5),
    background = Color(0xFF10131A),
    onBackground = Color(0xFFE6E8F0),
    surface = Color(0xFF10131A),
    onSurface = Color(0xFFE6E8F0),
    surfaceVariant = Color(0xFF232735),
    onSurfaceVariant = Color(0xFFA6ABBE),
    surfaceContainer = Color(0xFF1A1D28),
    surfaceContainerHigh = Color(0xFF1A1D28),
    surfaceContainerLow = Color(0xFF1A1D28),
    outline = Color(0xFF4A4F63),
    outlineVariant = Color(0xFF2C3040),
    error = Color(0xFFF2B8B5),
)

/** Colours for status pills that Material's scheme has no slot for. */
data class StatusColors(val okBg: Color, val okFg: Color, val warnBg: Color, val warnFg: Color)

private val LightStatus = StatusColors(Color(0xFFDFF3E4), Color(0xFF1B6B35), Color(0xFFFCEFD3), Color(0xFF7A5200))
private val DarkStatus = StatusColors(Color(0xFF1D3B27), Color(0xFF9FDDB0), Color(0xFF3F3113), Color(0xFFF2CF7E))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}
