package dev.briiqn.verbalize.application

import com.google.protobuf.ByteString
import dev.briiqn.verbalize.domain.dialogue.CannedLines
import dev.briiqn.verbalize.domain.model.ChatTurn
import dev.briiqn.verbalize.domain.model.Npc
import dev.briiqn.verbalize.domain.model.Player
import dev.briiqn.verbalize.domain.model.Profession
import dev.briiqn.verbalize.domain.ports.DialoguePort
import dev.briiqn.verbalize.domain.ports.ReplyEvent
import dev.briiqn.verbalize.domain.ports.SpeechSynthesisPort
import dev.briiqn.verbalize.domain.ports.ToolSpec
import dev.briiqn.verbalize.infrastructure.proto.NpcStreamChunkEvent
import dev.briiqn.verbalize.infrastructure.proto.ServerEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class NpcDialogueService(
    private val game: GameLoopService,
    private val dialogue: DialoguePort?,
    private val tts: SpeechSynthesisPort?,
    private val stt: dev.briiqn.verbalize.domain.ports.SpeechToTextPort?,
    private val scope: CoroutineScope
) {
    private val log = LoggerFactory.getLogger(NpcDialogueService::class.java)

    /** Transcribe the player's raw mic audio, then reply to what they said. */
    fun startVoiceReply(npc: Npc, player: Player, audio: ByteArray, tick: Long) {
        val transcriber = stt
        if (transcriber == null || npc.aiBusy) {
            if (transcriber == null) log.warn("[ai] voice interact dropped — no speech-to-text configured")
            return
        }
        npc.aiBusy = true
        scope.launch {
            try {
                val heard = try {
                    transcriber.transcribe(audio, "audio/webm")
                } catch (e: Exception) {
                    log.warn("[ai] transcription failed for {}: {}", player.username, e.message); ""
                }
                if (heard.isBlank()) {
                    log.info("[ai] {} heard nothing intelligible from {}", npc.name, player.username)
                    return@launch
                }
                log.info("[ai] {} understood {}: \"{}\"", npc.name, player.username, heard.take(60))
                runReply(npc, player, heard, tick)
            } finally {
                npc.aiBusy = false
            }
        }
    }

    fun startReply(npc: Npc, player: Player, heard: String, tick: Long) {
        if (npc.aiBusy) {
            log.info("[ai] {} busy — ignoring interact from {}", npc.name, player.username)
            return
        }
        npc.aiBusy = true
        scope.launch {
            try {
                runReply(npc, player, heard, tick)
            } finally {
                npc.aiBusy = false
            }
        }
    }

    private suspend fun runReply(npc: Npc, player: Player, heard: String, tick: Long) {
        val interactionId = "${npc.netId.value}-$tick-${player.netId.value}"
        var chunkIndex = 0

        fun sendChunk(textDelta: String, audio: ByteString, isComplete: Boolean, isAudio: Boolean = false) {
            val chunk = NpcStreamChunkEvent.newBuilder()
                .setInteractionId(interactionId)
                .setNpcNetId(npc.netId.value)
                .setTextDelta(textDelta)
                .setAudioChunk(audio)
                .setChunkIndex(chunkIndex++)
                .setIsComplete(isComplete)
                .build()
            game.broadcastNpcChunk(ServerEnvelope.newBuilder().setNpcStreamChunk(chunk), isAudio)
        }

        suspend fun sendCannedLine(line: String): String {
            val words = line.split(' ')
            var i = 0
            while (i < words.size) {
                val delta = (if (i == 0) "" else " ") + words.subList(i, minOf(words.size, i + 2)).joinToString(" ")
                sendChunk(delta, ByteString.EMPTY, false)
                delay(110)
                i += 2
            }
            return line
        }

        var fullText = ""
        val toolsUsed = ArrayList<String>()
        // Hidden "<<rapport:N>>" tag the model appends — stripped from the stream, drives reputation.
        val emitBuf = StringBuilder()
        var repDelta = 0
        val rapportRe = Regex("<<\\s*rapport\\s*:\\s*([+-]?\\d+)\\s*>>", RegexOption.IGNORE_CASE)
        fun flushSafe(finalize: Boolean) {
            rapportRe.find(emitBuf)?.let { m ->
                repDelta = m.groupValues[1].toInt().coerceIn(-3, 3)
                emitBuf.delete(m.range.first, emitBuf.length) // drop the tag and anything after it
            }
            // withhold a possible partial tag starting at a trailing '<' until more text (or finalize) arrives
            val safeEnd = if (finalize) emitBuf.length else emitBuf.lastIndexOf("<").let { if (it >= 0) it else emitBuf.length }
            if (safeEnd > 0) {
                val out = emitBuf.substring(0, safeEnd)
                emitBuf.delete(0, safeEnd)
                fullText += out
                sendChunk(out, ByteString.EMPTY, false)
            }
        }
        // chunk 0: empty "composing" signal so clients can show a typing indicator immediately
        sendChunk("", ByteString.EMPTY, false)
        try {
            val persona = npc.persona
            if (dialogue != null && persona != null) {
                dialogue.streamReply(
                    persona,
                    npc.profession.title,
                    toolsFor(npc),
                    npc.chatHistory.toList(),
                    player.username,
                    heard,
                    player.reputation
                ).collect { event ->
                    when (event) {
                        is ReplyEvent.TextDelta -> {
                            emitBuf.append(event.text)
                            flushSafe(false)
                        }
                        is ReplyEvent.ToolCall -> {
                            toolsUsed.add(event.name)
                            dispatchTool(npc, player, event)
                        }
                    }
                }
                flushSafe(true)
                if (repDelta != 0) {
                    val reason = if (repDelta > 0) "${npc.name} warmed to you" else "${npc.name} took offence"
                    game.submitReputationDelta(player.netId.value, repDelta, reason)
                }
                if (fullText.isBlank() && toolsUsed.isNotEmpty()) {
                    fullText = sendCannedLine(acknowledgmentFor(toolsUsed.first()))
                }
                if (fullText.isBlank()) throw IllegalStateException("empty Gemini reply")
                val userLine = "${player.username} says: \"${heard.ifBlank { "(indistinct mumbling)" }}\""
                val modelLine = fullText + if (toolsUsed.isNotEmpty()) " [actions performed: ${toolsUsed.joinToString(", ")}]" else ""
                npc.chatHistory.addLast(ChatTurn("user", userLine))
                npc.chatHistory.addLast(ChatTurn("model", modelLine))
                while (npc.chatHistory.size > 24) npc.chatHistory.removeFirst()
                log.info(
                    "[ai] {} replied to {} (memory: {} exchanges{})",
                    npc.name, player.username, npc.chatHistory.size / 2,
                    if (toolsUsed.isNotEmpty()) ", tools: ${toolsUsed.joinToString(",")}" else ""
                )
            } else {
                fullText = sendCannedLine(CannedLines.reactionTo(heard))
            }
        } catch (e: Exception) {
            log.warn("[ai] reply via Gemini failed for {}: {}", npc.name, e.message)
            fullText = sendCannedLine(CannedLines.random())
        }

        val spoken = fullText.replace(Regex("\\*[^*]*\\*"), " ").replace(Regex("\\s+"), " ").trim()
        val voiceId = npc.voiceId
        if (tts != null && voiceId != null && spoken.isNotEmpty()) {
            try {
                tts.streamSpeech(spoken, voiceId).collect { audio ->
                    sendChunk("", ByteString.copyFrom(audio), false, isAudio = true)
                }
            } catch (e: Exception) {
                log.warn("[ai] TTS failed for {}: {}", npc.name, e.message)
            }
        }

        sendChunk("", ByteString.EMPTY, true)
    }

    /** Spontaneous canned quip when a villager notices a player's expression; no Gemini call. */
    fun startExpressionReaction(npc: Npc, player: Player, faceState: Int, tick: Long) {
        if (npc.aiBusy) return
        npc.aiBusy = true
        scope.launch {
            try {
                val interactionId = "${npc.netId.value}-$tick-face${player.netId.value}"
                var chunkIndex = 0
                fun sendChunk(textDelta: String, audio: ByteString, isComplete: Boolean, isAudio: Boolean = false) {
                    val chunk = NpcStreamChunkEvent.newBuilder()
                        .setInteractionId(interactionId)
                        .setNpcNetId(npc.netId.value)
                        .setTextDelta(textDelta)
                        .setAudioChunk(audio)
                        .setChunkIndex(chunkIndex++)
                        .setIsComplete(isComplete)
                        .build()
                    game.broadcastNpcChunk(ServerEnvelope.newBuilder().setNpcStreamChunk(chunk), isAudio)
                }

                val line = CannedLines.expressionReaction(faceState)
                val words = line.split(' ')
                var i = 0
                while (i < words.size) {
                    sendChunk((if (i == 0) "" else " ") + words.subList(i, minOf(words.size, i + 2)).joinToString(" "), ByteString.EMPTY, false)
                    delay(110)
                    i += 2
                }

                val voiceId = npc.voiceId
                if (tts != null && voiceId != null) {
                    try {
                        tts.streamSpeech(line, voiceId).collect { audio ->
                            sendChunk("", ByteString.copyFrom(audio), false, isAudio = true)
                        }
                    } catch (e: Exception) {
                        log.warn("[ai] reaction TTS failed for {}: {}", npc.name, e.message)
                    }
                }
                sendChunk("", ByteString.EMPTY, true)

                npc.chatHistory.addLast(ChatTurn("user", "(${player.username} looks at you ${CannedLines.expressionDescription(faceState)})"))
                npc.chatHistory.addLast(ChatTurn("model", line))
                while (npc.chatHistory.size > 24) npc.chatHistory.removeFirst()
            } finally {
                npc.aiBusy = false
            }
        }
    }

    private fun toolsFor(npc: Npc): List<ToolSpec> {
        val tools = mutableListOf(
            ToolSpec(
                "walk_to_player",
                "Walk over and stand beside the traveller you are talking to. Use when asked to come along, follow, or come closer."
            ),
            ToolSpec(
                "visit_landmark",
                "Walk to a village landmark.",
                mapOf("name" to "One of: plaza, tower, forge, warehouse, farm")
            )
        )
        if (npc.profession == Profession.BUILDER) {
            tools.add(
                ToolSpec(
                    "build_house",
                    "Construct a brand-new small house for the traveller, built block by block near where they stand. Use when asked to build them a house, home, hut or shelter.",
                    mapOf("style" to "A short phrase describing the requested style")
                )
            )
        }
        return tools
    }

    private fun dispatchTool(npc: Npc, player: Player, call: ReplyEvent.ToolCall) {
        val action = when (call.name) {
            "walk_to_player" -> GameLoopService.VillagerAction.WalkToPlayer(player.netId.value)
            "visit_landmark" -> GameLoopService.VillagerAction.VisitLandmark(call.args["name"] ?: "plaza")
            "build_house" -> GameLoopService.VillagerAction.BuildHouse(player.netId.value, call.args["style"] ?: "cozy")
            else -> {
                log.warn("[ai] {} called unknown tool {}", npc.name, call.name)
                return
            }
        }
        game.submit(GameLoopService.Command.NpcAction(npc.netId.value, action))
    }

    private fun acknowledgmentFor(tool: String): String = when (tool) {
        "build_house" -> "A house, you say? Stand back — I shall raise you one right here, beam by beam."
        "walk_to_player" -> "Hold fast, I am coming over."
        else -> "Very well, off I go."
    }
}
