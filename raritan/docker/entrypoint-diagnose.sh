#!/bin/bash
set -u

LOG_DIR=/logs
mkdir -p "$LOG_DIR"
exec > >(tee -a "$LOG_DIR/diagnose.log") 2>&1

echo "=== Raritan AKC Diagnose Container ==="
echo "started: $(date -Iseconds)"
echo "RARITAN_IP: ${RARITAN_IP:-<unset>}"
echo "IDLE_SECONDS: ${IDLE_SECONDS}"
echo "container hostname: $(hostname)"
echo

echo "=== environment ==="
echo "Mono: $(mono --version 2>&1 | head -1)"
echo "libgdiplus: $(dpkg -s libgdiplus 2>/dev/null | grep ^Version)"
echo

echo "=== /opt/akc contents ==="
ls -la /opt/akc/ | tee "$LOG_DIR/akc-files.txt"
echo

echo "=== network reachability check ==="
if [ -z "$RARITAN_IP" ]; then
    echo "WARN: RARITAN_IP not set — using 127.0.0.1 (will fail to connect, ok for build-test)"
    TARGET=127.0.0.1
else
    TARGET="$RARITAN_IP"
fi

echo "ip addr:"
ip -brief addr 2>&1 | tee "$LOG_DIR/ip-addr.txt"
echo
echo "ip route:"
ip route 2>&1 | tee "$LOG_DIR/ip-route.txt"
echo
echo "ping $TARGET (4 attempts):"
ping -c 4 -W 2 "$TARGET" 2>&1 | tee "$LOG_DIR/ping.txt" || echo "(ping failed — may be ICMP-filtered, not fatal)"
echo
echo "TCP probe port 443 on $TARGET:"
nc -zv -w 5 "$TARGET" 443 2>&1 | tee "$LOG_DIR/tcp-443.txt" || true
echo "TCP probe port 5000 on $TARGET (Raritan KVM port):"
nc -zv -w 5 "$TARGET" 5000 2>&1 | tee "$LOG_DIR/tcp-5000.txt" || true
echo

echo "=== starting Xvfb on :99 ==="
Xvfb :99 -screen 0 1920x1080x24 -ac +extension RANDR +extension GLX >"$LOG_DIR/xvfb.log" 2>&1 &
XVFB_PID=$!
sleep 1
export DISPLAY=:99

if ! kill -0 $XVFB_PID 2>/dev/null; then
    echo "FATAL: Xvfb died"
    cat "$LOG_DIR/xvfb.log"
    exit 1
fi
echo "Xvfb PID=$XVFB_PID"

echo "=== starting fluxbox (window manager) ==="
fluxbox >"$LOG_DIR/fluxbox.log" 2>&1 &
FLUXBOX_PID=$!
sleep 1
echo "fluxbox PID=$FLUXBOX_PID"

echo "=== starting x11vnc on :5900 (no password — diagnose only) ==="
x11vnc -display :99 -nopw -forever -shared -rfbport 5900 \
       -bg -o "$LOG_DIR/x11vnc.log" -quiet
sleep 1
echo "x11vnc up — connect with: vncviewer <host>:5900"
echo

echo "=== launching mono kxgui-patched.exe $TARGET ==="
echo "(verbose mono logging is OFF to keep the log readable — re-run with VERBOSE=1 for full trace)"
echo

cd /opt/akc

MONO_FLAGS=()
if [ "${VERBOSE:-0}" = "1" ]; then
    export MONO_LOG_LEVEL=info
    export MONO_LOG_MASK=dll,cfg,asm
    MONO_FLAGS+=(--debug)
fi

MONO_PATH=. LD_LIBRARY_PATH=. mono "${MONO_FLAGS[@]}" \
    /opt/akc/kxgui-patched.exe "$TARGET" \
    >"$LOG_DIR/mono-stdout.log" 2>"$LOG_DIR/mono-stderr.log" &
MONO_PID=$!
echo "mono PID=$MONO_PID"

echo "=== waiting up to 30s for mono main window ==="
for i in $(seq 1 30); do
    if ! kill -0 $MONO_PID 2>/dev/null; then
        echo "[t=${i}s] mono EXITED early — see mono-stderr.log"
        break
    fi
    # try to detect window via xdotool — install only if needed; use xwininfo as fallback
    if command -v xwininfo >/dev/null 2>&1; then
        if xwininfo -root -tree 2>/dev/null | grep -qi 'raritan\|kvm\|kxgui'; then
            echo "[t=${i}s] AKC window detected"
            xwininfo -root -tree 2>/dev/null | grep -iE 'raritan|kvm|kxgui' \
                | tee -a "$LOG_DIR/windows.txt"
            break
        fi
    fi
    sleep 1
done

echo
echo "=== final window inventory ==="
if command -v xwininfo >/dev/null 2>&1; then
    xwininfo -root -tree 2>&1 | tee "$LOG_DIR/windows-final.txt"
fi

echo
echo "=== mono-stderr.log (first 200 lines) ==="
head -200 "$LOG_DIR/mono-stderr.log" 2>/dev/null
echo
echo "=== mono-stdout.log (first 200 lines) ==="
head -200 "$LOG_DIR/mono-stdout.log" 2>/dev/null
echo
echo "=== AKC log4net output (if any) ==="
# AKC log4net writes via HOME_DIR (set by Com.Raritan.KxGui.Preferences.Utils).
# Scan likely paths only — avoid hitting /var/log noise.
find /opt/akc /root /tmp -maxdepth 4 -type f \( -name '*.log' -o -name '*.txt' \) \
     -newer /entrypoint.sh 2>/dev/null \
    | while read f; do
        echo "--- $f ---"
        cat "$f" 2>/dev/null
done | tee "$LOG_DIR/akc-log4net.txt"
echo
echo "=== idle for ${IDLE_SECONDS}s — VNC in (port 5900) to see the GUI ==="
echo "to end early: docker stop <container>"
SECS=0
while [ "$SECS" -lt "$IDLE_SECONDS" ]; do
    sleep 5
    SECS=$((SECS + 5))
    if ! kill -0 $MONO_PID 2>/dev/null; then
        echo "[t=${SECS}s idle] mono process gone"
        break
    fi
done

echo
echo "=== shutting down ==="
kill $MONO_PID 2>/dev/null
sleep 2
kill -9 $MONO_PID 2>/dev/null
pkill -TERM x11vnc 2>/dev/null
pkill -TERM fluxbox 2>/dev/null
kill $XVFB_PID 2>/dev/null
echo "stopped at: $(date -Iseconds)"
echo
echo "=== logs available under /logs (mount this to host to retrieve) ==="
ls -la "$LOG_DIR"
