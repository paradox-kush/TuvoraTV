package com.nuvio.tv.updater

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.updater.model.AppUpdate
import com.nuvio.tv.updater.model.AppUpdateAsset
import kotlinx.coroutines.flow.first
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/** B175: GitHub rate-limited us; nothing is asked before [resetAtEpochSeconds]. */
internal class UpdateRateLimitedException(val resetAtEpochSeconds: Long) :
    IllegalStateException("GitHub is rate limiting update checks until $resetAtEpochSeconds")

/** B175: a 403 with no rate-limit evidence (proxy, carrier or policy block). */
internal class UpdateForbiddenException : IllegalStateException("GitHub refused the update check (403)")

internal class NoEligibleUpdateException(channel: UpdateChannel) :
    IllegalStateException("No compatible APK release found for ${channel.storedValue} channel")

@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubReleaseApi: GitHubReleaseApi,
    private val updatePreferences: UpdatePreferences
) {

    suspend fun getLatestUpdate(channel: UpdateChannel): Result<AppUpdate> {
        return runCatching {
            // B175: a known limit is respected by every caller (in-app check, periodic job).
            val nowSeconds = System.currentTimeMillis() / 1000L
            val blockedUntil = updatePreferences.rateLimitedUntilEpochSeconds.first()
            if (!UpdaterHttpErrorPolicy.mayCheck(blockedUntil, nowSeconds)) {
                throw UpdateRateLimitedException(blockedUntil!!)
            }
            val owner = BuildConfig.GITHUB_OWNER
            val repo = BuildConfig.GITHUB_REPO

            val releases = when (channel) {
                UpdateChannel.STABLE -> {
                    val response = gitHubReleaseApi.getLatestRelease(owner = owner, repo = repo)
                    if (!response.isSuccessful) throw failureFor(response)
                    listOf(response.body() ?: error("Empty GitHub release response"))
                }
                UpdateChannel.BETA -> {
                    val response = gitHubReleaseApi.getReleases(owner = owner, repo = repo)
                    if (!response.isSuccessful) throw failureFor(response)
                    response.body() ?: error("Empty GitHub release response")
                }
            }
            // A clean answer lifts any stale limit.
            if (blockedUntil != null) updatePreferences.setRateLimitedUntilEpochSeconds(null)
            val releaseWithAsset = ReleaseSelector
                .eligibleReleases(releases, channel)
                .firstNotNullOfOrNull { release ->
                    AbiSelector.rankApkAssets(release.assets).takeIf { it.isNotEmpty() }?.let { ranked ->
                        release to ranked
                    }
                }
                ?: throw NoEligibleUpdateException(channel)
            val (dto, rankedAssets) = releaseWithAsset
            val asset = rankedAssets.first()

            val tag = dto.tagName?.takeIf { it.isNotBlank() }
                ?: dto.name?.takeIf { it.isNotBlank() }
                ?: error("Release has no tag/name")

            AppUpdate(
                tag = tag,
                title = dto.name?.takeIf { it.isNotBlank() } ?: tag,
                notes = dto.body.orEmpty(),
                releaseUrl = dto.htmlUrl,
                assetName = asset.name,
                assetUrl = asset.browserDownloadUrl,
                assetSizeBytes = asset.size,
                fallbackAssets = rankedAssets.drop(1).map {
                    AppUpdateAsset(name = it.name, url = it.browserDownloadUrl, sizeBytes = it.size)
                }
            )
        }
    }

    /** B175: keeps the rate-limit evidence (headers + body) instead of a bare status code. */
    private suspend fun failureFor(response: Response<*>): Exception {
        val headers = response.headers().toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
        val body = runCatching { response.errorBody()?.string().orEmpty() }.getOrDefault("")
        val now = System.currentTimeMillis() / 1000L
        return when (val outcome = UpdaterHttpErrorPolicy.classify(response.code(), headers, body, now)) {
            is UpdaterHttpErrorPolicy.Outcome.RateLimited -> {
                updatePreferences.setRateLimitedUntilEpochSeconds(outcome.resetAtEpochSeconds)
                UpdateRateLimitedException(outcome.resetAtEpochSeconds)
            }
            UpdaterHttpErrorPolicy.Outcome.Forbidden -> UpdateForbiddenException()
            is UpdaterHttpErrorPolicy.Outcome.Other -> IllegalStateException("GitHub API error: ${response.code()}")
        }
    }
}
