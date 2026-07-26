package org.example.overlay.inworld

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** WebSocket поверх `java.net.http` — тот же клиент, что обслуживает REST-запросы к бэкенду. */
class JdkRealtimeTransport(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    capacity: Int = DEFAULT_CAPACITY,
) : RealtimeTransport {
    private val messages = Channel<String>(capacity)
    private var socket: WebSocket? = null

    override val incoming: Flow<String> = messages.receiveAsFlow()

    override suspend fun connect(uri: URI, headers: Map<String, String>) {
        check(socket == null) { "Транспорт уже подключён" }

        val builder = httpClient.newWebSocketBuilder()
        headers.forEach(builder::header)
        socket = builder.buildAsync(uri, Listener(messages)).await()
    }

    override suspend fun send(text: String) {
        val activeSocket = checkNotNull(socket) { "Транспорт не подключён" }
        activeSocket.sendText(text, true).await()
    }

    override suspend fun close(code: Int, reason: String) {
        val activeSocket = socket ?: return
        socket = null
        runCatching { activeSocket.sendClose(code, reason).await() }
        messages.close()
    }

    internal class Listener(
        private val messages: Channel<String>,
    ) : WebSocket.Listener {
        private val assembler = TextFrameAssembler()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            val completeMessage = assembler.append(data, last)
            if (completeMessage != null && messages.trySend(completeMessage).isFailure) {
                webSocket.sendClose(1011, "incoming message queue overflow")
                messages.close(IllegalStateException("Очередь входящих сообщений переполнена"))
            }
            webSocket.request(1)
            return null
        }

        override fun onBinary(
            webSocket: WebSocket,
            data: ByteBuffer,
            last: Boolean,
        ): CompletionStage<*>? {
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            messages.close()
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            messages.close(error)
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
    }
}

/** Текстовый кадр может прийти фрагментами — собираем до `last = true`. */
internal class TextFrameAssembler {
    private val current = StringBuilder()

    fun append(fragment: CharSequence, last: Boolean): String? {
        current.append(fragment)
        if (!last) return null

        return current.toString().also { current.clear() }
    }
}

private suspend fun <T> CompletableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    whenComplete { result, error ->
        if (error == null) continuation.resume(result) else continuation.resumeWithException(error)
    }
    continuation.invokeOnCancellation { cancel(true) }
}
