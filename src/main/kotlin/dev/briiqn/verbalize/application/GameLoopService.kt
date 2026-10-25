package dev.briiqn.verbalize.application

import com.google.protobuf.ByteString
import dev.briiqn.verbalize.domain.model.MovementIntent
import dev.briiqn.verbalize.domain.model.NetId
import dev.briiqn.verbalize.domain.model.Npc
import dev.briiqn.verbalize.domain.model.Player
import dev.briiqn.verbalize.domain.model.Profession
import dev.briiqn.verbalize.domain.ports.SessionHub
import dev.briiqn.verbalize.domain.simulation.Simulation
import dev.briiqn.verbalize.domain.speech.SpeechGate
import dev.briiqn.verbalize.domain.world.Pathfinder
import dev.briiqn.verbalize.domain.world.WorldGen
import dev.briiqn.verbalize.infrastructure.codec.PacketCompressor
import dev.briiqn.verbalize.infrastructure.proto.ClientEnvelope
import dev.briiqn.verbalize.infrastructure.proto.EntityDeltaTickEvent
import dev.briiqn.verbalize.infrastructure.proto.EntityLifecycleEvent
import dev.briiqn.verbalize.infrastructure.proto.EntitySpawnInfo
import dev.briiqn.verbalize.infrastructure.proto.EntityType
import dev.briiqn.verbalize.infrastructure.proto.QuantizedEntityTransform
import dev.briiqn.verbalize.infrastructure.proto.ServerEnvelope
import dev.briiqn.verbalize.infrastructure.proto.VoiceRelayEvent
import dev.briiqn.verbalize.infrastructure.proto.WelcomeEvent
import dev.briiqn.verbalize.infrastructure.proto.WorldDeltaEvent
import dev.briiqn.verbalize.infrastructure.proto.WorldDeltaRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.random.Random

private const val TICK_MS = 50L
private const val MAX_INTENTS_PER_TICK = 10
private const val MIN_AUDIO_BYTES = 1500      // below this, treat a voice clip as silence
private const val MAX_AUDIO_BYTES = 2_000_000 // cap buffered mic audio per utterance
private const val VOICE_RADIUS_MM = 30_000L
private const val NPC_TALK_RANGE_MM = 6_000L
private const val NPC_WANDER_CELLS = 8

private data class PlacedDelta(val gridX: Int, val gridY: Int, val gridZ: Int, val structureType: Int, val stateFlags: Int)

/** Reject frame usable before any room/loop is involved (e.g. unknown room code). */
fun buildStandaloneRejectFrame(reason: String): ByteArray {
    val rejected = dev.briiqn.verbalize.infrastructure.proto.JoinRejectedEvent.newBuilder().setReason(reason).build()
    return PacketCompressor.pack(ServerEnvelope.newBuilder().setServerTick(0).setJoinRejected(rejected).build().toByteArray())
}

/**
 * Single-writer game authority: all world state is owned by one coroutine.
 * Sessions enqueue commands; nothing else mutates state, so there are no
 * locks and no data races by construction.
 */
