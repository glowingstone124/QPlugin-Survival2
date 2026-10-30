#!/usr/bin/env python3
"""Exercise real module changes, failure rollback and resource release on an isolated Paper server.

Requires QHotProbe, the full survival plugin, local QAPI stubs and fixtures from paper_fixtures.py.
Uses an NMS test player and real Bukkit handlers/PDC; this does not emulate a networked client.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time
import zipfile

from paper_validation import Rcon


def exercise(server, fixture_file):
    repo = Path(__file__).resolve().parent.parent
    hot = server / "plugins/QuantumPlugin/hot"
    fixtures = json.loads(fixture_file.read_text())
    evidence = {"deploys": [], "checks": []}
    report = server / "integration.json"

    def status():
        return json.loads((hot / "status.json").read_text())

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        evidence["checks"].append(name)
        print("PASS " + name, flush=True)
        report.write_text(json.dumps(evidence, indent=2, ensure_ascii=False) + "\n")

    def deploy(version, expected=0):
        start = time.monotonic()
        process = subprocess.run([sys.executable, str(repo / "scripts/deploy-hot.py"),
            "--release", fixtures[version], "--hot-dir", str(hot)], capture_output=True, text=True, timeout=70)
        evidence["deploys"].append({"version": version, "exit": process.returncode,
            "seconds": time.monotonic() - start, "stdout": process.stdout.strip(), "stderr": process.stderr.strip()})
        check("deploy " + version, process.returncode == expected)
        if expected == 0:
            receipt = json.loads(process.stdout)
            check("receipt matches published ready status", status()["activeRelease"] == receipt["releaseId"] and status()["phase"] == "ready")
        return process

    def settled_resources(client):
        # Initial async file reads enqueue one-shot main-thread tasks. Count the steady registrations,
        # rather than taking a snapshot while those deliveries are still in the scheduler queue.
        time.sleep(.25)
        samples = []
        for _ in range(4):
            samples.append(client.probe("resources")); time.sleep(.1)
        return min(samples, key=lambda value: value["hostTasks"])

    with Rcon(server) as client:
        check("full plugin enabled", client.probe("resources")["hostEnabled"])
        client.probe("spawn QHotTester")
        deploy("v1")
        client.probe("track")
        first = client.probe("hot-event QHotTester clear")
        check("v1 business code and daily announcement", "[V1]" in first["display"] and len(first["messages"]) == 1 and bool(first["dailyMarker"]))
        check("one announcement per day", not client.probe("hot-event QHotTester")["messages"])
        before = settled_resources(client)
        evidence["beforeResources"] = before
        deploy("v2")
        client.probe("track")
        second = client.probe("hot-event QHotTester")
        check("v2 code changed and player marker survived", "[V2]" in second["display"] and not second["messages"] and second["dailyMarker"] == first["dailyMarker"])
        stopped = client.probe("resources")
        time.sleep(.4)
        advanced = client.probe("resources")
        check("retired v1 tick callback stopped", stopped["qhot.validation.ticks.v1"] == advanced["qhot.validation.ticks.v1"])
        check("v2 tick callback running", int(advanced["qhot.validation.ticks.v2"]) > int(stopped.get("qhot.validation.ticks.v2", "0")))
        old_state = json.loads((hot / "state.json").read_text())
        failed = deploy("broken", 1)
        check("health failure confirmed rollback", json.loads(failed.stdout)["code"] == "ROLLED_BACK")
        check("failed transaction kept persistent pointers", json.loads((hot / "state.json").read_text()) == old_state)
        check("old business handlers restored", "[V2]" in client.probe("hot-event QHotTester")["display"] and len(status()["modules"]) == 2)
        client.command("qhot rollback")
        deadline = time.monotonic() + 15
        while status()["activeRelease"] != old_state["previous"] and time.monotonic() < deadline:
            time.sleep(.05)
        rolled_back = client.probe("hot-event QHotTester")
        check("manual rollback restored v1 without duplicate announcement", "[V1]" in rolled_back["display"] and not rolled_back["messages"])
        state_file = hot / "state.json"
        state_bytes = state_file.read_bytes()
        active = status()["activeRelease"]
        state_file.unlink(); state_file.mkdir()
        try:
            failed = deploy("v2", 1)
            check("state write failure rolled back on real filesystem", json.loads(failed.stdout)["code"] == "ROLLED_BACK" and status()["activeRelease"] == active)
            check("state failure restored live business code", "[V1]" in client.probe("hot-event QHotTester")["display"])
        finally:
            state_file.rmdir(); state_file.write_bytes(state_bytes)
        for i in range(10):
            deploy("v2" if i % 2 == 0 else "v1")
            client.probe("track")
        final = settled_resources(client)
        evidence["afterResources"] = final
        check("listener and task counts stable", all(final[key] == before[key] for key in ("hostTasks", "modulePlayerJoinHandlers", "moduleCommandHandlers")))
        client.probe("gc"); time.sleep(1); client.probe("gc"); time.sleep(1)
        evidence["resources"] = client.probe("resources")
        check("only active module loaders remain", evidence["resources"]["aliveTrackedLoaders"] == 2)

        # Corrupt candidates use unique IDs and never alter immutable releases already on the host.
        base = Path(fixtures["v2"])
        for kind, expected in (("checksum", 1), ("host-mismatch", 20)):
            archive = fixture_file.parent / (kind + ".zip")
            with zipfile.ZipFile(base) as source, zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as output:
                manifest = json.loads(source.read("manifest.json"))
                manifest["releaseId"] += "-" + kind
                if kind == "host-mismatch":
                    manifest["hosts"]["QuantumPlugin"] = "0" * 64
                for name in source.namelist():
                    data = source.read(name)
                    if name == "manifest.json": data = json.dumps(manifest).encode()
                    elif kind == "checksum" and name == "sponsor-prefix.jar": data += b"tampered"
                    output.writestr(name, data)
            fixtures[kind] = str(archive)
            active = status()["activeRelease"]
            deploy(kind, expected)
            check(kind + " did not alter active version", status()["activeRelease"] == active)

        config = server / "daily-login-ad.json"
        original = config.read_bytes() if config.exists() else None
        try:
            config.write_text(json.dumps({"template": "Local announcement {url}", "url": "https://example.org/local"}))
            time.sleep(6)
            announced = client.probe("hot-event QHotTester clear")
            check("daily config live reload", announced["messages"] == ["Local announcement https://example.org/local"])
            config.write_text("{invalid")
            time.sleep(6)
            check("invalid config kept last valid message", client.probe("hot-event QHotTester clear")["messages"] == announced["messages"])
            config.write_text('{"enabled":false}')
            time.sleep(6)
            disabled = client.probe("hot-event QHotTester clear")
            check("disabled announcement preserves empty marker", not disabled["messages"] and not disabled["dailyMarker"])
        finally:
            if original is None: config.unlink(missing_ok=True)
            else: config.write_bytes(original)
        evidence["finalState"] = status()
        report.write_text(json.dumps(evidence, indent=2, ensure_ascii=False) + "\n")
        print("Evidence: " + str(report), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-dir", type=Path, required=True)
    parser.add_argument("--fixtures", type=Path, required=True)
    args = parser.parse_args()
    exercise(args.server_dir.resolve(), args.fixtures.resolve())


if __name__ == "__main__": main()
