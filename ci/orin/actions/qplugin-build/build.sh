#!/usr/bin/env bash
set -euo pipefail
QPLUGIN_REPOSITORY="${ORIN_CONTEXT_REPOSITORY:-${QPLUGIN_REPOSITORY:-}}"
QPLUGIN_RELEASE_STORE="${ORIN_CONTEXT_RELEASE_STORE:-${QPLUGIN_RELEASE_STORE:-}}"
: "${QPLUGIN_REPOSITORY:?Set a trusted Git repository URL on the build node}"
: "${QPLUGIN_RELEASE_STORE:?Set an absolute persistent release-store directory}"
: "${ORIN_CONTEXT_RECEIPT:?Orin action must provide a build receipt path}"
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
  qplugin_java_proxy="$(python3 - "$qplugin_proxy" <<'PY'
import sys, urllib.parse
p = urllib.parse.urlsplit(sys.argv[1])
if p.scheme not in ('http', 'https') or not p.hostname:
    raise SystemExit('QPLUGIN_HTTP_PROXY must be an HTTP proxy URL')
port = p.port or (443 if p.scheme == 'https' else 80)
print('-Dhttp.proxyHost=%s -Dhttp.proxyPort=%s -Dhttps.proxyHost=%s -Dhttps.proxyPort=%s -Dhttp.nonProxyHosts=localhost|127.*|[::1]' % (p.hostname, port, p.hostname, port))
PY
)"
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }$qplugin_java_proxy"
fi
revision="${ORIN_CONTEXT_REVISION:-main}"
[[ "$revision" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]{0,199}$ && "$revision" != *..* ]] || { echo 'Invalid revision' >&2; exit 1; }
[[ "$QPLUGIN_RELEASE_STORE" == /* ]] || { echo 'Release store must be absolute' >&2; exit 1; }
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
  python3 -m unittest discover -s scripts -p 'test_*.py' -v
)
python3 - "$work/repo/build/releases/$commit.zip" "$QPLUGIN_RELEASE_STORE" "$commit" "$ORIN_CONTEXT_RECEIPT" <<'PY'
import hashlib, os, pathlib, shutil, sys, tempfile
source, store, commit, receipt = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3], pathlib.Path(sys.argv[4])
store.mkdir(parents=True, exist_ok=True)
target = store / (commit + '.zip')
handle, temporary = tempfile.mkstemp(prefix='.qplugin-', dir=store)
try:
    with os.fdopen(handle, 'wb') as output, source.open('rb') as incoming:
        shutil.copyfileobj(incoming, output)
        output.flush(); os.fsync(output.fileno())
    try:
        os.link(temporary, target)
    except FileExistsError:
        def digest(file):
            value = hashlib.sha256()
            with file.open('rb') as stream:
                for block in iter(lambda: stream.read(65536), b''):
                    value.update(block)
            return value.hexdigest()
        if target.is_symlink() or digest(target) != digest(pathlib.Path(temporary)):
            raise RuntimeError('Release commit already contains different bytes')
    receipt.write_text(commit + '\n' + str(target) + '\n', encoding='utf-8')
finally:
    os.unlink(temporary)
PY
