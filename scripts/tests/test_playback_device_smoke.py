from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from playback_device_smoke import (
    Adb,
    DEVICES,
    Harness,
    HarnessError,
    parse_smoke_events,
    release_proven,
)


class FakeAdbCommand:
    def __init__(self) -> None:
        self.running: dict[str, dict[str, int]] = {
            "192.168.1.236:5555": {},
            "192.168.1.225:5555": {},
        }
        self.logs = {serial: "" for serial in self.running}
        self.release_after_broadcast = {serial: False for serial in self.running}
        self.commands: list[list[str]] = []

    def __call__(self, command: list[str], **_: object) -> subprocess.CompletedProcess[str]:
        self.commands.append(command)
        serial = command[2]
        arguments = command[3:]
        output = ""
        if arguments == ["get-state"]:
            output = "device\n"
        elif arguments[:3] == ["shell", "ps", "-A"]:
            output = "PID NAME\n" + "".join(
                f"{pid} {name}\n" for name, pid in self.running[serial].items()
            )
        elif arguments[:3] == ["shell", "am", "force-stop"]:
            self.running[serial] = {}
        elif arguments[:3] == ["shell", "am", "broadcast"] and self.release_after_broadcast[serial]:
            nonce = arguments[arguments.index("smoke_nonce") + 1]
            self.logs[serial] += (
                "I/CleanPlaybackSmoke: CP_SMOKE v=1 event=RELEASE release_outcome=GRACEFUL "
                f"provider_owned=false surface_owned=false release_nonce={nonce}\n"
            )
        elif arguments[:3] == ["shell", "dumpsys", "SurfaceFlinger"]:
            output = "SurfaceView[com.tuvora.tv.debug](BLAST)\nprovider-secret-layer\n"
        elif arguments[:3] == ["shell", "dumpsys", "window"]:
            output = "mCurrentFocus=Window{ com.tuvora.tv.debug/.MainActivity }\n"
        elif arguments and arguments[0] == "logcat" and "-d" in arguments:
            output = self.logs[serial]
        return subprocess.CompletedProcess(command, 0, stdout=output, stderr="")


class SmokeEventParserTests(unittest.TestCase):
    def test_closed_schema_retains_renderer_surface_state_and_error_facts(self) -> None:
        events = parse_smoke_events(
            "I/CleanPlaybackSmoke: CP_SMOKE v=1 event=STATE engine=MEDIA3 "
            "player_state=READY play_when_ready=true generation=4 ignored=anything\n"
            "I/CleanPlaybackSmoke: CP_SMOKE v=1 event=RENDERER renderer=MediaCodecVideoRenderer "
            "decoder=c2.android.avc.decoder codec=AVC\n"
            "I/CleanPlaybackSmoke: CP_SMOKE v=1 event=SURFACE surface_type=TEXTURE_VIEW "
            "surface_valid=true surface_width=960 surface_height=540\n"
            "I/CleanPlaybackSmoke: CP_SMOKE v=1 event=ERROR error_domain=VIDEO_DECODER "
            "error_code=DECODER_INIT phase=STARTUP fatal=true\n"
        )
        self.assertEqual([event["event"] for event in events], ["STATE", "RENDERER", "SURFACE", "ERROR"])
        self.assertNotIn("ignored", events[0])
        self.assertEqual(events[2]["surface_width"], 960)

    def test_secret_looking_event_is_dropped_in_full(self) -> None:
        events = parse_smoke_events(
            "I/CleanPlaybackSmoke: CP_SMOKE v=1 event=STATE engine=MEDIA3 "
            "player_state=READY source=https://user:pass@example.test/live?token=nope\n"
        )
        self.assertEqual(events, [])

    def test_release_requires_ownership_ended(self) -> None:
        self.assertFalse(release_proven([{"event": "RELEASE", "release_outcome": "GRACEFUL"}]))
        self.assertTrue(
            release_proven(
                [
                    {
                        "event": "RELEASE",
                        "release_outcome": "HARD_ABORT",
                        "provider_owned": False,
                        "surface_owned": False,
                    }
                ]
            )
        )

    def test_path_or_email_causes_full_event_drop(self) -> None:
        events = parse_smoke_events(
            "I/CleanPlaybackSmoke: CP_SMOKE v=1 event=RENDERER "
            "renderer=MediaCodecVideoRenderer decoder=provider.example/live user=user@example.test\n"
        )
        self.assertEqual(events, [])


class HarnessTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.fake = FakeAdbCommand()
        self.harness = Harness(Adb(self.fake), Path(self.temporary.name))

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_begin_refuses_when_either_device_has_debug_process(self) -> None:
        self.fake.running[DEVICES["fire"].serial] = {"com.tuvora.tv.debug": 42}
        with self.assertRaisesRegex(HarnessError, "fire"):
            self.harness.begin("onn", "wp4-media3", "hls-ts-a")

    def test_begin_refuses_package_suffixed_service_process(self) -> None:
        self.fake.running[DEVICES["fire"].serial] = {"com.tuvora.tv.debug:playback": 43}
        with self.assertRaisesRegex(HarnessError, "fire"):
            self.harness.begin("onn", "wp4-media3", "hls-ts-a")

    def test_active_run_blocks_device_switch(self) -> None:
        self.harness.begin("onn", "wp4-media3", "hls-ts-a")
        with self.assertRaisesRegex(HarnessError, "quiesce"):
            self.harness.begin("fire", "wp4-media3", "hls-ts-b")

    def test_capture_persists_only_sanitized_facts(self) -> None:
        self.harness.begin("onn", "wp4-media3", "hls-ts-a")
        self.fake.running[DEVICES["onn"].serial] = {"com.tuvora.tv.debug": 12}
        self.fake.logs[DEVICES["onn"].serial] = (
            "I/CleanPlaybackSmoke: CP_SMOKE v=1 event=STATE engine=MEDIA3 player_state=READY\n"
            "I/CleanPlaybackSmoke: CP_SMOKE v=1 event=ERROR source=https://secret.example/x?token=y\n"
        )
        path = self.harness.capture("onn")
        serialized = path.read_text(encoding="utf-8")
        report = json.loads(serialized)
        self.assertEqual(len(report["adapter_events"]), 1)
        self.assertEqual(report["surface_flinger"]["layer_kinds"], ["SURFACE_VIEW_LAYER"])
        self.assertNotIn("secret.example", serialized)
        self.assertNotIn("provider-secret-layer", serialized)
        self.assertNotIn(DEVICES["onn"].serial, serialized)

    def test_quiesce_uses_package_scoped_release_force_stops_and_allows_next_device(self) -> None:
        self.harness.begin("onn", "wp4-media3", "hls-ts-a")
        self.fake.running[DEVICES["onn"].serial] = {"com.tuvora.tv.debug": 12}
        self.fake.release_after_broadcast[DEVICES["onn"].serial] = True
        result = self.harness.quiesce("onn", release_timeout_seconds=0)
        self.assertTrue(result["adapter_release_proven"])
        self.assertTrue(result["process_absent_confirmed"])
        flattened = [command[3:] for command in self.fake.commands]
        self.assertNotIn(["shell", "input", "keyevent", "127"], flattened)
        self.assertIn(["shell", "am", "force-stop", "com.tuvora.tv.debug"], flattened)
        release_report = json.loads(Path(result["release_report"]).read_text())
        self.assertTrue(release_report["release_proven"])
        self.harness.begin("fire", "wp4-media3", "hls-ts-b")

    def test_quiesce_does_not_accept_a_stale_release_event(self) -> None:
        self.harness.begin("onn", "wp4-media3", "hls-ts-a")
        self.fake.running[DEVICES["onn"].serial] = {"com.tuvora.tv.debug": 12}
        self.fake.logs[DEVICES["onn"].serial] = (
            "I/CleanPlaybackSmoke: CP_SMOKE v=1 event=RELEASE release_outcome=GRACEFUL "
            "provider_owned=false surface_owned=false\n"
        )
        result = self.harness.quiesce("onn", release_timeout_seconds=0)
        self.assertFalse(result["adapter_release_proven"])

    def test_quiesce_never_sends_global_pause_when_app_never_started(self) -> None:
        self.harness.begin("onn", "wp4-media3", "hls-ts-a")
        result = self.harness.quiesce("onn", release_timeout_seconds=0)
        self.assertFalse(result["process_was_running"])
        flattened = [command[3:] for command in self.fake.commands]
        self.assertNotIn(["shell", "input", "keyevent", "127"], flattened)

    def test_missing_release_proof_fails_gate_only_after_safe_force_stop(self) -> None:
        self.harness.begin("onn", "wp4-media3", "hls-ts-a")
        self.fake.running[DEVICES["onn"].serial] = {"com.tuvora.tv.debug": 12}
        with self.assertRaisesRegex(HarnessError, "release gate failed"):
            self.harness.quiesce("onn", release_timeout_seconds=0, require_release_proof=True)
        self.assertEqual(self.fake.running[DEVICES["onn"].serial], {})
        state = json.loads((Path(self.temporary.name) / "state.json").read_text())
        self.assertEqual(state["status"], "QUIESCED")


if __name__ == "__main__":
    unittest.main()
