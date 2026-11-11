package com.nuvio.tv.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class AbandonedPlayerBudgetTest {
    @Test
    fun `default budget allows exactly one abandon`() {
        val budget = AbandonedPlayerBudget()
        assertTrue("first abandon is allowed", budget.tryConsume())
        assertFalse("second abandon is refused", budget.tryConsume())
        assertFalse("third abandon is refused", budget.tryConsume())
    }

    @Test
    fun `used counts only granted abandons`() {
        val budget = AbandonedPlayerBudget()
        assertEquals("nothing used yet", 0, budget.used)
        budget.tryConsume()
        assertEquals("one granted", 1, budget.used)
        budget.tryConsume()
        assertEquals("refusals are not counted", 1, budget.used)
    }

    @Test
    fun `custom max is honoured and zero never grants`() {
        val three = AbandonedPlayerBudget(maxAbandons = 3)
        assertEquals("three grants", 3, (1..5).count { three.tryConsume() })
        assertEquals("used", 3, three.used)
        val none = AbandonedPlayerBudget(maxAbandons = 0)
        assertFalse("zero budget never grants", none.tryConsume())
        assertEquals("zero used", 0, none.used)
    }

    @Test
    fun `concurrent callers share the budget exactly`() {
        val budget = AbandonedPlayerBudget(maxAbandons = 2)
        val granted = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val futures = (1..8).map {
            pool.submit {
                start.await()
                if (budget.tryConsume()) granted.incrementAndGet()
            }
        }
        start.countDown()
        futures.forEach { it.get() }
        pool.shutdown()
        assertEquals("exactly maxAbandons grants", 2, granted.get())
    }
}
