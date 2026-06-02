# Raritan DKX2 AKC Docker Wrapper

Verpackt den Raritan Active KVM Client (.NET-App, eigentlich Windows/IE-only) in einem Linux-Container, sodass die DKX2 aus einem normalen Browser (über VNC/Guacamole) bedient werden kann — ohne Java, ohne IE.

## Status

| Phase | Status |
|---|---|
| 1. AKC-Binär-Analyse, Mono-Pfad validiert | ✅ |
| 2. Diagnose-Container (1 Container, headless test) | 🚧 gebaut, OT-Test ausstehend |
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

## Was wir gleichzeitig vermissen werden im OT-Test

Bisher mit Dummy-IPs nur das UI-Aufkommen verifiziert. Beim echten Connect zur DKX2 könnten noch P/Invokes triggert die wir nicht stubben:
- TLS/Crypto-Pfade in `System.Security.Cryptography.X509Certificates` → Mono macht das eigentlich richtig, aber Raritan-Server-Cert ist evtl. self-signed
- WebBrowser-Control im EXE (für eingebettete Hilfe/UI-Teile) → Mono unterstützt das nur via xulrunner/webkit, oft brüchig. Falls relevant: weiterer Patch nötig.
- Performance-Counter-Calls (`QueryPerformanceCounter`) — sollten gehen, aber gut zu wissen

Der Diagnose-Container schreibt detaillierte Logs — die zeigen, was vom OT-Lauf zurück muss um die nächste Iteration zu bauen.
