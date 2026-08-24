package com.sun.java.browser.dom;

/**
 * Ersatz aus dem Java-Plugin (plugin.jar). Siehe {@link DOMService}.
 *
 * Ungeprueft — im Original wickelt sie Fehler ein, die waehrend einer
 * DOMAction auftreten.
 */
public class DOMAccessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DOMAccessException() {
        super();
    }

    public DOMAccessException(String s) {
        super(s);
    }

    public DOMAccessException(Throwable e) {
        super(e);
    }

    public DOMAccessException(Throwable e, String s) {
        super(s, e);
    }
}
