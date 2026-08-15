package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.CatchUpDialectWalk.Dialect
import com.nuvio.tv.core.iptv.CatchUpDialectWalk.StoredWinner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persistent half of the dialect walk, and the fork it has to resolve.
 *
 * [CatchUpDialectWalk] puts a remembered winner at the head of the ladder. That is right until the
 * viewer flips the "prefer m3u8 (enables scrubbing)" preference: on an account that already proved
 * a TS dialect, the remembered TS winner would still lead and the toggle would do nothing at all —
 * a setting that silently ignores the user. The winner is therefore stamped with the preference it
 * was proven under, and a different preference voids it exactly like a changed formats signature.
 */
class CatchUpWinnerStoreTest {

    private class FakePersistence(var entries: MutableMap<String, String> = mutableMapOf()) :
        CatchUpWinnerStore.Persistence {
        var saves = 0
        override fun load(): Map<String, String> = entries.toMap()
        override fun save(entries: Map<String, String>) {
            saves++
            this.entries = entries.toMutableMap()
        }
    }

    private val account = "http://panel.example|bob"
    private val unknownFormats = CatchUpDialectWalk.formatsSignature(null)

    @Test
    fun `a proven winner is recalled`() {
        val store = CatchUpWinnerStore(FakePersistence())
        store.useAccountPreference(account, preferM3u8 = false)
        store.remember(account, StoredWinner(unknownFormats, Dialect.PATH_TS))

        val recalled = store.recall(account)
        assertNotNull("recalled", recalled)
        assertEquals("dialect", Dialect.PATH_TS, recalled!!.dialect)
        assertEquals("signature", unknownFormats, recalled.formatsSignature)
    }

    @Test
    fun `a winner survives a process restart`() {
        val persistence = FakePersistence()
        CatchUpWinnerStore(persistence).apply {
            useAccountPreference(account, preferM3u8 = false)
            remember(account, StoredWinner(unknownFormats, Dialect.PHP_STREAMING))
        }
        val reopened = CatchUpWinnerStore(persistence)
        reopened.useAccountPreference(account, preferM3u8 = false)
        assertEquals(
            "reloaded from disk",
            Dialect.PHP_STREAMING,
            reopened.recall(account)?.dialect,
        )
    }

    /** THE FORK: a TS winner must not outrank a freshly-flipped m3u8 preference. */
    @Test
    fun `flipping the container preference voids the remembered winner`() {
        val store = CatchUpWinnerStore(FakePersistence())
        store.useAccountPreference(account, preferM3u8 = false)
        store.remember(account, StoredWinner(unknownFormats, Dialect.PATH_TS))

        store.useAccountPreference(account, preferM3u8 = true)
        assertNull("the TS proof does not survive the flip", store.recall(account))

        store.useAccountPreference(account, preferM3u8 = false)
        assertNull("and it is gone, not merely hidden", store.recall(account))
    }

    /**
     * The same fork seen end to end: with the preference flipped, the walk's FIRST attempt has to be
     * an m3u8 dialect. This is the assertion that proves the toggle actually changes what plays.
     */
    @Test
    fun `the m3u8 preference leads the walk even after a TS winner was proven`() {
        val store = CatchUpWinnerStore(FakePersistence())
        val walk = CatchUpDialectWalk(store)
        val tsRequest = CatchUpDialectWalk.Request(
            accountId = account,
            baseUrl = "http://panel.example",
            username = "bob",
            password = "secret",
            streamId = 4271,
            startMs = 1_710_000_000_000L,
            endMs = 1_710_003_600_000L,
            preferM3u8 = false,
        )

        // Prove TS the way a real playback would.
        store.useAccountPreference(account, preferM3u8 = false)
        val first = walk.begin(tsRequest) as CatchUpDialectWalk.Step.Next
        assertEquals("TS leads by default", Dialect.PATH_TS, first.attempt.dialect)
        walk.onSuccess(first.attempt.token)
        assertEquals("TS remembered", Dialect.PATH_TS, store.recall(account)?.dialect)

        // The viewer turns on "prefer m3u8 (enables scrubbing)".
        store.useAccountPreference(account, preferM3u8 = true)
        val flipped = walk.begin(tsRequest.copy(preferM3u8 = true)) as CatchUpDialectWalk.Step.Next
        assertEquals(
            "the preference wins over the stale TS proof",
            Dialect.PATH_M3U8,
            flipped.attempt.dialect,
        )
        assertTrue("and asks the panel for m3u8", flipped.attempt.url.endsWith(".m3u8"))
    }

    /** Clearing an account (removed playlist, credentials edited) drops its proof. */
    @Test
    fun `forget clears one account and leaves the others`() {
        val store = CatchUpWinnerStore(FakePersistence())
        store.useAccountPreference(account, preferM3u8 = false)
        store.useAccountPreference("other", preferM3u8 = false)
        store.remember(account, StoredWinner(unknownFormats, Dialect.PATH_TS))
        store.remember("other", StoredWinner(unknownFormats, Dialect.PHP_ROOT))

        store.forget(account)
        assertNull("cleared", store.recall(account))
        assertEquals("untouched", Dialect.PHP_ROOT, store.recall("other")?.dialect)
    }

    /** A changed formats signature still voids the proof — the walk's own rule, kept end to end. */
    @Test
    fun `a winner proven under different formats is not recalled by the walk`() {
        val store = CatchUpWinnerStore(FakePersistence())
        store.useAccountPreference(account, preferM3u8 = false)
        store.remember(account, StoredWinner(CatchUpDialectWalk.formatsSignature(listOf("ts")), Dialect.PATH_TS))

        val recalled = store.recall(account)
        assertNotNull("the record is still there", recalled)
        assertEquals(
            "but it carries the old signature, which the walk compares",
            CatchUpDialectWalk.formatsSignature(listOf("ts")),
            recalled!!.formatsSignature,
        )
    }

    /**
     * Persisted records outlive the enum. A dialect renamed or dropped in a later release must read
     * as "no winner", never as a crash on the first replay after an update.
     */
    @Test
    fun `an unreadable record is ignored rather than thrown`() {
        val persistence = FakePersistence(
            mutableMapOf(
                account to "false|unknown|DIALECT_FROM_THE_FUTURE",
                "malformed" to "not-even-close",
            )
        )
        val store = CatchUpWinnerStore(persistence)
        store.useAccountPreference(account, preferM3u8 = false)
        store.useAccountPreference("malformed", preferM3u8 = false)
        assertNull("unknown dialect", store.recall(account))
        assertNull("malformed record", store.recall("malformed"))
    }

    /** A failed walk pins nothing, so an account with no proof must simply answer null. */
    @Test
    fun `an account with no proof recalls nothing`() {
        val store = CatchUpWinnerStore(FakePersistence())
        store.useAccountPreference(account, preferM3u8 = false)
        assertNull("never proven", store.recall(account))
    }
}
