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

Nächster Schritt ist Phase 3: Produktiv-Container (AKC im Xvfb gerendert, per x11vnc/Guacamole im Browser bedienbar).
