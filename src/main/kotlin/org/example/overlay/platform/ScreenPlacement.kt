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
     * Не дать окну уехать за край экрана после того, как оно выросло под содержимое.
     * По умолчанию HUD стоит в правом верхнем углу, поэтому расширение вправо без этого
     * уводило бы половину таблицы за границу.
     */
    fun clampToScreen(x: Float, y: Float, width: Float, height: Float): Placement {
        val screen = screensInDp().firstOrNull { it.contains(x + 1, y + 1) }
            ?: screensInDp().firstOrNull()
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
