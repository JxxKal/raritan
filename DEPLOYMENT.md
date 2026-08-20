# Deployment Guide

Anleitung für den Betrieb des Raritan-AKC-Wrappers als Docker-Compose-Stack.

Der Stack macht eine Dominion KX2 aus einem normalen Browser bedienbar — ohne
Java, ohne Internet Explorer, ohne Client-Installation. Der Original-Client von
Raritan läuft dafür unter Mono in einem virtuellen X-Server im Container und wird
als VNC beziehungsweise noVNC nach außen gereicht.

---

## 1. Voraussetzungen

- Docker Engine 24 oder neuer mit Compose-Plugin v2
  (`docker --version`, `docker compose version`)
- Rund 2,5 GB freier Plattenplatz — das Image allein misst 1,33 GB
- Freie Ports für VNC, noVNC und die Control-Oberfläche (per `.env` einstellbar)
- Netzwerksicht vom Host auf die DKX2, Port 443/tcp
- Ein Konto auf der DKX2 mit Zugriff auf die gewünschten KVM-Ports

Der Host selbst braucht **keinen** Grafikstack, keine X-Installation und keine
GPU — alles davon steckt im Container.

Der Stack besteht aus einem Dienst:

| Dienst | Aufgabe | Ports |
|---|---|---|
| `raritan-akc` | Xvfb + fluxbox, x11vnc, websockify/noVNC, AKC unter Mono | 5900, 6080, 8081 |

Was im Container übereinander liegt:

```
Browser ─WS─▶ noVNC/websockify (6080) ─VNC─▶ x11vnc (5900) ─grab─▶ Xvfb :99
                                                                      ▲
                                                               render │
                                               AKC (kxgui-patched.exe) unter Mono
                                                                      │ HTTPS
                                                                      ▼
                                                                 DKX2 (443)
```

Details zur Funktionsweise stehen in der [README](README.md).

## 2. Installation

```bash
git clone https://github.com/JxxKal/raritan.git
cd raritan
cp .env.example .env
```

`.env` bearbeiten. Zwei Werte **müssen** gesetzt werden, der Stack startet sonst nicht:

```bash
RARITAN_IP=10.180.42.160     # Adresse der DKX2
RARITAN_PASS=…               # Passwort des DKX2-Kontos
```

Ports anpassen, falls 5900/6080 belegt sind — auf Hosts mit anderen VNC- oder
noVNC-Diensten ist das die Regel:

```bash
VNC_HOST_PORT=5901
NOVNC_HOST_PORT=6081
CTRL_HOST_PORT=8081
```

Starten:

```bash
docker compose up -d --build
```

Der erste Start baut das Image; je nach Anbindung dauert das einige Minuten. Die
Binärdateien des AKC liegen im Repository, es wird dafür nichts von Raritan
nachgeladen.

### Hinter einem Proxy

Ein Proxy greift an **drei** getrennten Stellen, und nur die mittlere kommt aus
der `.env`. Wer das durcheinanderbringt, sucht den Fehler an der falschen Stelle.

**1. Das Basis-Image zieht der Docker-Daemon**, nicht der Build. Der Proxy
gehört deshalb in die Daemon-Konfiguration, sonst scheitert schon `FROM
debian:bookworm-slim`:

```bash
mkdir -p /etc/systemd/system/docker.service.d
cat > /etc/systemd/system/docker.service.d/http-proxy.conf <<'EOF'
[Service]
Environment="HTTP_PROXY=http://proxy.example.org:3128"
Environment="HTTPS_PROXY=http://proxy.example.org:3128"
Environment="NO_PROXY=localhost,127.0.0.1"
EOF
systemctl daemon-reload && systemctl restart docker
```

`systemctl restart docker` stoppt kurzzeitig **alle** Container auf dem Host.

**2. Der Bau selbst (apt)** nimmt die Werte aus der `.env`:

```bash
HTTP_PROXY=http://proxy.example.org:3128
HTTPS_PROXY=http://proxy.example.org:3128
NO_PROXY=localhost,127.0.0.1
```

Compose reicht sie als vordefinierte Build-Argumente durch; ein zusätzliches
`--build-arg` ist nicht nötig, und sie landen nicht in der Image-Historie.

**3. Zur Laufzeit wird bewusst kein Proxy verwendet.** Der Container spricht
ausschließlich mit der DKX2 im lokalen Netz. Der Stack setzt dafür fest
`no_proxy=*` im Container — diese Zeile in der `docker-compose.yml` bitte stehen
lassen. Der Grund: Mono wertet `http_proxy`/`HTTP_PROXY` auch für `https://`-Adressen
aus, und der Docker-Client kann über `~/.docker/config.json` jedem Container
global einen Proxy mitgeben. Dann läuft die Anmeldung an der DKX2 in den Proxy —
sie scheitert, und die Zugangsdaten waren trotzdem dort.

