package dev.briiqn.verbalize.infrastructure.ktor

import dev.briiqn.verbalize.application.AuthOutcome
import dev.briiqn.verbalize.application.AuthService
import dev.briiqn.verbalize.application.GameLoopService
import dev.briiqn.verbalize.application.Room
import dev.briiqn.verbalize.application.RoomManager
import dev.briiqn.verbalize.application.buildStandaloneRejectFrame
import dev.briiqn.verbalize.domain.model.NetId
import dev.briiqn.verbalize.infrastructure.codec.PacketCompressor
import dev.briiqn.verbalize.infrastructure.proto.ClientEnvelope
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

private val json = Json { ignoreUnknownKeys = true }

private fun roomJson(room: Room) = buildJsonObject {
    put("code", room.code)
    put("name", room.name)
    put("private", room.isPrivate)
    put("owner", room.owner)
    put("players", room.game.playerCount())
}

fun startServer(
    port: Int,
    distDir: File,
    roomManager: RoomManager,
    authService: AuthService
) = embeddedServer(Netty, port = port) {
    install(WebSockets) {
        pingPeriodMillis = 20_000
        timeoutMillis = 30_000
        maxFrameSize = 1 shl 20
    }

    suspend fun io.ktor.server.application.ApplicationCall.authenticate(): String? {
        val header = request.header("Authorization") ?: return null
        if (!header.startsWith("Basic ", ignoreCase = true)) return null
        val decoded = try {
            String(Base64.getDecoder().decode(header.substring(6).trim()))
        } catch (e: Exception) {
            return null
        }
        val sep = decoded.indexOf(':')
        if (sep <= 0) return null
        val username = decoded.substring(0, sep)
        val password = decoded.substring(sep + 1)
        return when (val outcome = withContext(Dispatchers.IO) { authService.verify(username, password) }) {
            is AuthOutcome.Success -> outcome.account.username
            is AuthOutcome.Rejected -> null
        }
    }

    routing {
        post("/api/auth/login") {
            val payload = try {
                json.parseToJsonElement(call.receiveText()).jsonObject
            } catch (e: Exception) {
                call.respondText("{\"error\":\"bad json\"}", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }
            val username = payload["username"]?.jsonPrimitive?.content ?: ""
            val password = payload["password"]?.jsonPrimitive?.content ?: ""
            val outcome = withContext(Dispatchers.IO) { authService.authenticateOrRegister(username, password) }
            when (outcome) {
                is AuthOutcome.Success -> {
                    val body = buildJsonObject {
                        put("ok", true)
                        put("username", outcome.account.username)
                        val avatar = outcome.account.avatar
                        if (avatar != null) {
                            put("avatar", buildJsonObject {
                                put("hat", avatar.hat)
                                put("bodyColorHex", avatar.bodyColorHex)
                                put("eyes", avatar.eyes)
                                put("mouth", avatar.mouth)
                                put("hair", avatar.hair)
                            })
                        }
                    }
                    call.respondText(body.toString(), ContentType.Application.Json)
                }
                is AuthOutcome.Rejected -> call.respondText(
                    buildJsonObject { put("error", outcome.reason) }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized
                )
            }
        }

        get("/api/rooms") {
            val body = buildJsonArray {
                for (room in roomManager.listPublic()) add(roomJson(room))
            }
            call.respondText(body.toString(), ContentType.Application.Json)
        }

        post("/api/rooms") {
            val user = call.authenticate()
            if (user == null) {
                call.respondText(
                    buildJsonObject { put("error", "authentication required (Basic auth with your game account)") }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized
                )
                return@post
            }
            val payload = try {
                json.parseToJsonElement(call.receiveText()).jsonObject
            } catch (e: Exception) {
                buildJsonObject {}
            }
            val name = payload["name"]?.jsonPrimitive?.content ?: ""
            val isPrivate = payload["private"]?.jsonPrimitive?.booleanOrNull ?: false
            val room = roomManager.create(name, isPrivate, user)
            if (room == null) {
                call.respondText(
                    buildJsonObject { put("error", "room limit reached") }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.TooManyRequests
                )
                return@post
            }
            call.respondText(roomJson(room).toString(), ContentType.Application.Json, HttpStatusCode.Created)
        }

        delete("/api/rooms/{code}") {
            val user = call.authenticate()
            if (user == null) {
                call.respondText("{\"error\":\"authentication required\"}", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@delete
            }
            val code = call.parameters["code"]?.uppercase() ?: ""
            if (roomManager.remove(code, user)) {
                call.respondText("{\"ok\":true}", ContentType.Application.Json)
            } else {
                call.respondText("{\"error\":\"not found or not owner\"}", ContentType.Application.Json, HttpStatusCode.Forbidden)
            }
        }

        staticFiles("/", distDir) {
            default("index.html")
        }

        webSocket("/ws/verbalize") {
            val assignedId = AtomicInteger(-1)
            var room: Room? = null
            val session = this
            val sendRaw: (ByteArray) -> Unit = { bytes ->
                session.launch { session.send(Frame.Binary(true, bytes)) }
            }
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Binary) continue
                    val bytes = frame.readBytes()

                    val activeRoom = room
                    if (activeRoom == null) {
                        // First meaningful frame must be a Join carrying the room code
                        val envelope = try {
                            ClientEnvelope.parseFrom(PacketCompressor.unpack(bytes))
                        } catch (e: Exception) {
                            continue
                        }
                        if (!envelope.hasJoin()) continue
                        val code = envelope.join.roomCode.ifBlank { RoomManager.LOBBY_CODE }.uppercase()
                        val resolved = roomManager.get(code)
                        if (resolved == null) {
                            sendRaw(buildStandaloneRejectFrame("Room '$code' not found."))
                            continue
                        }
                        room = resolved
                        resolved.game.submit(
                            GameLoopService.Command.Inbound(
                                null,
                                bytes,
                                attach = { assigned ->
                                    assignedId.set(assigned)
                                    resolved.hub.register(NetId(assigned), session, session)
                                },
                                sendRaw = sendRaw
                            )
                        )
                        continue
                    }

                    val current = assignedId.get().let { if (it >= 0) it else null }
                    activeRoom.game.submit(
                        GameLoopService.Command.Inbound(
                            current,
                            bytes,
                            attach = { assigned ->
                                assignedId.set(assigned)
                                activeRoom.hub.register(NetId(assigned), session, session)
                            },
                            sendRaw = sendRaw
                        )
                    )
                }
            } finally {
                val id = assignedId.get()
                val r = room
                if (id >= 0 && r != null) {
                    r.hub.unregister(NetId(id))
                    r.game.submit(GameLoopService.Command.Disconnect(id))
                }
            }
        }
    }
}.start(wait = false)
