package com.nuvio.tv.core.sources

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Explicit setup may validate one manifest. Catalogs, streams and automatic refresh stay gated. */
class AddonInstallationRequest(private val addonUrl: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AddonInstallationRequest>
    fun permitsManifest(url: String): Boolean =
        url.substringBefore('#').substringBefore('?').trimEnd('/').endsWith("/manifest.json", ignoreCase = true) &&
            DeviceSourcePolicy.addonReference(url) == DeviceSourcePolicy.addonReference(addonUrl)
}
