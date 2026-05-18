package com.nuvio.tv.core.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * Input data for launching an external video player.
 */
data class ExternalPlayerInput(
    val url: String,
    val title: String? = null,
    val headers: Map<String, String>? = null,
    val resumePositionMs: Long = 0L
)

/**
 * Result returned by an external video player after playback ends.
 * Not all players return this data — MX Player, VLC, and Just Player are known to support it.
 */
data class ExternalPlayerResult(
    val positionMs: Long,
    val durationMs: Long?,
    val endedByUser: Boolean = true
)

/**
 * ActivityResultContract that launches an external video player via ACTION_VIEW
 * and parses the playback position returned by the player (if supported).
 *
 * Supported players:
 * - MX Player: returns "position" (Int, ms), "duration" (Int, ms), "end_by" (String)
 * - VLC: returns "extra_position" (Long, ms), "extra_duration" (Long, ms)
 * - Just Player: returns "position" (Int, ms), "duration" (Int, ms)
 * - mpv-android: returns "position" (Int, ms), "duration" (Int, ms)
 */
class ExternalPlayerResultContract : ActivityResultContract<ExternalPlayerInput, ExternalPlayerResult?>() {

    override fun createIntent(context: Context, input: ExternalPlayerInput): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(input.url), "video/*")

            input.title?.let {
                putExtra("title", it)
                putExtra(Intent.EXTRA_TITLE, it)
            }

            input.headers?.let { hdrs ->
                if (hdrs.isNotEmpty()) {
                    val headerArray = hdrs.entries.map { "${it.key}: ${it.value}" }.toTypedArray()
                    putExtra("headers", headerArray)
                }
            }

            // Resume position — supported by MX Player, VLC, Just Player, mpv-android
            if (input.resumePositionMs > 0L) {
                putExtra("position", input.resumePositionMs.toInt())  // MX Player / Just Player / mpv (Int ms)
                putExtra("from_start", false)                         // VLC: don't force start from beginning
            }

            // Request that the player returns result with position/duration.
            // Required by MX Player; harmless for other players.
            putExtra("return_result", true)

            // Do NOT add FLAG_ACTIVITY_NEW_TASK — it prevents receiving the result.
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): ExternalPlayerResult? {
        // Some players return RESULT_OK, others return RESULT_CANCELED even on normal exit.
        // We try to parse position regardless of resultCode.
        android.util.Log.d("ExtPlayerContract", "parseResult: resultCode=$resultCode, intent=$intent, extras=${intent?.extras?.keySet()?.toList()}")
        val data = intent ?: return null

        val position = parsePosition(data) ?: return null
        val duration = parseDuration(data)
        val endedByUser = parseEndReason(data)

        return ExternalPlayerResult(
            positionMs = position,
            durationMs = duration,
            endedByUser = endedByUser
        )
    }

    private fun parsePosition(data: Intent): Long? {
        // VLC uses Long extras
        val vlcPosition = data.getLongExtra("extra_position", -1L)
        if (vlcPosition > 0) return vlcPosition

        // MX Player / Just Player / mpv use Int extras
        val mxPosition = data.getIntExtra("position", -1)
        if (mxPosition > 0) return mxPosition.toLong()

        return null
    }

    private fun parseDuration(data: Intent): Long? {
        val vlcDuration = data.getLongExtra("extra_duration", -1L)
        if (vlcDuration > 0) return vlcDuration

        val mxDuration = data.getIntExtra("duration", -1)
        if (mxDuration > 0) return mxDuration.toLong()

        return null
    }

    private fun parseEndReason(data: Intent): Boolean {
        // MX Player returns "end_by" with values "user" or "playback_completion"
        val endBy = data.getStringExtra("end_by")
        return endBy != "playback_completion"
    }
}
