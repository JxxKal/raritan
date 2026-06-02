# Raritan DKX2 AKC Docker Wrapper

Verpackt den Raritan Active KVM Client (.NET-App, eigentlich Windows/IE-only) in einem Linux-Container, sodass die DKX2 aus einem normalen Browser (über VNC/Guacamole) bedient werden kann — ohne Java, ohne IE.

**Repo:** https://github.com/JxxKal/raritan (privat)

```bash
git clone https://github.com/JxxKal/raritan.git
```

> Hinweis: Große Binär-Snapshots (`*.tar`, `*.tar.gz`) sind via `.gitignore` ausgeschlossen und nicht Teil des Repos.

## Status

| Phase | Status |
|---|---|
| 1. AKC-Binär-Analyse, Mono-Pfad validiert | ✅ |
| 2. Diagnose-Container (1 Container, headless test) | ✅ OT-Test erfolgreich — AKC startet & verbindet zur DKX2 |
| 3. Produktiv-Container mit Guacamole | 📋 geplant |

## Repo-Layout

```
.
├── akc-extracted/        Original ClickOnce-Bundle (entpacktes raritan.zip)
├── app/                  Flachgezogene Binaries — Docker-Build-Input
├── stubs/                Quellen für die drei Mono-Patches
│   ├── SystemDeployment.cs    Stub für System.Deployment.ApplicationDeployment
│   ├── winstub.c              C-Stubs für urlmon/wininet/shell32 P/Invokes
│   └── CecilPatch.cs          IL-Patcher: entfernt Form.set_Icon (libgdiplus-Workaround)
├── docker/
│   ├── Dockerfile.diagnose    Diagnose-Image
│   ├── entrypoint-diagnose.sh
│   └── OT-TEST-README.md      Anleitung für OT-Host
├── runtime-logs/         Lokale Mono-Test-Logs
├── build-diagnose.sh     Baut Image + exportiert .tar.gz
└── .dockerignore
```

## Hintergrund: Warum funktioniert das überhaupt?

Der AKC (`kxgui.exe`) ist eine WinForms-.NET-4.0-Anwendung, **kein WPF** — und Mono unterstützt WinForms auf Linux. Drei spezifische Hürden mussten gepatcht werden:

1. **`System.Deployment.Application.ApplicationDeployment`** fehlt in Monos Stock-DLL → eigener Stub liefert die paar Properties die die App liest.
2. **`urlmon.dll`/`wininet.dll`/`shell32.dll`** P/Invokes im Form-Konstruktor → leere C-Stubs via Mono `dllmap`.
3. **`Form.set_Icon`** triggert `libgdiplus` `OutOfMemoryException` → die zwei Setter-Calls per Mono.Cecil aus dem IL ausgepatcht.

Ergebnis: `mono kxgui-patched.exe <raritan-ip>` startet das original Raritan-Hauptfenster unter X11. Verifiziert mit Mono 6.12 auf Debian 13.

## Build (auf einem Rechner mit Docker)

```bash
./build-diagnose.sh
```

Erzeugt:
- Docker-Image `raritan-akc-diagnose:latest` lokal
- `raritan-akc-diagnose.tar.gz` zum USB-Transfer

## Deployment in OT

Siehe [`docker/OT-TEST-README.md`](docker/OT-TEST-README.md).

## OT-Test: Ergebnis

Der Diagnoselauf gegen die echte DKX2 war erfolgreich — der AKC startet unter Mono, verbindet sich und loggt sich ein. Ein weiterer Diagnoselauf ist nicht mehr nötig.

Nächster Schritt ist Phase 3: Produktiv-Container (AKC im Xvfb gerendert, per x11vnc/noVNC im Browser bedienbar).

## Phase 3: Produktiv-Container (Xvfb → x11vnc → noVNC)

Statt Frames per Reflection aus `rccore` zu ziehen, läuft der AKC seinen **normalen UI-Code-Pfad** in einem virtuellen X-Display (Xvfb). Das gerenderte Display wird per `x11vnc` als VNC exportiert und über `websockify`/`noVNC` im Browser zugänglich gemacht — kein Java, kein IE, kein Client-Plugin.

### Architektur

```
                          Container
┌─────────────────────────────────────────────────────────────┐
│                                                               │
│  Browser ─WS─▶ noVNC / websockify ─VNC─▶ x11vnc               │
│   (6080)                          (5900)    │                 │
│                                             │ grab            │
│                                             ▼                 │
│                                          Xvfb :99             │
│                                             ▲                 │
│                                             │ render          │
│                                       AKC (kxgui-patched.exe) │
│                                          in Mono              │
│                                             │                 │
│                                  HTTP-Login + Init/Connect    │
│                                             │                 │
└─────────────────────────────────────────────┼───────────────┘
                                               ▼
                                         DKX2 KVM-IP
```

### Ablauf (Bridge v16)

1. **HTTP-Login** → `POST /auth.asp?client=dotnet` liefert `pp_session_id`
2. **AKC-Stack** via Reflection konstruieren (`apm`, `dpm`, `fav`, `t`, `bm`)
3. **`Form.Show()`** macht das Fenster im Xvfb sichtbar (Paint-Events feuern)
4. **Init/Connect** auf dem `BrowserMediator` (Session-Token, `portId`, `portType=VM`, `portPermission=CCC`)
5. **`Application.Run(t)`** blockt im UI-Thread — AKC öffnet den KVM-Viewer als zweite Form und malt die Frames selbst
6. **`x11vnc`** exportiert `Xvfb :99` als VNC auf Port 5900
7. **`websockify`/`noVNC`** stellt den Stream als Browser-Frontend auf Port 6080 bereit

### Cecil-Patches in `kxgui-patched.exe`

Aufbauend auf dem `set_Icon`-Patch aus Phase 1/2:

| Methode | Patch | Grund |
|---|---|---|
| `Form.set_Icon` | `pop;pop` | libgdiplus `OutOfMemoryException` |
| `WebBrowser.Navigate(string)` | `pop;pop` | kein Web-Login im AKC — läuft extern via HTTP |
| `WebBrowser.set_ObjectForScripting(object)` | `pop;pop` | keine COM-Bridge unter Mono |

### Container-Änderungen ggü. Diagnose-Image

- Zusätzliche Pakete: `x11vnc`, optional `websockify` + `novnc`
- Entrypoint startet `Xvfb` + `x11vnc` (+ optional noVNC), dann die Bridge
- Exponierte Ports: **5900** (VNC) und **6080** (noVNC HTTP)
- Image-Größe ~265 MB (statt ~190 MB Diagnose)

### Status

Architektur in der Entwicklung mit Bridge v16 bestätigt: noVNC-Frontend zeigt den vollständigen AKC-KVM-Viewer (Menüs, Toolbar, Statusleiste) gegen eine echte DKX2. Offen ist die Produktiv-Härtung (Dockerfile, Entrypoint, Build-Skript, Multi-Port-Handling).
