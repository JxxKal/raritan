#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# Xvfb → fluxbox → x11vnc → websockify/noVNC → RcHarness (Applet ohne Browser)
#
# Der Harness meldet sich selbst am Geraet an, holt rc.jar von dort und startet
# nn.pp.rc.RemoteConsoleApplet in einem JFrame. Kein Mono, keine Cecil-Patches,
# keine Reflection auf den BrowserMediator.
# ─────────────────────────────────────────────────────────────────────────────
set -u

LOG_DIR=/logs; mkdir -p "$LOG_DIR"
DISPLAY_NUM=99; export DISPLAY=":${DISPLAY_NUM}"
XVFB_PID=""; FLUXBOX_PID=""; X11VNC_PID=""; WS_PID=""; WATCH_PID=""

log() { echo "[$(date -Iseconds)] $*"; }
cleanup() {
    log "shutting down…"
    for p in "$WATCH_PID" "$WS_PID" "$X11VNC_PID" "$FLUXBOX_PID" "$XVFB_PID"; do
        [ -n "$p" ] && kill "$p" 2>/dev/null
    done
    wait 2>/dev/null
}
trap cleanup EXIT
trap 'log "caught signal — exiting"; exit 143' TERM INT

log "=== Raritan KVM über RcHarness (Applet ohne Browser) ==="
log "RARITAN_IP:   ${RARITAN_IP:-<unset>}"
log "RARITAN_USER: ${RARITAN_USER}"
log "RARITAN_PASS: $([ -n "${RARITAN_PASS}" ] && echo "<set>" || echo "<unset>")"
log "GEOMETRY:     ${SCREEN_GEOMETRY}"
java -version 2>&1 | head -1

if [ -z "${RARITAN_IP}" ] || [ -z "${RARITAN_PASS}" ]; then
    log "FATAL: RARITAN_IP und RARITAN_PASS müssen gesetzt sein"
    exit 2
fi
if nc -z -w 5 "$RARITAN_IP" "$RARITAN_PORT" 2>/dev/null; then
    log "KX2 ${RARITAN_IP}:${RARITAN_PORT} erreichbar"
else
    log "WARN: ${RARITAN_IP}:${RARITAN_PORT} nicht erreichbar — versuche es trotzdem"
fi

# "docker compose restart" startet den Prozess neu, nicht den Container: Lock und
# Socket des alten Xvfb liegen dann noch da. Xvfb verweigert daraufhin den Start
# ("Server is already active for display 99") — und weil die Bereitschaftspruefung
# frueher nur auf den Socket sah, meldete der Entrypoint trotzdem "Xvfb bereit".
# Der Harness lief in ein AWTError und der Container blieb ohne Anzeige stehen.
if [ -e "/tmp/.X${DISPLAY_NUM}-lock" ] || [ -S "/tmp/.X11-unix/X${DISPLAY_NUM}" ]; then
    if pgrep -f "Xvfb :${DISPLAY_NUM}" > /dev/null 2>&1; then
        log "FATAL: auf :${DISPLAY_NUM} laeuft bereits ein Xvfb"; exit 3
    fi
    log "verwaister X-Lock von einem frueheren Lauf — raeume auf"
    rm -f "/tmp/.X${DISPLAY_NUM}-lock" "/tmp/.X11-unix/X${DISPLAY_NUM}"
fi

log "starte Xvfb auf :${DISPLAY_NUM} (${SCREEN_GEOMETRY})"
Xvfb ":${DISPLAY_NUM}" -screen 0 "${SCREEN_GEOMETRY}" -nolisten tcp > "$LOG_DIR/xvfb.log" 2>&1 &
XVFB_PID=$!
# xdpyinfo statt Socket-Existenz: der Socket sagt nur, dass irgendwann einmal
# einer da war — nicht, dass jetzt jemand antwortet.
XVFB_UP=0
for i in $(seq 1 50); do
    if xdpyinfo -display ":${DISPLAY_NUM}" > /dev/null 2>&1; then XVFB_UP=1; break; fi
    kill -0 "$XVFB_PID" 2>/dev/null || { log "FATAL: Xvfb beendet sich beim Start:"; cat "$LOG_DIR/xvfb.log"; exit 3; }
    sleep 0.2
done
[ "$XVFB_UP" = 1 ] || { log "FATAL: Xvfb antwortet nicht auf :${DISPLAY_NUM}:"; cat "$LOG_DIR/xvfb.log"; exit 3; }
log "Xvfb bereit"

log "starte fluxbox"
fluxbox > "$LOG_DIR/fluxbox.log" 2>&1 &
FLUXBOX_PID=$!
sleep 0.5

VNC_AUTH=(-nopw)
if [ -n "${VNC_PASSWORD}" ]; then
    x11vnc -storepasswd "$VNC_PASSWORD" "$LOG_DIR/.vncpass" >/dev/null 2>&1
    VNC_AUTH=(-rfbauth "$LOG_DIR/.vncpass")
    log "x11vnc mit Passwort-Auth"
