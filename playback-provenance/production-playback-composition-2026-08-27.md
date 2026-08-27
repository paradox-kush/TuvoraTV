# Production clean playback composition provenance — 2026-08-27

## Scope

This change adds the TV-only production construction boundary for the clean playback stack. It does
not connect a production navigation or UI route and it does not alter the legacy player.

## Owned construction

`ProductionPlaybackSessionFactory` constructs one `PlaybackSession`, one engine registry, and one
`PlaybackSessionController` for a caller-supplied host. The host supplies the lifecycle scope,
Media3 and libmpv surface hosts, surface capability facts, output controller, lifecycle events,
preview viewport/resource budget, and current audio route. The factory owns preference loading,
runtime capability collection, provider resolution, compatibility history, diagnostics, version
facts, graph enumeration, engine adapters, and the single session release path.

The factory intentionally has no production UI call site yet. The atomic ingress cutover remains a
separate implementation phase.

## Contract decisions preserved

- Preferences and the provider resolver are bound to the same explicit, redacted
  `PlaybackProfileId` captured by the playback owner. Neither follows mutable active-profile state.
  Environment policy returns the complete
  effective `PlaybackPreferences`; `PlaybackSession` passes that exact object to the requirements
  resolver. There is no second preference resolver and no hidden latest-request bridge.
- Compatibility history is read using the explicit hashed `CompatibilityScopeKey` carried by the
  resolved provider request. Runtime, app, engine-version, graph, and 24-hour bootstrap expiry facts
  are recorded explicitly.
- Media3 resolves shared application DNS for each request's opaque `ApplicationDnsKey`. A missing or
  unknown key fails as typed network failure. System DNS uses no application resolver. libmpv keeps
  its documented system-fallback behavior.
- DRM presence keeps Media3 eligibility policy but does not invent a protected-surface requirement.
  Only the explicit `DrmRequest.secureOutputRequired` fact requests a secure Media3 surface.
- The Media3 client is a clean strict-TLS client. It deliberately does not reuse the legacy global
  client because that binding has legacy trust exceptions.
- Graph generation is mechanical. Selection, fallback, retry, and recovery remain core policy
  authorities.

## Pinned implementation facts

- Media3 engine: `androidx-media3-1.11.0+nuvio-fork`.
- libmpv engine: `lib-mpv-release.aar` SHA-256
  `44747a57bef59979d32ab2b28d9b582cb05e91684d53f1bdf5f120183b380a8b`.
- Application version uses `BuildConfig.VERSION_NAME` and `VERSION_CODE`.

## Verification

- `:app:compileFullDebugKotlin`
- Focused TV unit tests for session preference handoff, request safety, requirements resolution,
  IPTV provider resolution, Media3 network planning and per-request DNS, libmpv DNS fallback,
  request mapping, and production composition.
- Mobile, Desktop, backend, and web were searched for the clean composition/environment/DNS symbols.
  They do not contain this Android-TV-only clean player or Android surface/DNS adapter path, so no
  corresponding source change exists there.

## Remaining integration seam

The production live guide/fullscreen host still needs to supply its concrete surfaces, lifecycle,
output controller, and URL-free ingress selection in the later atomic route cutover. This factory
does not authorize partial legacy/clean ownership of the same playback session.
