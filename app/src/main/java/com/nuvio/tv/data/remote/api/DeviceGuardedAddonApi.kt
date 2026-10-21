package com.nuvio.tv.data.remote.api

import com.nuvio.tv.core.sources.DeviceSources
import kotlinx.coroutines.CancellationException

/** One boundary for manifest, catalog, metadata, stream and subtitle discovery. */
class DeviceGuardedAddonApi(
    private val delegate: AddonApi,
    private val allowed: (String) -> Boolean = { DeviceSources.addonAllowed(it) },
    private val sourceEpoch: () -> Long = { DeviceSources.epoch },
) : AddonApi {
    private suspend fun <T> guarded(url: String, installing: Boolean = false, action: suspend () -> T): T {
        val installation = installing && kotlinx.coroutines.currentCoroutineContext()[com.nuvio.tv.core.sources.AddonInstallationRequest]?.permitsManifest(url) == true
        if (!installation && !allowed(url)) throw CancellationException("Source disabled on this device")
        val epoch = sourceEpoch()
        return action().also {
            if (!installation && !allowed(url)) throw CancellationException("Source disabled on this device")
            if (epoch != sourceEpoch()) throw CancellationException("Device sources changed")
        }
    }
    override suspend fun getManifest(manifestUrl: String) = guarded(manifestUrl, installing = true) { delegate.getManifest(manifestUrl) }
    override suspend fun getCatalog(catalogUrl: String) = guarded(catalogUrl) { delegate.getCatalog(catalogUrl) }
    override suspend fun getMeta(metaUrl: String) = guarded(metaUrl) { delegate.getMeta(metaUrl) }
    override suspend fun getStreams(streamUrl: String) = guarded(streamUrl) { delegate.getStreams(streamUrl) }
    override suspend fun getSubtitles(subtitleUrl: String) = guarded(subtitleUrl) { delegate.getSubtitles(subtitleUrl) }
}
