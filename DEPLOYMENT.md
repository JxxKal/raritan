# Deployment Guide

Anleitung für den Betrieb des Raritan-AKC-Wrappers als Docker-Compose-Stack.

Der Stack macht eine Dominion KX2 aus einem normalen Browser bedienbar — ohne
Java, ohne Internet Explorer, ohne Client-Installation. Der Original-Client von
Raritan läuft dafür unter Mono in einem virtuellen X-Server im Container und wird
als VNC beziehungsweise noVNC nach außen gereicht.

---

## 0. Kurzfassung für einen abgeschotteten Host

Erreicht der Host GitHub über einen Proxy, ist das alles. Die Reihenfolge ist
wichtig: Punkt 1 wird gern vergessen, und der Fehler taucht dann im Build auf.

```bash
# 1. Proxy für den Docker-DAEMON — er zieht das Basis-Image, nicht der Build.
mkdir -p /etc/systemd/system/docker.service.d
cat > /etc/systemd/system/docker.service.d/http-proxy.conf <<'EOF'
[Service]
Environment="HTTP_PROXY=http://proxy.example.org:3128"
Environment="HTTPS_PROXY=http://proxy.example.org:3128"
Environment="NO_PROXY=localhost,127.0.0.1,192.168.0.0/16"
EOF
systemctl daemon-reload && systemctl restart docker    # stoppt kurz ALLE Container

# 2. Proxy für git
git config --global http.proxy http://proxy.example.org:3128

# 3. Holen und einrichten
git clone https://github.com/JxxKal/raritan.git && cd raritan
cp .env.example .env
vi .env      # RARITAN_IP, RARITAN_PASS, und HTTP_PROXY/HTTPS_PROXY für apt

# 4. Starten
docker compose up -d --build
```

Danach `http://<host>:6080/` im Browser. Die Seite verbindet von selbst.

**Aktualisieren:**

```bash
git pull && docker compose up -d --build
```

**Nach einem Firmware-Update der KX2** genügt ein `docker compose restart`: der
Client (`rc.jar`) wird bei jedem Start vom Gerät geladen, nicht aus dem Image.
Das Image bleibt unberührt.

**Zur Laufzeit braucht der Container kein Internet** — nur das lokale Netz zur
KX2. Der Proxy wird ausschliesslich beim Bauen gebraucht, im Container ist er
mit `no_proxy=*` ausdrücklich abgeschaltet (Begründung in Abschnitt 2).

Geht gar nichts über den Proxy, siehe *Ohne Internet auf dem Zielhost* in
Abschnitt 2 — dann wird das Image anderswo gebaut und als Datei übertragen.

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

Der Stack kennt zwei Wege zum selben Ziel. Gestartet wird nur der erste:

| Dienst | Client | Ports | Start |
|---|---|---|---|
| `raritan-kvm` | der Java-Client, den die KX2 selbst ausliefert, ohne Browser | 5900, 6080 | Standard |
| `raritan-akc` | der Vorgänger: der .NET-Client unter Mono | 5901, 6081, 8081 | nur mit `--profile akc` |

`raritan-kvm` lädt `rc.jar` beim Start vom Gerät. Der Client passt damit immer
zur laufenden Firmware — nach einem Firmware-Update genügt ein Neustart des
Containers, das Image bleibt unberührt.

Der ältere `raritan-akc` bringt als einziger eine Control-Oberfläche zum
Umschalten zwischen KVM-Ports mit (Port 8081); beim Standarddienst geschieht das
über `RARITAN_PORT_ID`. Beide Dienste hören auf getrennte Host-Ports und können
nebeneinander laufen.

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
VNC_HOST_PORT=5910
NOVNC_HOST_PORT=6090
```

Starten:

```bash
docker compose up -d --build
```

Das ist alles. Der erste Start baut das Image; je nach Anbindung dauert das
einige Minuten. Danach läuft der Stack unter `docker compose ps` als
`raritan-kvm`.

Den alten Weg über den .NET-Client startet man bei Bedarf zusätzlich:

```bash
docker compose --profile akc up -d --build
```

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
docker compose exec raritan-kvm env | grep -i proxy   # Laufzeit
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
docker compose logs raritan-kvm | grep -E 'harness|Login|Connect'
```

