package com.nuvio.tv

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StrictMode
import coil3.ImageLoader
import coil3.SingletonImageLoader
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
import com.nuvio.tv.core.runtime.PluginRuntimeHooks
import com.nuvio.tv.core.sync.RealtimeSyncInvalidationService
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.data.local.SentrySettingsDataStore
import com.nuvio.tv.data.simkl.SimklAnimeIdPreferenceHolder
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.posthog.PostHog
import com.posthog.PostHogBeforeSend
import com.posthog.logs.PostHogBeforeSendLog
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@HiltAndroidApp
class NuvioApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    private val analyticsConsentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Inject lateinit var startupSyncService: StartupSyncService
    @Inject lateinit var recEventLogger: com.nuvio.tv.core.rec.RecEventLogger
    @Inject lateinit var androidTvChannelSyncService: AndroidTvChannelSyncService
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var iptvRefreshScheduler: IptvRefreshScheduler
    // Eagerly created so its accountId->dnsProvider mirror is warm before the first playback needs
    // it (its init observes the account store); otherwise a very-early first live/VOD play could
    // miss its playlist's DoH provider and fall back to system DNS.
    @Inject lateinit var playlistDnsResolver: com.nuvio.tv.core.iptv.dns.PlaylistDnsResolver
    @Inject lateinit var xtreamTmdbResolver: com.nuvio.tv.core.iptv.match.XtreamTmdbResolver
    @Inject lateinit var realtimeSyncInvalidationService: RealtimeSyncInvalidationService
    @Inject lateinit var sentrySettingsDataStore: SentrySettingsDataStore
    @Inject lateinit var simklAnimeIdPreferenceHolder: SimklAnimeIdPreferenceHolder

    // Route WorkManager through Hilt so @HiltWorker workers get their dependencies injected.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    companion object {
        // Public client-side key — safe to ship in the binary.
        const val POSTHOG_PROJECT_TOKEN = "phc_o824qv3fcxKW9NvF4K6mYKX3rScK5CBQzrSx4RQ5b6ye"
        const val POSTHOG_HOST = "https://us.i.posthog.com"

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

    override fun onCreate() {
        super.onCreate()
        // Resolve the memory tier once, before anything sizes a cache from it.
        com.nuvio.tv.core.memory.AndroidMemoryTierProbe.tier(this)
        val crashReportsEnabled = runBlocking(Dispatchers.IO) {
            sentrySettingsDataStore.isEnabled()
        }
        // Breadcrumbs persist locally so AppExitReporter can attribute a process death on the
        // NEXT launch; their live-event side goes through PostHog and is consent-gated by the
        // SDK like every other capture.
        com.nuvio.tv.core.analytics.Breadcrumbs.crashWriter =
            object : com.nuvio.tv.core.analytics.Breadcrumbs.CrashWriter {
                override fun onScreen(name: String) {
                    AppExitReporter.recordRoute(this@NuvioApplication, name)
                }

                override fun onPlaybackStarted(kind: String, engine: String, surface: String) {
                    AppExitReporter.recordPlaybackStarted(this@NuvioApplication, kind, engine, surface)
                }

                override fun onPlaybackStopped() {
                    AppExitReporter.recordPlaybackStopped(this@NuvioApplication)
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
                optOut = !crashReportsEnabled
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
                    if (PostHogPrivacy.shouldDropEvent(event.event)) null
                    else event.copy(
                        properties = PostHogPrivacy.sanitize(event.properties.orEmpty()).toMutableMap(),
                    )
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
        if (crashReportsEnabled) {
            PostHog.optIn()
            AppExitReporter.reportPendingExits(this)
        } else {
            PostHog.optOut()
        }
        analyticsConsentScope.launch {
            sentrySettingsDataStore.enabled.distinctUntilChanged().collect { enabled ->
                if (enabled) PostHog.optIn() else PostHog.optOut()
            }
        }
        // Upstream's Sentry: inert without a SENTRY_DSN (the fork's crash reporter is PostHog);
        // kept wired so future upstream merges stay clean.
        SentryInitializer.start(this, sentrySettingsDataStore)
        PluginRuntimeHooks.onApplicationCreate(this)
        // Restores anything a previous process left unsent and starts the flush timer. Guarded
        // because nothing about recommendation telemetry may ever prevent the app from starting.
        runCatching { recEventLogger.start() }
        androidTvChannelSyncService.start()
        if (BuildConfig.REALTIME_SYNC_ENABLED) {
            realtimeSyncInvalidationService.start()
        }
        // Keep the IPTV auto-refresh worker scheduled to the shortest enabled playlist interval.
        iptvRefreshScheduler.start()
        // Warm the Xtream match indexes off the critical path so the first play/search
        // doesn't pay the full-catalog download (minutes on budget boxes).
        xtreamTmdbResolver.warmUpAll()
        // Load locale synchronously so it's available before Activity.attachBaseContext.
        // SharedPreferences reads are fast (cached in memory after first access).
        val tag = getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            .getString("locale_tag", null)
        LocaleCache.localeTag = tag ?: ""
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

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory())
                // Use a lean OkHttpClient for image fetching — no HTTP cache (Coil's own
                // DiskCache handles caching), no cookie jar, no logging interceptors.
                add(
                    coil3.network.okhttp.OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient.Builder()
                                .dns(IPv4FirstDns())
                                .followRedirects(true)
                                .followSslRedirects(true)
                                .build()
                        }
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
