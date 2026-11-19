package com.nuvio.tv.core.analytics

import android.content.Context
import android.os.Build

/**
 * Buckets the installer package name into a fixed set of `install_source` analytics values, so
 * retention can be split by install channel. The raw package name is never sent.
 *
 * Buckets: `play`, `amazon`, `package_installer`, `other`, `unknown` (null/blank or unreadable).
 * Pure bucketing in [bucket]; the Android lookup is isolated in [currentBucket].
 */
object InstallSourcePolicy {
    const val PLAY = "play"
    const val AMAZON = "amazon"
    const val PACKAGE_INSTALLER = "package_installer"
    const val OTHER = "other"
    const val UNKNOWN = "unknown"

    fun bucket(installerPackage: String?): String {
        val pkg = installerPackage?.trim()
        if (pkg.isNullOrEmpty()) return UNKNOWN
        return when (pkg) {
            "com.android.vending" -> PLAY
            "com.amazon.venezia" -> AMAZON
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            -> PACKAGE_INSTALLER
            else -> OTHER
        }
    }

    /** Reads this app's installer with the API appropriate to the OS level; any failure is "unknown". */
    fun currentBucket(context: Context): String = bucket(
        runCatching { installerPackage(context) }.getOrNull(),
    )

    @Suppress("DEPRECATION")
    private fun installerPackage(context: Context): String? {
        val pm = context.packageManager
        val pkg = context.packageName
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            pm.getInstallerPackageName(pkg)
        }
    }
}
