package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubAssetDto

/**
 * Decides which release APKs this device may install, best first (B207).
 *
 * Pure so it tests without Android. Rules:
 *  - ABI splits matching the device come first, in the device's preference order.
 *  - A 32-bit app process never gets a 64-bit split, even when SUPPORTED_ABIS lists arm64-v8a
 *    (64-bit kernel with 32-bit userland: the abilist is the CPU's, the process is what loads libs).
 *  - Then the universal APK, then any APK that names no ABI (the plain `Tuvora-TV-x.y.z.apk`).
 *  - A split for an ABI the device cannot use is never returned, so it is never downloaded.
 * The caller tries candidates in order and moves to the next when a download is incomplete or
 * Android cannot parse it.
 */
internal object UpdateAssetPolicy {

    private val knownAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    fun rank(
        assets: List<GitHubAssetDto>,
        supportedAbis: List<String>,
        is64BitProcess: Boolean,
    ): List<GitHubAssetDto> {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        val usableAbis = supportedAbis.filter { is64BitProcess || !is64BitAbi(it) }

        val ranked = LinkedHashSet<GitHubAssetDto>()
        for (abi in usableAbis) {
            apks.filter { mentions(it.name, abi) }.forEach { ranked += it }
        }
        apks.filter { it.name.contains("universal", ignoreCase = true) && mentionsNoAbi(it.name) }
            .forEach { ranked += it }
        apks.filter { mentionsNoAbi(it.name) }.forEach { ranked += it }
        return ranked.toList()
    }

    private fun is64BitAbi(abi: String) = abi.contains("64")

    private fun mentionsNoAbi(name: String) = knownAbis.none { mentions(name, it) }

    // "x86" must not match inside "x86_64".
    private fun mentions(name: String, abi: String): Boolean =
        Regex("(?<![a-z0-9])${Regex.escape(abi)}(?![a-z0-9_])", RegexOption.IGNORE_CASE).containsMatchIn(name)
}
