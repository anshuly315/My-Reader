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

    val coverRadiusDp = listOf(0, 4, 8, 14, 20)
    val coverSizes = listOf(0.8f, 1f, 1.2f, 1.5f, 1.8f)

    fun glass(o: Map<String, String>): GlassMode =
        runCatching { GlassMode.valueOf(o[GLASS].orEmpty()) }.getOrDefault(GlassMode.FROSTED)

    fun coverRadiusIndex(o: Map<String, String>) = (o[COVER_RADIUS]?.toIntOrNull() ?: 2).coerceIn(0, coverRadiusDp.lastIndex)

    fun coverSizeIndex(o: Map<String, String>) = (o[COVER_SIZE]?.toIntOrNull() ?: 1).coerceIn(0, coverSizes.lastIndex)

    fun hiddenTabs(o: Map<String, String>): Set<String> =
        o[HIDDEN_TABS]?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()
}
