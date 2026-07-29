package org.example.overlay.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.example.overlay.app.OverlayStatus

object OverlayColors {
    val Background = Color(0xFF12151A)
    val Surface = Color(0xFF1B1F26)
    val Accent = Color(0xFF7AC7FF)
    val Text = Color(0xFFE8ECF2)
    val TextDim = Color(0xFF97A1B0)
    val Ok = Color(0xFF6BD68A)
    val Warn = Color(0xFFE7B75A)
    val Error = Color(0xFFE86A6A)
}

@Composable
fun OverlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = OverlayColors.Accent,
            background = OverlayColors.Background,
            surface = OverlayColors.Surface,
            onPrimary = OverlayColors.Background,
            onBackground = OverlayColors.Text,
            onSurface = OverlayColors.Text,
        ),
        content = content,
    )
}

/** Цвет точки статуса: игрок читает её боковым зрением, поэтому смысл несёт цвет, а не текст. */
fun OverlayStatus.dotColor(): Color = when (this) {
    OverlayStatus.Disconnected -> OverlayColors.TextDim
    OverlayStatus.Ready -> OverlayColors.TextDim
    OverlayStatus.Connecting -> OverlayColors.Warn
    OverlayStatus.Listening -> OverlayColors.Ok
    OverlayStatus.Thinking -> OverlayColors.Accent
    OverlayStatus.Speaking -> OverlayColors.Accent
    OverlayStatus.Answering -> OverlayColors.Accent
    is OverlayStatus.Reconnecting -> OverlayColors.Warn
    is OverlayStatus.Failed -> OverlayColors.Error
}
