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
FLUXBOX_PID=""
X11VNC_PID=""
WS_PID=""

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

# ── Window-Manager (fluxbox) ──
# Ohne WM bekommt das AKC-KVM-Fenster keinen X-Input-Focus: x11vnc spritzt Tastatur-
# Events via XTEST ein, die ohne fokussiertes Fenster verworfen werden. Erst der WM
# aktiviert/fokussiert das Fenster, damit Maus UND Tastatur an AKC durchgereicht und
# zum Zielrechner weitergeleitet werden.
log "starte fluxbox (window manager)"
fluxbox > "$LOG_DIR/fluxbox.log" 2>&1 &
FLUXBOX_PID=$!
sleep 0.5
log "fluxbox bereit (pid $FLUXBOX_PID)"

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
# -xkb -add_keysyms: Xvfb hat eine lückenhafte Keymap. Ohne diese Flags kann x11vnc
# die vom VNC-Client gesendeten Keysyms nicht auf Keycodes mappen -> es entsteht gar
# kein X-KeyEvent, AKC sieht die Taste nie (Maus geht trotzdem, da pointer-basiert).
# -add_keysyms legt fehlende Keysyms zur Laufzeit auf freie Keycodes.
# Cursor-Lag-Fix: Klicks landen korrekt (Position stimmt), nur der SICHTBARE Cursor hinkt
# nach, weil noVNC ihn aus den langsamen Framebuffer-Updates rendert statt client-seitig.
#   -cursor most -cursorpos : Cursor-Shape+Position via XFIXES/Cursor-Encoding senden ->
#                             noVNC zeichnet den Cursor lokal am Mauszeiger = instant.
#   -threads                : multithreaded -> deutlich reaktiver bei 1920x1080.
#   -defer 5 -wait 5        : Updates schneller flushen (Default 30ms).
# -quiet entfernt, damit x11vnc.log XFIXES/XDAMAGE/Cursor-Diagnose zeigt.
x11vnc -display ":${DISPLAY_NUM}" -forever -shared -rfbport 5900 \
    -xkb -add_keysyms \
    -threads -defer 5 -wait 5 -cursor most -cursorpos \
    "${VNC_AUTH[@]}" -o "$LOG_DIR/x11vnc.log" &
X11VNC_PID=$!

# ── websockify + noVNC ──
log "starte websockify/noVNC → http://<host>:6080/vnc.html"
websockify --web=/usr/share/novnc 6080 "localhost:5900" > "$LOG_DIR/websockify.log" 2>&1 &
WS_PID=$!

# ── Bridge.exe (AKC) im Vordergrund ──
cd /opt/akc
# Mono-XIM abschalten: System.Windows.Forms.X11Keyboard.Xutf8LookupString segfaultet (SIGSEGV)
# beim Übersetzen von (u.a. XTEST-injizierten) Key-Events. Ohne XIM nutzt Mono den simplen
# XLookupString-Pfad -> kein Crash. Tastatur läuft unverändert über unseren IMessageFilter.
export MONO_WINFORMS_XIM_STYLE=disabled
# Stack-Sicherheitsnetz: bei einem Session-Teardown disposed AKC den Render-Control-Baum rekursiv
# (Render.g.d -> DisposeChildren -> ...). Wird der durch eine Mono-Race-Exception (cross-thread
# Control.Invalidate, s. Hwnd.AddInvalidArea) tief/zyklisch, läuft der Default-8MB-Stack über ->
# SIGSEGV killt den Container. Großer Main-Thread-Stack lässt einen endlichen Teardown durchlaufen
# (Session droppt sauber, Container bleibt) statt zu crashen.
ulimit -s 524288 2>/dev/null || ulimit -s unlimited 2>/dev/null || true
log "=== starte Bridge.exe → ${RARITAN_IP}:${RARITAN_PORT} (Control-API :8081, XIM disabled, stack=$(ulimit -s)) ==="
LD_LIBRARY_PATH=. MONO_PATH=. mono Bridge.exe \
    "$RARITAN_IP" "$RARITAN_PORT" "$RARITAN_USER" "$RARITAN_PASS" "${RARITAN_PORT_ID}" "${RARITAN_PORT_NAME}" \
    2>&1 | tee "$LOG_DIR/bridge.log"
EXIT=${PIPESTATUS[0]}

log "=== Bridge.exe beendet (exit $EXIT) ==="

# Debug-Modus: Container am Leben lassen, damit der X-Stack (Xvfb/x11vnc/noVNC)
# stehen bleibt und die Bridge per `docker exec` neu gestartet werden kann,
# ohne den ganzen Container neu hochzufahren:
#   docker exec -it raritan-akc bash -lc 'DISPLAY=:99 MONO_PATH=. LD_LIBRARY_PATH=. \
#       mono Bridge.exe "$RARITAN_IP" "$RARITAN_PORT" "$RARITAN_USER" "$RARITAN_PASS"'
if [ "${KEEP_ALIVE:-0}" = "1" ]; then
    log "KEEP_ALIVE=1 — Container bleibt oben (X-Stack läuft weiter, Bridge exit $EXIT)"
    while true; do sleep 3600; done
fi

exit "$EXIT"
