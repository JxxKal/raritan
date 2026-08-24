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

    protected JSObject() {
    }

    public static JSObject getWindow(Applet applet) {
        return INSTANCE;
    }

    public Object call(String methodName, Object[] args) {
        return null;
    }

    public Object eval(String s) {
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

    @Override
    public String toString() {
        return "JSObject(stub)";
    }
}
