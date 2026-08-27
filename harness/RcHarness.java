import java.applet.Applet;
import java.applet.AppletContext;
import java.applet.AppletStub;
import java.applet.AudioClip;
import java.awt.Image;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Startet den Java-Client der Dominion KX2 ausserhalb eines Browsers.
 *
 * Die KX2 laedt ihren Client selbst als Applet:
 *
 *   <applet archive="rc.jar, rclang_en.jar" code="nn.pp.rc.RemoteConsoleApplet.class">
 *     <param name="SESSION_ID" …> <param name="PORT" …> <param name="SSL" value="force">
 *
 * Dieser Harness baut die Umgebung nach, die das Applet erwartet — AppletStub und
 * AppletContext —, meldet sich vorher per HTTP am Geraet an und reicht die dabei
 * erhaltene Session weiter. Das Passwort steht damit nur im Anmeldeschritt im
 * Spiel; das Applet selbst bekommt bloss die SESSION_ID.
 *
 * rc.jar wird zur Laufzeit vom Geraet geladen, nicht mitgeliefert. Damit passt der
 * Client immer zur Firmware, die gerade laeuft — bei einem Firmware-Update zieht
 * der Harness beim naechsten Start automatisch die passende Fassung.
 *
 * Aufruf:
 *   java -cp harness.jar RcHarness <host> <user> <pass> [portId]
 * oder ueber die Umgebung: RARITAN_IP, RARITAN_USER, RARITAN_PASS, RARITAN_PORT_ID.
 */
public class RcHarness {

    private static final String APPLET_CLASS = "nn.pp.rc.RemoteConsoleApplet";
    private static final String[] CLIENT_JARS = { "rc.jar", "rclang_en.jar" };

    private static String host;
    private static int httpsPort = 443;

    private static Applet applet;
    private static Map<String, String> appletParams;
    private static JLabel stateLabel;
    private static JLabel eventLabel;
    private static JLabel reasonLabel;
    private static JLabel portLabel;
    private static JLabel portsLabel;
    private static JFrame ownFrame;
    private static JLayeredPane layers;
    private static volatile String lastEvent = "—";
    private static volatile String lastReason = "";
    private static volatile boolean connected;

    // Der Knopf "Erneut verbinden" muss denselben Anlauf ausloesen koennen wie
    // die Schleife in main() — auch dann, wenn es noch gar kein Applet gibt.
    // Dafuer bleiben die Startwerte hier stehen.
    private static String cfgUser, cfgPass, cfgWantedPort;
    private static int cfgW, cfgH;

