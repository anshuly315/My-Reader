@file:Suppress("TooGenericExceptionCaught")

package com.flowfolio.core.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gemma on the phone through LiteRT-LM. The engine takes seconds to load, so it is kept for the next question; the GPU
 * is tried first and the CPU used when the phone cannot run it.
 */
@Singleton
class OnDeviceProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val model: OnDeviceModel
) : AiProvider {
    private val lock = Mutex()
    private var engine: Engine? = null

    private suspend fun engine(): Engine = lock.withLock {
        engine?.let { return it }
        if (!model.has()) throw AiException("No on-device model yet. Download or import one in Settings → AI.")
        val cache = File(context.cacheDir, "litertlm").apply { mkdirs() }.absolutePath
        fun load(backend: Backend) =
            Engine(EngineConfig(modelPath = model.file.absolutePath, backend = backend, cacheDir = cache))
                .also { it.initialize() }
        val created = try {
            load(Backend.GPU())
        } catch (_: Throwable) {
            load(Backend.CPU())
        }
        engine = created
        created
    }

    /** Frees the model's memory; the next question loads it again. */
    suspend fun unload() = lock.withLock {
        engine?.close()
        engine = null
    }

    override fun stream(messages: List<ChatMessage>): Flow<String> = flow {
        val system = messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }
        // The engine answers one message; earlier turns are folded into it as a transcript.
        val turns = messages.filter { it.role != Role.SYSTEM }
        val prompt = if (turns.size <= 1) {
            turns.firstOrNull()?.content.orEmpty()
        } else {
            turns.dropLast(1).joinToString("\n") {
                (if (it.role == Role.USER) "Reader: " else "Assistant: ") + it.content
            } + "\n\nReader: " + turns.last().content
        }
        val conversation = engine().createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3)
            )
        )
        val chunks = Channel<String>(Channel.UNLIMITED)
        try {
            conversation.sendMessageAsync(
                Contents.of(prompt),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        chunks.trySend(message.toString())
                    }

                    override fun onDone() {
                        chunks.close()
                    }

                    override fun onError(throwable: Throwable) {
                        chunks.close(throwable)
                    }
                }
            )
            for (piece in chunks) emit(piece)
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { conversation.cancelProcess() }
            throw e
        } catch (e: Throwable) {
            throw AiException(e.message ?: "The on-device model failed.", e)
        } finally {
            runCatching { conversation.close() }
        }
    }.flowOn(Dispatchers.Default)
}
