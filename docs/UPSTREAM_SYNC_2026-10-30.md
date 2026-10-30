# Upstream sync — 2026-10-30

Merge of `origin/dev` at `47dff1c3eea09983bce80d35caec5a3c06aea87d` (149 non-merge upstream commits, 210 total, merge base `b36db80835`) into Tuvora `org/main` `33b9ce6d4`.

## Player pin (Phase 5 of the playbook)

`ui/screens/player/*`, `player/mpv/*` and the player tests stay fork-pinned: the tree is `org/main`'s, including `fix/restore-upstream-player-fixes` (1d60fd0a2) and the live hardware-decoder PR #46. A three-way auto-merge of the player was tried first and did not compile: upstream's new mpv event handling (`PlayerRuntimeControllerMpvEvents.kt`) calls `mpv.*` directly, which the fork's shadow/ctl-queue design (nuvio-mpv-anr-fix) forbids on the main thread, and its chapter/badge/debug-stats code relies on view and ViewModel APIs the fork never had. Settings that only drive unported features (`PlayerSettingsDataStore`, `PlaybackSubtitleSettings`, `PlaybackSettingsModel/ViewModel`) are pinned with them so no setting is shown that does nothing. The libass AAR (`nuvio-libass-release.aar`) is not taken; `io.github.peerless2012:ass-media:0.4.0` stays.

### Upstream player commits in the delta

| Upstream commit(s) | Result |
| --- | --- |
| a11y: 670d22c88, daefdb1ea, 3c1249728, b2f7aada3, e0f8e5d24, a0992385f, bf8c29136, 77d47bf80, 8bf8d9ee3, e932468ef, 7a308ee15, e6b6c8b0e, 857d6b114, ec4c22cd1 (player parts) | Ported: semantics in Audio/Subtitle selection, subtitle style panel, episodes season tabs, seek bar (name + range), error overlay live region, overlay scaffold tap handling |
| ba0db4900 source navigation during scraping | Ported: StreamSourcesSidePanel + StreamComponents focus handling, `isLoadingSourceStreams` stays true until every addon answered |
| 7eb388a83 Montenegrin to sr-ME | Ported: `LANGUAGE_OVERRIDES`/aliases, overlay rail key, regression test (autosync half skipped, no autosync) |
| b4e47d6d6 + 583eae818 no resume progress for live streams | Ported as `MpvLiveDurationPolicy` (pure, tested) fed from the mpv progress tick; both save paths skip a stream whose mpv duration keeps growing |
| 9099b47ba MKV Tracks after Clusters | Ported (self-contained `core/player/dvmkv`), with its test |
| faf630577 torrent stream kept for external players | Ported (`ExternalPlaybackTracker`, StreamScreenViewModel) |
| CustomDefaultTrackNameProvider codec resolution | Ported (file unchanged by the fork) |
| 0b9269276 decoder released in background, 2a32c1532 USB skips tunneling | Already in the fork (1d60fd0a2) |
| 830e8e83c no libmpv retry on permanent HTTP statuses | Already in the fork (`MpvVodRecoveryPolicy`, `isPermanentHttpStatus`) |
| 7837fb1c2 subtitle lift after controls hide | Skipped: the fork has no subtitle-lift subsystem |
| 7dc661731, cc9bdb19e, d5da6bcaa, 7070a646a, 2bc54ec8f, 6e2e5f961, 205c314ef, 3315ef13b, 8d771aa2c (libass video effect, render mode, overlay geometry) | Skipped: needs the new libass AAR and the ASS render-mode feature |
| 0be4de884, b9332010d, 0aa52c7c4, bb5cf59f1, fc869b665, d18cc55ff, 4b9eb966c, b7a729a50, bce757dfe, 42d98ba85 (mpv media session) | Skipped: feature built on upstream's direct mpv event path |
| 117e188ca, 243f93ed1, 2af7756aa, 2e4f29888, d37b8fe15, 19f550eeb, 98e691331, 9d5e97c91, bb8bd3dc2 (chapter skip intervals) | Skipped: feature (Exo/mpv chapters), depends on the new MPV events and chapter panel |
| 13683564d, be6f2d317, 0c09dbffc, 94ecdb9c2 (player badges) | Skipped: feature |
| 4b8d0f41e autoplay for episodes selected in player | Skipped: ships with the episode options overlay and manual-play event the fork lacks |
| 0d08672f5 dim controls on HDR video, 77f914c19/efd7df263 forced subtitle in MPV (reverted upstream) | Skipped: feature / net zero |
