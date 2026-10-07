package com.nuvio.tv.core.mediaserver.policy

/**
 * Turns what a person typed into the addresses worth probing (design 5.4): http/https only, no credentials
 * in the address, reverse-proxy base paths kept (clients do not follow `BaseUrl` redirects), https tried
 * before http, the products' own ports (8096 http, 8920 Emby https) guessed for a bare host, IPv6
 * link-local dropped. Pure string work - no URL library - so the result is identical on every platform.
 */
internal object ServerUrlPolicy {
    enum class Reason { EMPTY, UNSUPPORTED_SCHEME, NO_HOST, INVALID_HOST, INVALID_PORT, CREDENTIALS_IN_ADDRESS, IPV6_LINK_LOCAL }

    sealed interface Outcome {
        /** [candidates] in probe order, canonical form (lowercase scheme/host, default port dropped, no trailing slash). */
        data class Ok(val candidates: List<String>) : Outcome
        data class Rejected(val reason: Reason) : Outcome
    }

    /** A parsed address: [path] has no trailing slash and no query/fragment (`""` for the root). */
    data class Parsed(val scheme: String, val host: String, val port: Int?, val path: String)

    private const val HTTP_PORT = 8096
    private const val EMBY_HTTPS_PORT = 8920
    /** What a pasted browser address adds on top of the server's base path (`.../web/index.html#!/home`). */
    private val strippedSuffixes = listOf("/web/index.html", "/index.html", "/web")
    private val hostChars = Regex("^[A-Za-z0-9._\\-]+$")

