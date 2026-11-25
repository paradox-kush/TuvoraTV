package com.nuvio.tv.core.sync.androidtv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "TvLauncherRemoval"

/**
 * The launcher tells us when the viewer removes one of our programs: it sets the program
 * non-browsable and sends this broadcast with the program's row id. We remember the item so the
 * next reconcile does not put it back, and delete the hidden row so a later replay inserts a fresh,
 * visible one.
 *
 * Actions (verified against androidx.tvprovider 1.0.0 TvContractCompat and android.media.tv.TvContract):
 * `android.media.tv.action.WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED` with extra
 * `android.media.tv.extra.WATCH_NEXT_PROGRAM_ID`, and
 * `android.media.tv.action.PREVIEW_PROGRAM_BROWSABLE_DISABLED` with extra
 * `android.media.tv.extra.PREVIEW_PROGRAM_ID` (both a long row id).
 */
class LauncherRemovalReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface LauncherRemovalEntryPoint {
        fun removalStore(): LauncherRemovalStore
    }

    override fun onReceive(context: Context, intent: Intent) {
        val (programUri, internalIdColumn) = when (intent.action) {
            TvContractCompat.ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED -> {
                val id = intent.getLongExtra(TvContractCompat.EXTRA_WATCH_NEXT_PROGRAM_ID, -1L)
                if (id < 0) return
                TvContractCompat.buildWatchNextProgramUri(id) to
                    TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
            }
            TvContractCompat.ACTION_PREVIEW_PROGRAM_BROWSABLE_DISABLED -> {
                val id = intent.getLongExtra(TvContractCompat.EXTRA_PREVIEW_PROGRAM_ID, -1L)
                if (id < 0) return
                TvContractCompat.buildPreviewProgramUri(id) to
                    TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID
            }
            else -> return
        }

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val internalId = readInternalId(appContext, programUri, internalIdColumn)
                if (internalId.isNullOrBlank()) {
                    Log.d(TAG, "Removed program has no internal id; nothing to remember")
                } else {
                    val store = EntryPointAccessors
                        .fromApplication(appContext, LauncherRemovalEntryPoint::class.java)
                        .removalStore()
                    store.record(internalId, System.currentTimeMillis())
                    Log.d(TAG, "Remembered a launcher removal")
                }
                // The hidden row would otherwise stay; deleting it lets a later replay insert a visible one.
                runCatching { appContext.contentResolver.delete(programUri, null, null) }
            } catch (e: Exception) {
                Log.w(TAG, "Could not record launcher removal", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun readInternalId(context: Context, uri: Uri, column: String): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
}
