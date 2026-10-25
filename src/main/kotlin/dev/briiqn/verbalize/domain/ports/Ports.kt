package dev.briiqn.verbalize.domain.ports

import dev.briiqn.verbalize.domain.model.Account
import dev.briiqn.verbalize.domain.model.AvatarConfig
import dev.briiqn.verbalize.domain.model.ChatTurn
import dev.briiqn.verbalize.domain.model.NetId
import dev.briiqn.verbalize.domain.model.Persona
import kotlinx.coroutines.flow.Flow

/** Blocking JDBC port; callers must dispatch to an IO context, never the game loop. */
interface AccountRepository {
    fun findByUsername(username: String): Account?
    fun create(username: String, passwordHash: String): Account
    fun saveAvatar(username: String, avatar: AvatarConfig)
    fun saveReputation(username: String, reputation: Int)
}

/** Outbound session transport: implementations must be thread-safe and never block the game loop. */
interface SessionHub {
    fun send(netId: NetId, frame: ByteArray)
    fun broadcast(frame: ByteArray, except: NetId? = null)
}

interface PersonaGenerationPort {
    /** roles: one description per character, in order (e.g. "the village mayor"). */
    suspend fun generate(roles: List<String>): List<Persona>
}

/** A callable in-game action exposed to the dialogue model. Params are all strings. */
data class ToolSpec(
    val name: String,
    val description: String,
    val params: Map<String, String> = emptyMap()
)

sealed interface ReplyEvent {
    data class TextDelta(val text: String) : ReplyEvent
    data class ToolCall(val name: String, val args: Map<String, String>) : ReplyEvent
}

interface DialoguePort {
    fun streamReply(
        persona: Persona,
        professionTitle: String,
        tools: List<ToolSpec>,
        history: List<ChatTurn>,
        playerName: String,
        transcript: String,
        reputation: Int
    ): Flow<ReplyEvent>
}

interface SpeechSynthesisPort {
    fun streamSpeech(text: String, voiceId: String): Flow<ByteArray>
}

/** Server-side transcription of raw mic audio. Returns "" when nothing intelligible is heard. */
interface SpeechToTextPort {
    suspend fun transcribe(audio: ByteArray, mimeType: String): String
}
