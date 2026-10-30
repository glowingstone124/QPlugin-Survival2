#!/usr/bin/env bash
set -euo pipefail
: "${ORIN_CONTEXT_ARCHIVE:?Orin action must provide the downloaded release archive}"
: "${ORIN_CONTEXT_HOT_DIR:?Orin pipeline must select its backend hot directory}"
: "${ORIN_CONTEXT_HOST_NAME:?Orin pipeline must select its backend host identity}"
if [[ "${ORIN_CONTEXT_INSTALL_HOST:-false}" == true ]]; then
  python3 "${ORIN_ACTION_DIR:?}/install-host.py" --release "$ORIN_CONTEXT_ARCHIVE" \
    --hot-dir "$ORIN_CONTEXT_HOT_DIR" --host-name "$ORIN_CONTEXT_HOST_NAME"
fi
python3 "${ORIN_ACTION_DIR:?}/deploy-hot.py" --release "$ORIN_CONTEXT_ARCHIVE" \
  --hot-dir "$ORIN_CONTEXT_HOT_DIR" --host-name "$ORIN_CONTEXT_HOST_NAME" --timeout 60
