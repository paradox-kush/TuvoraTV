# Clean-player real-device adapter smoke harness

**Status:** debug Media3 lab wired; it does not auto-launch or auto-open a stream.

`scripts/playback_device_smoke.py` is the sequential ADB evidence harness for the authorized ONN
(`192.168.1.236:5555`) and Fire TV (`192.168.1.225:5555`) devices. It protects one-connection IPTV
accounts by refusing to begin while the debug app or any package-suffixed service process runs on
either device, refusing an ONN/Fire switch while a run is active, and making every switch pass
through pause, adapter release, force-stop, and confirmed process absence.

The harness never accepts a URL, header, cookie, username, password, playlist identifier, channel
name, or provider name. `--fixture-id` is a local sanitized alias only. Assign a distinct provider
fixture/account to ONN and Fire outside this tool; never place that mapping in a report or command.

## Adapter instrumentation contract

The debug-only `CleanMedia3PlaybackLabActivity` emits single-line facts at info level under tag
`CleanPlaybackSmoke`. The line starts with `CP_SMOKE v=1` and uses space-separated `key=value`
tokens. It must not log raw exceptions or request material. Example:

```text
CP_SMOKE v=1 event=STATE engine=MEDIA3 profile=GUIDE generation=3 player_state=READY play_when_ready=true is_loading=false
CP_SMOKE v=1 event=RENDERER engine=MEDIA3 renderer=MediaCodecVideoRenderer decoder=c2.amlogic.avc.decoder
CP_SMOKE v=1 event=RENDERER engine=MEDIA3 renderer=MediaCodecVideoRenderer codec=AVC
CP_SMOKE v=1 event=SURFACE engine=MEDIA3 surface_type=SURFACE_VIEW surface_valid=true surface_width=960 surface_height=540 secure=false
CP_SMOKE v=1 event=VIDEO engine=MEDIA3 rendered_first_frame=true
CP_SMOKE v=1 event=VIDEO engine=MEDIA3 video_width=1920 video_height=1080
CP_SMOKE v=1 event=ERROR error_domain=VIDEO_DECODER error_code=DECODER_INIT phase=STARTUP fatal=true
CP_SMOKE v=1 event=RELEASE release_outcome=GRACEFUL provider_owned=false surface_owned=false release_nonce=0123456789abcdef
```

The parser keeps only the closed version-1 event/field vocabulary in the script. Unknown fields are
discarded. A line containing a URL or secret marker is discarded in full. SurfaceFlinger and window
dumps are reduced in memory to process/focus, app-layer count, and layer-type facts; raw dumps and
raw log lines are never written.

The debug lab must handle the package-scoped broadcast
`com.tuvora.tv.debug.action.PLAYBACK_SMOKE_RELEASE` by pausing the session and awaiting the clean
session release barrier. It emits `RELEASE` only after both provider and surface ownership are
affirmatively ended, echoing the broadcast's `smoke_nonce` extra as `release_nonce`. The harness
accepts only that fresh correlated event, so an earlier successful release cannot satisfy a later
run. It force-stops afterward even when release succeeds: adapter proof and device-switch safety are
separate requirements.

## Sequential procedure (ONN first, then Fire TV)

Use separate sanitized fixture aliases for the devices. Do not pass a provider URL to the shell.

```bash
python3 scripts/playback_device_smoke.py status
python3 scripts/playback_device_smoke.py begin \
  --device onn --run-id wp4-media3-hls-ts --fixture-id onn-hls-ts-a
```

After `begin`, install/open the debug lab manually and choose only ONN's assigned fixture. Capture at
startup, first frame, stable playback, an injected failure if the matrix requires one, and after a
surface recreation:

```bash
python3 scripts/playback_device_smoke.py capture --device onn --suffix first-frame
python3 scripts/playback_device_smoke.py capture --device onn --suffix surface-recreated
python3 scripts/playback_device_smoke.py quiesce --device onn --require-release-proof
python3 scripts/playback_device_smoke.py status
```

Only after quiesce succeeds and status reports both debug processes absent may Fire TV begin:

```bash
python3 scripts/playback_device_smoke.py begin \
  --device fire --run-id wp4-media3-hls-ts --fixture-id fire-hls-ts-b
# Open only Fire TV's separately assigned fixture in the debug lab.
python3 scripts/playback_device_smoke.py capture --device fire --suffix first-frame
python3 scripts/playback_device_smoke.py capture --device fire --suffix surface-recreated
python3 scripts/playback_device_smoke.py quiesce --device fire --require-release-proof
```

Ephemeral state and sanitized JSON reports default to
`/tmp/nuvio-playback-device-smoke`. A failed `--require-release-proof` still force-stops and confirms
the process absent, so switching is safe, but it fails the WP4 deterministic-release gate. Do not
represent process absence alone as adapter release proof.

## Debug-profile boundary and lab operation

The lab is compiled only into `com.tuvora.tv.debug`. Android app-private storage means it cannot
read or import the production `com.tuvora.tv` profile. Prepare the debug package through its normal
UI: sign in/sync or add the assigned playlist, select that playlist, play one live channel long
enough to create a recent, then stop playback. Do this separately for each device/fixture. Never
copy, export, hard-code, or pass production credentials to ADB.

The lab selects exactly the active debug playlist and its newest live recent. It has no URL,
credential, account, channel, or playlist Intent extras; missing state, a disabled playlist, and
Stalker sources fail closed with a non-secret `LAB_*` readiness code. Playback starts only after the
operator presses **Start selected recent channel**.

After `begin`, launch the Activity on the active device only:

```bash
adb -s 192.168.1.236:5555 shell am start \
  -n com.tuvora.tv.debug/com.nuvio.tv.playback.lab.CleanMedia3PlaybackLabActivity
```

For the Fire pass, use `192.168.1.225:5555` only after ONN `quiesce` succeeds. The **Recreate
surface** action detaches the selected clean Media3 surface, rebuilds the same graph-selected View,
and reattaches it to the existing backend on the same generation. It does not resolve the URL again,
construct a second backend, or restart the provider request. Leaving the lab foreground also starts
the pause/release barrier; harness `quiesce` remains mandatory before switching devices.

## Report acceptance

For every Media3 WP4 fixture, the report must contain normalized state plus the selected renderer,
decoder, surface type/validity/size, first-frame/video dimensions, and any stable error code/domain.
The release report must prove `provider_owned=false` and `surface_owned=false`. Fire guide playback
must show the exact policy-selected TextureView path; ONN must show its independently selected path.
No report may contain a network location, request value, account/provider/channel identity, raw
exception, or device address.

Host tests:

```bash
PYTHONPATH=scripts python3 -m unittest scripts.tests.test_playback_device_smoke
```
