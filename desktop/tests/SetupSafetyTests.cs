using System;using System.IO;using System.IO.Compression;using System.Threading;using System.Runtime.InteropServices;
static class SetupSafetyTests {
 [DllImport("shell32.dll",SetLastError=true)]static extern IntPtr CommandLineToArgvW([MarshalAs(UnmanagedType.LPWStr)]string command,out int count);
 [DllImport("kernel32.dll")]static extern IntPtr LocalFree(IntPtr value);
 static int checks;
 static void Require(bool value,string why){if(!value)throw new Exception(why);checks++;}
 static void Rejected(Action action,string why){try{action();throw new Exception("Accepted: "+why);}catch(InvalidDataException){checks++;}}
 static void Main(string[] args){SetupPackage.EnableLongPaths();string root=Path.GetFullPath(args[0]);Directory.CreateDirectory(root);
  foreach(string value in new[]{@"C:\folder with spaces\", "a\"b", "", @"C:\тест\игра", @"abc\\\", "nick name"}){
   foreach(string quoted in new[]{Portable.Q(value),SetupForm.Quote(value)}){
    int count;IntPtr ptr=CommandLineToArgvW("program "+quoted,out count);try{Require(count==2&&Marshal.PtrToStringUni(Marshal.ReadIntPtr(ptr,IntPtr.Size))==value,"Windows argument round trip");}finally{LocalFree(ptr);}
   }
  }
  foreach(string path in new[]{"../escape", "Client/../../escape", @"C:\escape", "/escape", @"\\server\share", "Client/./file"})Rejected(()=>SetupPackage.EntryPath(root,path),path);
  Require(SetupPackage.EntryPath(root,"Client/normal.txt").StartsWith(root),"Normal ZIP entry");
  string zip=Path.Combine(root,"test.zip"),stage=Path.Combine(root,"unpack-normal");
  using(var z=ZipFile.Open(zip,ZipArchiveMode.Create)){using(var w=new StreamWriter(z.CreateEntry("Client/normal.txt").Open()))w.Write("hello");}
  SetupPackage.Extract(zip,stage,(s,p)=>{},CancellationToken.None);Require(File.ReadAllText(Path.Combine(stage,"Client/normal.txt"))=="hello","Extraction");
  Require(SetupPackage.Digest(Path.Combine(stage,"Client/normal.txt"),CancellationToken.None)=="2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824","SHA256");
  SetupPackage.RemoveStage(root,stage);Require(!Directory.Exists(stage),"Owned stage cleanup");
  string longZip=Path.Combine(root,"long.zip"),longStage=Path.Combine(root,"unpack-long"),longEntry="Server/"+new string('a',140)+"/"+new string('b',140)+"/config.json";
  using(var z=ZipFile.Open(longZip,ZipArchiveMode.Create)){using(var w=new StreamWriter(z.CreateEntry(longEntry).Open()))w.Write("long path");}
  SetupPackage.Extract(longZip,longStage,(s,p)=>{},CancellationToken.None);Require(File.ReadAllText(SetupPackage.EntryPath(longStage,longEntry))=="long path","Long Windows paths without registry changes");SetupPackage.RemoveStage(root,longStage);
  string bad=Path.Combine(root,"bad.zip");using(var z=ZipFile.Open(bad,ZipArchiveMode.Create))z.CreateEntry("../escape");
  Rejected(()=>SetupPackage.Extract(bad,Path.Combine(root,"unpack-bad"),(s,p)=>{},CancellationToken.None),"Traversal archive");
  string symbolic=Path.Combine(root,"symbolic.zip");using(var z=ZipFile.Open(symbolic,ZipArchiveMode.Create)){var e=z.CreateEntry("Client/link");e.ExternalAttributes=unchecked((int)0xA1FF0000);}
  Rejected(()=>SetupPackage.Extract(symbolic,Path.Combine(root,"unpack-symlink"),(s,p)=>{},CancellationToken.None),"Symbolic ZIP entry");
  string foreign=Path.Combine(root,"existing"),save=Path.Combine(foreign,"Local/Server/data/player.json");Directory.CreateDirectory(Path.GetDirectoryName(save));File.WriteAllText(save,"preserve me");
  try{SetupPackage.Install(foreign,(s,p)=>{},CancellationToken.None);throw new Exception("Foreign install replaced");}catch(IOException){Require(File.ReadAllText(save)=="preserve me","Existing saves preserved");}
  Require(!SetupPackage.NativesCurrent(root),"Missing nested native DLLs need repair");
  Rejected(()=>SetupPackage.InstallNatives(root,root,zip,false,CancellationToken.None),"Unverified native archive rejected before installation");
  Console.WriteLine("PASS "+checks+" installer safety and Windows argument checks");
 }
}