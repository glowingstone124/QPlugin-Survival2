#!/usr/bin/env python3
"""Read-only inventory of the two configured backends; excludes credentials and player names."""
import json
import os
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import tempfile

TARGETS = {'survival': Path('/opt/server/qo2'), 'creative': Path('/opt/server/qo_creative')}


def varint(value):
    value &= 0xffffffff
    out = bytearray()
    while True:
        byte = value & 0x7f; value >>= 7
        out.append(byte | (0x80 if value else 0))
        if not value: return bytes(out)


def ping(port):
    def receive(stream, size):
        data = b''
        while len(data) < size:
            block = stream.recv(size - len(data))
            if not block: raise EOFError('Status connection closed')
            data += block
        return data
    def number(stream):
        value = 0
        for shift in range(0, 35, 7):
            byte = receive(stream, 1)[0]; value |= (byte & 127) << shift
            if not byte & 128: return value
        raise ValueError('Invalid status packet')
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=2) as connection:
            host = b'localhost'
            handshake = b'\0' + varint(-1) + varint(len(host)) + host + struct.pack('>H', port) + b'\x01'
            connection.sendall(varint(len(handshake)) + handshake + b'\x01\x00')
            length = number(connection)
            if not 1 <= length <= 1048576: raise ValueError('Invalid status length')
            number(connection); size = number(connection)
            reply = json.loads(receive(connection, size))
            return {'online': reply.get('players', {}).get('online'), 'maxPlayers': reply.get('players', {}).get('max'), 'version': reply.get('version', {}).get('name')}
    except (OSError, ValueError, EOFError) as error:
        return {'error': str(error)}


def inventory():
    result = {'targets': {}, 'processes': [], 'containers': [], 'mcsDirectories': []}
    for role, directory in TARGETS.items():
        settings = {}
        for line in (directory / 'server.properties').read_text().splitlines():
            if line and not line.startswith('#') and '=' in line:
                key, value = line.split('=', 1); settings[key] = value
        update_folder = 'update'
        for line in (directory / 'bukkit.yml').read_text().splitlines():
            if line.strip().startswith('update-folder:'):
                update_folder = line.split(':', 1)[1].strip().strip('"\'')
        result['targets'][role] = {'directory': str(directory), 'updateFolder': update_folder,
            'configuredPort': int(settings.get('server-port', 25565)), 'jars': [p.name for p in (directory / 'plugins').glob('*QuantumPlugin*.jar')]}
    for proc in Path('/proc').glob('[0-9]*'):
        try:
            executable = Path(os.readlink(proc / 'exe')).name
            cwd = Path(os.readlink(proc / 'cwd')).resolve()
            argv = [v.decode(errors='replace') for v in (proc / 'cmdline').read_bytes().split(b'\0') if v]
            if executable == 'java' and cwd in [*TARGETS.values(), Path('/opt/server/orin')]:
                item = {'pid': int(proc.name), 'cwd': str(cwd), 'uid': proc.stat().st_uid}
                if '-jar' in argv: item['jar'] = argv[argv.index('-jar') + 1]
                for option in ('--port', '-port', '-p'):
                    if option in argv and argv[argv.index(option) + 1].isdigit(): item['port'] = int(argv[argv.index(option) + 1])
                result['processes'].append(item)
            if executable.startswith('node') and ('mcs' in str(cwd).lower() or (cwd / 'data/InstanceConfig').is_dir()):
                result['mcsDirectories'].append(str(cwd))
        except (OSError, ValueError, IndexError):
            continue
    if shutil.which('docker'):
        listing = subprocess.run(['docker', 'ps', '-aq'], capture_output=True, text=True, timeout=10)
        if listing.returncode == 0 and listing.stdout.strip():
            raw = subprocess.run(['docker', 'inspect', *listing.stdout.split()], capture_output=True, text=True, timeout=15)
            for container in json.loads(raw.stdout):
                mounts = [{k: m.get(k) for k in ['Source', 'Destination', 'RW']} for m in container.get('Mounts', [])]
                roles = [role for role, path in TARGETS.items() if any(m['Source'] == str(path) for m in mounts)]
                if not roles: continue
                config = container.get('Config', {}); state = container.get('State', {})
                result['containers'].append({'id': container['Id'][:12], 'name': container['Name'], 'roles': roles,
                    'running': state.get('Running'), 'pid': state.get('Pid'), 'image': config.get('Image'),
                    'ports': container.get('NetworkSettings', {}).get('Ports'), 'mounts': mounts,
                    'restartPolicy': container.get('HostConfig', {}).get('RestartPolicy')})
    for role, target in result['targets'].items():
        port = next((p.get('port', target['configuredPort']) for p in result['processes'] if p['cwd'] == target['directory']), target['configuredPort'])
        for container in result['containers']:
            if role in container['roles']:
                binding = (container.get('ports') or {}).get('25565/tcp')
                if binding: port = int(binding[0]['HostPort'])
        target['statusPort'] = port
        target['serverStatus'] = ping(port)
    return result


if __name__ == '__main__':
    result = inventory()
    root = Path(os.environ['ORIN_WORKSPACE_DIR'])
    handle, name = tempfile.mkstemp(prefix='.qplugin-status-', dir=root)
    try:
        with os.fdopen(handle, 'w') as out: json.dump(result, out, ensure_ascii=False, indent=2)
        os.chmod(name, 0o640); os.chown(name, -1, root.stat().st_gid)
        os.replace(name, root / 'qplugin-status.json')
    finally:
        if os.path.exists(name): os.unlink(name)
    print(json.dumps(result, ensure_ascii=False))
