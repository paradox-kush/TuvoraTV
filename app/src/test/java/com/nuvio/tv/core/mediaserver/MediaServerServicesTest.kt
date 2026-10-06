package com.nuvio.tv.core.mediaserver

import com.nuvio.tv.core.mediaserver.api.MediaServerType
import com.nuvio.tv.core.mediaserver.client.AuthSession
import com.nuvio.tv.core.mediaserver.client.DiscoveryResult
import com.nuvio.tv.core.mediaserver.client.HealthStatus
import com.nuvio.tv.core.mediaserver.client.MediaServerException
import com.nuvio.tv.core.mediaserver.client.QuickConnectOutcome
import com.nuvio.tv.core.mediaserver.client.QuickConnectRequest
import com.nuvio.tv.core.mediaserver.policy.ServerUrlPolicy
import com.nuvio.tv.core.mediaserver.store.StoredCredential
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import com.nuvio.tv.core.mediaserver.assertIs
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class MediaServerServicesTest {
    private val publicInfo = """{"LocalAddress":"http://nas:8096","ServerName":"Living Room","Version":"12.1.0","ProductName":"Jellyfin Server","Id":"$M","StartupWizardCompleted":true}"""

    private fun jellyfinAt(vararg hosts: String) = FakeHttp { r ->
        if (hosts.any { r.url.startsWith(it) } && r.url.endsWith("/System/Info/Public")) json(publicInfo)
        else throw MediaServerException.Unreachable("refused")
    }

    @Test
    fun discoveryReturnsTheFirstAddressThatAnswersAsAMediaServer() = runTest {
        val rig = TestRig(http = jellyfinAt("http://192.168.1.5:8096"))
        val found = assertIs<DiscoveryResult.Found>(rig.services.discover("192.168.1.5", MediaServerType.JELLYFIN))
        assertEquals("http://192.168.1.5:8096", found.baseUrl)
        assertEquals(M, found.info.machineId)
        assertEquals("Living Room", found.info.name)
        assertEquals(MediaServerType.JELLYFIN, found.type)
        assertFalse(found.typeMismatch)
        assertTrue("only the anonymous probe is ever sent", rig.http.requests.all { it.url.endsWith("/System/Info/Public") })
        assertTrue("no token before sign-in", rig.http.requests.all { !it.headers["Authorization"].orEmpty().contains("Token=") })
    }

    @Test
    fun aPickedProductThatTheServerIsNotIsFlaggedNotSilentlySwitched() = runTest {
        val rig = TestRig(http = jellyfinAt("http://nas:8096"))
        val found = assertIs<DiscoveryResult.Found>(rig.services.discover("http://nas:8096", MediaServerType.EMBY))
        assertEquals("the server says what it is", MediaServerType.JELLYFIN, found.type)
        assertTrue(found.typeMismatch)
    }

    @Test
    fun anEmbyServerIsRecognisedByItsRemoteAddresses() = runTest {
        val rig = TestRig(http = FakeHttp { json("""{"LocalAddress":"http://nas:8096","ServerName":"E","Version":"4.9.1.0","Id":"$M","RemoteAddresses":[]}""") })
        val found = assertIs<DiscoveryResult.Found>(rig.services.discover("http://nas:8096", null))
        assertEquals(MediaServerType.EMBY, found.type)
    }

    @Test
    fun anAddressTheUserCannotMeanIsRejectedWithoutANetworkCall() = runTest {
        val rig = TestRig()
        assertEquals(DiscoveryResult.Rejected(ServerUrlPolicy.Reason.CREDENTIALS_IN_ADDRESS), rig.services.discover("http://kid:pw@nas", null))
        assertEquals(DiscoveryResult.Rejected(ServerUrlPolicy.Reason.UNSUPPORTED_SCHEME), rig.services.discover("ftp://nas", null))
        assertTrue(rig.http.requests.isEmpty())
    }

    @Test
    fun aNonMediaServerAnswerIsNotAMediaServerNotUnreachable() = runTest {
        val rig = TestRig(http = FakeHttp { json("<html>login</html>") })
        assertEquals(DiscoveryResult.NotAMediaServer, rig.services.discover("http://nas:8096", null))
    }

    @Test
    fun nothingListeningIsUnreachable() = runTest {
        val rig = TestRig(http = FakeHttp { throw MediaServerException.Unreachable("refused") })
        assertEquals(DiscoveryResult.Unreachable, rig.services.discover("nas.example.com", null))
        assertEquals("https, http, :8096 and :8920 were each tried", 4, rig.http.requests.size)
    }

    @Test
    fun anUntrustedCertificateIsSurfacedSoTheUserCanTrustIt() = runTest {
        val untrusted = MediaServerException.CertificateUntrusted("nas:8920", "SELF_SIGNED", "AB12")
        val rig = TestRig(http = FakeHttp { r -> if (r.url.startsWith("https://nas:8920")) throw untrusted else throw MediaServerException.Unreachable("no") })
        val r = assertIs<DiscoveryResult.CertificateNeedsTrust>(rig.services.discover("https://nas:8920", null))
        assertEquals("AB12", r.failure.fingerprint)
    }

    // --- health ---

    private fun signedIn(rig: TestRig) = entry().also { e ->
        rig.store.applyFromRemote(1, listOf(e))
        rig.credentials.save(e.serverKey, StoredCredential("TOKEN-1"))
    }

    @Test
    fun healthIsOnlineWhenTheAuthenticatedProbeAnswers() = runTest {
        val rig = TestRig(http = FakeHttp { json("""{"Id":"$U","Name":"kid"}""") })
        val e = signedIn(rig)
        assertEquals(HealthStatus.ONLINE, rig.services.health(e))
        val req = rig.http.requests.single()
        assertEquals("an AUTHENTICATED call - streams and images are anonymous and cannot reveal a revoked token", "http://nas:8096/Users/Me", req.url)
        assertTrue(req.headers.getValue("Authorization").contains("Token=\"TOKEN-1\""))
    }

    @Test
    fun aRevokedTokenDropsTheSessionAndShowsSignInAgain() = runTest {
        val rig = TestRig(http = FakeHttp { json("", status = 401) })
        val e = signedIn(rig)
        assertEquals(HealthStatus.AUTH_ERROR, rig.services.health(e))
        assertFalse(rig.services.isSignedIn(e))
        assertEquals(setOf(e.serverKey), rig.services.expiredSessions.value)
        assertEquals("the entry stays; only this device's session ends", 1, rig.store.current().size)
    }

    @Test
    fun aServerThatIsDownIsOfflineNotRevoked() = runTest {
        val rig = TestRig(http = FakeHttp { json("", status = 503, headers = mapOf("Retry-After" to "30")) })
        val e = signedIn(rig)
        assertEquals(HealthStatus.OFFLINE, rig.services.health(e))
        assertTrue("a 503 must never sign the user out", rig.services.isSignedIn(e))
        val rig2 = TestRig(http = FakeHttp { throw MediaServerException.Unreachable("down") })
        assertEquals(HealthStatus.OFFLINE, rig2.services.health(signedIn(rig2)))
    }

    @Test
    fun anEntryWithoutAnAddressOrASessionHasNoClient() {
        val rig = TestRig()
        val noAddress = entry(userId = "u9", address = null)
        rig.credentials.save(noAddress.serverKey, StoredCredential("t"))
        assertNull(rig.services.clientFor(noAddress))
        assertNull("signed out", rig.services.clientFor(entry()))
    }

    // --- Quick Connect ---

    private fun quickConnectServer(approveAfterPolls: Int, expireAtPoll: Int? = null): FakeHttp {
        var polls = 0
        return FakeHttp { r ->
            when {
                r.url.endsWith("/QuickConnect/Initiate") -> json("""{"Authenticated":false,"Secret":"SECRET-1","Code":"123456","DeviceId":"d","DeviceName":"n"}""")
                r.url.contains("/QuickConnect/Connect") -> {
                    polls++
                    if (expireAtPoll != null && polls >= expireAtPoll) json("", status = 404)
                    else json("""{"Authenticated":${polls >= approveAfterPolls},"Secret":"SECRET-1","Code":"123456"}""")
                }
                r.url.endsWith("/Users/AuthenticateWithQuickConnect") ->
                    json("""{"User":{"Name":"kid","ServerId":"$M","Id":"$U"},"ServerId":"$M","AccessToken":"TOKEN-QC"}""")
                else -> error("unexpected ${r.url}")
            }
        }
    }

    @Test
    fun quickConnectShowsACodeThenSignsInOnceApproved() = runTest {
        val rig = TestRig(http = quickConnectServer(approveAfterPolls = 3))
        var shown: QuickConnectRequest? = null
        val outcome = rig.services.signInWithQuickConnect("http://nas:8096", MediaServerType.JELLYFIN) { req, _ -> shown = req }
        assertEquals("123456", shown?.code)
        val ok = assertIs<QuickConnectOutcome.SignedIn>(outcome)
        assertEquals("TOKEN-QC", ok.session.accessToken)
        assertEquals(U, ok.session.userId)
        val init = rig.http.requests.first()
        assertEquals("POST", init.method)
        val h = init.headers.getValue("Authorization")
        assertTrue(h, h.startsWith("MediaBrowser Client=\"Tuvora\", Device=\"Test%20Device\", DeviceId=\""))
        assertFalse("initiation is tokenless", h.contains("Token="))
        assertEquals("""{"Secret":"SECRET-1"}""", rig.http.requests.last().body)
    }

    @Test
    fun anExpiredQuickConnectRequestAsksForANewCode() = runTest {
        val rig = TestRig(http = quickConnectServer(approveAfterPolls = 99, expireAtPoll = 2))
        assertEquals(QuickConnectOutcome.Expired, rig.services.signInWithQuickConnect("http://nas:8096", MediaServerType.JELLYFIN) { _, _ -> })
    }

    @Test
    fun theClockAloneExpiresTheCodeAfterTenMinutes() = runTest {
        val rig = TestRig(http = quickConnectServer(approveAfterPolls = 99))
        val outcome = rig.services.signInWithQuickConnect("http://nas:8096", MediaServerType.JELLYFIN) { _, _ -> rig.nowMs += 11 * 60_000L }
        assertEquals(QuickConnectOutcome.Expired, outcome)
    }

    @Test
    fun quickConnectOnEmbyFailsWithoutAnyRequest() = runTest {
        val rig = TestRig()
        assertEquals(QuickConnectOutcome.Failed, rig.services.signInWithQuickConnect("http://nas:8096", MediaServerType.EMBY) { _, _ -> })
        assertTrue(rig.http.requests.isEmpty())
    }

    @Test
    fun aFlakyNetworkDuringThePollIsToleratedAFewTimes() = runTest {
        var polls = 0
        val rig = TestRig(http = FakeHttp { r ->
            when {
                r.url.endsWith("/QuickConnect/Initiate") -> json("""{"Secret":"S","Code":"654321"}""")
                r.url.contains("/QuickConnect/Connect") -> if (++polls <= 3) throw MediaServerException.Unreachable("blip") else json("""{"Authenticated":true}""")
                else -> json("""{"User":{"Name":"kid","Id":"$U"},"AccessToken":"T"}""")
            }
        })
        assertIs<QuickConnectOutcome.SignedIn>(rig.services.signInWithQuickConnect("http://nas:8096", MediaServerType.JELLYFIN) { _, _ -> })
    }

    @Test
    fun aDurablyBrokenServerEndsTheQuickConnectAttempt() = runTest {
        val rig = TestRig(http = FakeHttp { r ->
            if (r.url.endsWith("/QuickConnect/Initiate")) json("""{"Secret":"S","Code":"654321"}""") else throw MediaServerException.Unreachable("down")
        })
        assertEquals(QuickConnectOutcome.Failed, rig.services.signInWithQuickConnect("http://nas:8096", MediaServerType.JELLYFIN) { _, _ -> })
    }

    @Test
    fun quickConnectEnabledIsAnonymousAndFalseOnAnyFailureAndForEmby() = runTest {
        val on = TestRig(http = FakeHttp { json("true") })
        assertTrue(on.services.authApi("http://nas:8096", MediaServerType.JELLYFIN).quickConnectEnabled())
        assertFalse(TestRig(http = FakeHttp { json("false") }).services.authApi("http://nas:8096", MediaServerType.JELLYFIN).quickConnectEnabled())
        assertFalse(TestRig(http = FakeHttp { json("", status = 404) }).services.authApi("http://nas:8096", MediaServerType.JELLYFIN).quickConnectEnabled())
        val emby = TestRig()
        assertFalse(emby.services.authApi("http://nas:8096", MediaServerType.EMBY).quickConnectEnabled())
        assertTrue(emby.http.requests.isEmpty())
    }

    @Test
    fun passwordSignInSendsTheCredentialsOnceAndMapsTheSession() = runTest {
        val rig = TestRig(http = FakeHttp { json("""{"User":{"Name":"kid","ServerId":"$M","Id":"$U","Policy":{"IsAdministrator":true}},"SessionInfo":{},"AccessToken":"TOKEN-PW","ServerId":"$M"}""") })
        val s: AuthSession = rig.services.authApi("http://nas:8096", MediaServerType.JELLYFIN).authenticateByName("kid", "hunter2")
        assertEquals(AuthSession("TOKEN-PW", U, "kid", M, true), s)
        val req = rig.http.requests.single()
        assertEquals("http://nas:8096/Users/AuthenticateByName", req.url)
        assertEquals("""{"Username":"kid","Pw":"hunter2"}""", req.body)
        assertFalse(req.headers.getValue("Authorization").contains("Token="))
        // the password was used once: nothing of it is in the secure store or the entry store
        assertTrue(rig.secure.items.values.none { it.contains("hunter2") } && rig.persistence.blobs.values.none { it.contains("hunter2") })
    }

    @Test
    fun aWrongPasswordIsAnUnauthorizedHttpErrorNotAGenericFailure() = runTest {
        val rig = TestRig(http = FakeHttp { json("", status = 401) })
        val e = kotlin.runCatching { rig.services.authApi("http://nas:8096", MediaServerType.EMBY).authenticateByName("kid", "wrong") }.exceptionOrNull()
        assertTrue((e as MediaServerException.Http).isUnauthorized)
    }

    @Test
    fun aSignInAnswerWithoutATokenIsMalformed() = runTest {
        val rig = TestRig(http = FakeHttp { json("""{"User":{"Id":"$U"}}""") })
        val e = kotlin.runCatching { rig.services.authApi("http://nas:8096", MediaServerType.JELLYFIN).authenticateByName("kid", "pw") }.exceptionOrNull()
        assertIs<MediaServerException.Malformed>(e)
    }

    @Test
    fun publicUsersFeedThePickerAndNeverThrow() = runTest {
        val rig = TestRig(http = FakeHttp { json("""[{"Name":"kid","Id":"$U"},{"Name":"","Id":"x"},{"Id":"y"}]""") })
        assertEquals(listOf("kid"), rig.services.authApi("http://nas:8096", MediaServerType.JELLYFIN).publicUsers().map { it.name })
        assertTrue(TestRig(http = FakeHttp { json("garbage") }).services.authApi("http://nas:8096", MediaServerType.JELLYFIN).publicUsers().isEmpty())
    }
}
