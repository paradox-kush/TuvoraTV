package com.nuvio.tv.core.multiview

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiviewBufferBudgetTest {
    private val mib = 1024 * 1024

    @Test
    fun `small heaps clamp up to 24 MiB`() {
        assertEquals(24 * mib, MultiviewBufferBudget.totalBytes(1))
        assertEquals(24 * mib, MultiviewBufferBudget.totalBytes(0))
        assertEquals(24 * mib, MultiviewBufferBudget.totalBytes(-5))
        assertEquals(24 * mib, MultiviewBufferBudget.totalBytes(128))
    }

    @Test
    fun `mid heaps take three sixteenths`() {
        assertEquals(48 * mib, MultiviewBufferBudget.totalBytes(256))
        assertEquals(96 * mib, MultiviewBufferBudget.totalBytes(512))
    }

    @Test
    fun `big heaps clamp down to 96 MiB`() {
        assertEquals(96 * mib, MultiviewBufferBudget.totalBytes(4096))
        assertEquals(96 * mib, MultiviewBufferBudget.totalBytes(1_000_000))
    }

    @Test
    fun `each pane gets a quarter`() {
        assertEquals(6 * mib, MultiviewBufferBudget.bytesPerPane(24 * mib))
        assertEquals(24 * mib, MultiviewBufferBudget.bytesPerPane(96 * mib))
    }
}
