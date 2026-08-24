// Bridge v17: hostet kxgui.exe als ganzes via Reflection + Port-Discovery + HTTP Control API.
// Komponenten:
//   1. HTTP-Login an /auth.asp?client=dotnet -> pp_session_id
//   2. AKC-Stack im selben Prozess konstruieren: ApplicationPreferencesManager,
//      DevicePreferencesManager, FavoriteDevices.a, KxGui.t (Form ohne Show()),
//      BrowserMediator
//   3. BrowserMediator.Init(xml) mit SESSION_ID = pp_session_id
//   4. BrowserMediator.Connect(0, "0", "0", portId, name, type, perm)
//   5. Polling - AKC-internes y::a startet rccore mit korrektem Auth
//
// Voraussetzungen (im Container):
//   - kxgui-patched.exe (Cecil-patched für Form.Icon)
//   - resources/ Verzeichnis
//   - DISPLAY=:99 von Xvfb gesetzt
//   - WinINet-DllMap auf libwinstub.so

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Security;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;
using log4net;
using log4net.Config;

class PortInfo
{
    public int Pindex;
    public string PortId;       // z.B. "P_000d5d06a393_0"
    public string Name;         // z.B. "Dominion-KX2_Port1"
    public string Ptype;        // z.B. "VM" (Type-Feld)
    public string PortType;     // z.B. "VM" (PortType-Feld)
    public string Pclass;       // z.B. "KVM"
    public int Status;          // 1=available
    public string PermString;   // "CCC" usw.
}

class Bridge
{
    const string AKC_UA = "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 6.2; Win64; x64; Trident/7.0; .NET4.0C; .NET4.0E; Tablet PC 2.0); RaritanAKC";

    static ILog log;
    static Assembly kxgui;
    static object bm;                       // current BrowserMediator
    static Form mainForm;                   // KxGui.t instance
    static PortInfo currentPort;            // currently connected port
    static List<PortInfo> ports = new List<PortInfo>();

