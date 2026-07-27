package org.example.overlay.markdown

/**
 * Адреса картинок в ответе.
 *
 * Инструменты отдают `iconPath` относительным путём Bungie (`/common/destiny2_content/...`),
 * и модель нередко вставляет его как есть — достраиваем хост сами. Всё, что не похоже ни на
 * такой путь, ни на http(s)-адрес, отбрасывается ещё на разборе: рисовать это нечем.
 */
internal object MdImages {

    private const val BUNGIE_BASE = "https://www.bungie.net"

    fun resolve(raw: String?): String? {
        val url = raw?.trim().orEmpty()
        return when {
            url.isEmpty() -> null
            url.startsWith("/") -> BUNGIE_BASE + url
            url.startsWith("https://") || url.startsWith("http://") -> url
            else -> null
        }
    }
}
