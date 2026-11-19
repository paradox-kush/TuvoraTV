package com.nuvio.tv.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFormatLadderPolicyTest {

    private val xtreamM3u8 = "http://panel.example:8080/live/user/pass/104.m3u8"
    private val plainM3u8 = "http://cdn.example/stream/channel.m3u8"
    private val extensionless = "http://cdn.example/stream/channel"

    private fun failure(code: FailureCode, domain: FailureDomain) = PlaybackFailure(
        code = code,
        domain = domain,
        phase = FailurePhase.ENGINE_START,
        retryability = Retryability.HANDOFF_ELIGIBLE,
    )

    private val manifestInvalid = failure(FailureCode.MANIFEST_INVALID, FailureDomain.MANIFEST)
    private val demuxFailed = failure(FailureCode.DEMUX_FAILED, FailureDomain.DEMUX)

    private fun hls(provenance: EvidenceProvenance = EvidenceProvenance.URL_INFERRED) =
        EvidenceFact(DeliveryType.HLS, provenance)

    private fun input(
        failure: PlaybackFailure = manifestInvalid,
        delivery: EvidenceFact<DeliveryType>? = hls(),
        url: String = xtreamM3u8,
        contentType: ContentType = ContentType.LIVE,
        engine: EngineType = EngineType.MEDIA3,
        singleUse: Boolean = false,
        tried: Set<LiveFormatStep> = emptySet(),
    ) = LiveFormatLadderPolicy.Input(contentType, engine, failure, delivery, url, singleUse, tried)

    private fun next(input: LiveFormatLadderPolicy.Input) = LiveFormatLadderPolicy.next(input)

    // F4: an Xtream channel whose .m3u8 answers 200 text/html while the .ts plays.
    @Test
    fun `F4 an Xtream m3u8 that is not a playlist tries the sibling ts first`() {
        assertEquals(LiveFormatStep.SIBLING_TS_URL, next(input()))
    }

    // F1/F2: an .m3u8 URL that is raw MPEG-TS; no Xtream shape, so the same URL is re-opened as TS.
    @Test
    fun `F1 a plain m3u8 that is raw TS is re-opened as TS on the same URL`() {
        assertEquals(LiveFormatStep.FORCE_RAW_TS, next(input(url = plainM3u8)))
    }

    @Test
    fun `an Xtream m3u8 whose sibling was already tried still gets the forced TS re-open`() {
        assertEquals(
            LiveFormatStep.FORCE_RAW_TS,
            next(input(tried = setOf(LiveFormatStep.SIBLING_TS_URL))),
        )
    }

    // F3: an extension-less URL that is HLS: Media3 sniffs it as progressive and fails to demux.
    @Test
    fun `F3 an undeclared stream that fails to demux is re-opened as HLS`() {
        assertEquals(LiveFormatStep.FORCE_HLS, next(input(failure = demuxFailed, delivery = null, url = extensionless)))
    }

    @Test
    fun `a delivery guessed from the URL counts as undeclared`() {
        val guessedTs = EvidenceFact(DeliveryType.RAW_TRANSPORT_STREAM, EvidenceProvenance.URL_INFERRED)
        assertEquals(
            LiveFormatStep.FORCE_HLS,
            next(input(failure = demuxFailed, delivery = guessedTs, url = "http://x.example/a.ts")),
        )
    }

    @Test
    fun `a provider declared delivery is never second guessed on a demux failure`() {
        val declared = EvidenceFact(DeliveryType.RAW_TRANSPORT_STREAM, EvidenceProvenance.PROVIDER_DECLARED)
        assertNull(next(input(failure = demuxFailed, delivery = declared)))
    }

    @Test
    fun `each step runs at most once per channel`() {
        LiveFormatStep.entries.forEach { step ->
            val spent = LiveFormatLadderPolicy.spentBy(step)
            assertTrue("$step manifest", next(input(tried = spent)) !in spent)
            assertTrue(
                "$step demux",
                next(input(failure = demuxFailed, delivery = null, tried = spent)) !in spent,
            )
        }
        // Every manifest step spent: nothing left, the caller falls through to the hand-off.
        assertNull(next(input(tried = setOf(LiveFormatStep.SIBLING_TS_URL, LiveFormatStep.FORCE_RAW_TS))))
        assertNull(next(input(failure = demuxFailed, delivery = null, tried = setOf(LiveFormatStep.FORCE_HLS))))
    }

    @Test
    fun `taking the sibling ts step also spends the forced TS step`() {
        assertEquals(
            setOf(LiveFormatStep.SIBLING_TS_URL, LiveFormatStep.FORCE_RAW_TS),
            LiveFormatLadderPolicy.spentBy(LiveFormatStep.SIBLING_TS_URL),
        )
        assertEquals(setOf(LiveFormatStep.FORCE_HLS), LiveFormatLadderPolicy.spentBy(LiveFormatStep.FORCE_HLS))
    }

    @Test
    fun `a single-use link is excluded from the ladder`() {
        assertNull(next(input(singleUse = true)))
        assertNull(next(input(failure = demuxFailed, delivery = null, singleUse = true)))
    }

    @Test
    fun `catch-up and VOD are untouched`() {
        assertNull(next(input(contentType = ContentType.CATCH_UP)))
        assertNull(next(input(contentType = ContentType.VOD)))
        assertNull(next(input(contentType = ContentType.VOD, failure = demuxFailed, delivery = null)))
    }

    @Test
    fun `only Media3 is re-opened, libmpv keeps its own sniffing`() {
        assertNull(next(input(engine = EngineType.LIBMPV)))
    }

    @Test
    fun `other failures fall through to the existing hand-off`() {
        val decoder = failure(FailureCode.VIDEO_DECODER_FAILED, FailureDomain.VIDEO_DECODER)
        val network = failure(FailureCode.NETWORK_TIMEOUT, FailureDomain.NETWORK)
        assertNull(next(input(failure = decoder)))
        assertNull(next(input(failure = network)))
        assertNull(next(input(failure = manifestInvalid.copy(retryability = Retryability.RETRYABLE_IN_PLACE))))
        // A manifest failure on a stream that never claimed to be HLS is not ours to re-open.
        assertNull(next(input(delivery = null)))
    }

    @Test
    fun `sibling url only for the Xtream live shape`() {
        assertEquals(
            "http://panel.example:8080/live/user/pass/104.ts",
            LiveFormatLadderPolicy.siblingTsUrl(xtreamM3u8),
        )
        assertEquals(
            "https://panel.example/user/pass/7.ts",
            LiveFormatLadderPolicy.siblingTsUrl("https://panel.example/user/pass/7.m3u8"),
        )
        assertNull(LiveFormatLadderPolicy.siblingTsUrl(plainM3u8))
        assertNull(LiveFormatLadderPolicy.siblingTsUrl("http://panel.example/live/user/pass/104.m3u8?token=1"))
        assertNull(LiveFormatLadderPolicy.siblingTsUrl("http://panel.example/live/user/pass/abc.m3u8"))
    }

    @Test
    fun `forced evidence carries a source type and is not URL guessed`() {
        val ts = LiveFormatLadderPolicy.evidenceFor(LiveFormatStep.FORCE_RAW_TS, StreamEvidence(delivery = hls()))
        assertEquals(DeliveryType.RAW_TRANSPORT_STREAM, ts.delivery?.value)
        assertEquals(ContainerType.MPEG_TS, ts.container?.value)
        assertTrue(ts.delivery?.provenance !in setOf(EvidenceProvenance.URL_INFERRED, EvidenceProvenance.UNKNOWN))
        val h = LiveFormatLadderPolicy.evidenceFor(LiveFormatStep.FORCE_HLS, StreamEvidence())
        assertEquals(DeliveryType.HLS, h.delivery?.value)
        assertNull(h.container)
    }
}
