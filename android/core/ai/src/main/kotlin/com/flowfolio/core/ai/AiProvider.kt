@file:Suppress("NestedBlockDepth", "ThrowsCount", "MagicNumber")

package com.flowfolio.core.ai

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Something that turns a conversation into streamed text. */
interface AiProvider {
    /** The answer to [messages], as pieces of text (deltas) in the order they arrive. */
    fun stream(messages: List<ChatMessage>): Flow<String>
}

internal val aiJson = Json { ignoreUnknownKeys = true }

/**
 * Posts [body] and hands each `data:` line of the server-sent-event reply to [onData]. The connection is closed the
 * moment the collecting coroutine is cancelled, which also unblocks a read in progress.
 */
internal suspend fun postEvents(
    url: String,
    headers: Map<String, String>,
    body: String,
    onData: suspend (String) -> Unit
) {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = CONNECT_MS
        readTimeout = READ_MS
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "text/event-stream")
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    val job: Job? = coroutineContext[Job]
    val handle = job?.invokeOnCompletion { conn.disconnect() }
    try {
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val text = (conn.errorStream ?: conn.inputStream).bufferedReader().use(BufferedReader::readText)
            throw AiException(describeHttpError(code, text))
        }
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                coroutineContext.ensureActive()
                val line = reader.readLine() ?: break
                if (line.startsWith("data:")) onData(line.removePrefix("data:").trim())
            }
        }
    } catch (e: AiException) {
        throw e
    } catch (e: java.io.IOException) {
        coroutineContext.ensureActive()
        throw AiException("Could not reach the AI service: ${e.message ?: e.javaClass.simpleName}", e)
    } finally {
        handle?.dispose()
        conn.disconnect()
    }
}

/** A readable sentence for a failed request: the service's own message where it gave one. */
internal fun describeHttpError(code: Int, body: String): String {
    val detail = runCatching {
        val root = aiJson.parseToJsonElement(body).jsonObject
        val err = root["error"]
        when (err) {
            is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNull
            is JsonPrimitive -> err.contentOrNull
            else -> root["message"]?.jsonPrimitive?.contentOrNull
        }
    }.getOrNull()
    val hint = when (code) {
        401, 403 -> "The key was refused. Check it in Settings → AI."
        404 -> "The address or model was not found."
        429 -> "Too many requests, or the quota is used up."
        else -> ""
    }
    return listOfNotNull("Error $code", detail?.take(MAX_DETAIL), hint.ifEmpty { null }).joinToString(". ")
}

/** `POST {base}/chat/completions`, the dialect nearly every service speaks. */
class OpenAiCompatProvider(private val baseUrl: String, private val model: String, private val apiKey: String) :
    AiProvider {
    override fun stream(messages: List<ChatMessage>): Flow<String> = flow {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("temperature", TEMPERATURE)
            put(
                "messages",
                buildJsonArray {
                    messages.forEach { m ->
                        add(
                            buildJsonObject {
                                put("role", m.role.name.lowercase())
                                put("content", m.content)
                            }
                        )
                    }
                }
            )
        }.toString()
        val headers = buildMap {
            if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
        }
        postEvents(chatUrl(baseUrl), headers, body) { data ->
            if (data == "[DONE]") return@postEvents
            val delta = runCatching { deltaOf(data) }.getOrNull()
            if (!delta.isNullOrEmpty()) emit(delta)
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        fun chatUrl(base: String): String {
            val b = base.trim().trimEnd('/')
            return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
        }

        /** The text added by one streamed `chat.completion.chunk`, or null. */
        fun deltaOf(data: String): String? =
            aiJson.parseToJsonElement(data).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
    }
}

/** Anthropic's Messages API. */
class AnthropicProvider(private val baseUrl: String, private val model: String, private val apiKey: String) :
    AiProvider {
    override fun stream(messages: List<ChatMessage>): Flow<String> = flow {
        val system = messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }
        val turns = messages.filter { it.role != Role.SYSTEM }
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            put("stream", true)
            if (system.isNotBlank()) put("system", system)
            put(
                "messages",
                JsonArray(
                    turns.map { m ->
                        buildJsonObject {
                            put("role", m.role.name.lowercase())
                            put("content", m.content)
                        }
                    }
                )
            )
        }.toString()
        val headers = mapOf("x-api-key" to apiKey, "anthropic-version" to VERSION)
        postEvents(messagesUrl(baseUrl), headers, body) { data ->
            val event = runCatching { aiJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@postEvents
            when (event["type"]?.jsonPrimitive?.contentOrNull) {
                "content_block_delta" -> {
                    val text = event["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                    if (!text.isNullOrEmpty()) emit(text)
                }

                "error" -> throw AiException(
                    event["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "The service failed."
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val VERSION = "2023-06-01"

        fun messagesUrl(base: String): String {
            val b = base.trim().trimEnd('/')
            return if (b.endsWith("/v1/messages")) b else "$b/v1/messages"
        }
    }
}

private const val CONNECT_MS = 15_000
private const val READ_MS = 90_000
private const val MAX_DETAIL = 240
private const val MAX_TOKENS = 2048
private const val TEMPERATURE = 0.3
