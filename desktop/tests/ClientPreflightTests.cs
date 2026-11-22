using System;
using System.IO;
using System.Text.RegularExpressions;
static class ClientPreflightTests {
 public static void Main(string[] args){
  Portable.Root=Path.GetFullPath(args[0]);Directory.CreateDirectory(Portable.Root);
  string game=Portable.P("installed-game");Directory.CreateDirectory(Path.Combine(game,"assets"));Directory.CreateDirectory(Path.Combine(game,"modassets/assets/bundle"));
  File.WriteAllText(Path.Combine(game,"classes.jar"),"fixture");File.WriteAllText(Path.Combine(game,"modassets/assets/bundle/configs.xeon"),"fixture");
  string source=File.ReadAllText(args[1]);int start=source.IndexOf("foreach(string part in new[]{");int end=source.IndexOf("})Portable.Need",start);
  foreach(Match m in Regex.Matches(source.Substring(start,end-start),"\"([^\"]+)\"")){string p=Portable.P(m.Groups[1].Value);if(Path.HasExtension(p)){Directory.CreateDirectory(Path.GetDirectoryName(p));File.WriteAllText(p,"fixture");}else Directory.CreateDirectory(p);}
  Directory.CreateDirectory(Portable.P("mods"));File.WriteAllText(Portable.P("mods/command-completion.jar"),"fixture");
  ClientForm.Check(game,"127.0.0.1:29545");
  if(!File.Exists(Portable.P("mods/command-completion.jar")))throw new Exception("preflight removed bundled mod");
  File.Delete(Portable.P("compat/game-resource-compat.jar"));bool rejected=false;try{ClientForm.Check(game,"127.0.0.1:29545");}catch(IOException){rejected=true;}if(!rejected)throw new Exception("missing agent accepted");
  Console.WriteLine("PASS client preflight: bundled mods allowed, required runtime still checked, game not launched");
 }
}
