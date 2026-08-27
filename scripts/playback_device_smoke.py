#!/usr/bin/env python3
"""Sequential, secret-safe ADB harness for clean-player adapter smoke runs.

This tool deliberately cannot start playback or accept a stream URL. An operator opens a named
fixture in the debug playback lab only after ``begin`` has established exclusive device ownership.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import secrets
import shlex
import shutil
import subprocess
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Sequence


PACKAGE = "com.tuvora.tv.debug"
LOG_TAG = "CleanPlaybackSmoke"
LOG_PREFIX = "CP_SMOKE"
RELEASE_ACTION = "com.tuvora.tv.debug.action.PLAYBACK_SMOKE_RELEASE"
SCHEMA_VERSION = 1
SAFE_ID = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
SAFE_TOKEN = re.compile(r"^[A-Za-z0-9_.:+|-]{1,96}$")
INTEGER = re.compile(r"^-?[0-9]{1,12}$")
SECRET_MARKERS = re.compile(
    r"(?i)(://|authorization|cookie|password|passwd|username|user=|token|secret|"
    r"[?&](?:key|auth|sig|session)=)"
)


@dataclass(frozen=True)
class Device:
    alias: str
    serial: str


DEVICES = {
    "onn": Device("onn", "192.168.1.236:5555"),
    "fire": Device("fire", "192.168.1.225:5555"),
}


class HarnessError(RuntimeError):
    """A safe, operator-actionable harness failure."""


class Adb:
    def __init__(
        self,
        run: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
        binary: str | None = None,
    ) -> None:
        self._run = run
        self._binary = binary or resolve_adb_binary()

    def call(self, device: Device, arguments: Sequence[str], *, check: bool = True) -> str:
        try:
            result = self._run(
                [self._binary, "-s", device.serial, *arguments],
                text=True,
                capture_output=True,
                timeout=20,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            raise HarnessError(f"adb invocation failed on {device.alias}") from error
        if check and result.returncode != 0:
            # adb output is intentionally not reflected: an Android exception/log line could carry
            # a request URL or header. The device alias and operation are sufficient for diagnosis.
            operation = " ".join(arguments[:2])
            raise HarnessError(f"adb {operation} failed on {device.alias}")
        return result.stdout if result.returncode == 0 else ""

    def connected(self, device: Device) -> bool:
        return self.call(device, ["get-state"], check=False).strip() == "device"

    def app_pids(self, device: Device) -> set[int]:
        # Include package-suffixed service processes. Checking only ``pidof PACKAGE`` could miss a
        # provider-owning ``PACKAGE:playback`` process and falsely permit a second device.
        raw = self.call(device, ["shell", "ps", "-A", "-o", "PID,NAME"])
        result: set[int] = set()
        for line in raw.splitlines():
            parts = line.split()
            if len(parts) < 2:
                continue
            process_name = parts[-1]
            if process_name != PACKAGE and not process_name.startswith(PACKAGE + ":"):
                continue
            numeric = next((part for part in parts[:-1] if part.isdigit()), None)
            if numeric:
                result.add(int(numeric))
        return result

    def pid(self, device: Device) -> int | None:
        pids = self.app_pids(device)
        return min(pids) if pids else None

    def app_surface_summary(self, device: Device) -> dict[str, object]:
        raw = self.call(device, ["shell", "dumpsys", "SurfaceFlinger", "--list"])
        matching = [line for line in raw.splitlines() if PACKAGE in line]
        kinds: set[str] = set()
        for line in matching:
            lowered = line.lower()
            if "surfaceview" in lowered:
                kinds.add("SURFACE_VIEW_LAYER")
            elif "blast" in lowered:
                kinds.add("APP_BLAST_LAYER")
            else:
                kinds.add("APP_LAYER")
        return {"layer_count": len(matching), "layer_kinds": sorted(kinds)}

    def app_focused(self, device: Device) -> bool:
        raw = self.call(device, ["shell", "dumpsys", "window", "windows"])
        return any(PACKAGE in line for line in raw.splitlines() if "mCurrentFocus" in line)

    def smoke_log(self, device: Device) -> str:
        return self.call(device, ["logcat", "-d", "-v", "brief", "-s", f"{LOG_TAG}:I"])


def resolve_adb_binary() -> str:
    candidates: list[str | None] = [shutil.which("adb")]
    android_home = os.environ.get("ANDROID_HOME")
    if android_home:
        candidates.append(str(Path(android_home) / "platform-tools" / "adb"))
    candidates.append("/opt/homebrew/share/android-commandlinetools/platform-tools/adb")
    for candidate in candidates:
        if candidate and Path(candidate).is_file() and os.access(candidate, os.X_OK):
            return candidate
    raise HarnessError("adb binary not found; set ANDROID_HOME or add platform-tools to PATH")


EVENTS = {"SESSION", "STATE", "RENDERER", "SURFACE", "VIDEO", "AUDIO", "ERROR", "RELEASE"}
ENUM_FIELDS = {
    "engine": {"MEDIA3", "LIBMPV"},
    "profile": {"GUIDE", "FULLSCREEN"},
    "player_state": {"IDLE", "BUFFERING", "READY", "ENDED", "ERROR", "RELEASED"},
    "surface_type": {"SURFACE_VIEW", "TEXTURE_VIEW", "MPV_DIRECT", "MPV_RENDER", "NONE"},
    "error_domain": {
        "NETWORK", "AUTHORIZATION", "PROVIDER_LIMIT", "TLS", "MANIFEST", "DEMUX",
        "VIDEO_DECODER", "VIDEO_RENDERER_SURFACE", "AUDIO", "DRM", "DEVICE_RESOURCE",
        "UNKNOWN",
    },
    "phase": {"PREPARE", "STARTUP", "PLAYING", "SEEK", "RELEASE", "UNKNOWN"},
    "release_outcome": {"GRACEFUL", "HARD_ABORT", "FAILED"},
}
BOOL_FIELDS = {
    "play_when_ready", "is_loading", "rendered_first_frame", "surface_valid",
    "provider_owned", "surface_owned", "fatal", "secure", "rendered_first_audio",
}
INT_FIELDS = {
    "generation", "surface_width", "surface_height", "video_width", "video_height",
    "dropped_frames", "decoder_count", "audio_session_id",
}
TOKEN_FIELDS = {"renderer", "decoder", "error_code", "codec", "container", "release_nonce"}
ALLOWED_FIELDS = set(ENUM_FIELDS) | BOOL_FIELDS | INT_FIELDS | TOKEN_FIELDS


def parse_smoke_events(raw: str) -> list[dict[str, object]]:
    """Return only closed-schema adapter facts; never retain raw log text."""
    events: list[dict[str, object]] = []
    for line in raw.splitlines():
        marker = line.find(LOG_PREFIX)
        if marker < 0:
            continue
        payload = line[marker:]
        if SECRET_MARKERS.search(payload):
            continue
        try:
            parts = shlex.split(payload)
        except ValueError:
            continue
        if not parts or parts[0] != LOG_PREFIX:
            continue
        values: dict[str, str] = {}
        malformed = False
        for part in parts[1:]:
            if "=" not in part:
                malformed = True
                break
            key, value = part.split("=", 1)
            values[key] = value
        if malformed or values.get("v") != str(SCHEMA_VERSION) or values.get("event") not in EVENTS:
            continue
        event: dict[str, object] = {"event": values["event"]}
        for key, value in values.items():
            if key not in ALLOWED_FIELDS:
                continue
            if key in ENUM_FIELDS and value in ENUM_FIELDS[key]:
                event[key] = value
            elif key in BOOL_FIELDS and value in {"true", "false"}:
                event[key] = value == "true"
            elif key in INT_FIELDS and INTEGER.fullmatch(value):
                event[key] = int(value)
            elif key in TOKEN_FIELDS and SAFE_TOKEN.fullmatch(value):
                event[key] = value
        events.append(event)
    return events


def release_proven(events: Iterable[dict[str, object]]) -> bool:
    return any(
        event.get("event") == "RELEASE"
        and event.get("release_outcome") in {"GRACEFUL", "HARD_ABORT"}
        and event.get("provider_owned") is False
        and event.get("surface_owned") is False
        for event in events
    )


class Harness:
    def __init__(self, adb: Adb, state_dir: Path) -> None:
        self.adb = adb
        self.state_dir = state_dir
        self.state_path = state_dir / "state.json"
        self.reports_dir = state_dir / "reports"

    def _read_state(self) -> dict[str, object] | None:
        if not self.state_path.exists():
            return None
        return json.loads(self.state_path.read_text(encoding="utf-8"))

    def _write_json(self, path: Path, value: dict[str, object]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(path.suffix + ".tmp")
        temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        temporary.replace(path)

    def _require_connected_devices(self) -> None:
        missing = [device.alias for device in DEVICES.values() if not self.adb.connected(device)]
        if missing:
            raise HarnessError(f"required device not connected: {', '.join(missing)}")

    def _assert_all_processes_absent(self) -> None:
        running = [device.alias for device in DEVICES.values() if self.adb.pid(device) is not None]
        if running:
            raise HarnessError(
                "debug app still running on " + ", ".join(running) + "; quiesce it before begin"
            )

    def begin(self, alias: str, run_id: str, fixture_id: str) -> dict[str, object]:
        if not SAFE_ID.fullmatch(run_id) or not SAFE_ID.fullmatch(fixture_id):
            raise HarnessError("run-id and fixture-id must be sanitized lowercase identifiers")
        self._require_connected_devices()
        previous = self._read_state()
        if previous and previous.get("status") == "ACTIVE":
            raise HarnessError(f"{previous.get('device')} run is active; quiesce it before switching")
        self._assert_all_processes_absent()
        device = DEVICES[alias]
        self.adb.call(device, ["logcat", "-c"])
        state: dict[str, object] = {
            "schema_version": SCHEMA_VERSION,
            "status": "ACTIVE",
            "device": alias,
            "run_id": run_id,
            "fixture_id": fixture_id,
            "began_at_epoch_ms": int(time.time() * 1000),
        }
        self._write_json(self.state_path, state)
        return state

    def _active(self, alias: str) -> tuple[Device, dict[str, object]]:
        state = self._read_state()
        if not state or state.get("status") != "ACTIVE" or state.get("device") != alias:
            raise HarnessError(f"no active {alias} run")
        if not SAFE_ID.fullmatch(str(state.get("run_id", ""))) or not SAFE_ID.fullmatch(
            str(state.get("fixture_id", ""))
        ):
            raise HarnessError("smoke state contains an invalid identifier")
        return DEVICES[alias], state

    def capture(self, alias: str, *, suffix: str = "capture") -> Path:
        if not SAFE_ID.fullmatch(suffix):
            raise HarnessError("capture suffix must be a sanitized lowercase identifier")
        device, state = self._active(alias)
        events = parse_smoke_events(self.adb.smoke_log(device))
        report: dict[str, object] = {
            "schema_version": SCHEMA_VERSION,
            "run_id": state["run_id"],
            "fixture_id": state["fixture_id"],
            "device": alias,
            "captured_at_epoch_ms": int(time.time() * 1000),
            "application": {
                "process_running": self.adb.pid(device) is not None,
                "focused": self.adb.app_focused(device),
            },
            "surface_flinger": self.adb.app_surface_summary(device),
            "adapter_events": events,
            "release_proven": release_proven(events),
        }
        filename = f"{state['run_id']}-{alias}-{suffix}.json"
        path = self.reports_dir / filename
        self._write_json(path, report)
        return path

    def quiesce(
        self,
        alias: str,
        *,
        release_timeout_seconds: float = 8.0,
        require_release_proof: bool = False,
    ) -> dict[str, object]:
        device, state = self._active(alias)
        before_events = parse_smoke_events(self.adb.smoke_log(device))
        release_nonce = secrets.token_hex(8)
        # The package-scoped debug receiver pauses first and then requests adapter-level release.
        # force-stop remains a final safety barrier so a missing/broken receiver cannot leave a
        # provider connection open. Never send a global media key: the app may be backgrounded.
        process_was_running = self.adb.pid(device) is not None
        proof = False
        final_events = before_events
        if process_was_running:
            self.adb.call(
                device,
                [
                    "shell", "am", "broadcast", "-a", RELEASE_ACTION, "-p", PACKAGE,
                    "--es", "smoke_nonce", release_nonce,
                ],
                check=False,
            )
            deadline = time.monotonic() + max(0.0, release_timeout_seconds)
            while True:
                final_events = parse_smoke_events(self.adb.smoke_log(device))
                proof = any(
                    event.get("release_nonce") == release_nonce and release_proven([event])
                    for event in final_events
                )
                if proof or time.monotonic() >= deadline:
                    break
                time.sleep(min(0.25, max(0.0, deadline - time.monotonic())))

        self.adb.call(device, ["shell", "am", "force-stop", PACKAGE])
        process_absent = False
        for _ in range(20):
            if self.adb.pid(device) is None:
                process_absent = True
                break
            time.sleep(0.1)
        if not process_absent:
            raise HarnessError(f"{alias} debug process remains after force-stop; do not switch devices")

        # Write a closed-schema release report even when proof is missing. This makes a failed
        # adapter barrier diagnosable without retaining raw logcat or dumpsys output.
        final_events = parse_smoke_events(self.adb.smoke_log(device))
        release_report_path = self.reports_dir / f"{state['run_id']}-{alias}-release.json"
        self._write_json(
            release_report_path,
            {
                "schema_version": SCHEMA_VERSION,
                "run_id": state["run_id"],
                "fixture_id": state["fixture_id"],
                "device": alias,
                "captured_at_epoch_ms": int(time.time() * 1000),
                "application": {"process_running": False},
                "surface_flinger": self.adb.app_surface_summary(device),
                "adapter_events": final_events,
                "release_proven": proof,
            },
        )

        updated = dict(state)
        updated.update(
            {
                "status": "QUIESCED",
                "quiesced_at_epoch_ms": int(time.time() * 1000),
                "adapter_release_proven": proof,
                "process_absent_confirmed": True,
                "process_was_running": process_was_running,
                "release_report": str(release_report_path),
            }
        )
        self._write_json(self.state_path, updated)
        if require_release_proof and not proof:
            raise HarnessError(
                "adapter release proof was not observed; process absence is safe, but the WP4 release gate failed"
            )
        return updated

    def status(self) -> dict[str, object]:
        self._require_connected_devices()
        return {
            "schema_version": SCHEMA_VERSION,
            "state": self._read_state(),
            "devices": {
                alias: {"debug_process_running": self.adb.pid(device) is not None}
                for alias, device in DEVICES.items()
            },
        }


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument(
        "--state-dir",
        type=Path,
        default=Path(tempfile.gettempdir()) / "nuvio-playback-device-smoke",
        help="ephemeral state/report directory (default: system temporary directory)",
    )
    subcommands = result.add_subparsers(dest="command", required=True)
    begin = subcommands.add_parser("begin", help="acquire one idle device without starting playback")
    begin.add_argument("--device", choices=DEVICES, required=True)
    begin.add_argument("--run-id", required=True)
    begin.add_argument("--fixture-id", required=True)
    capture = subcommands.add_parser("capture", help="write a closed-schema fact report")
    capture.add_argument("--device", choices=DEVICES, required=True)
    capture.add_argument("--suffix", default="capture")
    quiesce = subcommands.add_parser("quiesce", help="pause, request release, and confirm process absence")
    quiesce.add_argument("--device", choices=DEVICES, required=True)
    quiesce.add_argument("--release-timeout", type=float, default=8.0)
    quiesce.add_argument("--require-release-proof", action="store_true")
    subcommands.add_parser("status", help="show sanitized ownership/process state")
    return result


def main(argv: Sequence[str] | None = None) -> int:
    arguments = parser().parse_args(argv)
    try:
        harness = Harness(Adb(), arguments.state_dir)
        if arguments.command == "begin":
            output: object = harness.begin(arguments.device, arguments.run_id, arguments.fixture_id)
        elif arguments.command == "capture":
            output = {"report": str(harness.capture(arguments.device, suffix=arguments.suffix))}
        elif arguments.command == "quiesce":
            output = harness.quiesce(
                arguments.device,
                release_timeout_seconds=arguments.release_timeout,
                require_release_proof=arguments.require_release_proof,
            )
        else:
            output = harness.status()
    except HarnessError as error:
        print(json.dumps({"ok": False, "error": str(error)}, sort_keys=True))
        return 2
    print(json.dumps({"ok": True, "result": output}, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
