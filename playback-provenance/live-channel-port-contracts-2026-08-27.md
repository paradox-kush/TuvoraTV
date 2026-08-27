# Live channel port contracts — 2026-08-27

Scope: NuvioTV clean player only. These contracts prepare fullscreen zap and truthful played
history without importing IPTV, storage, network, Media3, or libmpv implementations into clean UI.

## Decisions

- Zap direction is closed to `PREVIOUS` and `NEXT`; arbitrary deltas do not cross the UI boundary.
- A relative lookup returns one atomic `LiveChannelTarget`: provider selection, exact content
  identity, sanitized display fields, captured playlist version, and a shared media fingerprint.
- Target construction rejects non-live selections and selection/presentation identity mismatch.
- Playlist version is provenance, not a compare-and-swap lock. A harmless guide refresh does not
  invalidate a target captured coherently from an earlier immutable snapshot.
- Profile scope uses the exact redacted `PlaybackProfileId` captured by the destination.
- Played history carries the exact rendered playback generation. Storage adapters may record only
  after `renderedVideoFrame` is true for that generation.
- Every request/result/target/identity string form omits stable IDs, titles, artwork, profile IDs,
  account identity, URLs, and fingerprints.
- Initial launches and later zaps use the same SHA-256 media fingerprint algorithm.

## Verification

The focused serialized TV gate passed `LiveChannelPortsTest`, `CleanLiveLaunchStoreTest`, profile-
bound resolver/composition/store tests, clean host/ViewModel tests, `ArchitectureTest`, and
`:app:compileFullDebugKotlin` on 2026-08-27. The architecture firewall keeps `playback.live` free of
provider, platform, storage, wiring, and UI implementation imports.

Mobile, Desktop, backend, and web have no clean Android TV live-channel port twin and are genuinely
unaffected.
