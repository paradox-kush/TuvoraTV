package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.epg.XmltvClient
import com.nuvio.tv.core.iptv.match.XtreamMatchIndex
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

/**
 * The derived Xtream guide URL — the one line that turns the whole-guide lane on for Xtream.
 *
 * Pinned by a test rather than by a live panel because getting it wrong is silent: a malformed URL
 * 404s once, the ladder falls through to the per-channel ask, and the app looks exactly as it did
 * before while paying a request per channel forever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class XmltvDerivedSourceTest {

    private val app = RuntimeEnvironment.getApplication()
    private val xmltv = XmltvClient(
        IptvContentDb(app),
        OkHttpClient(),
        com.nuvio.tv.core.iptv.dns.PlaylistDns(),
        XtreamMatchIndex(app),
    )

    private fun acct(
        base: String = "http://panel.example:8080",
        user: String = "u",
        pass: String = "p",
        type: String = XtreamAccount.SOURCE_XTREAM,
    ) = XtreamAccount(id = "a", name = "a", baseUrl = base, username = user, password = pass, sourceType = type)

    @Test
    fun `an xtream account derives its own xmltv url`() {
        assertEquals(
            "http://panel.example:8080/xmltv.php?username=u&password=p",
            xmltv.derivedXmltvUrl(acct()),
        )
    }

    @Test
    fun `a trailing slash does not double up`() {
        assertEquals(
            "http://panel.example:8080/xmltv.php?username=u&password=p",
            xmltv.derivedXmltvUrl(acct(base = "http://panel.example:8080/")),
        )
    }

    @Test
    fun `credentials with query-breaking characters are encoded`() {
        // Panels really do issue these; an unencoded & truncates the password server-side.
        assertEquals(
            "http://panel.example:8080/xmltv.php?username=a%20b&password=p%26q%2Br",
            xmltv.derivedXmltvUrl(acct(user = "a b", pass = "p&q+r")),
        )
    }

    @Test
    fun `non-xtream sources derive nothing`() {
        assertNull(xmltv.derivedXmltvUrl(acct(type = XtreamAccount.SOURCE_URL)))
        assertNull(xmltv.derivedXmltvUrl(acct(type = XtreamAccount.SOURCE_FILE)))
    }

    @Test
    fun `an account missing credentials derives nothing`() {
        assertNull(xmltv.derivedXmltvUrl(acct(user = "")))
        assertNull(xmltv.derivedXmltvUrl(acct(pass = "")))
        assertNull(xmltv.derivedXmltvUrl(acct(base = "")))
    }
}
