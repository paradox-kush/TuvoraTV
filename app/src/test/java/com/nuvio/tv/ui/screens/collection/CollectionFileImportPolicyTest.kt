package com.nuvio.tv.ui.screens.collection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B09 (Discord 2026-08-26 / 09-26): "Import collections from file does nothing on TV". The notice said
 * "File not found in Downloads. Export collections first" although the viewer had put the file there:
 * scoped storage hides other apps' Downloads files, and below Android 10 the app has no storage access.
 */
class CollectionFileImportPolicyTest {

    @Test
    fun `Android 10 and later can read only this install's own export`() {
        for (sdk in listOf(29, 30, 33, 35)) {
            assertEquals(
                "sdk $sdk",
                CollectionFileImportPolicy.Notice.ONLY_OWN_EXPORT_READABLE,
                CollectionFileImportPolicy.noticeFor(sdk),
            )
        }
    }

    @Test
    fun `below Android 10 the app has no storage access so file import cannot work`() {
        // Fire OS 7 (Fire TV Stick 4K, Lite, 3rd gen) is Android 9.
        for (sdk in listOf(21, 25, 28)) {
            assertEquals(
                "sdk $sdk",
                CollectionFileImportPolicy.Notice.FILE_IMPORT_UNAVAILABLE,
                CollectionFileImportPolicy.noticeFor(sdk),
            )
        }
    }
}
