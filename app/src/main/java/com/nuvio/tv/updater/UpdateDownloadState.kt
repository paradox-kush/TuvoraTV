package com.nuvio.tv.updater

/**
 * What the shared update download store is doing right now. One instance of the store serves both the background
 * job and the banner, so this is the single source of truth for "is the APK downloading / ready / failed" (B274).
 */
sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(val tag: String, val progress: Float?) : UpdateDownloadState
    data class Ready(val tag: String, val apkPath: String) : UpdateDownloadState
    data class Failed(val tag: String, val message: String) : UpdateDownloadState
}

/**
 * Turns the store's state plus what the view model already knows into what the banner shows.
 *
 * B274: the banner used to learn that the background job had downloaded the APK only at the next check, so it offered
 * "Download" while the same file was already coming down, and then jumped to "Install" at the next launch.
 */
internal object UpdateDownloadUiPolicy {
    data class Resolved(
        val isDownloading: Boolean,
        val downloadProgress: Float?,
        val downloadedApkPath: String?,
        val errorMessage: String?
    )

    /**
     * @param updateTag the release the banner is about, or null when there is none
     * @param manualPending the user pressed Download and that call has not returned yet (it may be queued behind a
     *   background download that already holds the store)
     * @param knownApkPath the APK path the view model learned from a check or its own download
     * @param knownError the error the view model itself reported
     */
    fun resolve(
        updateTag: String?,
        download: UpdateDownloadState,
        manualPending: Boolean,
        knownApkPath: String?,
        knownError: String?
    ): Resolved {
        val ours = updateTag != null
        return when {
            ours && download is UpdateDownloadState.Downloading && download.tag == updateTag -> Resolved(
                isDownloading = true,
                downloadProgress = download.progress,
                downloadedApkPath = knownApkPath,
                errorMessage = null
            )
            ours && download is UpdateDownloadState.Ready && download.tag == updateTag -> Resolved(
                isDownloading = false,
                downloadProgress = null,
                downloadedApkPath = download.apkPath,
                errorMessage = null
            )
            ours && download is UpdateDownloadState.Failed && download.tag == updateTag -> Resolved(
                isDownloading = manualPending,
                downloadProgress = null,
                downloadedApkPath = knownApkPath,
                errorMessage = if (manualPending) null else knownError ?: download.message
            )
            else -> Resolved(
                isDownloading = manualPending,
                downloadProgress = if (manualPending) 0f else null,
                downloadedApkPath = knownApkPath,
                errorMessage = knownError
            )
        }
    }
}

/**
 * Keeps the 8 KB read loop of the downloader from becoming a state change per read: a 58 MB APK reports about 7,000
 * times, and every report used to rebuild the update state and wake every screen reading it. Emits when the whole
 * percent moved and at least [minIntervalMs] passed since the last emission, plus the first and the final 100%.
 */
class UpdateProgressThrottle(private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS) {
    private var lastPercent = -1
    private var lastEmitAtMs = 0L

    /** Returns the progress (0..1) to publish now, or null to skip this report. */
    fun accept(downloadedBytes: Long, totalBytes: Long?, nowMs: Long): Float? {
        if (totalBytes == null || totalBytes <= 0L) return null
        val percent = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
        val publish = when {
            lastPercent < 0 -> true
            percent == lastPercent -> false
            percent == 100 -> true
            else -> nowMs - lastEmitAtMs >= minIntervalMs
        }
        if (!publish) return null
        lastPercent = percent
        lastEmitAtMs = nowMs
        return percent / 100f
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 250L
    }
}

/** A second press on Install while the system installer is still on its way must not start a second install. */
internal object InstallPressPolicy {
    /** How long the button keeps saying "Opening installer..." after a press; the system installer can take seconds. */
    const val BUSY_HOLD_MS = 15_000L

    fun shouldStart(isInstalling: Boolean): Boolean = !isInstalling
}

/**
 * The in-process background download competes with the player for network and flash on low-RAM boxes, and nobody can
 * see the banner while the player owns the screen, so it waits until the player is gone.
 */
internal object BackgroundDownloadGatePolicy {
    fun shouldDefer(playerOnScreen: Boolean): Boolean = playerOnScreen
}
