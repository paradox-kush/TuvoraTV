package com.nuvio.tv.core.iptv

/**
 * How a channel search matches and orders names (F01: search in the Live TV guide, and the live hits
 * in global search). Pure.
 *
 * Provider names are noisy ("UK: BBC ONE FHD", "UK | BBC-ONE"), so matching is by words: every word of
 * the query must appear in the name, in any order, ignoring case and punctuation. Names that start
 * with the query come first, then names with a word starting with it, then the rest, each group in
 * playlist order.
 *
 * TV twin of NuvioMobile's `features/iptv/IptvChannelSearchPolicy`.
 */
object IptvChannelSearchPolicy {

    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var space = true
        for (ch in text.lowercase()) {
            if (ch.isLetterOrDigit()) {
                out.append(ch); space = false
            } else if (!space) {
                out.append(' '); space = true
            }
        }
        return out.toString().trimEnd()
    }

    private fun words(query: String): List<String> = normalize(query).split(' ').filter { it.isNotEmpty() }

    fun matches(query: String, name: String): Boolean {
        val words = words(query)
        if (words.isEmpty()) return false
        val n = normalize(name)
        return words.all { it in n }
    }

    fun <T> search(items: List<T>, query: String, nameOf: (T) -> String): List<T> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        val phrase = words.joinToString(" ")
        val first = words.first()
        return items
            .mapNotNull { item ->
                val n = normalize(nameOf(item))
                if (!words.all { it in n }) return@mapNotNull null
                val rank = when {
                    n.startsWith(phrase) -> 0
                    n.split(' ').any { it.startsWith(first) } -> 1
                    else -> 2
                }
                rank to item
            }
            .sortedBy { it.first }
            .map { it.second }
    }
}
