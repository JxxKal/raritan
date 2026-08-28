# Raritan DKX2 AKC Docker Wrapper

Verpackt den Raritan Active KVM Client (.NET-App, eigentlich Windows/IE-only) in einem Linux-Container, sodass die DKX2 aus einem normalen Browser (über VNC/Guacamole) bedient werden kann — ohne Java, ohne IE.

**Repo:** https://github.com/JxxKal/raritan (privat)

```bash
git clone https://github.com/JxxKal/raritan.git
cd raritan
cp .env.example .env      # RARITAN_IP und RARITAN_PASS eintragen
docker compose up -d --build
```

Danach `http://<host>:6080/` im Browser öffnen. Die vollständige Anleitung —
Proxy, abgeschottete Hosts, Absicherung, Fehlersuche — steht in
**[DEPLOYMENT.md](DEPLOYMENT.md)**.

Der Stack startet den Java-Client, den die KX2 selbst ausliefert (`rc.jar`), ohne
Browser — siehe [Phase 4](#phase-4-der-client-des-geräts-ohne-browser). Der
ältere Weg über den .NET-Client unter Mono bleibt als Profil erhalten:
`docker compose --profile akc up -d --build`.

> Hinweis: Große Binär-Snapshots (`*.tar`, `*.tar.gz`) sind via `.gitignore` ausgeschlossen und nicht Teil des Repos.

## Status

| Phase | Status |
|---|---|
| 1. AKC-Binär-Analyse, Mono-Pfad validiert | ✅ |
| 2. Diagnose-Container (1 Container, headless test) | ✅ OT-Test erfolgreich — AKC startet & verbindet zur DKX2 |
| 3. Produktiv-Container (Xvfb → x11vnc → noVNC) | ✅ läuft als Compose-Stack, siehe [DEPLOYMENT.md](DEPLOYMENT.md) |
| 4. Java-Client des Geräts statt AKC unter Mono | ✅ Anmeldung und Portwahl stehen; Bild braucht einen CIM |

## Repo-Layout

```
.
├── akc-extracted/        Original ClickOnce-Bundle (entpacktes raritan.zip)
├── app/                  Flachgezogene Binaries — Docker-Build-Input
├── stubs/                Quellen für die drei Mono-Patches
│   ├── SystemDeployment.cs    Stub für System.Deployment.ApplicationDeployment
│   ├── winstub.c              C-Stubs für urlmon/wininet/shell32 P/Invokes
│   └── CecilPatch.cs          IL-Patcher: entfernt Form.set_Icon (libgdiplus-Workaround)
├── bridge/
│   ├── Bridge.cs              Bridge: HTTP-Login, Port-Discovery, AKC-Stack, Control-API
│   └── app/                   AKC-Binaries — Build-Input für Phase 3
├── docker/
│   ├── Dockerfile.phase3      Produktiv-Image (Xvfb/x11vnc/noVNC/Mono)
│   ├── entrypoint-phase3.sh
│   ├── Dockerfile.diagnose    Diagnose-Image
│   ├── entrypoint-diagnose.sh
│   └── OT-TEST-README.md      Anleitung für OT-Host
├── docker-compose.yml         Produktiv-Stack
├── docker-compose.subnet.yml  Override: festes Subnetz, wenn Dockers Pools leer sind
├── .env.example               Konfiguration (DKX2, Ports, Proxy)
├── DEPLOYMENT.md              Installation und Betrieb
├── deploy.sh                  Entwickler-Werkzeug: Arbeitsstand auf einen Test-Host schieben
├── runtime-logs/         Lokale Mono-Test-Logs
├── build-diagnose.sh     Baut Image + exportiert .tar.gz
├── build-phase3.sh       Baut Produktiv-Image + exportiert .tar.gz (Transfer per USB)
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

Siehe **[DEPLOYMENT.md](DEPLOYMENT.md)** — Installation, Proxy, Hosts ohne
Internet, Absicherung und Fehlersuche. Der ältere Diagnose-Lauf ist in
[`docker/OT-TEST-README.md`](docker/OT-TEST-README.md) beschrieben.

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

- Zusätzliche Pakete: `fluxbox`, `x11vnc`, `websockify` + `novnc`, dazu
  Werkzeug zur Fehlersuche (`xdotool`, `xwd`, `xwininfo`)
- Entrypoint startet `Xvfb`, `fluxbox`, `x11vnc` und noVNC, dann die Bridge
- Exponierte Ports: **5900** (VNC), **6080** (noVNC HTTP), **8081** (Control-API)
- Image-Größe 1,33 GB

### Status

Bestätigt gegen eine echte DKX2: das noVNC-Frontend zeigt den vollständigen
AKC-KVM-Viewer (Menüs, Toolbar, Statusleiste), Maus und Tastatur laufen bis zum
Zielrechner durch. Produktiv-Härtung steht: Dockerfile, Entrypoint, Compose-Stack
mit Proxy-Unterstützung und Build-Skript für Hosts ohne Internet.

## Phase 4: Der Client des Geräts, ohne Browser

Die KX2 lädt ihren Client selbst als Applet:

```html
<applet archive="rc.jar, rclang_en.jar" code="nn.pp.rc.RemoteConsoleApplet.class">
  <param name="SESSION_ID" …> <param name="PORT" …> <param name="SSL" value="force">
```

`harness/RcHarness.java` baut die Umgebung nach, die dieses Applet erwartet —
`AppletStub` und `AppletContext` —, meldet sich vorher per HTTP am Gerät an, holt
die Parameter von dessen Seite und startet den Client in einem `JFrame`. `rc.jar`
wird dabei **zur Laufzeit vom Gerät geladen**, nicht mitgeliefert: der Client
passt so immer zur laufenden Firmware.

Damit entfällt der gesamte Mono-Unterbau aus Phase 1–3 — keine `dllmap` auf
`libwinstub.so`, keine Cecil-Patches, kein abgeschaltetes XIM, kein angehobenes
Stack-Limit, keine Reflection auf den `BrowserMediator`.

### Vier Stolpersteine

| Was | Warum |
|---|---|
| Nach `start()` passiert nichts | Im Browser ruft die Seite per JavaScript `connect(isSwitch, fromPort, port, portId, channelName, portType, portPermission)`; erst dessen `notifyAll()` löst `runRemoteConsole()` aus der Sperre. Der Harness übernimmt diese Rolle. |
| `NoSuchMethodError` in `initializeJSObjects()` | OpenJDK 17 liefert `netscape.javascript` mit, aber ohne `getWindow(java.applet.Applet)`. `--limit-modules java.se,jdk.crypto.ec` nimmt `jdk.jsobject` aus dem Modulgraphen, dann greift der Ersatz aus dem Klassenpfad. |
| `NoClassDefFoundError: com/sun/java/browser/dom/DOMUnsupportedException` beim `Class.forName` des Applets | Neuere `rc.jar`-Stände sprechen die Seite auch über die DOM-Brücke des Java-Plugins an. Das Paket steckte allein in `plugin.jar` und fehlt jedem JDK seit 9; die Klasse steht in einer `throws`-Klausel und wird schon beim Laden aufgelöst. `harness/com/sun/java/browser/dom/` ersetzt sie — `getService()` liefert immer eine Brücke, jede Aktion läuft gegen ein leeres Dokument, Fehler werden gemeldet statt geworfen. |
| TLS-Handshake scheitert | Die KX2 spricht nur TLS 1.0 mit `AES256-SHA` und weist sich mit einem selbstsignierten, SHA1-signierten, abgelaufenen Zertifikat aus. `harness/legacy.security` hebt die drei Sperren der JRE auf. |

### Woher der lesbare Quelltext kommt

`rc.jar` vom Gerät ist obfuskiert. Der Multi-Platform Client (`sMpc.jar`) enthält
denselben Code **unobfuskiert** — `nn.pp.rccore.impl.rfb.V01_21/V01_22`,
`RfbAuthenticatorV01_22`, `ImageDecoderLrle`. Also: MPC zum Nachlesen, `rc.jar`
zum Ausführen.

### Portlage: was der Harness vom Gerät liest

`sidebar.asp` trägt die ganze Portlage als JavaScript aus — der Harness liest sie
statt nur die Kennungen herauszufischen:

```
ports.addPortNew(J('PortId','P_000d5d06a393_0'), J('Name','Console 1'),
                 J('PortIndex',0), J('PortNumber',1), J('Type','DCIM'),
                 J('Class','KVM'), J('Status',1), J('StatAvailable',2), …)
```

Die Bedeutung der Zahlen steht im selben Skript (`getPortsSummary`):

| Feld | Werte |
|---|---|
| `Status` | `0` down · `1` up |
| `StatAvailable` | `0` frei · `1` verbunden · `2` belegt · `3` nicht verfügbar |

Daraus wird eine Tabelle im Protokoll, eine Kurzfassung in der Anzeige
(„Ports: 6 frei · 1 belegt · 1 ohne CIM") und ein Hinweis, bevor der
Verbindungsversuch in `[0x10020001]` läuft.

**Der `PortIndex` zählt.** Die Weboberfläche ruft
`connect(0, 0, pindex, portId, pname, ptype, permString)`. Der Harness schickte
dort früher eine feste `"0"` — jeder Versuch ging damit an den ersten Port, egal
welche `PORT_ID` daneben stand.

| Umgebungsvariable | Vorgabe | Wirkung |
|---|---|---|
| `RARITAN_PORT_ID` | — | fester Port; schlägt alles andere |
| `RARITAN_PORT_PICK` | `first` | `free` nimmt stattdessen den ersten freien Port |
| `RARITAN_PORT_TYPE` | `auto` | Typ von der Geräteseite (wie im Browser); fester Wert übersteuert |
| `RARITAN_PORT_PERM` | `CCC` | `auto` rechnet die Rechte wie die Weboberfläche (`getJacPermStringByItem`) |
| `RARITAN_PORT_NAME` | Name vom Gerät | überschreibt den Anzeigenamen |

`first` bleibt die Vorgabe, damit eine laufende Installation nach einem Update
nicht plötzlich auf einem anderen Port landet.

### Maus und Meldungen im Browser

Im KVM-Fenster laufen zwei Mauszeiger auseinander: der echte des Zielrechners
und der, den der Client zeichnet. Dagegen hilft der **Single Cursor Mode**.

Der Client liest sein Verhalten aus den Java-Preferences unter
`/ApplicationSettings` (`ApplicationPreferences.ROOT_NODE`; die Werte kämen
sonst aus `$HOME/ApplicationSettings.xml`, die es im Container nicht gibt):

| Schlüssel | Wirkung |
|---|---|
| `AlwaysOpenSingleMouseMode` | jede Sitzung startet im Single Cursor Mode |
| `singleMouseInstructions` | zeigt die Rückfrage dazu |

`HARNESS_SINGLE_MOUSE=1` setzt beides passend — Modus an, Rückfrage aus.

**Vorsicht in dieser Umgebung.** Der Single Cursor Mode greift Maus *und*
Tastatur exklusiv („this software will have exclusive control over the mouse
and keyboard"). Hinter VNC heißt das: hängt der Grab, kommt man im Browser an
nichts mehr heran — auch nicht ans Menü des Clients. Fluchtwege:
`Strg+LinkeAlt+O` im noVNC, `./deploy.sh ungrab`, oder `HARNESS_SINGLE_MOUSE=0`
und neu starten. Für Windows-Ziele ist **Mouse → Absolute** der ruhigere Weg:
absolute Koordinaten brauchen keinen Grab und kein Nachrechnen der
Mausbeschleunigung.

Tastenkürzel im Client (aus `SourceResources_en.properties`):

| Kürzel | Funktion |
|---|---|
| `Strg+Alt+X` | Single Mouse Cursor an/aus |
| `Strg+LinkeAlt+O` | Single Cursor Mode verlassen |
| `Strg+Alt+S` | Synchronize Mouse |

### Der Dialogwächter — und warum er abräumt

Ein modaler Swing-Dialog parkt den EDT in einer verschachtelten
Ereignisschleife (`Dialog.show` → `WaitDispatchSupport.enter`) und friert damit
die **gesamte** Java-Oberfläche ein. Das sieht von außen tückisch aus: Fenster
lassen sich weiter verschieben — das macht der Fenstermanager, an Java vorbei —
aber *im* Client reagiert nichts mehr, kein Menü, kein Klick. Also auch kein
Weg, den Dialog selbst wegzuklicken.

Deshalb räumt der Wächter ab (`HARNESS_REAP_DIALOGS=always`, Vorgabe) und
schreibt den Text ins Protokoll. Der Versuch, Dialoge während einer Sitzung
stehen zu lassen (`nosession`), war ein Rückschritt: die Rückfrage zum Single
Cursor Mode ließ sich zwar bestätigen, aber jeder andere Dialog konnte die
Oberfläche lahmlegen. Zentrieren und Nach-vorn-holen half nicht, wenn der
Dialog gar nicht erst gezeichnet wird.

| Wert | Verhalten |
|---|---|
| `always` (Vorgabe) | jeden Dialog abräumen, Text ins Protokoll |
| `nosession` | während einer Sitzung stehen lassen — Notausgang: `./deploy.sh dialogs close` |
| `never` | Wächter aus |

Was der Wächter kostet: Dialoge, die man eigentlich bedienen will (Single
Cursor Mode, Video Settings), sind nach spätestens 1,5 s weg. Für den Single
Cursor Mode gibt es deshalb den Weg ohne Dialog — `HARNESS_SINGLE_MOUSE=1`.

Zur Diagnose einer eingefrorenen Oberfläche:

```
./deploy.sh dialogs        # welche Fenster stehen auf :99, was ist aktiv
./deploy.sh dialogs close  # Escape, dann Enter hinterher
./deploy.sh ungrab         # Single Cursor Mode verlassen, X-Grab lösen
```

### Port wechseln

Die Anzeige listet alle KVM-Ports als Knöpfe, eingefärbt nach Zustand:

| Farbe | Bedeutung |
|---|---|
| grün | up und frei |
| gelb | up, aber belegt oder verbunden |
| grau | down — meist kein CIM |

Ein Klick wählt den Port und baut die Sitzung neu auf; der Tooltip zeigt
Kennung, Typ und Status. Bewusst über denselben Weg wie der Start, statt über
`connect(1, …)` umzuschalten — der Umschaltpfad des Clients ist hier nie
erprobt worden, der Neuaufbau schon.

Die Liste frischt sich vor jedem Anlauf auf, ein belegter Port wird also von
selbst wieder grün, sobald ihn jemand freigibt. Wer immer denselben Port will,
setzt weiterhin `RARITAN_PORT_ID`.

### Was am Gerät eingestellt sein muss

Zwei Einstellungen entscheiden darüber, ob der Client überhaupt eine Sitzung
bekommt — beide haben nichts mit dem Container zu tun, sondern mit der Policy
des KX2. Sie sind über die Weboberfläche erreichbar und (Feldnamen aus dem
Formular, Firmware 2.7.0.5.2183) auch per HTTP setzbar:

| Einstellung | Seite | Feld | Wirkung |
|---|---|---|---|
| **PC Share Mode** | `security.asp` → Security Settings | `FV_3_seccryptkvm` (`Private` \| `PC-Share`) | Auf `Private` weist das Gerät jede zweite Sitzung auf einen belegten Port ab — der Client zeigt dann `[0x10020001] : Port sharing on Port … is unavailable`. Auf `PC-Share` steigt man neben der bestehenden Sitzung ein. |
| **VM Share Mode** | `security.asp` | `FV_2_seccryptkvm` | Dasselbe für Virtual Media. |
| **Enable Standard Local Port** | `local_port_settings.asp` → Local Port Settings | `FV_3_localportsettings` | Abschalten trennt die lokale Konsole ganz. Nötig, wenn die lokale Konsole einen Port dauerhaft an sich zieht und PC-Share nicht gewollt ist. |
| **Log Out Idle Users** | `security.asp` | `FV_3_secloglim` / `FV_4_secloglim` (Minuten) | Räumt vergessene Sitzungen von selbst ab. |

`[0x10020001]` ist also **keine** Fehlfunktion des Harness: das Applet läuft, der
Port ist nur belegt und die Policy verbietet das Teilen. Die Meldung stammt aus
einem modalen Dialog des Clients und landet über den Dialogwächter in der
Anzeige.

**PC-Share löst das Problem nur halb.** Ein belegter Port hat zwei getrennte
Folgen, und die zweite sieht aus wie ein Fehler des Containers:

| Policy | Port belegt | Ergebnis |
|---|---|---|
| `Private` | ja | Sitzung wird ganz abgewiesen — `[0x10020001]` |
| `PC-Share` | ja | Sitzung kommt zustande, **aber Tastatur und Maus gehören dem Ersten** |

Im zweiten Fall läuft das Video sauber durch, während Maus *und* Tastatur
gemeinsam wirkungslos bleiben — beide zugleich, nicht eines von beidem. Das ist
die Signatur, an der man es erkennt; ein USB-Profil oder der Mausmodus ändern
daran nichts, die liegen eine Ebene tiefer.

Abhilfe: die andere Sitzung trennen (Port Access → Disconnect) oder die lokale
Konsole abschalten (`FV_3_localportsettings`). Der Harness weist im Protokoll
darauf hin, sobald der gewählte Port als *belegt* oder *verbunden* gemeldet
wird.

### Wie der Client zurückmeldet — und warum die DOM-Brücke zählt

Im Bytecode von `rc.jar` (2.7.0.5.2183) benutzt `RemoteConsoleApplet` genau
zwei Einstiege des Java-Plugins:

```
DOMService.getService:(Ljava/lang/Object;)Lcom/sun/java/browser/dom/DOMService;
DOMService.invokeAndWait:(Lcom/sun/java/browser/dom/DOMAction;)Ljava/lang/Object;
```

Die `DOMAction` (`RemoteConsoleApplet$1`) **ignoriert den DOMAccessor
vollständig** und ruft darin nur `JSObject.call(methode, args)` auf. Die
DOM-Brücke ist also bloß der Threadwechsel für den JavaScript-Aufruf. Deshalb
genügt der Ersatz in `harness/com/sun/java/browser/dom/`: er führt die Aktion
direkt aus, der `JSObject`-Ersatz fängt den Aufruf, und jede Rückmeldung des
Clients steht im Protokoll — ohne dass es je ein echtes Dokument bräuchte.
