package com.nuvio.tv.core.analytics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The normal background SIGKILL (cached process reclaimed for RAM) must NOT be reported — it was
 * ~89% of app_exit volume and is not a crash. App faults and user-visible reclaims still report.
 */
class AppExitReportPolicyTest {

    // Android RunningAppProcessInfo importance values.
    private val FOREGROUND = 100
    private val FOREGROUND_SERVICE = 125
    private val PERCEPTIBLE = 230
    private val SERVICE = 300
    private val CACHED = 400
    private val GONE = 1000

    @Test
    fun `cached background SIGKILL is dropped (the 89 percent skew)`() {
        assertFalse("cached signaled is normal reclaim, not a crash",
            AppExitReportPolicy.shouldReport("signaled", CACHED))
        assertFalse("gone signaled is normal reclaim",
            AppExitReportPolicy.shouldReport("signaled", GONE))
        assertFalse("service (background) signaled is normal reclaim",
            AppExitReportPolicy.shouldReport("signaled", SERVICE))
    }

    @Test
    fun `foreground or perceptible SIGKILL is still reported (user-perceived)`() {
        assertTrue("foreground signaled — app died while visible",
            AppExitReportPolicy.shouldReport("signaled", FOREGROUND))
        assertTrue("foreground-service signaled",
            AppExitReportPolicy.shouldReport("signaled", FOREGROUND_SERVICE))
        assertTrue("perceptible signaled — user could see/hear the app",
            AppExitReportPolicy.shouldReport("signaled", PERCEPTIBLE))
    }

    @Test
    fun `os reclaim reasons follow the same visibility rule`() {
        assertFalse(AppExitReportPolicy.shouldReport("low_memory_kill", CACHED))
        assertTrue(AppExitReportPolicy.shouldReport("low_memory_kill", FOREGROUND))
        assertFalse(AppExitReportPolicy.shouldReport("excessive_resource_usage", CACHED))
        assertTrue(AppExitReportPolicy.shouldReport("excessive_resource_usage", PERCEPTIBLE))
    }

    @Test
    fun `app faults always report regardless of importance`() {
        for (imp in listOf(FOREGROUND, CACHED, GONE, 0)) {
            assertTrue("crash@$imp", AppExitReportPolicy.shouldReport("crash", imp))
            assertTrue("native_crash@$imp", AppExitReportPolicy.shouldReport("native_crash", imp))
            assertTrue("anr@$imp", AppExitReportPolicy.shouldReport("anr", imp))
            assertTrue("initialization_failure@$imp", AppExitReportPolicy.shouldReport("initialization_failure", imp))
        }
    }

    @Test
    fun `unknown importance on a reclaim reason is dropped, not guessed as visible`() {
        assertFalse("unknown/unset importance (0) is not user-visible",
            AppExitReportPolicy.shouldReport("signaled", 0))
    }

    @Test
    fun `the visibility boundary is inclusive at 270 and excludes 300 plus`() {
        assertTrue(AppExitReportPolicy.shouldReport("signaled", AppExitReportPolicy.USER_VISIBLE_IMPORTANCE_MAX))
        assertFalse(AppExitReportPolicy.shouldReport("signaled", AppExitReportPolicy.USER_VISIBLE_IMPORTANCE_MAX + 1))
    }
}