Eine einzelne IP in `no_proxy` genügt dafür **nicht**. Gemessen am fertigen
Image, jeweils Anmeldung an einer nicht erreichbaren Test-IP:

| Umgebung im Container | Verhalten |
|---|---|
| kein Proxy | direkt — `No route to host` nach 2,1 s |
| `http_proxy=…` (auch nur `HTTP_PROXY`) | über den Proxy — Fehler nach 0,3 s |
| `http_proxy=…` + `no_proxy=<DKX2-IP>` | weiterhin über den Proxy |
| `http_proxy=…` + `no_proxy=*` | wieder direkt |

Mono ignoriert IP-Adressen in der Ausnahmeliste; nur `*` schaltet den Proxy
zuverlässig ab.

Zum Prüfen, was tatsächlich ankommt:

```bash
docker compose config | grep -i proxy          # Bau
docker compose exec raritan-akc env | grep -i proxy   # Laufzeit
```

Zur Laufzeit darf dort nur `no_proxy=*` und `NO_PROXY=*` stehen.

### Ohne Internet auf dem Zielhost

Kommt der Zielhost gar nicht ins Netz, wird das Image auf einem Rechner mit
Anbindung gebaut und als Datei übertragen:

```bash
# auf dem Rechner mit Netz
./build-phase3.sh                       # baut und schreibt raritan-akc-phase3.tar.gz
```

Die Datei auf den Zielhost bringen und dort einspielen:

```bash
gunzip -c raritan-akc-phase3.tar.gz | docker load
git clone https://github.com/JxxKal/raritan.git && cd raritan
cp .env.example .env && vi .env
docker compose up -d          # ohne --build, das Image ist schon da
```

Ohne `--build` nimmt Compose das vorhandene `raritan-akc-phase3:latest` und baut
nichts nach. Das Repository wird trotzdem gebraucht, weil `docker-compose.yml`
und `.env` daraus kommen.

## 3. Erste Nutzung

Die Oberfläche im Browser öffnen:

```
http://<host>:<NOVNC_HOST_PORT>/
```

*Connect* anklicken — bei gesetztem `VNC_PASSWORD` danach das Passwort eingeben.
Zu sehen ist das Fenster des AKC mit der Konsole des Zielrechners. Tastatur und
Maus gehen von dort direkt an den angeschlossenen Rechner.

Ob die Anmeldung geklappt hat, zeigt das Protokoll:

```bash
docker compose logs raritan-akc | grep -E 'Login|Connect|ports'
```

**Zwischen KVM-Ports umschalten.** Die Bridge bringt dafür eine kleine
Oberfläche mit:

```
http://<host>:<CTRL_HOST_PORT>/
```

| Adresse | Inhalt |
|---|---|
| `/` | Oberfläche mit einer Schaltfläche je KVM-Port |
| `/ports` | Portliste der DKX2 als JSON |
| `/status` | aktueller Zustand der Sitzung |
| `/switch?port=N` | auf Port `N` umschalten |
| `/debug/…` | Innenansicht des AKC, nur zur Fehlersuche |

Soll immer derselbe Port kommen, dessen Kennung aus `/ports` in die `.env`
eintragen (`RARITAN_PORT_ID`), sonst nimmt die Bridge den ersten verfügbaren.

## 4. Zugriff absichern

**Der Stack bringt keine Benutzerverwaltung mit.** Wer noVNC erreicht, sitzt an
der Konsole des Zielrechners — in der Regel mit Administratorrechten und ohne
weitere Anmeldung. In einem OT-Netz ist das die entscheidende Frage, nicht die
Installation.

Drei Maßnahmen, aufeinander aufbauend:

*1. VNC-Passwort setzen.* Ohne `VNC_PASSWORD` nimmt x11vnc jede Verbindung an:

```bash
VNC_PASSWORD=…
```

*2. Die Ports nicht offen ins Netz stellen.* Die Port-Angaben in der `.env`
dürfen eine Adresse enthalten; damit lauscht Docker nur dort:

```bash
NOVNC_HOST_PORT=127.0.0.1:6080
VNC_HOST_PORT=127.0.0.1:5900
CTRL_HOST_PORT=127.0.0.1:8081
```

Danach ist der Stack nur noch vom Host selbst erreichbar und wird über einen
vorgelagerten Reverse Proxy oder Guacamole veröffentlicht, der die Anmeldung und
die Protokollierung übernimmt.

