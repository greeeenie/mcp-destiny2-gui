package org.example.overlay.update

/**
 * Сравнение версий вида `1.2.3`. Ничего сверх чисел через точку не поддерживается сознательно:
 * релизы нумеруются только так, а полноценный semver с пре-релизами здесь не нужен.
 */
object Versions {
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate)
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (diff != 0) return diff > 0
        }
        return false
    }

    /** Нечисловой хвост части отбрасывается: "1.0.1-beta" сравнивается как 1.0.1. */
    private fun parse(raw: String): List<Int> = raw.trim().removePrefix("v")
        .split('.')
        .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
