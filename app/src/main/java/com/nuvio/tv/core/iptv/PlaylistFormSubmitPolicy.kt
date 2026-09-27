package com.nuvio.tv.core.iptv


/** Whether the add/edit-playlist form's Save button is enabled, per source type. */
object PlaylistFormSubmitPolicy {
    fun canSubmit(sourceType: String, filePicked: Boolean, portalUrl: String, macAddress: String): Boolean =
        when (sourceType) {
            XtreamAccount.SOURCE_XTREAM, XtreamAccount.SOURCE_URL -> true
            // File: only submittable once a document is actually picked.
            XtreamAccount.SOURCE_FILE -> filePicked
            // Stalker: the portal and the MAC are the only required fields (B58 — was always false).
            XtreamAccount.SOURCE_STALKER -> portalUrl.isNotBlank() && macAddress.isNotBlank()
            else -> false
        }
}
