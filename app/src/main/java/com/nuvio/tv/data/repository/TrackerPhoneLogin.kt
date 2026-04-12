package com.nuvio.tv.data.repository

/**
 * Shared types for the three tracker phone-pair auth services. Each service
 * (Mal/AniList/Kitsu) returns these from [startPhoneLogin] and [pollPhoneLogin]
 * so the Settings UI can treat them uniformly.
 */
data class TrackerPhoneLoginChallenge(
    val code: String,
    /** Full web URL the phone opens — already contains the short code. Encoded into the QR. */
    val webUrl: String,
    val pollIntervalSeconds: Int,
    val expiresAtEpochMs: Long
)

sealed interface TrackerPhoneLoginPoll {
    data object Pending : TrackerPhoneLoginPoll
    data class Success(val username: String?) : TrackerPhoneLoginPoll
    data class Expired(val reason: String? = null) : TrackerPhoneLoginPoll
    data class Error(val message: String) : TrackerPhoneLoginPoll
}
