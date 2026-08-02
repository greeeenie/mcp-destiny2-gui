package org.example.overlay.platform

import org.slf4j.LoggerFactory
import java.awt.GraphicsEnvironment

/**
 * Выбор стартового положения HUD (риск 11).
 *
 * Сохранённое положение годится, только если центр окна попадает на существующий экран:
 * иначе после отключения второго монитора оверлей окажется «за краем мира» и игрок его не найдёт.
 * Запасной вариант — правый верхний угол основного экрана.
 *
 * Границы экранов AWT отдаёт в физических пикселях, а Compose позиционирует окно в Dp,
 * поэтому пиксели делятся на масштаб DPI этого экрана. При разном масштабе на разных
 * мониторах это приближение — но нам нужен только ответ «окно на экране или нет».
 */
object ScreenPlacement {

    data class Placement(val x: Float, val y: Float)

    private const val MARGIN_DP = 24f
    private val log = LoggerFactory.getLogger(ScreenPlacement::class.java)

    fun resolve(savedX: Float?, savedY: Float?, widthDp: Float, heightDp: Float): Placement {
        val screens = screensInDp()
        if (savedX != null && savedY != null) {
            val centerX = savedX + widthDp / 2
            val centerY = savedY + heightDp / 2
            if (screens.any { it.contains(centerX, centerY) }) return Placement(savedX, savedY)
            log.info("Сохранённое положение HUD ({}, {}) вне экранов — ставлю в угол основного", savedX, savedY)
        }
        val primary = screens.firstOrNull() ?: return Placement(MARGIN_DP, MARGIN_DP)
        return Placement(
            x = primary.x + primary.width - widthDp - MARGIN_DP,
            y = primary.y + MARGIN_DP,
        )
    }

    /**
     * Положение окна после программной смены размера.
     *
     * Окно якорится к тому краю экрана, к которому стоит ближе: у правого края фиксируется
     * правый край окна, у нижнего — нижний. Иначе HUD в правом углу вёл себя как прибитый
     * за левый угол: ответ расширял окно, кламп сдвигал его влево, а сужение на следующем
     * ходе оставляло окно в этом сдвинутом месте — и оно шаг за шагом уезжало от края.
     */
    fun resize(
        x: Float,
        y: Float,
        oldWidth: Float,
        oldHeight: Float,
        newWidth: Float,
        newHeight: Float,
    ): Placement {
        val screen = screensInDp().firstOrNull { it.contains(x + oldWidth / 2, y + oldHeight / 2) }
            ?: screensInDp().firstOrNull()
        val anchoredX = if (screen != null && anchorsFarEdge(x, oldWidth, screen.x, screen.width)) {
            x + oldWidth - newWidth
        } else {
            x
        }
        val anchoredY = if (screen != null && anchorsFarEdge(y, oldHeight, screen.y, screen.height)) {
            y + oldHeight - newHeight
        } else {
            y
        }
        return clampToScreen(anchoredX, anchoredY, newWidth, newHeight)
    }

    data class Anchors(val end: Boolean, val bottom: Boolean)

    data class Capacity(val width: Float, val height: Float)

    /**
     * Сколько места есть у панели в направлении её раскрытия на текущем экране. На узком или
     * HiDPI-мониторе это число может быть меньше глобального MAX — тогда HUD переносит текст,
     * а не раскрывается за границу экрана. Минимальный размер сохраняем даже на совсем маленьком
     * экране: пилюля всё равно остаётся видимой, а панель не получает невозможные constraints.
     */
    fun anchoredCapacity(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        anchors: Anchors,
        minimumWidth: Float,
        minimumHeight: Float,
        maximumWidth: Float,
        maximumHeight: Float,
    ): Capacity {
        val screens = screensInDp()
        val screen = screens.firstOrNull { it.contains(x + width / 2, y + height / 2) }
            ?: screens.firstOrNull()
            ?: return Capacity(maximumWidth, maximumHeight)
        val availableWidth = if (anchors.end) {
            x + width - screen.x
        } else {
            screen.x + screen.width - MARGIN_DP - x
        }
        val availableHeight = if (anchors.bottom) {
            y + height - screen.y
        } else {
            screen.y + screen.height - MARGIN_DP - y
        }
        return Capacity(
            width = availableWidth.coerceIn(minimumWidth, maximumWidth),
            height = availableHeight.coerceIn(minimumHeight, maximumHeight),
        )
    }

    /** К каким краям экрана окно прижато: к тем же краям прижимается панель внутри кожуха. */
    fun anchors(x: Float, y: Float, width: Float, height: Float): Anchors {
        val screen = screensInDp().firstOrNull { it.contains(x + width / 2, y + height / 2) }
            ?: screensInDp().firstOrNull()
            ?: return Anchors(end = false, bottom = false)
        return Anchors(
            end = anchorsFarEdge(x, width, screen.x, screen.width),
            bottom = anchorsFarEdge(y, height, screen.y, screen.height),
        )
    }

    /** Ближе ли окно к дальнему краю оси (правому или нижнему), чем к ближнему. */
    private fun anchorsFarEdge(position: Float, size: Float, screenStart: Float, screenSize: Float): Boolean {
        val nearGap = position - screenStart
        val farGap = screenStart + screenSize - (position + size)
        return farGap < nearGap
    }

    /**
     * Не дать окну уехать за край экрана после того, как оно выросло под содержимое.
     * По умолчанию HUD стоит в правом верхнем углу, поэтому расширение вправо без этого
     * уводило бы половину таблицы за границу.
     */
    fun clampToScreen(x: Float, y: Float, width: Float, height: Float): Placement {
        val screens = screensInDp()
        // На стыке мониторов top-left и центр могут оказаться на разных экранах. Пользователь
        // воспринимает окно по центру видимой панели; сначала сохраняем именно этот монитор.
        val screen = screens.firstOrNull { it.contains(x + width / 2, y + height / 2) }
            ?: screens.firstOrNull { it.contains(x + 1, y + 1) }
            ?: screens.firstOrNull()
            ?: return Placement(x, y)
        val maxX = screen.x + screen.width - width - MARGIN_DP
        val maxY = screen.y + screen.height - height - MARGIN_DP
        return Placement(
            x = x.coerceIn(screen.x, maxOf(screen.x, maxX)),
            y = y.coerceIn(screen.y, maxOf(screen.y, maxY)),
        )
    }

    private data class ScreenRect(val x: Float, val y: Float, val width: Float, val height: Float) {
        fun contains(px: Float, py: Float): Boolean =
            px >= x && px <= x + width && py >= y && py <= y + height
    }

    /** Основной экран идёт первым — на него уезжает HUD, если сохранённое место исчезло. */
    private fun screensInDp(): List<ScreenRect> = try {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val primary = environment.defaultScreenDevice
        val ordered = listOf(primary) + environment.screenDevices.filter { it != primary }
        ordered.map { device ->
            val configuration = device.defaultConfiguration
            val bounds = configuration.bounds
            val scaleX = configuration.defaultTransform.scaleX.takeIf { it > 0.0 } ?: 1.0
            val scaleY = configuration.defaultTransform.scaleY.takeIf { it > 0.0 } ?: 1.0
            ScreenRect(
                x = (bounds.x / scaleX).toFloat(),
                y = (bounds.y / scaleY).toFloat(),
                width = (bounds.width / scaleX).toFloat(),
                height = (bounds.height / scaleY).toFloat(),
            )
        }
    } catch (error: Exception) {
        log.warn("Не удалось получить границы экранов: {}", error.toString())
        emptyList()
    }
}
