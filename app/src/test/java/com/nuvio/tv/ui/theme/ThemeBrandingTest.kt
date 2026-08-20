package com.nuvio.tv.ui.theme

import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeBrandingTest {
    /**
     * Tuvora pins the brand wordmark to a single Tuvora asset across every theme. Upstream shipped
     * per-theme Nuvio-branded wordmarks; the fork deliberately dropped them so no Nuvio branding can
     * ever render. This guards that decision — a reintroduced per-theme variant fails here.
     */
    @Test
    fun everyThemeUsesTheTuvoraWordmark() {
        AppTheme.entries.forEach { theme ->
            assertEquals(R.drawable.app_logo_wordmark, theme.brandWordmarkResource)
        }
    }
}
