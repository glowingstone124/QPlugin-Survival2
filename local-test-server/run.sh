#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_DIR="$ROOT_DIR/local-test-server"
PLUGIN_JAR="$ROOT_DIR/survival/build/libs/QuantumPlugin-1.14.5.5.1.jar"
SERVER_VERSION="${SERVER_VERSION:-26.2}"
SERVER_JAR="$SERVER_DIR/server-$SERVER_VERSION.jar"
PURPUR_URL="${PURPUR_URL:-https://api.purpurmc.org/v2/purpur/$SERVER_VERSION/latest/download}"
if [[ -z "${JAVA_BIN:-}" ]]; then
  JAVA_BIN="java"
  for candidate in "$HOME"/.sdkman/candidates/java/25*/bin/java; do
    if [[ -x "$candidate" ]]; then
      JAVA_BIN="$candidate"
      break
    fi
  done
fi
JAVA_FLAGS="${JAVA_FLAGS:--Xms1G -Xmx2G}"
DISABLE_QO_API="${DISABLE_QO_API:-true}"
FALLEN_LOCAL_TEST="${FALLEN_LOCAL_TEST:-true}"
export DISABLE_QO_API
export FALLEN_LOCAL_TEST

if ! command -v curl >/dev/null 2>&1; then
  echo "Missing required command: curl" >&2
  exit 1
fi
if [[ "$JAVA_BIN" == */* ]]; then
  [[ -x "$JAVA_BIN" ]] || { echo "Java is not executable: $JAVA_BIN" >&2; exit 1; }
elif ! command -v "$JAVA_BIN" >/dev/null 2>&1; then
  echo "Java was not found. Install Java 25 or set JAVA_BIN." >&2
  exit 1
fi

cd "$ROOT_DIR"
echo "[1/4] Building QuantumPlugin..."
./gradlew :survival:shadowJar --no-configuration-cache

echo "[2/4] Deploying plugin to the local server..."
mkdir -p "$SERVER_DIR/plugins"
cp "$PLUGIN_JAR" "$SERVER_DIR/plugins/QuantumPlugin.jar"
mkdir -p "$SERVER_DIR/plugins/QuantumPlugin"
if [[ "${RESET_FALLEN:-0}" == "1" || ! -f "$SERVER_DIR/plugins/QuantumPlugin/fallen.yml" ]]; then
  cp "$SERVER_DIR/fallen-local-test.yml" "$SERVER_DIR/plugins/QuantumPlugin/fallen.yml"
fi

if [[ ! -f "$SERVER_JAR" ]]; then
  echo "[3/4] Downloading Purpur $SERVER_VERSION..."
  curl -L "$PURPUR_URL" -o "$SERVER_JAR"
else
  echo "[3/4] Using cached Purpur $SERVER_VERSION server jar."
fi

cd "$SERVER_DIR"
if [[ "${RESET_WORLD:-0}" == "1" ]]; then
  rm -rf world world_nether world_the_end
fi
echo "[4/4] Starting the server at localhost:25565"
echo "      Finale preview: /fallen finale test A"
echo "      Stop safely with: stop"
exec "$JAVA_BIN" $JAVA_FLAGS -jar "$SERVER_JAR" nogui
