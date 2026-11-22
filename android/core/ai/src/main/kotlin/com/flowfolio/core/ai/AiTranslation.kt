package com.flowfolio.core.ai

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** What the Translate panel needs from the AI: whether it is set up, whether it is online, and a translation. */
interface AiTranslation {
    /** True once Settings → AI has a provider that can answer. */
    val ready: Boolean

    /** True when the passage leaves the phone (an online provider), false for the on-device model. */
    val online: Boolean

    /** The translation of [passage] into the language [code], growing as it streams in. */
    fun translate(passage: String, code: String): Flow<String>

    /** No AI at all, for tests and previews. */
    object None : AiTranslation {
        override val ready = false
        override val online = false
        override fun translate(passage: String, code: String): Flow<String> = emptyFlow()
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AiTranslationModule {
    @Binds
    abstract fun bindTranslation(service: AiService): AiTranslation
}
