# Android playback lifecycle port — 2026-08-27

## Scope

TV-only clean playback infrastructure. `AndroidPlaybackLifecyclePort` converts a UI owner's
AndroidX `Lifecycle` into the engine-neutral `PlaybackLifecycleEvent` flow consumed by the single
`PlaybackSession`.

## Contract

- A new collector first receives the owner's current state.
- `STARTED` or above maps to `ACTIVE`.
- Leaving `STARTED` maps to `INACTIVE`; this is the boundary that stops reconnect work and releases
  the provider connection.
- Destruction maps to terminal `DESTROYED`.
- Resume/pause noise inside the started interval is ignored and duplicate states are suppressed.
- The lifecycle observer is removed when collection ends.

No provider, URL, engine, surface, or UI-navigation fact crosses this adapter.

## Verification

`AndroidPlaybackLifecyclePortTest` covers an initially inactive owner, the full visible lifecycle,
duplicate suppression, an already-started owner, and an already-destroyed owner. The full TV unit
gate is recorded in the implementation log when this contract lands.

No equivalent clean playback lifecycle port exists in Mobile, Desktop, backend, or web; this change
is genuinely specific to the NuvioTV clean player.
