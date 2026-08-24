package com.sun.java.browser.dom;

/**
 * Ersatz aus dem Java-Plugin (plugin.jar). Siehe {@link DOMService}.
 *
 * Der Aufrufer verpackt seinen DOM-Zugriff in eine solche Aktion; die
 * DOMService fuehrt sie im richtigen Thread aus.
 */
public interface DOMAction {

    public Object run(DOMAccessor accessor);
}
