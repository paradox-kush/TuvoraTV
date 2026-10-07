package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedLibraryItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LibraryAtomicToggleTest {
    @Test
    fun concurrentToggleAndUndoPreserveParityInTheRealStore() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("library-toggle").toFile()
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) {
            File(dir, "library.preferences_pb")
        }
        val factory = mockk<ProfileDataStoreFactory> { every { get(1, any()) } returns store }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        val prefs = LibraryPreferences(factory, profiles)
        val item = SavedLibraryItem("xtream:live:1", "tv", "Channel", null, PosterShape.LANDSCAPE,
            null, null, null, null, emptyList(), null, addedAt = 1L)
        try {
            (1..40).map { async(Dispatchers.Default) { prefs.toggleItem(item, 1) } }.awaitAll()
            assertEquals(emptyList<SavedLibraryItem>(), prefs.getAllItems(1))
            prefs.toggleItem(item, 1)
            assertEquals(listOf(item.id), prefs.getAllItems(1).map { it.id })
        } finally {
            job.cancelAndJoin()
            dir.deleteRecursively()
        }
    }
}
