#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# Entwickler-Werkzeug: schiebt den ARBEITSSTAND (nicht den Git-Stand) auf einen
# Docker-Host und baut/startet ihn dort. Fuer die richtige Installation auf einem
# Zielhost gilt DEPLOYMENT.md — dort wird aus GitHub geklont.
#
#   ./deploy.sh              sync + build + up
#   ./deploy.sh sync         nur Dateien übertragen
#   ./deploy.sh build        sync + docker compose build
#   ./deploy.sh up           sync + build + up -d
#   ./deploy.sh restart      container neu starten (ohne rebuild)
#   ./deploy.sh logs         docker compose logs -f
#   ./deploy.sh sh           interaktive Shell auf dem Host in $DEST
#
# Zugangsdaten kommen aus deploy.env (nicht im Git):
#   DOCKER_HOST_IP=192.168.1.230
#   DOCKER_HOST_USER=root
#   DOCKER_HOST_PASS=…
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
cd "$(dirname "$0")"

[ -f deploy.env ] && . ./deploy.env

HOST="${DOCKER_HOST_IP:-192.168.1.230}"
USER="${DOCKER_HOST_USER:-root}"
DEST="${DOCKER_HOST_DEST:-/opt/raritan}"

if [ -n "${DOCKER_HOST_PASS:-}" ]; then
    export SSHPASS="$DOCKER_HOST_PASS"
    SSH=(sshpass -e ssh -o StrictHostKeyChecking=no -o LogLevel=ERROR)
else
    SSH=(ssh -o StrictHostKeyChecking=no)
fi

remote() { "${SSH[@]}" "${USER}@${HOST}" "$@"; }
compose() { remote "cd $DEST && docker compose $*"; }

sync() {
    echo "=== sync → ${USER}@${HOST}:${DEST} ==="
    remote "mkdir -p $DEST/logs"
    tar czf - \
        bridge/Bridge.cs bridge/app \
        stubs/winstub.c stubs/SystemDeployment.cs stubs/CecilPatch.cs \
        docker/Dockerfile.phase3 docker/entrypoint-phase3.sh \
        docker-compose.yml docker-compose.subnet.yml \
        .env.example .dockerignore README.md DEPLOYMENT.md \
      | remote "tar xzf - -C $DEST"
    # .env nur anlegen, nie überschreiben — enthält das DKX2-Passwort
    remote "[ -f $DEST/.env ] || cp $DEST/.env.example $DEST/.env"
    echo "ok"
}

case "${1:-up}" in
    sync)    sync ;;
    build)   sync; compose build ;;
    up)      sync; compose build; compose up -d; compose ps ;;
    restart) compose restart; compose ps ;;
    # ./deploy.sh ip 192.168.1.42 geheim  → DKX2-Zugang setzen und neu starten
    ip)      [ $# -ge 2 ] || { echo "usage: ./deploy.sh ip <DKX2-IP> [PASS] [USER]"; exit 1; }
             remote "cd $DEST && sed -i \
                 -e 's|^RARITAN_IP=.*|RARITAN_IP=$2|' \
                 ${3:+-e \"s|^RARITAN_PASS=.*|RARITAN_PASS=$3|\"} \
                 ${4:+-e \"s|^RARITAN_USER=.*|RARITAN_USER=$4|\"} .env && grep -E '^RARITAN_(IP|USER)=' .env"
             compose up -d; compose ps ;;
    # Screenshot des Xvfb-Displays holen (Container muss laufen)
    shot)    OUT="${2:-screen-$(date +%H%M%S).png}"
             compose "exec -T raritan-akc bash -lc 'DISPLAY=:99 xwd -root -silent | xwdtopnm 2>/dev/null | pnmtopng > /logs/screen.png'"
             remote "cat $DEST/logs/screen.png" > "$OUT"
             echo "→ $OUT ($(du -h "$OUT" | cut -f1))" ;;
    down)    compose down ;;
    logs)    compose logs -f --tail=200 ;;
    ps)      compose ps ;;
    sh)      "${SSH[@]}" -t "${USER}@${HOST}" "cd $DEST && exec bash -l" ;;
    *)       echo "unbekannt: $1"; exit 1 ;;
esac
