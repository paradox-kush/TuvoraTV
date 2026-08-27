# Clean live launch store — 2026-08-27

## Scope

This TV-only change adds the opaque in-memory handoff required by the future Search/Library clean
live destination. It does not add or switch a navigation route, construct a player, resolve a media
link, or modify the legacy player.

## Contract

- A pending entry contains the URL-free `ProviderPlaybackSelection`, the active profile id,
  sanitized display labels, an explicit `SEARCH`/`LIBRARY` origin, and a SHA-256 media fingerprint.
- The fingerprint is computed inside the store from length-framed opaque selection/profile identity.
  Raw identity never appears in its string form.
- The route capability is 32 bytes from `SecureRandom`, encoded as 64 lowercase hexadecimal
  characters. Only that token may later cross a navigation argument.
- Entries are process-local and never enter `SavedStateHandle`, preferences, disk, or analytics.
- Consumption is synchronized, one-shot, and profile-bound. Missing, expired, and profile-mismatched
  entries return stable typed reasons without values. A mismatch consumes the entry rather than
  allowing it to be replayed after another profile switch.
- Pending launches expire after two minutes and the store retains at most sixteen. Expired entries
  are pruned before oldest-first capacity eviction.
- Labels are control-character stripped, whitespace-normalized, bounded to 256 characters, and
  reject URL/auth/cookie/credential-shaped text. Public string forms contain only shape facts.

## Test seams

The internal clock and entropy ports permit deterministic TTL, eviction, collision, fingerprint,
and one-shot tests. Production injection uses the no-argument `@Inject` constructor, a monotonic
clock, and `SecureRandom`; no DI module or binding is required.

## Platform disposition

NuvioMobile and NuvioDesktop were searched. They have their own byte-parallel KMP
`PlayerLaunchStore`/`LiveTvRoute` path, but neither contains the TV clean playback contracts and
their existing launch payload still owns platform-specific resolved media data. Backend and web
contain no local player navigation handoff. The new store is therefore genuinely Android-TV-only.

## Verification status

Source and focused tests were added without touching production routing. The serialized Gradle gate
will run when the shared build lane is released.
