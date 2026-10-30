#!/usr/bin/bash
# Root-owned fixed-target helper. It accepts no PID, path or command from the caller.
set -euo pipefail
PATH=/usr/sbin:/usr/bin:/sbin:/bin
export PATH
ORIN_DIRECTORY=/opt/server/orin
ORIN_JAR=/opt/server/orin/Orin-all.jar
ORIN_JAR_REAL="$(readlink -f -- "$ORIN_JAR")"
[[ -f "$ORIN_JAR_REAL" ]] || { echo 'Fixed Orin JAR is missing' >&2; exit 1; }

start_token() {
  local stat rest
  stat="$(<"/proc/$1/stat")"
  rest="${stat##*) }"
  read -r -a fields <<< "$rest"
  [[ "${#fields[@]}" -gt 19 ]] || return 1
  printf '%s' "${fields[19]}"
}

find_processes() {
  local proc pid executable cwd jar_index jar candidate children token uid
  local -a argv
  for proc in /proc/[0-9]*; do
    [[ -d "$proc" ]] || continue
    pid="${proc##*/}"
    executable="$(readlink -f -- "$proc/exe" 2>/dev/null || true)"
    [[ "${executable##*/}" == java ]] || continue
    cwd="$(readlink -f -- "$proc/cwd" 2>/dev/null || true)"
    [[ "$cwd" == "$ORIN_DIRECTORY" ]] || continue
    mapfile -d '' -t argv < "$proc/cmdline" || true
    jar_index=-1
    for candidate in "${!argv[@]}"; do
      [[ "${argv[$candidate]}" == -jar ]] && jar_index="$candidate" && break
    done
    [[ "$jar_index" -ge 0 && "$((jar_index + 1))" -lt "${#argv[@]}" ]] || continue
    jar="${argv[$((jar_index + 1))]}"
    [[ "$jar" == /* ]] || jar="$cwd/$jar"
    [[ "$(readlink -f -- "$jar" 2>/dev/null || true)" == "$ORIN_JAR_REAL" ]] || continue
    children=
    IFS= read -r children < "$proc/task/$pid/children" || true
    token="$(start_token "$pid")" || continue
    uid="$(stat -c %u -- "$proc" 2>/dev/null)" || continue
    printf '%s|%s|%s|%s\n' "$pid" "$uid" "$children" "$token"
  done
}

[[ "$EUID" -eq 0 ]] || { echo 'Run through the installed sudo rule' >&2; exit 1; }
[[ "$#" -eq 1 && "$1" =~ ^(status|restart)$ ]] || { echo 'Usage: qplugin-orin-admin status|restart' >&2; exit 2; }
mapfile -t processes < <(find_processes)
if [[ "$1" == status ]]; then
  printf '{"processes":['
  separator=
  for process in "${processes[@]}"; do
    IFS='|' read -r pid uid children token <<< "$process"
    printf '%s{"pid":%s,"uid":%s,"cwd":"/opt/server/orin","jar":"/opt/server/orin/Orin-all.jar","children":[' "$separator" "$pid" "$uid"
    child_separator=
    for child in $children; do printf '%s%s' "$child_separator" "$child"; child_separator=,; done
    printf '],"startToken":"%s"}' "$token"
    separator=,
  done
  printf ']}\n'
  exit 0
fi
[[ "${#processes[@]}" -eq 1 ]] || { echo 'Refusing restart: expected exactly one matching Orin JVM' >&2; exit 3; }
IFS='|' read -r pid uid children token <<< "${processes[0]}"
[[ -z "$children" ]] || { echo 'Refusing restart: Orin has active child processes; retry after CI tasks finish' >&2; exit 3; }
[[ "$(start_token "$pid")" == "$token" ]] || { echo 'Refusing restart: process changed during inspection' >&2; exit 4; }
kill -TERM -- "$pid"
printf '{"signal":"SIGTERM","pid":%s,"supervisor":"MCSManager"}\n' "$pid"