    fun normalize(input: String): Outcome {
        val raw = input.trim()
        if (raw.isEmpty()) return Outcome.Rejected(Reason.EMPTY)
        val schemeEnd = raw.indexOf("://")
        val explicitScheme: String?
        val rest: String
        if (schemeEnd >= 0) {
            val s = raw.substring(0, schemeEnd).lowercase()
            if (s != "http" && s != "https") return Outcome.Rejected(Reason.UNSUPPORTED_SCHEME)
            explicitScheme = s
            rest = raw.substring(schemeEnd + 3)
        } else {
            // "jellyfin:8096" / "mailto:x" style inputs have a colon but no "//": a scheme-less host:port is the only legal one
            explicitScheme = null
            rest = raw
        }
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        var path = rest.substring(authorityEnd).substringBefore('#').substringBefore('?')
        if ('@' in authority) return Outcome.Rejected(Reason.CREDENTIALS_IN_ADDRESS)

        val host: String
        val portText: String
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return Outcome.Rejected(Reason.INVALID_HOST)
            val inner = authority.substring(1, close)
            val tail = authority.substring(close + 1)
            if (tail.isNotEmpty() && !tail.startsWith(":")) return Outcome.Rejected(Reason.INVALID_HOST)
            if (inner.isEmpty() || !inner.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }) return Outcome.Rejected(Reason.INVALID_HOST)
            if (isIpv6LinkLocal(inner)) return Outcome.Rejected(Reason.IPV6_LINK_LOCAL)
            host = "[${inner.lowercase()}]"
            portText = tail.removePrefix(":")
        } else {
            val colon = authority.lastIndexOf(':')
            host = (if (colon < 0) authority else authority.substring(0, colon)).lowercase()
            portText = if (colon < 0) "" else authority.substring(colon + 1)
            if (host.isEmpty()) return Outcome.Rejected(Reason.NO_HOST)
            if (!hostChars.matches(host) || host.startsWith(".") || host.endsWith("..")) return Outcome.Rejected(Reason.INVALID_HOST)
        }
        val port: Int? = if (portText.isEmpty()) null else {
            if (portText.length > 5 || !portText.all { it in '0'..'9' }) return Outcome.Rejected(Reason.INVALID_PORT)
            portText.toInt().takeIf { it in 1..65535 } ?: return Outcome.Rejected(Reason.INVALID_PORT)
        }

        path = path.trimEnd('/')
        for (suffix in strippedSuffixes) {
            if (path.endsWith(suffix, ignoreCase = true)) {
                path = path.dropLast(suffix.length).trimEnd('/')
                break
            }
        }

        val candidates = linkedSetOf<String>()
        fun add(scheme: String, p: Int?) { build(Parsed(scheme, host, p, path))?.let(candidates::add) }
        when {
            explicitScheme != null && port != null -> add(explicitScheme, port)
            explicitScheme == "https" -> {
                add("https", null)
                if (isLocalHost(host)) add("https", EMBY_HTTPS_PORT)
            }
            explicitScheme == "http" -> {
                add("http", null)
                add("http", HTTP_PORT)
            }
            port != null -> {
                // 8096 is the http port; everything else tries https first
                if (port == HTTP_PORT) { add("http", port); add("https", port) } else { add("https", port); add("http", port) }
            }
            else -> {
                add("https", null)
                add("http", null)
                add("http", HTTP_PORT)
                add("https", EMBY_HTTPS_PORT)
            }
        }
        return Outcome.Ok(candidates.toList())
    }

    /** Canonical string of [p]: lowercase scheme/host, the scheme's default port dropped, no trailing slash. */
    fun build(p: Parsed): String? {
        val defaultPort = if (p.scheme == "https") 443 else 80
        val portSuffix = if (p.port == null || p.port == defaultPort) "" else ":${p.port}"
        return "${p.scheme}://${p.host}$portSuffix${p.path}"
    }

    /** [url] normalised, or null when it is not a usable http(s) address. Idempotent. */
    fun canonical(url: String): String? = parse(url)?.let(::build)

    fun parse(url: String): Parsed? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = url.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = url.substring(schemeEnd + 3)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        val path = rest.substring(authorityEnd).substringBefore('#').substringBefore('?').trimEnd('/')
        val host: String
        val port: Int?
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            host = authority.substring(0, close + 1).lowercase()
            port = authority.substring(close + 1).removePrefix(":").toIntOrNull()
        } else {
            val colon = authority.lastIndexOf(':')
            host = (if (colon < 0) authority else authority.substring(0, colon)).lowercase()
            port = if (colon < 0) null else authority.substring(colon + 1).toIntOrNull()
        }
        if (host.isEmpty()) return null
        return Parsed(scheme, host, port, path)
    }

    /** `host:port` (the scheme's default port filled in): the unit a pinned certificate is stored under. */
    fun hostAuthority(url: String): String? {
        val p = parse(url) ?: return null
        val port = p.port ?: if (p.scheme == "https") 443 else 80
        return "${p.host}:$port"
    }

    fun isInsecure(url: String): Boolean = parse(url)?.scheme == "http"

    /** True for a LAN / loopback / link-local / single-label / `.local`-style host - where plain http is expected and a self-signed certificate is normal. */
    fun isLocalHost(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (h.isEmpty()) return false
        if (h == "localhost" || h == "::1") return true
        if (h.startsWith("fc") || h.startsWith("fd")) {
            if (':' in h) return true // IPv6 unique-local fc00::/7
        }
        if (':' in h) return false
        val octets = h.split('.')
        if (octets.size == 4 && octets.all { it.isNotEmpty() && it.all(Char::isDigit) && it.length <= 3 }) {
            val n = octets.map { it.toInt() }
            if (n.any { it > 255 }) return false
            return n[0] == 10 || n[0] == 127 ||
                (n[0] == 172 && n[1] in 16..31) ||
                (n[0] == 192 && n[1] == 168) ||
                (n[0] == 169 && n[1] == 254)
        }
        if ('.' !in h) return true // a single-label name only resolves on the local network
        return listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain").any { h.endsWith(it) }
    }

    /** True when [host] is a literal IP address (certificates are rarely issued for those, so a hostname mismatch is expected). */
    fun isIpLiteral(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (':' in h) return true
        val octets = h.split('.')
        return octets.size == 4 && octets.all { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    private fun isIpv6LinkLocal(inner: String): Boolean {
        val addr = inner.substringBefore('%').lowercase()
        // fe80::/10 -> fe80 .. febf
        if (addr.length < 4 || !addr.startsWith("fe")) return false
        val third = addr[2]
        return third in "89ab"
    }
}
