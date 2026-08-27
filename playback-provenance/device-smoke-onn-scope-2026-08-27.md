# ONN-only device-smoke scope — 2026-08-27

## Decision

Real-device playback validation is currently ONN-only. Fire TV is powered off, deferred, and must
not be represented as tested. `scripts/playback_device_smoke.py` therefore exposes only the `onn`
device and neither `begin` nor `status` queries the former Fire TV address.

## Preserved safety properties

- A single active state lease rejects any second run until the first is quiesced.
- `begin` requires ONN connectivity and confirms the base package plus package-suffixed processes
  are absent before clearing logs or writing the active lease.
- `quiesce` retains nonce-correlated adapter release proof, package-scoped release, force-stop, and
  confirmed process absence. Missing proof still fails the release gate after reaching safe process
  absence.
- Device arguments are constrained by the explicit device map. Reintroducing another target now
  requires a deliberate code and test change, preventing an accidental command to a second TV.
- Reports remain closed-schema and secret-safe.

## Verification

Focused host tests assert that `begin` and `status` address only the ONN serial, that an ONN process
or package-suffixed process blocks acquisition, that a second lease is rejected, and that a proven
release permits the next ONN run. No ADB connection, install, application launch, stream request, or
Gradle task was used for this scope correction.
