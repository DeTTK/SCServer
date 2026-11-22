using System;using System.IO;using System.IO.Compression;using System.Threading;using System.Runtime.InteropServices;
static class SetupSafetyTests {
 [DllImport("shell32.dll",SetLastError=true)]static extern IntPtr CommandLineToArgvW([MarshalAs(UnmanagedType.LPWStr)]string command,out int count);
 [DllImport("kernel32.dll",SetLastError=true)]static extern IntPtr LocalFree(IntPtr value);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)][return:MarshalAs(UnmanagedType.U1)]static extern bool CreateSymbolicLinkW(string link,string target,uint flags);
 static int checks;
 static void Require(bool value,string why){if(!value)throw new Exception(why);checks++;}
 static void Rejected(Action action,string why){try{action();throw new Exception("Accepted: "+why);}catch(InvalidDataException){checks++;}}
 static void RejectedIo(Action action,string why){try{action();throw new Exception("Accepted: "+why);}catch(InvalidDataException){throw;}catch(IOException){checks++;}}
 static bool CreateSymbolicLink(string link,string target,uint flags){try{return CreateSymbolicLinkW(link,target,flags);}catch(EntryPointNotFoundException){return false;}catch(DllNotFoundException){return false;}}
 static bool DirectoryLink(string link,string target){
  if(CreateSymbolicLink(link,target,3)||CreateSymbolicLink(link,target,1))return true;
  // Junction creation does not require Developer Mode or the symlink privilege.
  try{var start=new System.Diagnostics.ProcessStartInfo("powershell.exe","-NoProfile -NonInteractive -Command \"New-Item -ItemType Junction -Path '"+link.Replace("'","''")+"' -Target '"+target.Replace("'","''")+"' -ErrorAction Stop | Out-Null\""){UseShellExecute=false,CreateNoWindow=true,WindowStyle=System.Diagnostics.ProcessWindowStyle.Hidden};using(var process=System.Diagnostics.Process.Start(start)){if(!process.WaitForExit(10000)){process.Kill();return false;}return process.ExitCode==0;}}catch(System.ComponentModel.Win32Exception){return false;}
 }
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
  string deepZip=Path.Combine(root,"deep.zip"),deepStage=Path.Combine(root,"unpack-deep");
  using(var z=ZipFile.Open(deepZip,ZipArchiveMode.Create))for(int i=0;i<200;i++)using(var w=new StreamWriter(z.CreateEntry("Client/assets/g"+i%7+"/s"+i%11+"/f"+i+".json").Open()))w.Write(i);
  SetupPackage.Extract(deepZip,deepStage,(s,p)=>{},CancellationToken.None);Require(Directory.GetFiles(deepStage,"*.json",SearchOption.AllDirectories).Length==200,"Repeated and implicit parent directories extracted");SetupPackage.RemoveStage(root,deepStage);
  string linkedZip=Path.Combine(root,"linked-parent.zip"),linkedStage=Path.Combine(root,"unpack-linked-parent"),linkTarget=Path.Combine(root,"outside-stage"),assetLink=Path.Combine(linkedStage,"Client/assets");
  Directory.CreateDirectory(Path.GetDirectoryName(assetLink));Directory.CreateDirectory(linkTarget);
  using(var z=ZipFile.Open(linkedZip,ZipArchiveMode.Create))using(var w=new StreamWriter(z.CreateEntry("Client/assets/newdir/payload.txt").Open()))w.Write("must not escape");
  if(!DirectoryLink(assetLink,linkTarget))throw new Exception("Cannot create a directory link for the extraction regression test");
  try{RejectedIo(()=>SetupPackage.Extract(linkedZip,linkedStage,(s,p)=>{},CancellationToken.None),"Intermediate link rejected before creating child directory");Require(!Directory.Exists(Path.Combine(linkTarget,"newdir")),"No external directory created through ancestor link");}finally{Directory.Delete(assetLink);}
  string stageLink=Path.Combine(root,"unpack-root-link");if(!DirectoryLink(stageLink,linkTarget))throw new Exception("Cannot create staging root link");
  try{RejectedIo(()=>SetupPackage.Extract(zip,stageLink,(s,p)=>{},CancellationToken.None),"Linked staging root rejected");Require(!Directory.Exists(Path.Combine(linkTarget,"Client")),"Linked staging root leaves outside target untouched");}finally{Directory.Delete(stageLink);}
  string foreign=Path.Combine(root,"existing"),save=Path.Combine(foreign,"Local/Server/data/player.json");Directory.CreateDirectory(Path.GetDirectoryName(save));File.WriteAllText(save,"preserve me");
  try{SetupPackage.Install(foreign,(s,p)=>{},CancellationToken.None);throw new Exception("Foreign install replaced");}catch(IOException){Require(File.ReadAllText(save)=="preserve me","Existing saves preserved");}
  // Linux/Wine keeps /home, /var/home and redirected profiles behind a link above the chosen folder.
  // Only links inside the chosen folder are a hazard, so the walk must start there.
  string real=Path.Combine(root,"profile-real"),outside=Path.Combine(root,"profile-link"),chosen=Path.Combine(outside,"BSCPCRAFT"),inside=Path.Combine(chosen,"link-in-inside");
  Directory.CreateDirectory(Path.Combine(real,"BSCPCRAFT"));
  if(CreateSymbolicLink(outside,real,1)){
   Directory.CreateDirectory(chosen);
   SetupPackage.NoLinks(chosen,chosen);
   Require(Directory.Exists(chosen),"Chosen folder behind a symbolic link is visible");
   RejectedIo(()=>SetupPackage.NoLinks(Path.Combine(root,"elsewhere"),chosen),"Path outside the chosen folder rejected");
   if(CreateSymbolicLink(inside,chosen,1)){
    RejectedIo(()=>SetupPackage.NoLinks(inside,chosen),"Link inside the chosen folder rejected");
    Directory.Delete(inside);
   }
   Directory.Delete(outside);
  }

  Require(!SetupPackage.NativesCurrent(root),"Missing nested native DLLs need repair");
  Rejected(()=>SetupPackage.InstallNatives(root,root,zip,false,CancellationToken.None),"Unverified native archive rejected before installation");
  string serverRoot=Path.Combine(root,"server-update"),serverCache=Path.Combine(root,"server-cache"),serverFile=Path.Combine(serverRoot,"Server/server.jar");
  Directory.CreateDirectory(Path.GetDirectoryName(serverFile));Directory.CreateDirectory(serverCache);File.WriteAllText(serverFile,"old server");
  Require(!SetupPackage.ServerCurrent(serverRoot),"Old server needs update");
  Rejected(()=>SetupPackage.InstallServer(serverRoot,serverCache,new byte[]{1,2,3},true),"Unverified server rejected");
  Require(File.ReadAllText(serverFile)=="old server","Invalid update leaves server intact");
  if(args.Length>1){
   string player=Path.Combine(serverRoot,"Server/data/player.json"),manifest=Path.Combine(serverRoot,"Server/FILES.sha256.json");Directory.CreateDirectory(Path.GetDirectoryName(player));File.WriteAllText(player,"keep character");File.WriteAllText(manifest,"[]");
   byte[] payload=File.ReadAllBytes(args[1]);SetupPackage.InstallServer(serverRoot,serverCache,payload,true);
   Require(SetupPackage.ServerCurrent(serverRoot),"Pinned server installed");Require(File.ReadAllText(Path.Combine(serverCache,"previous-server.jar"))=="old server","Rollback preserved");Require(File.ReadAllText(player)=="keep character","Characters preserved");Require(File.ReadAllText(manifest).Contains(SetupPackage.ServerHash),"Server manifest refreshed");
   SetupPackage.InstallServer(serverRoot,serverCache,payload,true);Require(File.ReadAllText(Path.Combine(serverCache,"previous-server.jar"))=="old server","Repeat update preserves rollback");
  }
  string scaleRoot=Path.Combine(root,"scale-update"),scaleCache=Path.Combine(root,"scale-cache"),scaleFile=Path.Combine(scaleRoot,"Client/compat/character-scale.jar");
  Directory.CreateDirectory(Path.GetDirectoryName(scaleFile));Directory.CreateDirectory(scaleCache);File.WriteAllText(scaleFile,"old scale");
  Require(!SetupPackage.ScaleCurrent(scaleRoot),"Missing or outdated character scale needs update");
  Rejected(()=>SetupPackage.InstallScale(scaleRoot,scaleCache,new byte[]{1,2,3},true),"Invalid scale payload rejected");
  Require(File.ReadAllText(scaleFile)=="old scale","Invalid scale update preserves component");
  if(args.Length>2){
   string manifest=Path.Combine(scaleRoot,"Client/FILES.sha256.json");File.WriteAllText(manifest,"[]");byte[] payload=File.ReadAllBytes(args[2]);
   SetupPackage.InstallScale(scaleRoot,scaleCache,payload,true);Require(SetupPackage.ScaleCurrent(scaleRoot),"Scale component installed");
   Require(File.ReadAllText(Path.Combine(scaleCache,"previous-character-scale.jar"))=="old scale","Previous scale component kept");
   Require(File.ReadAllText(manifest).Contains(SetupPackage.ScaleHash),"New scale component added to manifest");
   SetupPackage.InstallScale(scaleRoot,scaleCache,payload,true);Require(File.ReadAllText(Path.Combine(scaleCache,"previous-character-scale.jar"))=="old scale","Repeat scale update preserves rollback");
  }
  string completionRoot=Path.Combine(root,"completion-update"),completionCache=Path.Combine(root,"completion-cache"),completionFile=Path.Combine(completionRoot,"Client/mods/command-completion.jar");
  Directory.CreateDirectory(Path.GetDirectoryName(completionFile));Directory.CreateDirectory(completionCache);File.WriteAllText(completionFile,"old completion");
  Require(!SetupPackage.CompletionCurrent(completionRoot),"Missing or outdated completion needs update");
  Rejected(()=>SetupPackage.InstallCompletion(completionRoot,completionCache,new byte[]{1,2,3},true),"Invalid completion payload rejected");
  Require(File.ReadAllText(completionFile)=="old completion","Invalid completion update preserves mod");
  if(args.Length>3){
   string manifest=Path.Combine(completionRoot,"Client/FILES.sha256.json");File.WriteAllText(manifest,"[]");byte[] payload=File.ReadAllBytes(args[3]);
   SetupPackage.InstallCompletion(completionRoot,completionCache,payload,true);Require(SetupPackage.CompletionCurrent(completionRoot),"Completion installed");
   Require(File.ReadAllText(Path.Combine(completionCache,"previous-command-completion.jar"))=="old completion","Previous completion retained");
   Require(File.ReadAllText(manifest).Contains("mods/command-completion.jar"),"Completion added to client manifest");
   SetupPackage.InstallCompletion(completionRoot,completionCache,payload,true);Require(File.ReadAllText(Path.Combine(completionCache,"previous-command-completion.jar"))=="old completion","Repeat preserves completion rollback");
  }
  string gameRoot=Path.Combine(root,"game-layout");Directory.CreateDirectory(Path.Combine(gameRoot,"assets"));Directory.CreateDirectory(Path.Combine(gameRoot,"modassets"));
  InstalledGame layout;Require(!InstalledGame.TryResolve(gameRoot,out layout),"Incomplete installed game rejected");
  Action<string> touch=relative=>{string file=Path.Combine(gameRoot,relative);Directory.CreateDirectory(Path.GetDirectoryName(file));File.WriteAllText(file,"fixture");};
  touch("bin_global/classes.jar");touch("bin_global/patchassets/assets/bundle/configs.xeon");
  layout=InstalledGame.Resolve(gameRoot);Require(layout.Variant=="bin_global"&&layout.Root==gameRoot,"Steam global detected at game root");
  Require(Path.GetFullPath(layout.PatchAssets)==Path.GetFullPath(Path.Combine(gameRoot,"bin_global/patchassets")),"Steam patch assets stay in selected variant");
  Require(layout.NativeBin==null,"Game Java not required: portable runtime owns native libraries");
  touch("bin_ru/classes.jar");Require(InstalledGame.Resolve(gameRoot).Variant=="bin_global","Incomplete RU does not mix with global configs");
  touch("bin_ru/patchassets/assets/bundle/configs.xeon");Require(InstalledGame.Resolve(gameRoot).Variant=="bin_ru","Complete RU preferred at Steam root");
  Require(InstalledGame.Resolve(Path.Combine(gameRoot,"bin_global")).Variant=="bin_global","Explicit global selection preserved");
  Require(InstalledGame.Resolve(Path.Combine(gameRoot,"bin_global")+Path.DirectorySeparatorChar).Variant=="bin_global","Trailing separator handled");
  Require(InstalledGame.Resolve(Path.Combine(gameRoot,"bin_global/classes.jar")).Variant=="bin_global","Selected game file handled");
  string selected=InstalledGame.Resolve(Path.Combine(gameRoot,"bin_global")).SelectedPath;Require(InstalledGame.Resolve(selected).Variant=="bin_global","Saved selection retains realm");
  touch("classes.jar");touch("modassets/assets/bundle/configs.xeon");Require(InstalledGame.Resolve(gameRoot).Variant=="launcher","Classic layout preserved");
  Require(File.ReadAllText(Path.Combine(gameRoot,"bin_ru/patchassets/assets/bundle/configs.xeon"))=="fixture","Resolution does not modify installed game");
  Console.WriteLine("PASS "+checks+" installer safety and Windows argument checks");
 }
}
