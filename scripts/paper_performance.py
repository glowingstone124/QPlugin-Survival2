#!/usr/bin/env python3
"""Paired callback microbenchmark and idle Paper A/B windows. No live-player capacity claims.

Run on the already prepared isolated validation server, without simultaneous builds or profiling changes.
The idle fixture disables business callbacks; the permanent host and the rest of QPlugin stay loaded.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time
from paper_validation import Rcon, sample


def run(server, fixtures, seconds):
    repo = Path(__file__).resolve().parent.parent
    hot = server / "plugins/QuantumPlugin/hot"
    report = {"microbenchmarks": [], "idleWindows": []}
    destination = server / "performance.json"
    fixture = json.loads(fixtures.read_text())
    status = json.loads((hot / "status.json").read_text())
    builtin = next(directory.name for directory in (hot / "releases").glob("builtin-" + status["hostCodeSha256"][:12] + "-*") if directory.is_dir())

    def save():
        destination.write_text(json.dumps(report, indent=2) + "\n")

    def wait_release(release):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            current = json.loads((hot / "status.json").read_text())
            if current["activeRelease"] == release and current["phase"] == "ready": return
            time.sleep(.05)
        raise TimeoutError("Activation did not finish")

    with Rcon(server) as client:
        for repetition in range(3):
            file = server / "plugins/QHotProbe/benchmark.json"
            client.probe("benchmark 100000 120")
            deadline = time.monotonic() + 20
            while not file.exists() and time.monotonic() < deadline: time.sleep(.1)
            result = json.loads(file.read_text())
            report["microbenchmarks"].append(result); save()
            print("Callback benchmark " + str(repetition + 1) + ": " + json.dumps(result), flush=True)
        client.command("fakeplayer remove QHotTester")
        # ABBA order limits monotonic warm-up drift. Each sample uses Paper's measured tick duration.
        for mode in ("business", "idle", "idle", "business"):
            if mode == "business":
                client.command("qhot apply " + builtin)
                wait_release(builtin)
            else:
                subprocess.run([sys.executable, str(repo / "scripts/deploy-hot.py"), "--release", fixture["idle"], "--hot-dir", str(hot)], check=True, capture_output=True)
            client.probe("gc"); time.sleep(3)
            measured = sample(server, seconds)
            measured["mode"] = mode
            a, b = measured["start"], measured["end"]
            measured["hotIoCpuPercentOfOneCore"] = 100 * (b["hotIoCpuNanos"] - a["hotIoCpuNanos"]) / 1e9 / measured["seconds"]
            report["idleWindows"].append(measured); save()
            print(mode + ": " + json.dumps({key: measured[key] for key in ("seconds", "cpuPercentOfOneCore", "hotIoCpuPercentOfOneCore", "ticksMillis")}), flush=True)
        # Leave the real bundled production modules active, never the empty test fixture.
        client.command("qhot apply " + builtin); wait_release(builtin)
        report["finalState"] = json.loads((hot / "status.json").read_text()); save()
    print("Evidence: " + str(destination), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-dir", required=True, type=Path)
    parser.add_argument("--fixtures", required=True, type=Path)
    parser.add_argument("--seconds", type=float, default=30)
    args = parser.parse_args()
    if not 10 <= args.seconds <= 50: parser.error("Use windows between 10 and 50 seconds")
    run(args.server_dir.resolve(), args.fixtures.resolve(), args.seconds)


if __name__ == "__main__": main()
