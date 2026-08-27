# Legacy `PlayerSettings` disposition

Date: 2026-08-26
Scope: NuvioTV clean-slate playback settings, WP2

This is a field-for-field inventory of the 70 properties in the legacy `PlayerSettings` data
class. Import is deliberately one-way and read-only: the clean repository accepts a detached
snapshot, writes its own versioned document once, and never changes legacy storage.

## Disposition vocabulary

- **CORE**: engine-neutral playback intent represented in the clean schema.
- **EXPERT**: valid but engine/device-specific control, quarantined from the core contract.
- **BEHAVIOR/UI**: presentation or product behavior; it must not configure an engine directly.
- **OUTSIDE**: stream selection, provider, external-player, cache, or telemetry workflow owned
  outside the playback engine contract.
- **RETIRE**: implementation switch or unsafe preset that policy/capability resolution replaces.
- **GAP**: legitimate user intent not yet present in the approved core models; do not guess a
  substitute during import.

## Complete inventory

| # | Legacy field | Class | Clean disposition |
|---:|---|---|---|
| 1 | `playerPreference` | OUTSIDE | External-player routing remains outside the internal graph resolver; import only notes non-internal values. |
| 2 | `internalPlayerEngine` | CORE | Imported to `engine` (`EXOPLAYER` → `MEDIA3`, `MVP_PLAYER` → `LIBMPV`). |
| 3 | `autoSwitchInternalPlayerOnError` | CORE | Imported to `automaticFallback`. |
| 4 | `useLibass` | CORE | Imported as subtitle fidelity intent (`FULL`/`COMPATIBLE`), not as a library toggle. |
| 5 | `libassRenderType` | EXPERT | Quarantine until an approved subtitle render-path preference exists. |
| 6 | `subtitleStyle` | GAP | Legitimate presentation intent; requires a typed engine-neutral style model. |
| 7 | `bufferSettings` | CORE | Valid values import to `CUSTOM`; malformed combinations fall back to `RECOMMENDED`. |
| 8 | `decoderPriority` | RETIRE | Media3 extension-renderer implementation detail; graph policy owns decoder ranking. |
| 9 | `downmixEnabled` | CORE | Imported to `audio.downmixToStereo`. |
| 10 | `audioOutputChannels` | GAP | Needs typed channel-layout intent; must not be inferred as passthrough. |
| 11 | `maintainOriginalAudioOnDownmix` | GAP | Legacy mixer behavior is not volume normalization; needs an explicit downmix-preservation contract. |
| 12 | `tunnelingEnabled` | EXPERT | Device/decoder-specific experimental control; not a core default. |
| 13 | `forceOpticalPassthrough` | CORE | Imported to `audio.output=PASSTHROUGH`. |
| 14 | `skipSilence` | CORE | Imported to `audio.skipSilence`. |
| 15 | `audioAmplificationDb` | GAP | Needs a bounded gain preference and conflict policy with passthrough. |
| 16 | `centerMixLevelDb` | GAP | Needs a bounded downmix-center preference. |
| 17 | `persistAudioAmplification` | BEHAVIOR/UI | Persistence behavior for the future gain preference. |
| 18 | `rememberAudioDelayPerDevice` | BEHAVIOR/UI | Per-device persistence scope, not an engine option. |
| 19 | `preferredAudioLanguage` | CORE | Imported when it is a concrete language, not a legacy sentinel. |
| 20 | `secondaryPreferredAudioLanguage` | GAP | Core currently supports one preferred audio language. |
| 21 | `loadingOverlayEnabled` | BEHAVIOR/UI | UI ownership; no engine call. |
| 22 | `showPlayerLoadingStatus` | BEHAVIOR/UI | Imported to `behavior.showStatusIndicators`. |
| 23 | `playbackIssueReportsEnabled` | OUTSIDE | Privacy/telemetry consent, outside playback policy. |
| 24 | `pauseOverlayEnabled` | BEHAVIOR/UI | UI ownership. |
| 25 | `osdClockEnabled` | BEHAVIOR/UI | UI ownership. |
| 26 | `skipIntroEnabled` | BEHAVIOR/UI | Product behavior; no graph configuration. |
| 27 | `parentalGuideEnabled` | BEHAVIOR/UI | Product/UI behavior. |
| 28 | `autoSkipSegmentTypes` | BEHAVIOR/UI | Product behavior; engine receives only resulting seek commands. |
| 29 | `dv5ToDv81Enabled` | EXPERT | Import records a warning only; a transform flag is not treated as HDR output intent. |
| 30 | `dv7ToDv81PreserveMappingEnabled` | EXPERT | Same quarantine as other libdovi transforms. |
| 31 | `dv7HandlingMode` | EXPERT | Bitstream transform is not inferred as HDR output intent and remains quarantined. |
| 32 | `dv7LibdoviModeOverride` | EXPERT | Exact libdovi algorithm override; never a default-policy input. |
| 33 | `stripHdr10PlusSei` | EXPERT | Bitstream mutation; requires explicit safety/availability modeling before exposure. |
| 34 | `mpvHardwareDecodeMode` | EXPERT | Coarsely imports decoder intent and quarantined `mpvOutput`; exact mpv spelling is not persisted. |
| 35 | `frameRateMatchingMode` | CORE | Imported to `display.frameRate`; start/stop variants become committed-playback AFR. |
| 36 | `resolutionMatchingEnabled` | CORE | Imported to `display.resolutionMatching`. |
| 37 | `streamAutoPlayMode` | OUTSIDE | Stream-selection workflow, before playback request resolution. |
| 38 | `streamAutoPlaySource` | OUTSIDE | Stream-selection workflow. |
| 39 | `streamAutoPlaySelectedAddons` | OUTSIDE | Provider/add-on selection and potentially identifying data; never playback telemetry. |
| 40 | `streamAutoPlaySelectedPlugins` | OUTSIDE | Provider/plugin selection. |
| 41 | `streamAutoPlayRegex` | OUTSIDE | Stream ranking/filtering. |
| 42 | `streamAutoPlayNextEpisodeEnabled` | BEHAVIOR/UI | Imported to `behavior.autoplayNext`. |
| 43 | `streamAutoPlayPreferBingeGroupForNextEpisode` | OUTSIDE | Stream-selection workflow. |
| 44 | `streamAutoPlayReuseBingeGroup` | OUTSIDE | Stream-selection/provider workflow. |
| 45 | `streamAutoPlayTimeoutSeconds` | OUTSIDE | Stream-selection timeout, not network/player timeout. |
| 46 | `stillWatchingEnabled` | BEHAVIOR/UI | Imported to `behavior.stillWatchingEnabled`. |
| 47 | `stillWatchingEpisodeThreshold` | BEHAVIOR/UI | Product behavior; clean behavior schema currently retains only enablement. |
| 48 | `nextEpisodeThresholdMode` | BEHAVIOR/UI | Product behavior. |
| 49 | `nextEpisodeThresholdPercent` | BEHAVIOR/UI | Product behavior. |
| 50 | `nextEpisodeThresholdMinutesBeforeEnd` | BEHAVIOR/UI | Product behavior. |
| 51 | `streamReuseLastLinkEnabled` | OUTSIDE | Request/provider cache workflow. |
| 52 | `streamReuseLastLinkCacheHours` | OUTSIDE | Request/provider cache workflow. |
| 53 | `externalPlayerForwardSubtitles` | OUTSIDE | External-player handoff. |
| 54 | `externalPlayerSendSkipSegments` | OUTSIDE | External-player handoff. |
| 55 | `subtitleOrganizationMode` | GAP | Subtitle track presentation/selection intent; requires an engine-neutral model. |
| 56 | `bufferEngineEnabled` | RETIRE | Legacy subsystem switch; buffering policy selects one implementation contract. |
| 57 | `parallelNetworkEnabled` | RETIRE | Legacy subsystem switch; graph/network policy owns eligibility. |
| 58 | `bufferBudgetManaged` | RETIRE | Resource safety is always a hard constraint, never user-disableable. |
| 59 | `allowLargeTargetBuffer` | RETIRE | Unsafe resource-budget bypass; clean resolver clamps custom buffers. |
| 60 | `vodCacheEnabled` | OUTSIDE | Cache/storage policy; not live playback graph preference. |
| 61 | `vodCacheSizeMode` | OUTSIDE | Cache/storage policy. |
| 62 | `vodCacheSizeMb` | OUTSIDE | Cache/storage budget. |
| 63 | `useParallelConnections` | EXPERT | Network transport experiment; needs server/provider safety constraints. |
| 64 | `parallelConnectionCount` | EXPERT | Bounded transport experiment, meaningful only when parallel fetch is eligible. |
| 65 | `parallelChunkSizeKb` | EXPERT | Transport implementation tuning. |
| 66 | `enableHttp2` | EXPERT | Transport protocol experiment subject to endpoint capability. |
| 67 | `addonSubtitleStartupMode` | GAP | Subtitle startup-selection intent; outside current one-language core model. |
| 68 | `enableBufferLogs` | OUTSIDE | Diagnostic verbosity; never changes playback policy. |
| 69 | `resizeMode` | GAP | Legitimate display scaling intent needs a typed enum and apply-in-place impact. |
| 70 | `nuvioPerformanceModeEnabled` | RETIRE | Reactive preset/bundle replaced by explicit intent plus runtime capability policy. |

