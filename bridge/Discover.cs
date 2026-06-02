using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Collections.Generic;

class Discover
{
    static int Main(string[] args)
    {
        if (args.Length < 1) {
            Console.Error.WriteLine("usage: Discover <assembly.dll> [filter-namespace]");
            return 2;
        }
        string path = args[0];
        string nsFilter = args.Length > 1 ? args[1] : null;

        // Load all DLLs in the same directory first so dependencies resolve
        var dir = Path.GetDirectoryName(Path.GetFullPath(path));
        AppDomain.CurrentDomain.AssemblyResolve += (s, e) => {
            var name = new AssemblyName(e.Name).Name;
            var p = Path.Combine(dir, name + ".dll");
            return File.Exists(p) ? Assembly.LoadFrom(p) : null;
        };

        var asm = Assembly.LoadFrom(path);
        Console.WriteLine($"# {asm.FullName}");
        Console.WriteLine();

        Type[] allTypes;
        try { allTypes = asm.GetTypes(); }
        catch (ReflectionTypeLoadException ex) {
            Console.Error.WriteLine($"# WARN: ReflectionTypeLoadException — {ex.LoaderExceptions.Length} types failed to load. Continuing with the {ex.Types.Count(t => t != null)} that did.");
            allTypes = ex.Types.Where(t => t != null).ToArray();
        }

        var types = allTypes
            .Where(t => nsFilter == null || (t.FullName ?? "").StartsWith(nsFilter))
            .OrderBy(t => t.FullName);

        string lastNs = null;
        foreach (var t in types) {
            if (t.IsNested) continue;

            if (t.Namespace != lastNs) {
                Console.WriteLine();
                Console.WriteLine($"// ====== namespace {t.Namespace} ======");
                Console.WriteLine();
                lastNs = t.Namespace;
            }
            EmitType(t, "");
            Console.WriteLine();
        }
        return 0;
    }

    static void EmitType(Type t, string indent)
    {
        string kind = t.IsEnum ? "enum" : t.IsInterface ? "interface" :
                     t.IsValueType ? "struct" : t.IsAbstract && t.IsSealed ? "static class" :
                     t.IsAbstract ? "abstract class" : "class";
        string vis = t.IsPublic || t.IsNestedPublic ? "public" :
                    t.IsNestedFamily ? "protected" : "internal";
        string baseT = t.BaseType != null && t.BaseType != typeof(object) && t.BaseType != typeof(ValueType) && t.BaseType != typeof(Enum)
            ? " : " + Friendly(t.BaseType) : "";
        var interfaces = t.GetInterfaces().Select(i => Friendly(i));
        if (interfaces.Any()) baseT += (baseT == "" ? " : " : ", ") + string.Join(", ", interfaces);

        Console.WriteLine($"{indent}{vis} {kind} {Friendly(t)}{baseT}");
        Console.WriteLine($"{indent}{{");
        string i2 = indent + "    ";

        if (t.IsEnum) {
            foreach (var v in Enum.GetNames(t)) {
                Console.WriteLine($"{i2}{v} = {Convert.ChangeType(Enum.Parse(t, v), Enum.GetUnderlyingType(t))},");
            }
            Console.WriteLine($"{indent}}}");
            return;
        }

        var bf = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static | BindingFlags.DeclaredOnly;

        // Fields
        foreach (var f in t.GetFields(bf).Where(f => !f.Name.Contains("<") && !f.IsSpecialName)) {
            string fv = f.IsPublic ? "public " : f.IsFamily ? "protected " : "";
            if (string.IsNullOrEmpty(fv) && !f.IsPublic) continue;
            string stat = f.IsStatic ? "static " : "";
            Console.WriteLine($"{i2}{fv}{stat}{Friendly(f.FieldType)} {f.Name};");
        }

        // Constructors
        foreach (var c in t.GetConstructors(bf).Where(c => c.IsPublic)) {
            Console.WriteLine($"{i2}public {SimpleName(t)}({Params(c)});");
        }

        // Events (emit before methods, since add_/remove_ are mixed into methods)
        var events = t.GetEvents(bf).Where(e => e.AddMethod?.IsPublic == true).ToList();
        foreach (var e in events) {
            Console.WriteLine($"{i2}public event {Friendly(e.EventHandlerType)} {e.Name};");
        }
        var eventMethods = new HashSet<string>(events.SelectMany(e => new[] { "add_" + e.Name, "remove_" + e.Name }));

        // Properties
        foreach (var p in t.GetProperties(bf).Where(p => p.GetMethod?.IsPublic == true || p.SetMethod?.IsPublic == true)) {
            string g = p.GetMethod?.IsPublic == true ? "get; " : "";
            string s = p.SetMethod?.IsPublic == true ? "set; " : "";
            string stat = (p.GetMethod ?? p.SetMethod).IsStatic ? "static " : "";
            Console.WriteLine($"{i2}public {stat}{Friendly(p.PropertyType)} {p.Name} {{ {g}{s}}}");
        }
        var propMethods = new HashSet<string>(t.GetProperties(bf).SelectMany(p =>
            new[] { p.GetMethod?.Name, p.SetMethod?.Name }).Where(n => n != null));

        // Methods
        foreach (var m in t.GetMethods(bf).Where(m => m.IsPublic && !m.IsSpecialName)) {
            if (propMethods.Contains(m.Name) || eventMethods.Contains(m.Name)) continue;
            string stat = m.IsStatic ? "static " : "";
            Console.WriteLine($"{i2}public {stat}{Friendly(m.ReturnType)} {m.Name}({Params(m)});");
        }

        // Nested
        foreach (var nt in t.GetNestedTypes(bf).Where(nt => nt.IsNestedPublic)) {
            Console.WriteLine();
            EmitType(nt, i2);
        }

        Console.WriteLine($"{indent}}}");
    }

    static string Params(MethodBase m) {
        return string.Join(", ", m.GetParameters().Select(p => {
            string mod = p.IsOut ? "out " : p.ParameterType.IsByRef ? "ref " : "";
            return mod + Friendly(p.ParameterType) + " " + p.Name;
        }));
    }

    static string SimpleName(Type t) {
        var n = t.Name;
        int tick = n.IndexOf('`');
        return tick > 0 ? n.Substring(0, tick) : n;
    }

    static string Friendly(Type t) {
        if (t == null) return "?";
        if (t.IsByRef) t = t.GetElementType();
        if (t == typeof(void)) return "void";
        if (t == typeof(string)) return "string";
        if (t == typeof(bool)) return "bool";
        if (t == typeof(int)) return "int";
        if (t == typeof(uint)) return "uint";
        if (t == typeof(long)) return "long";
        if (t == typeof(byte)) return "byte";
        if (t == typeof(short)) return "short";
        if (t == typeof(object)) return "object";
        if (t.IsArray) return Friendly(t.GetElementType()) + "[]";
        if (t.IsGenericType) {
            var name = t.GetGenericTypeDefinition().Name;
            int tick = name.IndexOf('`');
            if (tick > 0) name = name.Substring(0, tick);
            return name + "<" + string.Join(",", t.GetGenericArguments().Select(Friendly)) + ">";
        }
        // Use FullName for non-system types so namespaces are visible
        if (t.Namespace != null && t.Namespace.StartsWith("Com.Raritan")) {
            return t.FullName.Replace("Com.Raritan.RcCore.", "").Replace("Com.Raritan.", "").Replace('+', '.');
        }
        return t.Name;
    }
}
