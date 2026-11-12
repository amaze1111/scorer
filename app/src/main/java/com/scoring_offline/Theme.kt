package com.scoring_offline

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Amber = Color(0xFFFFC107)
val Good = Color(0xFF4CAF50)
val Bad = Color(0xFFF44336)

/** Screen backgrounds. Each game state has its own, so the state reads from across the table. */
val BgPlaying = Color(0xFF12161F)
val BgTarget = Color(0xFF5A3D00)
val BgTie = Color(0xFF5C2310)
val BgRoundEnd = Color(0xFF2C1F6B)
val BgGameOver = Color(0xFF0F5A36)

private val PlayerColors = listOf(Amber, Color(0xFF4FC3F7), Color(0xFF81C784))
fun colorOfPlayer(p: Int): Color = PlayerColors[p % PlayerColors.size]

/** One dark theme for phone and TV, matching the TV's amber-on-black look. */
private val Scheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3A2F00),
    onPrimaryContainer = Color(0xFFFFE082),
    secondary = Good,
    background = Color(0xFF0E0E10),
    onBackground = Color(0xFFECECEC),
    surface = Color(0xFF16161A),
    onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF212127),
    onSurfaceVariant = Color(0xFFB8B8C0),
    surfaceContainerHigh = Color(0xFF1C1C21),
    outline = Color(0xFF4A4A52),
)

@Composable
fun ScoreboardTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
