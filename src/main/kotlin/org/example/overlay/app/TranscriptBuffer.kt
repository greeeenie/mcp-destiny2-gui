package org.example.overlay.app

/**
 * Склейка транскрипта из дельт. Живёт до конца хода: как только после финальной реплики
 * приходит новая дельта, буфер начинается заново (§5.3).
 */
class TranscriptBuffer {
    private val text = StringBuilder()
    private var finalized = false

    fun accept(delta: String, isFinal: Boolean): String {
        if (isFinal) {
            text.setLength(0)
            text.append(delta)
            finalized = true
            return text.toString()
        }
        if (finalized) {
            text.setLength(0)
            finalized = false
        }
        text.append(delta)
        return text.toString()
    }

    fun clear(): String {
        text.setLength(0)
        finalized = false
        return ""
    }

    val isFinal: Boolean get() = finalized
}
