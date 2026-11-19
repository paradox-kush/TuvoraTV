package com.nuvio.tv.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class InstallSourcePolicyTest {
    @Test
    fun `play store installer buckets to play`() =
        assertEquals("play", "play", InstallSourcePolicy.bucket("com.android.vending"))

    @Test
    fun `amazon appstore buckets to amazon`() =
        assertEquals("amazon", "amazon", InstallSourcePolicy.bucket("com.amazon.venezia"))

    @Test
    fun `system package installers bucket to package_installer`() {
        assertEquals("google installer", "package_installer", InstallSourcePolicy.bucket("com.google.android.packageinstaller"))
        assertEquals("aosp installer", "package_installer", InstallSourcePolicy.bucket("com.android.packageinstaller"))
    }

    @Test
    fun `null and blank bucket to unknown`() {
        assertEquals("null", "unknown", InstallSourcePolicy.bucket(null))
        assertEquals("empty", "unknown", InstallSourcePolicy.bucket(""))
        assertEquals("blank", "unknown", InstallSourcePolicy.bucket("   "))
    }

    @Test
    fun `any other package buckets to other and never leaks the name`() {
        val result = InstallSourcePolicy.bucket("com.example.sideloader")
        assertEquals("other", "other", result)
        assertEquals("downloader app", "other", InstallSourcePolicy.bucket("com.esaba.downloader"))
    }

    @Test
    fun `surrounding whitespace is ignored`() =
        assertEquals("trimmed", "play", InstallSourcePolicy.bucket(" com.android.vending "))

    @Test
    fun `new super properties survive the privacy scrubber unchanged`() {
        val sanitized = PostHogPrivacy.sanitize(
            mapOf("build_flavor" to "store", "install_source" to "package_installer"),
        )
        assertEquals("build_flavor", "store", sanitized["build_flavor"])
        assertEquals("install_source", "package_installer", sanitized["install_source"])
        for (v in listOf("play", "amazon", "package_installer", "other", "unknown")) {
            assertEquals("value $v", v, PostHogPrivacy.sanitize(mapOf("install_source" to v))["install_source"])
        }
        for (v in listOf("store", "full")) {
            assertEquals("value $v", v, PostHogPrivacy.sanitize(mapOf("build_flavor" to v))["build_flavor"])
        }
    }
}
