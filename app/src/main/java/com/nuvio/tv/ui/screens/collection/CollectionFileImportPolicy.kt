package com.nuvio.tv.ui.screens.collection

/**
 * B09: what "From File" tells the viewer when it cannot load `nuvio-collections.json`.
 *
 * The TV has no file picker for this; it reads that one name from Downloads. On Android 10+ (scoped
 * storage) an app can read only the Downloads files IT created — a file copied in from a phone, a
 * computer, a USB stick or a browser is invisible to the query (Android docs: "If your app wants to
 * access a file within the MediaStore.Downloads collection that your app didn't create, you must use
 * the Storage Access Framework"), and so is this app's own export after a reinstall. Below Android 10
 * the app holds no storage permission at all, so the public Downloads file cannot be read either.
 * The old notice, "File not found in Downloads. Export collections first", was wrong in both cases:
 * the file the viewer put there IS there. The notice has to say what can actually be read, and point
 * at the import modes that work on a TV.
 */
internal object CollectionFileImportPolicy {

    enum class Notice {
        /** Only a file this install exported can be read; anything else needs From URL / Paste JSON. */
        ONLY_OWN_EXPORT_READABLE,

        /** No storage access below Android 10: From File cannot work on this device. */
        FILE_IMPORT_UNAVAILABLE,
    }

    /** Scoped storage starts at Android 10 (Q, API 29). */
    private const val SCOPED_STORAGE_SDK = 29

    /** The file was not found (Android 10+) or could not be opened (below 10). */
    fun noticeFor(sdkInt: Int): Notice =
        if (sdkInt >= SCOPED_STORAGE_SDK) Notice.ONLY_OWN_EXPORT_READABLE else Notice.FILE_IMPORT_UNAVAILABLE
}
