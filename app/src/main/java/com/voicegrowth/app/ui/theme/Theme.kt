package com.voicegrowth.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = TealPrimary,
    onPrimary = TealOnPrimary,
    primaryContainer = TealContainer,
    onPrimaryContainer = TealOnContainer,
    secondary = Secondary,
    secondaryContainer = SecondaryContainer,
    background = BackgroundLight,
    surface = SurfaceLight,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant
)

@Composable
fun VoiceGrowthTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}
