// Probe: instantiates AKC dependencies via Reflection under Mono.
// Reports what works, what fails, with errors.
// Goal: understand AKC's runtime requirements before building Bridge v8.

using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Windows.Forms;
using log4net;
using log4net.Config;

class Probe
{
    static ILog log;

    static int Main(string[] args)
    {
        BasicConfigurator.Configure();
        log = LogManager.GetLogger(typeof(Probe));
        log.Info("=== AKC Probe starting ===");

        log.Info("Step 1: Load kxgui-patched.exe");
        Assembly kxgui;
        try {
            kxgui = Assembly.LoadFrom("kxgui-patched.exe");
            log.Info($"  OK: {kxgui.FullName}");
        } catch (Exception ex) {
            log.Error($"  FAIL: {ex.GetType().Name}: {ex.Message}");
            return 1;
        }

        // Enumerate the interesting types
        string[] typeNames = {
            "Com.Raritan.KxGui.BrowserMediator",
            "Com.Raritan.KxGui.k",
            "Com.Raritan.KxGui.t",
            "Com.Raritan.KxGui.y",
            "Com.Raritan.KxGui.s",
            "Com.Raritan.KxGui.FavoriteDevices.a",
            "Com.Raritan.KxGui.Preferences.ApplicationPreferencesManager",
            "Com.Raritan.KxGui.Preferences.DevicePreferencesManager",
        };

        log.Info("Step 2: Inspect type ctors");
        foreach (var tn in typeNames) {
            var t = kxgui.GetType(tn);
            if (t == null) {
                log.Warn($"  type not found: {tn}");
                continue;
            }
            log.Info($"  {tn} (isInterface={t.IsInterface}, isAbstract={t.IsAbstract}):");
            foreach (var c in t.GetConstructors(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance)) {
                var ps = c.GetParameters();
                var sig = string.Join(", ", ps.Select(p => $"{p.ParameterType.Name} {p.Name}"));
                log.Info($"    ctor({sig}) [public={c.IsPublic}]");
            }
        }

        log.Info("Step 3: Try instantiating ApplicationPreferencesManager (no args)");
        object apm = TryInstantiate(kxgui, "Com.Raritan.KxGui.Preferences.ApplicationPreferencesManager");

        log.Info("Step 4: Try instantiating DevicePreferencesManager(string)");
        object dpm = TryInstantiate(kxgui, "Com.Raritan.KxGui.Preferences.DevicePreferencesManager", new object[] { "10.180.42.160" });

        log.Info("Step 5: Try instantiating FavoriteDevices.a(apm)");
        object fav = (apm != null) ? TryInstantiate(kxgui, "Com.Raritan.KxGui.FavoriteDevices.a", new object[] { apm }) : null;

        log.Info("Step 6: Try instantiating KxGui.t (the form) WITHOUT Show()");
        // Form ctor: (string, string, DevicePreferencesManager, ApplicationPreferencesManager)
        object formT = (apm != null && dpm != null)
            ? TryInstantiate(kxgui, "Com.Raritan.KxGui.t", new object[] { "10.180.42.160", "10.180.42.160", dpm, apm })
            : null;

        log.Info("Step 7: Try constructing BrowserMediator(null, fav, formT, apm, dpm)");
        object bm = null;
        if (formT != null && fav != null && apm != null && dpm != null) {
            bm = TryInstantiate(kxgui, "Com.Raritan.KxGui.BrowserMediator", new object[] { null, fav, formT, apm, dpm });
        } else {
            log.Warn("  skipped: missing dependencies");
        }

        if (bm != null) {
            log.Info("Step 8: Try calling BrowserMediator.Init(xml)");
            string xml =
                "<param name=\"SESSION_ID\" value=\"DUMMYTOKEN\"/>" +
                "<param name=\"PORT\" value=\"443\"/>" +
                "<param name=\"SSLPORT\" value=\"443\"/>" +
                "<param name=\"SSL\" value=\"force\"/>" +
                "<param name=\"FIPS\" value=\"0\"/>" +
                "<param name=\"BOARD_TYPE\" value=\"lara\"/>" +
                "<param name=\"PRODUCT_TYPE\" value=\"kx2\"/>" +
                "<param name=\"HW_ID\" value=\"5E\"/>" +
                "<param name=\"PORT_ID\" value=\"the_kvm_port\"/>" +
                "<param name=\"LANGUAGE\" value=\"en\"/>";
            try {
                bm.GetType().GetMethod("Init").Invoke(bm, new object[] { xml });
                log.Info("  Init(xml) returned without exception");
            } catch (Exception ex) {
                log.Error($"  Init(xml) FAILED: {ex.GetBaseException().GetType().Name}: {ex.GetBaseException().Message}");
            }
        }

        log.Info("=== Probe finished ===");
        return 0;
    }

    static object TryInstantiate(Assembly asm, string typeName, object[] ctorArgs = null)
    {
        try {
            var t = asm.GetType(typeName);
            if (t == null) {
                log.Warn($"  type not found: {typeName}");
                return null;
            }
            var obj = (ctorArgs != null && ctorArgs.Length > 0)
                ? Activator.CreateInstance(t,
                    BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic,
                    null, ctorArgs, null)
                : Activator.CreateInstance(t,
                    BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic,
                    null, null, null);
            log.Info($"  OK: {typeName} → {obj?.GetType()?.FullName}");
            return obj;
        } catch (Exception ex) {
            var e = ex.GetBaseException();
            log.Error($"  FAIL {typeName}: {e.GetType().Name}: {e.Message}");
            // dump stack for diagnosing
            foreach (var line in e.StackTrace?.Split('\n').Take(5) ?? new string[0]) {
                log.Error($"     {line.Trim()}");
            }
            return null;
        }
    }
}
