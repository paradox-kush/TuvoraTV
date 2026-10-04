# ── B116: strip VERBOSE/DEBUG logging from SHIPPED release builds only ─────────
# Release builds are minified by R8 with proguard-android-optimize.txt (optimization ON, which
# -assumenosideeffects requires). Debug-level diagnostics carry the most request detail (URLs,
# ids) and have no consumer in production: crash telemetry is AppExitReporter (ApplicationExitInfo)
# + PostHog/Sentry exception capture, none of which read logcat (PostHog captureLogcat=false, Sentry
# logcat instrumentation disabled). INFO/WARN/ERROR stay — and stay redacted via LogRedaction.
#
# Applied to `release` ONLY: the `cert`/`benchmark` validation builds reset their proguard files so
# their IS_DEBUG_BUILD-gated Log.d diagnostics (CleanPlaybackDiag) keep working on minified bits.
# Explicit method signatures, never a `*` wildcard (that would also strip isLoggable etc.).
# Ref: developer.android.com/topic/performance/app-optimization/additional-rule-types
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
