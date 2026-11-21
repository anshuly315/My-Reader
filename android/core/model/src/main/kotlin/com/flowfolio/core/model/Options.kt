@file:Suppress("MagicNumber")

package com.flowfolio.core.model

/** How strongly the glass bars and panels blur what is behind them. */
enum class GlassMode(val blurDp: Int, val tint: Float) {
    OFF(0, 1f),
    SOFT(10, 0.9f),
    FROSTED(18, 0.8f),
    CRYSTAL(30, 0.55f)
}

/** The Customise page's choices, read from [UserSettings.options] with a default for anything unset. */
object Options {
    const val GLASS = "glass"
    const val COVER_RADIUS = "cover_radius"
    const val COVER_SIZE = "cover_size"
    const val HIDDEN_TABS = "hidden_tabs"
    const val HIDDEN_TOOLS = "hidden_tools"
    const val ORIENTATION = "orientation"
    const val BLUE_LIGHT = "blue_light"
    const val COLUMNS = "columns"
    const val VOLUME_KEYS = "volume_keys"
    const val TAP_ZONE = "tap_zone"

    val orientations = listOf("auto", "portrait", "landscape")

    fun orientationIndex(o: Map<String, String>) = orientations.indexOf(o[ORIENTATION]).coerceAtLeast(0)

    /** 0 is off; 1 to 3 warm the screen more and more. */
    fun blueLight(o: Map<String, String>) = (o[BLUE_LIGHT]?.toIntOrNull() ?: 0).coerceIn(0, 3)

    /** Taps that turn pages: 0 either half of the page, 1 only the outer edges, 2 none (swipe only). */
    fun tapZone(o: Map<String, String>) = (o[TAP_ZONE]?.toIntOrNull() ?: 0).coerceIn(0, 2)

    /** The volume buttons in the reader: 0 leave them to the phone, 1 volume down turns forward, 2 volume up does. */
    fun volumeKeys(o: Map<String, String>) = (o[VOLUME_KEYS]?.toIntOrNull() ?: 0).coerceIn(0, 2)

    /** Books across the library grid: 0 lets the screen width decide, 2 to 6 fixes it. */
    fun columns(o: Map<String, String>) = (o[COLUMNS]?.toIntOrNull() ?: 0).let { if (it in 2..6) it else 0 }

    val coverRadiusDp = listOf(0, 4, 8, 14, 20)
    val coverSizes = listOf(0.8f, 1f, 1.2f, 1.5f, 1.8f)

    fun glass(o: Map<String, String>): GlassMode =
        runCatching { GlassMode.valueOf(o[GLASS].orEmpty()) }.getOrDefault(GlassMode.FROSTED)

    fun coverRadiusIndex(o: Map<String, String>) =
        (o[COVER_RADIUS]?.toIntOrNull() ?: 2).coerceIn(0, coverRadiusDp.lastIndex)

    fun coverSizeIndex(o: Map<String, String>) = (o[COVER_SIZE]?.toIntOrNull() ?: 1).coerceIn(0, coverSizes.lastIndex)

    fun hiddenTools(o: Map<String, String>): Set<String> = hiddenTabs(o + (HIDDEN_TABS to o[HIDDEN_TOOLS].orEmpty()))

    fun hiddenTabs(o: Map<String, String>): Set<String> =
        o[HIDDEN_TABS]?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()
}

/** Hands the volume buttons from the activity to the open book while the reader asks for them. */
object ReaderKeys {
    /** Set by the open reader (main thread); null when the buttons belong to the phone. */
    @Volatile
    var onVolume: ((up: Boolean) -> Unit)? = null
}
