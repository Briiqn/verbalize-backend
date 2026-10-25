package dev.briiqn.verbalize.infrastructure.ai

import dev.briiqn.verbalize.domain.ports.SpeechSynthesisPort
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object VoicePools {
    val MALE = listOf("JBFqnCBsd6RMkjVDRZzb", "TX3LPaxmHKxFdv7VOQHJ", "onwK4e9ZLuTAKqWW03F9")
    val FEMALE = listOf("EXAVITQu4vr4xnSDxMaL", "XB0fDUnXU5powFXDhCwa", "Xb7hH8MSUJpSbSDYk0k2")
}

class ElevenLabsClient(
    private val http: HttpClient,
    private val apiKey: String
) : SpeechSynthesisPort {
    override fun streamSpeech(text: String, voiceId: String): Flow<ByteArray> = flow {
        val body = buildJsonObject {
            put("text", text)
            put("model_id", "eleven_turbo_v2_5")
            putJsonObject("voice_settings") {
                put("stability", 0.45)
                put("similarity_boost", 0.75)
            }
        }
        http.preparePost("https://api.elevenlabs.io/v1/text-to-speech/$voiceId/stream?output_format=mp3_44100_64") {
            header("xi-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw IllegalStateException("ElevenLabs HTTP ${response.status.value}: ${response.bodyAsText().take(200)}")
            }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(8192)
            // buffer & stream
            var pending = ByteArray(0)
            while (true) {
                val n = channel.readAvailable(buffer, 0, buffer.size)
                if (n == -1) break
                if (n == 0) continue
                pending = pending + buffer.copyOf(n)
                if (pending.size >= 12288) {
                    emit(pending)
                    pending = ByteArray(0)
                }
            }
            if (pending.isNotEmpty()) emit(pending)
        }
    }
}
