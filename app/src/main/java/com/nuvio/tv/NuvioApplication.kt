package com.nuvio.tv

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StrictMode
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.nuvio.tv.core.image.CustomPosterFallbackInterceptor
import com.nuvio.tv.core.image.HardwareBitmapSettingInterceptor
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.gif.GifDecoder
import coil3.gif.AnimatedImageDecoder
import coil3.svg.SvgDecoder
import coil3.request.crossfade
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.bitmapFactoryMaxParallelism

import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import okio.Path.Companion.toOkioPath
import com.nuvio.tv.core.analytics.AppExitReporter
import com.nuvio.tv.core.diagnostics.SentryInitializer
import com.nuvio.tv.core.iptv.refresh.IptvRefreshScheduler
import com.nuvio.tv.core.analytics.PostHogPrivacy
import com.nuvio.tv.core.image.StaleWhileRevalidateCacheStrategy
import com.nuvio.tv.core.runtime.PluginRuntimeHooks
import com.nuvio.tv.core.sync.RealtimeSyncInvalidationService
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.network.NoDelaySocketFactory
import com.nuvio.tv.core.network.imageNetworkFetcherFactory
import com.nuvio.tv.data.local.SentrySettingsDataStore
import com.nuvio.tv.data.mediaserver.ServerUserStateProjection
import com.nuvio.tv.data.mediaserver.plex.PlexArtworkInterceptor
import com.nuvio.tv.data.mediaserver.plex.PlexArtworkSigner
import com.nuvio.tv.data.mediaserver.silo.SiloArtworkInterceptor
import com.nuvio.tv.data.mediaserver.silo.SiloArtworkRefresher
import com.nuvio.tv.data.simkl.SimklAnimeIdPreferenceHolder
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.posthog.PostHog
import com.posthog.PostHogBeforeSend
import com.posthog.logs.PostHogBeforeSendLog
import com.nuvio.tv.ui.screens.settings.MemoryBudget
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@HiltAndroidApp
class NuvioApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    private val analyticsConsentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Field injection runs inside super.onCreate() on the main thread for EVERY process start, including
    // headless JobService/receiver/bind starts with a bindApplication deadline. Eager fields used to build
    // the whole graph there (Supabase/Ktor, Room, every sync service): that is where the background
    // "Timed out while trying to bind" ANRs sat. So heavy dependencies are dagger.Lazy and are started
    // by core/startup/StartupWorkPolicy: BACKGROUND (every process, off Main) or FIRST_UI (first Activity).
    // ArchitectureTest R8 keeps it that way.
    @Inject lateinit var startupSyncService: dagger.Lazy<StartupSyncService>
    @Inject lateinit var appForegroundTracker: com.nuvio.tv.core.lifecycle.AppForegroundTracker
    @Inject lateinit var recEventLogger: dagger.Lazy<com.nuvio.tv.core.rec.RecEventLogger>
    @Inject lateinit var androidTvChannelSyncService: dagger.Lazy<AndroidTvChannelSyncService>
    @Inject lateinit var workerFactory: dagger.Lazy<HiltWorkerFactory>
    @Inject lateinit var iptvRefreshScheduler: dagger.Lazy<IptvRefreshScheduler>
    // Built at process start (BACKGROUND) so LiveEngineMemory is loaded from disk before the first live
    // tune reads it (§3.7); a tune that comes first waits on the same singleton's construction.
    @Inject lateinit var liveEngineMemoryStore: dagger.Lazy<com.nuvio.tv.core.memory.LiveEngineMemoryStore>
    // Built at process start (BACKGROUND) so its accountId->dnsProvider mirror is warm before the first
    // playback needs it (its init observes the account store); otherwise a very-early first live/VOD play
    // could miss its playlist's DoH provider and fall back to system DNS.
    @Inject lateinit var playlistDnsResolver: dagger.Lazy<com.nuvio.tv.core.iptv.dns.PlaylistDnsResolver>
    @Inject lateinit var xtreamTmdbResolver: dagger.Lazy<com.nuvio.tv.core.iptv.match.XtreamTmdbResolver>
    @Inject lateinit var realtimeSyncInvalidationService: dagger.Lazy<RealtimeSyncInvalidationService>
    @Inject lateinit var m3uIdRekeyer: dagger.Lazy<com.nuvio.tv.core.iptv.M3uIdRekeyer>
    @Inject lateinit var sentrySettingsDataStore: SentrySettingsDataStore
    @Inject lateinit var telemetry: com.nuvio.tv.core.telemetry.TelemetryService
    @Inject lateinit var profileDataStoreFactory: com.nuvio.tv.data.local.ProfileDataStoreFactory
    @Inject lateinit var simklAnimeIdPreferenceHolder: dagger.Lazy<SimklAnimeIdPreferenceHolder>
    @Inject lateinit var xtreamIptvSearchProvider: dagger.Lazy<com.nuvio.tv.core.iptv.XtreamIptvSearchProvider>
    @Inject lateinit var serverUserStateProjection: dagger.Lazy<ServerUserStateProjection>
    @Inject lateinit var serverTrust: com.nuvio.tv.data.mediaserver.ServerTrust
    @Inject lateinit var serverRepository: dagger.Lazy<com.nuvio.tv.data.mediaserver.ServerRepository>
    @Inject lateinit var xtreamAccountStore: dagger.Lazy<com.nuvio.tv.data.local.XtreamAccountStore>
    @Inject lateinit var deviceClaimCoordinator: dagger.Lazy<com.nuvio.tv.core.iptv.DeviceClaimCoordinator>
    @Inject lateinit var recordingsPort: com.nuvio.tv.core.recordings.api.RecordingsPort
    @Inject lateinit var calendarComingUpContributor: dagger.Lazy<com.nuvio.tv.core.calendar.CalendarComingUpContributor>

    @Inject lateinit var plexArtworkSigner: dagger.Lazy<PlexArtworkSigner>
    @Inject lateinit var siloArtworkRefresher: dagger.Lazy<SiloArtworkRefresher>

    private val firstUiGate = com.nuvio.tv.core.startup.FirstUiGate()

    // Route WorkManager through Hilt so @HiltWorker workers get their dependencies injected.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory.get())
            .build()

    companion object {
        // Public client-side key — safe to ship in the binary.
        const val POSTHOG_PROJECT_TOKEN = "phc_o824qv3fcxKW9NvF4K6mYKX3rScK5CBQzrSx4RQ5b6ye"
        const val POSTHOG_HOST = "https://us.i.posthog.com"

        /** Main waits this long for the prefetched device profile before registering it late instead. */
        private const val DEVICE_PROFILE_WAIT_MS = 500L
        private const val DEVICE_PROFILE_LATE_WAIT_MS = 30_000L

        /**
         * Shared cookie jar for CloudStream extension HTTP requests.
         * Accessible so the player's OkHttpClient can share cookies
         * obtained during scraping (e.g., session tokens needed for playback).
         */
        val extensionCookieJar: CookieJar = object : CookieJar {
            private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val hostCookies = store[url.host] ?: return emptyList()
                synchronized(hostCookies) {
                    return hostCookies.filter { cookie ->
                        cookie.expiresAt > System.currentTimeMillis()
                    }
                }
            }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val hostCookies = store.getOrPut(url.host) { mutableListOf() }
                synchronized(hostCookies) {
                    cookies.forEach { newCookie ->
                        hostCookies.removeAll { it.name == newCookie.name }
                        hostCookies.add(newCookie)
                    }
                }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        // Startup reads start on their own threads first, so they overlap Hilt injection below instead of
        // running after it on the main thread (consent: StartupConsentPolicy; the main thread only waits
        // for it with a deadline). The two SharedPreferences files read on Main later load meanwhile.
        val consentPrefetch = com.nuvio.tv.core.startup.StartupPrefetch("startup-consent") {
            SentrySettingsDataStore.readEnabledBlocking(this)
        }
        val deviceProfilePrefetch = com.nuvio.tv.core.startup.StartupPrefetch("startup-device-profile") {
            getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            getSharedPreferences("telemetry", Context.MODE_PRIVATE)
            com.nuvio.tv.core.telemetry.DeviceProfile.properties(this)
        }
        // Before super.onCreate(): Hilt creates injected services there, and the budget is read once.
        MemoryBudget.init(this)
        super.onCreate()
        appForegroundTracker.register(this)
        com.nuvio.tv.data.mediaserver.ServerMediaRouting.router = com.nuvio.tv.data.mediaserver.ServerMediaRouter(serverTrust)
        // Unverified TLS for playback is an IPTV-panel-only fallback; a media-server host is never eligible.
        com.nuvio.tv.core.contracts.PlaybackTrustHosts.registerMediaServerHostPredicate("servers") { host ->
            serverRepository.get().uiState.value.connections.any { connection ->
                com.nuvio.tv.core.contracts.PlaybackTlsPolicy.normalize(
                    runCatching { java.net.URI(connection.address).host }.getOrNull()
                ) == host
            }
        }
        // First-run funnel: decide new vs existing install ONCE, here, before anything creates user state. Events wait
        // for the SDK (firstRunSdkReady below); consent is checked when each is sent.
        telemetry.startFirstRunFunnel(
            com.nuvio.tv.core.telemetry.FirstRunExistingInstallProbe.forApp(this, profileDataStoreFactory)::hasUserState,
        )
        // Recordings: alarm receivers and the foreground service are not injected, so they read the port here.
        // Ledger recovery + handing overdue schedules to the service wait for the first visible Activity.
        com.nuvio.tv.core.recordings.api.RecordingsAccess.register(recordingsPort)
        com.nuvio.tv.core.recordings.api.RecordingsAndroid.installAppStartHook(this, recordingsPort)
        // Own sources register into the plural source ports (id predicates, search) before anything asks.
        // The search provider is built on its first use, not here (its graph is the IPTV index + overlay).
        com.nuvio.tv.core.iptv.IptvSourceRegistrations.register(
            com.nuvio.tv.core.iptv.LazyIptvSearchProvider { xtreamIptvSearchProvider.get() }
        )
        // Resolve the memory tier once, before anything sizes a cache from it.
        com.nuvio.tv.core.memory.AndroidMemoryTierProbe.tier(this)
        // Startup journal — foundational to the durable resolver backoff and the crash-loop recovery
        // gate. Initialize before any risky optional work (warm-ups, workers) is scheduled below.
        com.nuvio.tv.core.journal.StartupJournalStore.initialize(this)
        // Recovery gate DECISION ONLY here (Application.onCreate runs for headless worker/JobService
        // starts too, so opening a UI attempt here would falsely accumulate startup failures). This
        // only sweeps + decides safe mode; the UI-launch attempt is opened in MainActivity. In safe
        // mode the resolver warm-up + IPTV refresh worker + channel sync withhold their heavy work.
        com.nuvio.tv.core.journal.StartupJournal.decideStartupMode()
        telemetry.start(BuildConfig.VERSION_NAME)
        val consent = com.nuvio.tv.core.startup.StartupConsentPolicy.initial(
            consentPrefetch.await(com.nuvio.tv.core.startup.StartupConsentPolicy.CONSENT_WAIT_MS)
        )
        // As early as consent is known: a crash during the rest of startup still leaves a stack. The
        // handler goes in before PostHog's, so ours writes the file before its best-effort capture.
        // Not answered in time: telemetry stays at its default (off, nothing written) WITHOUT the wiping
        // applyConsent(false); the consent collector below settles it.
        if (consent.applyConsentNow) telemetry.applyConsent(!consent.optOut)
        com.nuvio.tv.core.telemetry.CrashRecorder.install(this, BuildConfig.VERSION_NAME)
        // Breadcrumbs persist locally so AppExitReporter can attribute a process death on the
        // NEXT launch; their live-event side goes through PostHog and is consent-gated by the
        // SDK like every other capture.
        com.nuvio.tv.core.analytics.Breadcrumbs.crashWriter =
            object : com.nuvio.tv.core.analytics.Breadcrumbs.CrashWriter {
                override fun onScreen(name: String) {
                    // First real screen = interactive shell reached — resets the crash-loop counter.
                    com.nuvio.tv.core.journal.StartupJournal.markInteractiveReached()
                    AppExitReporter.recordRoute(this@NuvioApplication, name)
                }

                override fun onPlaybackStarted(kind: String, engine: String, surface: String) {
                    AppExitReporter.recordPlaybackStarted(this@NuvioApplication, kind, engine, surface)
                    telemetry.onPlaybackStarted()
                }

                override fun onPlaybackStopped() {
                    AppExitReporter.recordPlaybackStopped(this@NuvioApplication)
                    telemetry.onPlaybackStopped()
                }
            }
        PostHogAndroid.setup(
            this,
            PostHogAndroidConfig(
                apiKey = POSTHOG_PROJECT_TOKEN,
                host = POSTHOG_HOST
            ).apply {
                // Capture uncaught exceptions as $exception events (where the app breaks).
                errorTrackingConfig.autoCapture = true
                // Consent is authoritative. Calling optIn/optOut after setup also overrides any
                // consent value persisted by an older PostHog SDK run.
                optOut = consent.optOut
                // This consent is for crash diagnostics, not general product analytics.
                captureApplicationLifecycleEvents = false
                captureScreenViews = false
                sendFeatureFlagEvent = false
                preloadFeatureFlags = false
                surveys = false
                // Deep-link autocapture records Intent.data verbatim, including OAuth code/state.
                captureDeepLinks = false
                // Explicit privacy defaults: remote config must never activate replay, screenshots,
                // logcat collection, tracing headers, or the structured-log upload path.
                sessionReplay = false
                sessionReplayConfig.screenshot = false
                sessionReplayConfig.captureLogcat = false
                tracingHeaders = emptyList()
                logs.addBeforeSend(PostHogBeforeSendLog { null })
                addBeforeSend(PostHogBeforeSend { event ->
                    if (PostHogPrivacy.shouldDropEvent(event.event)) return@PostHogBeforeSend null
                    val sanitized = PostHogPrivacy.sanitize(event.properties.orEmpty())
                    // Error-only sending: routine successes are logged on-device or counted, errors
                    // go out with the trail that led up to them (see core/telemetry).
                    when (val gated = telemetry.gate.process(event.event, sanitized)) {
                        is com.nuvio.tv.core.telemetry.TelemetryGate.Result.Drop -> null
                        is com.nuvio.tv.core.telemetry.TelemetryGate.Result.Pass -> {
                            if (gated.isError) telemetry.flushNow()
                            @Suppress("UNCHECKED_CAST")
                            event.copy(properties = (gated.properties.filterValues { it != null } as Map<String, Any>).toMutableMap())
                        }
                    }
                })
                // Upload queued events quickly after launch: a crash queued by the previous
                // run must ship before the user navigates back into whatever crashed
                // (the default 30s starved uploads during crash-loops).
                flushIntervalSeconds = 10
                // SDK logcat diagnostics in debug builds only.
                debug = BuildConfig.DEBUG
            }
        )
        PostHog.register(PostHogPrivacy.GEOIP_DISABLE_PROPERTY, true)
        val supportCode = com.nuvio.tv.core.telemetry.SupportCodeStore.get(this)
        PostHog.register("support_code", supportCode)
        // Computed on the prefetch thread (a binder call); registered here so early events still carry it.
        // Late (starved box): registered from the prefetch side as soon as it lands.
        val deviceProfile = deviceProfilePrefetch.await(DEVICE_PROFILE_WAIT_MS)
        if (deviceProfile != null) {
            deviceProfile.forEach { (k, v) -> PostHog.register(k, v) }
        } else {
            Thread({
                deviceProfilePrefetch.await(DEVICE_PROFILE_LATE_WAIT_MS)?.forEach { (k, v) -> PostHog.register(k, v) }
            }, "startup-device-profile-late").apply { isDaemon = true }.start()
        }
        // Install-channel split for retention: flavor + bucketed installer (never the raw package).
        PostHog.register("build_flavor", com.nuvio.tv.core.build.AppFeaturePolicy.buildFlavor)
        PostHog.register("install_source", com.nuvio.tv.core.analytics.InstallSourcePolicy.currentBucket(this))
        // Background -> one session_health summary and a durable copy of the on-device log.
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: android.app.Activity) { started++ }
            override fun onActivityStopped(activity: android.app.Activity) {
                if (--started <= 0) {
                    started = 0
                    telemetry.flushHealth()
                    telemetry.flushNow()
                }
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        // first_leave: the WHOLE process leaving the foreground (ProcessLifecycleOwner ON_STOP fires once no Activity is
        // started, debounced so a config change or an Activity-to-Activity hop is not a leave), not one Activity.
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) = telemetry.firstRunAppForegrounded()
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) = telemetry.firstRunAppBackgrounded()
        })
        if (consent.runConsentedWorkNow) {
            PostHog.optIn()
            runConsentedStartupWork(supportCode)
        } else {
            PostHog.optOut()
        }
        // Opt state is final for this start: funnel events held since startFirstRunFunnel can go out now.
        // Consent not known yet: they stay held until the collector's first answer.
        if (consent.releaseFunnelNow) telemetry.firstRunSdkReady()
        analyticsConsentScope.launch {
            var deferredSettled = false
            sentrySettingsDataStore.enabled.distinctUntilChanged().collect { enabled ->
                telemetry.applyConsent(enabled)
                if (enabled) PostHog.optIn() else PostHog.optOut()
                val late = com.nuvio.tv.core.startup.StartupConsentPolicy.onAnswer(consent, enabled, deferredSettled)
                deferredSettled = true
                // A late yes: the startup exit report and crash send run now, one launch late, never lost
                // (exit timestamps only advance after a report).
                if (late.runConsentedWork) runConsentedStartupWork(supportCode)
                if (late.releaseFunnel) telemetry.firstRunSdkReady()
            }
        }
        // Upstream's Sentry: inert without a SENTRY_DSN (the fork's crash reporter is PostHog);
        // kept wired so future upstream merges stay clean.
        // Store builds ship without built-in Usenet: nothing to warm or stop.
        if (com.nuvio.tv.core.build.AppFeaturePolicy.usenetEnabled) registerActivityLifecycleCallbacks(com.nuvio.tv.core.usenet.UsenetAppLifecycle())
        SentryInitializer.start(this, sentrySettingsDataStore)
        PluginRuntimeHooks.onApplicationCreate(this)
        // Process-wide starters: off Main, in every process (StartupWorkPolicy BACKGROUND).
        com.nuvio.tv.core.startup.StartupWorkPolicy.tasksFor(com.nuvio.tv.core.startup.StartupTrigger.PROCESS_START)
            .forEach { task -> analyticsConsentScope.launch { runStartupTaskSuspending(task) } }
        // UI-only starters: once, as the first Activity is created (StartupWorkPolicy FIRST_UI). A headless
        // start (JobService, receiver, bind) never pays for them.
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
                runFirstUiWork()
            }
            // Below API 29 there is no pre-created callback; this one still runs before any frame.
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
                runFirstUiWork()
            }
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        // Load locale synchronously so it's available before Activity.attachBaseContext.
        // SharedPreferences reads are fast (cached in memory after first access).
        val tag = getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            .getString("locale_tag", null)
        LocaleCache.localeTag = tag ?: ""
    }

    /** Consent is ON for this start: the startup exit report, then the previous run's crash file, off Main. */
    private fun runConsentedStartupWork(supportCode: String) {
        AppExitReporter.reportPendingExits(this)
        Thread({
            runCatching { com.nuvio.tv.core.telemetry.DeviceProfile.extended(this).forEach { (k, v) -> PostHog.register(k, v) } }
            runCatching { com.nuvio.tv.core.telemetry.CrashRecorder.sendPending(this, supportCode) }
        }, "pending-crash-send").start()
    }

    /** Main thread, first Activity only. Same work, same thread as before; it just no longer runs headless. */
    private fun runFirstUiWork() {
        if (!firstUiGate.claim()) return
        com.nuvio.tv.core.startup.StartupWorkPolicy.tasksFor(com.nuvio.tv.core.startup.StartupTrigger.FIRST_ACTIVITY)
            .forEach(::runStartupTask)
    }

    private suspend fun runStartupTaskSuspending(task: com.nuvio.tv.core.startup.StartupTask) {
        when (task) {
            com.nuvio.tv.core.startup.StartupTask.IPTV_PANEL_HOSTS ->
                xtreamAccountStore.get().accounts.collect { com.nuvio.tv.core.iptv.IptvPanelHosts.setAccounts(it) }
            else -> runStartupTask(task)
        }
    }

    private fun runStartupTask(task: com.nuvio.tv.core.startup.StartupTask) {
        when (task) {
            com.nuvio.tv.core.startup.StartupTask.IPTV_PANEL_HOSTS -> Unit // collected in runStartupTaskSuspending
            com.nuvio.tv.core.startup.StartupTask.LIVE_ENGINE_MEMORY -> liveEngineMemoryStore.get()
            com.nuvio.tv.core.startup.StartupTask.PLAYLIST_DNS -> playlistDnsResolver.get()
            // Keep the IPTV auto-refresh worker scheduled to the shortest enabled playlist interval.
            com.nuvio.tv.core.startup.StartupTask.IPTV_REFRESH_SCHEDULER -> iptvRefreshScheduler.get().start()
            com.nuvio.tv.core.startup.StartupTask.SIMKL_ANIME_ID_PREFERENCE -> simklAnimeIdPreferenceHolder.get()
            // Built here, not in Application injection: its init starts the sign-in sync pull.
            com.nuvio.tv.core.startup.StartupTask.STARTUP_SYNC -> startupSyncService.get()
            com.nuvio.tv.core.startup.StartupTask.REALTIME_SYNC ->
                if (BuildConfig.REALTIME_SYNC_ENABLED) realtimeSyncInvalidationService.get().start()
            // B205: a setup code redeemed with no account is claimed when a real account signs in (and at app start).
            com.nuvio.tv.core.startup.StartupTask.DEVICE_CLAIM -> deviceClaimCoordinator.get().start(analyticsConsentScope)
            // Restores anything a previous process left unsent and starts the flush timer. Guarded
            // because nothing about recommendation telemetry may ever prevent the app from starting.
            com.nuvio.tv.core.startup.StartupTask.REC_EVENT_LOGGER -> { runCatching { recEventLogger.get().start() } }
            com.nuvio.tv.core.startup.StartupTask.TV_CHANNEL_SYNC -> androidTvChannelSyncService.get().start()
            // B64: moves saved M3U refs onto the login-free ids once each catalog is rebuilt under them.
            com.nuvio.tv.core.startup.StartupTask.M3U_REKEY -> { runCatching { m3uIdRekeyer.get().start() } }
            // Warm the Xtream match indexes off the critical path so the first play/search
            // doesn't pay the full-catalog download (minutes on budget boxes).
            com.nuvio.tv.core.startup.StartupTask.XTREAM_MATCH_WARMUP -> xtreamTmdbResolver.get().warmUpAll()
            com.nuvio.tv.core.startup.StartupTask.SERVER_USER_STATE -> serverUserStateProjection.get().start()
            // Calendar's optional Home "Coming up" row (off by default); registered before Home first collects changes.
            com.nuvio.tv.core.startup.StartupTask.CALENDAR_HOME_ROW ->
                com.nuvio.tv.core.contracts.HomeSectionContributorRegistry.register(calendarComingUpContributor.get())
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Since Android 14 only these two constants fire (the rest died in 14, formally
        // deprecated in 15) — both mean the UI left the screen: drop every registered
        // cache. Truth is on disk; the windows repopulate on return.
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            com.nuvio.tv.core.memory.AppMemory.trimCaches()
        }
        AppExitReporter.recordMemorySnapshot(this, "trim_memory", level)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun newImageLoader(context: android.content.Context): ImageLoader {
        val imageBaseClient by lazy {
            val imageDispatcher = okhttp3.Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 16
            }
            OkHttpClient.Builder()
                .dispatcher(imageDispatcher)
                .dns(IPv4FirstDns())
                .socketFactory(NoDelaySocketFactory)
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                .addInterceptor(PlexArtworkInterceptor { if (::plexArtworkSigner.isInitialized) plexArtworkSigner.get() else null })
                .addNetworkInterceptor(PlexArtworkInterceptor.Token { if (::plexArtworkSigner.isInitialized) plexArtworkSigner.get() else null })
                .addInterceptor(SiloArtworkInterceptor({ if (::siloArtworkRefresher.isInitialized) siloArtworkRefresher.get() else null }))
                .addInterceptor { chain ->
                    try {
                        chain.proceed(chain.request())
                    } catch (e: java.net.SocketTimeoutException) {
                        chain.withConnectTimeout(3, TimeUnit.SECONDS)
                            .withReadTimeout(4, TimeUnit.SECONDS)
                            .proceed(chain.request())
                    }
                }
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor(com.nuvio.tv.core.diagnostics.HttpTraceInterceptor("IMG"))
                .build()
        }
        // A media-server host the user pinned (self-signed, trust on first use) is fetched through a client
        // that trusts exactly that pin; every other host keeps normal validation. Derived from the base client,
        // so the dispatcher, timeouts and trace interceptor carry over; revalidation shares this client.
        val imageOkHttpClient by lazy {
            imageBaseClient.newBuilder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val pinned = com.nuvio.tv.data.mediaserver.ServerMediaRouting.router
                        ?.pinnedClient(request.url.toString(), imageBaseClient)
                    if (pinned != null) pinned.newCall(request).execute() else chain.proceed(request)
                }
                .build()
        }

        val imageLoaderRef: () -> ImageLoader = { SingletonImageLoader.get(this) }

        return ImageLoader.Builder(this)
            .components {
                // Upstream hardware-bitmap setting; prefs read per request (no injection on the startup path).
                val imagePerformancePreferences = com.nuvio.tv.data.local.ImagePerformancePreferences(this@NuvioApplication)
                add(HardwareBitmapSettingInterceptor { imagePerformancePreferences.hardwareBitmapsEnabled })
                add(CustomPosterFallbackInterceptor())
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory())
                // Lean image client (no HTTP cache — Coil's DiskCache owns caching, no cookie jar) with
                // bounded dispatcher + timeouts; stale-while-revalidate so dynamic posters refresh.
                add(
                    imageNetworkFetcherFactory(
                        callFactory = { imageOkHttpClient },
                        cacheStrategy = {
                            StaleWhileRevalidateCacheStrategy(
                                revalidationClient = { imageOkHttpClient },
                                imageLoaderProvider = imageLoaderRef,
                            )
                        },
                    )
                )
            }
            .memoryCache {
                // 0.33 of the largeHeap memory class allowed a ~127MB bitmap cache,
                // which helped push 2GB boxes into swap-thrash. Cap it from the memory
                // tier (LOW 32 / MID 64 / HIGH 96 MiB) and register it in the budget
                // registry so the trim hooks can drop it; posters are RGB565+inexact
                // so even the LOW cap fits several screens' worth.
                val tier = com.nuvio.tv.core.memory.AndroidMemoryTierProbe.tier(this)
                val cap = com.nuvio.tv.core.memory.MemoryTierPolicy.imageMemoryCacheBytes(tier)
                val cache = MemoryCache.Builder().maxSizeBytes(cap).build()
                com.nuvio.tv.core.memory.AppMemory.registry
                    .register("image_memory_cache", cap, priority = 0) { cache.clear() }
                cache
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .precision(coil3.size.Precision.INEXACT)
            .allowHardware(true)
            .allowRgb565(true)
            .bitmapFactoryMaxParallelism(2)
            .build()
    }
}
