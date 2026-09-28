package com.nuvio.tv.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One row of the `get_app_announcements(p_platform)` RPC. Server-ordered, at most three per call.
 * [kind] is "info" | "update" | "policy"; unknown kinds are kept as-is (display is kind-agnostic).
 */
@Serializable
data class Announcement(
    val id: String,
    val title: String,
    val body: String = "",
    @SerialName("cta_label") val ctaLabel: String? = null,
    @SerialName("cta_url") val ctaUrl: String? = null,
    val kind: String = "info",
    @SerialName("starts_at") val startsAt: String? = null,
)
