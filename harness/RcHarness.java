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
import javax.swing.JFrame;
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

    public static void main(String[] args) throws Exception {
        host = arg(args, 0, "RARITAN_IP", null);
        String user = arg(args, 1, "RARITAN_USER", "admin");
        String pass = arg(args, 2, "RARITAN_PASS", null);
        String portId = arg(args, 3, "RARITAN_PORT_ID", "");
        httpsPort = Integer.parseInt(env("RARITAN_PORT", "443"));

        if (host == null || host.isEmpty() || pass == null || pass.isEmpty()) {
            System.err.println("Aufruf: RcHarness <host> <user> <pass> [portId]");
            System.err.println("   oder RARITAN_IP / RARITAN_USER / RARITAN_PASS setzen");
            System.exit(2);
        }

        log("=== RcHarness — " + APPLET_CLASS + " ohne Browser ===");
        log("Geraet: " + host + ":" + httpsPort + ", Benutzer: " + user);

        installLegacyTls();
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(cookies);

        String session = login(user, pass);
        log("Session: " + session.substring(0, Math.min(12, session.length())) + "… (" + session.length() + " Zeichen)");

        // Die Parameter der Geraeteseite uebernehmen und mit unseren ueberschreiben.
        Map<String, String> params = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        params.putAll(fetchAppletParams());

        List<String> ports = discoverPorts();
        if (portId == null || portId.isEmpty()) {
            portId = ports.isEmpty() ? "" : ports.get(0);
            if (!portId.isEmpty()) log("kein PORT_ID vorgegeben, nehme " + portId);
        }

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
        launch(jarDir, params);
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

    private static final Pattern PORT_ID = Pattern.compile("P_[0-9a-fA-F]+_\\d+");

    private static List<String> discoverPorts() {
        Set<String> ids = new LinkedHashSet<>();
        try {
            Matcher m = PORT_ID.matcher(get("/sidebar.asp"));
            while (m.find()) ids.add(m.group());
        } catch (IOException e) {
            log("WARN: Portliste nicht lesbar: " + e.getMessage());
        }
        List<String> list = new ArrayList<>(ids);
        Collections.sort(list);
        log("gefundene Ports: " + (list.isEmpty() ? "keine" : String.join(", ", list)));
        return list;
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
    private static void launch(File jarDir, Map<String, String> params) throws Exception {
        URL[] urls = new URL[CLIENT_JARS.length];
        for (int i = 0; i < CLIENT_JARS.length; i++) urls[i] = new File(jarDir, CLIENT_JARS[i]).toURI().toURL();

        // Der eigene Klassenlader als Elternteil, damit das Applet unseren
        // netscape.javascript.JSObject-Ersatz findet statt gar keinen.
        URLClassLoader loader = new URLClassLoader(urls, RcHarness.class.getClassLoader());
        Class<?> cls = Class.forName(APPLET_CLASS, true, loader);
        final Applet applet = (Applet) cls.getDeclaredConstructor().newInstance();

        int w = Integer.parseInt(env("HARNESS_WIDTH", "1920"));
        int h = Integer.parseInt(env("HARNESS_HEIGHT", "1080"));

        final URL base = new URL("https://" + host + ":" + httpsPort + "/");
        applet.setStub(new Stub(applet, params, base, w, h));
        applet.setSize(w, h);

        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                JFrame frame = new JFrame("Raritan KVM — " + host);
                frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
                frame.getContentPane().add(applet);
                frame.setSize(w, h);
                frame.setVisible(true);
            }
        });

        log("Applet.init()");
        applet.init();
        log("Applet.start()");
        applet.start();

        // Im Browser bleibt das Applet nach start() stehen und wartet: die
        // umgebende Seite ruft per JavaScript connect(...) auf, und erst dessen
        // notifyAll() loest runRemoteConsole() aus der Sperre. Ohne diesen Aufruf
        // passiert nichts — kein TCP, kein Bild. Der Harness uebernimmt hier die
        // Rolle der Seite.
        String portId = params.getOrDefault("PORT_ID", "");
        if (portId.isEmpty()) {
            log("kein PORT_ID — kein connect(); das Applet bleibt im Leerlauf");
        } else {
            String name = env("RARITAN_PORT_NAME", portId);
            String type = env("RARITAN_PORT_TYPE", "VM");
            String perm = env("RARITAN_PORT_PERM", "CCC");
            log("connect(0, \"0\", \"0\", \"" + portId + "\", \"" + name + "\", \"" + type + "\", \"" + perm + "\")");
            java.lang.reflect.Method connect = applet.getClass().getMethod("connect",
                    int.class, String.class, String.class, String.class, String.class, String.class, String.class);
            connect.invoke(applet, 0, "0", "0", portId, name, type, perm);
        }

        log("=== laeuft — Fenster ist im X-Display sichtbar ===");

        // Der Aufruf kehrt sofort zurueck; die Arbeit macht der EDT. Ohne diese
        // Sperre wuerde der Prozess enden und der Container mit ihm.
        Thread.currentThread().join();
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
