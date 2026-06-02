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
        log.Info("=== Bridge v17 starting (AKC + Port-Discovery + Control API :8081) ===");

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
            currentPort = ports.FirstOrDefault(p => p.Pclass == "KVM" && p.Status == 1)
                       ?? ports.FirstOrDefault(p => p.Pclass == "KVM");
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
        var maximizeTimer = new System.Windows.Forms.Timer { Interval = 500 };
        maximizeTimer.Tick += (s, e) => {
            foreach (Form f in Application.OpenForms) {
                if (f.WindowState != FormWindowState.Maximized) {
                    try { f.WindowState = FormWindowState.Maximized; }
                    catch { }
                }
            }
        };
        maximizeTimer.Start();

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
