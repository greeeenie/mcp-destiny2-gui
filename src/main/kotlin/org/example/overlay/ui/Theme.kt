package org.example.overlay.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.example.overlay.app.OverlayStatus

object OverlayColors {
    val Background = Color(0xFF0E1212)
    val Surface = Color(0xFF171C1D)
    val Header = Color(0xFF222727)
    val HeaderEnd = Color(0xFF181D1E)
    val Control = Color(0xFF202526)
    val ControlSelected = Color(0xFF292E2F)
    val ControlBorder = Color(0xFF454B4C)
    val ControlBorderStrong = Color(0xFF5B6263)
    val Divider = Color(0xFF303637)
    val Accent = Color(0xFF5DDCF4)
    val Text = Color(0xFFE9ECEC)
    val TextMuted = Color(0xFFCDD1D1)
    val TextDim = Color(0xFFA5ABAC)
    val TextFaint = Color(0xFF737B7C)
    val Ok = Color(0xFF6BD68A)
    val Warn = Color(0xFFE7B75A)
    val Error = Color(0xFFE86A6A)
}

@Composable
fun OverlayTheme(content: @Composable () -> Unit) {
    val square = RoundedCornerShape(0.dp)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = OverlayColors.Accent,
            background = OverlayColors.Background,
            surface = OverlayColors.Surface,
            onPrimary = OverlayColors.Background,
            onBackground = OverlayColors.Text,
            onSurface = OverlayColors.Text,
        ),
        shapes = Shapes(
            extraSmall = square,
            small = square,
            medium = square,
            large = square,
            extraLarge = square,
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
    OverlayStatus.SearchingWeb -> OverlayColors.Accent
    OverlayStatus.Speaking -> OverlayColors.Accent
    OverlayStatus.Answering -> OverlayColors.Accent
    is OverlayStatus.Reconnecting -> OverlayColors.Warn
    is OverlayStatus.Failed -> OverlayColors.Error
}
