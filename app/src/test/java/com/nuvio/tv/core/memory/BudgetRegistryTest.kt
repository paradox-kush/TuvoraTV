package com.nuvio.tv.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetRegistryTest {

    @Test
    fun `every registered cache reports its cap and the total is the sum`() {
        val registry = BudgetRegistry()
        assertTrue(registry.register("image_memory_cache", 96L * 1024 * 1024, priority = 0) {})
        assertTrue(registry.register("guide_pages", 8L * 1024 * 1024, priority = 1) {})
        assertTrue(registry.register("poster_palette", 1L * 1024 * 1024, priority = 2) {})

        assertEquals(96L * 1024 * 1024, registry.capOf("image_memory_cache"))
        assertEquals(8L * 1024 * 1024, registry.capOf("guide_pages"))
        assertEquals(1L * 1024 * 1024, registry.capOf("poster_palette"))
        assertEquals(105L * 1024 * 1024, registry.totalCapBytes)
    }

    @Test
    fun `trim walks members in declared priority order`() {
        val registry = BudgetRegistry()
        val trimmed = mutableListOf<String>()
        // Registered out of order on purpose: trim must follow priority, not registration.
        registry.register("last", 1L, priority = 2) { trimmed.add("last") }
        registry.register("first", 1L, priority = 0) { trimmed.add("first") }
        registry.register("middle", 1L, priority = 1) { trimmed.add("middle") }
        // Ties break by registration order.
        registry.register("first-tie", 1L, priority = 0) { trimmed.add("first-tie") }

        registry.trimAll()
        assertEquals(listOf("first", "first-tie", "middle", "last"), trimmed)
    }

    @Test
    fun `a second registration under one name is refused`() {
        val registry = BudgetRegistry()
        var firstTrims = 0
        var secondTrims = 0
        assertTrue(registry.register("image_memory_cache", 32L, priority = 0) { firstTrims++ })
        assertFalse(registry.register("image_memory_cache", 999L, priority = 5) { secondTrims++ })

        // The original registration stays authoritative: cap, total, and trim callback.
        assertEquals(32L, registry.capOf("image_memory_cache"))
        assertEquals(32L, registry.totalCapBytes)
        registry.trimAll()
        assertEquals(1, firstTrims)
        assertEquals(0, secondTrims)
    }
}
