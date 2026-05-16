package com.nuvio.tv.data.locallibrary.source

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.nuvio.tv.data.locallibrary.LocalLibraryCredentialStore
import com.nuvio.tv.data.locallibrary.LocalLibraryCredentialStore.Field
import com.nuvio.tv.data.locallibrary.subtitle.SubtitleFilenameParser
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.locallibrary.ExternalSubtitleFile
import com.nuvio.tv.domain.model.locallibrary.LocalLibrarySourceConfig
import com.nuvio.tv.domain.model.locallibrary.ResolvedStream
import com.nuvio.tv.domain.model.locallibrary.ScannedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.EnumSet

/**
 * Browses and reads media from a SMB/CIFS share via smbj.
 *
 * Playback URLs use the `smb://` scheme so ExoPlayer routes through
 * [com.nuvio.tv.core.player.datasource.SmbDataSource] — the password lookup
 * happens there at open-time, never in the URL itself.
 */
class SmbSource(
    override val config: LocalLibrarySourceConfig,
    private val credentialStore: LocalLibraryCredentialStore
) : LocalLibrarySource {

    private val location: SmbLocation = SmbLocation.parse(config.urlOrPath)

    override fun scan(): Flow<ScannedItem> = flow {
        Log.i(TAG, "scan start sourceId=${config.id} host=${location.host} share=${location.share} rootDir='${location.rootDir}'")
        var emitted = 0
        withShare { share, rootDir ->
            traverse(share, rootDir, rootDir).forEach { entry ->
                val (relPath, sizeBytes) = entry
                val parsed = com.nuvio.tv.data.locallibrary.match.FilenameParser.parse(
                    relPath.substringAfterLast('/')
                )
                emit(
                    ScannedItem(
                        sourceId = config.id,
                        relativePath = relPath,
                        fileName = relPath.substringAfterLast('/'),
                        sizeBytes = sizeBytes,
                        parsedTitle = parsed.title,
                        parsedYear = parsed.year,
                        parsedSeason = parsed.season,
                        parsedEpisode = parsed.episode,
                        typeHint = parsed.contentType
                    )
                )
                emitted++
            }
        }
        Log.i(TAG, "scan complete sourceId=${config.id} videosEmitted=$emitted")
    }.flowOn(Dispatchers.IO)

    override suspend fun resolveStream(item: ScannedItem): ResolvedStream {
        val urlPath = item.relativePath.trim('/').replace('\\', '/')
        // ScannedItem.relativePath is stored relative to the source's rootDir
        // (see traverse()) — re-prepend rootDir here so the playback URL is
        // absolute under the share root, which is what SmbDataSource expects.
        val rootPrefix = location.rootDir.replace('\\', '/').trim('/').let {
            if (it.isEmpty()) "" else "$it/"
        }
        val url = "smb://${location.host}/${location.share}/$rootPrefix$urlPath"
        val subtitles = withContext(Dispatchers.IO) {
            discoverSidecarSubtitles(item, rootPrefix)
        }
        return ResolvedStream(
            url = url,
            scheme = "smb",
            sizeBytes = item.sizeBytes,
            subtitles = subtitles
        )
    }

    private fun discoverSidecarSubtitles(
        item: ScannedItem,
        rootPrefix: String
    ): List<ExternalSubtitleFile> {
        val relPath = item.relativePath.trim('/').replace('\\', '/')
        val parentDir = relPath.substringBeforeLast('/', missingDelimiterValue = "")
        val videoBase = item.fileName.substringBeforeLast('.')
        if (videoBase.isBlank()) return emptyList()

        val urlParentPrefix = if (parentDir.isEmpty()) rootPrefix else "$rootPrefix$parentDir/"
        val sharePath = (rootPrefix + parentDir)
            .replace('/', '\\')
            .trim('\\')

        return try {
            withShare { share, _ ->
                val entries = try {
                    share.list(sharePath)
                } catch (t: Throwable) {
                    Log.w(TAG, "subtitle dir list failed '$sharePath' on ${location.host}/${location.share}", t)
                    return@withShare emptyList()
                }
                entries.mapNotNull { entry ->
                    val name = entry.fileName
                    if (name == "." || name == "..") return@mapNotNull null
                    val isDirectory =
                        (entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                    if (isDirectory) return@mapNotNull null
                    if (!SubtitleFilenameParser.matchesVideo(name, videoBase)) return@mapNotNull null
                    val ext = name.substringAfterLast('.', missingDelimiterValue = "")
                    val parsed = SubtitleFilenameParser.parse(name, videoBase)
                    ExternalSubtitleFile(
                        url = "smb://${location.host}/${location.share}/$urlParentPrefix$name",
                        displayName = parsed.displayName,
                        language = parsed.language,
                        mimeType = SubtitleFilenameParser.mimeTypeFor(ext),
                        isForced = parsed.isForced,
                        source = ExternalSubtitleFile.Source.LOCAL_SIDECAR
                    )
                }.also {
                    if (it.isNotEmpty()) {
                        Log.i(TAG, "discovered ${it.size} sidecar subtitle(s) for ${item.fileName}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "subtitle discovery failed for ${item.fileName}", t)
            emptyList()
        }
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                withTimeout(CONNECT_BUDGET_MS) {
                    withShare { _, _ -> /* successful connect+authenticate is enough */ }
                }
            } catch (t: TimeoutCancellationException) {
                throw IOException("Timed out connecting to ${location.host} after ${CONNECT_BUDGET_MS}ms", t)
            }
        }.onFailure {
            Log.e(TAG, "testConnection failed for ${location.host}/${location.share}", it)
        }
    }

    private inline fun <T> withShare(block: (DiskShare, String) -> T): T {
        val username = credentialStore.getSecret(config.id, Field.SMB_USERNAME).orEmpty()
        val password = credentialStore.getSecret(config.id, Field.SMB_PASSWORD).orEmpty()
        val domain = credentialStore.getSecret(config.id, Field.SMB_DOMAIN)
        val auth = if (username.isBlank()) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(username, password.toCharArray(), domain)
        }
        val client = SmbClientFactory.newClient()
        return client.connect(location.host).use { connection ->
            val session = connection.authenticate(auth)
            session.connectShare(location.share).use { share ->
                require(share is DiskShare) { "Share ${location.share} is not a disk share" }
                block(share, location.rootDir)
            }
        }
    }

    /**
     * DFS over [share] rooted at [rootDir]. Returns pairs of (relative path, size).
     *
     * Internal path tracking uses backslash separators because that's what smbj's
     * `DiskShare.list` puts on the wire — forward slashes silently fail on some
     * SMB servers. The relative path returned to callers is normalised back to
     * `/` so the rest of the app (URLs, file-name parsing) stays consistent.
     */
    private fun traverse(
        share: DiskShare,
        rootDir: String,
        currentDir: String
    ): List<Pair<String, Long>> {
        val results = mutableListOf<Pair<String, Long>>()
        val normalizedRoot = rootDir.replace('/', '\\').trim('\\')
        val normalizedStart = currentDir.replace('/', '\\').trim('\\')
        val stack = ArrayDeque<String>().apply { addLast(normalizedStart) }
        var dirsListed = 0
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val entries = try {
                share.list(dir.takeIf { it.isNotEmpty() } ?: "")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to list '$dir' on ${location.host}/${location.share}", t)
                continue
            }
            dirsListed++
            var fileCount = 0
            var dirCount = 0
            var videoCount = 0
            for (entry in entries) {
                val name = entry.fileName
                if (name == "." || name == "..") continue
                val isDirectory = (entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                val full = if (dir.isEmpty()) name else "$dir\\$name"
                if (isDirectory) {
                    dirCount++
                    stack.addLast(full)
                } else {
                    fileCount++
                    if (isVideoFile(name)) {
                        videoCount++
                        val stripped = if (normalizedRoot.isEmpty()) full else full.removePrefix("$normalizedRoot\\")
                        results += stripped.replace('\\', '/') to entry.endOfFile
                    }
                }
            }
            Log.d(TAG, "list '$dir' on ${location.host}/${location.share}: dirs=$dirCount files=$fileCount videos=$videoCount")
        }
        Log.i(TAG, "traverse complete on ${location.host}/${location.share}: dirsListed=$dirsListed totalVideos=${results.size}")
        return results
    }

    private fun isVideoFile(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot < 0) return false
        return name.substring(dot + 1).lowercase() in VIDEO_EXTS
    }

    /** Public helper so [com.nuvio.tv.core.player.datasource.SmbDataSource] can reuse parsing. */
    data class SmbLocation(
        val host: String,
        val share: String,
        /** Subpath under the share root, "" if browsing the share root itself. */
        val rootDir: String
    ) {
        companion object {
            fun parse(raw: String): SmbLocation {
                val cleaned = raw
                    .removePrefix("smb://")
                    .removePrefix("//")
                    .trimStart('/')
                    .trimEnd('/')
                val parts = cleaned.split('/')
                require(parts.size >= 2) { "SMB path must include a share: $raw" }
                val host = parts[0]
                val share = parts[1]
                val rootDir = parts.drop(2).joinToString("/")
                return SmbLocation(host = host, share = share, rootDir = rootDir)
            }
        }
    }

    companion object {
        private const val TAG = "SmbSource"
        private const val CONNECT_BUDGET_MS = 15_000L
        private val VIDEO_EXTS = setOf(
            "mp4", "mkv", "avi", "mov", "ts", "m2ts", "webm", "wmv", "flv", "mpg", "mpeg", "m4v"
        )

        /** Open access mask for read-only file open from [SmbDataSource]. */
        val READ_ACCESS: EnumSet<AccessMask> = EnumSet.of(AccessMask.GENERIC_READ)
        val READ_DISPOSITION: SMB2CreateDisposition = SMB2CreateDisposition.FILE_OPEN
        val READ_SHARE_ACCESS: EnumSet<SMB2ShareAccess> = EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ)
    }
}
