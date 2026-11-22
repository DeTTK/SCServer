using System;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Diagnostics;
using System.Drawing;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

static class SetupPackage {
    internal static void EnableLongPaths(){
        AppContext.SetSwitch("Switch.System.IO.UseLegacyPathHandling",false);
        AppContext.SetSwitch("Switch.System.IO.BlockLongPaths",false);
    }
    internal static string Extended(string path){
        string full=Path.GetFullPath(path);
        if(full.StartsWith(@"\\?\",StringComparison.Ordinal))return full;
        return full.StartsWith(@"\\",StringComparison.Ordinal)?@"\\?\UNC\"+full.Substring(2):@"\\?\"+full;
    }
    internal const string Url="https://github.com/DeTTK/SCServer/releases/download/local-ready-2026.11.20/BSCPCRAFT-local-20261119.zip";
    internal const string Hash="644af9116bf0931884e778455179137e70e088f9e1fa77c66c00aad6d505752a";
    internal const long Bytes=1312415473;
    internal const string AgentUrl="https://github.com/DeTTK/SCServer/releases/download/world-items-2026.11.22/game-resource-compat-udp-20261122.jar";
    internal const string AgentHash="0ad625606c495904d22c9eb77bc9290dec9b741f0eeb1d45cf3440b2e50412b0";
    internal const int AgentBytes=225369;
    internal const string StartupUrl="https://github.com/DeTTK/SCServer/releases/download/loading-screen-2026.11.21/startup-guard-20261121.jar";
    internal const string StartupHash="e58ef38fb32af6d025adbf71ab3e8892fd9c6faac61a5fbb356fb38ee59b4fd0";
    internal const int StartupBytes=136518;
    internal const string ServerUrl="https://github.com/DeTTK/SCServer/releases/download/linux-native-2026.11.22/server-linux-native-20261122.jar";
    internal const string ServerHash="f3ea9701c2633090786f3a6b5752991529183ee5c107f4031ba6b517fbcf06d5";
    internal const int ServerBytes=6562981;
    internal const string CompletionUrl="https://github.com/DeTTK/SCServer/releases/download/lootboxes-2026.11.22/command-completion.jar";
    internal const string CompletionHash="2b20f9beccdf59d0f977f91259702f76887e485ba44486e93233910d1a1d97e9";
    internal const int CompletionBytes=16607;
    internal const string ScaleUrl="https://github.com/DeTTK/SCServer/releases/download/character-scale-2026.11.22/character-scale.jar";
    internal const string ScaleHash="952b4a4423aa140bd2223d68335eedb58acf276bcaaea585ab6057f801f1aaeb";
    internal const int ScaleBytes=24131;
    internal const string NativeUrl="https://github.com/DeTTK/SCServer/releases/download/native-startup-2026.11.21/BSCPCRAFT-native-libraries-20261121.zip";
    internal const string NativeHash="e92212cf14279f7615c0f2c00ad3d51c798532fa735a8d0b9d6b46be3959d8fc";
    internal const int NativeBytes=15812300;
    internal static readonly System.Collections.Generic.Dictionary<string,string> NativeFiles=new System.Collections.Generic.Dictionary<string,string> {
        {"fmod/x64/fmod.dll","dad8b479225db203c7d6750f7817421680c09dfb5ff8e93f45d49499487bab28"},
        {"fmod/x64/fmodstudio.dll","fb99c94e3d51231e32923c69123dbbae4385c199411ce697318526aa8aadf032"},
        {"eos/x64/EOSSDK-Win64-Shipping.dll","4ee17bb5cb3ebb580bba995cb384d30fe24ad6a1a50074e340045dcdb3280ced"},
        {"eos/x64/xaudio2_9redist.dll","0e01f400baa09694cacfd2cfbff0d721c17b56785d2d6e29b6fdd117f70ccc1e"},
        {"stove/BaseSDK.dll","7826c5fc75aa8b0cf9b450251fcb566adc7da34142f735be146a083252b5ca89"},
        {"stove/GameSupportSDK.dll","65a23d7b9b073ecc2ffced5844d4eead588606bc3648d9843674abcae807076a"},
        {"stove/IAPSDK.dll","82a34ff749a60719b9359a2666605af29ae9c34984ca8e959975c2fda0959039"},
        {"stove/LogSDK.dll","70bd612686bfc07aa74d14f0c1dfe218ec2c4ffc481530294e4b96a030c2644c"},
        {"stove/OwnershipSDK.dll","01c524cc0c7dcdadf7d44a85d1188a8be7b0a3546fa366b8008655509f316aa4"},
        {"stove/PCBangSDK.dll","bbe4c14a321a1c78dc89a091ab73fce9743e5adcc9ef7ac85eff18095723ddbb"},
        {"stove/WebView2Loader.dll","74f16550da608ec233a3e54871ec72657dff34cdef068193c1a7b554b670a1a3"},
    };
    static readonly string[] Required={"Играть.exe","Client/Launcher.exe","Client/jre/bin/javaw.exe","Server/server.jar","Server/jre/bin/java.exe","Map/registry.json","Map/region"};
    static readonly string[] ToolNames={"BootLocal","BootClient","BootServer"};
    static readonly string[] ToolPaths={"Играть.exe","Client/Launcher.exe","Server/ServerManager.exe"};
    static byte[] Tool(string name) {
        using(var input=System.Reflection.Assembly.GetExecutingAssembly().GetManifestResourceStream(name)) {
            if(input==null)throw new InvalidDataException("Установщик собран не полностью: отсутствует "+name);
            using(var output=new MemoryStream()){input.CopyTo(output);return output.ToArray();}
        }
    }
    static string ToolDigest(byte[] raw){using(var sha=SHA256.Create())return BitConverter.ToString(sha.ComputeHash(raw)).Replace("-","").ToLowerInvariant();}
    static string PackageMarker(){return Hash+":"+String.Join(":",ToolNames.Select(name=>ToolDigest(Tool(name))))+":"+AgentHash+":"+NativeHash+":"+StartupHash+":"+ServerHash+":"+ScaleHash+":"+CompletionHash;}
    static byte[] DownloadSmall(string cache,string name,string url,string hash,int bytes,Action<string,int> progress,CancellationToken cancel) {
        string path=Path.Combine(cache,name+"-"+hash+".jar");NoLinks(path,cache);
        if(File.Exists(path)&&new FileInfo(path).Length==bytes&&Digest(path,cancel)==hash)return File.ReadAllBytes(path);
        progress("Скачивается обновление загрузки ресурсов…",1);ServicePointManager.SecurityProtocol=SecurityProtocolType.Tls12;
        var request=(HttpWebRequest)WebRequest.Create(url);request.UserAgent="BSCPCRAFT-Setup/2";request.Timeout=30000;request.ReadWriteTimeout=30000;
        using(cancel.Register(()=>request.Abort()))using(var response=request.GetResponse())using(var input=response.GetResponseStream())using(var memory=new MemoryStream()) {
            byte[] buffer=new byte[16384];int n;
            while((n=input.Read(buffer,0,buffer.Length))>0){cancel.ThrowIfCancellationRequested();if(memory.Length+n>bytes)throw new IOException("Обновление ресурсов больше ожидаемого");memory.Write(buffer,0,n);}
            byte[] raw=memory.ToArray();if(raw.Length!=bytes||ToolDigest(raw)!=hash)throw new IOException("Не прошла проверка обновления ресурсов. Повторите установку.");
            File.WriteAllBytes(path,raw);return raw;
        }
    }
    static byte[] DownloadAgent(string cache,Action<string,int> progress,CancellationToken cancel){return DownloadSmall(cache,"resource-agent",AgentUrl,AgentHash,AgentBytes,progress,cancel);}
    static byte[] DownloadStartup(string cache,Action<string,int> progress,CancellationToken cancel){return DownloadSmall(cache,"startup-agent",StartupUrl,StartupHash,StartupBytes,progress,cancel);}
    internal static bool ScaleCurrent(string target){string path=Path.Combine(target,"Client/compat/character-scale.jar");NoLinks(path,target);return File.Exists(path)&&Digest(path,CancellationToken.None)==ScaleHash;}
    internal static void InstallScale(string target,string cache,byte[] raw,bool updating){if(raw.Length!=ScaleBytes||ToolDigest(raw)!=ScaleHash)throw new InvalidDataException("Не прошла проверка обновления размера персонажа");Directory.CreateDirectory(Path.Combine(target,"Client/compat"));InstallCompatibility(target,cache,raw,updating,"character-scale.jar",ScaleHash,"previous-character-scale.jar");}
    internal static bool CompletionCurrent(string target){string path=Path.Combine(target,"Client/mods/command-completion.jar");NoLinks(path,target);return File.Exists(path)&&Digest(path,CancellationToken.None)==CompletionHash;}
    internal static void InstallCompletion(string target,string cache,byte[] raw,bool updating){if(raw.Length!=CompletionBytes||ToolDigest(raw)!=CompletionHash)throw new InvalidDataException("Не прошла проверка автодополнения команд");InstallCompatibility(target,cache,raw,updating,"command-completion.jar",CompletionHash,"previous-command-completion.jar","mods");}
    static bool StartupCurrent(string target){string path=Path.Combine(target,"Client/compat/startup-guard.jar");NoLinks(path,target);return File.Exists(path)&&Digest(path,CancellationToken.None)==StartupHash;}
    internal static bool ServerCurrent(string target){string path=Path.Combine(target,"Server/server.jar");NoLinks(path,target);return File.Exists(path)&&Digest(path,CancellationToken.None)==ServerHash;}
    internal static void InstallServer(string target,string cache,byte[] raw,bool updating) {
        if(raw.Length!=ServerBytes||ToolDigest(raw)!=ServerHash)throw new InvalidDataException("Не прошла проверка обновления сервера");
        string file=Path.Combine(target,"Server/server.jar");NoLinks(file,target);
        if(ServerCurrent(target))return;
        if(updating&&File.Exists(file)){string backup=Path.Combine(cache,"previous-server.jar");NoLinks(backup,cache);File.Copy(file,backup,true);}
        string temporary=file+".incoming-"+Guid.NewGuid().ToString("N");
        try{File.WriteAllBytes(Extended(temporary),raw);if(File.Exists(file))File.Replace(Extended(temporary),Extended(file),null);else File.Move(Extended(temporary),Extended(file));}
        finally{if(File.Exists(Extended(temporary)))File.Delete(Extended(temporary));}
        string manifest=Path.Combine(target,"Server/FILES.sha256.json");NoLinks(manifest,target);
        if(File.Exists(manifest)) {
            var serializer=new System.Web.Script.Serialization.JavaScriptSerializer{MaxJsonLength=64*1024*1024};
            var rows=serializer.Deserialize<System.Collections.Generic.List<System.Collections.Generic.Dictionary<string,object>>>(File.ReadAllText(manifest));
            var row=rows.FirstOrDefault(x=>(string)x["path"]=="server.jar");
            if(row==null){row=new System.Collections.Generic.Dictionary<string,object>{{"path","server.jar"}};rows.Add(row);}
            row["size"]=raw.Length;row["sha256"]=ServerHash;File.WriteAllText(manifest,serializer.Serialize(rows));
        }
    }
    static void NoRunningClients(string root) {
        string prefix=Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar)+Path.DirectorySeparatorChar;
        int self=Process.GetCurrentProcess().Id;
        foreach(var process in Process.GetProcesses())using(process) {
            if(process.Id==self)continue;string file=null;
            try{var module=process.MainModule;if(module!=null)file=module.FileName;}catch(System.ComponentModel.Win32Exception){}catch(InvalidOperationException){}catch(NotSupportedException){}
            if(file!=null&&file.StartsWith(prefix,StringComparison.OrdinalIgnoreCase))throw new IOException("Закройте игру, Играть.exe и окно сервера из этой локалки, затем повторите обновление. Сохранения не меняются.");
        }
    }
    static void InstallCompatibility(string target,string cache,byte[] raw,bool updating,string name,string hash,string backupName,string folder="compat") {
        string file=Path.Combine(target,"Client/"+folder+"/"+name);NoLinks(file,target);Directory.CreateDirectory(Path.GetDirectoryName(file));
        if(File.Exists(file)&&Digest(file,CancellationToken.None)==hash)return;
        if(updating&&File.Exists(file)){string backup=Path.Combine(cache,backupName);NoLinks(backup,cache);File.Copy(file,backup,true);}
        string temporary=file+".incoming-"+Guid.NewGuid().ToString("N");
        try{File.WriteAllBytes(Extended(temporary),raw);if(File.Exists(file))File.Replace(Extended(temporary),Extended(file),null);else File.Move(Extended(temporary),Extended(file));}
        finally{if(File.Exists(Extended(temporary)))File.Delete(Extended(temporary));}
        string manifest=Path.Combine(target,"Client/FILES.sha256.json");
        if(File.Exists(manifest)) {
            var serializer=new System.Web.Script.Serialization.JavaScriptSerializer{MaxJsonLength=64*1024*1024};
            var rows=serializer.Deserialize<System.Collections.Generic.List<System.Collections.Generic.Dictionary<string,object>>>(File.ReadAllText(manifest));
            var row=rows.FirstOrDefault(x=>(string)x["path"]==folder+"/"+name);if(row==null){row=new System.Collections.Generic.Dictionary<string,object>{{"path",folder+"/"+name}};rows.Add(row);}row["size"]=raw.Length;row["sha256"]=hash;
            File.WriteAllText(manifest,serializer.Serialize(rows));
        }
    }
    static void InstallAgent(string target,string cache,byte[] raw,bool updating){InstallCompatibility(target,cache,raw,updating,"game-resource-compat.jar",AgentHash,"previous-resource-agent.jar");}
    static void InstallStartup(string target,string cache,byte[] raw,bool updating){InstallCompatibility(target,cache,raw,updating,"startup-guard.jar",StartupHash,"previous-startup-agent.jar");}
    internal static bool NativesCurrent(string target) {
        foreach(var row in NativeFiles){string path=Path.Combine(target,"Client/runtime/natives",row.Key);NoLinks(path,target);if(!File.Exists(path)||Digest(path,CancellationToken.None)!=row.Value)return false;}return true;
    }
    static bool ToolsCurrent(string target) {
        for(int i=0;i<ToolNames.Length;i++){string path=Path.Combine(target,ToolPaths[i]);NoLinks(path,target);if(!File.Exists(path)||Digest(path,CancellationToken.None)!=ToolDigest(Tool(ToolNames[i])))return false;}return true;
    }
    static string DownloadNatives(string cache,Action<string,int> progress,CancellationToken cancel) {
        string path=Path.Combine(cache,"native-"+NativeHash+".zip");NoLinks(path,cache);
        if(File.Exists(path)&&new FileInfo(path).Length==NativeBytes&&Digest(path,cancel)==NativeHash)return path;
        progress("Скачиваются недостающие библиотеки клиента (16 МБ)…",5);ServicePointManager.SecurityProtocol=SecurityProtocolType.Tls12;
        var request=(HttpWebRequest)WebRequest.Create(NativeUrl);request.UserAgent="BSCPCRAFT-Setup/3";request.Timeout=30000;request.ReadWriteTimeout=30000;
        try {
            using(cancel.Register(()=>request.Abort()))using(var response=request.GetResponse())using(var input=response.GetResponseStream())using(var output=new FileStream(path,FileMode.Create,FileAccess.Write,FileShare.None)) {
                byte[] buffer=new byte[65536];int n;long total=0;while((n=input.Read(buffer,0,buffer.Length))>0){cancel.ThrowIfCancellationRequested();total+=n;if(total>NativeBytes)throw new InvalidDataException("Неверный размер библиотек клиента");output.Write(buffer,0,n);}
            }
            if(new FileInfo(path).Length!=NativeBytes||Digest(path,cancel)!=NativeHash)throw new InvalidDataException("Не прошла проверка библиотек клиента. Повторите обновление.");return path;
        }catch{if(File.Exists(path))File.Delete(path);throw;}
    }
    internal static void InstallNatives(string target,string cache,string archive,bool updating,CancellationToken cancel) {
        // Verify the complete allowlisted payload before changing any installed file.
        if(Digest(archive,cancel)!=NativeHash)throw new InvalidDataException("Неверная подпись архива библиотек");
        var payload=new System.Collections.Generic.Dictionary<string,byte[]>();
        using(var zip=ZipFile.OpenRead(archive))foreach(var entry in zip.Entries){
            string expected;if(!NativeFiles.TryGetValue(entry.FullName,out expected)||payload.ContainsKey(entry.FullName)||entry.Length>25*1024*1024)throw new InvalidDataException("Неожиданный файл в архиве библиотек");
            using(var input=entry.Open())using(var output=new MemoryStream()){input.CopyTo(output);byte[] raw=output.ToArray();if(ToolDigest(raw)!=expected)throw new InvalidDataException("Повреждена библиотека "+entry.FullName);payload.Add(entry.FullName,raw);}
        }
        if(payload.Count!=NativeFiles.Count)throw new InvalidDataException("В архиве не хватает библиотек");
        foreach(var row in payload){
            cancel.ThrowIfCancellationRequested();string path=Path.Combine(target,"Client/runtime/natives",row.Key);NoLinks(path,target);if(File.Exists(path)&&Digest(path,cancel)==NativeFiles[row.Key])continue;
            if(updating&&File.Exists(path)){string backup=Path.Combine(cache,"previous-natives",row.Key);NoLinks(backup,cache);Directory.CreateDirectory(Path.GetDirectoryName(backup));File.Copy(path,backup,true);}
            Directory.CreateDirectory(Path.GetDirectoryName(path));string temporary=path+".incoming-"+Guid.NewGuid().ToString("N");
            try{File.WriteAllBytes(Extended(temporary),row.Value);if(File.Exists(path))File.Replace(Extended(temporary),Extended(path),null);else File.Move(Extended(temporary),Extended(path));}
            finally{if(File.Exists(Extended(temporary)))File.Delete(Extended(temporary));}
        }
        string manifest=Path.Combine(target,"Client/FILES.sha256.json");NoLinks(manifest,target);
        if(File.Exists(manifest)){
            var serializer=new System.Web.Script.Serialization.JavaScriptSerializer{MaxJsonLength=64*1024*1024};var rows=serializer.Deserialize<System.Collections.Generic.List<System.Collections.Generic.Dictionary<string,object>>>(File.ReadAllText(manifest));
            foreach(var row in payload){string path="runtime/natives/"+row.Key;var existing=rows.FirstOrDefault(x=>(string)x["path"]==path);if(existing==null){existing=new System.Collections.Generic.Dictionary<string,object>{{"path",path}};rows.Add(existing);}existing["size"]=row.Value.Length;existing["sha256"]=NativeFiles[row.Key];}
            File.WriteAllText(manifest,serializer.Serialize(rows));
        }
    }
    static void ApplyTools(string stage) {
        for(int i=0;i<ToolNames.Length;i++)File.WriteAllBytes(Path.Combine(stage,ToolPaths[i]),Tool(ToolNames[i]));
        var serializer=new System.Web.Script.Serialization.JavaScriptSerializer{MaxJsonLength=64*1024*1024};
        foreach(string role in new[]{"Client","Server"}) {
            string path=Path.Combine(stage,role,"FILES.sha256.json");
            var rows=serializer.Deserialize<System.Collections.Generic.List<System.Collections.Generic.Dictionary<string,object>>>(File.ReadAllText(path));
            string name=role=="Client"?"Launcher.exe":"ServerManager.exe";
            foreach(var row in rows)if((string)row["path"]==name){string file=Path.Combine(stage,role,name);row["size"]=new FileInfo(file).Length;row["sha256"]=ToolDigest(File.ReadAllBytes(file));}
            File.WriteAllText(path,serializer.Serialize(rows));
        }
    }
    static bool Linked(string path) {return (Directory.Exists(Extended(path))||File.Exists(Extended(path)))&&(File.GetAttributes(Extended(path))&FileAttributes.ReparsePoint)!=0;}
    /** Drop the extended prefix: directory enumeration returns it, and comparisons must stay prefix-agnostic. */
    static string Plain(string path) {
        string full=Path.GetFullPath(path);
        if(full.StartsWith(@"\\?\UNC\",StringComparison.Ordinal))full=@"\\"+full.Substring(8);
        else if(full.StartsWith(@"\\?\",StringComparison.Ordinal))full=full.Substring(4);
        return full.TrimEnd(Path.DirectorySeparatorChar,Path.AltDirectorySeparatorChar);
    }
    /** Only links below the chosen folder matter: /home on Linux/Wine and redirected Windows profiles are the user's own layout. */
    internal static void NoLinks(string path,string root) {
        string current=Plain(path),boundary=Plain(root);
        if(!String.Equals(current,boundary,StringComparison.OrdinalIgnoreCase)
            &&!current.StartsWith(boundary+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase)
            &&!current.StartsWith(boundary+Path.AltDirectorySeparatorChar,StringComparison.OrdinalIgnoreCase))
            throw new IOException("Путь вне папки установки.");
        while(!String.IsNullOrEmpty(current)&&current.Length>boundary.Length) {
            if(Linked(current))throw new IOException("В пути есть ссылка на другую папку. Выберите обычную папку установки.");
            current=Path.GetDirectoryName(current);current=current==null?null:Plain(current);
        }
    }
    internal static string EntryPath(string root,string name) {
        if(String.IsNullOrWhiteSpace(name)||Path.IsPathRooted(name)||name.Contains(':'))throw new InvalidDataException("Небезопасный путь в архиве");
        var parts=name.Replace('\\','/').Split('/');
        if(parts.Any(p=>p==".."||p=="."))throw new InvalidDataException("Выход за папку распаковки");
        string path=Path.GetFullPath(Path.Combine(root,name.Replace('/',Path.DirectorySeparatorChar)));
        string prefix=Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar)+Path.DirectorySeparatorChar;
        if(!path.StartsWith(prefix,StringComparison.OrdinalIgnoreCase))throw new InvalidDataException("Выход за папку распаковки");
        return path.Length>=240?Extended(path):path;
    }
    internal static string Digest(string path,CancellationToken cancel) {
        using(var sha=SHA256.Create())using(var input=File.OpenRead(path)) {
            byte[] buffer=new byte[1024*1024];int count;
            while((count=input.Read(buffer,0,buffer.Length))>0){cancel.ThrowIfCancellationRequested();sha.TransformBlock(buffer,0,count,buffer,0);}
            sha.TransformFinalBlock(new byte[0],0,0);return BitConverter.ToString(sha.Hash).Replace("-","").ToLowerInvariant();
        }
    }
    static bool Complete(string root) {return Required.All(p=>File.Exists(Path.Combine(root,p))||Directory.Exists(Path.Combine(root,p)));}
    static bool KnownManualPackage(string root,CancellationToken cancel) {
        string file=Path.Combine(root,"Client/compat/game-resource-compat.jar");NoLinks(file,root);
        if(!File.Exists(file))return false;string hash=Digest(file,cancel);
        return hash==AgentHash||hash=="a289ba5229a24d45294199bcd020ed33bd6dccaa305f9740f07ea341eb2e34b0"||hash=="602f6e72b6d1843e79004add6a3c133415b1d07ff4d01360037870e112a9798d"||hash=="7d717f201ced1f2c32bbfdfa8177e5452e6bd9c1331e7720ad40d7905c3792be";
    }
    static void Download(string file,Action<string,int> progress,CancellationToken cancel) {
        ServicePointManager.SecurityProtocol=SecurityProtocolType.Tls12;
        long existing=File.Exists(file)?new FileInfo(file).Length:0;
        if(existing>Bytes){File.Delete(file);existing=0;}
        if(existing==Bytes)return;
        var request=(HttpWebRequest)WebRequest.Create(Url);request.UserAgent="BSCPCRAFT-Setup/1";request.Timeout=30000;request.ReadWriteTimeout=30000;
        if(existing>0)request.AddRange(existing);
        using(cancel.Register(()=>request.Abort()))using(var response=(HttpWebResponse)request.GetResponse()) {
            if(response.ResponseUri.Scheme!="https")throw new IOException("Загрузка должна идти через HTTPS");
            if(response.StatusCode==HttpStatusCode.PartialContent){
                string prefix="bytes "+existing+"-";
                if(!(response.Headers["Content-Range"]??"").StartsWith(prefix,StringComparison.Ordinal))throw new IOException("Сервер прислал неверную часть архива");
            }else if(response.StatusCode==HttpStatusCode.OK)existing=0;
            else throw new IOException("Не удалось скачать архив: HTTP "+(int)response.StatusCode);
            using(var input=response.GetResponseStream())using(var output=new FileStream(file,existing==0?FileMode.Create:FileMode.Append,FileAccess.Write,FileShare.None)) {
                byte[] buffer=new byte[1024*1024];int count;long total=existing;var clock=Stopwatch.StartNew();
                while((count=input.Read(buffer,0,buffer.Length))>0) {
                    cancel.ThrowIfCancellationRequested();if(count>Bytes-total)throw new IOException("Архив оказался больше ожидаемого");output.Write(buffer,0,count);total+=count;
                    if(clock.ElapsedMilliseconds>250){progress("Скачивается комплект: "+(total/1048576)+" / "+(Bytes/1048576)+" МБ",(int)(total*75/Bytes));clock.Restart();}
                }
                if(total!=Bytes)throw new IOException("Загрузка оборвалась. Нажмите ещё раз — продолжим с сохранённого места.");
            }
        }
    }
    // Cache path parsing and directory creation, never the result of link checks.
    // Every entry rechecks all ancestors, including previously used directories.
    static void ExtractDirectory(string path,string stage,System.Collections.Generic.Dictionary<string,string[]> chains) {
        string key=Plain(path);string[] chain;bool known=chains.TryGetValue(key,out chain);
        if(!known){
            NoLinks(key,stage);var parts=new System.Collections.Generic.List<string>();string current=key;
            while(!String.Equals(current,stage,StringComparison.OrdinalIgnoreCase)){parts.Add(current);current=Path.GetDirectoryName(current);}
            parts.Add(stage);parts.Reverse();chain=parts.ToArray();
        }
        foreach(string directory in chain){
            if(Linked(directory))throw new IOException("В пути есть ссылка на другую папку. Выберите обычную папку установки.");
            if(!known){Directory.CreateDirectory(Extended(directory));if(Linked(directory))throw new IOException("В пути есть ссылка на другую папку. Выберите обычную папку установки.");}
        }
        if(!known){if(chains.Count>=4096)chains.Clear();chains[key]=chain;}
    }
    internal static void Extract(string archive,string stage,Action<string,int> progress,CancellationToken cancel) {
        stage=Plain(stage);if(Linked(stage))throw new IOException("Временная папка не должна быть ссылкой.");Directory.CreateDirectory(Extended(stage));
        var chains=new System.Collections.Generic.Dictionary<string,string[]>(StringComparer.OrdinalIgnoreCase);
        using(var zip=ZipFile.OpenRead(archive)) {
            if(zip.Entries.Count>500000)throw new InvalidDataException("Слишком много файлов в архиве");
            long sum=0;int done=0;var clock=Stopwatch.StartNew();
            foreach(var entry in zip.Entries) {
                cancel.ThrowIfCancellationRequested();sum=checked(sum+entry.Length);
                if(sum>5L*1024*1024*1024)throw new InvalidDataException("Слишком большой распакованный архив");
                int mode=(entry.ExternalAttributes>>16)&0xF000;
                if(mode!=0&&mode!=0x8000&&mode!=0x4000)throw new InvalidDataException("Ссылки в архиве не поддерживаются");
                string path=EntryPath(stage,entry.FullName);
                if(String.IsNullOrEmpty(entry.Name))ExtractDirectory(path,stage,chains);
                else {
                    ExtractDirectory(Path.GetDirectoryName(path),stage,chains);
                    if(Linked(path))throw new IOException("В пути есть ссылка на другую папку. Выберите обычную папку установки.");
                    using(var input=entry.Open())using(var output=new FileStream(Extended(path),FileMode.CreateNew,FileAccess.Write,FileShare.None))input.CopyTo(output,1024*1024);
                }
                done++;if(clock.ElapsedMilliseconds>250){progress("Распаковывается: "+done+" / "+zip.Entries.Count+" файлов",80+(done*18/zip.Entries.Count));clock.Restart();}
            }
        }
    }
    internal static void RemoveStage(string cache,string stage) {
        if(!Directory.Exists(stage))return;
        if(!String.Equals(Path.GetDirectoryName(Path.GetFullPath(stage)),Path.GetFullPath(cache),StringComparison.OrdinalIgnoreCase)||!Path.GetFileName(stage).StartsWith("unpack-",StringComparison.Ordinal))throw new IOException("Неверный временный путь");
        NoLinks(stage,cache);
        foreach(string child in Directory.EnumerateDirectories(Extended(stage),"*",SearchOption.AllDirectories))NoLinks(child,stage);
        Directory.Delete(Extended(stage),true);
    }
    internal static string Install(string folder,Action<string,int> progress,CancellationToken cancel) {
        folder=Path.GetFullPath(folder);NoLinks(folder,folder);Directory.CreateDirectory(folder);
        bool manual=Complete(folder);
        string cache=Path.Combine(folder,".setup"),target=manual?folder:Path.Combine(folder,"Local"),marker=Path.Combine(target,".installed-package.sha256"),identity=PackageMarker();
        NoLinks(cache,folder);NoLinks(target,folder);Directory.CreateDirectory(cache);
        using(var lease=new FileStream(Path.Combine(cache,"setup.lock"),FileMode.OpenOrCreate,FileAccess.ReadWrite,FileShare.None)) {
            if(Directory.Exists(target)) {
                string installed=File.Exists(marker)?File.ReadAllText(marker).Trim():"";
                bool owned=installed==Hash||installed.StartsWith(Hash+":",StringComparison.Ordinal);
                if(Complete(target)&&(owned||(manual&&KnownManualPackage(target,cancel)))){
                    string agent=Path.Combine(target,"Client/compat/game-resource-compat.jar");NoLinks(agent,target);
                    if(!File.Exists(agent)||Digest(agent,cancel)!=AgentHash||!NativesCurrent(target)||!ToolsCurrent(target)||!StartupCurrent(target)||!ServerCurrent(target)||!ScaleCurrent(target)||!CompletionCurrent(target)){
                        NoRunningClients(target);byte[] update=DownloadAgent(cache,progress,cancel);cancel.ThrowIfCancellationRequested();
                        byte[] startupUpdate=StartupCurrent(target)?null:DownloadStartup(cache,progress,cancel);string nativeArchive=NativesCurrent(target)?null:DownloadNatives(cache,progress,cancel);
                        byte[] serverUpdate=ServerCurrent(target)?null:DownloadSmall(cache,"server",ServerUrl,ServerHash,ServerBytes,progress,cancel);
                        byte[] scaleUpdate=ScaleCurrent(target)?null:DownloadSmall(cache,"character-scale",ScaleUrl,ScaleHash,ScaleBytes,progress,cancel);
                        byte[] completionUpdate=CompletionCurrent(target)?null:DownloadSmall(cache,"command-completion",CompletionUrl,CompletionHash,CompletionBytes,progress,cancel);
                        NoRunningClients(target);InstallAgent(target,cache,update,true);if(startupUpdate!=null)InstallStartup(target,cache,startupUpdate,true);if(nativeArchive!=null)InstallNatives(target,cache,nativeArchive,true,cancel);if(!ToolsCurrent(target)){for(int i=0;i<ToolPaths.Length;i++){string previous=Path.Combine(cache,"previous-launcher-tools",ToolPaths[i]);NoLinks(previous,cache);Directory.CreateDirectory(Path.GetDirectoryName(previous));File.Copy(Path.Combine(target,ToolPaths[i]),previous,true);}ApplyTools(target);}
                        if(serverUpdate!=null)InstallServer(target,cache,serverUpdate,true);if(scaleUpdate!=null)InstallScale(target,cache,scaleUpdate,true);if(completionUpdate!=null)InstallCompletion(target,cache,completionUpdate,true);
                        File.WriteAllText(marker,identity);
                        progress("Обновление установлено. Мир и персонажи сохранены.",100);
                    }else progress("Комплект уже установлен",100);
                    return target;
                }
                throw new IOException("В выбранной папке уже есть Local без отметки этой установки. Чтобы сохранить ваши файлы, выберите другую папку.");
            }
            string partial=Path.Combine(cache,"package-"+Hash+".partial");
            long cached=File.Exists(partial)?new FileInfo(partial).Length:0;
            if(new DriveInfo(Path.GetPathRoot(folder)).AvailableFreeSpace<5L*1024*1024*1024-Math.Min(cached,Bytes))throw new IOException("Для установки нужно около 5 ГБ свободного места. Выберите другой диск.");
            byte[] compatibleAgent=DownloadAgent(cache,progress,cancel);byte[] startupAgent=DownloadStartup(cache,progress,cancel);string nativePackage=DownloadNatives(cache,progress,cancel);
            byte[] serverPackage=DownloadSmall(cache,"server",ServerUrl,ServerHash,ServerBytes,progress,cancel);
            byte[] scalePackage=DownloadSmall(cache,"character-scale",ScaleUrl,ScaleHash,ScaleBytes,progress,cancel);
            byte[] completionPackage=DownloadSmall(cache,"command-completion",CompletionUrl,CompletionHash,CompletionBytes,progress,cancel);
            Download(partial,progress,cancel);progress("Проверяется скачанный архив…",76);
            if(Digest(partial,cancel)!=Hash){File.Delete(partial);throw new IOException("Архив скачался с ошибкой. Он удалён; нажмите установить ещё раз.");}
            string stage=Path.Combine(cache,"unpack-"+Guid.NewGuid().ToString("N"));
            try {
                Extract(partial,stage,progress,cancel);
                if(!Complete(stage))throw new InvalidDataException("В архиве не хватает файлов клиента, сервера или карты");
                ApplyTools(stage);InstallAgent(stage,cache,compatibleAgent,false);InstallStartup(stage,cache,startupAgent,false);InstallNatives(stage,cache,nativePackage,false,cancel);InstallServer(stage,cache,serverPackage,false);InstallScale(stage,cache,scalePackage,false);InstallCompletion(stage,cache,completionPackage,false);File.WriteAllText(Path.Combine(stage,".installed-package.sha256"),identity);cancel.ThrowIfCancellationRequested();Directory.Move(stage,target);
            }finally{RemoveStage(cache,stage);}
            File.Delete(partial);progress("Всё готово",100);return target;
        }
    }
}
static class SetupProgram {
    [STAThread] static void Main(string[] args) {
        SetupPackage.EnableLongPaths();
        if(args.Length==2&&args[0]=="--install-only") {
            Console.SetOut(new StreamWriter(Console.OpenStandardOutput(),new System.Text.UTF8Encoding(false)){AutoFlush=true});
            Console.SetError(new StreamWriter(Console.OpenStandardError(),new System.Text.UTF8Encoding(false)){AutoFlush=true});
            try {string target=SetupPackage.Install(args[1],(s,p)=>Console.WriteLine(p+"% "+s),CancellationToken.None);Console.WriteLine("INSTALLED "+target);}
            catch(Exception e){Console.Error.WriteLine(e.ToString());Environment.ExitCode=1;}return;
        }
        Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);Application.Run(new SetupForm());
    }
}
sealed class SetupForm:Form {
    readonly TextBox folder=new TextBox(),game=new TextBox(),login=new TextBox();readonly Button install=new Button(),cancel=new Button();
    readonly Label status=new Label();readonly ProgressBar progress=new ProgressBar();CancellationTokenSource cancellation;bool closing;
    public SetupForm() {
        Text="Установка локалки Бскпкрафт";ClientSize=new Size(680,425);FormBorderStyle=FormBorderStyle.FixedDialog;MaximizeBox=false;Font=new Font("Segoe UI",10);StartPosition=FormStartPosition.CenterScreen;
        Controls.Add(new Label{Text="Скачает клиент, сервер, Java и карту. Нужна уже установленная игра.",Left=20,Top=15,Width=640,Height=40});
        Add("Куда поставить локалку",folder,65);folder.Text=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"BSCPCRAFT");
        Add("Папка установленной игры (classes.jar, assets и modassets внутри)",game,137);
        Add("Ваш ник",login,209);login.Text="Player";
        install.Text="Скачать, установить и играть";install.SetBounds(20,282,470,40);Controls.Add(install);
        cancel.Text="Отмена";cancel.SetBounds(500,282,160,40);cancel.Enabled=false;Controls.Add(cancel);
        progress.SetBounds(20,337,640,20);Controls.Add(progress);status.SetBounds(20,366,640,48);status.Text="Скачается около 1,3 ГБ. Для установки нужно около 5 ГБ места.";Controls.Add(status);
        install.Click+=async(s,e)=>await Start();cancel.Click+=(s,e)=>{if(cancellation!=null){status.Text="Отменяется установка. Убираются временные файлы…";cancellation.Cancel();}};
        FormClosing+=(s,e)=>{if(cancellation!=null){closing=true;e.Cancel=true;cancellation.Cancel();status.Text="Останавливается загрузка…";}};
    }
    void Add(string title,TextBox box,int y) {
        Controls.Add(new Label{Text=title,Left=20,Top=y,Width=640,Height=24});box.SetBounds(20,y+25,530,28);Controls.Add(box);
        if(box==login){box.Width=640;return;}
        var browse=new Button{Text="Выбрать…",Left=560,Top=y+24,Width=100,Height=30};Controls.Add(browse);
        browse.Click+=(s,e)=>{using(var dialog=new FolderBrowserDialog())if(dialog.ShowDialog()==DialogResult.OK)box.Text=dialog.SelectedPath;};
    }
    async Task Start() {
        if(cancellation!=null)return;
        InstalledGame layout;try{layout=InstalledGame.Resolve(game.Text);}catch(Exception error){MessageBox.Show(this,error.Message);return;}
        if(String.IsNullOrWhiteSpace(login.Text)||login.Text.Any(char.IsControl)){MessageBox.Show(this,"Введите ник.");return;}
        string path=folder.Text,installedGame=layout.SelectedPath,name=login.Text.Trim();cancellation=new CancellationTokenSource();var token=cancellation.Token;install.Enabled=false;cancel.Enabled=true;
        try {
            Action<string,int> update=(text,percent)=>{if(!IsDisposed)BeginInvoke(new Action(()=>{status.Text=text;progress.Value=Math.Max(0,Math.Min(100,percent));}));};
            string target=await Task.Run(()=>SetupPackage.Install(path,update,token));
            if(!token.IsCancellationRequested){Process.Start(new ProcessStartInfo(Path.Combine(target,"Играть.exe"),"--auto "+Quote(installedGame)+" "+Quote(name)){WorkingDirectory=target,UseShellExecute=false});status.Text="Готово. Запускаются сервер и игра.";}
        }catch(Exception e){if(token.IsCancellationRequested)status.Text="Отменено. Скачанную часть сохранили, можно продолжить позже.";else{status.Text="Не удалось установить: "+e.Message;MessageBox.Show(this,e.Message,"Установка",MessageBoxButtons.OK,MessageBoxIcon.Warning);}}
        finally{cancellation.Dispose();cancellation=null;install.Enabled=true;cancel.Enabled=false;if(closing)Close();}
    }
    internal static string Quote(string text) {
        var result=new System.Text.StringBuilder("\"");int slashes=0;
        foreach(char c in text){if(c=='\\'){slashes++;continue;}result.Append('\\',c=='"'?slashes*2+1:slashes);result.Append(c);slashes=0;}
        return result.Append('\\',slashes*2).Append('"').ToString();
    }
}