*3. Die Control-Oberfläche auf 8081 hat keine Authentisierung.* Sie schaltet
KVM-Ports um und öffnet über `/debug/…` die Innenansicht des Clients. Sie gehört
nicht ins Netz — entweder wie oben an `127.0.0.1` binden oder die Zeile mit
`CTRL_HOST_PORT` in der `docker-compose.yml` ganz entfernen, wenn ohnehin nur ein
Port bedient wird.

Zusätzlich: das DKX2-Konto in `.env` nur mit den Rechten ausstatten, die
gebraucht werden. Der AKC übernimmt die Rechte des angemeldeten Benutzers, ein
`admin` ist dafür selten nötig.

## 5. Betrieb

**Status und Protokolle**

```bash
docker compose ps
docker compose logs -f raritan-akc
ls logs/                # bridge.log, x11vnc.log, xvfb.log, websockify.log
```

Der Healthcheck prüft alle drei Ports — auch die Control-API auf 8081, die
Bridge.exe erst nach erfolgreicher Anmeldung an der DKX2 öffnet. `healthy` heißt
damit: Anzeige läuft **und** die Sitzung zur DKX2 steht. Bricht die Bridge ab,
fällt der Container binnen einer Minute auf `unhealthy`.

**Bildschirmabzug ohne Browser.** Praktisch für Fernwartung und Fehlerberichte:

```bash
docker compose exec raritan-akc bash -lc \
    'DISPLAY=:99 xwd -root -silent | xwdtopnm | pnmtopng > /logs/screen.png'
```

Das Bild liegt danach als `logs/screen.png` neben dem Compose-File.

**Aktualisieren**

```bash
git pull
docker compose up -d --build
```

Hinter einem Proxy zieht `--build` die Angaben aus der `.env`; ein zusätzliches
`--build-arg` ist nicht nötig.

**Neu verbinden.** Die Sitzung zur DKX2 wird beim Start aufgebaut. Nach einem
Neustart der DKX2 oder einem Netzausfall:

```bash
docker compose restart raritan-akc
```

**Stoppen und zurücksetzen**

```bash
docker compose down          # Stack anhalten
docker compose down --rmi local   # zusätzlich das Image entfernen
```

Der Stack hält keine Nutzdaten vor — es gibt nichts zu sichern außer der `.env`.

## 6. Fehlersuche

**`RARITAN_IP fehlt`** beim Start — `.env` fehlt oder die Variable ist nicht
gesetzt. Compose liest `.env` aus dem Verzeichnis, in dem der Befehl läuft.

**`Login failed` mit `ConnectFailure`** — der Container erreicht die DKX2 nicht.
Der Reihe nach prüfen:

```bash
docker compose logs raritan-akc | head -20        # steht dort schon "nicht erreichbar"?
docker compose exec raritan-akc nc -zv <DKX2-IP> 443
docker compose exec raritan-akc env | grep -i proxy   # darf nur no_proxy=* zeigen
```

Antwortet `nc` nicht, liegt es am Netz oder an einer Firewall zwischen Host und
DKX2. Zeigt `env` einen Proxy, siehe Abschnitt 2 — dann geht die Anmeldung an den
Proxy statt an die DKX2.

**`Login failed` ohne `ConnectFailure`** — die DKX2 antwortet, weist die
Zugangsdaten aber ab. Benutzer und Passwort in der `.env` prüfen; Sonderzeichen
gehören in Anführungszeichen. Zur Gegenprobe dieselben Daten in der Web-Oberfläche
der DKX2 verwenden.

**Port ist belegt** — `Bind for 0.0.0.0:5900 failed: port is already allocated`.
Belegte Ports zeigt `ss -ltn`; alternative Ports über die `.env` setzen. 5900 und
6080 sind auf Hosts mit anderen KVM- oder noVNC-Diensten häufig schon vergeben.

**`all predefined address pools have been fully subnetted`** — der Docker-Daemon
hat keine Adressbereiche mehr für ein weiteres Bridge-Netz. Ab Werk vergibt
Docker aus `172.17.0.0/12` Blöcke der Größe /16, also nur 16 Stück.

Was belegt ist:

```bash
docker network inspect $(docker network ls -q) \
    --format '{{.Name}} {{range .IPAM.Config}}{{.Subnet}}{{end}}'
```

Drei Auswege, in der Reihenfolge des geringsten Eingriffs:

*1. Aufräumen* — meist liegen Netze verwaister Projekte herum:

```bash
docker network prune
docker compose up -d
```

*2. Eigenes Subnetz für diesen Stack.* Umgeht die Pool-Vergabe ganz und kommt
ohne Neustart des Daemons aus. Zwei Zeilen in die `.env`:

