package dev.briiqn.verbalize.domain.model

import dev.briiqn.verbalize.domain.simulation.Simulation

@JvmInline
value class NetId(val value: Int)

/** Millimeter-quantized authoritative transform shared by all mobile entities. */
class MillimeterTransform {
    var xMm = 0
    var yMm = 0
    var zMm = 0
    var vxMm = 0
    var vyMm = 0
    var vzMm = 0
    var yawMrad = 0

    fun positionMeters() = Simulation.Vec3(xMm / 1000.0, yMm / 1000.0, zMm / 1000.0)
    fun velocityMeters() = Simulation.Vec3(vxMm / 1000.0, vyMm / 1000.0, vzMm / 1000.0)

    fun applyStep(result: Simulation.StepResult) {
        xMm = Math.round(result.nextPos.x * 1000).toInt()
        yMm = Math.round(result.nextPos.y * 1000).toInt()
        zMm = Math.round(result.nextPos.z * 1000).toInt()
        vxMm = Math.round(result.velocity.x * 1000).toInt()
        vyMm = Math.round(result.velocity.y * 1000).toInt()
        vzMm = Math.round(result.velocity.z * 1000).toInt()
    }
}

class MovementIntent(
    val sequenceNumber: Int,
    val keyBitmask: Int,
    val yawMrad: Int,
    val pitchMrad: Int,
    val micEnergyByte: Int,
    val faceState: Int = 255,
    val headRollMrad: Int = 0
)

class Player(
    val netId: NetId,
    val username: String,
    var baseColorHex: Int
) {
    var avatarHat = 255
    var avatarEyes = 255
    var avatarMouth = 255
    var avatarHair = 255
    var reputation = Account.DEFAULT_REPUTATION // 0..100 standing with the villagers, loaded from the DB
    val transform = MillimeterTransform()
    var micEnergyByte = 0
    var faceState = 255
    var pitchMrad = 0
    var headRollMrad = 0
    var faceStreak = 0
    var streakFace = -1
    var lastAckSeq = 0
    var dirty = true
    val pendingIntents = ArrayDeque<MovementIntent>()

    // adaptive voice-activity baseline
    var noiseFloor = 0.0
    var noiseDev = 0.0
    var lastSpeechTick = -1_000_000L

    val npcTranscripts = HashMap<Int, String>()
    // raw mic audio accumulated per target NPC until the final chunk, then transcribed
    val npcAudio = HashMap<Int, java.io.ByteArrayOutputStream>()

    fun queueIntent(intent: MovementIntent) {
        if (pendingIntents.size < 64) pendingIntents.addLast(intent)
    }
}

data class Persona(
    val name: String,
    val gender: String,
    val tone: String,
    val speakingStyle: String,
    val personality: String
)

class ChatTurn(val role: String, val text: String)

enum class Profession(val title: String) {
    MAYOR("the village mayor"),
    BLACKSMITH("the blacksmith"),
    LUMBERJACK("the lumberjack who fells and tends trees"),
    FARMER("the farmer who works the field rows"),
    BUILDER("the master builder who constructs houses"),
    NAVIGATOR("a retired airship navigator")
}

class Npc(
    val netId: NetId,
    var name: String,
    var gender: String,
    val baseColorHex: Int,
    val profession: Profession = Profession.NAVIGATOR
) {
    val transform = MillimeterTransform()
    var audioEnergyByte = 0
    var dirty = true

    var path: List<Pair<Int, Int>>? = null
    var pathIndex = 0
    var nextWanderTick = 0L
    var conversationHoldUntil = 0L
    var nextReactionTick = 0L
    var workCursor = 0
    var actionState = 0 // 0 none, 1 chop, 2 hammer, 3 farm, 4 tinker
    var workAction = 0
    var workFocusX = 0.0
    var workFocusZ = 0.0

    @Volatile var persona: Persona? = null
    @Volatile var voiceId: String? = null
    @Volatile var aiBusy = false
    val chatHistory = ArrayDeque<ChatTurn>() // guarded by aiBusy single-flight
}
