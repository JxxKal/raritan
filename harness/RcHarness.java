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
            if (!connected) attemptOnce();
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
    private static void attemptOnce() {
        try {
            if (applet == null) {
                startup(cfgUser, cfgPass, cfgWantedPort, cfgW, cfgH);
            } else {
                log("keine Sitzung — neuer Versuch");
            }
            connectOnce();
        } catch (Exception e) {
            String why = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            log("Anlauf fehlgeschlagen — " + why);
            lastReason = why;
            setState("kein Kontakt zum Geraet");
            updateStatus();
        }
    }

    /** Anmelden, Parameter der Geraeteseite holen, rc.jar laden, Applet starten. */
    private static void startup(String user, String pass, String wantedPort, int w, int h) throws Exception {
        setState("melde mich an …");
        String portId = wantedPort;
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
        String name = env("RARITAN_PORT_NAME", portId);
        String type = env("RARITAN_PORT_TYPE", "VM");
        String perm = env("RARITAN_PORT_PERM", "CCC");
        log("connect(0, \"0\", \"0\", \"" + portId + "\", \"" + name + "\", \"" + type + "\", \"" + perm + "\")");
        setState("verbinde mit " + portId + " …");
        try {
            java.lang.reflect.Method connect = applet.getClass().getMethod("connect",
                    int.class, String.class, String.class, String.class, String.class, String.class, String.class);
            connect.invoke(applet, 0, "0", "0", portId, name, type, perm);
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

    private static void reapDialogs() {
        for (java.awt.Window win : java.awt.Window.getWindows()) {
            if (!(win instanceof java.awt.Dialog) || !win.isVisible()) continue;
            java.awt.Dialog dlg = (java.awt.Dialog) win;
            String text = collectText(dlg).trim();
            String title = dlg.getTitle() == null ? "" : dlg.getTitle();
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

    private static void updatePortLabel() {
        if (portLabel == null) return;
        SwingUtilities.invokeLater(() ->
                portLabel.setText("Port: " + appletParams.getOrDefault("PORT_ID", "—")));
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

    private static void updateStatus() {
        if (eventLabel == null) return;
        SwingUtilities.invokeLater(() -> {
            eventLabel.setText("Letzte Meldung: " + lastEvent);
            stateLabel.setText(connected ? "Sitzung steht" : "keine Sitzung");
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
