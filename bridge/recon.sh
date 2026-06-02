#!/bin/bash
# Raritan DKX2 HTTP-Recon — kein Mono, kein Docker. Reines curl auf dem OT-Host.
#
# Ausgabe nach $OUT_DIR (default ./recon-logs/). Verpacken und zurückbringen.
#
# Nutzung:
#   RARITAN_IP=10.180.42.160 RARITAN_USER=admin RARITAN_PASS=Abcdmin01 ./recon.sh

set -u
HOST="${RARITAN_IP:?RARITAN_IP env var missing}"
USER="${RARITAN_USER:-admin}"
PASS="${RARITAN_PASS:?RARITAN_PASS env var missing}"
OUT="${OUT_DIR:-./recon-logs}"
mkdir -p "$OUT"

BASE="https://${HOST}"
COOKIES="$OUT/cookies.txt"
: > "$COOKIES"

CURL_BASE=(curl --insecure --silent --show-error
           --cookie-jar "$COOKIES" --cookie "$COOKIES"
           --max-time 15
           --user-agent "Raritan-Recon/1.0")

dump() {
    local tag="$1"; shift
    local out_html="$OUT/${tag}.html"
    local out_hdr="$OUT/${tag}.headers"
    echo "=== $tag ==="
    "${CURL_BASE[@]}" --dump-header "$out_hdr" --output "$out_html" "$@"
    echo "    HTTP: $(head -1 "$out_hdr" 2>/dev/null)"
    echo "    Size: $(wc -c <"$out_html" 2>/dev/null) bytes -> $out_html"
    grep -iE "^(set-cookie|location|content-type):" "$out_hdr" | sed 's/^/    /'
}

echo "Raritan: $HOST"
echo "User:    $USER"
echo "Output:  $OUT"
echo

# 1. Root + Login-Seite anonym holen
dump "01-root"          "$BASE/"
dump "02-auth"          "$BASE/auth.asp"
dump "03-style"         "$BASE/style.asp"

# 2. Diverse Login-POSTs, jeden Body komplett ablegen
dump "10-post-auth-asp-username"  -X POST -d "username=${USER}&password=${PASS}" "$BASE/auth.asp"
dump "11-post-auth-asp-encoded"   -X POST --data-urlencode "username=${USER}" --data-urlencode "password=${PASS}" "$BASE/auth.asp"
dump "12-post-auth-asp-action"    -X POST -d "action=login&username=${USER}&password=${PASS}" "$BASE/auth.asp"
dump "13-post-auth-asp-submit"    -X POST -d "username=${USER}&password=${PASS}&submit=Login" "$BASE/auth.asp"

# 3. Login-CGI-Pfade die in Raritan-Boxen vorkommen
dump "20-post-cgi-auth"   -X POST -d "username=${USER}&password=${PASS}" "$BASE/cgi-bin/auth.cgi"
dump "21-post-cgi-login"  -X POST -d "username=${USER}&password=${PASS}" "$BASE/cgi-bin/login.cgi"
dump "22-post-cgi-eric"   -X POST -d "username=${USER}&password=${PASS}" "$BASE/cgi-bin/eric.cgi"

# 4. Nach (möglichem) Login: was kommt jetzt
dump "30-post-login-then-root"  "$BASE/"
dump "31-post-login-then-start" "$BASE/start.asp"
dump "32-post-login-then-admin" "$BASE/admin.asp"

# 5. AKC-relevante Endpoints (die Web-UI nutzt die zum Launch)
dump "40-akc-app"          "$BASE/akc/akc.application"
dump "41-akc-akc"          "$BASE/akc/"
dump "42-akc-launch"       "$BASE/akc/akc.html"
dump "43-akc-akcLaunch"    "$BASE/akcLaunch.html"
dump "44-akc-launch-asp"   "$BASE/akcLaunch.asp"

# 6. Cookies und Forms aus den Bodies extrahieren
echo
echo "=== Cookies ==="
cat "$COOKIES" | grep -v "^#" | grep -v "^$" | sed 's/^/    /'

echo
echo "=== <form action> in allen Bodies ==="
for f in "$OUT"/*.html; do
    matches=$(grep -oiE 'action="[^"]*"|action='"'"'[^'"'"']*'"'"'|<form[^>]*>' "$f" 2>/dev/null | head -3)
    if [ -n "$matches" ]; then
        echo "    $(basename "$f"):"
        echo "$matches" | sed 's/^/        /'
    fi
done

echo
echo "=== <input name> in allen Bodies ==="
for f in "$OUT"/*.html; do
    matches=$(grep -oiE '<input[^>]*name="[^"]*"[^>]*>' "$f" 2>/dev/null | head -10)
    if [ -n "$matches" ]; then
        echo "    $(basename "$f"):"
        echo "$matches" | sed 's/^/        /'
    fi
done

echo
echo "=== document.location / window.location in JS ==="
for f in "$OUT"/*.html; do
    matches=$(grep -iE '(document|window|self)\.location|location\.href|XMLHttpRequest|fetch\(' "$f" 2>/dev/null | head -5)
    if [ -n "$matches" ]; then
        echo "    $(basename "$f"):"
        echo "$matches" | head -5 | sed 's/^/        /'
    fi
done

echo
echo "=== form/post URLs anywhere ==="
for f in "$OUT"/*.html; do
    matches=$(grep -oiE '(\.cgi|\.asp|\.html)\?[^"'"'"' ]*|"/[a-zA-Z][^"]*\.(cgi|asp|xml)"' "$f" 2>/dev/null | sort -u | head -15)
    if [ -n "$matches" ]; then
        echo "    $(basename "$f"):"
        echo "$matches" | sed 's/^/        /'
    fi
done

echo
echo "Done. Tar es zusammen und schick zurück:"
echo "    tar -czf raritan-recon.tar.gz $OUT"
