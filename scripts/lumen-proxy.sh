#!/usr/bin/env bash
# Keep chrome://inspect across app restarts (no re-click Inspect).
#
# An adb-shell process (uid 2000, not root) holds @lumen_<pkg>_devtools_remote
# for the USB session. Chrome's in-window Reconnect cannot do this.
#
# Daily:
#   LUMEN_PACKAGE=com.example.app ./scripts/lumen-proxy.sh start
#   chrome://inspect → inspect once (lumen://com.example.app)
#   restart the app as usual — do not click Inspect again
#
#   ./scripts/lumen-proxy.sh start     # no-op if already listening
#   ./scripts/lumen-proxy.sh status
#   ./scripts/lumen-proxy.sh stop      # uninstall, or APK older than 0.1.2
#   ./scripts/lumen-proxy.sh restart   # drops any open inspect window
#
# Host apps can copy this file and default LUMEN_PACKAGE to their applicationId.
# Env: ANDROID_SERIAL, LUMEN_PACKAGE (default dev.lumen.sample), LUMEN_USER,
#      LUMEN_PROCESS (default = package; set for ":foo" secondary processes)
# USB / adb must stay connected. Needs Lumen 0.1.2+ (yieldInspectSocket).
set -euo pipefail

ADB="${ADB:-adb}"
if ! command -v "$ADB" >/dev/null 2>&1; then
  if [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then
    ADB="$ANDROID_HOME/platform-tools/adb"
  fi
fi

PKG="${LUMEN_PACKAGE:-dev.lumen.sample}"
PROCESS="${LUMEN_PROCESS:-$PKG}"
CMD="${1:-start}"

if [[ ! "$CMD" =~ ^(start|stop|status|restart)$ ]]; then
  echo "usage: $0 [start|stop|status|restart]" >&2
  echo "  env: ANDROID_SERIAL LUMEN_PACKAGE LUMEN_USER LUMEN_PROCESS" >&2
  exit 2
fi

DEVICE=("$ADB")
[[ -n "${ANDROID_SERIAL:-}" ]] && DEVICE+=(-s "$ANDROID_SERIAL")

USER_ARGS=()
PM_USER=()
if [[ -n "${LUMEN_USER:-}" ]]; then
  USER_ARGS+=(--user "$LUMEN_USER")
  PM_USER+=(--user "$LUMEN_USER")
fi

if [[ -n "${LUMEN_USER:-}" && "$LUMEN_USER" != "0" ]]; then
  SOCK="lumen_${PROCESS}_${LUMEN_USER}_devtools_remote"
else
  SOCK="lumen_${PROCESS}_devtools_remote"
fi

AUTH="content://${PKG}.lumen-adb"

device() { "${DEVICE[@]}" "$@"; }

push_script() {
  local dest="$1"
  local tmp
  tmp="$(mktemp)"
  cat > "$tmp"
  device push "$tmp" "$dest" >/dev/null
  rm -f "$tmp"
  device shell chmod 755 "$dest"
}

kill_proxy() {
  push_script /data/local/tmp/kill-lumen-proxy.sh <<'EOF'
#!/system/bin/sh
if [ -f /data/local/tmp/lumen-proxy.pid ]; then
  kill -9 "$(cat /data/local/tmp/lumen-proxy.pid)" 2>/dev/null || true
  rm -f /data/local/tmp/lumen-proxy.pid
fi
for p in /proc/[0-9]*; do
  cmd=$(tr '\0' ' ' < "$p/cmdline" 2>/dev/null) || continue
  case $cmd in
    *lumen.proxy.DevtoolsProxy*) kill -9 "${p#/proc/}" 2>/dev/null ;;
  esac
done
EOF
  device shell sh /data/local/tmp/kill-lumen-proxy.sh >/dev/null 2>&1 || true
}

cmd_stop() {
  kill_proxy
  device shell content call "${USER_ARGS[@]}" --uri "$AUTH" \
    --method resumeInspectSocket >/dev/null 2>&1 || true
}

# True when this sidecar already holds @$SOCK. Re-start would kill that
# process and chrome://inspect would drop the open window.
cmd_already_running() {
  local pid
  pid="$(device shell cat /data/local/tmp/lumen-proxy.pid 2>/dev/null | tr -d '\r')"
  [[ -n "$pid" ]] || return 1
  device shell "test -d /proc/$pid && tr '\\0' ' ' < /proc/$pid/cmdline" 2>/dev/null \
    | grep -q 'lumen.proxy.DevtoolsProxy' || return 1
  device shell cat /proc/net/unix 2>/dev/null | grep -F -q "$SOCK"
}

