#!/usr/bin/env bash
# Run once as root: sudo bash setup-orin-sudo.sh glowingstone
set -euo pipefail

QPLUGIN_ACCOUNT="${1:-glowingstone}"
[[ "$QPLUGIN_ACCOUNT" =~ ^[a-z_][a-z0-9_-]*$ ]] || { echo 'Invalid login name' >&2; exit 1; }
[[ "$EUID" -eq 0 ]] || { echo "Run: sudo bash $0 $QPLUGIN_ACCOUNT" >&2; exit 1; }
id "$QPLUGIN_ACCOUNT" >/dev/null
QPLUGIN_VISUDO="$(command -v visudo)"
[[ -x /usr/bin/python3 ]] || { echo 'Python 3 is required at /usr/bin/python3' >&2; exit 1; }
QPLUGIN_ADMIN=/usr/local/sbin/qplugin-orin-admin
QPLUGIN_RULE=/etc/sudoers.d/90-qplugin-orin
QPLUGIN_TEMP="$(mktemp -d)"
trap 'rm -rf -- "$QPLUGIN_TEMP"' EXIT

cat > "$QPLUGIN_TEMP/admin" <<'PYTHON'
#!/usr/bin/python3 -I
"""Root-owned, fixed-target status/restart helper. Accepts no PIDs, paths or shell commands."""
import json
import os
from pathlib import Path
import signal
import sys

ORIN_DIRECTORY = Path('/opt/server/orin')
ORIN_JAR = ORIN_DIRECTORY / 'Orin-all.jar'
PROC_DIRECTORY = Path('/proc')


def start_token(directory):
    # Fields following the final ')' begin with state (field 3); starttime is field 22.
    return (directory / 'stat').read_text().rsplit(')', 1)[1].split()[19]


def find_processes():
    found = []
    for directory in PROC_DIRECTORY.glob('[0-9]*'):
        try:
            if Path(os.readlink(directory / 'exe')).name != 'java':
                continue
            cwd = Path(os.readlink(directory / 'cwd')).resolve()
            if cwd != ORIN_DIRECTORY.resolve():
                continue
            argv = [value.decode(errors='replace') for value in (directory / 'cmdline').read_bytes().split(b'\0') if value]
            if '-jar' not in argv:
                continue
            jar = Path(argv[argv.index('-jar') + 1])
            if not jar.is_absolute():
                jar = cwd / jar
            if jar.resolve() != ORIN_JAR.resolve():
                continue
            pid = int(directory.name)
            try:
                children = [int(value) for value in (directory / 'task' / str(pid) / 'children').read_text().split()]
            except FileNotFoundError:
                children = []
            found.append({'pid': pid, 'uid': directory.stat().st_uid, 'cwd': str(cwd),
                          'jar': str(ORIN_JAR), 'children': children, 'startToken': start_token(directory)})
        except (OSError, ValueError, IndexError):
            continue
    return found


def main(arguments=None):
    arguments = sys.argv[1:] if arguments is None else arguments
    if len(arguments) != 1 or arguments[0] not in ('status', 'restart'):
        print('Usage: qplugin-orin-admin status|restart', file=sys.stderr)
        return 2
    if os.geteuid() != 0:
        print('Run through the installed sudo rule', file=sys.stderr)
        return 1
    processes = find_processes()
    if arguments[0] == 'status':
        print(json.dumps({'processes': processes}))
        return 0
    if len(processes) != 1:
        print('Refusing restart: expected exactly one matching Orin JVM', file=sys.stderr)
        return 3
    process = processes[0]
    if process['children']:
        print('Refusing restart: Orin has active child processes; retry after CI tasks finish', file=sys.stderr)
        return 3
    try:
        if start_token(PROC_DIRECTORY / str(process['pid'])) != process['startToken']:
            print('Refusing restart: process changed during inspection', file=sys.stderr)
            return 4
        os.kill(process['pid'], signal.SIGTERM)
    except (OSError, ValueError, IndexError) as error:
        print('Could not signal the inspected Orin process: ' + str(error), file=sys.stderr)
        return 4
    print(json.dumps({'signal': 'SIGTERM', 'pid': process['pid'], 'supervisor': 'MCSManager'}))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
PYTHON

printf '%s ALL=(root) NOPASSWD: %s status, %s restart\n' \
  "$QPLUGIN_ACCOUNT" "$QPLUGIN_ADMIN" "$QPLUGIN_ADMIN" > "$QPLUGIN_TEMP/sudoers"
"$QPLUGIN_VISUDO" -cf "$QPLUGIN_TEMP/sudoers"

QPLUGIN_STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
for QPLUGIN_EXISTING in "$QPLUGIN_ADMIN" "$QPLUGIN_RULE"; do
  [[ ! -L "$QPLUGIN_EXISTING" ]] || { echo 'Refusing to overwrite a symlink' >&2; exit 1; }
  if [[ -e "$QPLUGIN_EXISTING" ]]; then
    cp -a -- "$QPLUGIN_EXISTING" "$QPLUGIN_EXISTING.backup-$QPLUGIN_STAMP"
  fi
done
for QPLUGIN_DIRECTORY in /usr/local/sbin /etc/sudoers.d; do
  if [[ ! -d "$QPLUGIN_DIRECTORY" ]]; then
    install -d -o root -g root -m 0755 "$QPLUGIN_DIRECTORY"
  fi
done
install -o root -g root -m 0755 "$QPLUGIN_TEMP/admin" "$QPLUGIN_ADMIN"
install -o root -g root -m 0440 "$QPLUGIN_TEMP/sudoers" "$QPLUGIN_RULE"
"$QPLUGIN_VISUDO" -cf /etc/sudoers
echo "Installed Orin status/restart permission for $QPLUGIN_ACCOUNT"
echo "Check: sudo -n $QPLUGIN_ADMIN status"
