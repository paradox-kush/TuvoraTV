# Android playback output controller — 2026-08-27

## Boundary

The clean output controller is a per-TV-player-host owner of Android display-mode requests. It
implements the core `PlaybackOutputController` port and receives only resolved requirements,
generation, committed state, and factual video rate/dimensions. It has no provider, URL, network,
Media3, or libmpv dependency and performs no preflight probing. Window access is serialized by a
per-instance `Mutex` on the injected Main dispatcher; no global display state is used.

## Selection and confirmation

`AndroidDisplayModeSelector` is pure and deterministic. It filters invalid modes, keeps the current
resolution unless resolution matching is explicitly effective, and otherwise chooses the nearest
supported factual video resolution. Cadence preference is exact, 2x, then 2.5x, followed by a
deterministic cadence-error fallback with the current mode preferred on a tie. Resolution-only
operation preserves the closest current display refresh and does not invent a content rate.

The controller never reports `APPLIED` merely because `preferredDisplayModeId` was written. It
requires bounded, stable observations of the requested mode. A timeout is
`APPLY_NOT_CONFIRMED`; an actual Window operation exception is the nonfatal `APPLY_FAILED` status,
so optional AFR cannot terminate otherwise-working playback. Equal generation/fact-revision mode
requests are deduplicated, while a newer factual revision can retry an unconfirmed target.

## Ownership and lifecycle

The first effective switch captures the original mode. Ownership is preserved across rebuild,
reselect, handoff, and surface loss. It is restored for stop, request replacement, completion,
failure, lifecycle inactivity, and an effective guide/OFF request. A mismatched released graph
generation cannot restore a newer owner; a null terminal generation still restores. Reset and
restoration are idempotent.

## Platform scope

This is Android TV/Fire TV display-Window behavior in NuvioTV's clean playback stack. NuvioMobile,
NuvioDesktop, `nuvio-backend`, and `nuvio-web` do not contain the clean TV output port or own an
Android TV Activity display mode, so they have no applicable twin implementation.