class GameLoopService(
    private val hub: SessionHub,
    private val features: WorldGen.Features,
    private val worldSeed: Long,
    private val scope: CoroutineScope
) {
    private val log = LoggerFactory.getLogger(GameLoopService::class.java)

    sealed interface Command {
        data class Inbound(
            val netId: Int?,
            val frame: ByteArray,
            val attach: (Int) -> Unit,
            val sendRaw: (ByteArray) -> Unit
        ) : Command

        data class CompleteJoin(
            val username: String,
            val avatar: dev.briiqn.verbalize.domain.model.AvatarConfig?,
            val reputation: Int,
            val attach: (Int) -> Unit,
            val sendRaw: (ByteArray) -> Unit
        ) : Command

        data class Disconnect(val netId: Int) : Command
        data class NpcAction(val npcId: Int, val action: VillagerAction) : Command
        data class ApplyReputation(val netId: Int, val delta: Int, val reason: String) : Command
    }

    sealed interface VillagerAction {
        data class WalkToPlayer(val playerId: Int) : VillagerAction
        data class VisitLandmark(val name: String) : VillagerAction
        data class BuildHouse(val playerId: Int, val style: String) : VillagerAction
    }

    private class BuildProject(
        val npcId: Int,
        val blocks: ArrayDeque<PlacedDelta>,
        val centerX: Int,
        val centerZ: Int,
        val frontX: Int,
        val frontZ: Int
    )

    private val commands = Channel<Command>(capacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val players = HashMap<Int, Player>()
    val npcs = LinkedHashMap<Int, Npc>()
    @Volatile private var playerCountSnapshot = 0
    private val worldDeltas = HashMap<Long, PlacedDelta>()
    @Volatile var serverTick = 0L
        private set
    private var nextNetId = 1

    var npcInteraction: ((Npc, Player, String, Long) -> Unit)? = null

    /** Wired by Main: a villager received raw mic audio to transcribe and reply to. */
    var npcVoiceInteraction: ((Npc, Player, ByteArray, Long) -> Unit)? = null

    /** Wired by Main: performs credential verification off the game loop, then submits CompleteJoin or sends a reject frame. */
    var authJoin: ((username: String, password: String, attach: (Int) -> Unit, sendRaw: (ByteArray) -> Unit) -> Unit)? = null

    /** Wired by Main: persists an avatar change off the game loop. */
    var avatarPersist: ((username: String, avatar: dev.briiqn.verbalize.domain.model.AvatarConfig) -> Unit)? = null

    /** Wired by Main: persists a reputation change off the game loop. */
    var reputationPersist: ((username: String, reputation: Int) -> Unit)? = null

    /** Off-loop callers (e.g. the AI dialogue service) nudge a player's standing through here. */
    fun submitReputationDelta(netId: Int, delta: Int, reason: String) {
        if (delta == 0) return
        submit(Command.ApplyReputation(netId, delta, reason))
    }

    /** Wired by Main: villager notices a player's sustained facial expression. */
    var npcExpressionReaction: ((Npc, Player, Int, Long) -> Unit)? = null

    private val structureQuery = Simulation.StructureQuery { gx, gy, gz ->
        worldDeltas[WorldGen.packCell3(gx, gy, gz)]?.structureType
            ?: features.structureTypeAt(gx, gy, gz)
    }

    private val pathfinder = Pathfinder { gx, gz ->
        abs(gx) >= Simulation.WORLD_RADIUS || abs(gz) >= Simulation.WORLD_RADIUS ||
            worldDeltas.containsKey(WorldGen.packCell3(gx, 0, gz)) ||
            features.blockedCells.contains(WorldGen.packCell2(gx, gz))
    }

    private val builds = ArrayList<BuildProject>()
    private val farmField: Pair<Int, Int> = findFarmField()

    init {
        data class Def(val name: String, val gender: String, val color: Int, val profession: Profession)
        val npcDefs = listOf(
            Def("Cogsworth", "male", 0xb87333, Profession.MAYOR),
            Def("Gearhilda", "female", 0x8fbc8f, Profession.BLACKSMITH),
            Def("Pistonjack", "male", 0x9f8fd4, Profession.LUMBERJACK),
            Def("Thessaly", "female", 0xc9a76f, Profession.FARMER),
            Def("Boltwright", "male", 0x7f8fa6, Profession.BUILDER),
            Def("Aria", "female", 0xaa6f7f, Profession.NAVIGATOR)
        )
        for (def in npcDefs) {
            val netId = nextNetId++
            val (ax, az) = professionAnchor(def.profession)
            val npc = Npc(NetId(netId), def.name, def.gender, def.color, def.profession)
            npc.transform.xMm = Math.round(ax * 1000).toInt()
            npc.transform.zMm = Math.round(az * 1000).toInt()
            npcs[netId] = npc
        }
    }

    private fun isCellOpen(x: Int, z: Int): Boolean =
        abs(x) < Simulation.WORLD_RADIUS - 2 && abs(z) < Simulation.WORLD_RADIUS - 2 &&
            !features.blockedCells.contains(WorldGen.packCell2(x, z)) &&
            !features.pathCells.contains(WorldGen.packCell2(x, z)) &&
            !worldDeltas.containsKey(WorldGen.packCell3(x, 0, z))

    private fun findFarmField(): Pair<Int, Int> {
        for (x0 in -38..28) {
            for (z0 in -38..28) {
                if (abs(x0 + 4) < 10 && abs(z0 + 3) < 10) continue
                var clear = true
                outer@ for (x in x0 - 1..x0 + 8) {
                    for (z in z0 - 1..z0 + 6) {
                        if (!isCellOpen(x, z)) {
                            clear = false
                            break@outer
                        }
                    }
                }
                if (clear) return x0 to z0
            }
        }
        return 20 to 20
    }

    private fun buildingFront(type: Int): Pair<Double, Double>? =
        features.buildings.firstOrNull { it.type == type }?.let { (it.frontX + 0.5) to (it.frontZ + 0.5) }

    private fun professionAnchor(p: Profession): Pair<Double, Double> = when (p) {
        Profession.MAYOR -> 0.5 to 4.5
        Profession.BLACKSMITH -> buildingFront(1) ?: (features.npcSpots[0].x to features.npcSpots[0].z)
        Profession.BUILDER -> buildingFront(2) ?: (features.npcSpots[1].x to features.npcSpots[1].z)
        Profession.LUMBERJACK -> features.trees.firstOrNull()?.let { (it.x + 1.2) to it.z }
            ?: (features.npcSpots[2].x to features.npcSpots[2].z)
        Profession.FARMER -> (farmField.first + 0.5) to (farmField.second + 0.5)
        Profession.NAVIGATOR -> features.npcSpots[0].x to features.npcSpots[0].z
    }

    /** Role-fitting work destination; also arms the npc's work action + focus point. Null = random wander. */
    private fun professionTarget(npc: Npc): Pair<Int, Int>? {
        val cursor = npc.workCursor++
        fun frontCell(type: Int): Pair<Int, Int>? =
            features.buildings.firstOrNull { it.type == type }?.let { it.frontX to it.frontZ }
        fun interiorCell(type: Int): Pair<Int, Int>? =
            features.buildings.firstOrNull { it.type == type }?.let { (it.x0 + it.w / 2) to (it.z0 + it.d / 2) }
        fun arm(action: Int, fx: Double, fz: Double) {
            npc.workAction = action
            npc.workFocusX = fx
            npc.workFocusZ = fz
        }
        npc.workAction = 0
        return when (npc.profession) {
            Profession.MAYOR -> {
                val stops = ArrayList<Pair<Int, Int>>()
                stops.add(3 to 3); stops.add(-3 to 3); stops.add(-3 to -3); stops.add(3 to -3)
                for (b in features.buildings) stops.add(b.frontX to b.frontZ)
                stops[cursor % stops.size]
            }
            Profession.BLACKSMITH -> {
                val stops = listOfNotNull(frontCell(1), interiorCell(1), 3 to 2)
                val target = stops[cursor % stops.size]
                if (target == interiorCell(1)) arm(2, target.first + 0.5, target.second - 0.6)
                target
            }
            Profession.BUILDER -> {
                val stops = listOfNotNull(frontCell(2), interiorCell(2), -3 to 2)
                val target = stops[cursor % stops.size]
                if (target == interiorCell(2)) arm(4, target.first + 0.5, target.second - 0.6)
                target
            }
            Profession.LUMBERJACK -> {
                if (features.trees.isEmpty()) return null
                val tree = features.trees[cursor % features.trees.size]
                val tx = floor(tree.x).toInt()
                val tz = floor(tree.z).toInt()
                val stand = listOf(tx + 1 to tz, tx - 1 to tz, tx to tz + 1, tx to tz - 1)
                    .firstOrNull { isCellOpen(it.first, it.second) }
                if (stand != null) arm(1, tree.x, tree.z)
                stand
            }
            Profession.FARMER -> {
                val (fx, fz) = farmField
                val row = (cursor / 8) % 6
                val colRaw = cursor % 8
                val col = if (row % 2 == 0) colRaw else 7 - colRaw
                val cell = (fx + col) to (fz + row)
                arm(3, cell.first + 0.5, cell.second + 1.1)
                cell
            }
            Profession.NAVIGATOR -> null
        }
    }

    fun submit(command: Command) {
        commands.trySend(command)
    }

    /** Thread-safe approximate count for room management. */
    fun playerCount(): Int = playerCountSnapshot

    fun start() {
        scope.launch {
            var nextTickAt = System.nanoTime()
            while (isActive) {
                while (true) {
                    val cmd = commands.tryReceive().getOrNull() ?: break
                    handleCommand(cmd)
                }
                tick()
                nextTickAt += TICK_MS * 1_000_000
                val now = System.nanoTime()
                if (nextTickAt < now - 5 * TICK_MS * 1_000_000) {
                    nextTickAt = now
                }
                val sleepMs = (nextTickAt - now) / 1_000_000
                if (sleepMs > 0) delay(sleepMs)
            }
        }
    }

    private fun handleCommand(command: Command) {
        when (command) {
            is Command.Disconnect -> handleLeave(command.netId)
            is Command.NpcAction -> executeVillagerAction(command.npcId, command.action)
            is Command.CompleteJoin -> handleJoin(command)
            is Command.ApplyReputation -> applyReputation(command.netId, command.delta, command.reason)
            is Command.Inbound -> {
                val envelope = try {
                    ClientEnvelope.parseFrom(PacketCompressor.unpack(command.frame))
                } catch (e: Exception) {
                    return
                }
                handleEnvelope(command.netId, envelope, command.attach, command.sendRaw)
            }
        }
    }

    private fun handleEnvelope(netId: Int?, envelope: ClientEnvelope, attach: (Int) -> Unit, sendRaw: (ByteArray) -> Unit) {
        if (envelope.hasJoin()) {
            if (netId != null) return
            val auth = authJoin
            if (auth != null) {
                auth(envelope.join.username, envelope.join.password, attach, sendRaw)
            } else {
                handleJoin(Command.CompleteJoin(envelope.join.username, null, dev.briiqn.verbalize.domain.model.Account.DEFAULT_REPUTATION, attach, sendRaw))
            }
            return
        }
        val player = netId?.let { players[it] } ?: return

        when {
            envelope.hasMovementInput() -> {
                val m = envelope.movementInput
                player.queueIntent(MovementIntent(envelope.sequenceNumber, m.keyBitmask, m.yawMrad, m.pitchMrad, m.micEnergyByte, m.faceState, m.headRollMrad))
            }
            envelope.hasVoiceStream() -> relayVoice(player, envelope)
            envelope.hasNpcAudioInteract() -> handleNpcInteract(player, envelope)
            envelope.hasWorldInteract() -> handleWorldInteract(envelope)
            envelope.hasAvatarUpdate() -> handleAvatarUpdate(player, envelope)
        }
    }

    private fun handleAvatarUpdate(player: Player, envelope: ClientEnvelope) {
        val a = envelope.avatarUpdate
        val avatar = dev.briiqn.verbalize.domain.model.AvatarConfig(a.hat, a.bodyColorHex, a.eyes, a.mouth, a.hair).sanitized()
        player.avatarHat = avatar.hat
        player.avatarEyes = avatar.eyes
        player.avatarMouth = avatar.mouth
        player.avatarHair = avatar.hair
        player.baseColorHex = avatar.bodyColorHex
        avatarPersist?.invoke(player.username, avatar)
        val lifecycle = EntityLifecycleEvent.newBuilder().addSpawned(spawnInfo(player)).build()
        broadcastEnvelope(ServerEnvelope.newBuilder().setServerTick(tickInt()).setEntityLifecycle(lifecycle))
        log.info("[avatar] {} customized (hat={}, eyes={}, mouth={}, hair={}, color=#{})",
            player.username, avatar.hat, avatar.eyes, avatar.mouth, avatar.hair, Integer.toHexString(avatar.bodyColorHex))
    }

    fun buildRejectFrame(reason: String): ByteArray {
        val rejected = dev.briiqn.verbalize.infrastructure.proto.JoinRejectedEvent.newBuilder().setReason(reason).build()
        return pack(ServerEnvelope.newBuilder().setServerTick(tickInt()).setJoinRejected(rejected))
    }

    private fun applyReputation(netId: Int, delta: Int, reason: String) {
        val player = players[netId] ?: return
        val updated = (player.reputation + delta).coerceIn(0, 100)
        if (updated == player.reputation) return
        player.reputation = updated
        reputationPersist?.invoke(player.username, updated)
        val event = dev.briiqn.verbalize.infrastructure.proto.ReputationEvent.newBuilder()
            .setReputation(updated)
            .setDelta(delta)
            .setReason(reason)
            .build()
        hub.send(NetId(netId), pack(ServerEnvelope.newBuilder().setServerTick(tickInt()).setReputation(event)))
    }

    private fun handleJoin(cmd: Command.CompleteJoin) {
        val username = cmd.username.ifBlank { "operator" }.take(24)
        if (players.values.any { it.username.equals(username, ignoreCase = true) }) {
            cmd.sendRaw(buildRejectFrame("'$username' is already connected."))
            return
        }
        val netId = nextNetId++
        val attach = cmd.attach
        val color = cmd.avatar?.bodyColorHex
            ?: (((Random.nextInt(0x80) + 0x60) shl 16) or
                ((Random.nextInt(0x80) + 0x50) shl 8) or
                (Random.nextInt(0x60) + 0x30))
        val player = Player(NetId(netId), username, color)
        player.reputation = cmd.reputation.coerceIn(0, 100)
        cmd.avatar?.let {
            player.avatarHat = it.hat
            player.avatarEyes = it.eyes
            player.avatarMouth = it.mouth
            player.avatarHair = it.hair
        }
        val spawn = features.spawnPoints.random()
        player.transform.xMm = Math.round((spawn.x + (Random.nextDouble() * 2 - 1) * 0.8) * 1000).toInt()
        player.transform.zMm = Math.round((spawn.z + (Random.nextDouble() * 2 - 1) * 0.8) * 1000).toInt()
        players[netId] = player
        attach(netId)

        val welcome = WelcomeEvent.newBuilder()
            .setAssignedNetId(netId)
            .setTickRateHz(Simulation.SERVER_TPS)
            .setWorldSeed(worldSeed)
            .addAllInitialEntities(allSpawnInfos())
            .addAllActiveDeltas(worldDeltas.values.map { deltaRecord(it) })
            .setReputation(player.reputation)
            .build()
        hub.send(NetId(netId), pack(ServerEnvelope.newBuilder().setServerTick(tickInt()).setWelcome(welcome)))

        val lifecycle = EntityLifecycleEvent.newBuilder().addSpawned(spawnInfo(player)).build()
        broadcastEnvelope(ServerEnvelope.newBuilder().setServerTick(tickInt()).setEntityLifecycle(lifecycle), except = netId)
        playerCountSnapshot = players.size
        log.info("[join] #{} {} ({} online)", netId, username, players.size)
    }

    private fun handleLeave(netId: Int) {
        val player = players.remove(netId) ?: return
        val lifecycle = EntityLifecycleEvent.newBuilder().addDespawnedNetIds(netId).build()
        broadcastEnvelope(ServerEnvelope.newBuilder().setServerTick(tickInt()).setEntityLifecycle(lifecycle))
        playerCountSnapshot = players.size
        log.info("[leave] #{} {} ({} online)", netId, player.username, players.size)
    }

    private fun relayVoice(player: Player, envelope: ClientEnvelope) {
        val vs = envelope.voiceStream
        for (other in players.values) {
            if (other.netId == player.netId) continue
            val dx = (other.transform.xMm - player.transform.xMm).toLong()
            val dz = (other.transform.zMm - player.transform.zMm).toLong()
            if (dx * dx + dz * dz > VOICE_RADIUS_MM * VOICE_RADIUS_MM) continue
            val relay = VoiceRelayEvent.newBuilder()
                .setSpeakerNetId(player.netId.value)
                .setOriginXMm(player.transform.xMm)
                .setOriginZMm(player.transform.zMm)
                .setOpusChunk(vs.opusChunk)
                .setChunkSeq(vs.chunkSeq)
                .build()
            hub.send(other.netId, pack(ServerEnvelope.newBuilder().setServerTick(tickInt()).setVoiceRelay(relay), isAudio = true))
        }
    }

    private fun handleNpcInteract(player: Player, envelope: ClientEnvelope) {
        val cmd = envelope.npcAudioInteract
        val npc = npcs[cmd.targetNetId] ?: return
        val dx = (npc.transform.xMm - player.transform.xMm).toLong()
        val dz = (npc.transform.zMm - player.transform.zMm).toLong()
        if (dx * dx + dz * dz > NPC_TALK_RANGE_MM * NPC_TALK_RANGE_MM) return
        // text chat supplies a transcript directly; voice streams raw mic audio to transcribe
        if (cmd.transcriptHint.isNotEmpty()) {
            player.npcTranscripts[cmd.targetNetId] = cmd.transcriptHint
        }
        if (!cmd.audioChunk.isEmpty) {
            val buf = player.npcAudio.getOrPut(cmd.targetNetId) { java.io.ByteArrayOutputStream() }
            if (buf.size() < MAX_AUDIO_BYTES) cmd.audioChunk.writeTo(buf)
        }
        if (!cmd.isFinalChunk) return

        val text = player.npcTranscripts.remove(cmd.targetNetId)
        val audio = player.npcAudio.remove(cmd.targetNetId)?.toByteArray()
        // Hold still, drop any wander path and face the speaker for the conversation
        npc.path = null
        npc.conversationHoldUntil = serverTick + 400
        npc.transform.yawMrad = Math.round(
            atan2((player.transform.xMm - npc.transform.xMm).toDouble(), -(player.transform.zMm - npc.transform.zMm).toDouble()) * 1000
        ).toInt()
        npc.dirty = true
        when {
            !text.isNullOrBlank() -> {
                log.info("[ai] interact(text): {} -> {} heard=\"{}\" busy={}", player.username, npc.name, text.take(60), npc.aiBusy)
                npcInteraction?.invoke(npc, player, text, serverTick)
            }
            audio != null && audio.size >= MIN_AUDIO_BYTES -> {
                log.info("[ai] interact(voice): {} -> {} {} bytes, transcribing", player.username, npc.name, audio.size)
                npcVoiceInteraction?.invoke(npc, player, audio, serverTick)
            }
            else -> log.info("[ai] interact from {} dropped: no text and {} audio bytes", player.username, audio?.size ?: 0)
        }
    }

    private fun handleWorldInteract(envelope: ClientEnvelope) {
        val wi = envelope.worldInteract
        if (abs(wi.gridX) >= Simulation.WORLD_RADIUS || abs(wi.gridZ) >= Simulation.WORLD_RADIUS) return
        val key = WorldGen.packCell3(wi.gridX, wi.gridY, wi.gridZ)
        val record = PlacedDelta(wi.gridX, wi.gridY, wi.gridZ, wi.structureType, 0)
        if (wi.structureType == 0) worldDeltas.remove(key) else worldDeltas[key] = record
        val event = WorldDeltaEvent.newBuilder().addDeltas(deltaRecord(record)).build()
        broadcastEnvelope(ServerEnvelope.newBuilder().setServerTick(tickInt()).setWorldDelta(event))
    }

    private fun placeBlock(record: PlacedDelta) {
        worldDeltas[WorldGen.packCell3(record.gridX, record.gridY, record.gridZ)] = record
        val event = WorldDeltaEvent.newBuilder().addDeltas(deltaRecord(record)).build()
        broadcastEnvelope(ServerEnvelope.newBuilder().setServerTick(tickInt()).setWorldDelta(event))
    }

    private fun pathNpcToCell(npc: Npc, tx: Int, tz: Int): Boolean {
        val sx = floor(npc.transform.xMm / 1000.0).toInt()
        val sz = floor(npc.transform.zMm / 1000.0).toInt()
        val candidates = listOf(tx to tz, tx + 1 to tz, tx - 1 to tz, tx to tz + 1, tx to tz - 1)
        for ((cx, cz) in candidates) {
            val path = pathfinder.findPath(sx, sz, cx, cz, maxExpansions = 2000,npcs.values.filter { it != npc })
            if (path != null) {
                npc.path = path
                npc.pathIndex = 0
                npc.conversationHoldUntil = 0
                npc.nextWanderTick = serverTick + 600
                return true
            }
        }
        return false
    }

    private fun executeVillagerAction(npcId: Int, action: VillagerAction) {
        val npc = npcs[npcId] ?: return
        when (action) {
            is VillagerAction.WalkToPlayer -> {
                val player = players[action.playerId] ?: return
                val px = floor(player.transform.xMm / 1000.0).toInt()
                val pz = floor(player.transform.zMm / 1000.0).toInt()
                log.info("[action] {} walks to {}", npc.name, player.username)
                pathNpcToCell(npc, px, pz)
            }
            is VillagerAction.VisitLandmark -> {
                val cell = when (action.name.lowercase().trim()) {
                    "plaza" -> 0 to 4
                    "tower" -> 2 to 2
                    "forge", "workshop", "smithy" -> features.buildings.firstOrNull { it.type == 1 }?.let { it.frontX to it.frontZ } ?: (0 to 4)
                    "warehouse" -> features.buildings.firstOrNull { it.type == 2 }?.let { it.frontX to it.frontZ } ?: (0 to 4)
                    "farm", "field" -> (farmField.first + 4) to (farmField.second + 3)
                    else -> 0 to 4
                }
                log.info("[action] {} visits landmark '{}'", npc.name, action.name)
                pathNpcToCell(npc, cell.first, cell.second)
            }
            is VillagerAction.BuildHouse -> {
                if (builds.any { it.npcId == npcId } || builds.size >= 3) return
                val player = players[action.playerId] ?: return
                val project = planHouse(npcId, player) ?: run {
                    log.info("[action] {} found no clear ground to build near {}", npc.name, player.username)
                    return
                }
                builds.add(project)
                npc.workAction = 2
                npc.workFocusX = project.centerX + 0.5
                npc.workFocusZ = project.centerZ + 0.5
                log.info(
                    "[action] {} starts building a house for {} at ({}, {}) — {} blocks, style '{}'",
                    npc.name, player.username, project.centerX, project.centerZ, project.blocks.size, action.style
                )
                pathNpcToCell(npc, project.frontX, project.frontZ)
            }
        }
    }

    /** Plans a unique small house near the player on clear ground; blocks are ordered so it visibly rises. */
    private fun planHouse(npcId: Int, player: Player): BuildProject? {
        val rng = Random(System.nanoTime())
        val px = floor(player.transform.xMm / 1000.0).toInt()
        val pz = floor(player.transform.zMm / 1000.0).toInt()
        val w = 5 + rng.nextInt(2)
        val d = 5 + rng.nextInt(3)

        var site: Pair<Int, Int>? = null
        search@ for (radius in 4..14) {
            for (ox in -radius..radius) {
                for (oz in -radius..radius) {
                    if (maxOf(abs(ox), abs(oz)) != radius) continue
                    val x0 = px + ox - w / 2
                    val z0 = pz + oz - d / 2
                    if (px in x0..x0 + w - 1 && pz in z0..z0 + d - 1) continue
                    var clear = true
                    cells@ for (x in x0 - 1..x0 + w) {
                        for (z in z0 - 1..z0 + d) {
                            if (!isCellOpen(x, z)) {
                                clear = false
                                break@cells
                            }
                        }
                    }
                    if (clear) {
                        site = x0 to z0
                        break@search
                    }
                }
            }
        }
        val (x0, z0) = site ?: return null

        // Door on the side facing the player, 2 cells wide
        val centerX = x0 + w / 2
        val centerZ = z0 + d / 2
        val doorOnX = abs(px - centerX) <= abs(pz - centerZ)
        val doorX: Int
        val doorZ: Int
        val doorX2: Int
        val doorZ2: Int
        if (!doorOnX) {
            doorX = if (px < centerX) x0 else x0 + w - 1
            doorZ = z0 + d / 2 - 1
            doorX2 = doorX
            doorZ2 = doorZ + 1
        } else {
            doorZ = if (pz < centerZ) z0 else z0 + d - 1
            doorX = x0 + w / 2 - 1
            doorZ2 = doorZ
            doorX2 = doorX + 1
        }
        val isDoor = { x: Int, z: Int -> (x == doorX && z == doorZ) || (x == doorX2 && z == doorZ2) }

        val wallType = 2 + rng.nextInt(6)
        val roofType = 2 + (wallType - 2 + 1 + rng.nextInt(4)) % 6

        fun perimeter(): List<Pair<Int, Int>> {
            val cells = ArrayList<Pair<Int, Int>>()
            for (x in x0 until x0 + w) cells.add(x to z0)
            for (z in z0 + 1 until z0 + d) cells.add(x0 + w - 1 to z)
            for (x in x0 + w - 2 downTo x0) cells.add(x to z0 + d - 1)
            for (z in z0 + d - 2 downTo z0 + 1) cells.add(x0 to z)
            return cells
        }

        val blocks = ArrayDeque<PlacedDelta>()
        for ((x, z) in perimeter()) {
            if (!isDoor(x, z)) blocks.addLast(PlacedDelta(x, 0, z, wallType, 0))
        }
        for ((x, z) in perimeter()) {
            if (isDoor(x, z)) continue
            val corner = (x == x0 || x == x0 + w - 1) && (z == z0 || z == z0 + d - 1)
            if (!corner && rng.nextDouble() < 0.3) continue // window gap
            blocks.addLast(PlacedDelta(x, 1, z, wallType, 0))
        }
        for (z in z0 until z0 + d) {
            for (x in x0 until x0 + w) {
                blocks.addLast(PlacedDelta(x, 2, z, roofType, 0))
            }
        }

        val frontX = if (!doorOnX) (if (px < centerX) doorX - 1 else doorX + 1) else doorX
        val frontZ = if (!doorOnX) doorZ else (if (pz < centerZ) doorZ - 1 else doorZ + 1)
        return BuildProject(npcId, blocks, centerX, centerZ, frontX, frontZ)
    }

    private fun processBuilds() {
        if (builds.isEmpty()) return
        val iterator = builds.iterator()
        while (iterator.hasNext()) {
            val project = iterator.next()
            val npc = npcs[project.npcId] ?: run { iterator.remove(); return }
            val dx = (npc.transform.xMm - project.centerX * 1000 - 500).toLong()
            val dz = (npc.transform.zMm - project.centerZ * 1000 - 500).toLong()
            if (dx * dx + dz * dz > 9_000L * 9_000L) {
                if (npc.path == null || npc.pathIndex >= (npc.path?.size ?: 0)) {
                    pathNpcToCell(npc, project.frontX, project.frontZ)
                }
                continue
            }
            npc.nextWanderTick = serverTick + 100
            if (serverTick % 3 != 0L) continue
            val block = project.blocks.removeFirstOrNull()
            if (block == null) {
                iterator.remove()
                npc.workAction = 0
                npc.actionState = 0
                npc.dirty = true
                log.info("[action] {} finished the house at ({}, {})", npc.name, project.centerX, project.centerZ)
                continue
            }
            placeBlock(block)
            npc.transform.yawMrad = Math.round(
                atan2((block.gridX * 1000 + 500 - npc.transform.xMm).toDouble(), -(block.gridZ * 1000 + 500 - npc.transform.zMm).toDouble()) * 1000
            ).toInt()
            npc.audioEnergyByte = 60 + Random.nextInt(60)
            npc.dirty = true
        }
    }

    private fun stepEntity(transformOwnerNetId: Int, transform: dev.briiqn.verbalize.domain.model.MillimeterTransform, keyBitmask: Int, yawMrad: Int) {
        val obstacles = ArrayList<Simulation.Obstacle>(features.obstacles.size + players.size + npcs.size)
        obstacles.addAll(features.obstacles)
        for (p in players.values) {
            if (p.netId.value != transformOwnerNetId) {
                obstacles.add(Simulation.Obstacle(p.transform.xMm / 1000.0, p.transform.yMm / 1000.0, p.transform.zMm / 1000.0, Simulation.ENTITY_BODY_RADIUS))
            }
        }
        for (n in npcs.values) {
            if (n.netId.value != transformOwnerNetId) {
                obstacles.add(Simulation.Obstacle(n.transform.xMm / 1000.0, n.transform.yMm / 1000.0, n.transform.zMm / 1000.0, Simulation.ENTITY_BODY_RADIUS))
            }
        }
        val result = Simulation.step(
            transform.positionMeters(),
            transform.velocityMeters(),
            keyBitmask,
            yawMrad,
            structureQuery,
            obstacles
        )
        transform.applyStep(result)
    }

    private fun tick() {
        serverTick++

        for (player in players.values) {
            val t = player.transform
            val prevX = t.xMm
            val prevY = t.yMm
            val prevZ = t.zMm
            val prevVx = t.vxMm
            val prevVz = t.vzMm
            val prevYaw = t.yawMrad
            val prevMic = player.micEnergyByte
            val prevFace = player.faceState
            val prevPitch = player.pitchMrad
            val prevRoll = player.headRollMrad
            var processed = 0
            while (processed++ < MAX_INTENTS_PER_TICK) {
                val intent = player.pendingIntents.removeFirstOrNull() ?: break
                player.lastAckSeq = maxOf(player.lastAckSeq, intent.sequenceNumber)
                t.yawMrad = intent.yawMrad
                player.pitchMrad = intent.pitchMrad
                player.headRollMrad = intent.headRollMrad
                player.micEnergyByte = intent.micEnergyByte
                player.faceState = intent.faceState
                SpeechGate.observe(player, intent.micEnergyByte, serverTick)
                stepEntity(player.netId.value, t, intent.keyBitmask, intent.yawMrad)
            }
            if (processed > 1 && (
                    t.xMm != prevX || t.yMm != prevY || t.zMm != prevZ ||
                        t.vxMm != prevVx || t.vzMm != prevVz ||
                        abs(t.yawMrad - prevYaw) > 15 ||
                        abs(player.pitchMrad - prevPitch) > 15 ||
                        abs(player.headRollMrad - prevRoll) > 15 ||
                        abs(player.micEnergyByte - prevMic) > 6 ||
                        (player.micEnergyByte == 0 && prevMic != 0) ||
                        player.faceState != prevFace
                    )
            ) {
                player.dirty = true
            }

            if (player.faceState == 3 || player.faceState == 4 || player.faceState == 5) {
                if (player.faceState == player.streakFace) player.faceStreak++
                else {
                    player.streakFace = player.faceState
                    player.faceStreak = 1
                }
            } else {
                player.faceStreak = 0
                player.streakFace = -1
            }
        }

        tickNpcs()
        if (serverTick % 20 == 0L) scanExpressionReactions()
        processBuilds()
        broadcastDirtyEntities()
    }

    /** Villagers notice a nearby player holding a smile/frown/shock while looking at them. */
    private fun scanExpressionReactions() {
        val handler = npcExpressionReaction ?: return
        for (npc in npcs.values) {
            if (npc.aiBusy || serverTick < npc.nextReactionTick || serverTick < npc.conversationHoldUntil) continue
            for (player in players.values) {
                if (player.faceStreak < 12) continue
                val dx = (npc.transform.xMm - player.transform.xMm) / 1000.0
                val dz = (npc.transform.zMm - player.transform.zMm) / 1000.0
                val dist = kotlin.math.sqrt(dx * dx + dz * dz)
                if (dist > 4.5 || dist < 1e-3) continue
                val yaw = player.transform.yawMrad / 1000.0
                val facing = (kotlin.math.sin(yaw) * dx - kotlin.math.cos(yaw) * dz) / dist
                if (facing < 0.6) continue

                npc.nextReactionTick = serverTick + 600
                npc.path = null
                npc.conversationHoldUntil = serverTick + 140
                npc.transform.yawMrad = Math.round(atan2(-dx * 1000, dz * 1000) * 1000).toInt()
                npc.dirty = true
                val face = player.streakFace
                player.faceStreak = 0
                log.info("[ai] {} notices {} {}", npc.name, player.username,
                    dev.briiqn.verbalize.domain.dialogue.CannedLines.expressionDescription(face))
                handler(npc, player, face, serverTick)
                break
            }
        }
    }

    private fun tickNpcs() {
        for (npc in npcs.values) {
            val t = npc.transform
            val conversing = npc.aiBusy || serverTick < npc.conversationHoldUntil
            val pathDone = npc.path == null || npc.pathIndex >= (npc.path?.size ?: 0)
            if (!conversing && pathDone && serverTick >= npc.nextWanderTick) {
                npc.nextWanderTick = serverTick + 80 + Random.nextInt(160)
                npc.path = null
                val sx = floor(t.xMm / 1000.0).toInt()
                val sz = floor(t.zMm / 1000.0).toInt()
                val work = professionTarget(npc)
                val target = when {
                    work != null -> work
                    Random.nextDouble() < 0.65 -> {
                        val tx = sx + Random.nextInt(NPC_WANDER_CELLS * 2 + 1) - NPC_WANDER_CELLS
                        val tz = sz + Random.nextInt(NPC_WANDER_CELLS * 2 + 1) - NPC_WANDER_CELLS
                        tx to tz
                    }
                    else -> null
                }
                if (target != null && (target.first != sx || target.second != sz)) {
                    val path = pathfinder.findPath(sx, sz, target.first, target.second,2000,npcs.values.filter { it != npc })
                    if (path != null) {
                        npc.path = path
                        npc.pathIndex = 0
                    }
                }
            }

            val prevX = t.xMm
            val prevZ = t.zMm
            var moved = false

            val path = if (conversing) null else npc.path
            if (path != null) {
                while (npc.pathIndex < path.size) {
                    val (wx, wz) = path[npc.pathIndex]
                    val wxMm = wx * 1000 + 500
                    val wzMm = wz * 1000 + 500
                    val dx = (wxMm - t.xMm).toLong()
                    val dz = (wzMm - t.zMm).toLong()
                    if (dx * dx + dz * dz < 90_000) {
                        npc.pathIndex++
                        continue
                    }
                    if (worldDeltas.containsKey(WorldGen.packCell3(wx, 0, wz))) {
                        npc.path = null
                        npc.nextWanderTick = serverTick + 20
                        break
                    }
                    t.yawMrad = Math.round(atan2(dx.toDouble(), -dz.toDouble()) * 1000).toInt()
                    stepEntity(npc.netId.value, t, 1, t.yawMrad)
                    t.vxMm = Math.round(t.vxMm * 0.4).toInt()
                    t.vzMm = Math.round(t.vzMm * 0.4).toInt()
                    t.xMm = prevX + Math.round((t.xMm - prevX) * 0.4).toInt()
                    t.zMm = prevZ + Math.round((t.zMm - prevZ) * 0.4).toInt()
                    moved = true
                    break
                }
            }

            if (!moved && (t.vxMm != 0 || t.vzMm != 0)) {
                t.vxMm = 0
                t.vzMm = 0
                npc.dirty = true
            }

            val targetEnergy = if (npc.aiBusy) 90 + Random.nextInt(120) else 0
            if (t.xMm != prevX || t.zMm != prevZ || npc.audioEnergyByte != targetEnergy) {
                npc.audioEnergyByte = targetEnergy
                npc.dirty = true
            }

            // Perform the profession's work animation while lingering at the work spot
            val atWork = !conversing && npc.workAction != 0 &&
                (npc.path == null || npc.pathIndex >= (npc.path?.size ?: 0)) &&
                serverTick < npc.nextWanderTick && !moved
            val desiredAction = if (atWork) npc.workAction else 0
            if (npc.actionState != desiredAction) {
                npc.actionState = desiredAction
                if (desiredAction != 0) {
                    val dx = npc.workFocusX * 1000 - t.xMm
                    val dz = npc.workFocusZ * 1000 - t.zMm
                    t.yawMrad = Math.round(atan2(dx, -dz) * 1000).toInt()
                }
                npc.dirty = true
            }
        }
    }

    private fun broadcastDirtyEntities() {
        val dirty = ArrayList<QuantizedEntityTransform>()
        for (p in players.values) {
            if (!p.dirty) continue
            p.dirty = false
            dirty.add(transformOf(p.netId.value, p.transform, p.micEnergyByte, p.faceState, p.pitchMrad, p.headRollMrad, 0))
        }
        for (n in npcs.values) {
            if (!n.dirty) continue
            n.dirty = false
            dirty.add(transformOf(n.netId.value, n.transform, n.audioEnergyByte, 255, 0, 0, n.actionState))
        }

        for (recipient in players.values) {
            val event = EntityDeltaTickEvent.newBuilder()
                .setAckInputSeq(recipient.lastAckSeq)
                .addAllDirtyEntities(dirty)
                .build()
            hub.send(recipient.netId, pack(ServerEnvelope.newBuilder().setServerTick(tickInt()).setEntityDeltaTick(event)))
        }
    }

    fun broadcastNpcRefresh() {
        val lifecycle = EntityLifecycleEvent.newBuilder()
            .addAllSpawned(npcs.values.map { spawnInfo(it) })
            .build()
        broadcastEnvelope(ServerEnvelope.newBuilder().setServerTick(tickInt()).setEntityLifecycle(lifecycle))
    }

    fun broadcastNpcChunk(builder: ServerEnvelope.Builder, isAudio: Boolean) {
        broadcastEnvelope(builder.setServerTick(tickInt()), isAudio = isAudio)
    }

    private fun allSpawnInfos(): List<EntitySpawnInfo> =
        players.values.map { spawnInfo(it) } + npcs.values.map { spawnInfo(it) }

    private fun spawnInfo(p: Player): EntitySpawnInfo = EntitySpawnInfo.newBuilder()
        .setNetId(p.netId.value)
        .setType(EntityType.ENTITY_PLAYER)
        .setDisplayName(p.username)
        .setBaseColorHex(p.baseColorHex)
        .setXMm(p.transform.xMm)
        .setYMm(p.transform.yMm)
        .setZMm(p.transform.zMm)
        .setYawMrad(p.transform.yawMrad)
        .setAvatarHat(p.avatarHat)
        .setAvatarEyes(p.avatarEyes)
        .setAvatarMouth(p.avatarMouth)
        .setAvatarHair(p.avatarHair)
        .build()

    private fun spawnInfo(n: Npc): EntitySpawnInfo {
        val isMayor = n.profession == Profession.MAYOR
        // styleFlags: bit1 female, bit2 monocle, bit4 mustache (profession signatures)
        var flags = if (n.gender == "female") 1 else 0
        if (isMayor) flags = flags or 2 or 4
        // 255 = procedural (client hash picks hats/goggles/backpack); 5 = clockwork top hat
        val hat = if (isMayor) 5 else 255
        return EntitySpawnInfo.newBuilder()
            .setNetId(n.netId.value)
            .setType(EntityType.ENTITY_NPC)
            .setDisplayName("${n.name} (${n.profession.name})")
            .setBaseColorHex(n.baseColorHex)
            .setXMm(n.transform.xMm)
            .setYMm(n.transform.yMm)
            .setZMm(n.transform.zMm)
            .setYawMrad(n.transform.yawMrad)
            .setStyleFlags(flags)
            .setAvatarHat(hat)
            .setAvatarEyes(255)
            .setAvatarMouth(255)
            .setAvatarHair(255)
            .build()
    }

    private fun transformOf(netId: Int, t: dev.briiqn.verbalize.domain.model.MillimeterTransform, energyByte: Int, faceState: Int, pitchMrad: Int, headRollMrad: Int, actionState: Int): QuantizedEntityTransform =
        QuantizedEntityTransform.newBuilder()
            .setNetId(netId)
            .setXMm(t.xMm)
            .setYMm(t.yMm)
            .setZMm(t.zMm)
            .setVxMm(t.vxMm)
            .setVyMm(t.vyMm)
            .setVzMm(t.vzMm)
            .setYawMrad(t.yawMrad)
            .setAudioEnergyByte(energyByte)
            .setFaceState(faceState)
            .setPitchMrad(pitchMrad)
            .setHeadRollMrad(headRollMrad)
            .setActionState(actionState)
            .build()

    private fun deltaRecord(d: PlacedDelta): WorldDeltaRecord = WorldDeltaRecord.newBuilder()
        .setGridX(d.gridX)
        .setGridY(d.gridY)
        .setGridZ(d.gridZ)
        .setStructureType(d.structureType)
        .setStateFlags(d.stateFlags)
        .build()

    private fun tickInt(): Int = (serverTick and 0xffffffffL).toInt()

    private fun pack(builder: ServerEnvelope.Builder, isAudio: Boolean = false): ByteArray =
        PacketCompressor.pack(builder.build().toByteArray(), isAudio)

    private fun broadcastEnvelope(builder: ServerEnvelope.Builder, except: Int? = null, isAudio: Boolean = false) {
        hub.broadcast(pack(builder, isAudio), except?.let { NetId(it) })
    }
}
