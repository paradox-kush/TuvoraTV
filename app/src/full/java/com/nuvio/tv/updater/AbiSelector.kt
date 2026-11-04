package com.nuvio.tv.updater

import android.os.Build
import android.os.Process
import com.nuvio.tv.data.remote.dto.GitHubAssetDto

internal object AbiSelector {

    /** Installable APKs for this device, best first; empty when none is safe to download. */
    fun rankApkAssets(assets: List<GitHubAssetDto>): List<GitHubAssetDto> =
        UpdateAssetPolicy.rank(
            assets = assets,
            supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            is64BitProcess = Process.is64Bit(),
        )
}
