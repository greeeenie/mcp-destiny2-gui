package org.example.overlay.inworld

import kotlinx.coroutines.flow.Flow
import java.net.URI

interface RealtimeTransport {
    val incoming: Flow<String>

    suspend fun connect(uri: URI, headers: Map<String, String>)

    suspend fun send(text: String)

    suspend fun close(code: Int = 1000, reason: String = "normal")
}

fun interface RealtimeTransportFactory {
    fun create(): RealtimeTransport
}
