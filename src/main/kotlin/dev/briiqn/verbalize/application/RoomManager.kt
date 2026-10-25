package dev.briiqn.verbalize.application

import dev.briiqn.verbalize.infrastructure.ktor.KtorSessionHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class Room(
    val code: String,
    val name: String,
    val isPrivate: Boolean,
    val owner: String,
    val hub: KtorSessionHub,
    val game: GameLoopService,
    val scope: CoroutineScope,
    val worldSeed: Long
) {
    @Volatile var lastEmptySinceMs: Long = System.currentTimeMillis()
}

/**
 * In-memory registry of active rooms. Each room owns an isolated world on its
 * own single-writer dispatcher. Empty non-lobby rooms are torn down (loop
 * cancelled, memory released) after an idle period.
 */
class RoomManager(
    private val buildRoom: (code: String, name: String, isPrivate: Boolean, owner: String) -> Room,
    cleanupScope: CoroutineScope
) {
    private val log = LoggerFactory.getLogger(RoomManager::class.java)
    private val rooms = ConcurrentHashMap<String, Room>()

    companion object {
        const val LOBBY_CODE = "LOBBY"
        private const val IDLE_TTL_MS = 10 * 60 * 1000L
        private const val MAX_ROOMS = 64
        private val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray()
    }

    init {
        rooms[LOBBY_CODE] = buildRoom(LOBBY_CODE, "The Village Lobby", false, "server")
        cleanupScope.launch {
            while (isActive) {
                delay(60_000)
                sweepIdleRooms()
            }
        }
    }

    fun get(code: String): Room? = rooms[code.uppercase()]

    fun allRooms(): Collection<Room> = rooms.values

    fun listPublic(): List<Room> = rooms.values.filter { !it.isPrivate }.sortedBy { it.code != LOBBY_CODE }

    fun create(name: String, isPrivate: Boolean, owner: String): Room? {
        if (rooms.size >= MAX_ROOMS) return null
        var code: String
        do {
            code = (1..6).map { CODE_ALPHABET[Random.nextInt(CODE_ALPHABET.size)] }.joinToString("")
        } while (rooms.containsKey(code))
        val room = buildRoom(code, name.ifBlank { "$owner's room" }.take(40), isPrivate, owner)
        rooms[code] = room
        log.info("[rooms] created {} '{}' ({}) by {}", code, room.name, if (isPrivate) "private" else "public", owner)
        return room
    }

    fun remove(code: String, requester: String): Boolean {
        if (code == LOBBY_CODE) return false
        val room = rooms[code] ?: return false
        if (!room.owner.equals(requester, ignoreCase = true)) return false
        destroy(room)
        return true
    }

    private fun destroy(room: Room) {
        rooms.remove(room.code)
        room.scope.cancel()
        log.info("[rooms] destroyed {} '{}'", room.code, room.name)
    }

    private fun sweepIdleRooms() {
        val now = System.currentTimeMillis()
        for (room in rooms.values) {
            if (room.code == LOBBY_CODE) continue
            if (room.game.playerCount() > 0) {
                room.lastEmptySinceMs = now
            } else if (now - room.lastEmptySinceMs > IDLE_TTL_MS) {
                destroy(room)
            }
        }
    }
}
