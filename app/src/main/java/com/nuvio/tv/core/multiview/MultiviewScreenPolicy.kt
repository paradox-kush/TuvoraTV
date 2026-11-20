package com.nuvio.tv.core.multiview

/** What Back does on the multiview screen, innermost thing first. Pure, so the order is tested. */
internal enum class MultiviewBackAction {
    CLOSE_PICKER,

    /** The layout panel was opened from the tile menu, so Back returns there. */
    LAYOUT_BACK_TO_MENU,
    CLOSE_TILE_MENU,
    EXIT_FULLSCREEN,

    /** Leave the grid, through [MultiviewLeaveGuard]. */
    LEAVE_GRID,
}

internal object MultiviewBackPolicy {
    fun action(
        channelPickerOpen: Boolean,
        layoutPickerOpen: Boolean,
        tileMenuOpen: Boolean,
        fullscreen: Boolean,
    ): MultiviewBackAction = when {
        channelPickerOpen -> MultiviewBackAction.CLOSE_PICKER
        layoutPickerOpen -> MultiviewBackAction.LAYOUT_BACK_TO_MENU
        tileMenuOpen -> MultiviewBackAction.CLOSE_TILE_MENU
        fullscreen -> MultiviewBackAction.EXIT_FULLSCREEN
        else -> MultiviewBackAction.LEAVE_GRID
    }
}

/**
 * Leaving a grid with two or more channels takes two Backs within [windowMs]: the first only arms, so one press too
 * many cannot tear down up to four streams. With fewer than two channels there is nothing to lose and Back leaves at
 * once. Moving the highlight [disarm]s it.
 */
internal class MultiviewLeaveGuard(private val windowMs: Long = WINDOW_MS) {
    enum class Result { LEAVE, ARMED }

    private var armedAtMs: Long? = null

    fun onBack(nowMs: Long, channelsOnScreen: Int): Result {
        if (channelsOnScreen < MIN_CHANNELS_TO_GUARD) {
            armedAtMs = null
            return Result.LEAVE
        }
        val armed = armedAtMs
        if (armed != null && nowMs - armed <= windowMs) {
            armedAtMs = null
            return Result.LEAVE
        }
        armedAtMs = nowMs
        return Result.ARMED
    }

    fun disarm() {
        armedAtMs = null
    }

    companion object {
        const val WINDOW_MS = 3_500L
        const val MIN_CHANNELS_TO_GUARD = 2
    }
}

/**
 * The one-line notices the grid can show, top centre, never focusable. One slot: a new notice replaces the one
 * showing. [durationMs] is how long each stays.
 */
internal sealed interface MultiviewNotice {
    val durationMs: Long

    /** Once per session, after a tile was added and the box may struggle: "about [about] at full quality". */
    data class Capacity(val about: Int) : MultiviewNotice {
        override val durationMs: Long get() = 8_000L
    }

    /** A pick that failed at once: the failure card is on the tile and this names the channel and the reason. */
    data class PickFailed(val name: String, val reason: String) : MultiviewNotice {
        override val durationMs: Long get() = 4_000L
    }

    /** A pick refused before any tile exists (this profile may not watch it, or the key is not a playlist channel). */
    data class CannotAdd(val name: String) : MultiviewNotice {
        override val durationMs: Long get() = 4_000L
    }

    /** The picked channel is already on a tile: the highlight moved there. */
    data class AlreadyOnScreen(val name: String) : MultiviewNotice {
        override val durationMs: Long get() = 3_500L
    }

    data object LeaveGuard : MultiviewNotice {
        override val durationMs: Long get() = MultiviewLeaveGuard.WINDOW_MS
    }
}

/** Labels and the hint bar hide after this long without a key press; the ring and the sound badge stay. */
internal object MultiviewChromePolicy {
    const val HIDE_AFTER_MS = 5_000L
    const val FADE_MS = 150

    fun visible(sinceLastKeyMs: Long): Boolean = sinceLastKeyMs < HIDE_AFTER_MS
}

/** What a tile that is loading says. */
internal enum class TileLoadingPhase { TUNING_IN, STILL_TRYING, REJOINING }

internal object TileLoadingPolicy {
    /** The provider is slow to answer after this long with no picture; the stall deadline (20 s) comes later. */
    const val STILL_TRYING_AFTER_MS = 8_000L

