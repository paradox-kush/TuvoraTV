package com.nuvio.tv.core.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.rpc
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "SyncDeviceReporter"

/**
 * Tells the account which TV this is, so the Devices list in the web dashboard can say
 * "Living room" instead of "Android TV".
 *
 * The server already learns the device exists from the origin client id on every push; this only
 * adds the human name. Best-effort by design: once per launch, failures swallowed, nothing waits.
 */
@Singleton
class SyncDeviceReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val postgrest: Postgrest,
    private val syncClientIdentity: SyncClientIdentity,
    private val authManager: AuthManager,
) {
    @Volatile
    private var reportedForClientId: String? = null

    suspend fun reportOnce() {
        // report_device is `revoke all … from public, anon`, so without a live session this is not
        // a no-op that fails quietly — it is a 42501 on every launch. Callers react to
        // AuthState.FullAccount, which outlives the session across a refresh failure.
        if (!authManager.canSync) return

        val clientId = syncClientIdentity.currentClientId()
        if (reportedForClientId == clientId) return
        reportedForClientId = clientId

        try {
            postgrest.rpc("report_device", buildJsonObject {
                put("p_client_id", clientId)
                put("p_device_name", deviceName())
                put("p_platform", "tv")
            })
            Log.d(TAG, "Reported device name")
        } catch (e: Exception) {
            // An older backend without the RPC, or no network. Not worth retrying — the device is
            // already listed by its sync traffic, just without a name.
            reportedForClientId = null
            Log.d(TAG, "Device name report failed (harmless): ${e.message}")
        }
    }

    fun clearAccountState() {
        reportedForClientId = null
    }

    /**
     * Unlike a phone, an Android TV usually has a name the owner chose during setup ("Living Room",
     * "Bedroom TV"), which is far more useful than the model. Fall back to the model when it is
     * unset or is just the model repeated.
     */
    private fun deviceName(): String {
        val configured = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()?.trim()

        val model = Build.MODEL.orEmpty().trim()
        if (!configured.isNullOrBlank() && !configured.equals(model, ignoreCase = true)) {
            return configured
        }

        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val name = when {
            model.isEmpty() -> manufacturer
            manufacturer.isEmpty() -> model
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
        return name.ifBlank { "Android TV" }.replaceFirstChar { it.uppercase() }
    }
}
