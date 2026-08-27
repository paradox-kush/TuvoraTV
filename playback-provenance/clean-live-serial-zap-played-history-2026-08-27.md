# Clean live serial zap and played-history evidence — 2026-08-27

## Scope

This TV-only slice wires fullscreen channel up/down commands through the clean provider-neutral
ports and records Recent Channels only after the exact accepted playback generation renders video.
It does not switch a production ingress, mint a provider link, add a decoder, or infer retry
generations.

## Identity and command ownership

- `CleanLiveLaunchEntry` owns one `LiveChannelTarget`; its playback selection and media fingerprint
  are getters from that target, so launch display, selection, profile fingerprint, and later history
  cannot drift into separate authorities.
- The launch store accepts optional sanitized artwork and playlist version. Only its random one-shot
  capability token crosses navigation.
- D-pad zap requests enter one ViewModel-owned conflated channel. There is one relative lookup in
  flight and at most the latest pending direction, avoiding both concurrent provider reads and an
  unbounded remote-repeat backlog.
- Relative lookup occurs outside the playback ownership mutex. Exit and surface recreation remain
  responsive; the result is discarded unless the same host, active target, and bound profile still
  own the destination when the mutex is reacquired.
- The old presentation collector is stopped only around accepted zap/metadata commit. The host
  returns the session-assigned generation; target, title, MediaSession metadata, and pending history
  evidence are then replaced atomically before presentation restarts from the latest snapshot.

## Played-history evidence

- Initial tune, surface retune, and zap arm a `LivePlayedIdentity` using the generation returned by
  the host acknowledgement contract.
- `PLAYING`, audio, buffering, elapsed time, or a guessed timeout are insufficient. The snapshot
  must have the same generation and `progress.renderedVideoFrame == true`.
- The current snapshot is inspected before collection starts, so an immediate first frame is not
  missed. Pending evidence is cleared before persistence, making duplicate frame facts idempotent.
- A newer unacknowledged generation clears pending evidence instead of being attributed to the old
  target. In particular, `retry()` still has no accepted-generation result and is deliberately not
  inferred in this slice.
- Best-effort history writes are chained in detection order and live in the destination scope. They
  never block playback commands or turn a healthy stream into a playback error.

## Race fences

The focused tests cover exact-generation frame evidence, immediate and duplicate frame facts,
superseding generations, atomic zap title/selection updates, conflated rapid commands, release
during lookup, profile rejection before or after command acceptance, and surface recreation after a
successful zap. Release still waits for the host's affirmative provider/engine barrier.

## Platform disposition

These clean fullscreen ports, Android surface host, TV navigation route, and IPTV bridge exist only
in NuvioTV. NuvioMobile, NuvioDesktop, nuvio-backend, and nuvio-web have no corresponding code path
and are unaffected.

Static verification: `git diff --check`. Gradle was intentionally left to the coordinating agent's
single serialized verification lane.
