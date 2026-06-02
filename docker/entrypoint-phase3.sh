#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# Phase 3 Produktiv-Entrypoint
#   Startet Xvfb → x11vnc → websockify/noVNC → Bridge.exe (AKC im Xvfb).
#   Bridge.exe läuft im Vordergrund; stirbt es, wird der Stack abgeräumt und der
#   Container beendet sich mit dessen Exit-Code.
# ─────────────────────────────────────────────────────────────────────────────
set -u

LOG_DIR=/logs
mkdir -p "$LOG_DIR"

DISPLAY_NUM=99
export DISPLAY=":${DISPLAY_NUM}"

XVFB_PID=""
X11VNC_PID=""
WS_PID=""

log() { echo "[$(date -Iseconds)] $*"; }

cleanup() {
    log "shutting down…"
    [ -n "$WS_PID" ]     && kill "$WS_PID"     2>/dev/null
    [ -n "$X11VNC_PID" ] && kill "$X11VNC_PID" 2>/dev/null
    [ -n "$XVFB_PID" ]   && kill "$XVFB_PID"   2>/dev/null
    wait 2>/dev/null
}
trap cleanup EXIT
trap 'log "caught signal — exiting"; exit 143' TERM INT

# ── Konfiguration prüfen ──
log "=== Raritan AKC Phase 3 (Xvfb → x11vnc → noVNC) ==="
log "RARITAN_IP:   ${RARITAN_IP:-<unset>}"
log "RARITAN_PORT: ${RARITAN_PORT}"
log "RARITAN_USER: ${RARITAN_USER}"
log "RARITAN_PASS: $([ -n "${RARITAN_PASS}" ] && echo "<set>" || echo "<unset>")"
log "GEOMETRY:     ${SCREEN_GEOMETRY}"
mono --version 2>&1 | head -1

if [ -z "${RARITAN_IP}" ]; then
    log "FATAL: RARITAN_IP ist leer — mit -e RARITAN_IP=10.180.42.160 setzen"
    exit 2
fi
if [ -z "${RARITAN_PASS}" ]; then
    log "FATAL: RARITAN_PASS ist leer — mit -e RARITAN_PASS=… setzen"
    exit 2
fi

# ── Erreichbarkeit (nicht-fatal, nur Info) ──
if nc -z -w 5 "$RARITAN_IP" "$RARITAN_PORT" 2>/dev/null; then
    log "DKX2 ${RARITAN_IP}:${RARITAN_PORT} erreichbar"
else
    log "WARN: ${RARITAN_IP}:${RARITAN_PORT} nicht erreichbar — versuche trotzdem zu starten"
fi

# ── Xvfb ──
log "starte Xvfb auf :${DISPLAY_NUM} (${SCREEN_GEOMETRY})"
Xvfb ":${DISPLAY_NUM}" -screen 0 "${SCREEN_GEOMETRY}" -nolisten tcp > "$LOG_DIR/xvfb.log" 2>&1 &
XVFB_PID=$!

# Auf X-Display-Socket warten statt fixem sleep
for i in $(seq 1 50); do
    [ -S "/tmp/.X11-unix/X${DISPLAY_NUM}" ] && break
    if ! kill -0 "$XVFB_PID" 2>/dev/null; then
        log "FATAL: Xvfb beendet sich beim Start — siehe $LOG_DIR/xvfb.log"
        exit 3
    fi
    sleep 0.2
done
log "Xvfb bereit (pid $XVFB_PID)"

# ── x11vnc (optional passwortgeschützt) ──
VNC_AUTH=(-nopw)
if [ -n "${VNC_PASSWORD}" ]; then
    x11vnc -storepasswd "$VNC_PASSWORD" "$LOG_DIR/.vncpass" >/dev/null 2>&1
    VNC_AUTH=(-rfbauth "$LOG_DIR/.vncpass")
    log "x11vnc mit Passwort-Auth"
else
    log "x11vnc OHNE Auth (VNC_PASSWORD nicht gesetzt)"
fi
log "starte x11vnc → Port 5900"
x11vnc -display ":${DISPLAY_NUM}" -forever -shared -rfbport 5900 \
    "${VNC_AUTH[@]}" -o "$LOG_DIR/x11vnc.log" -quiet &
X11VNC_PID=$!

# ── websockify + noVNC ──
log "starte websockify/noVNC → http://<host>:6080/vnc.html"
websockify --web=/usr/share/novnc 6080 "localhost:5900" > "$LOG_DIR/websockify.log" 2>&1 &
WS_PID=$!

# ── Bridge.exe (AKC) im Vordergrund ──
cd /opt/akc
log "=== starte Bridge.exe → ${RARITAN_IP}:${RARITAN_PORT} (Control-API :8081) ==="
LD_LIBRARY_PATH=. MONO_PATH=. mono Bridge.exe \
    "$RARITAN_IP" "$RARITAN_PORT" "$RARITAN_USER" "$RARITAN_PASS" "${RARITAN_PORT_ID}" "${RARITAN_PORT_NAME}" \
    2>&1 | tee "$LOG_DIR/bridge.log"
EXIT=${PIPESTATUS[0]}

log "=== Bridge.exe beendet (exit $EXIT) ==="
exit "$EXIT"
