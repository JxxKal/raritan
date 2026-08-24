package com.sun.java.browser.dom;

import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;

/**
 * Ersatz aus dem Java-Plugin (plugin.jar). Siehe {@link DOMService}.
 *
 * Im Original der Anschluss fuer browsereigene DOM-Zulieferer. Der Harness
 * hat keinen — die Klasse ist nur da, damit sie beim Aufloesen nicht fehlt.
 */
public abstract class DOMServiceProvider {

    protected DOMServiceProvider() {
    }

    public abstract boolean canHandle(Object obj);

    public abstract Document getDocument(Object obj) throws DOMUnsupportedException;

    public abstract DOMImplementation getDOMImplementation();
}
