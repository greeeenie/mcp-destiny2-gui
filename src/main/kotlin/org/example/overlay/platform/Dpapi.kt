package org.example.overlay.platform

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Crypt32Util

/**
 * DPAPI (`CryptProtectData`) в области текущего пользователя — так сессионный токен и BYOK-ключи
 * не лежит на диске открытым текстом (риск 10 плана). Ключ держит Windows, расшифровать файл
 * может только этот пользователь на этой машине.
 */
object Dpapi {

    val isAvailable: Boolean get() = Platform.isWindows()

    fun protect(data: ByteArray): ByteArray {
        require(isAvailable) { "DPAPI is only available on Windows" }
        return Crypt32Util.cryptProtectData(data)
    }

    fun unprotect(data: ByteArray): ByteArray {
        require(isAvailable) { "DPAPI is only available on Windows" }
        return Crypt32Util.cryptUnprotectData(data)
    }
}
