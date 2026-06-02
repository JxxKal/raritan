# Raritan AKC Diagnose-Container — OT Test-Anleitung

Dieser Container prüft, ob der Raritan-AKC-Client unter Linux/Mono gegen die echte DKX2 connected, und sammelt Logs zur Auswertung.

## Voraussetzungen auf dem OT-Docker-Host

- Debian mit installiertem `docker` (Engine, kein Desktop nötig)
- Netzwerk-Erreichbarkeit zur Raritan-IP (TCP 443 + TCP 5000)
- ~1 GB freier Platz
- Optional: ein VNC-Client (`tigervnc-viewer`, `remmina`) wenn du die GUI sehen willst

## Image importieren

```bash
gunzip raritan-akc-diagnose.tar.gz
docker load -i raritan-akc-diagnose.tar
docker image ls | grep raritan-akc-diagnose
```

## Diagnose-Lauf starten

Ersetze `<RARITAN-IP>` durch die IP der DKX2:

```bash
mkdir -p ./logs

docker run --rm \
    --name raritan-diag \
    --network=host \
    -e RARITAN_IP=<RARITAN-IP> \
    -e IDLE_SECONDS=600 \
    -v "$PWD/logs:/logs" \
    raritan-akc-diagnose:latest
```

**Was passiert:**

1. Container prüft Netz-Erreichbarkeit (ping, TCP-Probe auf 443 und 5000)
2. Startet Xvfb (virtual display) + x11vnc auf Port 5900
3. Startet `mono kxgui-patched.exe <RARITAN-IP>` — die gepatchte .NET-App
4. Wartet bis zu 30s auf das AKC-Hauptfenster und protokolliert
5. Bleibt für `IDLE_SECONDS` (default 600s = 10 min) idle, sodass du **per VNC-Client** auf `localhost:5900` die GUI ansehen kannst
6. Schreibt alle Logs nach `./logs/`

`--network=host` ist absichtlich gesetzt: einfachste Variante damit der Container die OT-Subnet-Routen vom Host erbt und die Raritan-IP erreicht.

## Während des Laufs (optional) — GUI ansehen

In einer zweiten Session auf dem OT-Host oder einer Workstation mit Netzzugriff:

```bash
vncviewer <docker-host-ip>:5900     # tigervnc
# oder remmina/jeder VNC-Client
```

Kein Passwort. Erwartetes Bild:

- Wenn alles klappt: AKC-Hauptfenster, Login-Dialog der DKX2 (Username/Password)
- Wenn der Connect fehlschlägt: eine Fehlermeldung im AKC oder ein "Connecting..." das hängen bleibt

Bitte **Screenshot des VNC-Fensters machen** (egal welcher Zustand) und mit den Logs zurückgeben.

## Lauf beenden

Wenn du genug gesehen hast (oder es offensichtlich hängt):

```bash
docker stop raritan-diag
```

Sonst beendet sich der Container nach `IDLE_SECONDS` von alleine.

## Logs für Auswertung zurückbringen

```bash
tar czf raritan-diag-logs-$(date +%Y%m%d-%H%M).tar.gz ./logs
```

Das Tarball plus den Screenshot per USB zurückgeben.

## Was in den Logs zu finden ist

| Datei | Inhalt |
|---|---|
| `diagnose.log` | Top-Level Skript-Output (chronologisch) |
| `ip-addr.txt`, `ip-route.txt` | Container-Netzwerk-Sicht |
| `ping.txt`, `tcp-443.txt`, `tcp-5000.txt` | Erreichbarkeitsprüfungen zur Raritan |
| `mono-stdout.log`, `mono-stderr.log` | Ausgaben von `mono kxgui-patched.exe` |
| `akc-log4net.txt` | Wenn AKC sein eigenes log4net schreibt |
| `windows.txt`, `windows-final.txt` | X11-Fenster-Liste — bestätigt UI-Start |
| `xvfb.log`, `x11vnc.log`, `fluxbox.log` | Display-Stack |

## Wenn was schief geht

- **`mono exit early`**: meist eine fehlende native Library. Stack-Trace in `mono-stderr.log` → bringe genau dieses Log zurück, dann patche ich den nächsten Stub-Eintrag.
- **AKC-Fenster kommt, aber Login schlägt fehl**: dann ist der Connect-Pfad OK, aber Auth-Krypto knirscht — wahrscheinlich `System.Security.Cryptography`-Inkompatibilität, lösbar mit weiteren dllmap-Einträgen.
- **Kein Window, mono-Prozess lebt aber**: Form-Konstruktor hängt — `MONO_LOG_LEVEL=info` Lauf mit `VERBOSE=1` aufrufen:

```bash
docker run --rm --name raritan-diag --network=host \
    -e RARITAN_IP=<IP> -e VERBOSE=1 -e IDLE_SECONDS=120 \
    -v "$PWD/logs:/logs" raritan-akc-diagnose:latest
```

Dann kommt die volle Trace in `mono-stderr.log`.
