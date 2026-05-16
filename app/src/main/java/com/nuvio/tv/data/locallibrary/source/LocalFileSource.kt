package com.nuvio.tv.data.locallibrary.source

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import android.util.Log
import com.nuvio.tv.data.locallibrary.match.FilenameParser
import com.nuvio.tv.data.locallibrary.subtitle.SubtitleFilenameParser
import com.nuvio.tv.domain.model.locallibrary.ExternalSubtitleFile
import com.nuvio.tv.domain.model.locallibrary.LocalLibrarySourceConfig
import com.nuvio.tv.domain.model.locallibrary.ResolvedStream
import com.nuvio.tv.domain.model.locallibrary.ScannedItem
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Backed by a SAF tree URI obtained from `ACTION_OPEN_DOCUMENT_TREE`. The
 * [LocalLibrarySourceConfig.urlOrPath] holds the persisted tree URI string;
 * permissions must already have been granted via `takePersistableUriPermission`
 * during the Add Source flow.
 */
class LocalFileSource(
    override val config: LocalLibrarySourceConfig,
    private val context: Context
) : LocalLibrarySource {

    private val rootUri: Uri = Uri.parse(config.urlOrPath)

    override fun scan(): Flow<ScannedItem> = flow {
        val root = DocumentFile.fromTreeUri(context, rootUri)
            ?: error("Cannot open tree URI: $rootUri")
        traverse(root, "").forEach { (relPath, docFile) ->
            val parsed = FilenameParser.parse(docFile.name.orEmpty())
            emit(
                ScannedItem(
                    sourceId = config.id,
                    relativePath = relPath,
                    fileName = docFile.name.orEmpty(),
                    sizeBytes = docFile.length().takeIf { it > 0 },
                    parsedTitle = parsed.title,
                    parsedYear = parsed.year,
                    parsedSeason = parsed.season,
                    parsedEpisode = parsed.episode,
                    typeHint = parsed.contentType,
                    directStreamUrl = docFile.uri.toString()
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun resolveStream(item: ScannedItem): ResolvedStream? {
        val url = item.directStreamUrl ?: return null
        val subtitles = withContext(Dispatchers.IO) { discoverSidecarSubtitles(item) }
        return ResolvedStream(
            url = url,
            scheme = "content",
            sizeBytes = item.sizeBytes,
            subtitles = subtitles
        )
    }

    private fun discoverSidecarSubtitles(item: ScannedItem): List<ExternalSubtitleFile> {
        return try {
            val root = DocumentFile.fromTreeUri(context, rootUri) ?: return emptyList()
            val parent = walkToParent(root, item.relativePath) ?: return emptyList()
            val videoBase = item.fileName.substringBeforeLast('.')
            if (videoBase.isBlank()) return emptyList()
            parent.listFiles().mapNotNull { child ->
                val name = child.name ?: return@mapNotNull null
                if (child.isDirectory) return@mapNotNull null
                if (!SubtitleFilenameParser.matchesVideo(name, videoBase)) return@mapNotNull null
                val ext = name.substringAfterLast('.', missingDelimiterValue = "")
                val parsed = SubtitleFilenameParser.parse(name, videoBase)
                ExternalSubtitleFile(
                    url = child.uri.toString(),
                    displayName = parsed.displayName,
                    language = parsed.language,
                    mimeType = SubtitleFilenameParser.mimeTypeFor(ext),
                    isForced = parsed.isForced,
                    source = ExternalSubtitleFile.Source.LOCAL_SIDECAR
                )
            }.also {
                if (it.isNotEmpty()) Log.i(TAG, "discovered ${it.size} sidecar subtitle(s) for ${item.fileName}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "sidecar discovery failed for ${item.fileName}", t)
            emptyList()
        }
    }

    private fun walkToParent(root: DocumentFile, relativePath: String): DocumentFile? {
        val normalized = relativePath.trim('/').replace('\\', '/')
        val parentPath = normalized.substringBeforeLast('/', missingDelimiterValue = "")
        if (parentPath.isEmpty()) return root
        var current: DocumentFile? = root
        for (segment in parentPath.split('/')) {
            if (segment.isBlank()) continue
            current = current?.findFile(segment) ?: return null
            if (current?.isDirectory != true) return null
        }
        return current
    }

    override suspend fun testConnection(): Result<Unit> = runCatching {
        val root = DocumentFile.fromTreeUri(context, rootUri)
            ?: error("Tree URI not accessible — permissions may have been revoked")
        require(root.isDirectory) { "Tree URI is not a directory: $rootUri" }
    }

    private fun traverse(
        root: DocumentFile,
        prefix: String
    ): List<Pair<String, DocumentFile>> {
        val results = mutableListOf<Pair<String, DocumentFile>>()
        val stack = ArrayDeque<Pair<DocumentFile, String>>().apply { addLast(root to prefix) }
        while (stack.isNotEmpty()) {
            val (dir, dirPath) = stack.removeLast()
            val children = try {
                dir.listFiles()
            } catch (_: Throwable) {
                continue
            }
            for (child in children) {
                val name = child.name ?: continue
                val rel = if (dirPath.isEmpty()) name else "$dirPath/$name"
                when {
                    child.isDirectory -> stack.addLast(child to rel)
                    isVideoFile(name) -> results += rel to child
                }
            }
        }
        return results
    }

    private fun isVideoFile(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot < 0) return false
        return name.substring(dot + 1).lowercase() in VIDEO_EXTS
    }

    companion object {
        private const val TAG = "LocalFileSource"
        private val VIDEO_EXTS = setOf(
            "mp4", "mkv", "avi", "mov", "ts", "m2ts", "webm", "wmv", "flv", "mpg", "mpeg", "m4v"
        )
    }
}
