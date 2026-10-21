package com.nuvio.tv.data.remote.api

import com.nuvio.tv.core.sources.DeviceSources
import kotlinx.coroutines.CancellationException

/** One boundary for manifest, catalog, metadata, stream and subtitle discovery. */
class DeviceGuardedAddonApi(private val delegate: AddonApi) : AddonApi {
    private suspend fun <T> guarded(url: String, action: suspend () -> T): T {
        DeviceSources.guardAddon(url)
        val epoch = DeviceSources.epoch
        return action().also {
            DeviceSources.guardAddon(url)
            if (epoch != DeviceSources.epoch) throw CancellationException("Device sources changed")
        }
    }
    override suspend fun getManifest(manifestUrl: String) = guarded(manifestUrl) { delegate.getManifest(manifestUrl) }
    override suspend fun getCatalog(catalogUrl: String) = guarded(catalogUrl) { delegate.getCatalog(catalogUrl) }
    override suspend fun getMeta(metaUrl: String) = guarded(metaUrl) { delegate.getMeta(metaUrl) }
    override suspend fun getStreams(streamUrl: String) = guarded(streamUrl) { delegate.getStreams(streamUrl) }
    override suspend fun getSubtitles(subtitleUrl: String) = guarded(subtitleUrl) { delegate.getSubtitles(subtitleUrl) }
}
