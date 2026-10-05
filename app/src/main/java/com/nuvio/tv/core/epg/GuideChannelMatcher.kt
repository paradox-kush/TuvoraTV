package com.nuvio.tv.core.epg

import com.nuvio.tv.core.iptv.epg.normalizeChannelId

/**
 * B10 — maps a playlist's lineup onto ONE whole-guide source's `<channel>` list, the way TiviMate
 * does and then some: the provider's id → the cleaned name.
 *
 * Before this existed the playlist's own guide (Xtream `xmltv.php`, an explicit EPG URL, the M3U
 * `url-tvg`) was joined by exact `epg_channel_id`/`tvg-id` ONLY; the `<channel>` display-names
 * were parsed and thrown away. A panel that leaves the id blank (94% of channels on a
 * Starshare-class panel, measured) therefore got nothing from its own guide, while TiviMate,
 * reading the same URL, fell back to names. This is that fallback, built on the measured tier
 * matcher ([EpgChannelIndex]) fed by the F10 normaliser ([ChannelNameCleaner]).
 *
 * Tiers, first hit wins (a user's manual pick — F14 — is NOT a tier here: it is per profile while
 * this map is per playlist, so it is applied at read time by [EpgGuideKeyPolicy]):
 *  1. ID     — the lineup id equals a guide id after [normalizeChannelId] folding, or after
 *              dropping an iptv-org style `@feed` suffix (`BBCOne.uk@SD`). Trusted as today: it
 *              is the provider's own assertion about its own guide.
 *  2. NAME   — [EpgChannelIndex] exact/tokens/squash/plural over every display-name the guide
 *              lists for a channel, cleaned on both sides. The "+1 must never map to its base"
 *              guard lives in the index.
 *  3. FUZZY  — similarity ≥ 0.87 within the same first token; only when [allowFuzzy] (off on
 *              the store lane: a wrong guide is worse than none, and misses pay the walk).
 *
 * Pure and synchronous — the caller runs it on the ingest's own background scope, once per guide
 * download (never per browse). Cost is one hash build over the guide plus one hash lookup chain
 * per lineup channel; see EpgMatchBenchmarkTest for the 50k-channel numbers.
 */
object GuideChannelMatcher {

    data class LineupChannel(
        val streamId: Int,
        val name: String,
        /** `epg_channel_id` / `tvg-id`; blank or null when the provider left it empty. */
        val epgId: String?,
    )

    /** One `<channel>`: its id and every `<display-name>` in document order. */
    data class GuideChannel(val id: String, val names: List<String>)

    enum class Tier(val slug: String) { ID("id"), NAME("name"), FUZZY("fuzzy") }

    /** [guideId] is normalized ([normalizeChannelId]) — the key programmes are stored under. */
    data class Assignment(val streamId: Int, val guideId: String, val tier: Tier)

    /**
     * Per-ingest coverage census (what the playlist screen shows and `epg_coverage` reports).
     * [eligible] excludes channels that by nature have no linear guide (24/7 loops, PPV, dated
     * event feeds, adult, separator rows) — the denominator the research measured against.
     */
    data class Census(
        val lineup: Int,
        val eligible: Int,
        val id: Int,
        val name: Int,
        val fuzzy: Int,
    ) {
        val matched: Int get() = id + name + fuzzy
        val unmatched: Int get() = lineup - matched
    }

    data class Result(val assignments: List<Assignment>, val census: Census)

    /** Match [lineup] against [guide]. */
    fun match(
        lineup: List<LineupChannel>,
        guide: List<GuideChannel>,
        rules: ChannelNameCleaner.Rules = ChannelNameCleaner.Rules.DEFAULT,
        allowFuzzy: Boolean = false,
    ): Result {
        if (lineup.isEmpty() || guide.isEmpty()) {
            return Result(emptyList(), Census(lineup.size, lineup.count { isEligible(it.name) }, 0, 0, 0))
        }
        val guideIds = HashSet<String>(guide.size * 2)
        for (g in guide) guideIds.add(normalizeChannelId(g.id))

        // The index is only built when something actually needs a name — a guide whose ids all
        // line up (the common good-panel case) never pays for it.
        var built: EpgChannelIndex? = null
        fun index(): EpgChannelIndex = built ?: EpgChannelIndex.build(
            guide.map { g -> g.id to cleanedNames(g.names, rules) },
        ).also { built = it }

        val out = ArrayList<Assignment>(lineup.size)
        var id = 0; var name = 0; var fuzzy = 0
        for (ch in lineup) {
            val byId = idHit(ch.epgId, guideIds)
            if (byId != null) {
                out.add(Assignment(ch.streamId, byId, Tier.ID)); id++
                continue
            }
            val hit = index().match(ch.name, ch.epgId, rules, allowFuzzy) ?: continue
            val tier = if (hit.tier == EpgChannelIndex.TIER_FUZZY) Tier.FUZZY else Tier.NAME
            out.add(Assignment(ch.streamId, normalizeChannelId(hit.epgId), tier))
            if (tier == Tier.FUZZY) fuzzy++ else name++
        }
        val census = Census(
            lineup = lineup.size,
            eligible = lineup.count { isEligible(it.name) },
            id = id, name = name, fuzzy = fuzzy,
        )
        return Result(out, census)
    }

    /** The guide id the provider's own id points at, or null. */
    internal fun idHit(epgId: String?, guideIds: Set<String>): String? {
        val key = normalizeChannelId(epgId ?: return null)
        if (key.isEmpty()) return null
        if (key in guideIds) return key
        // iptv-org style feed suffix: "bbcone.uk@sd" -> "bbcone.uk".
        val at = key.indexOf('@')
        if (at > 0) {
            val base = key.substring(0, at)
            if (base in guideIds) return base
        }
        return null
    }

    /**
     * Every display-name, plus its cleaned form when cleaning changes it. A panel-generated
     * `xmltv.php` often lists the panel's own decorated names (`UK: BBC ONE FHD ᴴᴰ`); a curated
     * feed lists clean ones. Indexing both keeps the clean name first (it wins a shared key).
     */
    private fun cleanedNames(names: List<String>, rules: ChannelNameCleaner.Rules): List<String> {
        if (names.isEmpty()) return names
        val out = ArrayList<String>(names.size * 2)
        for (n in names) {
            val c = ChannelNameCleaner.clean(EpgNorm.stripPanelNoise(n), rules)
            if (c.isNotBlank()) out.add(c)
        }
        for (n in names) if (n !in out) out.add(n)
        return out
    }

    /**
     * Channels that by nature have no linear guide — dated event feeds, PPV, 24/7 loops, adult,
     * and the `===== UK =====` separator rows panels insert. Port of the research harness's
     * NO_EPG_NATURE (research/epg-matching/epg_match.py) plus separators.
     */
    fun isEligible(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        if (SEPARATOR_ROW.containsMatchIn(n)) return false
        return !NO_EPG_NATURE.containsMatchIn(n)
    }

    private val NO_EPG_NATURE = Regex(
        "(24[/-]7|\\b24x7\\b|\\(\\s*\\d{4}-\\d{2}-\\d{2}|\\bppv\\b|box office|pay.?per.?view" +
            "|\\bxxx\\b|\\badults?\\b|\\bevent \\d|\\b(mls|nba|nfl|mlb|nhl|ufc) ?\\d{2,3}\\b)",
        RegexOption.IGNORE_CASE,
    )

    private val SEPARATOR_ROW = Regex("(={3,}|#{3,}|\\*{3,}|-{4,}|~{3,}|•{3,})")
}