    static int Main(string[] args)
    {
        BasicConfigurator.Configure();
        log = LogManager.GetLogger(typeof(Bridge));
        log.Info("=== Bridge v27 starting (/debug/mousemode + /debug/geo; auto video-scaling; keyboard) ===");

        // UI-Thread-Exceptions abfangen, statt den Prozess zu beenden. Wird der KVM-Viewer
        // (KxGui.s) per Menü geschlossen, wirft die RcCore-Render-Dispose-Kaskade
        // (Render.g.d -> ... -> Component.Dispose) eine Exception; ohne Handler propagiert
        // die aus Application.Run() heraus und der Container stirbt mit exit 1.
        // Muss vor dem ersten Fenster-Handle gesetzt werden.
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (s, e) =>
            log.Error("UI-thread exception (swallowed, bridge stays up): " + e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (s, e) =>
            log.Error("AppDomain unhandled exception: " + (e.ExceptionObject as Exception));

        string host = args.Length > 0 ? args[0] : null;
        int port    = args.Length > 1 ? int.Parse(args[1]) : 443;
        string user = args.Length > 2 ? args[2] : "admin";
        string pass = args.Length > 3 ? args[3] : "raritan";
        // Port-Info kommt jetzt aus discovery (siehe unten nach Login)

        if (host == null) {
            log.Error("usage: Bridge.exe <host> [port] [user] [pass] [portId]");
            return 2;
        }

        ServicePointManager.ServerCertificateValidationCallback = (a, b, c, d) => true;
        ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls
                                             | SecurityProtocolType.Tls11
                                             | SecurityProtocolType.Tls12;

        // === Phase 1: HTTP-Login ===
        var jar = new CookieContainer();
        string sessionToken = Login(host, port, user, pass, jar);
        if (sessionToken == null) {
            log.Error("Login failed");
            return 1;
        }
        log.Info($"Got pp_session_id ({sessionToken.Length} chars)");

        // === Phase 1b: Port-Discovery via /sidebar.asp ===
        ports = FetchPortList(host, port, jar);
        log.Info($"Discovered {ports.Count} ports:");
        foreach (var p in ports) {
            log.Info($"  pindex={p.Pindex} portId={p.PortId} name={p.Name} class={p.Pclass} type={p.Ptype} status={p.Status}");
        }
        // Wähle voreingestellten Port oder via RARITAN_PORT_ID Override
        string envPortId = Environment.GetEnvironmentVariable("RARITAN_PORT_ID");
        if (!string.IsNullOrEmpty(envPortId)) {
            currentPort = ports.FirstOrDefault(p => p.PortId == envPortId) ?? new PortInfo {
                Pindex = 0, PortId = envPortId,
                Name = Environment.GetEnvironmentVariable("RARITAN_PORT_NAME") ?? "KVM Port",
                Ptype = Environment.GetEnvironmentVariable("RARITAN_PORT_TYPE") ?? "VM",
                PermString = Environment.GetEnvironmentVariable("RARITAN_PORT_PERM") ?? "CCC",
                Pclass = "KVM",
            };
        } else {
            // Nur echte Zielports kommen in Frage. Die Discovery liefert daneben
            // Pseudo-Einträge mit Pclass=KVM, die kein Ziel sind — auf einer KX2
            // etwa "000d5d057eed_FG_0" (Ptype=FG). Ein Connect darauf endet nicht
            // mit einem Fehler, sondern in einem endlos rekursiven Teardown von
            // Render.g.d, der das Protokoll volllaufen lässt. Echte Zielports
            // tragen die Kennung "P_<mac>_<n>".
            var targets = ports.Where(p => p.Pclass == "KVM" && p.PortId.StartsWith("P_")).ToList();
            currentPort = targets.FirstOrDefault(p => p.Status == 1 && p.Ptype != "Not Available");
            if (currentPort == null) {
                // Ohne angeschlossenen CIM meldet die KX2 jeden Port als
                // "Not Available". Blind zu verbinden führt in denselben
                // Teardown-Rausch — deshalb hier abbrechen statt es zu versuchen.
                log.Error($"Kein belegter KVM-Port: {targets.Count} Zielport(s) gefunden, "
                        + "aber keiner meldet Status=1. Ist ein CIM angeschlossen?");
                foreach (var p in targets)
                    log.Error($"  {p.PortId} ({p.Name}) type={p.Ptype} status={p.Status}");
                log.Error("Mit RARITAN_PORT_ID lässt sich ein Port trotzdem erzwingen.");
                return 1;
            }
        }
        if (currentPort == null) {
            log.Error("No KVM port found in discovery; aborting");
            return 1;
        }
        log.Info($"Initial port: pindex={currentPort.Pindex} portId={currentPort.PortId} ({currentPort.Name})");

        // === Phase 2: AKC-Stack über Reflection ===
        kxgui = Assembly.LoadFrom("kxgui-patched.exe");
        log.Info($"Loaded {kxgui.FullName}");

        var apm = NewInst("Com.Raritan.KxGui.Preferences.ApplicationPreferencesManager");
        var dpm = NewInst("Com.Raritan.KxGui.Preferences.DevicePreferencesManager", host);
        var fav = NewInst("Com.Raritan.KxGui.FavoriteDevices.a", apm);
        var formT = NewInst("Com.Raritan.KxGui.t", host, host, dpm, apm);  // Form, instantiated but never Show()
        mainForm = (Form)formT;
        bm = NewInst("Com.Raritan.KxGui.BrowserMediator", null, fav, formT, apm, dpm);
        log.Info($"AKC stack ready: BrowserMediator={bm.GetType().FullName}");

        // === Phase 3: Init(xml) ===
        string xml =
            $"<param name=\"SESSION_ID\" value=\"{sessionToken}\"/>" +
            $"<param name=\"PORT\" value=\"{port}\"/>" +
            $"<param name=\"SSLPORT\" value=\"{port}\"/>" +
            $"<param name=\"SSL\" value=\"force\"/>" +
            $"<param name=\"FIPS\" value=\"0\"/>" +
            $"<param name=\"BOARD_TYPE\" value=\"lara\"/>" +
            $"<param name=\"PRODUCT_TYPE\" value=\"kx2\"/>" +
            $"<param name=\"HW_ID\" value=\"5E\"/>" +
            $"<param name=\"PORT_ID\" value=\"the_kvm_port\"/>" +
            $"<param name=\"LANGUAGE\" value=\"en\"/>";
        log.Info("Calling BrowserMediator.Init(xml)");
        bm.GetType().GetMethod("Init").Invoke(bm, new object[] { xml });

        // === Phase 4: Connect(0, ...) — initial connect zum gewählten Port ===
        log.Info($"Initial Connect: portId={currentPort.PortId} ({currentPort.Name})");
        DoConnect(0, "0", currentPort.Pindex.ToString(), currentPort);

        // === Phase 5: Form sichtbar machen + Control-API + UI-Loop ===
        log.Info($"Showing form {mainForm.GetType().FullName} on DISPLAY={Environment.GetEnvironmentVariable("DISPLAY")}");
        mainForm.Show();
        mainForm.WindowState = FormWindowState.Maximized;

        // KVM-Viewer (KxGui.s) öffnet sich auf 410x320 Default-Größe — Mitte vom 1920x1080-Display.
        // Timer im UI-Thread maximiert alle Forms die AKC im Lauf öffnet (KxGui.s erscheint
        // nach Connect; auch evtl. neue Forms nach Port-Switch).
        // FormClosing-Guard: verhindert, dass ein versehentlicher Menü-Klick per noVNC
        // den KVM-Viewer (oder mainForm) schließt und damit die RcCore-Dispose-Kaskade
        // auslöst. Prozess-Shutdown läuft über SIGTERM (docker stop), nicht über Close().
        // Nur das Hauptfenster und den KVM-Viewer (KxGui.s) gegen Close schützen.
        // AKC-Dialoge (z.B. KxGui.Dialogs.*) MÜSSEN normal schließbar bleiben — viele
        // sind modal (ShowDialog) und würden sonst den UI-Thread dauerhaft blockieren.
        FormClosingEventHandler closeGuard = (cs, ce) => {
            var f = (Form)cs;
            bool isViewer = ReferenceEquals(f, mainForm)
                         || f.GetType().FullName == "Com.Raritan.KxGui.s";
            if (!isViewer) return;
            ce.Cancel = true;
            log.Warn($"blocked Form.Close() on {f.GetType().FullName} (noVNC menu click?)");
        };

        // Jede Form nur EINMAL einrichten. KEIN Dauer-Gezerre an Fokus/Z-Order mehr:
        // die Tastatur läuft jetzt über den IMessageFilter, unabhängig vom Control-Fokus.
        // Früheres per-Tick Maximize/SendToBack/TopMost/Activate hat offene Menüs sofort wieder
        // geschlossen (Doppelklick nötig, Menü ging gleich wieder zu) und Klicks unzuverlässig
        // gemacht. Hauptfenster nach hinten, Viewer maximiert nach vorne — einmalig.
        var setupForms = new System.Collections.Generic.HashSet<Form>(new RefEq());
        bool viewerScaled = false;
        var setupTimer = new System.Windows.Forms.Timer { Interval = 500 };
        setupTimer.Tick += (s, e) => {
            if (keyboardD == null) WireConsoleKeyboard();   // retry bis Keyboard.d gefunden

            // Video-Scaling automatisch einschalten (KxGui.s.i(true) = "Scale Video"-Menüaktion):
            // skaliert das Target-Bild auf die Fenstergröße -> kein Cut-off/schwarzer Balken, und
            // Maus-Koordinaten werden 1:1 gemappt. Retry bis erfolgreich (l ist evtl. erst nach
            // Connect bereit). Läuft auf dem UI-Thread (Timer) -> kein cross-thread Invalidate.
            if (!viewerScaled) {
                foreach (Form f in Application.OpenForms) {
                    if (f.GetType().FullName != "Com.Raritan.KxGui.s") continue;
                    try {
                        var mi = f.GetType().GetMethod("i", BindingFlags.Instance | BindingFlags.NonPublic | BindingFlags.Public,
                                                       null, new[] { typeof(bool) }, null);
                        if (mi != null) { mi.Invoke(f, new object[] { true }); viewerScaled = true; log.Info("auto-scaling aktiviert (KxGui.s.i(true))"); }
                    } catch { /* l noch nicht bereit -> nächster Tick */ }
                    break;
                }
            }

            foreach (Form f in Application.OpenForms) {
                if (!setupForms.Add(f)) continue;           // schon eingerichtet -> nichts tun
                try { f.FormClosing -= closeGuard; f.FormClosing += closeGuard; } catch { }
                if (ReferenceEquals(f, mainForm)) {
                    // KxGui.t ist das leere AKC-Hauptfenster — wir brauchen es nur, damit
                    // Application.Run() läuft (Port-Switch geht über unsere Web-UI). Ausblenden,
                    // damit kein zweites leeres Fenster im WM hängt. Hide() schließt NICHT -> Loop
                    // läuft weiter, Shutdown via SIGTERM.
                    try { f.ShowInTaskbar = false; } catch { }
                    try { f.Hide(); } catch { }
                    log.Info("KxGui.t (leeres Hauptfenster) ausgeblendet");
                } else if (f.GetType().FullName == "Com.Raritan.KxGui.s") {
                    try { f.WindowState = FormWindowState.Maximized; } catch { }
                    try { f.BringToFront(); } catch { }
                    log.Info("KVM viewer eingerichtet (maximiert, vorne); Tastatur via IMessageFilter");
                }
                // AKC-Dialoge & andere Forms NICHT in Größe/Z-Order/Fokus anfassen -> bedienbar.
            }
        };
        setupTimer.Start();

        // HTTP-Control-API auf Port 8081 in separatem Thread
        StartControlApi();

        log.Info("=== starting Application.Run() — UI thread takes over ===");
        Application.Run(mainForm);
        log.Info("=== Application.Run returned — form closed ===");

        log.Info("=== done ===");
        return 0;
    }

    static object NewInst(string typeName, params object[] ctorArgs)
    {
        var t = kxgui.GetType(typeName) ?? throw new Exception($"type not found: {typeName}");
        try {
            var obj = (ctorArgs != null && ctorArgs.Length > 0)
                ? Activator.CreateInstance(t,
                    BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic,
                    null, ctorArgs, null)
                : Activator.CreateInstance(t,
                    BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic,
                    null, null, null);
            log.Info($"  ctor({typeName}) -> ok");
            return obj;
        } catch (Exception ex) {
            var e = ex.GetBaseException();
            log.Error($"  ctor({typeName}) FAILED: {e.GetType().Name}: {e.Message}");
            throw;
        }
    }

    // Walk reflection durch das Object-Graph von BrowserMediator nach rccore.l.
    // rccore.l ist Interface Com.Raritan.RcCore.l. Wir suchen jedes Objekt das das implementiert.
    // Außerdem rufen wir parameterlose Methoden auf, die Com.Raritan-Typen zurückgeben,
    // weil rccore.l auch via Getter wie KxGui.s::r() zurückgegeben werden kann.
    static object FindRccoreL(object root)
    {
        if (root == null) return null;
        var visited = new System.Collections.Generic.HashSet<object>(new RefEq());
        var queue = new System.Collections.Generic.Queue<object>();
        queue.Enqueue(root);

        // Zusätzliche Roots: ALLE statischen Felder im kxgui-Assembly. KxGui.s wird in
        // einer static List<KxGui.s> auf Com.Raritan.KxGui.m gespeichert, das ist via
        // BrowserMediator-Object-Graph nicht erreichbar.
        try {
            foreach (var t in kxgui.GetTypes()) {
                foreach (var f in t.GetFields(BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic)) {
                    if (f.FieldType.IsValueType || f.FieldType == typeof(string)) continue;
                    try {
                        var v = f.GetValue(null);
                        if (v != null) queue.Enqueue(v);
                    } catch { }
                }
            }
            log.Info($"FindRccoreL: seeded {queue.Count} roots (BrowserMediator + KxGui static fields)");
        } catch (Exception ex) {
            log.Warn($"static-field enumeration failed: {ex.Message}");
        }
        int hops = 0;
        while (queue.Count > 0 && hops < 20000) {
            var cur = queue.Dequeue();
            hops++;
            if (cur == null || visited.Contains(cur)) continue;
            visited.Add(cur);
            var t = cur.GetType();
            if (IsRccoreL(t)) {
                log.Info($"FindRccoreL: matched after {hops} hops, type={t.FullName}");
                return cur;
            }
            // Visit instance fields. Filter based on VALUE type, not declared field type:
            // a field declared as System.Windows.Forms.Form might hold a Com.Raritan.KxGui.s instance!
            foreach (var f in t.GetFields(BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)) {
                if (f.FieldType.IsValueType || f.FieldType == typeof(string)) continue;
                try {
                    var v = f.GetValue(cur);
                    if (v == null) continue;
                    var vt = v.GetType();
                    if (vt == typeof(string) || vt.IsValueType) continue;
                    if (vt.FullName?.StartsWith("System.Reflection.") == true) continue;
                    if (vt.FullName?.StartsWith("log4net.") == true) continue;
                    // Skip large standard containers but allow KxGui-stuff via collections too
                    if (IsRccoreL(vt)) {
                        log.Info($"FindRccoreL: matched via field {t.Name}::{f.Name} after {hops} hops, type={vt.FullName}");
                        return v;
                    }
                    queue.Enqueue(v);
                } catch { }
            }
            // Static fields of this type
            foreach (var f in t.GetFields(BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic)) {
                if (f.FieldType.IsValueType || f.FieldType == typeof(string)) continue;
                try {
                    var v = f.GetValue(null);
                    if (v == null) continue;
                    var vt = v.GetType();
                    if (vt == typeof(string) || vt.IsValueType) continue;
                    if (IsRccoreL(vt)) {
                        log.Info($"FindRccoreL: matched via STATIC field {t.Name}::{f.Name} after {hops} hops, type={vt.FullName}");
                        return v;
                    }
                    queue.Enqueue(v);
                } catch { }
            }
            // Parameter-less methods returning non-System non-void (rccore.l might be via KxGui.s::r())
            foreach (var m in t.GetMethods(BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.DeclaredOnly)) {
                if (m.GetParameters().Length != 0) continue;
                if (m.ReturnType == typeof(void) || m.ReturnType.IsValueType) continue;
                if (m.ReturnType == typeof(string)) continue;
                if (m.IsSpecialName) continue;
                if (m.ReturnType.FullName?.StartsWith("System.") == true) continue;
                if (m.ReturnType.FullName?.StartsWith("log4net.") == true) continue;
                try {
                    var v = m.Invoke(cur, null);
                    if (v != null) {
                        if (IsRccoreL(v.GetType())) {
                            log.Info($"FindRccoreL: matched via method {t.Name}::{m.Name}() after {hops} hops, type={v.GetType().FullName}");
                            return v;
                        }
                        queue.Enqueue(v);
                    }
                } catch { }
            }
            // Walk through IEnumerable/Collections. Wichtig: Dictionary liefert
            // KeyValuePair<TKey,TValue> structs - das sind Value-Types, mein Filter würde
            // sie skippen. Stattdessen: für Dictionaries extra die Values walken.
            if (cur is System.Collections.IDictionary dict) {
                if (cur.GetType().Name.StartsWith("Dictionary") && dict.Count > 0) {
                    log.Info($"  visiting Dictionary {t.FullName} with {dict.Count} entries");
                }
                try {
                    foreach (var v in dict.Values) {
                        if (v == null) continue;
                        var vt = v.GetType();
                        if (vt == typeof(string) || vt.IsValueType) continue;
                        if (IsRccoreL(vt)) {
                            log.Info($"FindRccoreL: matched via Dictionary value of {t.Name} after {hops} hops, type={vt.FullName}");
                            return v;
                        }
                        queue.Enqueue(v);
                    }
                } catch { }
            } else if (cur is System.Collections.IEnumerable enumerable && !(cur is string)) {
                if (cur is System.Collections.ICollection coll && coll.Count > 0
                    && (t.FullName?.Contains("List") == true || t.FullName?.Contains("Collection") == true)) {
                    log.Info($"  visiting Collection {t.FullName} with {coll.Count} items");
                }
                try {
                    foreach (var item in enumerable) {
                        if (item == null) continue;
                        var it = item.GetType();
                        if (it == typeof(string)) continue;
                        // KeyValuePair: handle explicitly
                        if (it.IsValueType) {
                            if (it.IsGenericType && it.GetGenericTypeDefinition().FullName == "System.Collections.Generic.KeyValuePair`2") {
                                var vProp = it.GetProperty("Value");
                                if (vProp != null) {
                                    var v = vProp.GetValue(item);
                                    if (v != null && !v.GetType().IsValueType && v.GetType() != typeof(string)) {
                                        if (IsRccoreL(v.GetType())) {
                                            log.Info($"FindRccoreL: matched via KVP value of {t.Name} after {hops} hops, type={v.GetType().FullName}");
                                            return v;
                                        }
                                        queue.Enqueue(v);
                                    }
                                }
                            }
                            continue;  // skip other value types
                        }
                        if (IsRccoreL(it)) {
                            log.Info($"FindRccoreL: matched via collection item of {t.Name} after {hops} hops, type={it.FullName}");
                            return item;
                        }
                        queue.Enqueue(item);
                    }
                } catch { }
            }
        }
        log.Info($"FindRccoreL: visited {visited.Count} objects in {hops} hops, no match. Dumping reachable types:");
        var typeCounts = new System.Collections.Generic.Dictionary<string, int>();
        foreach (var o in visited) {
            var n = o.GetType().FullName ?? "<null>";
            if (!typeCounts.ContainsKey(n)) typeCounts[n] = 0;
            typeCounts[n]++;
        }
        foreach (var kv in typeCounts.OrderByDescending(x => x.Value).Take(40)) {
            log.Info($"  {kv.Value,4}x {kv.Key}");
        }
        return null;
    }

    static bool IsRccoreL(Type t)
    {
        if (t.FullName == "Com.Raritan.RcCore.l") return true;
        foreach (var iface in t.GetInterfaces()) {
            if (iface.FullName == "Com.Raritan.RcCore.l") return true;
        }
        return false;
    }

    class RefEq : System.Collections.Generic.IEqualityComparer<object> {
        public new bool Equals(object x, object y) => object.ReferenceEquals(x, y);
        public int GetHashCode(object o) => System.Runtime.CompilerServices.RuntimeHelpers.GetHashCode(o);
    }

    // Ruft BrowserMediator.Connect mit Marshalling auf den UI-Thread auf
    static void DoConnect(int isSwitch, string fromPort, string toPort, PortInfo p)
    {
        var args = new object[] {
            isSwitch, fromPort, toPort,
            p.PortId, p.Name, p.Ptype ?? "VM", p.PermString ?? "CCC"
        };
        log.Info($"Connect({isSwitch}, \"{fromPort}\", \"{toPort}\", \"{p.PortId}\", \"{p.Name}\", \"{p.Ptype}\", \"{p.PermString}\")");
        Action call = () => bm.GetType().GetMethod("Connect").Invoke(bm, args);
        if (mainForm != null && mainForm.InvokeRequired) {
            mainForm.Invoke(call);
        } else {
            call();
        }
        currentPort = p;
    }

    // ── Konsolen-Tastatur-Fokus ──────────────────────────────────────────────
    // Schon verdrahtete Viewer-Fenster (verhindert Mehrfach-Anhängen des Activated-Handlers).
    static readonly System.Collections.Generic.HashSet<Form> wiredViewers =
        new System.Collections.Generic.HashSet<Form>(new RefEq());
    // Viewer, bei denen Focus() schon einmal erfolgreich war — dann nur noch per Activated-Handler
    // re-fokussieren, statt jeden Tick erneut Activate() zu rufen (würde Menü-Dropdowns stören).
    static readonly System.Collections.Generic.HashSet<Form> focusDone =
        new System.Collections.Generic.HashSet<Form>(new RefEq());

    // Sucht rekursiv das Render-Control (Com.Raritan.RcCore.Impl.Render.*) im Control-Baum.
    // Das ist das WinForms-Control, das das Remote-Bild zeichnet UND die KeyDown/KeyUp-Events
    // feuert, die die Keyboard-Impl als RFB an den Zielrechner weiterreicht.
    static Control FindRenderControl(Control root)
    {
        if (root == null) return null;
        var fn = root.GetType().FullName;
        if (fn != null && fn.StartsWith("Com.Raritan.RcCore.Impl.Render"))
            return root;
        foreach (Control c in root.Controls) {
            var found = FindRenderControl(c);
            if (found != null) return found;
        }
        return null;
    }

    static string lastFocusLog = "";

    // Holt das Viewer-Fenster in den Vordergrund und setzt den Tastatur-Fokus auf das
    // Render-Control. Liefert true, wenn das Control danach den Fokus hat (ContainsFocus).
    static bool EnsureConsoleFocus(Form viewer)
    {
        if (viewer == null) return false;
        var rc = FindRenderControl(viewer);
        if (rc == null) {
            if (lastFocusLog != "norc") { log.Warn("EnsureConsoleFocus: kein Render-Control gefunden (noch nicht verbunden?)"); lastFocusLog = "norc"; }
            return false;
        }
        try {
            // Viewer über das Hauptfenster heben und aktivieren, sonst kann das innere
            // Control keinen Focus annehmen (Focus() liefert dann false).
            if (!viewer.TopMost) { try { viewer.TopMost = true; } catch { } }
            viewer.BringToFront();
            viewer.Activate();
            try { viewer.ActiveControl = rc; } catch { }
            rc.Select();
            bool ok = rc.Focus();

            // Nur bei Zustandswechsel loggen (sonst 1000 identische Zeilen).
            var af = Form.ActiveForm;
            string line = $"Focus()={ok} Focused={rc.Focused} Contains={rc.ContainsFocus} CanFocus={rc.CanFocus} "
                        + $"ActiveForm={(af == null ? "<null>" : af.GetType().FullName)} "
                        + $"owner={(viewer.Owner == null ? "<none>" : viewer.Owner.GetType().FullName)}";
            if (line != lastFocusLog) {
                log.Info($"console focus -> {rc.GetType().FullName}: {line}");
                lastFocusLog = line;
            }
            return rc.Focused || rc.ContainsFocus;
        } catch (Exception ex) {
            log.Warn($"EnsureConsoleFocus failed: {ex.Message}");
            return false;
        }
    }

    // ── Konsolen-Tastatur via IMessageFilter ─────────────────────────────────
    // AKCs Keyboard läuft normalerweise über einen Windows-Low-Level-Hook
    // (SetWindowsHookEx, user32.dll) — unter Mono/Linux tot. Die WinForms-KeyDown/Up-Handler
    // setzen nur e.Handled, senden nichts. Wir greifen die Tasten daher selbst per
    // IMessageFilter aus dem Mono-Message-Loop ab und speisen sie in AKCs eigene
    // Übersetzung+Sink: Keyboard.b.a(Keys) -> fertiger Device-Scancode (mit e0/e1-Handling),
    // dann Keyboard.d.c(bool down, int sc) -> RFB-KeyEvent an den Zielrechner.
    static object keyboardD;        // Com.Raritan.RcCore.Impl.Keyboard.d (im Objektgraph gesucht)
    // DER funktionierende Sende-Pfad (live verifiziert): a(bool down, Keys key, int hwScanCode, bool ext).
    // AKC übersetzt hwScanCode(+ext) intern via Keyboard.g.a -> Device-Scancode -> RFB-Sink. Die Keys-Enum
    // ist (außer PrintScreen) nur kosmetisch. Keyboard.b.a(Keys) war FALSCH (mappt Buchstaben nicht -> -1).
    static MethodInfo mSendReal;    // instance Keyboard.d.a(bool, Keys, int, bool)
    static bool keyFilterInstalled;
    static bool kbdWireWarned;

    // vk(Keys) -> Windows-Hardware-Scancode (Set 1, "make"-Code). Das ist, was der Low-Level-Hook
    // unter Windows aus lParam gelesen hätte. AKC übersetzt das selbst weiter.
    static readonly Dictionary<Keys, int> VkToScan = BuildScanTable();
    static readonly HashSet<Keys> ExtKeys = new HashSet<Keys> {
        Keys.Up, Keys.Down, Keys.Left, Keys.Right, Keys.Insert, Keys.Delete,
        Keys.Home, Keys.End, Keys.PageUp, Keys.PageDown,
        Keys.RControlKey, Keys.RMenu, Keys.Divide, Keys.NumLock,
        Keys.LWin, Keys.RWin, Keys.Apps,
    };

    static Dictionary<Keys, int> BuildScanTable() {
        var m = new Dictionary<Keys, int> {
            // Buchstaben
            {Keys.A,0x1E},{Keys.B,0x30},{Keys.C,0x2E},{Keys.D,0x20},{Keys.E,0x12},{Keys.F,0x21},
            {Keys.G,0x22},{Keys.H,0x23},{Keys.I,0x17},{Keys.J,0x24},{Keys.K,0x25},{Keys.L,0x26},
            {Keys.M,0x32},{Keys.N,0x31},{Keys.O,0x18},{Keys.P,0x19},{Keys.Q,0x10},{Keys.R,0x13},
            {Keys.S,0x1F},{Keys.T,0x14},{Keys.U,0x16},{Keys.V,0x2F},{Keys.W,0x11},{Keys.X,0x2D},
            {Keys.Y,0x15},{Keys.Z,0x2C},
            // Zahlenreihe
            {Keys.D1,0x02},{Keys.D2,0x03},{Keys.D3,0x04},{Keys.D4,0x05},{Keys.D5,0x06},
            {Keys.D6,0x07},{Keys.D7,0x08},{Keys.D8,0x09},{Keys.D9,0x0A},{Keys.D0,0x0B},
            // Steuertasten
            {Keys.Escape,0x01},{Keys.Back,0x0E},{Keys.Tab,0x0F},{Keys.Return,0x1C},{Keys.Space,0x39},
            {Keys.CapsLock,0x3A},
            // Modifier (generisch -> linke Variante)
            {Keys.ShiftKey,0x2A},{Keys.LShiftKey,0x2A},{Keys.RShiftKey,0x36},
            {Keys.ControlKey,0x1D},{Keys.LControlKey,0x1D},{Keys.RControlKey,0x1D},
            {Keys.Menu,0x38},{Keys.LMenu,0x38},{Keys.RMenu,0x38},
            // OEM (US-Layout-Positionen)
            {Keys.OemMinus,0x0C},{Keys.Oemplus,0x0D},
            {Keys.OemOpenBrackets,0x1A},{Keys.Oem6,0x1B},{Keys.Oem5,0x2B},
            {Keys.Oem1,0x27},{Keys.Oem7,0x28},{Keys.Oem3,0x29},
            {Keys.Oemcomma,0x33},{Keys.OemPeriod,0x34},{Keys.OemQuestion,0x35},
            // F-Tasten
            {Keys.F1,0x3B},{Keys.F2,0x3C},{Keys.F3,0x3D},{Keys.F4,0x3E},{Keys.F5,0x3F},
            {Keys.F6,0x40},{Keys.F7,0x41},{Keys.F8,0x42},{Keys.F9,0x43},{Keys.F10,0x44},
            {Keys.F11,0x57},{Keys.F12,0x58},
            // Navigation (extended; ExtKeys setzt ext=true, Basis-Scancode hier)
            {Keys.Up,0x48},{Keys.Down,0x50},{Keys.Left,0x4B},{Keys.Right,0x4D},
            {Keys.Insert,0x52},{Keys.Delete,0x53},{Keys.Home,0x47},{Keys.End,0x4F},
            {Keys.PageUp,0x49},{Keys.PageDown,0x51},
            {Keys.NumLock,0x45},{Keys.Divide,0x35},
            // Numpad
            {Keys.NumPad0,0x52},{Keys.NumPad1,0x4F},{Keys.NumPad2,0x50},{Keys.NumPad3,0x51},
            {Keys.NumPad4,0x4B},{Keys.NumPad5,0x4C},{Keys.NumPad6,0x4D},{Keys.NumPad7,0x47},
            {Keys.NumPad8,0x48},{Keys.NumPad9,0x49},
            {Keys.Multiply,0x37},{Keys.Subtract,0x4A},{Keys.Add,0x4E},{Keys.Decimal,0x53},
        };
        return m;
    }

    // Verdrahtet die Konsolen-Tastatur. Statt fragilem Feld-Walk (FindRenderControl liefert
    // Impl.Render.g+a, eine Helfer-Klasse OHNE Feld u) suchen wir die Keyboard.d-Instanz im
    // gesamten Objektgraph (Forms + BrowserMediator + kxgui-Statics). Sobald gefunden, cachen
    // wir Keyboard.b.a(Keys) + Keyboard.d.c(bool,int) und installieren den IMessageFilter.
    static void WireConsoleKeyboard()
    {
        if (keyboardD != null) return;          // schon verdrahtet
        try {
            object kd = DbgFindFirst("Com.Raritan.RcCore.Impl.Keyboard.d");
            if (kd == null) {
                if (!kbdWireWarned) { log.Warn("WireConsoleKeyboard: keine Keyboard.d-Instanz im Objektgraph gefunden"); kbdWireWarned = true; }
                return;
            }
            mSendReal = kd.GetType().GetMethod("a", BindingFlags.Instance | BindingFlags.NonPublic,
                                       null, new[] { typeof(bool), typeof(Keys), typeof(int), typeof(bool) }, null);
            if (mSendReal == null) {
                if (!kbdWireWarned) { log.Warn($"WireConsoleKeyboard: Sende-Methode a(bool,Keys,int,bool) fehlt auf {kd.GetType().FullName}"); kbdWireWarned = true; }
                return;
            }
            keyboardD = kd;
            if (!keyFilterInstalled) {
                Application.AddMessageFilter(new ConsoleKeyFilter());
                keyFilterInstalled = true;
            }
            log.Info($"console keyboard wired: kd={kd.GetType().FullName} -> IMessageFilter + Keyboard.d.a(down,Keys,hwsc,ext)");
        } catch (Exception ex) {
            log.Error("WireConsoleKeyboard failed: " + ex.GetBaseException().Message);
        }
    }

    // Fängt WM_KEY*-Messages ab und reicht sie als Device-Scancode an AKCs RFB-Keyboard-Sink.
    class ConsoleKeyFilter : IMessageFilter
    {
        const int WM_KEYDOWN = 0x100, WM_KEYUP = 0x101, WM_SYSKEYDOWN = 0x104, WM_SYSKEYUP = 0x105;
        static bool loggedFirst;

        public bool PreFilterMessage(ref Message m)
        {
            bool down = m.Msg == WM_KEYDOWN || m.Msg == WM_SYSKEYDOWN;
            bool up   = m.Msg == WM_KEYUP   || m.Msg == WM_SYSKEYUP;
            if (!down && !up) return false;
            if (keyboardD == null || mSendReal == null) return false;
            // Nur abgreifen, wenn ein Konsolen-Fenster aktiv ist (Viewer KxGui.s ODER Hauptfenster
            // KxGui.t — Letzteres hält faktisch den Fokus). Bei AKC-Dialogen/anderem -> normal an
            // WinForms weiterreichen, damit Textfelder etc. funktionieren.
            var af = Form.ActiveForm;
            if (af == null) return false;
            var afn = af.GetType().FullName;
            if (afn != "Com.Raritan.KxGui.s" && afn != "Com.Raritan.KxGui.t") return false;
            var key = (Keys)((int)m.WParam & 0xFF);
            if (!VkToScan.TryGetValue(key, out int hwsc)) return false;  // unbekannt -> weiterreichen
            bool ext = ExtKeys.Contains(key);
            try {
                mSendReal.Invoke(keyboardD, new object[] { down, key, hwsc, ext });
                if (!loggedFirst) { log.Info($"console key forwarded: {key} down={down} hwsc={hwsc} ext={ext}"); loggedFirst = true; }
                return true;                    // konsumiert
            } catch (Exception ex) {
                log.Warn("ConsoleKeyFilter error: " + ex.GetBaseException().Message);
                return false;
            }
        }
    }

    // ── Live-Reflection-Debug-API (über Control-API :8081) ───────────────────
    // Interaktive Analyse des laufenden Containers OHNE Rebuild. Alles wird auf den
    // UI-Thread marshalled (WinForms-Objekte sind thread-affin).
    //   /debug/forms                  offene Forms + ActiveForm
    //   /debug/tree[?form=Sub]        Control-Baum (+ Com.Raritan-Felder), je Control eine #id
    //   /debug/grep?q=Keyboard[&f=1]  Objektgraph nach Typname (Teilstring) durchsuchen
    //   /debug/obj?id=N               Objekt #N dumpen (Felder + deklarierte Methoden)
    //   /debug/members?type=Full.Name Felder+Methoden eines Typs (aus rccore/kxgui)
    //   /debug/call?id=N&m=meth&a=bool:true;int:30     Instanz-/statische Methode auf #N
    //   /debug/scall?type=Full&m=a&a=keys:A            statische Methode auf Typ
    //   /debug/sendkey?vk=65[&down=1]  Test-Taste über keyboardD senden (kein down => tap)
    //   /debug/wire                   WireConsoleKeyboard() erzwingen + Status
    // Arg-Tokens (per ';' getrennt): int:N uint:N bool:0/1 str:x keys:A id:N null
    //                                enum:Voller.Typ:Member
    static readonly List<object> dbgReg = new List<object>();

    static int DbgRegister(object o) {
        if (o == null) return -1;
        for (int i = 0; i < dbgReg.Count; i++) if (ReferenceEquals(dbgReg[i], o)) return i;
        dbgReg.Add(o); return dbgReg.Count - 1;
    }

    static IEnumerable<FieldInfo> AllFields(Type t) {
        var seen = new HashSet<string>();
        for (var x = t; x != null && x != typeof(object); x = x.BaseType)
            foreach (var fi in x.GetFields(BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.DeclaredOnly))
                if (seen.Add(x.Name + "::" + fi.Name)) yield return fi;
    }

    static List<object> DbgRoots() {
        var roots = new List<object>();
        try { foreach (Form f in Application.OpenForms) roots.Add(f); } catch { }
        if (bm != null) roots.Add(bm);
        if (mainForm != null) roots.Add(mainForm);
        try {
            foreach (var t in kxgui.GetTypes())
                foreach (var fi in t.GetFields(BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic)) {
                    if (fi.FieldType.IsValueType || fi.FieldType == typeof(string)) continue;
                    try { var v = fi.GetValue(null); if (v != null) roots.Add(v); } catch { }
                }
        } catch { }
        return roots;
    }

    static void DbgMaybeEnqueue(object v, Queue<object> q) {
        if (v == null) return;
        var vt = v.GetType();
        if (vt.IsValueType || vt == typeof(string)) return;
        var fn = vt.FullName ?? "";
        if (fn.StartsWith("System.Reflection.") || fn.StartsWith("log4net.")) return;
        if (fn.StartsWith("Com.Raritan") || v is Control
            || v is System.Collections.IDictionary
            || (v is System.Collections.IEnumerable))
            q.Enqueue(v);
    }

    // BFS über den Objektgraph; visit(obj)==true beendet. Traversiert nur Com.Raritan-Objekte,
    // Controls und Collections, damit der Graph nicht explodiert.
    static void DbgBfs(Func<object, bool> visit, int maxHops = 300000) {
        var seen = new HashSet<object>(new RefEq());
        var q = new Queue<object>();
        foreach (var r in DbgRoots()) if (r != null) q.Enqueue(r);
        int hops = 0;
        while (q.Count > 0 && hops < maxHops) {
            var cur = q.Dequeue(); hops++;
            if (cur == null || seen.Contains(cur)) continue;
            seen.Add(cur);
            if (visit(cur)) return;
            foreach (var fi in AllFields(cur.GetType())) {
                if (fi.FieldType.IsValueType || fi.FieldType == typeof(string)) continue;
                object v; try { v = fi.GetValue(cur); } catch { continue; }
                DbgMaybeEnqueue(v, q);
            }
            if (cur is Control ctl) { try { foreach (Control ch in ctl.Controls) if (ch != null) q.Enqueue(ch); } catch { } }
            if (cur is System.Collections.IDictionary dict) { try { foreach (var v in dict.Values) DbgMaybeEnqueue(v, q); } catch { } }
            else if (cur is System.Collections.IEnumerable en && !(cur is string)) { try { foreach (var it in en) DbgMaybeEnqueue(it, q); } catch { } }
        }
    }

    static object DbgFindFirst(string typeFullName) {
        object found = null;
        DbgBfs(o => { if (o.GetType().FullName == typeFullName) { found = o; return true; } return false; });
        return found;
    }

    static Type ResolveType(string full) {
        if (string.IsNullOrEmpty(full)) return null;
        // '.' -> '+' Toleranz für verschachtelte Typen (z.B. "Com.Raritan.RcCore.o.a").
        var alt = full.Replace('/', '+');
        var t = Type.GetType(full) ?? kxgui?.GetType(full)
            ?? AppDomain.CurrentDomain.GetAssemblies().Select(a => { try { return a.GetType(full); } catch { return null; } }).FirstOrDefault(x => x != null);
        if (t != null) return t;
        // Robuster Fallback: alle Typen aller geladenen Assemblies nach FullName durchsuchen
        // (findet interne/verschachtelte Enums, bei denen Assembly.GetType unter Mono null liefert).
        return AppDomain.CurrentDomain.GetAssemblies()
            .SelectMany(a => { try { return a.GetTypes(); } catch { return new Type[0]; } })
            .FirstOrDefault(x => x.FullName == full || x.FullName == alt);
    }

    static string DbgVal(object v) {
        if (v == null) return "null";
        var t = v.GetType();
        if (t.IsPrimitive || t.IsEnum || v is string || v is decimal) return $"({t.Name}) {v}";
        return $"#{DbgRegister(v)} {t.FullName}";
    }

    static string DbgSig(MethodInfo m) {
        var ps = string.Join(", ", m.GetParameters().Select(p => p.ParameterType.Name + " " + p.Name));
        return $"{(m.IsStatic ? "static " : "")}{m.ReturnType.Name} {m.Name}({ps})";
    }

    static object DbgParseArg(string tok) {
        if (tok == "null") return null;
        int c = tok.IndexOf(':');
        string kind = c < 0 ? "str" : tok.Substring(0, c);
        string val = c < 0 ? tok : tok.Substring(c + 1);
        switch (kind) {
            case "int":  return int.Parse(val);
            case "uint": return uint.Parse(val);
            case "bool": return val == "1" || val.ToLower() == "true";
            case "str":  return val;
            case "keys": return Enum.Parse(typeof(Keys), val, true);
            case "id":   return dbgReg[int.Parse(val)];
            case "enum": { int p = val.LastIndexOf(':'); var et = ResolveType(val.Substring(0, p)); return Enum.Parse(et, val.Substring(p + 1), true); }
            default:     return tok;
        }
    }

    static string DbgInvoke(object target, Type targetType, string method, string argSpec) {
        var toks = string.IsNullOrEmpty(argSpec) ? new string[0] : argSpec.Split(';');
        var args = new object[toks.Length];
        try { for (int i = 0; i < toks.Length; i++) args[i] = DbgParseArg(toks[i]); }
        catch (Exception ex) { return "arg parse error: " + ex.Message; }
        var cands = targetType.GetMethods(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static)
                              .Where(m => m.Name == method && m.GetParameters().Length == args.Length).ToList();
        if (cands.Count == 0) return $"no method {method}/{args.Length} on {targetType.FullName}";
        string lastErr = null;
        foreach (var mi in cands) {
            try {
                var res = mi.Invoke(mi.IsStatic ? null : target, args);
                return $"OK {DbgSig(mi)} => {(mi.ReturnType == typeof(void) ? "void" : DbgVal(res))}";
            } catch (Exception ex) { lastErr = ex.GetBaseException().Message; }
        }
        return $"ERR ({cands.Count} overloads): {lastErr}";
    }

    static string DbgForms() {
        var sb = new StringBuilder();
        foreach (Form f in Application.OpenForms)
            sb.AppendLine($"#{DbgRegister(f)} {f.GetType().FullName} text='{f.Text}' state={f.WindowState} vis={f.Visible} top={f.TopMost} containsFocus={f.ContainsFocus} bounds={f.Bounds}");
        var af = Form.ActiveForm;
        sb.AppendLine($"ActiveForm = {(af == null ? "<null>" : af.GetType().FullName)}");
        sb.AppendLine($"keyboardD = {(keyboardD == null ? "null" : keyboardD.GetType().FullName)}");
        return sb.ToString();
    }

    static void DbgTreeRec(Control c, int depth, StringBuilder sb) {
        string ind = new string(' ', depth * 2);
        sb.AppendLine($"{ind}#{DbgRegister(c)} {c.GetType().FullName} name='{c.Name}' vis={c.Visible} focused={c.Focused} bounds={c.Bounds}");
        foreach (var fi in AllFields(c.GetType())) {
            var ft = fi.FieldType.FullName ?? "";
            if (!ft.StartsWith("Com.Raritan")) continue;
            object v; try { v = fi.GetValue(c); } catch { continue; }
            sb.AppendLine($"{ind}    .{fi.Name} : {fi.FieldType.Name} = {DbgVal(v)}");
        }
        foreach (Control ch in c.Controls) DbgTreeRec(ch, depth + 1, sb);
    }

    static string DbgTree(string formFilter) {
        var sb = new StringBuilder();
        foreach (Form f in Application.OpenForms) {
            if (formFilter != null && !(f.GetType().FullName ?? "").Contains(formFilter)) continue;
            sb.AppendLine($"=== {f.GetType().FullName} ===");
            DbgTreeRec(f, 0, sb);
        }
        return sb.ToString();
    }

    static string DbgObj(int id) {
        if (id < 0 || id >= dbgReg.Count) return "bad id";
        var o = dbgReg[id]; var t = o.GetType();
        var sb = new StringBuilder();
        sb.AppendLine($"#{id} {t.FullName} : {t.BaseType?.FullName}");
        sb.AppendLine("-- fields --");
        foreach (var fi in AllFields(t)) {
            object v; try { v = fi.GetValue(o); } catch (Exception ex) { v = "<err:" + ex.Message + ">"; }
            sb.AppendLine($"  {fi.Name} : {fi.FieldType.Name} = {DbgVal(v)}");
        }
        sb.AppendLine("-- methods (declared) --");
        foreach (var mi in t.GetMethods(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.DeclaredOnly))
            sb.AppendLine("  " + DbgSig(mi));
        return sb.ToString();
    }

    static string DbgMembers(string typeName) {
        var t = ResolveType(typeName);
        if (t == null) return "type not found: " + typeName;
        var sb = new StringBuilder();
        sb.AppendLine($"=== {t.FullName} : {t.BaseType?.FullName} ===");
        sb.AppendLine("-- fields --");
        foreach (var fi in AllFields(t)) sb.AppendLine($"  {(fi.IsStatic ? "static " : "")}{fi.FieldType.Name} {fi.Name}");
        sb.AppendLine("-- methods --");
        foreach (var mi in t.GetMethods(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static | BindingFlags.DeclaredOnly))
            sb.AppendLine("  " + DbgSig(mi));
        return sb.ToString();
    }

    static string DbgGrep(string q, bool withFields) {
        var sb = new StringBuilder(); int n = 0;
        DbgBfs(o => {
            var fn = o.GetType().FullName ?? "";
            if (fn.IndexOf(q, StringComparison.OrdinalIgnoreCase) >= 0) {
                sb.AppendLine($"#{DbgRegister(o)} {fn}");
                if (withFields) foreach (var fi in AllFields(o.GetType())) {
                    var ft = fi.FieldType.FullName ?? "";
                    if (!ft.StartsWith("Com.Raritan")) continue;
                    object v; try { v = fi.GetValue(o); } catch { continue; }
                    sb.AppendLine($"    .{fi.Name}={DbgVal(v)}");
                }
                n++;
            }
            return n >= 300;
        });
        sb.AppendLine($"({n} matches)");
        return sb.ToString();
    }

    static string DbgSendKey(int vk, bool? down) {
        if (keyboardD == null) WireConsoleKeyboard();
        if (keyboardD == null || mSendReal == null) return "keyboardD/mSendReal not wired";
        var key = (Keys)(vk & 0xFF);
        if (!VkToScan.TryGetValue(key, out int hwsc)) return $"{key}(vk={vk}): kein Scancode in Tabelle";
        bool ext = ExtKeys.Contains(key);
        try {
            if (down.HasValue) { mSendReal.Invoke(keyboardD, new object[] { down.Value, key, hwsc, ext }); return $"sent {key} down={down} hwsc={hwsc} ext={ext}"; }
            mSendReal.Invoke(keyboardD, new object[] { true, key, hwsc, ext });
            mSendReal.Invoke(keyboardD, new object[] { false, key, hwsc, ext });
            return $"tapped {key} hwsc={hwsc} ext={ext}";
        } catch (Exception ex) { return "ERR " + ex.GetBaseException().Message; }
    }

    // Holt das l-Session-Objekt über KxGui.s.r().
    static object DbgGetL() {
        foreach (Form f in Application.OpenForms) {
            if (f.GetType().FullName != "Com.Raritan.KxGui.s") continue;
            var r = f.GetType().GetMethod("r", BindingFlags.Instance | BindingFlags.NonPublic | BindingFlags.Public, null, Type.EmptyTypes, null);
            if (r != null) { try { return r.Invoke(f, null); } catch { } }
        }
        return null;
    }

    // Kugelsicher: Maus-Modus über l.a(o.a) setzen. Findet die a(enum)-Überladung über den
    // Parametertyp (Enum namens "a") und setzt den Wert per Enum.ToObject — kein Typname nötig.
    static string DbgMouseMode(int n) {
        var l = DbgGetL();
        if (l == null) return "kein l-Objekt (KxGui.s.r())";
        var m = l.GetType().GetMethods(BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)
                 .FirstOrDefault(mm => mm.Name == "a" && mm.GetParameters().Length == 1
                     && mm.GetParameters()[0].ParameterType.IsEnum
                     && mm.GetParameters()[0].ParameterType.Name == "a");
        if (m == null) return "keine a(o.a)-Überladung auf " + l.GetType().FullName;
        var pt = m.GetParameters()[0].ParameterType;
        try {
            var ev = Enum.ToObject(pt, n);
            m.Invoke(l, new object[] { ev });
            return $"mouse mode gesetzt: {pt.FullName}={ev} (n={n}) auf {l.GetType().FullName}";
        } catch (Exception ex) { return "ERR " + ex.GetBaseException().Message; }
    }

    // Geometrie-Dump: Target-Auflösung (l.ac()) + Viewer/Panel/Render-Bounds.
    static string DbgGeo() {
        var sb = new StringBuilder();
        var l = DbgGetL();
        if (l != null) {
            sb.AppendLine($"l = {l.GetType().FullName}");
            try {
                var ac = l.GetType().GetMethod("ac", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null, Type.EmptyTypes, null);
                if (ac != null) sb.AppendLine($"  target size l.ac() = {ac.Invoke(l, null)}");
            } catch (Exception ex) { sb.AppendLine("  ac() err: " + ex.GetBaseException().Message); }
        }
        foreach (Form f in Application.OpenForms) {
            if (f.GetType().FullName != "Com.Raritan.KxGui.s") continue;
            sb.AppendLine($"viewer state={f.WindowState} bounds={f.Bounds} client={f.ClientSize}");
            DbgGeoRec(f, sb, 1);
        }
        return sb.ToString();
    }
    static void DbgGeoRec(Control c, StringBuilder sb, int depth) {
        foreach (Control ch in c.Controls) {
            var tn = ch.GetType().FullName ?? "";
            bool interesting = ch is Panel || tn.StartsWith("Com.Raritan.RcCore.Impl.Render") || tn.Contains("Render");
            if (interesting)
                sb.AppendLine($"{new string(' ', depth * 2)}{tn} bounds={ch.Bounds} dock={ch.Dock} size={ch.Size} vis={ch.Visible}");
            DbgGeoRec(ch, sb, depth + 1);
        }
    }

    static string DbgDispatch(string path, System.Collections.Specialized.NameValueCollection qs) {
        try {
            switch (path) {
                case "/debug/forms":   return DbgForms();
                case "/debug/tree":    return DbgTree(qs["form"]);
                case "/debug/grep":    return DbgGrep(qs["q"] ?? "", qs["f"] == "1");
                case "/debug/obj":     return DbgObj(int.Parse(qs["id"] ?? "-1"));
                case "/debug/members": return DbgMembers(qs["type"] ?? "");
                case "/debug/call": {
                    int id = int.Parse(qs["id"] ?? "-1");
                    var o = (id >= 0 && id < dbgReg.Count) ? dbgReg[id] : null;
                    return o == null ? "bad id" : DbgInvoke(o, o.GetType(), qs["m"], qs["a"]);
                }
                case "/debug/scall": {
                    var t = ResolveType(qs["type"] ?? "");
                    return t == null ? ("type not found: " + qs["type"]) : DbgInvoke(null, t, qs["m"], qs["a"]);
                }
                case "/debug/sendkey": {
                    bool? dn = qs["down"] == null ? (bool?)null : (qs["down"] == "1" || qs["down"].ToLower() == "true");
                    return DbgSendKey(int.Parse(qs["vk"] ?? "0"), dn);
                }
                case "/debug/mousemode": return DbgMouseMode(int.Parse(qs["n"] ?? "0"));
                case "/debug/geo":       return DbgGeo();
                case "/debug/wire": WireConsoleKeyboard(); return "keyboardD=" + (keyboardD == null ? "null" : keyboardD.GetType().FullName);
                default: return "unknown debug route: " + path;
            }
        } catch (Exception ex) { return "EXC " + ex.GetType().Name + ": " + ex.GetBaseException().Message; }
    }

    // Parse sidebar.asp body — JS-Calls "ports.addPortNew(J('PortId','...'), ...)"
    static List<PortInfo> FetchPortList(string host, int port, CookieContainer jar)
    {
        var url = $"https://{host}" + (port == 443 ? "" : $":{port}") + "/sidebar.asp";
        log.Info($"GET {url} (port discovery)");
        string body;
        try {
            var req = (HttpWebRequest)WebRequest.Create(url);
            req.UserAgent = AKC_UA;
            req.CookieContainer = jar;
            req.AllowAutoRedirect = true;
            req.Timeout = 15000;
            using (var resp = (HttpWebResponse)req.GetResponse())
            using (var sr = new StreamReader(resp.GetResponseStream())) {
                body = sr.ReadToEnd();
            }
        } catch (Exception ex) {
            log.Error($"  FetchPortList failed: {ex.Message}");
            return new List<PortInfo>();
        }

        var result = new List<PortInfo>();
        // Regex matches each addPortNew(...) call body
        var addPortRe = new Regex(@"addPortNew\s*\(([^;]*?)\)\s*;", RegexOptions.Singleline);
        var argRe = new Regex(@"J\(\s*'([^']+)'\s*,\s*('([^']*)'|(-?\d+))\s*\)");
        foreach (Match m in addPortRe.Matches(body)) {
            var info = new PortInfo();
            foreach (Match a in argRe.Matches(m.Groups[1].Value)) {
                string key = a.Groups[1].Value;
                string val = a.Groups[3].Success ? a.Groups[3].Value : a.Groups[4].Value;
                switch (key) {
                    case "PortIndex": int.TryParse(val, out info.Pindex); break;
                    case "PortId":    info.PortId = val; break;
                    case "Name":      info.Name = val; break;
                    case "Type":      info.Ptype = val; break;
                    case "PortType":  info.PortType = val; break;
                    case "Class":     info.Pclass = val; break;
                    case "Status":    int.TryParse(val, out info.Status); break;
                }
            }
            if (!string.IsNullOrEmpty(info.PortId)) {
                info.PermString = "CCC";  // Admin assumed; refine if needed
                result.Add(info);
            }
        }
        return result;
    }

    // HTTP-Control-API auf Port 8081
    static void StartControlApi()
    {
        var listener = new HttpListener();
        listener.Prefixes.Add("http://+:8081/");
        try { listener.Start(); }
        catch (Exception ex) { log.Error($"HttpListener start failed: {ex.Message}"); return; }
        log.Info("=== Control API listening on :8081 (GET /ports, /status, /switch?port=N) ===");

        var workerThread = new Thread(() => {
            while (listener.IsListening) {
                try {
                    var ctx = listener.GetContext();
                    HandleControlRequest(ctx);
                } catch (Exception ex) { log.Warn($"control API err: {ex.Message}"); }
            }
        }) { IsBackground = true, Name = "ControlApi" };
        workerThread.Start();
    }

    static void HandleControlRequest(HttpListenerContext ctx)
    {
        var path = ctx.Request.Url.AbsolutePath;
        string body = "";
        int status = 200;
        try {
            if (path == "/ports") {
                var sb = new StringBuilder();
                sb.Append("[\n");
                for (int i = 0; i < ports.Count; i++) {
                    var p = ports[i];
                    sb.Append($"  {{\"pindex\":{p.Pindex}, \"portId\":\"{p.PortId}\", \"name\":\"{p.Name}\", \"class\":\"{p.Pclass}\", \"type\":\"{p.Ptype}\", \"status\":{p.Status}}}");
                    if (i < ports.Count - 1) sb.Append(",");
                    sb.Append("\n");
                }
                sb.Append("]");
                body = sb.ToString();
            } else if (path == "/status") {
                body = currentPort != null
                    ? $"{{\"currentPort\":\"{currentPort.PortId}\", \"name\":\"{currentPort.Name}\", \"pindex\":{currentPort.Pindex}}}"
                    : "{\"currentPort\":null}";
            } else if (path == "/switch") {
                string portArg = ctx.Request.QueryString["port"]
                              ?? ctx.Request.QueryString["pindex"]
                              ?? ctx.Request.QueryString["portId"];
                if (portArg == null) { status = 400; body = "missing ?port=N or ?portId=..."; }
                else {
                    PortInfo target = null;
                    // Bei numerischem port: nur echte KVM-Ports (nicht Admin/FG) matchen
                    if (int.TryParse(portArg, out int pidx)) {
                        target = ports.FirstOrDefault(p =>
                            p.Pclass == "KVM" && p.Ptype != "FG" && p.Pindex == pidx);
                    }
                    if (target == null) target = ports.FirstOrDefault(p => p.PortId == portArg);
                    if (target == null) { status = 404; body = $"port not found: {portArg}"; }
                    else if (currentPort != null && target.PortId == currentPort.PortId) {
                        body = $"{{\"ok\":true,\"alreadyOn\":\"{target.PortId}\"}}";
                    } else {
                        string fromPidx = currentPort?.Pindex.ToString() ?? "0";
                        log.Info($"Control API: switching {currentPort?.PortId ?? "<none>"} -> {target.PortId}");
                        DoConnect(1, fromPidx, target.Pindex.ToString(), target);
                        body = $"{{\"ok\":true,\"switchedTo\":\"{target.PortId}\",\"name\":\"{target.Name}\"}}";
                    }
                }
            } else if (path.StartsWith("/debug/")) {
                ctx.Response.ContentType = "text/plain; charset=utf-8";
                var qs = ctx.Request.QueryString;
                // WinForms-Objekte sind thread-affin -> auf den UI-Thread marshallen.
                string res = null;
                Action work = () => { res = DbgDispatch(path, qs); };
                if (mainForm != null && mainForm.InvokeRequired) mainForm.Invoke(work); else work();
                body = res ?? "";
            } else if (path == "/" || path == "/index.html") {
                body = WebUiHtml();
                ctx.Response.ContentType = "text/html; charset=utf-8";
            } else {
                status = 404; body = "not found";
            }
        } catch (Exception ex) {
            status = 500; body = $"error: {ex.GetBaseException().Message}";
            log.Error($"control API exception: {ex}");
        }

        ctx.Response.StatusCode = status;
        if (ctx.Response.ContentType == null)
            ctx.Response.ContentType = "application/json";
        ctx.Response.Headers["Access-Control-Allow-Origin"] = "*";
        var bytes = Encoding.UTF8.GetBytes(body);
        ctx.Response.ContentLength64 = bytes.Length;
        ctx.Response.OutputStream.Write(bytes, 0, bytes.Length);
        ctx.Response.Close();
    }

    // Schmales Webinterface: Port-Liste oben, noVNC unten als iframe.
    // Wird auf :8081/ ausgeliefert. Browser-Tab lädt das, klickt Port-Buttons,
    // bekommt KVM-Bild direkt im iframe (noVNC auf :6080).
    static string WebUiHtml() => @"<!DOCTYPE html>
<html><head><meta charset='utf-8'><title>Raritan KVM</title>
<style>
  html,body{margin:0;height:100%;font-family:system-ui,sans-serif;background:#1a1a1a;color:#eee}
  body{display:flex;flex-direction:column}
  header{background:#2c3e50;padding:8px 12px;display:flex;gap:10px;align-items:center;flex-wrap:wrap}
  header strong{font-size:14px;margin-right:4px}
  .ports{display:flex;gap:4px;flex-wrap:wrap}
  .port-btn{padding:4px 10px;background:#34495e;color:#eee;border:1px solid #2c3e50;cursor:pointer;border-radius:3px;font-size:13px}
  .port-btn:hover{background:#4a6178}
  .port-btn.active{background:#27ae60;border-color:#1e8449}
  .port-btn.dead{opacity:0.35;cursor:not-allowed}
  .status{margin-left:auto;font-size:12px;opacity:0.7}
  iframe{flex:1;border:none;width:100%}
</style></head><body>
<header>
  <strong>Raritan KVM</strong>
  <div class='ports' id='ports'>…</div>
  <span class='status' id='status'></span>
</header>
<iframe id='viewport' src='' allowfullscreen></iframe>
<script>
const proto = location.protocol;
const host = location.hostname;
const novncPort = 6080;
document.getElementById('viewport').src =
  `${proto}//${host}:${novncPort}/vnc.html?autoconnect=true&resize=scale&reconnect=true`;

async function refresh() {
  try {
    const ports = await (await fetch('/ports')).json();
    const st = await (await fetch('/status')).json();
    const ctr = document.getElementById('ports');
    ctr.innerHTML = '';
    for (const p of ports) {
      if (p.class !== 'KVM' || p.type === 'FG') continue;
      const b = document.createElement('button');
      const dead = p.status !== 1;
      b.className = 'port-btn' +
        (p.portId === st.currentPort ? ' active' : '') +
        (dead ? ' dead' : '');
      b.textContent = p.name + (dead ? ' (–)' : '');
      b.disabled = dead;
      b.onclick = async () => {
        b.disabled = true; b.textContent = '… switching …';
        await fetch('/switch?port=' + p.pindex);
        // give AKC ~1s to switch internally
        setTimeout(refresh, 1500);
      };
      ctr.appendChild(b);
    }
    document.getElementById('status').textContent =
      st.currentPort ? `aktiv: ${st.name||st.currentPort}` : 'nicht verbunden';
  } catch (e) {
    document.getElementById('status').textContent = 'API-Fehler: ' + e;
  }
}
refresh();
setInterval(refresh, 5000);
</script>
</body></html>";

    static string Login(string host, int port, string user, string pass, CookieContainer jar)
    {
        var baseUrl = $"https://{host}" + (port == 443 ? "" : $":{port}");
        var url = baseUrl + "/auth.asp?client=dotnet";
        log.Info($"POST {url}");

        try {
            var req = (HttpWebRequest)WebRequest.Create(url);
            req.Method = "POST";
            req.ContentType = "application/x-www-form-urlencoded";
            req.UserAgent = AKC_UA;
            req.AllowAutoRedirect = false;
            req.Timeout = 15000;
            req.CookieContainer = jar;
            string body = $"is_dotnet=1&is_standalone_client=0&login={Uri.EscapeDataString(user)}&password={Uri.EscapeDataString(pass)}&action_login=Login";
            var bytes = Encoding.ASCII.GetBytes(body);
            req.ContentLength = bytes.Length;
            using (var s = req.GetRequestStream()) s.Write(bytes, 0, bytes.Length);
            using (var resp = (HttpWebResponse)req.GetResponse()) {
                log.Info($"POST {url} -> {(int)resp.StatusCode}");
            }
        } catch (WebException we) {
            log.Error($"Login POST: {we.Status} {we.Message}");
            return null;
        }

        foreach (Cookie c in jar.GetCookies(new Uri(baseUrl))) {
            if (c.Name == "pp_session_id") return c.Value;
        }
        return null;
    }
}
