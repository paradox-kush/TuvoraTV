package com.nuvio.tv.updater.model

data class AppUpdate(
    val tag: String,
    val title: String,
    val notes: String,
    val releaseUrl: String?,
    val assetName: String,
    val assetUrl: String,
    val assetSizeBytes: Long?,
    /** Next-best installable APKs, tried in order if the chosen one is incomplete or unparsable (B207). */
    val fallbackAssets: List<AppUpdateAsset> = emptyList()
)

data class AppUpdateAsset(
    val name: String,
    val url: String,
    val sizeBytes: Long?
)