**Zwischen KVM-Ports umschalten.** Der Standarddienst nimmt den Port aus
`RARITAN_PORT_ID` in der `.env` (leer = der erste, den das Gerät nennt); ein
Wechsel ist ein `docker compose up -d`. Die Kennungen stehen im Protokoll:

```bash
docker compose logs raritan-kvm | grep "gefundene Ports"
```

Der Dienst aus dem Profil `akc` bringt dafür eine kleine Oberfläche mit:

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
docker compose logs -f raritan-kvm
ls logs/                # bridge.log, x11vnc.log, xvfb.log, websockify.log
```

Der Healthcheck von `raritan-kvm` prüft VNC und noVNC; `healthy` heißt also *der
Bildschirm wird ausgeliefert*. Ob die Sitzung zur KX2 steht, sagt das Protokoll.
Beim Dienst aus dem Profil `akc` gehört die Control-API auf 8081 mit zum
Healthcheck — dort heißt `healthy` zusätzlich, dass die Sitzung steht.

**Bildschirmabzug ohne Browser.** Praktisch für Fernwartung und Fehlerberichte:

```bash
docker compose exec raritan-kvm bash -lc \
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
docker compose restart raritan-kvm
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
docker compose logs raritan-kvm | head -20        # steht dort schon "nicht erreichbar"?
docker compose exec raritan-kvm nc -zv <KX2-IP> 443
docker compose exec raritan-kvm env | grep -i proxy   # darf nur no_proxy=* zeigen
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
RARITAN_SUBNET=10.201.7.0/24
COMPOSE_FILE=docker-compose.yml:docker-compose.subnet.yml
```

`COMPOSE_FILE` sorgt dafür, dass **jeder** Compose-Befehl die Override-Datei
mitnimmt; ohne diese Zeile müsste sie jedes Mal angehängt werden, und ein
vergessenes `docker compose up -d` legt das Netz wieder aus dem Pool an.

Der Bereich sollte **außerhalb von `172.16.0.0/12`** liegen. Daraus bedient sich
Docker selbst — `172.17.0.0/16`, `172.18.0.0/16` und so fort. Was dort heute frei
ist, vergibt der Daemon morgen an einen anderen Stack, und dann steht dieser hier.

Ob die Override-Datei überhaupt greift, und was danach steht:

```bash
docker compose config | grep -A3 ipam        # vor dem Start
docker network inspect raritan_raritan-net \
    --format '{{range .IPAM.Config}}{{.Subnet}}{{end}}'
```

### `pool overlaps with other address space`

Die andere Hälfte, und sie bedeutet etwas anderes als *fully subnetted*: der
gewählte Bereich überschneidet sich mit etwas, das der Host schon kennt. Das ist
**nicht nur** ein anderes Docker-Netz — Docker prüft auch die Routen des Hosts.
Ein Bereich, der zum OT-Netz gehört oder über ein VPN geroutet wird, fällt
genauso durch. Beide Listen prüfen:

```bash
docker network inspect $(docker network ls -q) \
    --format '{{.Name}} {{range .IPAM.Config}}{{.Subnet}}{{end}}'
ip route
```

Dann einen Bereich wählen, der in keiner der beiden auftaucht. Die zwei Zeilen
wieder auszukommentieren hilft nicht: dann greift erneut die Pool-Vergabe, und
man ist zurück bei *fully subnetted*.

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

**`[0x10020004] Connecting to Port … failed. …could not detect video.`** — die
KX2 erreicht den Zielrechner nicht. In aller Regel steckt kein CIM am Port, oder
der Zielrechner liefert kein Bild. Welche Ports belegt sind, zeigt die
Weboberfläche des Geräts; ein Port ohne CIM meldet dort *Not Available*.

**`[0x10000003] Authentication failed`** — die RFB-Anmeldung wurde abgewiesen,
obwohl die HTTP-Anmeldung lief (im Protokoll steht dann bereits eine Session).
Der Client handelt das Verfahren selbst aus; mit `HARNESS_SEND_PASSWORD=1`
bekommt er Benutzer, Passwort und Session und darf wählen. Steht dort 0, wird nur
die Session angeboten — was manche Firmware ablehnt.

**Auf der Control-API (8081) kommt keine Verbindung zustande** — die gibt es nur
im Profil `akc`; ohne `--profile akc` läuft dort nichts. Im Profil öffnet den Port
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
