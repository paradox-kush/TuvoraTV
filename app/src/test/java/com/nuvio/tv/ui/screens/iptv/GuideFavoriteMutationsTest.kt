package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.core.tracking.TrackingMembershipApplyResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GuideFavoriteMutationsTest {
    private fun entry(id: String, stamp: Long) = LibraryEntry(
        id = id, type = "tv", name = id, poster = null, background = null, logo = null,
        description = null, releaseInfo = null, imdbRating = null, genres = emptyList(), addonBaseUrl = null, listedAt = stamp,
    )
    private val channel = GuideChannel(contentId = "middle", name = "middle", logo = null, streamUrl = "http://localhost/one.ts", streamId = 1, categoryId = null)
    private val items = MutableStateFlow(listOf(entry("top", 300), entry("middle", 200), entry("bottom", 100)))
    private var profile = 1
    private val library = mockk<LibraryRepository> {
        every { libraryItems } returns items
        coEvery { toggleDefault(any(), any()) } coAnswers {
            val id = firstArg<com.nuvio.tv.domain.model.LibraryEntryInput>().itemId
            items.value = if (items.value.any { it.id == id }) items.value.filterNot { it.id == id } else items.value + entry(id, 900)
            TrackingMembershipApplyResult()
        }
        coEvery { setFavoritesOrder(any()) } coAnswers {
            val changes = firstArg<Map<String, Long>>()
            items.value = items.value.map { it.copy(listedAt = changes[it.id] ?: it.listedAt) }
            changes.size
        }
    }
    private val mutations = GuideFavoriteMutations(library) { profile }

    @Test fun `undo removal restores middle favourite at its original place`() = runTest {
        val notice = mutations.toggle(channel)
        mutations.undo(notice)
        assertEquals(listOf("top", "middle", "bottom"), items.value.sortedByDescending { it.listedAt }.map { it.id })
        assertEquals(200L, items.value.first { it.id == "middle" }.listedAt)
    }

    @Test fun `undo addition removes favourite only once`() = runTest {
        items.value = emptyList()
        val notice = mutations.toggle(channel)
        mutations.undo(notice)
        assertFalse(mutations.undo(notice))
        assertEquals(emptyList<LibraryEntry>(), items.value)
    }

    @Test fun `undo does not overwrite a later readd and reorder`() = runTest {
        val notice = mutations.toggle(channel)
        items.value = items.value + entry("middle", 800)
        assertFalse(mutations.undo(notice))
        assertEquals(800L, items.value.first { it.id == "middle" }.listedAt)
    }

    @Test fun `legacy zero order stamp is restored unchanged`() = runTest {
        items.value = listOf(entry("middle", 0))
        val notice = mutations.toggle(channel)
        mutations.undo(notice)
        assertEquals(0L, items.value.single().listedAt)
    }

    @Test fun `old notice cannot change a different profile`() = runTest {
        val notice = mutations.toggle(channel)
        profile = 2
        assertFalse(mutations.undo(notice))
        assertEquals(listOf("top", "bottom"), items.value.map { it.id })
    }
}
