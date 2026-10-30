#!/usr/bin/env python3
"""Explicit first installation/host upgrade for the two known Docker-backed Minecraft instances.

Stages the verified JAR in Paper's update folder, then gracefully stops the container's Java process.
Never sends SIGKILL or changes container configuration. A separate deploy step confirms the modules.
"""
import argparse
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile
import time
import zipfile

TARGETS = {'QuantumPlugin': Path('/opt/server/qo2'), 'QuantumPlugin-Creative': Path('/opt/server/qo_creative')}


def docker(*arguments):
    result = subprocess.run(['docker', *arguments], capture_output=True, text=True, timeout=40)
    if result.returncode: raise RuntimeError('Docker operation failed: ' + result.stderr.strip())
    return result.stdout


def find_container(directory):
    ids = docker('ps', '-aq').split()
    matches = [c for c in json.loads(docker('inspect', *ids)) if any(m.get('Source') == str(directory) for m in c.get('Mounts', []))] if ids else []
    if len(matches) != 1: raise RuntimeError('Expected exactly one container mounting the configured backend')
    return matches[0]


def java_pids(container_id):
    lines = docker('top', container_id, '-eo', 'pid,comm').splitlines()[1:]
    return [int(parts[0]) for line in lines if len(parts := line.split()) == 2 and parts[1] == 'java']


def java_process(container_id):
    pids = java_pids(container_id)
    if len(pids) != 1: raise RuntimeError('Expected exactly one Java process in the backend container')
    proc = Path('/proc') / str(pids[0])
    token = (proc / 'stat').read_text().rsplit(')', 1)[1].split()[19]
    return pids[0], token


def alive(pid, token):
    try:
        values = (Path('/proc') / str(pid) / 'stat').read_text().rsplit(')', 1)[1].split()
        return values[19] == token and values[0] != 'Z'
    except (OSError, IndexError): return False


def plugin_jars(directory):
    matches = []
    for jar in directory.glob('*.jar'):
        try:
            with zipfile.ZipFile(jar) as zipped:
                metadata = zipped.read('plugin.yml').decode()
            if re.search(r'^name:\s*[\'"]?QuantumPlugin[\'"]?\s*$', metadata, re.M): matches.append(jar)
        except (KeyError, OSError, ValueError, zipfile.BadZipFile): pass
    if len(matches) != 1: raise RuntimeError('Expected exactly one existing QuantumPlugin JAR in this backend')
    return matches


def update_directory(directory):
    name = 'update'
    for line in (directory / 'bukkit.yml').read_text().splitlines():
        if line.strip().startswith('update-folder:'): name = line.split(':', 1)[1].strip().strip('\'"')
    if not name: raise RuntimeError('Paper update folder is disabled')
    plugins = (directory / 'plugins').resolve()
    target = (plugins / name).resolve()
    if plugins not in target.parents: raise RuntimeError('Update folder must be inside the configured plugins directory')
    return target


def install(archive, hot, host):
    if host not in TARGETS: raise ValueError('Unknown backend host')
    directory = TARGETS[host].resolve()
    if hot.resolve() != directory / 'plugins/QuantumPlugin/hot': raise ValueError('Backend path does not match its host identity')
    spec = importlib.util.spec_from_file_location('deploy_hot', Path(__file__).with_name('deploy-hot.py'))
    client = importlib.util.module_from_spec(spec); spec.loader.exec_module(client)
    with client.unpack(archive) as release:
        manifest = client.validate(release)
        artifact = next((item for item in manifest['plugins'] if item['id'] == host), None)
        if artifact is None: raise ValueError('Release has no full plugin for this host')
        source = release / artifact['file']
        current = plugin_jars(directory / 'plugins')[0]
        if client.sha256(current) == artifact['sha256']:
            print(json.dumps({'host': host, 'code': 'HOST_ALREADY_INSTALLED'}), flush=True); return
        container = find_container(directory)
        if not container['State']['Running']: raise RuntimeError('Backend must be running before an explicit upgrade')
        pid, token = java_process(container['Id'])
        updates = update_directory(directory)
        if updates.exists() and any(file.name != current.name for file in updates.glob('*.jar')):
            raise RuntimeError('Other pending plugin updates exist; review them before restarting')
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        backup = directory / 'backups/qplugin' / (manifest['releaseId'] + '-' + stamp)
        backup.mkdir(parents=True, exist_ok=False)
        shutil.copy2(current, backup / current.name)
        data = directory / 'plugins/QuantumPlugin'
        if data.exists(): shutil.copytree(data, backup / 'QuantumPlugin', symlinks=True)
        updates.mkdir(parents=True, exist_ok=True)
        destination = updates / current.name
        if destination.exists(): shutil.copy2(destination, backup / ('pending-' + current.name))
        handle, temporary = tempfile.mkstemp(prefix='.qplugin-host-', dir=updates)
        try:
            with os.fdopen(handle, 'wb') as out, source.open('rb') as incoming:
                shutil.copyfileobj(incoming, out); out.flush(); os.fsync(out.fileno())
            os.chmod(temporary, 0o644)
            os.replace(temporary, destination)
        finally:
            if os.path.exists(temporary): os.unlink(temporary)
        before = {'host': host, 'releaseId': manifest['releaseId'], 'container': container['Id'][:12],
            'oldJar': str(current), 'newSha256': artifact['sha256'], 'backup': str(backup), 'staged': str(destination)}
        (backup / 'receipt.json').write_text(json.dumps(before, indent=2))
        print(json.dumps(dict(before, phase='staged')), flush=True)
        if not alive(pid, token): raise RuntimeError('Backend process changed before shutdown; staged update was preserved')
        os.kill(pid, signal.SIGTERM)
        deadline = time.monotonic() + 150
        while alive(pid, token) and time.monotonic() < deadline: time.sleep(1)
        if alive(pid, token): raise RuntimeError('Graceful shutdown exceeded 150 seconds; no SIGKILL was sent')
        # Docker start preserves all existing ports, bind mounts and launch arguments.
        for _ in range(10):
            state = json.loads(docker('inspect', container['Id']))[0]['State']
            if not state['Running']: break
            time.sleep(1)
        state = json.loads(docker('inspect', container['Id']))[0]['State']
        if state['Running']:
            # A supervisor may already have restored Java. Never restart it again or force-kill a wrapper.
            if not java_pids(container['Id']):
                raise RuntimeError('Java saved and exited, but the container wrapper remains running; stop/start it manually. Backup: ' + str(backup))
        else: docker('start', container['Id'])
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            try:
                status = json.loads((hot / 'status.json').read_text())
                if (status.get('running') and status.get('phase') == 'ready' and status.get('hostName') == host
                        and status.get('hostCodeSha256') == manifest['hosts'][host]
                        and client.sha256(current) == artifact['sha256']):
                    time.sleep(3)
                    final = json.loads((hot / 'status.json').read_text())
                    if final.get('running'):
                        result = dict(before, phase='ready', instanceId=final.get('instanceId'))
                        (backup / 'receipt.json').write_text(json.dumps(result, indent=2))
                        print(json.dumps(result), flush=True); return
            except (OSError, ValueError): pass
            time.sleep(2)
        raise RuntimeError('Backend did not load the expected host after restart; backup is at ' + str(backup))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', type=Path, required=True)
    parser.add_argument('--hot-dir', type=Path, required=True)
    parser.add_argument('--host-name', choices=list(TARGETS), required=True)
    args = parser.parse_args()
    install(args.release, args.hot_dir, args.host_name)
