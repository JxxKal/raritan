package netscape.javascript;

import java.applet.Applet;

/**
 * Stub für die JavaScript-Brücke des Browser-Plugins.
 *
 * RemoteConsoleApplet ruft in initializeJSObjects() JSObject.getWindow(this) auf
 * und redet damit im Browser mit der umgebenden Seite (das Applet-Tag trägt
 * MAYSCRIPT). Ausserhalb eines Browsers gibt es diese Klasse gar nicht: ohne
 * Ersatz bricht schon das Laden mit NoClassDefFoundError ab.
 *
 * Ein Ersatz genügt, weil die Brücke nur für die Rückmeldungen an die Weboberflaeche
 * gebraucht wird — Verbindungsaufbau, Video und Eingaben laufen nicht darüber.
 * Jeder Aufruf wird still verschluckt statt zu scheitern, damit ein unerwarteter
 * Zugriff die Sitzung nicht mitreisst.
 */
public class JSObject {

    private static final JSObject INSTANCE = new JSObject();

    /**
     * Der Client meldet seinen Zustand ueber diese Bruecke an die Seite —
     * jacConnected, jacDisconnected, jacSwitched und Verwandte. Ausserhalb eines
     * Browsers ist das die einzige Stelle, an der man erfaehrt, was er gerade
     * tut. Der Harness haengt sich hier ein, statt zu raten.
     */
    public static java.util.function.BiConsumer<String, Object[]> listener;

    protected JSObject() {
    }

    public static JSObject getWindow(Applet applet) {
        return INSTANCE;
    }

    public Object call(String methodName, Object[] args) {
        notifyListener(methodName, args);
        return null;
    }

    public Object eval(String s) {
        notifyListener("eval", new Object[] { s });
        return null;
    }

    public Object getMember(String name) {
        return null;
    }

    public void setMember(String name, Object value) {
    }

    public void removeMember(String name) {
    }

    public Object getSlot(int index) {
        return null;
    }

    public void setSlot(int index, Object value) {
    }

    private static void notifyListener(String method, Object[] args) {
        java.util.function.BiConsumer<String, Object[]> l = listener;
        if (l == null) return;
        try {
            l.accept(method, args == null ? new Object[0] : args);
        } catch (Throwable ignored) {
            // Ein Fehler in der Anzeige darf die Sitzung nicht mitreissen.
        }
    }

    @Override
    public String toString() {
        return "JSObject(stub)";
    }
}
