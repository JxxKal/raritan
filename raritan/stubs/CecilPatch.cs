using System;
using System.IO;
using System.Linq;
using Mono.Cecil;
using Mono.Cecil.Cil;

class CecilPatch
{
    static int Main(string[] args)
    {
        if (args.Length < 2) {
            Console.Error.WriteLine("usage: CecilPatch <input.exe|dll> <output.exe|dll>");
            return 2;
        }
        string inPath = args[0];
        string outPath = args[1];

        var resolver = new DefaultAssemblyResolver();
        resolver.AddSearchDirectory(Path.GetDirectoryName(Path.GetFullPath(inPath)));
        var rp = new ReaderParameters { AssemblyResolver = resolver, ReadWrite = false };
        var asm = AssemblyDefinition.ReadAssembly(inPath, rp);

        int icoSetPatched = 0, icoBmpPatched = 0;

        foreach (var module in asm.Modules) {
            foreach (var type in module.GetTypes()) {
                foreach (var method in type.Methods) {
                    if (!method.HasBody) continue;
                    var il = method.Body.GetILProcessor();
                    var instructions = method.Body.Instructions.ToList();
                    foreach (var instr in instructions) {
                        if (instr.OpCode != OpCodes.Call && instr.OpCode != OpCodes.Callvirt) continue;
                        var mref = instr.Operand as MethodReference;
                        if (mref == null) continue;

                        // Patch out Form.set_Icon: stack is [this, icon] → replace call with pop;pop
                        if (mref.Name == "set_Icon" &&
                            mref.DeclaringType.FullName == "System.Windows.Forms.Form") {
                            var pop1 = il.Create(OpCodes.Pop);
                            var pop2 = il.Create(OpCodes.Pop);
                            il.Replace(instr, pop1);
                            il.InsertAfter(pop1, pop2);
                            icoSetPatched++;
                            Console.WriteLine($"  patched set_Icon in {type.FullName}::{method.Name}");
                        }

                        // Patch out Icon.ToBitmap: stack is [icon] → replace with pop;ldnull (returns null bitmap)
                        // Only do this if the calling context can handle null — we'll see if needed.
                        // For now leave ToBitmap alone; the offender is the set_Icon path.
                    }
                }
            }
        }

        asm.Write(outPath);
        Console.WriteLine($"Patched {icoSetPatched} set_Icon call(s), {icoBmpPatched} ToBitmap call(s)");
        Console.WriteLine($"Wrote {outPath}");
        return 0;
    }
}