Count check: **70/70 fields classified**.

## Minimal approved-core gaps

The clean WP2 code intentionally does not add these to `PlaybackModels.kt`. Before their settings UI
can be ported without semantic loss, the approved core needs typed contracts for:

1. subtitle style, organization, and startup-selection intent;
2. audio channel layout, bounded gain/center mix, secondary language, and persistence scope;
3. display resize/scaling mode;
4. an explicit expert extension contract if tunneling, Dolby bitstream transforms, or parallel
   transport controls remain user-facing.

All other non-imported fields are UI/product/provider/cache/telemetry concerns or retired
implementation switches, not missing engine preferences.

## Audit remediation evidence boundary

- The production clean store is `clean_playback_preferences_v1`, separate from legacy storage.
  A strict serializer writes one atomic document per profile. Schema migration is persisted once
  on read with one revision increment; no legacy key is read or changed by that store.
- `RequestSummary.hasDrm` and `StreamEvidence.drmScheme` establish DRM, but neither contract says
  that secure decoder/surface output is required. The resolver therefore requires Media3 for DRM
  without guessing secure-output eligibility. A future secure-output constraint needs an explicit
  evidence fact in the approved core request/evidence contract.
- `StreamEvidence` has no HDR transfer characteristic or compatible HDR10 base-layer evidence.
  When Dolby Vision output is requested but unavailable, effective output remains `AUTO`; the
  resolver never invents HDR10 conversion from display capability alone. A typed source-HDR and
  fallback-layer fact is required before that fallback can be selected.
- Compatibility history is accepted only for an exact eligible graph and stable runtime
  fingerprint. Network, authorization/provider-limit, and TLS failures are excluded again inside
  the pure resolver as defense in depth.
- Resolution is field-granular: all 24 persisted clean preference fields (including the
  quarantined mpv output field) expose requested value, effective value, authority, availability,
  reason/conflicts, and runtime impact. Automatic fallback and software-fallback enablement are
  `NEXT_SESSION_ONLY`; track/UI controls are apply-in-place; buffer construction rebuilds the
  graph; decoder/output/fidelity/display-path changes reselect it.
