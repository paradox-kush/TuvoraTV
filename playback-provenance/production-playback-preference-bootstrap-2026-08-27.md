# Production playback preference bootstrap provenance — 2026-08-27

The clean TV session now opens preferences through one profile-scoped bootstrap boundary. If a
clean document already exists, it is decoded directly and legacy storage is not read. If absent,
the active profile's typed `PlayerSettings` value is detached through
`LegacyPlayerSettingsSnapshotMapper` and imported once with the stable `player-settings-v1` token.
The repository mutex makes concurrent/repeated bootstrap idempotent.

The production legacy source fails closed when the requested clean profile is malformed, is not the
active profile, or changes while the typed snapshot is being read. This prevents importing one
profile's legacy preferences under another profile's clean key.

`BootstrappedPlaybackPreferences.initialCommand()` creates `PreferencesChanged` from the exact
`PlaybackPreferences` returned by the repository. `ProductionPlaybackSessionFactory` dispatches
that command on the session actor before returning its controller. Neither the settings ViewModel
nor the bootstrap source imports or configures Media3/libmpv.

Focused tests cover typed mapper exhaustiveness, lazy import, independent profile keys, idempotence,
and exact command propagation. The architecture firewall prevents the clean settings ViewModel from
depending on the legacy player store, playback session/controller, engine contract, or adapters.

This contract is TV-only: Mobile/Desktop do not contain the clean TV repository, typed
`PlayerSettings`, or production composition root; backend/web have no client playback settings or
engine path.

The architecture gate also exposed and closed an earlier composition crossing: the clean factory no
longer imports `PlaylistDns`. Hilt adapts that fork implementation to the Media3
`ApplicationDnsResolver` port at the application composition boundary.