    fun phase(elapsedMs: Long, rejoining: Boolean): TileLoadingPhase = when {
        rejoining -> TileLoadingPhase.REJOINING
        elapsedMs >= STILL_TRYING_AFTER_MS -> TileLoadingPhase.STILL_TRYING
        else -> TileLoadingPhase.TUNING_IN
    }
}

/** What the grid's hint bar names for the focused tile. */
internal data class GridHint(
    val kind: Kind,
    /** What OK does on a failed tile (its main action); null otherwise. */
    val failureOk: TileAction? = null,
    /** Name of the tile that keeps the sound, when the highlight is on another tile. */
    val soundKeptOn: String? = null,
) {
    enum class Kind { PLAYING, EMPTY, FAILED, FULLSCREEN }
}

internal object MultiviewHintPolicy {
    fun forTile(
        status: TileStatus,
        failure: TileFailure?,
        fullscreen: Boolean,
        soundKeptOnOtherTile: String?,
    ): GridHint = when {
        fullscreen -> GridHint(GridHint.Kind.FULLSCREEN)
        status == TileStatus.EMPTY -> GridHint(GridHint.Kind.EMPTY, soundKeptOn = soundKeptOnOtherTile)
        status == TileStatus.FAILED ->
            GridHint(GridHint.Kind.FAILED, failure?.primary, soundKeptOnOtherTile)
        else -> GridHint(GridHint.Kind.PLAYING, soundKeptOn = soundKeptOnOtherTile)
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

/** One option in the layout panel: an arrangement and how many tiles it shows. */
internal data class LayoutChoice(val layout: MultiviewLayout, val tileCount: Int)

internal object MultiviewLayoutChoices {
    /**
     * Exactly two choices, drawn for the tiles on screen: "Same size" then "One big". The layout only arranges the tiles
     * that are there; the number of tiles changes with Add / Remove, never from this panel.
     */
    fun options(currentCount: Int): List<LayoutChoice> {
        val now = currentCount.coerceIn(2, MultiviewLayoutPolicy.MAX_TILES)
        return MultiviewLayoutPolicy.Family.entries.map { LayoutChoice(MultiviewLayoutPolicy.layoutFor(it, now), now) }
    }
}

/** Which tiles close while one is full screen (owner decision 2: Android TV frees their memory). */
internal object MultiviewFullscreenPolicy {
    fun hiddenSlots(paneCount: Int, fullscreenSlot: Int): List<Int> =
        (0 until paneCount).filter { it != fullscreenSlot }
}

/**
 * What is remembered of the grid between visits: the layout, the channel keys and each channel's display name (so a
 * restored tile has its name before the playlist has loaded). No stream links, no programme titles, no logo URLs.
 */
internal data class SavedGrid(
    val layout: MultiviewLayout,
    val keys: List<String?>,
    /** One per slot; null where the name is unknown (and for a grid saved before names were kept). */
    val names: List<String?> = List(keys.size) { null },
)

internal interface MultiviewGridStore {
    suspend fun load(): SavedGrid?
    suspend fun save(grid: SavedGrid?)
}

internal object MultiviewGridCodec {
    private const val VERSION = "v2"
    private const val VERSION_1 = "v1"

    /** v2: `v2;LAYOUT;key,name;key,name;`. An empty slot is empty text; a slot without a name is just `key`. */
    fun encode(grid: SavedGrid): String {
        val slots = grid.keys.mapIndexed { i, key ->
            if (key == null) "" else {
                val name = grid.names.getOrNull(i)
                escape(key) + if (name.isNullOrBlank()) "" else "," + escape(name)
            }
        }
        return (listOf(VERSION, grid.layout.name) + slots).joinToString(";")
    }

    /** Null for anything that is not a well-formed v1 or v2 grid: a bad record is "nothing to resume", never a crash. */
    fun decode(raw: String?): SavedGrid? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(';')
        if (parts.size < 3 || (parts[0] != VERSION && parts[0] != VERSION_1)) return null
        val layout = MultiviewLayout.entries.firstOrNull { it.name == parts[1] } ?: return null
        val slots = parts.drop(2).take(MultiviewLayoutPolicy.MAX_TILES)
        val keys = ArrayList<String?>()
        val names = ArrayList<String?>()
        for (part in slots) {
            if (part.isEmpty()) { keys += null; names += null; continue }
            if (parts[0] == VERSION_1) { keys += unescape(part); names += null; continue }
            val comma = part.indexOf(',')
            keys += unescape(if (comma < 0) part else part.substring(0, comma))
            names += if (comma < 0) null else unescape(part.substring(comma + 1)).takeIf { it.isNotBlank() }
        }
        if (keys.any { it != null && it.isBlank() }) return null
        return SavedGrid(MultiviewLayoutPolicy.effective(layout, keys.size), keys, names)
    }

    // Keys are channel ids that may hold ';', ',' or '%'; names are free text. Only these three need escaping.
    private fun escape(text: String): String =
        text.replace("%", "%25").replace(";", "%3B").replace(",", "%2C")

    private fun unescape(part: String): String =
        part.replace("%2C", ",").replace("%3B", ";").replace("%25", "%")
}

internal enum class TileMenuItem(val inMultiviewGroup: Boolean = false) {
    BACK_TO_GRID,

