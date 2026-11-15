package com.nuvio.tv.core.multiview

/** What Back does on the multiview screen, innermost thing first. Pure, so the order is tested. */
internal enum class MultiviewBackAction {
    CLOSE_PICKER,
    CLOSE_LAYOUT_PICKER,
    CLOSE_TILE_MENU,
    EXIT_FULLSCREEN,
    EXIT_MULTIVIEW,
}

internal object MultiviewBackPolicy {
    fun action(
        channelPickerOpen: Boolean,
        layoutPickerOpen: Boolean,
        tileMenuOpen: Boolean,
        fullscreen: Boolean,
    ): MultiviewBackAction = when {
        channelPickerOpen -> MultiviewBackAction.CLOSE_PICKER
        layoutPickerOpen -> MultiviewBackAction.CLOSE_LAYOUT_PICKER
        tileMenuOpen -> MultiviewBackAction.CLOSE_TILE_MENU
        fullscreen -> MultiviewBackAction.EXIT_FULLSCREEN
        else -> MultiviewBackAction.EXIT_MULTIVIEW
    }
}

/**
 * OK on a tile: a short press opens it full screen, a long press opens the tile menu. The press is
 * decided on the key events alone, and a KeyUp (or a repeat) whose KeyDown happened elsewhere does
 * nothing, so an OK that opened a panel never "clicks" the tile it lands on.
 */
internal class CenterPressTracker {
    enum class Result { NONE, ACTIVATE, LONG_PRESS }

    private var down = false
    private var fired = false

    /** [repeatCount] is the platform's key repeat counter: 0 for the first KeyDown, above 0 while held. */
    fun onKeyDown(repeatCount: Int): Result {
        if (repeatCount <= 0) {
            // A fresh KeyDown starts a new press, even if the last one's KeyUp went to a panel that took focus.
            down = true
            fired = false
            return Result.NONE
        }
        if (!down || fired) return Result.NONE
        fired = true
        return Result.LONG_PRESS
    }

    fun onKeyUp(): Result {
        if (!down) return Result.NONE
        val longPressed = fired
        down = false
        fired = false
        return if (longPressed) Result.NONE else Result.ACTIVATE
    }

    fun reset() {
        down = false
        fired = false
    }
}

/** One option in the layout picker: an arrangement and how many tiles it shows. */
internal data class LayoutChoice(val layout: MultiviewLayout, val tileCount: Int)

internal object MultiviewLayoutChoices {
    /**
     * Every arrangement that holds at least the tiles on screen, up to four: the same count (a plain
     * re-arrange) or more (the new slots arrive empty with an "Add channel" tile). Never fewer:
     * taking a tile away is "Remove", which keeps the others playing.
     */
    fun options(currentCount: Int): List<LayoutChoice> {
        val now = currentCount.coerceIn(1, MultiviewLayoutPolicy.MAX_TILES)
        return MultiviewLayout.entries.flatMap { layout ->
            (layout.minTiles..layout.maxTiles).filter { it >= now }.map { LayoutChoice(layout, it) }
        }
    }
}

/** Which tiles close while one is full screen (owner decision 2: Android TV frees their memory). */
internal object MultiviewFullscreenPolicy {
    fun hiddenSlots(paneCount: Int, fullscreenSlot: Int): List<Int> =
        (0 until paneCount).filter { it != fullscreenSlot }
}

/** What is remembered of the grid between visits: the layout and the channel keys. No stream links. */
internal data class SavedGrid(val layout: MultiviewLayout, val keys: List<String?>)

internal interface MultiviewGridStore {
    suspend fun load(): SavedGrid?
    suspend fun save(grid: SavedGrid?)
}

internal object MultiviewGridCodec {
    private const val VERSION = "v1"

    fun encode(grid: SavedGrid): String =
        (listOf(VERSION, grid.layout.name) + grid.keys.map { it?.let(::escape).orEmpty() }).joinToString(";")

    /** Null for anything that is not a well-formed v1 grid: a bad record is "nothing to resume", never a crash. */
    fun decode(raw: String?): SavedGrid? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(';')
        if (parts.size < 3 || parts[0] != VERSION) return null
        val layout = MultiviewLayout.entries.firstOrNull { it.name == parts[1] } ?: return null
        val keys = parts.drop(2).take(MultiviewLayoutPolicy.MAX_TILES).map { part ->
            part.takeIf { it.isNotEmpty() }?.let(::unescape)
        }
        if (keys.any { it != null && it.isBlank() }) return null
        return SavedGrid(MultiviewLayoutPolicy.effective(layout, keys.size), keys)
    }

    // Keys are channel ids that may hold ';', ',' or '%'. Only these three need escaping.
    private fun escape(key: String): String =
        key.replace("%", "%25").replace(";", "%3B")

    private fun unescape(part: String): String =
        part.replace("%3B", ";").replace("%25", "%")
}

internal enum class TileMenuItem {
    FULL_SCREEN,
    BACK_TO_GRID,
    OPEN_IN_PLAYER,
    RETRY,
    KEEP_SOUND,
    FOLLOW_FOCUS,
    ADD_CHANNEL,
    REPLACE_CHANNEL,
    REMOVE,
    CHANGE_LAYOUT,
    EXIT,
}

/** What the tile menu offers for a tile, in order. The first item is the one that matters most for that state. */
internal object MultiviewTileMenuPolicy {
    fun items(
        status: TileStatus,
        failure: TileFailure?,
        pinnedHere: Boolean,
        paneCount: Int,
        fullscreen: Boolean,
    ): List<TileMenuItem> {
        val retry = status == TileStatus.FAILED && failure?.actions?.contains(TileAction.RETRY) == true
        if (fullscreen) {
            return buildList {
                add(TileMenuItem.BACK_TO_GRID)
                if (retry) add(TileMenuItem.RETRY)
                add(TileMenuItem.EXIT)
            }
        }
        return when (status) {
            TileStatus.EMPTY -> buildList {
                add(TileMenuItem.ADD_CHANNEL)
                if (paneCount > 1) add(TileMenuItem.REMOVE)
                add(TileMenuItem.CHANGE_LAYOUT)
                add(TileMenuItem.EXIT)
            }
            TileStatus.FAILED -> buildList {
                // A stream that is gone: the same link cannot play, so the way out is another channel (or none).
                val gone = failure?.actions?.contains(TileAction.REPLACE) == true
                if (gone) {
                    add(TileMenuItem.REPLACE_CHANNEL)
                    add(TileMenuItem.REMOVE)
                    add(TileMenuItem.CHANGE_LAYOUT)
                    add(TileMenuItem.EXIT)
                    return@buildList
                }
                if (retry) add(TileMenuItem.RETRY)
                add(TileMenuItem.OPEN_IN_PLAYER)
                add(TileMenuItem.REPLACE_CHANNEL)
                add(TileMenuItem.REMOVE)
                add(TileMenuItem.CHANGE_LAYOUT)
                add(TileMenuItem.EXIT)
            }
            TileStatus.LOADING, TileStatus.PLAYING -> listOf(
                TileMenuItem.FULL_SCREEN,
                if (pinnedHere) TileMenuItem.FOLLOW_FOCUS else TileMenuItem.KEEP_SOUND,
                TileMenuItem.REPLACE_CHANNEL,
                TileMenuItem.REMOVE,
                TileMenuItem.CHANGE_LAYOUT,
                TileMenuItem.EXIT,
            )
        }
    }
}
