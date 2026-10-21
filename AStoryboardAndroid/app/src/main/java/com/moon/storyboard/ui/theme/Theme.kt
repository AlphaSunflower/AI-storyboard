package com.moon.storyboard.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MoonColors = lightColorScheme(    primary = Color(0xFFCC785C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF2DCD3),
    onPrimaryContainer = Color(0xFF4A2419),
    secondary = Color(0xFF6B6560),
    onSecondary = Color.White,
    background = Color(0xFFFAF9F5),
    onBackground = Color(0xFF141413),
    surface = Color(0xFFFAF9F5),
    onSurface = Color(0xFF141413),
    surfaceVariant = Color(0xFFEFE9DE),
    onSurfaceVariant = Color(0xFF6B6560),
    outline = Color(0xFFE0D8CA),
    outlineVariant = Color(0xFFEFE9DE),
    error = Color(0xFFC44D4D),
)

@Composable
fun MoonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MoonColors,
        typography = Typography(),
        content = content,
    )
}

/** 暖纸底色 + 单一珊瑚强调色；不要在这里加第二种强调色。 */
object MoonPalette {
    val Canvas = Color(0xFFFAF9F5)
    val Ink = Color(0xFF141413)
    val Body = Color(0xFF4B4742)
    val Muted = Color(0xFF8A837B)
    val Hairline = Color(0xFFE7DFD2)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceWarm = Color(0xFFF2ECE1)
    val Primary = Color(0xFFCC785C)
    val PrimarySoft = Color(0xFFF4E2D9)
    val Success = Color(0xFF4A9D6E)
    val Danger = Color(0xFFC44D4D)
}