```bash
RARITAN_SUBNET=172.31.251.0/24
COMPOSE_FILE=docker-compose.yml:docker-compose.subnet.yml
```

`COMPOSE_FILE` sorgt dafür, dass **jeder** Compose-Befehl die Override-Datei
mitnimmt; ohne diese Zeile müsste sie jedes Mal angehängt werden, und ein
vergessenes `docker compose up -d` legt das Netz wieder aus dem Pool an. Der
Bereich darf sich weder mit dem OT-Netz noch mit einem anderen Docker-Netz auf
dem Host überschneiden.

*3. Den Pool des Daemons vergrößern*, wenn auf dem Host dauerhaft viele Stacks
laufen. In `/etc/docker/daemon.json`:

```json
{
  "default-address-pools": [
    { "base": "172.17.0.0/12", "size": 24 },
    { "base": "10.200.0.0/16", "size": 24 }
  ]
}
```

Danach `systemctl restart docker` — das stoppt kurzzeitig **alle** Container auf
dem Host, und die Basisbereiche dürfen nicht mit dem OT-Netz kollidieren.

**Auf der Control-API (8081) kommt keine Verbindung zustande** — den Port öffnet
Bridge.exe erst, wenn die Anmeldung an der DKX2 durch ist. `Connection refused`
heißt deshalb nicht *Port falsch gemappt*, sondern *die Bridge läuft nicht*. Der
Grund steht im Protokoll:

```bash
docker compose logs raritan-akc | grep -E 'Login|Control API'
```

Steht dort `Login failed`, gilt der Abschnitt darüber. Ist der Container zugleich
`unhealthy`, ist das dieselbe Ursache — der Healthcheck prüft diesen Port mit.

**Der Browser zeigt nur einen schwarzen Bildschirm** — noVNC ist verbunden, aber
der AKC malt nichts. Meist ist die Bridge abgebrochen, der X-Stack läuft weiter.
Für die Fehlersuche den Container oben halten und nachsehen:

```bash
# .env: KEEP_ALIVE=1
docker compose up -d
docker compose logs raritan-akc | tail -40
docker compose exec raritan-akc bash -lc 'DISPLAY=:99 xdotool search --name . getwindowname %@'
```

Erscheint dort kein AKC-Fenster, steht der Grund im Protokoll oberhalb.

**Der Browser verbindet gar nicht** — `Failed to connect to server`. Dann
antwortet websockify nicht:

```bash
curl -I http://<host>:<NOVNC_HOST_PORT>/vnc.html    # erwartet: 200
docker compose logs raritan-akc | grep -i websockify
cat logs/websockify.log
```

**Maus geht, Tastatur nicht** — das ist der klassische Fehler dieses Aufbaus.
Er tritt auf, wenn der Fenstermanager fehlt: ohne fokussiertes Fenster verwirft X
die von x11vnc eingespeisten Tasten. Der Container startet fluxbox deshalb selbst.
Zeigt `logs/fluxbox.log` einen Abbruch, ist das die Spur; ein modaler Dialog vor
dem AKC-Fenster hat dieselbe Wirkung.

**Der Container beendet sich beim Schließen des KVM-Viewers** — der AKC räumt
den Render-Baum sehr tief rekursiv ab und lief dabei früher über den Stack. Der
Stack setzt dafür `ulimits: stack: -1`; der Entrypoint hebt den weichen Wert dann
selbst an. Zur Kontrolle steht die verwendete Größe in der Startzeile des
Protokolls (`stack=524288`).

**Ein zweiter Checkout übernimmt den laufenden Stack** — Compose identifiziert
einen Stack über seinen Projektnamen. Der ist hier auf `raritan` festgelegt, also
für alle Arbeitskopien auf dem Host derselbe: ein `docker compose up -d` aus einem
zweiten Verzeichnis konfiguriert den **vorhandenen** Container um, statt einen
zweiten zu starten. Pro Host ist genau eine Instanz vorgesehen. Wer trotzdem
parallel testen will, gibt einen eigenen Namen mit:

```bash
docker compose -p raritan-test up -d
```

**Die DKX2 meldet die Sitzung als belegt** — eine KX2 erlaubt je Port nur eine
Virtual-Media-Sitzung. Andere Clients auf demselben Port trennen, oder in der
Oberfläche der DKX2 unter *Active Users* nachsehen.

**Bau scheitert an `Unable to connect` oder `Could not resolve host`** — der Host
braucht einen Proxy, oder der gesetzte stimmt nicht. Scheitert schon der erste
Schritt (`FROM debian:bookworm-slim`), fehlt er beim **Daemon**, nicht im Build.
Siehe *Hinter einem Proxy* in Abschnitt 2.
