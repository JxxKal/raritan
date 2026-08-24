package com.sun.java.browser.dom;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;

/**
 * Ersatz fuer die DOM-Bruecke des Java-Plugins (com.sun.java.browser.dom aus
 * plugin.jar).
 *
 * Neuere Staende von rc.jar sprechen die umgebende Seite nicht nur ueber
 * netscape.javascript.JSObject an, sondern auch ueber diese zweite Bruecke.
 * Das Paket steckte allein im Plugin und fehlt jedem JDK seit 9 — gemessen am
 * Geraet (Firmware mit rc.jar, 1537230 Bytes) scheitert deshalb schon
 * Class.forName("nn.pp.rc.RemoteConsoleApplet"):
 *
 *   NoClassDefFoundError: com/sun/java/browser/dom/DOMUnsupportedException
 *
 * Die Klasse steht in einer throws-Klausel des Applets und wird beim Laden
 * aufgeloest, ganz ohne dass ein DOM-Aufruf stattfaende. Aeltere Staende
 * (rc.jar aus 2009, 977850 Bytes im Labor) kennen das Paket gar nicht — dort
 * faellt der Ersatz schlicht nicht auf.
 *
 * Wie beim JSObject-Ersatz gilt: nichts scheitern lassen. getService() liefert
 * immer eine Bruecke, und jede Aktion laeuft gegen ein leeres Dokument. So
 * bekommt das Applet einen gueltigen Zugang statt einer Ausnahme, und ein
 * Zugriff auf ein Element, das es hier nicht gibt, reisst die Sitzung nicht
 * mit. Ueber die Seite laufen ohnehin nur Rueckmeldungen — Verbindungsaufbau,
 * Video und Eingaben nicht.
 */
public abstract class DOMService {

    public DOMService() {
    }

    /**
     * Im Original: die Bruecke zum Dokument, in dem das Applet steckt. Wirft
     * dort DOMUnsupportedException, wenn es keine gibt. Genau das tun wir
     * NICHT — ein Applet, das die Ausnahme nicht sauber abfaengt, waere sonst
     * am Ende, obwohl es ohne Seite weiterlaufen koennte.
     */
    public static DOMService getService(Object obj) throws DOMUnsupportedException {
        return new HarnessService();
    }

    /**
     * Dieselbe Bruecke fuer den Aufruf mit Applet-Typ. Welche der beiden
     * Ueberladungen das Original hatte, ist von aussen nicht zu sehen; der
     * Aufruf im Applet ist auf eine feste Signatur uebersetzt. Beide Fassungen
     * anzubieten kostet nichts und erspart ein NoSuchMethodError.
     */
    public static DOMService getService(java.applet.Applet applet) throws DOMUnsupportedException {
        return new HarnessService();
    }

    public abstract Object invokeAndWait(DOMAction action) throws DOMAccessException;

    public abstract void invokeLater(DOMAction action) throws DOMAccessException;

    /** Die Bruecke ins Leere: fuehrt die Aktion aus, verschluckt jeden Fehler. */
    private static final class HarnessService extends DOMService {

        @Override
        public Object invokeAndWait(DOMAction action) throws DOMAccessException {
            return run(action);
        }

        @Override
        public void invokeLater(DOMAction action) throws DOMAccessException {
            run(action);
        }

        private static Object run(DOMAction action) {
            if (action == null) return null;
            try {
                return action.run(HarnessAccessor.INSTANCE);
            } catch (Throwable t) {
                // Das Applet greift auf ein Element der Seite zu, die es hier
                // nicht gibt. Nur melden — die Sitzung haengt nicht daran.
                System.out.println("[harness] DOM-Zugriff ins Leere: " + t);
                System.out.flush();
                return null;
            }
        }
    }

    /** Ein leeres Dokument statt der Seite; die uebrigen Aufrufe gehen an die JDK-Fassung. */
    private static final class HarnessAccessor implements DOMAccessor {

        static final HarnessAccessor INSTANCE = new HarnessAccessor();

        private final Document empty;

        private HarnessAccessor() {
            Document d = null;
            try {
                d = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            } catch (Exception e) {
                System.out.println("[harness] kein leeres DOM-Dokument: " + e);
                System.out.flush();
            }
            empty = d;
        }

        public Document getDocument(Object obj) {
            return empty;
        }

        public DOMImplementation getDOMImplementation() {
            return empty == null ? null : empty.getImplementation();
        }

        public boolean hasFeature(String feature, String version) {
            DOMImplementation impl = getDOMImplementation();
            return impl != null && impl.hasFeature(feature, version);
        }

        public DocumentType createDocumentType(String qualifiedName, String publicId, String systemId) {
            DOMImplementation impl = getDOMImplementation();
            return impl == null ? null : impl.createDocumentType(qualifiedName, publicId, systemId);
        }

        public Document createDocument(String namespaceURI, String qualifiedName, DocumentType doctype) {
            DOMImplementation impl = getDOMImplementation();
            return impl == null ? null : impl.createDocument(namespaceURI, qualifiedName, doctype);
        }

        public Object getFeature(String feature, String version) {
            DOMImplementation impl = getDOMImplementation();
            return impl == null ? null : impl.getFeature(feature, version);
        }
    }
}