    /** From a full-screen tile: leave Multiview for the normal player on this channel. */
    WATCH_IN_PLAYER,

    /** A failed tile's way out for a stream another engine may play. */
    OPEN_IN_PLAYER,
    RETRY,
    KEEP_SOUND,
    FOLLOW_FOCUS,

    /** Empty tile: choose a channel for it. */
    ADD_CHANNEL,
    REPLACE_CHANNEL,

    /** Take the tile away (three or four tiles); the others close up. */
    REMOVE,

    /** Empty the tile (a grid never has fewer than two slots). */
    CLEAR_CHANNEL,

    /** An empty tile that can be taken away (more than two tiles). */
    REMOVE_TILE,

    /** Append a slot and open the picker on it. */
    ADD_ANOTHER(inMultiviewGroup = true),
    CHANGE_LAYOUT(inMultiviewGroup = true),
    EXIT(inMultiviewGroup = true),
}

/**
 * What the tile menu offers for a tile, in order: the tile's own items, then the Multiview group. The first item is
 * the one that matters most for that state ("Keep sound here" on a playing tile, the failure's main action on a
 * failed one). OK already means full screen, so the menu has no "Full screen".
 */
internal object MultiviewTileMenuPolicy {
    fun items(
        status: TileStatus,
        failure: TileFailure?,
        pinnedHere: Boolean,
        paneCount: Int,
        fullscreen: Boolean,
    ): List<TileMenuItem> {
        val failed = status == TileStatus.FAILED && failure != null
        if (fullscreen) {
            return buildList {
                add(TileMenuItem.BACK_TO_GRID)
                if (failed && failure!!.actions.contains(TileAction.RETRY)) add(TileMenuItem.RETRY)
                add(TileMenuItem.WATCH_IN_PLAYER)
                add(TileMenuItem.EXIT)
            }
        }
        val remove = if (paneCount > MIN_SLOTS) TileMenuItem.REMOVE else TileMenuItem.CLEAR_CHANNEL
        return buildList {
            when {
                status == TileStatus.EMPTY -> {
                    add(TileMenuItem.ADD_CHANNEL)
                    if (paneCount > MIN_SLOTS) add(TileMenuItem.REMOVE_TILE)
                }
                failed -> failure!!.actions.forEach { action ->
                    add(
                        when (action) {
                            TileAction.RETRY -> TileMenuItem.RETRY
                            TileAction.REPLACE -> TileMenuItem.REPLACE_CHANNEL
                            TileAction.OPEN_IN_PLAYER -> TileMenuItem.OPEN_IN_PLAYER
                            TileAction.REMOVE -> remove
                        },
                    )
                }
                else -> {
                    add(if (pinnedHere) TileMenuItem.FOLLOW_FOCUS else TileMenuItem.KEEP_SOUND)
                    add(TileMenuItem.REPLACE_CHANNEL)
                    add(remove)
                }
            }
            if (paneCount < MultiviewLayoutPolicy.MAX_TILES) add(TileMenuItem.ADD_ANOTHER)
            add(TileMenuItem.CHANGE_LAYOUT)
            add(TileMenuItem.EXIT)
        }
    }

    /** A grid never has fewer slots than this: one channel is the player. */
    const val MIN_SLOTS = 2
}

/**
 * Whether the multiview screen holds the app's own screensaver off. Watching intent counts, as on the live
 * guide (memory: screensaver black-out was fixed by holding on intent, not on momentary playback): any tile
 * playing or still loading/reconnecting blocks it; a grid with nothing chosen, or only failed tiles, does not.
 */
internal object MultiviewAmbientPolicy {
    fun blocksScreensaver(statuses: List<TileStatus>): Boolean =
        statuses.any { it == TileStatus.PLAYING || it == TileStatus.LOADING }
}
