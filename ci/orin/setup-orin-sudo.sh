#!/usr/bin/env bash
# Run once as root: sudo bash setup-orin-sudo.sh glowingstone
set -euo pipefail
PATH=/usr/sbin:/usr/bin:/sbin:/bin
export PATH

QPLUGIN_ACCOUNT="${1:-glowingstone}"
[[ "$QPLUGIN_ACCOUNT" =~ ^[a-z_][a-z0-9_-]*$ ]] || { echo 'Invalid login name' >&2; exit 1; }
[[ "$EUID" -eq 0 ]] || { echo "Run: sudo bash $0 $QPLUGIN_ACCOUNT" >&2; exit 1; }
id "$QPLUGIN_ACCOUNT" >/dev/null
QPLUGIN_VISUDO="$(command -v visudo)"
QPLUGIN_ADMIN=/usr/local/sbin/qplugin-orin-admin
QPLUGIN_RULE=/etc/sudoers.d/90-qplugin-orin
QPLUGIN_SOURCE_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
QPLUGIN_ADMIN_SOURCE="$QPLUGIN_SOURCE_DIRECTORY/qplugin-orin-admin.sh"
[[ -f "$QPLUGIN_ADMIN_SOURCE" && ! -L "$QPLUGIN_ADMIN_SOURCE" ]] || {
  echo "Missing fixed helper next to setup script: $QPLUGIN_ADMIN_SOURCE" >&2
  exit 1
}
bash -n "$QPLUGIN_ADMIN_SOURCE"
QPLUGIN_TEMP="$(mktemp -d)"
trap 'rm -rf -- "$QPLUGIN_TEMP"' EXIT

printf '%s ALL=(root) NOPASSWD: %s status, %s restart\n' \
  "$QPLUGIN_ACCOUNT" "$QPLUGIN_ADMIN" "$QPLUGIN_ADMIN" > "$QPLUGIN_TEMP/sudoers"
"$QPLUGIN_VISUDO" -cf "$QPLUGIN_TEMP/sudoers"

QPLUGIN_STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
for QPLUGIN_EXISTING in "$QPLUGIN_ADMIN" "$QPLUGIN_RULE"; do
  [[ ! -L "$QPLUGIN_EXISTING" ]] || { echo 'Refusing to overwrite a symlink' >&2; exit 1; }
  if [[ -e "$QPLUGIN_EXISTING" ]]; then cp -a -- "$QPLUGIN_EXISTING" "$QPLUGIN_EXISTING.backup-$QPLUGIN_STAMP"; fi
done
for QPLUGIN_DIRECTORY in /usr/local/sbin /etc/sudoers.d; do
  [[ -d "$QPLUGIN_DIRECTORY" ]] || install -d -o root -g root -m 0755 "$QPLUGIN_DIRECTORY"
done
install -o root -g root -m 0755 "$QPLUGIN_ADMIN_SOURCE" "$QPLUGIN_ADMIN"
install -o root -g root -m 0440 "$QPLUGIN_TEMP/sudoers" "$QPLUGIN_RULE"
"$QPLUGIN_VISUDO" -cf /etc/sudoers
echo "Installed Orin status/restart permission for $QPLUGIN_ACCOUNT"
echo "Check: sudo -n $QPLUGIN_ADMIN status"
