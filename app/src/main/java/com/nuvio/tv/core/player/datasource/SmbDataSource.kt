package com.nuvio.tv.core.player.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import com.nuvio.tv.data.locallibrary.LocalLibraryCredentialStore
import com.nuvio.tv.data.locallibrary.LocalLibraryCredentialStore.Field
import com.nuvio.tv.data.locallibrary.source.SmbClientFactory
import com.nuvio.tv.data.locallibrary.source.SmbSource.SmbLocation
import com.nuvio.tv.data.local.LocalLibraryPreferences
import com.nuvio.tv.domain.model.locallibrary.LocalLibrarySourceConfig
import com.nuvio.tv.domain.model.locallibrary.SourceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.EnumSet

/**
 * ExoPlayer DataSource backed by smbj. Resolves the right SMB source config
 * from the URI's host+share, authenticates with the matching credentials, and
 * exposes a seekable read interface over the remote file.
 *
 * URIs are of the form `smb://<host>/<share>/<path>`.
 */
@UnstableApi
class SmbDataSource(
    private val preferences: LocalLibraryPreferences,
    private val credentialStore: LocalLibraryCredentialStore
) : BaseDataSource(/* isNetwork = */ true) {

    private var currentUri: Uri? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    private var file: SmbFile? = null
    private var position: Long = 0
    private var bytesRemaining: Long = 0
    private var opened: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        currentUri = dataSpec.uri
        transferInitializing(dataSpec)

        val location = SmbLocation.parse(dataSpec.uri.toString())
        val sourceConfig = resolveSourceForLocation(location)
            ?: throw DataSourceException(
                IOException("No configured SMB source for ${location.host}/${location.share}"),
                DataSourceException.POSITION_OUT_OF_RANGE
            )

        try {
            val client = SmbClientFactory.newClient()
            val conn = client.connect(location.host).also { connection = it }
            val sess = conn.authenticate(authContextFor(sourceConfig.id)).also { session = it }
            val sh = sess.connectShare(location.share) as? DiskShare
                ?: throw IOException("Share ${location.share} is not a disk share")
            share = sh

            val filePath = buildFilePath(location, dataSpec.uri)
            val opened = sh.openFile(
                filePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            file = opened

            val fileSize = opened.fileInformation.standardInformation.endOfFile
            position = dataSpec.position
            bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                fileSize - dataSpec.position
            } else {
                minOf(dataSpec.length, fileSize - dataSpec.position)
            }
            if (bytesRemaining < 0) {
                throw DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE)
            }
        } catch (t: Throwable) {
            closeQuietly()
            throw if (t is DataSourceException) t else DataSourceException(
                t,
                DataSourceException.POSITION_OUT_OF_RANGE
            )
        }

        this.opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val f = file ?: throw IOException("SMB file not opened")
        val read = try {
            f.read(buffer, position, offset, toRead)
        } catch (t: Throwable) {
            throw IOException("SMB read failed at $position", t)
        }
        if (read < 0) {
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                throw DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE)
            }
            return C.RESULT_END_OF_INPUT
        }
        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun close() {
        if (opened) transferEnded()
        closeQuietly()
        opened = false
        currentUri = null
    }

    override fun getUri(): Uri? = currentUri

    private fun closeQuietly() {
        runCatching { file?.close() }
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        file = null
        share = null
        session = null
        connection = null
    }

    private fun resolveSourceForLocation(target: SmbLocation): LocalLibrarySourceConfig? = runBlocking {
        preferences.sources.first()
            .filter { it.kind == SourceKind.SMB }
            .firstOrNull { config ->
                runCatching { SmbLocation.parse(config.urlOrPath) }
                    .getOrNull()
                    ?.let { it.host.equals(target.host, ignoreCase = true) && it.share.equals(target.share, ignoreCase = true) }
                    ?: false
            }
    }

    private fun authContextFor(sourceId: String): AuthenticationContext {
        val username = credentialStore.getSecret(sourceId, Field.SMB_USERNAME).orEmpty()
        val password = credentialStore.getSecret(sourceId, Field.SMB_PASSWORD).orEmpty()
        val domain = credentialStore.getSecret(sourceId, Field.SMB_DOMAIN)
        return if (username.isBlank()) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(username, password.toCharArray(), domain)
        }
    }

    private fun buildFilePath(location: SmbLocation, uri: Uri): String {
        // Strip the leading "/<share>/" segment from the URI path
        val path = uri.path.orEmpty().trimStart('/')
        val parts = path.split('/', limit = 2)
        return if (parts.size < 2) "" else parts[1].replace('/', '\\')
    }

    class Factory(
        private val preferences: LocalLibraryPreferences,
        private val credentialStore: LocalLibraryCredentialStore
    ) : DataSource.Factory {
        @UnstableApi
        override fun createDataSource(): DataSource = SmbDataSource(preferences, credentialStore)
    }
}