else
    log "x11vnc OHNE Auth (VNC_PASSWORD nicht gesetzt)"
fi
x11vnc -display ":${DISPLAY_NUM}" -forever -shared -rfbport 5900 \
    -xkb -add_keysyms -threads -defer 5 -wait 5 -cursor most -cursorpos \
    "${VNC_AUTH[@]}" -o "$LOG_DIR/x11vnc.log" &
X11VNC_PID=$!

# ── noVNC-Startseite ──
# Ohne eigene index.html zeigt Debians noVNC-Paket ein Verzeichnislisting, und
# vnc.html verlangt erst einen Klick auf "Verbinden" und skaliert nicht. Die
# Startseite leitet deshalb mit den gewuenschten Parametern weiter.
#   rm -f zuerst: liegt dort ein Symlink auf vnc.html, wuerde ein Schreiben
#   sonst vnc.html selbst ueberschreiben.
NOVNC_QUERY="${NOVNC_OPTIONS:-autoconnect=true&resize=scale&reconnect=true&reconnect_delay=2000}"
rm -f /usr/share/novnc/index.html
cat > /usr/share/novnc/index.html <<HTML
<!doctype html>
<meta charset="utf-8">
<title>Raritan KVM</title>
<meta http-equiv="refresh" content="0; url=vnc.html?${NOVNC_QUERY}">
<a href="vnc.html?${NOVNC_QUERY}">weiter zum KVM</a>
HTML
log "noVNC-Startseite -> vnc.html?${NOVNC_QUERY}"
log "starte websockify/noVNC → http://<host>:6080/"
websockify --web=/usr/share/novnc 6080 "localhost:5900" > "$LOG_DIR/websockify.log" 2>&1 &
WS_PID=$!

GEO_W="${SCREEN_GEOMETRY%%x*}"
GEO_REST="${SCREEN_GEOMETRY#*x}"
GEO_H="${GEO_REST%%x*}"

# RcHarness liest die Geometrie aus der Umgebung, nicht aus Systemeigenschaften.
export HARNESS_WIDTH="$GEO_W" HARNESS_HEIGHT="$GEO_H"

WINDOW_TITLE_MATCH="${WINDOW_TITLE_MATCH:-Virtual KVM Client}"

# ── Fenster des Clients auf Displaygroesse ziehen ──
# Der Client oeffnet ein eigenes Fenster und rendert nicht in den Rahmen, den der
# Harness aufspannt. Ohne Zutun erscheint es in seiner Vorgabegroesse (~715x560)
# links oben, der Rest des Displays bleibt leer — und noVNC skaliert im Browser
# genau diese leere Flaeche mit.
#
# Eine fluxbox-Regel in ~/.fluxbox/apps greift hier nicht: Swing setzt den
# Fenstertitel erst NACH dem Mappen, da hat der Fenstermanager seine Regel
# laengst angewandt. Deshalb ein Waechter, der neu erscheinende Fenster einmalig
# aufzieht — einmalig, damit eine spaetere Groessenaenderung von Hand bleibt.
(
    handled=""
    while true; do
        for w in $(xdotool search --name "$WINDOW_TITLE_MATCH" 2>/dev/null); do
            case " $handled " in *" $w "*) continue ;; esac
            xdotool windowmove "$w" 0 0 windowsize "$w" 100% 100% 2>/dev/null \
                && log "Fenster $w auf ${GEO_W}x${GEO_H} gezogen"
            handled="$handled $w"
        done
        sleep 2
    done
) &
WATCH_PID=$!

cd /opt/harness
log "=== starte RcHarness → ${RARITAN_IP}:${RARITAN_PORT} ==="
# java.security.properties mit einfachem "=" ergaenzt die Vorgaben der JRE, statt
# sie zu ersetzen — nur die drei Sperren in legacy.security fallen weg.
# --limit-modules nimmt jdk.jsobject aus dem Graphen; erst dadurch greift unser
# netscape.javascript.JSObject aus harness.jar. Siehe Dockerfile.harness.
java --limit-modules java.se,jdk.crypto.ec \
     -Djava.security.properties=/opt/harness/legacy.security \
     -Dsun.security.ssl.allowUnsafeRenegotiation=true \
     -Dsun.security.ssl.allowLegacyHelloMessages=true \
     -Dsun.java2d.noddraw=true -Djava.awt.headless=false \
     -Xmx512M \
     -cp /opt/harness/harness.jar RcHarness 2>&1
EXIT=$?

log "=== RcHarness beendet (exit $EXIT) ==="
if [ "${KEEP_ALIVE:-0}" = "1" ]; then
    log "KEEP_ALIVE=1 — Container bleibt oben (X-Stack läuft weiter, Harness exit $EXIT)"
    while true; do sleep 3600; done
fi
exit "$EXIT"