    public static void main(String[] args) throws Exception {
        host = arg(args, 0, "RARITAN_IP", null);
        final String user = arg(args, 1, "RARITAN_USER", "admin");
        final String pass = arg(args, 2, "RARITAN_PASS", null);
        final String wantedPort = arg(args, 3, "RARITAN_PORT_ID", "");
        httpsPort = Integer.parseInt(env("RARITAN_PORT", "443"));

        if (host == null || host.isEmpty() || pass == null || pass.isEmpty()) {
            System.err.println("Aufruf: RcHarness <host> <user> <pass> [portId]");
            System.err.println("   oder RARITAN_IP / RARITAN_USER / RARITAN_PASS setzen");
            System.exit(2);
        }

        log("=== RcHarness — " + APPLET_CLASS + " ohne Browser ===");
        log("Geraet: " + host + ":" + httpsPort + ", Benutzer: " + user);

        installLegacyTls();
        CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));

        final int w = Integer.parseInt(env("HARNESS_WIDTH", "1920"));
        final int h = Integer.parseInt(env("HARNESS_HEIGHT", "1080"));

        // Die Anzeige entsteht VOR dem ersten Anlauf. Frueher flog ein
        // fehlgeschlagener Start — Geraet nicht erreichbar, Passwort falsch —
        // als Ausnahme aus main(): die JVM endete, der Entrypoint endete, der
        // Container startete neu. Im Browser sah man davon nur "Failed to connect
        // to server", weil die WebSocket-Verbindung bei jedem Neustart abriss,
        // und der Grund stand allein im Protokoll. Jetzt bleibt die Anzeige
        // stehen, nennt den Grund, und der Anlauf wiederholt sich von selbst.
        cfgUser = user; cfgPass = pass; cfgWantedPort = wantedPort; cfgW = w; cfgH = h;

        SwingUtilities.invokeAndWait(() -> buildFrame(w, h));
        startDialogWatchdog();

        int retry = Integer.parseInt(env("RETRY_SECONDS", "30"));
        log("Wiederholung alle " + retry + " s (RETRY_SECONDS=0 schaltet sie ab)");
        while (true) {
            if (!sessionLive()) attemptOnce();
            if (retry <= 0) {
                log("RETRY_SECONDS=0 — keine Wiederholung");
                Thread.currentThread().join();
            }
            Thread.sleep(retry * 1000L);
        }
    }

    /**
     * Ein vollstaendiger Anlauf: fehlt der Client noch, wird er erst geholt und
     * gestartet, danach folgt der Connect. Schleife und Knopf gehen beide hier
     * durch — sonst rief der Knopf connectOnce() auf, wenn es nach einem
     * gescheiterten Start noch gar kein Applet gab, und lief in eine
     * NullPointerException.
     */
    /**
     * Nur EIN Anlauf zur Zeit.
     *
     * Der Knopf "Erneut verbinden" startet einen eigenen Thread, und die
     * Schleife in main() laeuft weiter — beide riefen frueher ungebremst
     * attemptOnce(). Am Geraet gemessen ueberlappten sich die Anlaeufe dann:
     * waehrend der eine noch in Applet.init() stand, meldete sich der naechste
     * schon wieder an und lud ein ZWEITES Applet. Ergebnis waren drei
     * Sitzungsfenster und drei connect() auf denselben Port — der sich prompt
     * als "verbunden" meldete, belegt von uns selbst.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean busy =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static void attemptOnce() {
        if (!busy.compareAndSet(false, true)) {
            log("es laeuft bereits ein Anlauf — dieser wird uebersprungen");
            return;
        }
        try {
            attemptOnceLocked();
        } finally {
            busy.set(false);
        }
    }

    private static void attemptOnceLocked() {
        try {
            if (applet == null) {
                startup(cfgUser, cfgPass, cfgWantedPort, cfgW, cfgH);
            } else {
                log("keine Sitzung — neuer Versuch");
                // Die Lage kann sich geaendert haben: ein belegter Port wird
                // frei, ein CIM kommt dazu. Vor dem naechsten connect() also
                // nachsehen, statt die Liste vom Start zu glauben.
                refreshPorts();
                updatePortLabel();
            }
            connectOnce();
        } catch (Throwable e) {
            // Throwable, nicht Exception: fehlt dem Applet eine Klasse aus der
            // Plugin-Umgebung, kommt ein NoClassDefFoundError. Der ist ein
            // Error und flog frueher an dieser Stelle vorbei — der Thread
            // "reconnect" starb still, die Anzeige nannte weiter den alten
            // Grund, und der Hinweis stand allein im Containerprotokoll.
            String why = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            log("Anlauf fehlgeschlagen — " + why);
            lastReason = why;
            setState("kein Kontakt zum Geraet");
            updateStatus();
        }
    }

    /**
     * Ein frueher gestartetes Applet samt Sitzungsfenster abraeumen.
     *
     * Ohne das bleibt bei jedem neuen Anlauf das alte stehen: unsichtbar
     * uebereinander liegende Fenster, jedes mit einer eigenen Sitzung auf
     * demselben Port. Das Geraet zaehlt die mit und weist irgendwann ab.
     */
    private static void discardApplet() {
        Applet old = applet;
        if (old == null) return;
        applet = null;
        appletParams = null;
        log("raeume das vorige Applet ab");
        try {
            old.stop();
            old.destroy();
        } catch (Throwable t) {
            log("beim Abraeumen: " + t);
        }
        try {
            SwingUtilities.invokeAndWait(() -> {
                for (java.awt.Window win : java.awt.Window.getWindows()) {
                    if (win == ownFrame || !win.isVisible()) continue;
                    win.setVisible(false);
                    win.dispose();
                }
            });
        } catch (Exception e) {
            log("beim Schliessen der Fenster: " + e);
        }
    }

    /**
     * Mauszeiger-Verhalten vorgeben, bevor der Client startet.
     *
     * Im KVM-Fenster laufen sonst zwei Zeiger auseinander: der echte des
     * Zielrechners und der, den der Client zeichnet. Dagegen hilft der Single
     * Cursor Mode — nur liess er sich schwer einschalten, weil der
     * Bestaetigungsdialog frueher vom Dialogwaechter abgeraeumt wurde (siehe
     * reapDialogs) und ein Menueweg im Browser umstaendlich ist.
     *
     * Der Client liest sein Verhalten aus den Java-Preferences unter
     * /ApplicationSettings (in ApplicationPreferences.importPreferences als
     * ROOT_NODE hinterlegt; die Werte kommen sonst aus
     * $HOME/ApplicationSettings.xml, die es im Container nicht gibt):
     *
     *   AlwaysOpenSingleMouseMode  jede Sitzung startet im Single Cursor Mode
     *   singleMouseInstructions    zeigt die Rueckfrage dazu
     *
     * HARNESS_SINGLE_MOUSE=1 setzt beides passend — Modus an, Rueckfrage aus.
     * Ohne die Variable bleibt alles wie gehabt.
     */
    private static void applyMousePreferences() {
        if (!"1".equals(env("HARNESS_SINGLE_MOUSE", "0"))) return;
        try {
            java.util.prefs.Preferences p =
                    java.util.prefs.Preferences.userRoot().node("/ApplicationSettings");
            p.putBoolean("AlwaysOpenSingleMouseMode", true);
            p.putBoolean("singleMouseInstructions", false);
            p.flush();
            log("HARNESS_SINGLE_MOUSE=1 — Sitzung startet im Single Cursor Mode, ohne Rueckfrage");
            log("  (verlassen im Browser mit Strg+LinkeAlt+O; Single Cursor an/aus: Strg+Alt+X)");
        } catch (Exception e) {
            log("WARN: Mauseinstellung nicht gesetzt: " + e);
        }
    }

    /** Anmelden, Parameter der Geraeteseite holen, rc.jar laden, Applet starten. */
    private static void startup(String user, String pass, String wantedPort, int w, int h) throws Exception {
        discardApplet();
        applyMousePreferences();
        setState("melde mich an …");
        String portId = wantedPort;
        String session = login(user, pass);
        log("Session: " + session.substring(0, Math.min(12, session.length())) + "… (" + session.length() + " Zeichen)");

        // Die Parameter der Geraeteseite uebernehmen und mit unseren ueberschreiben.
        Map<String, String> params = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        params.putAll(fetchAppletParams());

        refreshPorts();
        chosenPort = choosePort(portId);
        if (chosenPort != null) portId = chosenPort.id;

        params.put("HOST", host);
        params.put("PORT", String.valueOf(httpsPort));
        params.put("SSLPORT", String.valueOf(httpsPort));
        params.put("SSL", "force");
        // NICHT mit dem pp_session_id-Cookie ueberschreiben: die Geraeteseite
        // traegt eine eigene SESSION_ID, und genau die erwartet die RFB-Schicht
        // (im AKC-Protokoll taucht sie als "EricKey" auf). Das Cookie autorisiert
        // nur die Weboberflaeche. Nur wenn die Seite keine liefert, springt das
        // Cookie ein.
        if (!params.containsKey("SESSION_ID") || params.get("SESSION_ID").isEmpty()) {
            log("Geraeteseite nennt keine SESSION_ID — nehme das Cookie");
            params.put("SESSION_ID", session);
        }
        params.put("USERNAME", user);
        // Das Applet handelt die RFB-Anmeldung selbst aus (RfbAuthenticator:
        // PLAIN, MD5, HTTP_SESSION_ID oder RDM_SESSION_ID, je nach den Caps-Bits
        // des Geraets). Welche Verfahren die KX2 anbietet, weiss man vorher nicht
        // — deshalb bekommt es Benutzer, Passwort UND Session, und darf waehlen.
        // Mit HARNESS_SEND_PASSWORD=0 laesst sich das Passwort zurueckhalten,
        // falls die Session allein genuegt.
        params.put("PASSWORD", "0".equals(env("HARNESS_SEND_PASSWORD", "1")) ? "" : pass);
        params.putIfAbsent("LANGUAGE", "en");
        params.putIfAbsent("bgcolor", "#000000");
        params.putIfAbsent("fgcolor", "#ffffff");
        if (!portId.isEmpty()) params.put("PORT_ID", portId);

        log("Parameter an das Applet:");
        for (Map.Entry<String, String> e : params.entrySet()) {
            String v = e.getValue();
            if (e.getKey().equalsIgnoreCase("SESSION_ID") && v.length() > 12) v = v.substring(0, 12) + "…";
            log("  " + e.getKey() + " = " + v);
        }

        File jarDir = downloadClientJars();
        launch(jarDir, params, w, h);
    }

    // ── TLS ──────────────────────────────────────────────────────────────────
    /**
     * Die KX2 spricht TLS 1.0 mit AES256-SHA und weist sich mit einem
     * selbstsignierten, abgelaufenen Zertifikat aus; sichere Renegotiation nach
     * RFC 5746 kennt sie nicht. Eine heutige JRE lehnt das gleich dreifach ab.
     *
     * Die Protokoll- und Signaturbeschraenkungen loest die mitgelieferte
     * legacy.security auf (per -Djava.security.properties gesetzt) — hier bleibt
     * das Zertifikat: es gibt keine Instanz, die es beglaubigen koennte, also
     * wird es ungeprueft angenommen. Vertretbar, weil die Verbindung im selben
     * Segment endet und die KX2 keine andere Moeglichkeit bietet.
     */
    private static void installLegacyTls() throws Exception {
        TrustManager[] trustAll = { new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) { }
            public void checkServerTrusted(X509Certificate[] c, String a) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        } };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new SecureRandom());

        // setDefault gilt auch fuer die Sockets, die das Applet selbst oeffnet —
        // ohne das wuerde nur unser Anmeldeschritt durchkommen.
        SSLContext.setDefault(ctx);
        HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
            public boolean verify(String h, SSLSession s) { return true; }
        });
        log("TLS: Zertifikatspruefung abgeschaltet, Protokolle aus legacy.security");
    }

    // ── Anmeldung ────────────────────────────────────────────────────────────
    private static String login(String user, String pass) throws IOException {
        String body = "is_dotnet=1&is_standalone_client=0"
                + "&login=" + enc(user)
                + "&password=" + enc(pass)
                + "&action_login=Login";
        HttpURLConnection c = open("/auth.asp?client=dotnet");
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        drain(c);
        log("POST /auth.asp?client=dotnet -> " + code);

        for (HttpCookie ck : ((CookieManager) CookieHandler.getDefault()).getCookieStore().getCookies()) {
            if ("pp_session_id".equals(ck.getName())) return ck.getValue();
        }
        // Kein Cookie heisst hier nicht "Netzfehler", sondern fast immer:
        // falsche Zugangsdaten — oder das Geraet verlangt nach einem Werksreset
        // erst eine Passwortaenderung und schickt jede Seite auf pwchangeforced.asp.
        throw new IOException("Anmeldung fehlgeschlagen: kein pp_session_id erhalten "
                + "(falsches Passwort, oder das Geraet verlangt eine Passwortaenderung)");
    }

    // ── Parameter und Ports von der Geraeteseite ─────────────────────────────
    private static final Pattern PARAM = Pattern.compile(
            "<param\\s+name\\s*=\\s*[\"']([^\"']+)[\"']\\s+value\\s*=\\s*[\"']([^\"']*)[\"']",
            Pattern.CASE_INSENSITIVE);

    private static Map<String, String> fetchAppletParams() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            String html = get("/sidebar.asp");
            Matcher m = PARAM.matcher(html);
            while (m.find()) out.put(m.group(1), m.group(2));
            log("Geraeteseite liefert " + out.size() + " Parameter");
        } catch (IOException e) {
            log("WARN: sidebar.asp nicht lesbar (" + e.getMessage() + ") — nehme nur eigene Parameter");
        }
        return out;
    }

    // ── Portliste vom Geraet ─────────────────────────────────────────────────
    /**
     * Ein Port, so wie ihn die Geraeteseite beschreibt.
     *
     * sidebar.asp traegt die ganze Portlage als JavaScript aus:
     *
     *   ports.addPortNew(J('PortId','P_000d5d06a393_0'), J('Name','Console 1'),
     *                    J('PortIndex',0), J('PortNumber',1), J('Type','DCIM'),
     *                    J('Class','KVM'), J('Status',1), J('StatAvailable',2), …)
     *
     * Die Bedeutung der beiden Zahlen steht im selben Skript (getPortsSummary):
     * Status 0=down, 1=up; StatAvailable 0=frei, 1=verbunden, 2=belegt,
     * 3=nicht verfuegbar. Genau diese Werte braucht man, um einen belegten Port
     * von einem toten zu unterscheiden — vorher nahm der Harness blind den
     * ersten und lief in "[0x10020001] Port sharing … is unavailable".
     */
    private static final class PortInfo {
        final String id, name, index, type, pclass;
        final int number, status, avail;
        final String kvmPerm, vmPerm, pwrPerm;

        PortInfo(Map<String, String> f) {
            id      = f.getOrDefault("PortId", "");
            name    = f.getOrDefault("Name", id);
            index   = f.getOrDefault("PortIndex", "0");
            type    = f.getOrDefault("Type", "");
            pclass  = f.getOrDefault("Class", "");
            number  = num(f.get("PortNumber"), 0);
            status  = num(f.get("Status"), -1);
            avail   = num(f.get("StatAvailable"), -1);
            kvmPerm = f.getOrDefault("KVMPermKey", "n/a");
            vmPerm  = f.getOrDefault("VMPermKey", "n/a");
            pwrPerm = f.getOrDefault("PWRPermKey", "n/a");
        }

        /** Fuer einen vorgegebenen Port, den die Seite nicht kennt. */
        PortInfo(String portId) {
            id = portId; name = portId; index = "0"; type = ""; pclass = "KVM";
            number = 0; status = -1; avail = -1;
            kvmPerm = "n/a"; vmPerm = "n/a"; pwrPerm = "n/a";
        }

        private static int num(String s, int fallback) {
            try { return s == null ? fallback : Integer.parseInt(s.trim()); }
            catch (NumberFormatException e) { return fallback; }
        }

        boolean isKvm()  { return id.startsWith("P_") && "KVM".equalsIgnoreCase(pclass); }
        boolean isUp()   { return status == 1; }
        boolean isFree() { return avail == 0; }
        boolean isBusy() { return avail == 1 || avail == 2; }

        String availText() {
            switch (avail) {
                case 0:  return "frei";
                case 1:  return "verbunden";
                case 2:  return "belegt";
                case 3:  return "nicht verfuegbar";
                default: return "unbekannt";
            }
        }

        String statusText() {
            switch (status) {
                case 0:  return "down";
                case 1:  return "up";
                default: return "?";
            }
        }

        String describe() {
            return String.format("%2d  %-24s %-16s %-6s %-16s %s",
                    number, cut(name, 24), cut(type.isEmpty() ? "—" : type, 16),
                    statusText(), availText(), id);
        }

        private static String cut(String s, int n) {
            return s.length() <= n ? s : s.substring(0, n - 1) + "…";
        }
    }

    private static final Pattern PORT_RECORD =
            Pattern.compile("ports\\.addPortNew\\((.*?)\\)\\s*;", Pattern.DOTALL);
    private static final Pattern J_FIELD =
            Pattern.compile("J\\('([^']*)'\\s*,\\s*(?:'([^']*)'|([^,)]*))\\)");
    private static final Pattern PERMS_CALL =
            Pattern.compile("new\\s+Permissions\\(([^)]*)\\)");
    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

    private static volatile List<PortInfo> portList = new ArrayList<>();
    private static volatile Map<String, String> devicePerms = new LinkedHashMap<>();
    private static volatile PortInfo chosenPort;

    /**
     * Portlage neu einlesen. Scheitert das — abgelaufene Sitzung, Netzhaenger —,
     * bleibt die alte Liste stehen: eine veraltete Anzeige ist besser als eine
     * leere, und der Verbindungsversuch soll daran nicht scheitern.
     */
    private static void refreshPorts() {
        String html;
        try {
            html = get("/sidebar.asp");
        } catch (IOException e) {
            log("WARN: Portliste nicht lesbar (" + e.getMessage() + ") — behalte die letzte");
            return;
        }

        Map<String, String> perms = new LinkedHashMap<>();
        Matcher pm = PERMS_CALL.matcher(html);
        if (pm.find()) {
            List<String> args = new ArrayList<>();
            Matcher q = QUOTED.matcher(pm.group(1));
            while (q.find()) args.add(q.group(1));
            for (int i = 0; i + 1 < args.size(); i += 2) perms.put(args.get(i), args.get(i + 1));
        }

        List<PortInfo> found = new ArrayList<>();
        Matcher rm = PORT_RECORD.matcher(html);
        while (rm.find()) {
            Map<String, String> fields = new LinkedHashMap<>();
            Matcher fm = J_FIELD.matcher(rm.group(1));
            while (fm.find()) {
                String v = fm.group(2) != null ? fm.group(2) : fm.group(3);
                fields.put(fm.group(1), v == null ? "" : v.trim());
            }
            PortInfo p = new PortInfo(fields);
            if (!p.id.isEmpty()) found.add(p);
        }

        if (found.isEmpty()) {
            log("WARN: keine Portsaetze in sidebar.asp gefunden — behalte die letzte Liste");
            return;
        }
        portList = found;
        devicePerms = perms;
        logPorts();
    }

    private static void logPorts() {
        List<PortInfo> kvm = new ArrayList<>();
        for (PortInfo p : portList) if (p.isKvm()) kvm.add(p);
        log("Portlage (" + kvm.size() + " KVM-Ports von " + portList.size() + " Eintraegen):");
        log("    #  Name                     Typ              Status Verfuegbar       PortId");
        for (PortInfo p : kvm) log("  " + p.describe());
        log("  Zusammenfassung: " + portSummary());
        if (!"yes".equals(devicePerms.get("pc_share"))) {
            log("  Hinweis: die Benutzergruppe hat KEIN pc_share — ein belegter Port bleibt dicht.");
        }
    }

    /** Kurzfassung fuer die Anzeige: wie viele Ports frei, belegt, tot sind. */
    private static String portSummary() {
        int frei = 0, belegt = 0, tot = 0, ohne = 0;
        for (PortInfo p : portList) {
            if (!p.isKvm()) continue;
            if (!p.isUp()) { tot++; continue; }
            if (p.avail == 3) { ohne++; }
            else if (p.isBusy()) { belegt++; }
            else { frei++; }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(frei).append(" frei");
        if (belegt > 0) sb.append(" · ").append(belegt).append(" belegt");
        if (tot > 0) sb.append(" · ").append(tot).append(" ohne CIM/aus");
        if (ohne > 0) sb.append(" · ").append(ohne).append(" nicht verfuegbar");
        return sb.toString();
    }

    /**
     * Welchen Port nehmen wir?
     *
     * Vorgabe schlaegt alles. Ohne Vorgabe bleibt es beim bisherigen Verhalten
     * (der erste KVM-Port) — RARITAN_PORT_PICK=free nimmt stattdessen den ersten
     * freien. Absichtlich nicht andersherum: eine laufende Installation soll
     * nach einem Update nicht ploetzlich auf einem anderen Port landen.
     */
    private static PortInfo choosePort(String wanted) {
        if (wanted != null && !wanted.isEmpty()) {
            for (PortInfo p : portList) if (p.id.equalsIgnoreCase(wanted)) return p;
            log("PORT_ID " + wanted + " steht nicht in der Portliste — nehme ihn trotzdem");
            return new PortInfo(wanted);
        }
        List<PortInfo> kvm = new ArrayList<>();
        for (PortInfo p : portList) if (p.isKvm()) kvm.add(p);
        if (kvm.isEmpty()) {
            log("kein KVM-Port gefunden");
            return null;
        }
        if ("free".equalsIgnoreCase(env("RARITAN_PORT_PICK", "first"))) {
            for (PortInfo p : kvm) {
                if (p.isUp() && p.isFree()) {
                    log("RARITAN_PORT_PICK=free — nehme " + p.id + " (" + p.name + ", frei)");
                    return p;
                }
            }
            log("RARITAN_PORT_PICK=free, aber kein freier Port — nehme den ersten");
        }
        PortInfo first = kvm.get(0);
        log("kein PORT_ID vorgegeben, nehme " + first.id + " (" + first.name + ", " + first.availText() + ")");
        return first;
    }

    /**
     * Die Berechtigungszeichenkette, die die Weboberflaeche dem Applet reicht
     * (getJacPermStringByItem): drei Zeichen fuer KVM, Virtual Media und Strom.
     *
     * Nur wenn sich alle drei Schluessel wirklich aufloesen lassen, wird
     * gerechnet. Sonst bleibt es bei der Vorgabe — ein Port ohne CIM meldet
     * "n/a" als Schluessel, und daraus wuerde "NNN" statt "CCC" werden: der
     * Client haette dann weniger Rechte als der Benutzer wirklich hat.
     */
    private static String permString(PortInfo p, String fallback) {
        String kvm = devicePerms.get(p.kvmPerm);
        String vm  = devicePerms.get(p.vmPerm);
        String pwr = devicePerms.get(p.pwrPerm);
        if (kvm == null || vm == null || pwr == null) return fallback;
        StringBuilder sb = new StringBuilder();
        sb.append("control".equals(kvm) ? 'C' : "view".equals(kvm) ? 'R' : 'N');
        sb.append("readwrite".equals(vm) ? 'C' : "readonly".equals(vm) ? 'R' : 'N');
        sb.append("access".equals(pwr) ? 'C' : 'N');
        String s = sb.toString();
        if (!s.equals(fallback)) log("Rechte laut Geraeteseite: " + s + " (Vorgabe war " + fallback + ")");
        return s;
    }

    // ── Client-Jars vom Geraet holen ─────────────────────────────────────────
    private static File downloadClientJars() throws IOException {
        File dir = Files.createTempDirectory("rcjars").toFile();
        dir.deleteOnExit();
        for (String jar : CLIENT_JARS) {
            HttpURLConnection c = open("/" + jar);
            byte[] data = readAll(c.getInputStream());
            File f = new File(dir, jar);
            try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(data); }
            log("geladen: " + jar + " (" + data.length + " Bytes)");
        }
        return dir;
    }

    // ── Applet starten ───────────────────────────────────────────────────────
    private static void launch(File jarDir, Map<String, String> params, int w, int h) throws Exception {
        URL[] urls = new URL[CLIENT_JARS.length];
        for (int i = 0; i < CLIENT_JARS.length; i++) urls[i] = new File(jarDir, CLIENT_JARS[i]).toURI().toURL();

        // Der eigene Klassenlader als Elternteil, damit das Applet unseren
        // netscape.javascript.JSObject-Ersatz findet statt gar keinen.
        URLClassLoader loader = new URLClassLoader(urls, RcHarness.class.getClassLoader());
        Class<?> cls = Class.forName(APPLET_CLASS, true, loader);
        applet = (Applet) cls.getDeclaredConstructor().newInstance();
        // Erst jetzt, wo das Applet wirklich existiert: sonst zeigt die Anzeige
        // einen Port an, zu dem es gar keinen Client gibt.
        appletParams = params;
        updatePortLabel();

        // Der Client meldet seinen Zustand ueber die JSObject-Bruecke. Im Browser
        // landet das in der Seite; hier in Protokoll und Anzeige.
        netscape.javascript.JSObject.listener = (method, args) -> {
            StringBuilder sb = new StringBuilder(method).append("(");
            for (int i = 0; i < args.length; i++) sb.append(i > 0 ? ", " : "").append(args[i]);
            String text = sb.append(")").toString();
            log("Client meldet: " + text);
            lastEvent = text;
            String m = method.toLowerCase();
            if (m.contains("connected") && !m.contains("dis")) connected = true;
            if (m.contains("disconnected")) connected = false;
            updateStatus();
        };

        final URL base = new URL("https://" + host + ":" + httpsPort + "/");
        applet.setStub(new Stub(applet, params, base, w, h));
        applet.setSize(w, h);

        SwingUtilities.invokeAndWait(() -> attachApplet(w, h));

        log("Applet.init()");
        applet.init();
        log("Applet.start()");
        applet.start();

        log("=== Applet laeuft ===");
    }

    /**
     * Im Browser bleibt das Applet nach start() stehen und wartet: die umgebende
     * Seite ruft per JavaScript connect(...) auf, und erst dessen notifyAll()
     * loest runRemoteConsole() aus der Sperre. Ohne diesen Aufruf passiert
     * nichts — kein TCP, kein Bild. Der Harness uebernimmt hier die Rolle der
     * Seite.
     */
    private static void connectOnce() {
        if (applet == null || appletParams == null) {
            log("noch kein Client geladen — connect() uebersprungen");
            setState("noch keine Verbindung zum Geraet");
            return;
        }
        String portId = appletParams.getOrDefault("PORT_ID", "");
        if (portId.isEmpty()) {
            log("kein PORT_ID — kein connect(); das Applet bleibt im Leerlauf");
            setState("kein KVM-Port bekannt");
            return;
        }
        PortInfo p = chosenPort != null && chosenPort.id.equals(portId) ? chosenPort : new PortInfo(portId);

        // Die Weboberflaeche ruft connect(0, 0, pindex, portId, pname, ptype,
        // permString) — der dritte Wert ist der PortIndex, nicht konstant "0".
        // Mit der festen "0" ging jeder Verbindungsversuch an den ersten Port,
        // egal welche PORT_ID daneben stand.
        String index = p.index;
        String name = env("RARITAN_PORT_NAME", p.name.isEmpty() ? portId : p.name);
        // Typ und Rechte bleiben bei den erprobten Vorgaben. "auto" nimmt, was
        // die Geraeteseite meldet — also genau das, was auch der Browser
        // schicken wuerde.
        String type = env("RARITAN_PORT_TYPE", "VM");
        if ("auto".equalsIgnoreCase(type)) type = p.type.isEmpty() || "Not Available".equalsIgnoreCase(p.type) ? "VM" : p.type;
        String perm = env("RARITAN_PORT_PERM", "CCC");
        if ("auto".equalsIgnoreCase(perm)) perm = permString(p, "CCC");

        if (p.isBusy()) {
            String wie = p.avail == 2 ? "belegt" : "verbunden";
            log("Achtung: " + p.id + " ist " + wie + " — es sitzt schon jemand darauf,");
            log("  oft die lokale Konsole des Geraets. Das hat zwei Folgen:");
            log("  ohne PC-Share weist das Geraet die Sitzung ganz ab ([0x10020001]);");
            log("  MIT PC-Share kommt sie zustande, aber Tastatur und Maus gehoeren");
            log("  dem Ersten — man sieht dann das Bild und kann nichts eingeben.");
            log("  Abhilfe: die andere Sitzung trennen (Port Access → Disconnect) oder");
            log("  die lokale Konsole abschalten (Device Settings → Local Port Settings).");
        } else if (p.status == 0) {
            log("Achtung: " + p.id + " meldet sich als down — meist fehlt das CIM.");
        }

        log("connect(0, \"0\", \"" + index + "\", \"" + portId + "\", \"" + name + "\", \"" + type + "\", \"" + perm + "\")");
        setState("verbinde mit " + name + " …");
        try {
            java.lang.reflect.Method connect = applet.getClass().getMethod("connect",
                    int.class, String.class, String.class, String.class, String.class, String.class, String.class);
            connect.invoke(applet, 0, "0", index, portId, name, type, perm);
        } catch (Exception e) {
            log("connect() fehlgeschlagen: " + e);
            setState("connect() fehlgeschlagen: " + e.getMessage());
        }
    }

    /**
     * Waechter fuer die modalen Dialoge des Clients.
     *
     * Scheitert ein Verbindungsaufbau, zeigt der Client einen modalen
     * Fehlerdialog. Der parkt den EDT in einer verschachtelten Ereignisschleife
     * (Dialog.show -> WaitDispatchSupport.enter) und blockiert dabei alle
     * anderen Fenster der Anwendung — auch unseren Rahmen. Ein Klick auf
     * "Erneut verbinden" erreicht den Knopf dann gar nicht erst, und jeder
     * weitere Versuch legt einen Dialog obendrauf.
     *
     * Der Waechter nimmt den Text heraus, schreibt ihn in Protokoll und Anzeige
     * und raeumt den Dialog ab. Die Meldung geht damit nicht verloren, sie steht
     * nur an einer Stelle, die nichts blockiert.
     */
    private static void startDialogWatchdog() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1500);
                    SwingUtilities.invokeAndWait(RcHarness::reapDialogs);
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    log("Dialogwaechter: " + e);
                }
            }
        }, "dialog-watchdog");
        t.setDaemon(true);
        t.start();
        log("Dialogwaechter laeuft — modale Meldungen des Clients landen in der Anzeige");
    }

    /**
     * Steht eine Sitzung, gehoeren die Dialoge dem Benutzer.
     *
     * Der Waechter war fuer den Fall gedacht, dass ein Verbindungsversuch
     * scheitert und der Fehlerdialog den EDT in einer verschachtelten
     * Ereignisschleife parkt — dann kommt niemand mehr an "Erneut verbinden".
     * Er raeumte aber ausnahmslos JEDEN Dialog ab, auch die des laufenden
     * Betriebs: die Rueckfrage zum Single Cursor Mode ("You are about to enter
     * Single Cursor mode … press Ctrl+LeftAlt+O") war nach spaetestens 1,5 s
     * wieder weg, bevor jemand OK druecken konnte. Damit liess sich der Modus
     * gar nicht einschalten — und genau der behebt die auseinanderlaufenden
     * Mauszeiger im KVM-Fenster. Dasselbe traf die Video-Settings.
     *
     * Der naheliegende Ausweg — Dialoge waehrend einer Sitzung stehen lassen —
     * hat sich am Geraet als schlimmer erwiesen: ein modaler Dialog parkt den
     * EDT in einer verschachtelten Ereignisschleife und friert damit die
     * gesamte Java-Oberflaeche ein. Der Fenstermanager kann das Fenster dann
     * noch verschieben (das laeuft an Java vorbei), aber IM Client reagiert
     * nichts mehr — kein Menue, kein Klick, also auch kein Weg zurueck.
     * Zentrieren und nach vorn holen half nicht, wenn der Dialog gar nicht
     * erst gezeichnet wird.
     *
     * Vorgabe ist deshalb wieder "always": lieber eine Meldung, die nur im
     * Protokoll steht, als eine Oberflaeche, an die niemand mehr herankommt.
     * "nosession" laesst Dialoge waehrend einer Sitzung stehen (dann ist
     * ./deploy.sh dialogs close der Notausgang), "never" schaltet den Waechter
     * ganz ab.
     *
     * Fuer den Single Cursor Mode gibt es den Weg ohne Dialog:
     * HARNESS_SINGLE_MOUSE=1 (siehe applyMousePreferences).
     */
    private static void reapDialogs() {
        String mode = env("HARNESS_REAP_DIALOGS", "always");
        if ("never".equalsIgnoreCase(mode)) return;
        boolean live = !"always".equalsIgnoreCase(mode) && sessionWindowOpen();

        for (java.awt.Window win : java.awt.Window.getWindows()) {
            if (!(win instanceof java.awt.Dialog) || !win.isVisible()) continue;
            java.awt.Dialog dlg = (java.awt.Dialog) win;
            String title = dlg.getTitle() == null ? "" : dlg.getTitle();

            if (live) {
                // Stehenlassen allein genuegt nicht: ein modaler Dialog parkt
                // den EDT in einer verschachtelten Ereignisschleife und
                // blockiert dabei ALLE Fenster der Anwendung. Liegt er dann
                // ausserhalb des Bildes oder hinter dem Sitzungsfenster, ist
                // die Oberflaeche tot — man kommt an nichts mehr heran, auch
                // nicht an den Dialog selbst.
                //
                // Also: in die Mitte des Bildschirms holen und nach vorn
                // bringen, damit er anklickbar ist. Nur beim ersten Mal, sonst
                // rueckt der Waechter ihn alle 1,5 s wieder zurecht, waehrend
                // jemand ihn gerade verschiebt.
                if (reportedDialogs.add(System.identityHashCode(dlg))) {
                    String text = collectText(dlg).trim();
                    log("Dialog des Clients [" + title + "]: "
                            + (text.isEmpty() ? "(ohne Text)" : text));
                    log("  bleibt stehen und wird nach vorn geholt — "
                            + "HARNESS_REAP_DIALOGS=always raeumt stattdessen ab");
                    lastReason = text.isEmpty() ? title : text;
                    updateStatus();
                    try {
                        java.awt.Dimension scr = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
                        dlg.setLocation(Math.max(0, (scr.width - dlg.getWidth()) / 2),
                                        Math.max(0, (scr.height - dlg.getHeight()) / 2));
                        dlg.setAlwaysOnTop(true);
                        dlg.toFront();
                        dlg.requestFocus();
                    } catch (Throwable t) {
                        log("  konnte den Dialog nicht nach vorn holen: " + t);
                    }
                }
                continue;
            }

            String text = collectText(dlg).trim();
            log("Meldung des Clients [" + title + "]: " + (text.isEmpty() ? "(ohne Text)" : text));
            // In eine eigene Zeile, nicht nach lastEvent: sonst ueberschreibt das
            // unmittelbar folgende jacDisconnected() genau die Begruendung, die
            // man sehen will.
            lastReason = text.isEmpty() ? title : text;
            updateStatus();
            dlg.setVisible(false);
            dlg.dispose();
        }
    }

    /** Schon gemeldete Dialoge — verhindert dieselbe Zeile alle 1,5 s. */
    private static final Set<Integer> reportedDialogs =
            java.util.Collections.synchronizedSet(new LinkedHashSet<>());

    /** Sammelt die Beschriftungen eines Dialogs — dort steht die Fehlermeldung. */
    private static String collectText(java.awt.Container c) {
        StringBuilder sb = new StringBuilder();
        for (java.awt.Component comp : c.getComponents()) {
            if (comp instanceof JLabel) {
                String t = ((JLabel) comp).getText();
                if (t != null && !t.isEmpty()) sb.append(t).append(" ");
            } else if (comp instanceof javax.swing.text.JTextComponent) {
                String t = ((javax.swing.text.JTextComponent) comp).getText();
                if (t != null && !t.isEmpty()) sb.append(t).append(" ");
            } else if (comp instanceof java.awt.Container) {
                sb.append(collectText((java.awt.Container) comp));
            }
        }
        return sb.toString();
    }

    /**
     * Der Client oeffnet fuer die Sitzung ein eigenes Fenster und rendert nicht
     * in diesen Rahmen. Scheitert der Verbindungsaufbau, schliesst er es wieder —
     * und ohne diese Anzeige bliebe ein leerer Desktop zurueck, dem man nicht
     * ansieht, ob ueberhaupt etwas laeuft.
     */
    private static void buildFrame(int w, int h) {
        JPanel info = new JPanel();
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.setBackground(new Color(0x1e1e1e));
        info.setBorder(BorderFactory.createEmptyBorder(40, 48, 40, 48));

        JLabel title = new JLabel("Raritan KVM");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        info.add(title);
        info.add(Box.createVerticalStrut(16));

        info.add(line(host + ":" + httpsPort + "   Benutzer: " + env("RARITAN_USER", "admin")));
        portLabel = line("Port: —");
        info.add(portLabel);
        portsLabel = line("");
        portsLabel.setForeground(new Color(0x9a9a9a));
        info.add(portsLabel);
        info.add(Box.createVerticalStrut(16));

        stateLabel = line("starte …");
        stateLabel.setForeground(new Color(0x9cdcfe));
        info.add(stateLabel);
        eventLabel = line("Letzte Meldung: —");
        info.add(eventLabel);
        reasonLabel = line("");
        reasonLabel.setForeground(new Color(0xf48771));
        info.add(reasonLabel);
        info.add(Box.createVerticalStrut(24));

        JButton again = new JButton("Erneut verbinden");
        again.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                new Thread(RcHarness::attemptOnce, "reconnect").start();
            }
        });
        info.add(again);
        info.add(Box.createVerticalStrut(24));
        info.add(line("Die Sitzung oeffnet ein eigenes Fenster ueber dieser Anzeige."));
        info.add(line("Bleibt es aus, steht der Grund im Protokoll: docker compose logs -f"));

        // Der Rahmen entsteht, bevor es ein Applet gibt: er soll auch dann etwas
        // zeigen, wenn die Anmeldung am Geraet noch gar nicht geklappt hat.
        // Das Applet kommt spaeter darunter — es traegt visuell nichts bei, muss
        // aber in voller Groesse in einem darstellbaren Rahmen haengen, weil
        // getRootPane() es fuer seine eigenen Dialoge braucht.
        layers = new JLayeredPane();
        layers.setPreferredSize(new java.awt.Dimension(w, h));
        info.setBounds(0, 0, w, h);
        layers.add(info, JLayeredPane.PALETTE_LAYER);

        JFrame frame = new JFrame("Raritan KVM — " + host);
        ownFrame = frame;
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(layers, BorderLayout.CENTER);
        frame.setSize(w, h);
        frame.setVisible(true);
    }

    private static void attachApplet(int w, int h) {
        applet.setBounds(0, 0, w, h);
        layers.add(applet, JLayeredPane.DEFAULT_LAYER);
        layers.revalidate();
        layers.repaint();
    }

    /**
     * Zeigt den gewaehlten Port mit Namen und Verfuegbarkeit, darunter die Lage
     * der uebrigen. Ein belegter Port ist damit auf einen Blick als solcher zu
     * erkennen — vorher stand dort nur eine Kennung, und warum nichts kam,
     * musste man im Protokoll suchen.
     */
    private static void updatePortLabel() {
        if (portLabel == null) return;
        final PortInfo p = chosenPort;
        final String id = p != null ? p.id
                : appletParams == null ? "—" : appletParams.getOrDefault("PORT_ID", "—");
        final String text = p == null ? "Port: " + id
                : "Port: " + (p.number > 0 ? p.number + "  " : "") + p.name + "   (" + p.availText() + ", " + p.statusText() + ")";
        final String summary = portList.isEmpty() ? "" : "Ports: " + portSummary();
        final boolean warn = p != null && p.isBusy() && !"yes".equals(devicePerms.get("pc_share"));
        SwingUtilities.invokeLater(() -> {
            portLabel.setText(text);
            if (portsLabel != null) {
                portsLabel.setText(warn ? summary + "   — belegt und kein PC-Share: das Geraet weist die Sitzung ab" : summary);
                portsLabel.setForeground(warn ? new Color(0xd7ba7d) : new Color(0x9a9a9a));
            }
        });
    }

    private static JLabel line(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(new Color(0xd4d4d4));
        l.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 15));
        return l;
    }

    private static void setState(String text) {
        if (stateLabel == null) return;
        SwingUtilities.invokeLater(() -> stateLabel.setText(text));
    }

    /**
     * Steht die Sitzung?
     *
     * Bisher haing das allein an der jacConnected-Rueckmeldung ueber die
     * JSObject-Bruecke. Am Geraet gemessen kommt die aber nicht zuverlaessig:
     * der Client oeffnet sein Sitzungsfenster, das Bild laeuft — und `connected`
     * bleibt trotzdem false. Die Schleife in main() schiebt dann alle
     * RETRY_SECONDS ein weiteres connect() nach, das Geraet sieht einen zweiten
     * Zugriff auf den eigenen, inzwischen belegten Port und weist ihn ab
     * ([0x10020001]). Der Harness saegte sich damit selbst den Ast ab.
     *
     * Verlaesslicher ist das Fenster: der Client rendert die Sitzung in einen
     * eigenen Rahmen. Ist ausser unserer Anzeige ein sichtbares Fenster da,
     * laeuft die Sitzung — unabhaengig davon, ob eine Rueckmeldung kam.
     */
    private static boolean sessionWindowOpen() {
        for (java.awt.Window win : java.awt.Window.getWindows()) {
            if (win == ownFrame || !win.isVisible()) continue;
            if (win instanceof java.awt.Dialog) continue;   // Meldungen zaehlen nicht
            if (win.getWidth() < 200 || win.getHeight() < 200) continue;
            return true;
        }
        return false;
    }

    /** connected ODER ein offenes Sitzungsfenster — beides heisst: nicht nachschieben. */
    private static boolean sessionLive() {
        if (connected) return true;
        try {
            final boolean[] open = new boolean[1];
            SwingUtilities.invokeAndWait(() -> open[0] = sessionWindowOpen());
            return open[0];
        } catch (Exception e) {
            return false;
        }
    }

    private static void updateStatus() {
        if (eventLabel == null) return;
        SwingUtilities.invokeLater(() -> {
            eventLabel.setText("Letzte Meldung: " + lastEvent);
            stateLabel.setText(connected ? "Sitzung steht"
                    : sessionWindowOpen() ? "Sitzung steht (Fenster offen)" : "keine Sitzung");
            if (reasonLabel != null) reasonLabel.setText(lastReason.isEmpty() ? "" : "Grund: " + lastReason);
        });
    }

    /** Die Umgebung, die ein Applet vom Browser erwartet. */
    private static class Stub implements AppletStub {
        private final Applet applet;
        private final Map<String, String> params;
        private final URL base;
        private final int w, h;
        private final AppletContext ctx = new Ctx();

        Stub(Applet applet, Map<String, String> params, URL base, int w, int h) {
            this.applet = applet; this.params = params; this.base = base; this.w = w; this.h = h;
        }
        public boolean isActive() { return true; }
        public URL getDocumentBase() { return base; }
        public URL getCodeBase() { return base; }
        public String getParameter(String name) { return params.get(name); }
        public AppletContext getAppletContext() { return ctx; }
        public void appletResize(int width, int height) { applet.setSize(width, height); }
    }

    /** Alles, was ohne Browser keinen Sinn ergibt, gibt hier nichts zurueck. */
    private static class Ctx implements AppletContext {
        private final Map<String, InputStream> streams = new LinkedHashMap<>();
        public AudioClip getAudioClip(URL url) { return null; }
        public Image getImage(URL url) { return null; }
        public Applet getApplet(String name) { return null; }
        public Enumeration<Applet> getApplets() { return Collections.enumeration(new ArrayList<Applet>()); }
        public void showDocument(URL url) { log("showDocument(" + url + ") — ignoriert"); }
        public void showDocument(URL url, String target) { showDocument(url); }
        public void showStatus(String status) { log("Status: " + status); }
        public void setStream(String key, InputStream stream) { streams.put(key, stream); }
        public InputStream getStream(String key) { return streams.get(key); }
        public Iterator<String> getStreamKeys() { return streams.keySet().iterator(); }
    }

    // ── Kleinkram ────────────────────────────────────────────────────────────
    private static HttpURLConnection open(String path) throws IOException {
        URL url = new URL("https://" + host + ":" + httpsPort + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; RcHarness)");
        return c;
    }

    private static String get(String path) throws IOException {
        HttpURLConnection c = open(path);
        return new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    private static void drain(HttpURLConnection c) {
        try {
            InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) readAll(in);
        } catch (IOException ignored) { }
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private static String arg(String[] a, int i, String envName, String def) {
        if (a.length > i && !a[i].isEmpty()) return a[i];
        return env(envName, def);
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static void log(String s) {
        System.out.println("[harness] " + s);
        System.out.flush();
    }
}
