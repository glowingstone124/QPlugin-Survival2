#!/usr/bin/env bash
set -euo pipefail
source_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
workspace="${1:-/opt/server/orin}"
[[ "$workspace" == /* && -d "$workspace/actions" ]] || { echo 'Pass an existing absolute Orin workspace' >&2; exit 1; }
backup="$workspace/backups/actions-$(date +%Y%m%dT%H%M%S)-$$"
mkdir -p "$backup"
chmod 700 "$backup"
for action in qplugin-build qplugin-deploy qplugin-status qplugin-ci; do
  if [[ -d "$workspace/actions/$action" ]]; then cp -R "$workspace/actions/$action" "$backup/"; fi
  mkdir -p "$workspace/actions/$action"
  for file in "$source_root/ci/orin/actions/$action/"*; do
    if [[ -f "$file" ]]; then cp "$file" "$workspace/actions/$action/"; fi
  done
done
# Backups above retain the previous action version. Remove only retired entrypoints so a
# KTS installation cannot silently keep executing or packaging legacy implementations.
rm -f -- \
  "$workspace/actions/qplugin-deploy/deploy.sh" \
  "$workspace/actions/qplugin-deploy/deploy-hot.py" \
  "$workspace/actions/qplugin-deploy/install-host.py" \
  "$workspace/actions/qplugin-ci/deploy.sh" \
  "$workspace/actions/qplugin-ci/deploy-hot.py" \
  "$workspace/actions/qplugin-ci/install-host.py" \
  "$workspace/actions/qplugin-status/main.sh" \
  "$workspace/actions/qplugin-status/status.py"
for cache in \
  "$workspace/actions/qplugin-deploy/__pycache__" \
  "$workspace/actions/qplugin-ci/__pycache__" \
  "$workspace/actions/qplugin-status/__pycache__"
do
  if [[ -d "$cache" && ! -L "$cache" ]]; then
    # Old interpreters may have created root-owned bytecode. Delete it when possible;
    # otherwise retire the fixed cache name in place so it can no longer be imported.
    if ! find "$cache" -depth -delete 2>/dev/null; then
      mv -- "$cache" "$cache.retired-$(basename -- "$backup")"
    fi
  fi
done
for action in qplugin-deploy qplugin-ci; do
  cp "$source_root/ci/orin/actions/qplugin-build/build.sh" "$workspace/actions/$action/"
done
# qplugin-ci exposes GitHub context through its own manifest, but executes the exact same
# audited KTS implementation as manual deployment. This prevents production copies drifting.
cp "$source_root/ci/orin/actions/qplugin-deploy/main.kts" "$workspace/actions/qplugin-ci/main.kts"
for helper in setup-orin-sudo.sh qplugin-orin-admin.sh; do
  destination="$workspace/$helper"
  [[ ! -L "$destination" ]] || { echo "Refusing to replace symlink: $destination" >&2; exit 1; }
  if [[ -e "$destination" ]]; then cp -a -- "$destination" "$backup/$helper"; fi
  install -m 0755 -- "$source_root/ci/orin/$helper" "$destination"
done
echo "Installed QPlugin actions. Backup: $backup"
