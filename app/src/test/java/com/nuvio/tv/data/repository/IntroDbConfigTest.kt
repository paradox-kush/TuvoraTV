package com.nuvio.tv.data.repository

import com.nuvio.tv.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Release builds set no INTRODB_API_URL, so the public IntroDB read API must be the default —
 * otherwise SkipIntroRepository.introDbConfigured is false and regular shows get no skip button.
 */
class IntroDbConfigTest {

    @Test
    fun defaultsToThePublicIntroDbReadApi() {
        assertEquals("IntroDB base URL default", "https://api.introdb.app", BuildConfig.INTRODB_API_URL)
    }
}
