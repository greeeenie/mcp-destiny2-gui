package org.example.overlay.audio

import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Line
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/** `mixer == null` — «системное устройство по умолчанию». */
data class AudioDevice(val name: String, val mixer: Mixer.Info?)

/**
 * Перечисление звуковых устройств (риск 3 плана).
 *
 * JavaSound фиксирует микшер в момент `open()` и не следует за сменой системного устройства
 * по умолчанию, а список `Mixer.Info` кэшируется JVM при старте. Поэтому выбор делает игрок,
 * а не мы, и рядом со списком в UI есть кнопка «Обновить».
 */
object AudioDevices {

    const val SYSTEM_DEFAULT = "System default"

    fun inputs(): List<AudioDevice> = list(TargetDataLine::class.java)

    fun outputs(): List<AudioDevice> = list(SourceDataLine::class.java)

    /** Сохранённое имя устройства могло исчезнуть — тогда молча откатываемся на системное. */
    fun resolve(name: String?, devices: List<AudioDevice>): Mixer.Info? =
        name?.let { wanted -> devices.firstOrNull { it.name == wanted }?.mixer }

    private fun list(lineClass: Class<*>): List<AudioDevice> {
        val available = AudioSystem.getMixerInfo()
            .filter { info ->
                runCatching { AudioSystem.getMixer(info).isLineSupported(Line.Info(lineClass)) }
                    .getOrDefault(false)
            }
            .map { AudioDevice(it.name, it) }
            .distinctBy { it.name }
        return listOf(AudioDevice(SYSTEM_DEFAULT, null)) + available
    }
}
