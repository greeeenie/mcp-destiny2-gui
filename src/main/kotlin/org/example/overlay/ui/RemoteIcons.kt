package org.example.overlay.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Иконка из ответа: перк или оружие с серверов Bungie.
 *
 * Пока грузится — пустое место того же размера, чтобы таблица не прыгала; не загрузилась —
 * тусклая заглушка. Alt в этом случае не рисуем: колонке из иконок он вернул бы ровно тот
 * текст, ради которого иконки и затевались.
 */
@Composable
fun RemoteIcon(url: String, contentDescription: String?, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = RemoteIcons.cached(url), url) {
        value = RemoteIcons.load(url)
    }

    val loaded = bitmap
    if (loaded != null) {
        Image(bitmap = loaded, contentDescription = contentDescription, modifier = modifier)
    } else {
        Box(
            modifier = modifier.background(
                color = OverlayColors.Surface.copy(alpha = 0.6f),
                shape = RoundedCornerShape(3.dp),
            ),
        )
    }
}

/**
 * Загрузка и кэш иконок.
 *
 * Ходим только на bungie.net: адрес в ответе — это текст модели, и оверлей не должен тащить
 * байты с произвольного хоста, который она сочинит. Кэш в памяти без вытеснения — иконки
 * по паре килобайт, а их набор за сессию ограничен арсеналом игрока.
 */
object RemoteIcons {

    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    /** Неудачные адреса, чтобы не долбить их на каждой перерисовке. */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ImageBitmap?>>()

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun cached(url: String): ImageBitmap? = cache[url]

    suspend fun load(url: String): ImageBitmap? {
        cache[url]?.let { return it }
        if (url in failed || !allowed(url)) return null

        val fresh = CompletableDeferred<ImageBitmap?>()
        inFlight.putIfAbsent(url, fresh)?.let { return it.await() }

        return try {
            val bitmap = fetch(url)
            if (bitmap != null) cache[url] = bitmap else failed += url
            bitmap
        } catch (error: Exception) {
            log.debug("Иконка не загрузилась: {} ({})", url, error.toString())
            failed += url
            null
        } finally {
            inFlight.remove(url)
            // Будят и тех, кто ждал этот же адрес параллельно.
            fresh.complete(cache[url])
        }
    }

    private suspend fun fetch(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        val body = response.body()
        if (response.statusCode() !in 200..299 || body.isEmpty() || body.size > MAX_BYTES) {
            null
        } else {
            SkiaImage.makeFromEncoded(body).toComposeImageBitmap()
        }
    }

    private fun allowed(url: String): Boolean {
        val host = runCatching { URI.create(url).host }.getOrNull() ?: return false
        return url.startsWith("https://") && (host == "www.bungie.net" || host == "bungie.net")
    }

    /** Иконки Bungie весят килобайты; всё сильно больше — не иконка. */
    private const val MAX_BYTES = 1_000_000

    private val log = LoggerFactory.getLogger(RemoteIcons::class.java)
}
