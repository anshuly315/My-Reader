@file:Suppress("MagicNumber", "TooManyFunctions")

package com.flowfolio.core.model

/**
 * How glass bars and sheets look. Each mode differs in several ways at once, so the difference shows even over a
 * plain light screen: [tint] is how much of the surface colour covers what is behind, [blurDp] how much that is
 * smudged, [grain] the frosted noise, [shine] a light sheen down from the top edge and [rim] how bright the edge is.
 *
 * Off is a solid bar; Soft is nearly solid with a gentle blur; Frosted is milky and grainy, with what is behind
 * blurred to colour only; Crystal is clear, so what scrolls behind stays readable, with a bright rim and sheen.
 */
enum class GlassMode(val blurDp: Int, val tint: Float, val grain: Float, val shine: Float, val rim: Float) {
    OFF(0, 1f, 0f, 0f, 0.75f),
    SOFT(10, 0.9f, 0.02f, 0.05f, 0.7f),
    FROSTED(30, 0.62f, 0.14f, 0.1f, 0.85f),
    CRYSTAL(3, 0.22f, 0f, 0.32f, 1f)
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
    const val SYNC_FOLDER = "sync_folder"
    const val SYNC_LAST = "sync_last"
    const val SYNC_ON_OPEN = "sync_on_open"
    const val TOOL_ORDER = "tool_order"
    const val TOOL_VIEW = "tool_view"
    const val WIDGET_THEME = "widget_theme"
    const val WIDGET_OPACITY = "widget_opacity"
    const val TAP_LEFT = "tap_left"
    const val TAP_CENTRE = "tap_centre"
    const val TAP_RIGHT = "tap_right"
    const val START_TAB = "start_tab"
    const val OPEN_LAST = "open_last"
    const val TAB_ORDER = "tab_order"
    const val DAY_PAPER = "day_paper"
    const val NIGHT_PAPER = "night_paper"

    /** Paper for Auto in a light app, then in a dark one (names of [PaperStyle]); a dark app starts on Black. */
    val dayPapers = listOf("AUTO", "PAPER", "SEPIA")
    val nightPapers = listOf("BLACK", "NIGHT", "RUSTIC")

    fun dayPaperIndex(o: Map<String, String>) = dayPapers.indexOf(o[DAY_PAPER]).coerceAtLeast(0)

    fun nightPaperIndex(o: Map<String, String>) = nightPapers.indexOf(o[NIGHT_PAPER]).coerceAtLeast(0)

    /** Tool names in the order the reader arranged them; tools it does not mention follow in their usual order. */
    fun toolOrder(o: Map<String, String>): List<String> =
        o[TOOL_ORDER]?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    /** How the Tools tab lays tools out: 0 icons, 1 compact (usual), 2 list, 3 large. */
    fun toolView(o: Map<String, String>) = (o[TOOL_VIEW]?.toIntOrNull() ?: 1).coerceIn(0, 3)

    /** Widgets' colours: 0 follow the app, 1 always light, 2 always dark. */
    fun widgetTheme(o: Map<String, String>) = (o[WIDGET_THEME]?.toIntOrNull() ?: 0).coerceIn(0, 2)

    /** Widgets' background, by index into [widgetOpacities]: solid, soft, glassy, clear. */
    val widgetOpacities = listOf(1f, 0.85f, 0.65f, 0.4f)

    fun widgetOpacityIndex(o: Map<String, String>) = (o[WIDGET_OPACITY]?.toIntOrNull() ?: 0).coerceIn(0, 3)

    /** What a tap in one third of the page does: 0 as usual, 1 back, 2 forward, 3 show or hide the bars, 4 nothing. */
    fun tapAction(o: Map<String, String>, key: String): Int {
        // The old "Off" tap zone (before the three thirds) meant no tap turns a page.
        val fallback = if (tapZone(o) == 2 && key != TAP_CENTRE) 4 else 0
        return (o[key]?.toIntOrNull() ?: fallback).coerceIn(0, 4)
    }

    /** The route the app opens on; anything unknown means the library. */
    fun startTab(o: Map<String, String>) = o[START_TAB]?.takeIf { it.isNotBlank() } ?: "library"

    /** Open the book read last, straight into the reader, when the app starts. */
    fun openLast(o: Map<String, String>) = o[OPEN_LAST] == "1"

    /** Tab routes in the order the reader chose; routes it does not mention keep their usual place after them. */
    fun tabOrder(o: Map<String, String>): List<String> = o[TAB_ORDER]?.split(',')?.filter { it.isNotBlank() }.orEmpty()

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
