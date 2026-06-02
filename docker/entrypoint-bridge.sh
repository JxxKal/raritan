#!/bin/bash
set -u

LOG_DIR=/logs
mkdir -p "$LOG_DIR"
exec > >(tee -a "$LOG_DIR/bridge.log") 2>&1

echo "=== Raritan AKC headless bridge (Phase 1: connect + auth probe) ==="
echo "started: $(date -Iseconds)"
echo "RARITAN_IP:   ${RARITAN_IP:-<unset>}"
echo "RARITAN_PORT: ${RARITAN_PORT}"
echo "RARITAN_USER: ${RARITAN_USER}"
echo "RARITAN_PASS: $([ -n "$RARITAN_PASS" ] && echo "<set, ${#RARITAN_PASS} chars>" || echo "<unset>")"
echo

echo "=== environment ==="
mono --version 2>&1 | head -1
echo

echo "=== /opt/akc contents ==="
ls -la /opt/akc/ | tee "$LOG_DIR/files.txt"
echo

if [ -z "${RARITAN_IP}" ]; then
    echo "FATAL: RARITAN_IP env var is empty — set it with -e RARITAN_IP=10.180.42.160"
    exit 2
fi

echo "=== network reachability ==="
ip -brief addr 2>&1 | tee "$LOG_DIR/ip-addr.txt"
echo
ping -c 3 -W 2 "$RARITAN_IP" 2>&1 | tee "$LOG_DIR/ping.txt" || echo "(ping failed)"
nc -zv -w 5 "$RARITAN_IP" "$RARITAN_PORT" 2>&1 | tee "$LOG_DIR/tcp-port.txt"
echo

echo "=== starting Xvfb on :99 (1920x1080x24) ==="
Xvfb :99 -screen 0 1920x1080x24 -nolisten tcp &
XVFB_PID=$!
export DISPLAY=:99
sleep 1

echo "=== starting x11vnc on :99 -> port 5900 (no auth) ==="
x11vnc -display :99 -forever -shared -rfbport 5900 -nopw -bg -o "$LOG_DIR/x11vnc.log" -quiet

echo "=== starting websockify on 6080 (noVNC HTTP frontend) ==="
websockify --web=/usr/share/novnc 6080 localhost:5900 > "$LOG_DIR/websockify.log" 2>&1 &
WS_PID=$!
sleep 1
echo "    Open http://<container-host>:6080/vnc.html in browser"

cd /opt/akc
if [ "${BRIDGE_MODE:-probe}" = "probe" ]; then
    echo "=== launching Probe.exe (AKC-reflection diagnostic) ==="
    LD_LIBRARY_PATH=. MONO_PATH=. mono Probe.exe \
        2>&1 | tee "$LOG_DIR/bridge-output.log"
else
    echo "=== launching Bridge.exe (AKC-as-library) ==="
    LD_LIBRARY_PATH=. MONO_PATH=. mono Bridge.exe \
        "$RARITAN_IP" "$RARITAN_PORT" "$RARITAN_USER" "$RARITAN_PASS" "${RARITAN_PORT_ID:-P_000d5d06a393_0}" "${RARITAN_PORT_NAME:-Dominion-KX2_Port1}" \
        2>&1 | tee "$LOG_DIR/bridge-output.log"
fi

EXIT=$?
echo
echo "=== Bridge exit code: $EXIT ==="
echo "=== logs ==="
ls -la "$LOG_DIR"
echo "stopped at: $(date -Iseconds)"
