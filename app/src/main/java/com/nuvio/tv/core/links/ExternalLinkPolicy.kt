package com.nuvio.tv.core.links

/**
 * Decides what happens when the TV is asked to open a web link.
 *
 * Many Android TV / Fire TV devices ship no browser, and they fail in two different ways:
 * some throw `ActivityNotFoundException` from `startActivity(ACTION_VIEW)`, others (e.g. the stock
 * Android TV image) swallow the intent and show a system "You don't have an app that can do this"
 * toast — a dead end the app never hears about. So the decision is made BEFORE launching:
 * [canResolve] is whether any real activity resolves the VIEW intent ([canOpen] — Android TV's
 * stub browser resolves too, but only shows that same toast). The manifest declares a
 * `<queries>` VIEW/BROWSABLE/https entry so Android 11+ package visibility can't hide browsers.
 * If nothing resolves, show the QR hand-off without launching. If something resolves, launch, and
 * still treat any launch refusal ([RuntimeException]: ActivityNotFound, SecurityException) as QR.
 */
object ExternalLinkPolicy {
    sealed interface Outcome {
        data object Opened : Outcome
        data class ShowQr(val url: String) : Outcome
        /** The link is not one the TV will open or hand off (scheme, host shape): nothing is launched. */
        data object Refused : Outcome
    }

    /**
     * The allow-list: `https` and `mailto` only. An https host must be a registered-looking name (two or more
     * labels, never an IP literal, no credentials, no whitespace); a mailto must be a bare address with no
     * `?`-header (cc/subject/body). Nothing else (http, intent:, javascript:, file:, custom schemes) is opened.
     */
    fun isAllowed(url: String): Boolean = safeHttpsUrl(url) != null || safeMailto(url) != null

    private val EMAIL = Regex("^[A-Za-z0-9.!#$'*+/=_~-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")

    private fun safeMailto(url: String): String? {
        if (!url.startsWith("mailto:", ignoreCase = true)) return null
        val addr = url.substring(7)
        return url.takeIf { addr.length <= 254 && EMAIL.matches(addr) }
    }

    /**
     * [url] with an IDN host converted to its punycode form (so a look-alike domain is shown for what it is),
     * or null when it is not an acceptable https address.
     */
    fun safeHttpsUrl(url: String): String? {
        val s = url.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) return null
        if (!s.startsWith("https://", ignoreCase = true)) return null
        val rest = s.substring(8)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in authority || authority.isEmpty()) return null
        val hostPort = authority
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', "")
        if (hostPort.contains(':') && (port.isEmpty() || port.any { it !in '0'..'9' })) return null
        if (host.startsWith('[')) return null // IPv6 literal
        val ascii = runCatching { java.net.IDN.toASCII(host, java.net.IDN.ALLOW_UNASSIGNED) }.getOrNull() ?: return null
        if (!isRegisteredName(ascii)) return null
        val tail = rest.substring(authority.length)
        return "https://" + ascii.lowercase() + (if (port.isNotEmpty()) ":$port" else "") + tail
    }

    private fun isRegisteredName(host: String): Boolean {
        if (host.length > 253) return false
        val labels = host.split('.')
        if (labels.size < 2) return false // a single-label host (intranet name, localhost)
        if (labels.any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') ||
                it.any { c -> !(c.isLetterOrDigit() && c.code < 128) && c != '-' } }) return false
        // The TLD must not be all digits: that is an IP literal (1.2.3.4, 0x7f.1, 2130706433.x).
        if (labels.last().all { it.isDigit() }) return false
        return true
    }

    /** The host as shown to the person: lower-case, punycode for an IDN, no port. */
    fun displayHost(url: String): String? = safeHttpsUrl(url)?.removePrefix("https://")?.substringBefore('/')
        ?.substringBefore('?')?.substringBefore('#')?.substringBefore(':')

    /**
     * Packages that resolve VIEW https but are not browsers. Android TV ships
     * `com.android.tv.frameworkpackagestubs` whose `Stubs$BrowserStub` only toasts
     * "You don't have an app that can do this", so resolving to it alone is a dead end.
     */
    val KNOWN_STUB_BROWSER_PACKAGES: Set<String> = setOf("com.android.tv.frameworkpackagestubs")

    /** True when at least one resolver of the VIEW intent is a real app, not a known stub. */
    fun canOpen(resolvedPackages: List<String>): Boolean =
        resolvedPackages.any { it !in KNOWN_STUB_BROWSER_PACKAGES }

    fun open(url: String, canResolve: Boolean, launch: (String) -> Unit): Outcome {
        if (!isAllowed(url)) return Outcome.Refused
        if (!canResolve) return Outcome.ShowQr(url)
        return try {
            launch(url)
            Outcome.Opened
        } catch (_: RuntimeException) {
            Outcome.ShowQr(url)
        }
    }
}
