# Raritan AKC Headless Bridge — Phase 2 (AKC unter Xvfb + x11vnc)

AKC läuft komplett in Mono in einem Xvfb-Virtual-Display. Wir machen den HTTP-Login extern und triggern AKC's BrowserMediator via Reflection. KVM-Bild rendert AKC selbst in den Xvfb. `x11vnc` exportiert das Display als VNC; `noVNC` als Browser-Frontend.

## Voraussetzungen

- Debian-Host mit Docker
- Erreichbarkeit zur DKX2 auf TCP 443
- Funktionierende Raritan-Login-Credentials
- VNC-Viewer (z.B. TigerVNC) ODER moderner Browser für noVNC

## Image laden

```bash
gunzip raritan-akc-bridge-phase1.tar.gz
docker load -i raritan-akc-bridge-phase1.tar
```

## Bridge ausführen

```bash
mkdir -p ./bridge-logs

docker run --rm \
    --name raritan-bridge \
    -p 5900:5900 \
    -p 6080:6080 \
    -p 8081:8081 \
    -e RARITAN_IP=10.180.42.160 \
    -e RARITAN_USER=admin \
    -e RARITAN_PASS=<dein-passwort> \
    -v "$PWD/bridge-logs:/logs" \
    raritan-akc-bridge:phase1
```

Hinweis: VNC läuft ohne Passwort — wer Port 5900/6080/8081 erreicht hat direkten KVM-Zugriff. In OT-Umgebungen ist das durch die Netzwerk-Segmentierung abgesichert; wenn nicht, dem `docker run` ein `--network=internal` oder Firewall vorschalten.

Beim Start sucht die Bridge **automatisch** die Ports der DKX2 aus `/sidebar.asp` und verbindet zum ersten verfügbaren KVM-Port mit angeschlossenem CIM (Status=1). Override via:
- `-e RARITAN_PORT_ID=P_000d5d06a393_0` → expliziter Port-ID

## Ports umschalten (HTTP Control API)

```bash
# Liste aller verfügbaren Ports am Gerät
curl http://<container-host>:8081/ports

# Aktueller Status
curl http://<container-host>:8081/status

# Auf Port-Index 1 wechseln (zweiter Port, da Index 0-basiert)
curl http://<container-host>:8081/switch?port=1

# Oder explizit über portId
curl http://<container-host>:8081/switch?portId=P_000d5d06a393_2
```

Die Bridge ruft intern `BrowserMediator.Connect(1, oldIdx, newIdx, ...)` auf — derselbe Code-Pfad den AKC normal nutzt. Das aktive KVM-Session wechselt, das noVNC-View zeigt sofort die neue Port-View.

## KVM-Bild anschauen

**Via Browser (komfortabel, mit Port-Switching):**
```
http://<container-host>:8081/
```
Zeigt Port-Liste oben + noVNC eingebettet darunter. Klick auf einen Port wechselt automatisch.

**Direkt noVNC (nur KVM, ohne Port-UI):**
```
http://<container-host>:6080/vnc.html
```
Auto-Connect ohne Passwort.

**Via VNC-Viewer:**
```
vncviewer <container-host>:5900
```
Falls remote: SSH-Tunnel: `ssh -L 5900:localhost:5900 user@container-host` + `vncviewer localhost:5900`.

## Was im Log zu erwarten ist

`bridge-logs/bridge-output.log`:
```
=== Bridge v16 starting ===
POST https://10.180.42.160/auth.asp?client=dotnet -> 302
Got pp_session_id (64 chars)
Loaded kxgui, Version=1.0.6.572
... ctor(... t / BrowserMediator) -> ok
Calling BrowserMediator.Init(xml)
DeviceConncetionParameters : Host: 10.180.42.160, Port: 443, ...
Calling BrowserMediator.Connect(0, "0", "0", "P_000d5d06a393_0", ...)
SSL authentication successful
Successfully logged in.
Connection Parameter: "BOARD_NAME" - "Dominion KX2"
... (60+ params)
Showing form Com.Raritan.KxGui.t on DISPLAY=:99
=== starting Application.Run() — UI thread takes over ===
```

Bei Erfolg: VNC zeigt jetzt das AKC-Hauptfenster mit dem geöffneten KVM-Viewer-Subfenster, das das tatsächliche KVM-Bild deiner DKX2 anzeigt (schwarz wegen DisplayPort-Adapter — aber Bild + Cursor sollten sichtbar sein).

## Probleme zu erwarten

| Symptom | Wahrscheinliche Ursache |
|---|---|
| Bridge crasht beim Form.Show() | Mono WinForms hat ein Issue mit einem Control aus AKC — Stack-Trace bringen |
| VNC zeigt nur grauen Hintergrund | Form ist da aber AKC malt nicht — KVM-Viewer-Subform öffnet nicht. Form möglicherweise minimized |
| VNC connection refused | x11vnc nicht gestartet, oder `-p 5900:5900` fehlt im docker run |
| noVNC zeigt "failed to connect" | websockify-Issue, in `bridge-logs/websockify.log` checken |
| "Permission denied" bei RFB-Auth | pp_session_id wird vom Server abgelehnt — meistens transient (alte Session noch aktiv, ~2min warten) |
