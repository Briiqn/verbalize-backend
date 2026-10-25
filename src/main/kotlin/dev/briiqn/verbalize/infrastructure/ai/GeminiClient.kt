package dev.briiqn.verbalize.infrastructure.ai

import dev.briiqn.verbalize.domain.model.ChatTurn
import dev.briiqn.verbalize.domain.model.Persona
import dev.briiqn.verbalize.domain.ports.DialoguePort
import dev.briiqn.verbalize.domain.ports.PersonaGenerationPort
import dev.briiqn.verbalize.domain.ports.ReplyEvent
import dev.briiqn.verbalize.domain.ports.ToolSpec
import io.ktor.client.HttpClient
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration.Companion.milliseconds

private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models"

class GeminiClient(
    private val http: HttpClient,
    private val apiKey: String,
    private val model: String
) : PersonaGenerationPort, DialoguePort {
    private val json = Json { ignoreUnknownKeys = true }

    private fun generationConfig(extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        extra()
        if (model.contains("2.5")) {
            putJsonObject("thinkingConfig") { put("thinkingBudget", 0) }
        }
    }

    override suspend fun generate(roles: List<String>): List<Persona> = withTimeout(25_000.milliseconds) {
        val count = roles.size
        val roleList = roles.withIndex().joinToString("; ") { (i, r) -> "${i + 1}) $r" }
        val body = buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(buildJsonObject {
                            put(
                                "text",
                                "Create $count distinct villager characters for \"Verbalize\", a whimsical steampunk village of brass machines, " +
                                    "airships and smog. In order, they are: $roleList. For each give: a short memorable steampunk-flavoured first name " +
                                    "(one word, like \"Cogsworth\"); gender (\"male\" or \"female\", mix them); an overall tone (e.g. gruff, cheery, wistful, " +
                                    "conspiratorial); a speaking style in one sentence (vocabulary, verbal quirks, catchphrases); and a personality of 2-3 " +
                                    "sentences grounded in their stated profession (how they feel about the work, desires, oddities). Make them feel like " +
                                    "neighbours who know each other; vary ages and temperaments."
                            )
                        })
                    }
                })
            }
            put("generationConfig", generationConfig {
                put("temperature", 1.1)
                put("responseMimeType", "application/json")
                putJsonObject("responseSchema") {
                    put("type", "ARRAY")
                    putJsonObject("items") {
                        put("type", "OBJECT")
                        putJsonObject("properties") {
                            putJsonObject("name") { put("type", "STRING") }
                            putJsonObject("gender") {
                                put("type", "STRING")
                                putJsonArray("enum") { add("male"); add("female") }
                            }
                            putJsonObject("tone") { put("type", "STRING") }
                            putJsonObject("speakingStyle") { put("type", "STRING") }
                            putJsonObject("personality") { put("type", "STRING") }
                        }
                        putJsonArray("required") {
                            add("name"); add("gender"); add("tone"); add("speakingStyle"); add("personality")
                        }
                    }
                }
            })
        }

        http.preparePost("$GEMINI_BASE/$model:generateContent?key=$apiKey") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Gemini personas HTTP ${response.status.value}: ${response.bodyAsText().take(200)}")
            }
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val text = root["candidates"]!!.jsonArray[0].jsonObject["content"]!!
                .jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
            val personas = json.parseToJsonElement(text).jsonArray.map { el ->
                val o = el.jsonObject
                Persona(
                    name = o["name"]!!.jsonPrimitive.content,
                    gender = o["gender"]!!.jsonPrimitive.content,
                    tone = o["tone"]!!.jsonPrimitive.content,
                    speakingStyle = o["speakingStyle"]!!.jsonPrimitive.content,
                    personality = o["personality"]!!.jsonPrimitive.content
                )
            }
            if (personas.size < count) throw IllegalStateException("persona payload malformed")
            personas.take(count)
        }
    }

    override fun streamReply(
        persona: Persona,
        professionTitle: String,
        tools: List<ToolSpec>,
        history: List<ChatTurn>,
        playerName: String,
        transcript: String,
        reputation: Int
    ): Flow<ReplyEvent> = flow {
        val standing = when {
            reputation >= 80 -> "The whole village adores $playerName. Be warm, delighted, affectionate and generous with them."
            reputation >= 60 -> "You like $playerName and villagers speak well of them. Be friendly and welcoming."
            reputation >= 40 -> "$playerName is a stranger of no particular reputation. Be politely neutral and a little reserved."
            reputation >= 20 -> "$playerName has a poor reputation around the village. Be cool, curt and a little wary of them."
            else -> "$playerName is widely disliked and distrusted here. Be rude, dismissive and short with them (but never slurs or cruelty about protected traits)."
        }
        val system =
            "You are ${persona.name}, a villager in the steampunk village of Verbalize. You are $professionTitle.\n" +
                "Gender: ${persona.gender}. Tone: ${persona.tone}.\n" +
                "Speaking style: ${persona.speakingStyle}\n" +
                "Personality: ${persona.personality}\n" +
                "Reputation: $standing (their standing is $reputation out of 100).\n" +
                "You are talking face-to-face with a traveller named $playerName. Reply with ONLY your spoken words: " +
                "1-3 short sentences, under 45 words, no stage directions, no asterisks, no quotation marks. " +
                "You remember every earlier exchange in this conversation accurately — when asked about something said before, " +
                "recall it correctly and specifically. Answer what was actually asked before adding flavour. " +
                (if (tools.isNotEmpty())
                    "When the traveller asks you to do something your tools can do, CALL the tool AND say a short in-character line about doing it. "
                else "") +
                "Never break character and never mention being an AI.\n" +
                "AFTER your spoken reply, append on a NEW LINE a hidden tag of the exact form <<rapport:N>> where N is an " +
                "integer from -3 to 3 scoring how THIS message from the traveller changed your opinion of them " +
                "(politeness, warmth, kindness, wit raise it; rudeness, insults, hostility, threats lower it; neutral is 0). " +
                "This tag is secret stage-direction — NEVER speak it aloud and never mention rapport."
        val heard = transcript.trim().ifEmpty { "(indistinct mumbling)" }

        val body = buildJsonObject {
            putJsonObject("system_instruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", system) }) }
            }
            putJsonArray("contents") {
                for (turn in history) {
                    add(buildJsonObject {
                        put("role", turn.role)
                        putJsonArray("parts") { add(buildJsonObject { put("text", turn.text) }) }
                    })
                }
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", "$playerName says to you: \"$heard\"") }) }
                })
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    add(buildJsonObject {
                        putJsonArray("functionDeclarations") {
                            for (tool in tools) {
                                add(buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    if (tool.params.isNotEmpty()) {
                                        putJsonObject("parameters") {
                                            put("type", "OBJECT")
                                            putJsonObject("properties") {
                                                for ((pName, pDesc) in tool.params) {
                                                    putJsonObject(pName) {
                                                        put("type", "STRING")
                                                        put("description", pDesc)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                })
                            }
                        }
                    })
                }
            }
            put("generationConfig", generationConfig {
                put("temperature", 0.8)
                put("maxOutputTokens", 4096)
            })
        }

        http.preparePost("$GEMINI_BASE/$model:streamGenerateContent?alt=sse&key=$apiKey") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Gemini reply HTTP ${response.status.value}: ${response.bodyAsText().take(200)}")
            }
            val channel: ByteReadChannel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                try {
                    val parts = json.parseToJsonElement(payload).jsonObject["candidates"]?.jsonArray?.get(0)
                        ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray ?: continue
                    for (part in parts) {
                        val obj = part.jsonObject
                        val text = obj["text"]?.jsonPrimitive?.content
                        if (!text.isNullOrEmpty()) emit(ReplyEvent.TextDelta(text))
                        val call = obj["functionCall"]?.jsonObject
                        if (call != null) {
                            val name = call["name"]?.jsonPrimitive?.content ?: continue
                            val args = call["args"]?.jsonObject?.mapValues { (_, v) ->
                                try { v.jsonPrimitive.content } catch (_: Exception) { v.toString() }
                            } ?: emptyMap()
                            emit(ReplyEvent.ToolCall(name, args))
                        }
                    }
                } catch (_: Exception) {
                    // keepalive / partial SSE line
                }
            }
        }
    }
}
