package com.azhidkov.mystuff.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF365E3A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F1B7),
    onPrimaryContainer = Color(0xFF002108),
    secondary = Color(0xFF52634F),
    background = Color(0xFFF8FAF4),
    surface = Color(0xFFF8FAF4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DD49D),
    onPrimary = Color(0xFF073912),
    primaryContainer = Color(0xFF1E5024),
    onPrimaryContainer = Color(0xFFB8F1B7),
    secondary = Color(0xFFB9CCB3),
    background = Color(0xFF10140F),
    surface = Color(0xFF10140F),
)

@Composable
fun MyStuffTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
