package com.sun.java.browser.dom;

/**
 * Ersatz aus dem Java-Plugin (plugin.jar). Siehe {@link DOMService}.
 *
 * Diese Klasse steht in der throws-Klausel von RemoteConsoleApplet und wird
 * deshalb schon beim Laden der Applet-Klasse aufgeloest — ohne sie endet
 * bereits Class.forName() mit NoClassDefFoundError, lange bevor irgendein
 * DOM-Aufruf stattfindet.
 */
public class DOMUnsupportedException extends Exception {

    private static final long serialVersionUID = 1L;

    public DOMUnsupportedException() {
        super();
    }

    public DOMUnsupportedException(String s) {
        super(s);
    }
}