cmd_start() {
  if ! device shell pm path "${PM_USER[@]}" "$PKG" | grep -q package:; then
    echo "package $PKG not installed" >&2
    exit 1
  fi
  if cmd_already_running; then
    echo "proxy already running @$SOCK — leaving it (re-start would drop chrome://inspect)."
    echo "use: $0 restart   to replace the sidecar"
    return 0
  fi
  push_script /data/local/tmp/start-lumen-proxy.sh <<EOF
#!/system/bin/sh
PKG="$PKG"
SOCK="$SOCK"
APKS=\$(pm path ${PM_USER[*]} "\$PKG" 2>/dev/null | cut -d: -f2 | tr -d '\\r' | tr '\\n' ':')
APKS=\${APKS%:}
[ -n "\$APKS" ] || exit 1
export CLASSPATH="\$APKS"
APP_PROCESS=app_process
[ -x /system/bin/app_process64 ] && APP_PROCESS=app_process64
nohup "\$APP_PROCESS" /system/bin dev.lumen.proxy.DevtoolsProxy "\$SOCK" \
  >/data/local/tmp/lumen-proxy.out 2>&1 &
echo \$! > /data/local/tmp/lumen-proxy.pid
EOF
  kill_proxy
  device shell rm -f /data/local/tmp/lumen-proxy.log /data/local/tmp/lumen-proxy.out >/dev/null 2>&1 || true
  yield_out="$(device shell content call "${USER_ARGS[@]}" --uri "$AUTH" \
    --method yieldInspectSocket 2>&1 || true)"
  if printf '%s\n' "$yield_out" | grep -q 'unknown method'; then
    echo "package $PKG does not support yieldInspectSocket. Rebuild the debug APK with this Lumen." >&2
    exit 1
  fi
  # The app refused to yield because it could not bind the loopback CDP port.
  # Starting the sidecar anyway would hold the chrome socket with a dead
  # backend, so stop here; normal chrome://inspect keeps working.
  if printf '%s\n' "$yield_out" | grep -q 'error: loopback bind failed'; then
    echo "app could not bind the loopback CDP port; sidecar not started:" >&2
    printf '%s\n' "$yield_out" >&2
    exit 1
  fi
  sleep 0.4
  restarted=0
  if device shell 'cat /proc/net/unix' | grep -F -q "$SOCK"; then
    echo "App still owns the inspect socket; restarting $PKG so the sidecar can bind (not a crash)."
    device shell am force-stop "$PKG" >/dev/null 2>&1 || true
    restarted=1
    sleep 0.4
  fi
  device shell "nohup sh /data/local/tmp/start-lumen-proxy.sh >/dev/null 2>&1 &"
  ok=0
  for _ in 1 2 3 4 5 6 7 8; do
    if device shell grep -F -q "listening @$SOCK" /data/local/tmp/lumen-proxy.log 2>/dev/null; then
      ok=1
      break
    fi
    sleep 0.25
  done
  if [[ "$ok" -ne 1 ]]; then
    echo "proxy listen failed; /data/local/tmp/lumen-proxy.log:" >&2
    device shell tail -20 /data/local/tmp/lumen-proxy.log >&2 || true
    device shell tail -20 /data/local/tmp/lumen-proxy.out >&2 || true
    exit 1
  fi
  if [[ "$restarted" -eq 1 ]]; then
    device shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  fi
  echo "proxy running (adb shell, no root)  @$SOCK"
  echo "chrome://inspect → inspect  $PKG"
}

cmd_status() {
  device shell 'cat /proc/net/unix' | tr -d '\r' | grep "lumen_${PROCESS}" || true
  device shell content call "${USER_ARGS[@]}" --uri "$AUTH" \
    --method inspectProxyStatus 2>/dev/null || true
  if device shell ps -A -o USER,PID,ARGS 2>/dev/null | grep -q DevtoolsProxy; then
    device shell ps -A -o USER,PID,ARGS 2>/dev/null | grep DevtoolsProxy
  else
    echo "(no DevtoolsProxy process)"
  fi
}

case "$CMD" in
  start) cmd_start ;;
  stop) cmd_stop ;;
  status) cmd_status ;;
  restart) cmd_stop; cmd_start ;;
esac
