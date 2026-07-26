package org.example.overlay.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter

/**
 * Иконка в трее рисуется кодом, а не берётся из файла: одна точка в фирменном цвете
 * не стоит бинарного ресурса в репозитории.
 */
object TrayIconPainter : Painter() {
    override val intrinsicSize: Size = Size(16f, 16f)

    override fun DrawScope.onDraw() {
        drawCircle(color = OverlayColors.Accent, radius = size.minDimension / 2)
    }
}
