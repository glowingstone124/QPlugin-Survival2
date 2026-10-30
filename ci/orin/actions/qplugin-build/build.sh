#!/usr/bin/env bash
set -euo pipefail
QPLUGIN_REPOSITORY="${ORIN_CONTEXT_REPOSITORY:-${QPLUGIN_REPOSITORY:-}}"
: "${QPLUGIN_REPOSITORY:?Set a trusted Git repository URL on the build node}"
: "${ORIN_CONTEXT_RECEIPT:?Orin action must provide a build receipt path}"
: "${ORIN_CONTEXT_OUTPUT_ARCHIVE:?Orin action must provide a temporary output archive path}"
qplugin_java_home="${ORIN_CONTEXT_JAVA_HOME:-${QPLUGIN_JAVA_HOME:-${JAVA_HOME:-}}}"
if [[ -z "$qplugin_java_home" || ! -x "$qplugin_java_home/bin/java" || ! -x "$qplugin_java_home/bin/javac" ]]; then
  echo 'QPlugin build requires a full JDK with bin/java and bin/javac. Set QPLUGIN_JAVA_HOME to the JDK 25 installation; Orin can keep its current JRE.' >&2
  exit 1
fi
export JAVA_HOME="$qplugin_java_home"
export PATH="$JAVA_HOME/bin:$PATH"
printf 'QPlugin build JAVA_HOME: %s\n' "$JAVA_HOME"
"$JAVA_HOME/bin/java" -version
"$JAVA_HOME/bin/javac" -version
qplugin_proxy="${ORIN_CONTEXT_HTTP_PROXY:-${QPLUGIN_HTTP_PROXY:-}}"
if [[ -n "$qplugin_proxy" ]]; then
  export http_proxy="$qplugin_proxy" https_proxy="$qplugin_proxy" HTTP_PROXY="$qplugin_proxy" HTTPS_PROXY="$qplugin_proxy"
  qplugin_java_proxy="${ORIN_CONTEXT_JAVA_PROXY:?KTS action must validate and provide JAVA proxy options}"
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }$qplugin_java_proxy"
fi
revision="${ORIN_CONTEXT_REVISION:-main}"
[[ "$revision" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]{0,199}$ && "$revision" != *..* ]] || { echo 'Invalid revision' >&2; exit 1; }
work="$(mktemp -d "${TMPDIR:-/tmp}/qplugin-build.XXXXXXXX")"
trap 'rm -rf -- "$work"' EXIT
git init --quiet "$work/repo"
git -C "$work/repo" remote add origin "$QPLUGIN_REPOSITORY"
git -C "$work/repo" -c http.lowSpeedLimit=1024 -c http.lowSpeedTime=60 fetch --depth=1 origin "$revision"
git -C "$work/repo" checkout --quiet --detach FETCH_HEAD
commit="$(git -C "$work/repo" rev-parse --verify HEAD)"
(
  cd "$work/repo"
  ./gradlew "-Dorg.gradle.java.home=$JAVA_HOME" --no-daemon build packageHotRelease "-PreleaseId=$commit"
)
install -m 0644 -- "$work/repo/build/releases/$commit.zip" "$ORIN_CONTEXT_OUTPUT_ARCHIVE"
sync -f "$ORIN_CONTEXT_OUTPUT_ARCHIVE" 2>/dev/null || sync
printf '%s\n%s\n' "$commit" "$ORIN_CONTEXT_OUTPUT_ARCHIVE" > "$ORIN_CONTEXT_RECEIPT"
sync -f "$ORIN_CONTEXT_RECEIPT" 2>/dev/null || sync
