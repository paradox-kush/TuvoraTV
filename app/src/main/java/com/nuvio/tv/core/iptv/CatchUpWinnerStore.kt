package com.nuvio.tv.core.iptv

/**
 * The persistent half of [CatchUpDialectWalk] — which URL shape has actually played for an account.
 *
 * ## The fork this resolves
 * The walk puts a remembered winner at the head of the ladder, which is right until the viewer
 * flips the per-playlist "prefer m3u8 (enables scrubbing)" preference. On an account that already
 * proved a TS dialect, the remembered TS winner would still lead — so the toggle would change
 * nothing at all on exactly the accounts that have been used the most. A setting that silently
 * does nothing is worse than no setting.
 *
 * So a winner is stamped with the preference it was proven under, and a different preference voids
 * it the same way a changed `allowed_output_formats` signature does. [forget] is the same fix from
 * the other end, for the settings screen to call.
 *
 * Only PROVEN winners are stored — never a fallback that merely happened to be last (iptvnator
 * persists its fallback here, which pins the wrong variant whenever a panel is briefly down).
 */
class CatchUpWinnerStore(private val persistence: Persistence) : CatchUpDialectWalk.WinnerMemory {

    /** Where records live between sessions. Injected so the policy stays testable off-device. */
    interface Persistence {
        fun load(): Map<String, String>
        fun save(entries: Map<String, String>)
    }

    private val entries: MutableMap<String, String> by lazy { persistence.load().toMutableMap() }

    /** The container preference each account's walk is currently running under. */
    private val preferences = HashMap<String, Boolean>()

    /**
     * Declares the preference in force for [accountId], and drops any proof that was made under a
     * different one. Called before every walk, so a preference that arrived from another device
     * voids a stale proof just as a local toggle does.
     */
    fun useAccountPreference(accountId: String, preferM3u8: Boolean) {
        val previous = preferences.put(accountId, preferM3u8)
        if (previous != null && previous != preferM3u8) forget(accountId)
        val stored = entries[accountId]?.let(::decode)
        if (stored != null && stored.preferM3u8 != preferM3u8) forget(accountId)
    }

    override fun recall(accountId: String): CatchUpDialectWalk.StoredWinner? {
        val record = entries[accountId]?.let(::decode) ?: return null
        val current = preferences[accountId] ?: return null
        if (record.preferM3u8 != current) return null
        return CatchUpDialectWalk.StoredWinner(record.formatsSignature, record.dialect)
    }

    override fun remember(accountId: String, winner: CatchUpDialectWalk.StoredWinner) {
        val preferM3u8 = preferences[accountId] ?: false
        entries[accountId] = encode(Record(preferM3u8, winner.formatsSignature, winner.dialect))
        persistence.save(entries)
    }

    /** Drops one account's proof — the playlist was removed, edited, or its preference changed. */
    fun forget(accountId: String) {
        if (entries.remove(accountId) != null) persistence.save(entries)
    }

    private data class Record(
        val preferM3u8: Boolean,
        val formatsSignature: String,
        val dialect: CatchUpDialectWalk.Dialect,
    )

    private fun encode(record: Record): String =
        "${record.preferM3u8}|${record.formatsSignature}|${record.dialect.name}"

    /**
     * Records outlive the enum. A dialect renamed or dropped in a later release, or a record written
     * by a version that stored something else entirely, has to read as "no winner" — never as a
     * crash on the first replay after an update.
     */
    private fun decode(raw: String): Record? {
        val parts = raw.split('|')
        if (parts.size != 3) return null
        val preferM3u8 = parts[0].toBooleanStrictOrNull() ?: return null
        val dialect = runCatching { CatchUpDialectWalk.Dialect.valueOf(parts[2]) }.getOrNull() ?: return null
        return Record(preferM3u8, parts[1], dialect)
    }
}
