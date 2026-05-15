package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.Stream

object DirectDebridStreamFilter {
    const val FALLBACK_SOURCE_NAME = "Direct Debrid"

    fun filterInstant(streams: List<Stream>): List<Stream> {
        return streams
            .filter { isInstantCandidate(it) }
            .map { stream ->
                val sourceName = sourceName(stream)
                stream.copy(
                    name = stream.name ?: sourceName,
                    addonName = sourceName,
                    addonLogo = null
                )
            }
            .distinctBy { stream ->
                listOf(
                    stream.clientResolve?.infoHash?.lowercase(),
                    stream.clientResolve?.fileIdx?.toString(),
                    stream.clientResolve?.filename,
                    stream.name,
                    stream.title
                ).joinToString("|")
            }
    }

    fun isInstantCandidate(stream: Stream): Boolean {
        val resolve = stream.clientResolve ?: return false
        return resolve.type.equals("debrid", ignoreCase = true) &&
            DebridProviders.isSupported(resolve.service) &&
            resolve.isCached == true
    }

    fun isDirectDebridSourceName(addonName: String): Boolean {
        return DebridProviders.all().any { addonName == DebridProviders.instantName(it.id) }
    }

    private fun sourceName(stream: Stream): String {
        return DebridProviders.instantName(stream.clientResolve?.service)
    }
}
