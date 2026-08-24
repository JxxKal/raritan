package com.sun.java.browser.dom;

import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;

/**
 * Ersatz aus dem Java-Plugin (plugin.jar). Siehe {@link DOMService}.
 *
 * Der Zugang zum Dokument der umgebenden Seite. Im Harness gibt es keine
 * Seite; {@link DOMService} reicht ein leeres Dokument heraus.
 */
public interface DOMAccessor extends DOMImplementation {

    public Document getDocument(Object obj) throws DOMUnsupportedException;

    public DOMImplementation getDOMImplementation();
}
