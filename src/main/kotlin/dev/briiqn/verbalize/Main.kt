package dev.briiqn.verbalize

import dev.briiqn.verbalize.application.AuthOutcome
import dev.briiqn.verbalize.application.AuthService
import dev.briiqn.verbalize.application.GameLoopService
import dev.briiqn.verbalize.application.NpcDialogueService
import dev.briiqn.verbalize.application.Room
import dev.briiqn.verbalize.application.RoomManager
import dev.briiqn.verbalize.domain.model.Persona
import dev.briiqn.verbalize.domain.ports.AccountRepository
import dev.briiqn.verbalize.domain.world.WorldGen
import dev.briiqn.verbalize.infrastructure.ai.ElevenLabsClient
import dev.briiqn.verbalize.infrastructure.ai.GeminiClient
import dev.briiqn.verbalize.infrastructure.ai.VoicePools
import dev.briiqn.verbalize.infrastructure.config.EnvConfig
import dev.briiqn.verbalize.infrastructure.db.MariaDbAccountRepository
import dev.briiqn.verbalize.infrastructure.ktor.KtorSessionHub
import dev.briiqn.verbalize.infrastructure.ktor.startServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun main(args: Array<String>) {
    if (args.size >= 2 && args[0] == "parity") {
        ParityDump.run(args[1].toInt())
        return
    }
    val log = LoggerFactory.getLogger("verbalize")
    val env = EnvConfig(File(System.getProperty("user.dir")))

    val aiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var accountRepo: AccountRepository? = null
    for (attempt in 1..6) {
        try {
            accountRepo = MariaDbAccountRepository(env.dbUrl, env.dbUser, env.dbPassword)
            log.info("[db] connected to {} (accounts table ready)", env.dbUrl)
            break
        } catch (e: Exception) {
            log.warn("[db] connection attempt {}/6 failed: {}", attempt, e.message)
            if (attempt < 6) Thread.sleep(5_000)
        }
    }
    if (accountRepo == null) {
        log.warn("[db] MariaDB unavailable — auth disabled, everyone joins as an ephemeral guest")
    }
    val authService = AuthService(accountRepo)

    val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
        }
    }
    val gemini = env.geminiApiKey?.let { GeminiClient(http, it, env.geminiModel) }
    val eleven = env.elevenLabsApiKey?.let { ElevenLabsClient(http, it) }
    val stt = env.geminiApiKey?.let { dev.briiqn.verbalize.infrastructure.ai.GeminiSpeechToText(http, it, env.geminiModel) }
    val personaCache = AtomicReference<List<Persona>?>(null)

    fun assignVoices(game: GameLoopService) {
        var m = 0
        var f = 0
        for (npc in game.npcs.values) {
            npc.voiceId = if (npc.gender == "female") {
                VoicePools.FEMALE[f++ % VoicePools.FEMALE.size]
            } else {
                VoicePools.MALE[m++ % VoicePools.MALE.size]
            }
        }
    }

    fun applyPersonas(game: GameLoopService, personas: List<Persona>, refresh: Boolean) {
        for ((i, npc) in game.npcs.values.withIndex()) {
            val p = personas[i % personas.size]
            npc.name = p.name
            npc.gender = if (p.gender == "female") "female" else "male"
            npc.persona = p
        }
        assignVoices(game)
        if (refresh) game.broadcastNpcRefresh()
    }

    val buildRoom = { code: String, name: String, isPrivate: Boolean, owner: String ->
        val roomSeed = (System.nanoTime() xor (code.hashCode().toLong() shl 20)) and 0xffffffffffffL
        val features = WorldGen.generate((roomSeed and 0xffffffffL).toInt())
        val hub = KtorSessionHub()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val game = GameLoopService(hub, features, roomSeed, scope)
        val dialogue = NpcDialogueService(game, gemini, eleven, stt, aiScope)
        game.npcInteraction = { npc, player, heard, tick -> dialogue.startReply(npc, player, heard, tick) }
        game.npcVoiceInteraction = { npc, player, audio, tick -> dialogue.startVoiceReply(npc, player, audio, tick) }
        game.npcExpressionReaction = { npc, player, face, tick -> dialogue.startExpressionReaction(npc, player, face, tick) }
        game.authJoin = { username, password, attach, sendRaw ->
            aiScope.launch {
                val outcome = try {
                    withContext(IO) { authService.authenticateOrRegister(username, password) }
                } catch (e: Exception) {
                    log.warn("[auth] db error for {}: {}", username, e.message)
                    AuthOutcome.Rejected("Account service hiccuped — try again.")
                }
                when (outcome) {
                    is AuthOutcome.Success ->
                        game.submit(GameLoopService.Command.CompleteJoin(outcome.account.username, outcome.account.avatar, outcome.account.reputation, attach, sendRaw))
                    is AuthOutcome.Rejected ->
                        sendRaw(game.buildRejectFrame(outcome.reason))
                }
            }
        }
        game.avatarPersist = { username, avatar ->
            val repo = accountRepo
            if (repo != null) {
                aiScope.launch {
                    try {
                        withContext(IO) { repo.saveAvatar(username, avatar) }
                    } catch (e: Exception) {
                        log.warn("[db] avatar save failed for {}: {}", username, e.message)
                    }
                }
            }
        }
        game.reputationPersist = { username, reputation ->
            val repo = accountRepo
            if (repo != null) {
                aiScope.launch {
                    try {
                        withContext(IO) { repo.saveReputation(username, reputation) }
                    } catch (e: Exception) {
                        log.warn("[db] reputation save failed for {}: {}", username, e.message)
                    }
                }
            }
        }
        assignVoices(game)
        personaCache.get()?.let { applyPersonas(game, it, refresh = false) }
        game.start()
        Room(code, name, isPrivate, owner, hub, game, scope, roomSeed)
    }

    val roomManager = RoomManager(buildRoom, aiScope)

    if (gemini != null) {
        aiScope.launch {
            var attempt = 1
            while (true) {
                try {
                    val lobby = roomManager.get(RoomManager.LOBBY_CODE)!!
                    val roles = lobby.game.npcs.values.map { it.profession.title }
                    val personas = gemini.generate(roles)
                    personaCache.set(personas)
                    for (room in roomManager.allRooms()) {
                        applyPersonas(room.game, personas, refresh = true)
                    }
                    for ((i, p) in personas.withIndex()) {
                        log.info("[ai] persona {}: {} ({}, {}) — {}...", i + 1, p.name, p.gender, p.tone, p.personality.take(70))
                    }
                    break
                } catch (e: Exception) {
                    log.warn("[ai] persona generation failed (attempt {}), retrying in 45s: {}", attempt++, e.message)
                    delay(45_000)
                }
            }
        }
    } else {
        log.info("[ai] GEMINI_API_KEY not set — NPCs use canned dialogue{}", if (eleven != null) " with TTS voices" else "")
    }

    startServer(env.port, env.distDir, roomManager, authService)
    log.info("[verbalize] Kotlin/Ktor server on http://localhost:{} — lobby seed {}", env.port, roomManager.get(RoomManager.LOBBY_CODE)?.worldSeed)
    log.info("[ai] gemini={} elevenlabs={} stt={} db={}", if (gemini != null) "on (${env.geminiModel})" else "off", if (eleven != null) "on" else "off", if (stt != null) "gemini" else "off", if (accountRepo != null) "on" else "off")

    Thread.currentThread().join()
}
