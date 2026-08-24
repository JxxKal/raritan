#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# Phase 4 Entrypoint — Xvfb → fluxbox → x11vnc → websockify/noVNC → MPC (Java)
#
# Unterschied zu entrypoint-phase3.sh: statt Bridge.exe unter Mono laeuft hier
# der Java-Client im Vordergrund. Ohne RARITAN_IP startet MPC mit seiner eigenen
# Oberflaeche — brauchbar, um den Weg bis zum Browser zu pruefen, wenn gerade
# kein Geraet erreichbar ist.
# ─────────────────────────────────────────────────────────────────────────────
set -u

LOG_DIR=/logs
mkdir -p "$LOG_DIR"

DISPLAY_NUM=99
export DISPLAY=":${DISPLAY_NUM}"

XVFB_PID=""; FLUXBOX_PID=""; X11VNC_PID=""; WS_PID=""

log() { echo "[$(date -Iseconds)] $*"; }

cleanup() {
    log "shutting down…"
    [ -n "$WS_PID" ]      && kill "$WS_PID"      2>/dev/null
    [ -n "$X11VNC_PID" ]  && kill "$X11VNC_PID"  2>/dev/null
    [ -n "$FLUXBOX_PID" ] && kill "$FLUXBOX_PID" 2>/dev/null
    [ -n "$XVFB_PID" ]    && kill "$XVFB_PID"    2>/dev/null
    wait 2>/dev/null
}
trap cleanup EXIT
trap 'log "caught signal — exiting"; exit 143' TERM INT

log "=== Raritan MPC (Java) — Xvfb → x11vnc → noVNC ==="
log "RARITAN_IP:   ${RARITAN_IP:-<unset>}"
log "RARITAN_USER: ${RARITAN_USER}"
log "RARITAN_PASS: $([ -n "${RARITAN_PASS}" ] && echo "<set>" || echo "<unset>")"
log "GEOMETRY:     ${SCREEN_GEOMETRY}"
java -version 2>&1 | head -1

if [ -n "${RARITAN_IP}" ]; then
    if nc -z -w 5 "$RARITAN_IP" "$RARITAN_PORT" 2>/dev/null; then
        log "DKX2 ${RARITAN_IP}:${RARITAN_PORT} erreichbar"
    else
        log "WARN: ${RARITAN_IP}:${RARITAN_PORT} nicht erreichbar — starte trotzdem"
    fi
else
    log "RARITAN_IP nicht gesetzt — MPC startet mit eigener Oberflaeche"
fi

# ── Xvfb ──
log "starte Xvfb auf :${DISPLAY_NUM} (${SCREEN_GEOMETRY})"
Xvfb ":${DISPLAY_NUM}" -screen 0 "${SCREEN_GEOMETRY}" -nolisten tcp > "$LOG_DIR/xvfb.log" 2>&1 &
XVFB_PID=$!
for i in $(seq 1 50); do
    [ -S "/tmp/.X11-unix/X${DISPLAY_NUM}" ] && break
    if ! kill -0 "$XVFB_PID" 2>/dev/null; then
        log "FATAL: Xvfb beendet sich beim Start — siehe $LOG_DIR/xvfb.log"
        exit 3
    fi
    sleep 0.2
done
log "Xvfb bereit (pid $XVFB_PID)"

# ── Window-Manager ──
# Ohne WM bekommt das Client-Fenster keinen X-Input-Focus; die von x11vnc per
# XTEST eingespeisten Tastendruecke werden dann verworfen.
log "starte fluxbox"
fluxbox > "$LOG_DIR/fluxbox.log" 2>&1 &
FLUXBOX_PID=$!
sleep 0.5

# ── x11vnc ──
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
    -xkb -add_keysyms \
    -threads -defer 5 -wait 5 -cursor most -cursorpos \
    "${VNC_AUTH[@]}" -o "$LOG_DIR/x11vnc.log" &
X11VNC_PID=$!

# ── websockify + noVNC ──
log "starte websockify/noVNC → http://<host>:6080/"
websockify --web=/usr/share/novnc 6080 "localhost:5900" > "$LOG_DIR/websockify.log" 2>&1 &
WS_PID=$!

# ── MPC ──
# Reihenfolge des Klassenpfads wie in start.sh des Installers.
CP="/opt/mpc/splugin.jar:/opt/mpc/sdeploy.jar:/opt/mpc/sFoxtrot.jar:/opt/mpc/jaws.jar:/opt/mpc/sMpc.jar"

# main() zerlegt jedes Argument an dem ersten "=" in Schluessel und Wert.
ARGS=()
[ -n "${RARITAN_IP}" ]      && ARGS+=("deviceip=${RARITAN_IP}")
[ -n "${RARITAN_USER}" ]    && ARGS+=("username=${RARITAN_USER}")
[ -n "${RARITAN_PASS}" ]    && ARGS+=("password=${RARITAN_PASS}")
[ -n "${RARITAN_PORT_ID}" ] && ARGS+=("portid=${RARITAN_PORT_ID}")
# shellcheck disable=SC2206
[ -n "${MPC_ARGS}" ] && ARGS+=(${MPC_ARGS})

cd /opt/mpc
# -Dsun.java2d.noddraw=true stammt aus start.sh des Herstellers.
# -Djava.awt.headless=false ist ausdruecklich noetig: ohne DISPLAY-taugliche
# Umgebung wuerde die JRE in den Headless-Modus fallen und Swing abbrechen.
log "=== starte MPC: ${ARGS[*]:-<ohne Argumente>} ==="
java -Xmn128M -Xmx512M -Dsun.java2d.noddraw=true -Djava.awt.headless=false \
     -cp "$CP" com.raritan.rrc.ui.RRCApplication "${ARGS[@]}" \
     2>&1
EXIT=$?

log "=== MPC beendet (exit $EXIT) ==="

if [ "${KEEP_ALIVE:-0}" = "1" ]; then
    log "KEEP_ALIVE=1 — Container bleibt oben (X-Stack laeuft weiter, MPC exit $EXIT)"
    while true; do sleep 3600; done
fi

exit "$EXIT"
