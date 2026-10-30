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
  cp -R "$source_root/ci/orin/actions/$action" "$workspace/actions/"
done
for action in qplugin-deploy qplugin-ci; do
  cp "$source_root/ci/orin/actions/qplugin-build/build.sh" "$workspace/actions/$action/"
  cp "$source_root/scripts/deploy-hot.py" "$workspace/actions/$action/"
done
for file in deploy.sh install-host.py; do
  cp "$source_root/ci/orin/actions/qplugin-deploy/$file" "$workspace/actions/qplugin-ci/"
done
echo "Installed QPlugin actions. Backup: $backup"
