#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

printf '\033]0;QPlugin Fallen Local Test Server\007'
echo "QPlugin Fallen local test server"
echo "Project: $PROJECT_DIR"
echo

exec "$PROJECT_DIR/local-test-server/run.sh"
