package dev.briiqn.verbalize.infrastructure.ktor

import dev.briiqn.verbalize.domain.model.NetId
import dev.briiqn.verbalize.domain.ports.SessionHub
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe outbound transport. Each session gets a bounded channel with
 * drop-oldest overflow: a slow or stalled client can never grow server memory,
 * and all session resources are torn down on unregister.
 */
class KtorSessionHub : SessionHub {
    private class Outbound(val channel: Channel<ByteArray>)

    private val sessions = ConcurrentHashMap<Int, Outbound>()

    fun register(netId: NetId, session: WebSocketSession, scope: CoroutineScope) {
        val outbound = Outbound(Channel(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST))
        sessions[netId.value] = outbound
        scope.launch {
            try {
                for (frame in outbound.channel) {
                    if (!isActive) break
                    session.send(Frame.Binary(true, frame))
                }
            } finally {
                sessions.remove(netId.value, outbound)
            }
        }
    }

    fun unregister(netId: NetId) {
        sessions.remove(netId.value)?.channel?.close()
    }

    override fun send(netId: NetId, frame: ByteArray) {
        sessions[netId.value]?.channel?.trySend(frame)
    }

    override fun broadcast(frame: ByteArray, except: NetId?) {
        for ((id, outbound) in sessions) {
            if (except != null && id == except.value) continue
            outbound.channel.trySend(frame)
        }
    }
}
