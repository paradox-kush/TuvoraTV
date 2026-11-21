package com.nuvio.tv.updater

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.updater.model.AppUpdate
import com.nuvio.tv.updater.model.AppUpdateAsset
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the downloaded update APK in app storage and remembers which release it belongs to, so a download made in the
 * background can be installed later, from the banner or by the idle install job.
 */
@Singleton
class UpdateDownloadStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updatePreferences: UpdatePreferences,
    private val apkDownloader: ApkDownloader
) {
    // Files storage instead of cache, which the system may clear before the update is installed.
    private val updatesDir: File get() = File(context.filesDir, "updates")

    // The banner and the background job can ask for the same download; the second one waits and reuses the file.
    private val downloadMutex = Mutex()

    // B274: the one place that knows whether the APK is downloading, ready or failed; the job and the banner share it.
    private val _state = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val state: StateFlow<UpdateDownloadState> = _state.asStateFlow()

    fun destinationFor(update: AppUpdate): File = destinationFor(update.assetName)

    private fun destinationFor(assetName: String): File =
        File(updatesDir, assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_"))

    // The stored file may be a fallback asset (B207), so compare against the size of the asset it came from.
    private fun expectedSizeOf(update: AppUpdate, file: File): Long? {
        val candidates = listOf(AppUpdateAsset(update.assetName, update.assetUrl, update.assetSizeBytes)) +
            update.fallbackAssets
        return candidates.firstOrNull { destinationFor(it.name).name == file.name }?.sizeBytes
            ?: update.assetSizeBytes
    }

    @Suppress("DEPRECATION")
    private fun canParseApk(file: File): Boolean =
        runCatching { context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) != null }
            .getOrDefault(false)

    /** Returns the already downloaded APK for this release, or null when it is missing or incomplete. */
    suspend fun downloadedFileFor(update: AppUpdate): File? {
        val (tag, path) = updatePreferences.downloadedUpdate.first() ?: return null
        if (tag != update.tag) return null
        val file = File(path)
        val expectedSize = expectedSizeOf(update, file)
        // File stats stay off the main thread: the banner check calls this from there, and on a loaded box they stall.
        return withContext(Dispatchers.IO) {
            file.takeIf { it.exists() && (expectedSize == null || it.length() == expectedSize) }
        }
    }

    /** Returns the downloaded APK when it is for a version newer than the installed one. */
    suspend fun pendingInstall(): Pair<String, File>? {
        val (tag, path) = updatePreferences.downloadedUpdate.first() ?: return null
        val file = File(path)
        if (!VersionUtils.isRemoteNewer(tag, BuildConfig.VERSION_NAME)) return null
        if (!withContext(Dispatchers.IO) { file.exists() }) return null
        return tag to file
    }

    /**
     * Downloads [update] once. A second caller waits for the first and reuses its file. Progress is published through
     * [state] (throttled), so [onProgress] is only for callers that want the raw byte counts.
     */
    suspend fun download(
        update: AppUpdate,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): Result<File> = downloadMutex.withLock {
        downloadedFileFor(update)?.let { file ->
            _state.value = UpdateDownloadState.Ready(update.tag, file.absolutePath)
            return@withLock Result.success(file)
        }
        _state.value = UpdateDownloadState.Downloading(update.tag, null)
        val throttle = UpdateProgressThrottle()
        val publishProgress: (Long, Long?) -> Unit = { downloaded, total ->
            onProgress(downloaded, total)
            throttle.accept(downloaded, total, SystemClock.elapsedRealtime())?.let { progress ->
                _state.value = UpdateDownloadState.Downloading(update.tag, progress)
            }
        }
        try {
            // Best candidate first, universal APK last. A candidate is accepted only when the file is the
            // advertised size and Android can parse it; otherwise the next one is tried (B207).
            val candidates = listOf(AppUpdateAsset(update.assetName, update.assetUrl, update.assetSizeBytes)) +
                update.fallbackAssets
            var result: Result<File> = Result.failure(IllegalStateException("No APK candidate"))
            for (candidate in candidates) {
                val destination = destinationFor(candidate.name)
                result = apkDownloader.download(candidate.url, destination, publishProgress).mapCatching { file ->
                    val expected = candidate.sizeBytes
                    check(expected == null || file.length() == expected) {
                        "Downloaded ${file.length()} of $expected bytes of ${candidate.name}"
                    }
                    // Parsing a ~58 MB APK is slow; callers run on IO, never Main.
                    check(canParseApk(file)) { "Android cannot parse ${candidate.name}" }
                    file
                }
                // The downloader's runCatching also swallows a cancellation; a stopped job is not a failed download.
                currentCoroutineContext().ensureActive()
                if (result.isSuccess) break
                runCatching { destination.delete() }
            }
            result.onSuccess { file ->
                updatesDir.listFiles()?.filter { it != file }?.forEach { it.delete() }
                updatePreferences.setDownloadedUpdate(update.tag, file.absolutePath)
                _state.value = UpdateDownloadState.Ready(update.tag, file.absolutePath)
            }.onFailure { error ->
                _state.value = UpdateDownloadState.Failed(update.tag, error.message.orEmpty())
            }
            result
        } catch (e: CancellationException) {
            _state.value = UpdateDownloadState.Idle
            throw e
        }
    }

    /** Drops the downloaded APK once it is installed, outdated, or no longer wanted. */
    suspend fun clear() {
        // Deleting a ~58 MB file must not run on Main: the banner's startup cleanup calls this from there.
        withContext(Dispatchers.IO) { updatesDir.deleteRecursively() }
        updatePreferences.setDownloadedUpdate(null, null)
        if (_state.value !is UpdateDownloadState.Downloading) _state.value = UpdateDownloadState.Idle
    }

    suspend fun clearIfInstalled() {
        val (tag, _) = updatePreferences.downloadedUpdate.first() ?: return
        if (!VersionUtils.isRemoteNewer(tag, BuildConfig.VERSION_NAME)) clear()
    }
}
