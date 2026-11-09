package com.screenshotbrain

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun SbTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(
        background = Color(0xFF0F0F0F), surface = Color(0xFF1A1A19),
        onBackground = Color(0xFFEDEDEA), onSurface = Color(0xFFEDEDEA),
        onSurfaceVariant = Color(0xFF8E8E89), primary = Color(0xFFEDEDEA),
        onPrimary = Color(0xFF0F0F0F), outline = Color(0xFF2E2E2B),
        surfaceVariant = Color(0xFF1F1F1D)
    ) else lightColorScheme(
        background = Color(0xFFF6F5F2), surface = Color(0xFFFFFFFF),
        onBackground = Color(0xFF1B1B1A), onSurface = Color(0xFF1B1B1A),
        onSurfaceVariant = Color(0xFF8A8A85), primary = Color(0xFF1B1B1A),
        onPrimary = Color(0xFFF6F5F2), outline = Color(0xFFE4E2DC),
        surfaceVariant = Color(0xFFECEAE4)
    )
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
