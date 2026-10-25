package dev.briiqn.verbalize.infrastructure.ai

import dev.briiqn.verbalize.domain.ports.SpeechToTextPort
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models"

/**
 * Transcribes raw browser mic audio using Gemini's multimodal input. The browser
 * records WebM/Opus, which Gemini can't ingest directly, so ffmpeg transcodes it to
 * FLAC first. Returns "" when nothing intelligible is heard.
 */
class GeminiSpeechToText(
    private val http: HttpClient,
    private val apiKey: String,
    private val model: String
) : SpeechToTextPort {
    private val log = LoggerFactory.getLogger(GeminiSpeechToText::class.java)

    override suspend fun transcribe(audio: ByteArray, mimeType: String): String {
        val flac = withContext(Dispatchers.IO) { transcodeToFlac(audio) } ?: return ""
        val b64 = Base64.getEncoder().encodeToString(flac)
        val body = buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(buildJsonObject {
                            put("text", "Transcribe the spoken words in this audio exactly, in the speaker's own language. " +
                                "Output ONLY the transcription, with no quotes or commentary. If there is no intelligible speech, output nothing.")
                        })
                        add(buildJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", "audio/flac")
                                put("data", b64)
                            }
                        })
                    }
                })
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.0)
                put("maxOutputTokens", 256)
            }
        }
        val response = http.post("$GEMINI_BASE/$model:generateContent?key=$apiKey") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        if (!response.status.isSuccess()) {
            log.warn("[stt] Gemini HTTP {}: {}", response.status.value, response.bodyAsText().take(200))
            return ""
        }
        return try {
            val text = Json.parseToJsonElement(response.bodyAsText())
                .jsonObject["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
                ?.joinToString("")
                ?.trim()
                .orEmpty()
            text
        } catch (e: Exception) {
            log.warn("[stt] Gemini parse failed: {}", e.message)
            ""
        }
    }

    private fun transcodeToFlac(webm: ByteArray): ByteArray? {
        val inFile = File.createTempFile("vz-stt-", ".webm")
        val outFile = File.createTempFile("vz-stt-", ".flac")
        try {
            inFile.writeBytes(webm)
            val proc = ProcessBuilder(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-i", inFile.absolutePath, "-ac", "1", "-ar", "16000", outFile.absolutePath
            ).redirectErrorStream(true).start()
            if (!proc.waitFor(15, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                log.warn("[stt] ffmpeg timed out")
                return null
            }
            if (proc.exitValue() != 0 || !outFile.exists() || outFile.length() == 0L) {
                log.warn("[stt] ffmpeg transcode failed (exit {})", proc.exitValue())
                return null
            }
            return outFile.readBytes()
        } catch (e: Exception) {
            log.warn("[stt] transcode error: {}", e.message)
            return null
        } finally {
            inFile.delete()
            outFile.delete()
        }
    }
}
