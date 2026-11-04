package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubAssetDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAssetPolicyTest {

    private fun apk(name: String) = GitHubAssetDto(name = name, browserDownloadUrl = "https://x/$name", size = 1L)

    // v1.16.7 asset set, verbatim from the release.
    private val release = listOf(
        apk("Tuvora-TV-1.16.7-arm64-v8a.apk"),
        apk("Tuvora-TV-1.16.7-armeabi-v7a.apk"),
        apk("Tuvora-TV-1.16.7.apk"),
        apk("Tuvora-TV-1.16.7-playstore.aab"),
        apk("Tuvora-TV-1.16.7.aab"),
    )

    private fun names(
        assets: List<GitHubAssetDto>,
        abis: List<String>,
        is64BitProcess: Boolean,
    ) = UpdateAssetPolicy.rank(assets, abis, is64BitProcess).map { it.name }

    @Test
    fun `64-bit device gets arm64 first then v7a then universal`() {
        assertEquals(
            listOf("Tuvora-TV-1.16.7-arm64-v8a.apk", "Tuvora-TV-1.16.7-armeabi-v7a.apk", "Tuvora-TV-1.16.7.apk"),
            names(release, listOf("arm64-v8a", "armeabi-v7a", "armeabi"), is64BitProcess = true),
        )
    }

    @Test
    fun `32-bit-only device never gets the arm64 split`() {
        assertEquals(
            listOf("Tuvora-TV-1.16.7-armeabi-v7a.apk", "Tuvora-TV-1.16.7.apk"),
            names(release, listOf("armeabi-v7a", "armeabi"), is64BitProcess = false),
        )
    }

    @Test
    fun `64-bit kernel with 32-bit userland lists arm64 but never gets it`() {
        // abilist advertises arm64-v8a first, yet the running (32-bit) process cannot take 64-bit libs.
        val ranked = names(release, listOf("arm64-v8a", "armeabi-v7a", "armeabi"), is64BitProcess = false)
        assertEquals(
            listOf("Tuvora-TV-1.16.7-armeabi-v7a.apk", "Tuvora-TV-1.16.7.apk"),
            ranked,
        )
        assertTrue(ranked.none { it.contains("arm64") })
    }

    @Test
    fun `missing matching split falls back to universal and excludes wrong-ABI splits`() {
        val onlyArm64AndUniversal = release.filterNot { it.name.contains("armeabi") }
        assertEquals(
            listOf("Tuvora-TV-1.16.7.apk"),
            names(onlyArm64AndUniversal, listOf("armeabi-v7a"), is64BitProcess = false),
        )
    }

    @Test
    fun `only universal present`() {
        assertEquals(
            listOf("Tuvora-TV-1.16.7.apk"),
            names(listOf(apk("Tuvora-TV-1.16.7.apk")), listOf("arm64-v8a"), is64BitProcess = true),
        )
        assertEquals(
            listOf("Tuvora-TV-1.16.7-universal.apk"),
            names(listOf(apk("Tuvora-TV-1.16.7-universal.apk")), listOf("armeabi-v7a"), is64BitProcess = false),
        )
    }

    @Test
    fun `nothing installable yields empty so nothing is downloaded`() {
        assertEquals(
            emptyList<String>(),
            names(listOf(apk("Tuvora-TV-1.16.7-arm64-v8a.apk")), listOf("armeabi-v7a"), is64BitProcess = false),
        )
        assertEquals(emptyList<String>(), names(listOf(apk("notes.txt"), apk("a.aab")), listOf("arm64-v8a"), true))
        assertEquals(emptyList<String>(), names(emptyList(), listOf("arm64-v8a"), true))
    }

    @Test
    fun `x86 does not match x86_64 asset`() {
        val assets = listOf(apk("T-x86_64.apk"), apk("T-x86.apk"))
        assertEquals(listOf("T-x86.apk"), names(assets, listOf("x86"), is64BitProcess = false))
        assertEquals(listOf("T-x86_64.apk", "T-x86.apk"), names(assets, listOf("x86_64", "x86"), is64BitProcess = true))
    }

    @Test
    fun `explicit universal outranks an unmarked apk`() {
        val assets = listOf(apk("T.apk"), apk("T-universal.apk"))
        assertEquals(listOf("T-universal.apk", "T.apk"), names(assets, listOf("arm64-v8a"), true))
    }
}
