#!/usr/bin/env python3
"""Stage an immutable release and wait for the running Bukkit host's confirmed result."""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import tempfile
import time
import uuid
import zipfile

NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,95}\Z")
DIGEST = re.compile(r"[0-9a-f]{64}\Z")
SUPPORTED_API_VERSIONS = (1, 2)
MAX_ARCHIVE_BYTES = 512 * 1024 * 1024


def safe_name(value):
    if not isinstance(value, str) or not NAME.fullmatch(value) or ".." in value:
        raise ValueError("Invalid release/module/file name")
    return value


def sha256(file):
    digest = hashlib.sha256()
    with file.open("rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(file, limit=65536):
    if file.is_symlink() or not file.is_file() or file.stat().st_size > limit:
        raise ValueError(f"Invalid JSON file: {file.name}")
    value = json.loads(file.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"Expected JSON object: {file.name}")
    return value


def validate(directory):
    if directory.is_symlink():
        raise ValueError("Release directory is a symlink")
    manifest = read_json(directory / "manifest.json")
    if manifest.get("formatVersion") != 1 or manifest.get("apiVersion") not in SUPPORTED_API_VERSIONS:
        raise ValueError("Unsupported release format/API")
    safe_name(manifest.get("releaseId"))
    hosts = manifest.get("hosts")
    if not isinstance(hosts, dict) or not 1 <= len(hosts) <= 16:
        raise ValueError("Missing host requirements")
    for host, digest in hosts.items():
        safe_name(host)
        if not isinstance(digest, str) or not DIGEST.fullmatch(digest):
            raise ValueError("Invalid host code fingerprint")
    modules, plugins = manifest.get("modules"), manifest.get("plugins")
    if not isinstance(modules, list) or not 1 <= len(modules) <= 16 or not isinstance(plugins, list) or len(plugins) > 16:
        raise ValueError("Invalid artifact count")
    names, ids = set(), set()
    for index, artifact in enumerate(modules + plugins):
        if not isinstance(artifact, dict):
            raise ValueError("Expected artifact object")
        safe_name(artifact.get("id"))
        safe_name(artifact.get("version"))
        name = safe_name(artifact.get("file"))
        if name in names or index < len(modules) and artifact["id"] in ids:
            raise ValueError("Duplicate artifact")
        names.add(name)
        if index < len(modules):
            ids.add(artifact["id"])
        digest = artifact.get("sha256")
        size = artifact.get("size")
        file = directory / name
        if (not isinstance(digest, str) or not DIGEST.fullmatch(digest)
                or type(size) is not int or not 1 <= size <= 268435456
                or file.is_symlink() or not file.is_file() or file.stat().st_size != size or sha256(file) != digest):
            raise ValueError(f"Artifact digest/size mismatch: {name}")
    expected = names | {"manifest.json"}
    if {file.name for file in directory.iterdir()} != expected:
        raise ValueError("Release contains unexpected files")
    return manifest


@contextlib.contextmanager
def unpack(source):
    if source.is_dir():
        yield source
        return
    with tempfile.TemporaryDirectory(prefix="qhot-unpack-") as temporary:
        directory = Path(temporary)
        with zipfile.ZipFile(source) as archive:
            if len(archive.infolist()) > 33 or sum(item.file_size for item in archive.infolist()) > MAX_ARCHIVE_BYTES:
                raise ValueError("Release archive exceeds limits")
            names = set()
            for entry in archive.infolist():
                safe_name(entry.filename)
                if entry.filename in names or entry.is_dir() or stat.S_ISLNK(entry.external_attr >> 16):
                    raise ValueError("Duplicate, directory or symlink archive entry")
                names.add(entry.filename)
                with archive.open(entry) as incoming, (directory / entry.filename).open("xb") as outgoing:
                    shutil.copyfileobj(incoming, outgoing)
        yield directory


def stage(source, root, manifest):
    releases = root / "releases"
    releases.mkdir(parents=True, exist_ok=True)
    if releases.is_symlink():
        raise ValueError("Release root must not be a symlink")
    target = releases / manifest["releaseId"]
    if target.exists() or target.is_symlink():
        if validate(target) != manifest:
            raise ValueError("Release ID is immutable; choose a different ID")
        return target
    temporary = Path(tempfile.mkdtemp(prefix=".incoming-", dir=releases))
    try:
        for file in source.iterdir():
            shutil.copyfile(file, temporary / file.name)
        if validate(temporary) != manifest:
            raise ValueError("Source release changed while copying")
        try:
            temporary.rename(target)
        except OSError:
            if not target.exists() or validate(target) != manifest:
                raise
        return target
    finally:
        shutil.rmtree(temporary, ignore_errors=True)


def atomic_json(file, value):
    handle, name = tempfile.mkstemp(prefix=".request-", dir=file.parent)
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as output:
            json.dump(value, output, ensure_ascii=False)
            output.flush()
            os.fsync(output.fileno())
        os.replace(name, file)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def deploy(source, root, timeout=60, expected_host=None):
    status = read_json(root / "status.json")
    if expected_host is not None and status.get("hostName") != safe_name(expected_host):
        raise ValueError(f"Wrong backend: expected {expected_host}, found {status.get('hostName')}")
    if (status.get("formatVersion") != 1 or status.get("apiVersion") not in SUPPORTED_API_VERSIONS or not status.get("running")
            or status.get("phase") != "ready" or status.get("restartRequired")
            or abs(time.time() * 1000 - status.get("updatedAt", 0)) > 30000):
        raise ValueError("Host is stopped, stale, busy or requires restart")
    with unpack(source) as directory:
        manifest = validate(directory)
        if (manifest["apiVersion"] != status["apiVersion"]
                or manifest["hosts"].get(status.get("hostName")) != status.get("hostCodeSha256")):
            return {"ok": False, "code": "RESTART_REQUIRED", "releaseId": manifest["releaseId"],
                    "message": "Host code/dependencies changed; deploy the full plugin JAR during a backend restart"}
        stage(directory, root, manifest)
    requests, results = root / "requests", root / "results"
    requests.mkdir(exist_ok=True)
    if requests.is_symlink() or results.is_symlink():
        raise ValueError("Management directories must not be symlinks")
    request_id = str(uuid.uuid4())
    request = {"requestId": request_id, "instanceId": status["instanceId"], "releaseId": manifest["releaseId"],
               "expectedActive": status.get("activeRelease"), "createdAt": int(time.time() * 1000)}
    atomic_json(requests / f"{request_id}.json", request)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        receipt = results / f"{request_id}.json"
        if receipt.exists():
            response = read_json(receipt)
            if response.get("requestId") != request_id or response.get("instanceId") != status["instanceId"]:
                raise ValueError("Deployment receipt does not match request/server process")
            result = response["result"]
            if result.get("ok") and result.get("releaseId") != manifest["releaseId"]:
                raise ValueError("Host confirmed a different release")
            result["requestId"] = request_id
            return result
        time.sleep(min(0.1, max(0, deadline - time.monotonic())))
    raise TimeoutError(f"No host confirmation for request {request_id}; inspect results before retrying")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", required=True, type=Path)
    parser.add_argument("--hot-dir", required=True, type=Path)
    parser.add_argument("--host-name", help="Expected release host identity; reject another backend before staging")
    parser.add_argument("--timeout", type=float, default=60)
    args = parser.parse_args()
    if not 0 < args.timeout <= 120:
        parser.error("timeout must be between 0 and 120 seconds")
    try:
        result = deploy(args.release, args.hot_dir, args.timeout, expected_host=args.host_name)
        print(json.dumps(result, ensure_ascii=False))
        return 0 if result.get("ok") and result.get("code") != "ACTIVE_RESTART_REQUIRED" else 20 if "RESTART_REQUIRED" in result.get("code", "") else 1
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print(json.dumps({"ok": False, "code": "DEPLOYMENT_FAILED", "message": str(error)}, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
