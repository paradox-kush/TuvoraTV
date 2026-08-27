# URL-free initial live presentation reader — 2026-08-27

## Scope

This TV-only checkpoint adds display identity lookup for a future clean Live TV ingress. It does
not issue a launch token, navigate, resolve a provider link, open a stream, or change production
callers.

## Contract

`IptvInitialLivePresentationReader` accepts only the captured numeric profile and stable live
content identity. It resolves sanitized title/logo in this order:

1. the exact current immutable `XtreamLivePlaylist` presentation;
2. an `XtreamItemRegistry` item whose live kind, account identity, and numeric stream identity all
   match the parsed requested identity;
3. an exact-profile `XtreamLiveStore` identity projection.

`XtreamLiveStore.identityForProfile` reads the requested profile's DataStore directly. It never
uses the active-profile flow or synchronous active-profile mirror and projects a stored row into a
URL-free `StoredLiveChannelIdentity` before returning it.

Invalid profiles, malformed/non-live identities, mismatched registry or persisted identities, and
ordinary storage/source failures fail closed. Coroutine cancellation is rethrown. The presentation
and stored-identity result types have no URL/stream field, and their string forms contain only
shape facts.

## Verification added

- exact playlist, registry, and persisted-profile precedence;
- strict registry account/stream/kind matching;
- explicit-profile persistence independent of the active profile;
- mismatched persisted identity rejection;
- transport-shaped title and credential-bearing logo sanitization;
- invalid input and ordinary failure fail-closed behavior;
- cancellation preservation and result-field/string redaction.

No Media3, libmpv, surface, navigation, launch-store, destination, backend, web, Mobile, or Desktop
counterpart exists. Those platforms are genuinely unaffected by this Android TV ingress reader.
