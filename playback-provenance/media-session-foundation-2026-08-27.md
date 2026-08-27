# Clean MediaSession foundation — 2026-08-27

## Scope

This change adds a detached Android system-control projection for the clean TV player. It does not
wire a production route and does not construct or reference either playback engine. Both Media3 and
libmpv sessions will use the same `PlaybackSessionController` boundary when production cutover lands.

V1 is intentionally limited to live play, pause, and stop. Seeking, queue editing, playback speed,
volume, video Surface control, and full VOD/catch-up MediaSession behavior remain out of scope until
the clean core owns the corresponding engine-neutral commands and position rules.

## Locally pinned API grounding

The implementation was checked against the artifacts that the TV app actually builds with:

- `gradle/libs.versions.toml`: Media3 `1.11.0`.
- `app/libs/lib-common-release.aar`: SHA-256
  `210854ff01a54a9913784d46f4e88f43acd8abd0901d3c9f35c1edf9bd469f08`.
- cached `androidx.media3:media3-session:1.11.0`: SHA-256
  `dc3814140816f46c391cd320412419b4d813ba21a0857d985b5264ef0128deee`.

Local `javap` inspection confirmed the pinned `SimpleBasePlayer` contract used here:

- immutable state is returned by `getState()` and refreshed with `invalidateState()`;
- `handleSetPlayWhenReady`, `handleStop`, and `handleRelease` return `ListenableFuture`;
- `State.Builder` owns available commands, playlist metadata, position, loading, playback state, and
  play intent;
- `MediaSession.Builder(Context, Player)` accepts the facade without an ExoPlayer.

## Ownership and privacy decisions

- `CleanMediaSessionPlayer` observes only secret-free `PlaybackSnapshot` and sends controls only to
  `PlaybackSessionController`.
- The advertised command set contains live play/pause, stop, read-only current-item/timeline/metadata,
  and release. It advertises no seek or media-item mutation.
- The facade's `MediaItem` never has a URI/local configuration. It contains display metadata only.
- `CleanMediaSessionMetadata` accepts only a pre-redacted hexadecimal content fingerprint and
  bounded labels; the published media id is a `clean-`-prefixed truncation, never a raw account or
  provider id. URL-,
  authorization-, cookie-, and common credential-shaped labels are dropped before publication.
- The facade owns no network request, decoder, Surface, Media3 backend, ExoPlayer, or libmpv object.
- `CleanMediaSessionOwner.release()` first removes the system command surface and releases the facade,
  then waits for `PlaybackSessionController.release()` in `finally`, so a MediaSession cleanup failure
  cannot leave the provider/session release path uncalled.
- Playback intent comes from the authoritative engine-neutral snapshot field. The facade does not
  infer intent from rendered playback and does not maintain a shadow intent state.

## Verification gates

- Focused Robolectric tests cover snapshot projection, URL-free metadata, command forwarding,
  restricted metadata sanitization, and idempotent owner/session release.
- The architecture firewall admits Media3 system-session APIs only in `playback.mediasession` while
  rejecting all engine/backend types and both clean adapter-package imports there.
- Production route wiring is intentionally absent.
