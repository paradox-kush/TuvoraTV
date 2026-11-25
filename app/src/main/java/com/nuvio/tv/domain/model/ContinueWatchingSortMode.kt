package com.nuvio.tv.domain.model

enum class ContinueWatchingSortMode {
    /** "Most recent first": recency only. */
    DEFAULT,
    STREAMING_STYLE,
    SPLIT_UPCOMING,

    /** "Pick up where I left off": half-watched first, then next episodes (see ResumeRankPolicy). */
    RESUME_FIRST
}
