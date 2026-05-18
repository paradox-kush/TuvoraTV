package com.nuvio.tv.core.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.nuvio.tv.R

object ExternalPlayerLauncher {

    /**
     * Fire-and-forget launch of an external player.
     * Used as a fallback when ActivityResultLauncher is not available (e.g. non-Activity context).
     */
    fun launch(
        context: Context,
        url: String,
        title: String? = null,
        headers: Map<String, String>? = null,
        resumePositionMs: Long = 0L
    ): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(url), "video/*")

                title?.let {
                    putExtra("title", it)
                    putExtra(Intent.EXTRA_TITLE, it)
                }

                headers?.let { hdrs ->
                    if (hdrs.isNotEmpty()) {
                        val headerArray = hdrs.entries.map { "${it.key}: ${it.value}" }.toTypedArray()
                        putExtra("headers", headerArray)
                    }
                }

                if (resumePositionMs > 0L) {
                    putExtra("position", resumePositionMs.toInt())
                    putExtra("from_start", false)
                }

                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.player_no_external_player),
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    /**
     * Create an [ExternalPlayerInput] for use with [ExternalPlayerResultContract].
     * Prefer this over [launch] when you have an Activity context and want to receive
     * playback progress back from the external player.
     */
    fun createInput(
        url: String,
        title: String? = null,
        headers: Map<String, String>? = null,
        resumePositionMs: Long = 0L
    ): ExternalPlayerInput = ExternalPlayerInput(
        url = url,
        title = title,
        headers = headers,
        resumePositionMs = resumePositionMs
    )
}
