// Portable .NET Framework launcher: no bundled .NET runtime and no global app-data cache.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Security;
using System.Runtime.InteropServices;
using System.Reflection;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32.SafeHandles;

[assembly: AssemblyTitle("SZCustom")]
[assembly: AssemblyProduct("SZCustom")]
[assembly: AssemblyFileVersion("1.0.2026.1118")]

sealed class LauncherError : Exception {
    internal LauncherError(string message) : base(message) { }
}

static class LightLauncher {
    // Opt-in diagnostics for --test-launch only. Normal launches never load a QA agent.
    static string TestQaAgent, TestQaDirectory;
    static string TestLog;
    static readonly object TestLogSync=new object();
    static void TestReport(string message) {
        if(string.IsNullOrEmpty(TestLog)) return;
        try {lock(TestLogSync) File.AppendAllText(TestLog,message+Environment.NewLine,Encoding.UTF8);} catch(IOException) { }
    }
    internal static readonly string Root=Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"launcher-data");
    static readonly JavaScriptSerializer Json=new JavaScriptSerializer();
    static readonly HttpClient Http=CreateHttp();
    static byte[] Resource(string name) { using(var source=typeof(LightLauncher).Assembly.GetManifestResourceStream(name)) using(var bytes=new MemoryStream()) {source.CopyTo(bytes); return bytes.ToArray();} }
    static string Hash(byte[] bytes) { using(var sha=SHA256.Create()) return BitConverter.ToString(sha.ComputeHash(bytes)).Replace("-","").ToLowerInvariant(); }
    static string FileHash(string file) { using(var sha=SHA256.Create()) using(var input=File.OpenRead(file)) return BitConverter.ToString(sha.ComputeHash(input)).Replace("-","").ToLowerInvariant(); }
    // The downloaded runtime may already be cached by a launcher that never loaded
    // its compatibility companion. Ship and select this required fix with the EXE.
    const string CompatibilityHash="602f6e72b6d1843e79004add6a3c133415b1d07ff4d01360037870e112a9798d";
    static string RequiredCompatibility() {
        byte[] bytes=Resource("resource-compatibility.jar");
        if(Hash(bytes)!=CompatibilityHash)throw new LauncherError("Повреждены исправления клиента. Распакуйте лаунчер заново.");
        string directory=Path.Combine(Root,"compat"), path=Path.Combine(directory,"resource-compat-"+CompatibilityHash.Substring(0,16)+".jar");
        Directory.CreateDirectory(directory);
        if(!File.Exists(path)||FileHash(path)!=CompatibilityHash) {
            string incoming=path+"."+Guid.NewGuid().ToString("N")+".incoming";
            File.WriteAllBytes(incoming,bytes);
            if(File.Exists(path))File.Replace(incoming,path,null);else File.Move(incoming,path);
        }
        if(FileHash(path)!=CompatibilityHash)throw new LauncherError("Не удалось проверить исправления клиента.");
        TestReport("Required resource compatibility ready: "+CompatibilityHash);
        return path;
    }
    static HttpClient CreateHttp() {
        byte[] expected=Resource("server-public.cer");
        string pin=Hash(expected);
        ServicePointManager.SecurityProtocol=SecurityProtocolType.Tls12;
        ServicePointManager.ServerCertificateValidationCallback=(sender,cert,chain,errors)=>cert!=null &&
            (errors & SslPolicyErrors.RemoteCertificateNameMismatch)==0 && Hash(cert.GetRawCertData())==pin &&
            DateTime.UtcNow>=new X509Certificate2(cert).NotBefore.ToUniversalTime() && DateTime.UtcNow<=new X509Certificate2(cert).NotAfter.ToUniversalTime();
        return new HttpClient(new HttpClientHandler {AllowAutoRedirect=false}) {Timeout=TimeSpan.FromMinutes(15)};
    }
    static Dictionary<string,object> Obj(object value) {return (Dictionary<string,object>)value;}
    static Dictionary<string,object> Parse(byte[] bytes) {return Json.Deserialize<Dictionary<string,object>>(Encoding.UTF8.GetString(bytes));}
    static string Str(Dictionary<string,object> obj,string key) {return Convert.ToString(obj[key]);}
    static long Number(Dictionary<string,object> obj,string key) {return Convert.ToInt64(obj[key]);}
    static void PruneOldClients(string directory,string current) {
        string root=Path.GetFullPath(directory)+Path.DirectorySeparatorChar;
        foreach(string candidate in Directory.GetFiles(directory,"client-*.szenc")) {
            string full=Path.GetFullPath(candidate), name=Path.GetFileName(candidate);
            if(full==Path.GetFullPath(current) || !full.StartsWith(root,StringComparison.OrdinalIgnoreCase) ||
                !System.Text.RegularExpressions.Regex.IsMatch(name,@"^client-[a-f0-9]{16}\.szenc$")) continue;
            try {File.Delete(full);} catch(IOException) { } catch(UnauthorizedAccessException) { }
        }
    }
    internal static string Endpoint(string value) {
        if(!value.Contains("://")) value="https://"+value;
        Uri uri;
        if(!Uri.TryCreate(value,UriKind.Absolute,out uri) || (uri.Scheme!="https" && !uri.IsLoopback) || uri.UserInfo!="")
            throw new LauncherError("Проверь адрес сервера.");
        var result=new UriBuilder(uri) {Path="",Query="",Fragment=""};
        if(uri.IsDefaultPort) result.Port=uri.Scheme=="https"?29544:29545;
        return result.Uri.GetLeftPart(UriPartial.Authority);
    }
    static HttpRequestMessage Request(string url,string token,HttpMethod method) {
        var request=new HttpRequestMessage(method,url);
        request.Headers.Authorization=new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer",token);
        return request;
    }
    static async Task<byte[]> Fetch(string url,string token,int maximum) {
        using(var request=Request(url,token,HttpMethod.Get)) using(var response=await Http.SendAsync(request,HttpCompletionOption.ResponseHeadersRead)) {
            if(response.StatusCode==HttpStatusCode.Unauthorized) throw new LauncherError("Токен недействителен, отозван или истёк.");
            response.EnsureSuccessStatusCode();
            using(var input=await response.Content.ReadAsStreamAsync()) using(var output=new MemoryStream()) {
                byte[] buffer=new byte[16384]; int n;
                while((n=await input.ReadAsync(buffer,0,buffer.Length))!=0) {if(output.Length+n>maximum) throw new IOException("Ответ слишком большой"); output.Write(buffer,0,n);}
                return output.ToArray();
            }
        }
    }
    static async Task<string> Cached(string endpoint,string token,Dictionary<string,object> artifact,Action<string> progress) {
        string name=Str(artifact,"file"), digest=Str(artifact,"sha256"); long size=Number(artifact,"size");
        if(name!=Path.GetFileName(name) || name.Contains(":") || name.Contains("\\") || name.Contains("/") || size<=0 || size>1024L*1024*1024 || digest.Length!=64) throw new IOException("Некорректный архив");
        string directory=Path.Combine(Root,"cache"); Directory.CreateDirectory(directory);
        string target=Path.Combine(directory,name);
        FileStream lease=null;
        for(int attempt=0;lease==null;attempt++) {
            try {lease=new FileStream(target+".lock",FileMode.OpenOrCreate,FileAccess.ReadWrite,FileShare.None);}
            catch(IOException) {if(attempt>1800) throw;}
            if(lease==null) await Task.Delay(500);
        }
        using(lease) {
            if(File.Exists(target) && new FileInfo(target).Length==size && FileHash(target)==digest) {if(name.EndsWith(".szenc")) PruneOldClients(directory,target); progress("Подготовка игры…"); return target;}
            string temporary=target+".part";
            try {
                using(var request=Request(endpoint+"/launcher/"+name,token,HttpMethod.Get)) using(var response=await Http.SendAsync(request,HttpCompletionOption.ResponseHeadersRead)) {
                    response.EnsureSuccessStatusCode();
                    if(response.Content.Headers.ContentLength!=size) throw new IOException("Размер архива не совпадает");
                    using(var input=await response.Content.ReadAsStreamAsync()) using(var output=new FileStream(temporary,FileMode.Create,FileAccess.Write,FileShare.None)) {
                        byte[] buffer=new byte[131072]; long count=0,last=0; int n;
                        while((n=await input.ReadAsync(buffer,0,buffer.Length))!=0) {
                            count+=n; if(count>size) throw new IOException("Архив слишком большой"); await output.WriteAsync(buffer,0,n);
                            if(Environment.TickCount-last>250 || last==0) {progress("Загрузка обновления: "+count/1048576+" / "+size/1048576+" МБ"); last=Environment.TickCount;}
                        }
                    }
                }
                if(new FileInfo(temporary).Length!=size || FileHash(temporary)!=digest) throw new IOException("Проверка архива не пройдена");
                if(File.Exists(target)) File.Delete(target);
                File.Move(temporary,target);
                if(name.EndsWith(".szenc")) PruneOldClients(directory,target);
            } finally {if(File.Exists(temporary)) File.Delete(temporary);}
            return target;
        }
    }
    static string EnvironmentRoot(string archive,string digest,Action<string> progress) {
        if(digest.Length!=64 || digest.Any(c=>!Uri.IsHexDigit(c))) throw new IOException("Некорректный хеш среды");
        // The marker still verifies all 256 bits. Short directory names leave room for native DLL paths on Windows.
        string destination=Path.Combine(Root,"environment",digest.Substring(0,16)), marker=Path.Combine(destination,".installed");
        if(File.Exists(marker) && File.ReadAllText(marker)==digest && File.Exists(Path.Combine(destination,"jre/bin/java.exe")) && File.Exists(Path.Combine(destination,"boot.jar"))) return destination;
        string staging=destination+".install-"+Guid.NewGuid().ToString("N").Substring(0,16); Directory.CreateDirectory(staging);
        string prefix=Path.GetFullPath(staging)+Path.DirectorySeparatorChar; long total=0;
        progress("Подготовка игры…");
        using(var zip=ZipFile.OpenRead(archive)) foreach(var entry in zip.Entries) {
            string path=Path.GetFullPath(Path.Combine(staging,entry.FullName));
            if(!path.StartsWith(prefix,StringComparison.OrdinalIgnoreCase) || entry.FullName.Contains(":") || (total+=entry.Length)>2L*1024*1024*1024) throw new IOException("Небезопасный архив среды");
            if(entry.FullName.EndsWith("/")) {Directory.CreateDirectory(path); continue;}
            Directory.CreateDirectory(Path.GetDirectoryName(path)); entry.ExtractToFile(path);
        }
        File.WriteAllText(Path.Combine(staging,".installed"),digest);
        if(Directory.Exists(destination)) Directory.Move(destination,destination+".incomplete-"+Guid.NewGuid().ToString("N").Substring(0,16));
        Directory.Move(staging,destination); return destination;
    }
    static void SettingsCopy(string source,string target) {
        Directory.CreateDirectory(target);
        foreach(string name in new[]{"options.json","keybindings","display","prefs","hbook.cfg","quests.cfg","waypoints.cfg","mutelist"})
            if(File.Exists(Path.Combine(source,name))) File.Copy(Path.Combine(source,name),Path.Combine(target,name),true);
    }
    static string Quote(string argument) {
        var text=new StringBuilder("\""); int slashes=0;
        foreach(char c in argument) {if(c=='\\') {slashes++; continue;} if(c=='\"') text.Append('\\',slashes*2+1); else text.Append('\\',slashes); text.Append(c); slashes=0;}
        text.Append('\\',slashes*2); return text.Append('"').ToString();
    }
    static async Task Drain(StreamReader reader,string session,string token) {
        if(TestLog!=null) {
            string line;
            while((line=await reader.ReadLineAsync())!=null) TestReport(line.Replace(session,"<session>").Replace(token,"<token>"));
        } else {char[] buffer=new char[4096]; while(await reader.ReadAsync(buffer,0,buffer.Length)>0) { }}
    }
    internal static string UserMessage(Exception error) {
        if(error is LauncherError) return error.Message;
        if(error is HttpRequestException || error is TaskCanceledException) return "Не удалось связаться с сервером. Проверь адрес и подключение.";
        if(error is UnauthorizedAccessException) return "Нет доступа к папке. Перемести SZCustom в доступную папку.";
        return "Не удалось запустить игру. Повтори попытку.";
    }
    internal static async Task Run(string token,string gameDirectory,string server,Action<string> progress,bool preflight,int control) {
        if(string.IsNullOrWhiteSpace(token) || token.Length>128 || token.Any(char.IsControl)) throw new LauncherError("Введи токен доступа.");
        if(!Directory.Exists(Path.Combine(gameDirectory,"assets")) || !Directory.Exists(Path.Combine(gameDirectory,"modassets"))) throw new LauncherError("Выбери папку с игрой.");
        string endpoint=Endpoint(server); Directory.CreateDirectory(Root);
        progress("Проверка обновлений…");
        byte[] manifestBytes=null;
        for(int attempt=0;attempt<3;attempt++) {
            manifestBytes=await Fetch(endpoint+"/launcher/manifest.json",token,65536);
            byte[] signature=await Fetch(endpoint+"/launcher/manifest.sig",token,4096);
            bool valid;
            using(var rsa=RSA.Create()) {
                rsa.FromXmlString(Encoding.UTF8.GetString(Resource("release-public.xml")));
                valid=rsa.VerifyData(manifestBytes,signature,HashAlgorithmName.SHA256,RSASignaturePadding.Pkcs1);
            }
            if(valid) break;
            if(attempt==2) throw new LauncherError("Не удалось проверить обновление. Повтори попытку.");
            await Task.Delay(250); // Publication can briefly straddle two atomic file replacements.
        }
        var manifest=Parse(manifestBytes); if(Number(manifest,"format")!=2) throw new LauncherError("Обнови SZCustom и повтори запуск.");
        var artifacts=Obj(manifest["artifacts"]); var runtime=Obj(artifacts["runtime"]); var client=Obj(artifacts["client"]);
        if(!Str(client,"file").EndsWith(".szenc") || Str(client,"algorithm")!="AES-256-GCM") throw new IOException("Клиент должен быть зашифрован");
        string runtimeZip=await Cached(endpoint,token,runtime,progress);
        string environment=await Task.Run(()=>EnvironmentRoot(runtimeZip,Str(runtime,"sha256"),progress));
        string code=await Cached(endpoint,token,client,progress);
        progress("Проверка доступа…");
        byte[] key=null;
        string login,session;
        using(var request=Request(endpoint+"/launcher/launch",token,HttpMethod.Post)) {
            request.Content=new StringContent(Json.Serialize(new{release=Str(manifest,"release")}),Encoding.UTF8,"application/json");
            using(var response=await Http.SendAsync(request)) {
                if(response.StatusCode==HttpStatusCode.Unauthorized) throw new LauncherError("Токен недействителен, отозван или истёк.");
                response.EnsureSuccessStatusCode(); byte[] authorization=await response.Content.ReadAsByteArrayAsync();
                try {var values=Parse(authorization); key=Convert.FromBase64String(Str(values,"key_b64")); login=Str(values,"login"); session=Str(values,"session");}
                finally {Array.Clear(authorization,0,authorization.Length);}
            }
        }
        string folder=Path.Combine(Root,"sessions",Guid.NewGuid().ToString("N")), game=Path.Combine(folder,"game"), temp=Path.Combine(folder,"temp");
        string profile=Path.Combine(Root,"profile"), natives=Path.Combine(environment,"natives");
        Directory.CreateDirectory(game); Directory.CreateDirectory(temp); Directory.CreateDirectory(Path.Combine(profile,"mods"));
        Process process=null; IntPtr job=IntPtr.Zero;
        try {
            foreach(string name in new[]{"assets","modassets","patchassets"}) if(Directory.Exists(Path.Combine(gameDirectory,name))) Junction(Path.Combine(game,name),Path.Combine(gameDirectory,name));
            string index=Path.Combine(gameDirectory,"client_class_index.txt"); if(File.Exists(index)) File.Copy(index,Path.Combine(game,"client_class_index.txt"));
            if(!Directory.Exists(Path.Combine(profile,"config"))) SettingsCopy(Path.Combine(gameDirectory,"config"),Path.Combine(profile,"config"));
            SettingsCopy(Path.Combine(profile,"config"),Path.Combine(game,"config"));
            var arguments=new List<string> {
                "-Xmx4096m","-XX:+UseG1GC","-XX:MaxGCPauseMillis=3","-XX:+DisableAttachMechanism","-XX:-CreateCoredumpOnCrash","-XX:-HeapDumpOnOutOfMemoryError",
                "-Djava.system.class.loader=szlaunch.LayeredMemoryLoader","-Dsz.launcher.memory=true","-Dsz.bundle.sha256="+Str(client,"plaintext_sha256"),
                "-Dsz.ram.dataDir="+Path.Combine(folder,"resource-data"),"-Dsz.mods.dir="+Path.Combine(profile,"mods"),
                "-Djavax.net.ssl.trustStore="+Path.Combine(environment,"server-trust.p12"),"-Djavax.net.ssl.trustStorePassword=szpublic",
                "-Djava.library.path="+natives+";"+Path.Combine(environment,"jre/bin/server"),"-Dorg.lwjgl.librarypath="+natives,"-Djava.io.tmpdir="+temp,
                "-Dstalcraft.native.dir="+natives,"-Dstalcraft.effekseer.path="+Path.Combine(natives,"stalcraft-effekseer-jni64.dll"),"-Dimgui.library.path="+natives,
                "-DZstdNativePath="+Path.Combine(natives,"libzstd-jni-1.5.0-5.dll"),"-Dfile.encoding=UTF-8","-Dshow_globally_enabled=false","-Dread_derived=true",
                "-DCustomNpcsSoundCache=true","-Dload_dumped_event_classes=true","-DusePrestitchedAtlas=true","-Ddisable_item_atlas=true","-Ddisable_mod_parsing=false",
                "-Duse_system_class_loader=true","-Dbundle.backend_http_endpoint="+endpoint,"--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED","--add-opens=java.base/java.time=ALL-UNNAMED"
            };
            if(preflight) arguments.Add("-Dstandard.jvm.preflight=true");
            string compatibilityAgent=RequiredCompatibility();
            arguments.Add("-javaagent:"+compatibilityAgent);
            string replayAgent=Path.Combine(environment,"agents/network-replay.jar");
            if(File.Exists(replayAgent)) arguments.Add("-javaagent:"+replayAgent);
            string weaponAgent=Path.Combine(environment,"agents/weapon-events.jar");
            if(File.Exists(weaponAgent)) {arguments.Add("--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED"); arguments.Add("-javaagent:"+weaponAgent);}
            string environmentAgent=Path.Combine(environment,"agents/world-environment.jar");
            if(File.Exists(environmentAgent)) {arguments.Add("--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED"); arguments.Add("-javaagent:"+environmentAgent);}
            if(control>0) {arguments.Add("-Dstandard.jvm.headless.control=true"); arguments.Add("-Dstandard.jvm.headless.port="+control);}
            if(TestQaAgent!=null) {
                arguments.Add("-javaagent:"+TestQaAgent);
                arguments.Add("-Dsz.render.qa.directory="+TestQaDirectory);
                arguments.Add("-Dsz.render.qa.guard.port="+(control+1));
                arguments.Add("-Dstandard.jvm.headless.hideWindow=true");
            }
            arguments.AddRange(new[]{"-cp",Path.Combine(environment,"boot.jar")+";"+Path.Combine(game,"modassets"),"szlaunch.Entry","--username",login,"--session",session,"--dlogin",login,"--gameDir",game,"--assetsDir",Path.Combine(game,"assets")});
            var info=new ProcessStartInfo(Path.Combine(environment,"jre/bin/java.exe"),string.Join(" ",arguments.Select(Quote))) {
                WorkingDirectory=game,UseShellExecute=false,CreateNoWindow=true,RedirectStandardInput=true,RedirectStandardOutput=true,RedirectStandardError=true
            };
            foreach(string env in new[]{"JAVA_TOOL_OPTIONS","_JAVA_OPTIONS","JDK_JAVA_OPTIONS","CLASSPATH","SZ_LAUNCH_TOKEN","SSLKEYLOGFILE"}) info.EnvironmentVariables.Remove(env);
            info.EnvironmentVariables["PATH"]=natives+";"+Path.Combine(environment,"jre/bin")+";"+Environment.GetFolderPath(Environment.SpecialFolder.System);
            info.EnvironmentVariables["TEMP"]=info.EnvironmentVariables["TMP"]=temp;
            job=CreateJobObject(IntPtr.Zero,null); byte[] limits=new byte[144]; BitConverter.GetBytes(0x2000u).CopyTo(limits,16);
            if(job==IntPtr.Zero || !SetInformationJobObject(job,9,limits,(uint)limits.Length)) throw new IOException("Не удалось создать lifecycle job");
            process=Process.Start(info);
            if(!AssignProcessToJobObject(job,process.Handle)) throw new IOException("Не удалось привязать JVM к лаунчеру");
            {
                Task stdout=Drain(process.StandardOutput,session,token), stderr=Drain(process.StandardError,session,token);
                using(var pipe=process.StandardInput.BaseStream) using(var encrypted=File.OpenRead(code)) {
                    byte[] magic=BitConverter.GetBytes(IPAddress.HostToNetworkOrder(0x535A4532)); await pipe.WriteAsync(magic,0,4);
                    if(key.Length!=32) throw new IOException("Неверный размер ключа"); await pipe.WriteAsync(key,0,32); Array.Clear(key,0,key.Length); key=null;
                    byte[] length=BitConverter.GetBytes(IPAddress.HostToNetworkOrder(checked((int)encrypted.Length))); await pipe.WriteAsync(length,0,4); await encrypted.CopyToAsync(pipe);
                }
                progress("Игра запущена");
                await Task.Run(()=>process.WaitForExit()); await Task.WhenAll(stdout,stderr);
                SettingsCopy(Path.Combine(game,"config"),Path.Combine(profile,"config"));
                if(process.ExitCode!=0) throw new LauncherError("Игра завершилась с ошибкой.");
            }
        } finally {
            if(key!=null) Array.Clear(key,0,key.Length);
            if(job!=IntPtr.Zero) CloseHandle(job);
            if(process!=null) {if(!process.HasExited) process.Kill(); process.Dispose();}
            foreach(string name in new[]{"assets","modassets","patchassets"}) {
                string link=Path.Combine(game,name);
                if(Directory.Exists(link) && (File.GetAttributes(link)&FileAttributes.ReparsePoint)!=0) Directory.Delete(link);
            }
            // Never recursively traverse stock resource junctions.
        }
    }
    [STAThread] static void Main(string[] args) {
        if(args.Length==1&&args[0]=="--verify-compat") {
            try{RequiredCompatibility();}catch{Environment.ExitCode=1;}return;
        }
        if(args.Length>=3 && args[0]=="--test-launch") {
            TestLog=Environment.GetEnvironmentVariable("SZ_LAUNCH_TEST_LOG");
            try {
                if(args.Contains("--isolated-test")) {
                    if(!args.Contains("--control")) throw new LauncherError("Isolated diagnostics require the control bridge.");
                    TestQaAgent=Environment.GetEnvironmentVariable("SZ_LAUNCH_QA_AGENT");
                    TestQaDirectory=Environment.GetEnvironmentVariable("SZ_LAUNCH_QA_DIRECTORY");
                    string expected=Environment.GetEnvironmentVariable("SZ_LAUNCH_QA_SHA256");
                    if(string.IsNullOrEmpty(expected) || !File.Exists(TestQaAgent) || FileHash(TestQaAgent)!=expected || !Directory.Exists(TestQaDirectory))
                        throw new LauncherError("Verified isolation guard is required.");
                }
                Run(Environment.GetEnvironmentVariable("SZ_LAUNCH_TOKEN")??"",args[1],args[2],TestReport,args.Contains("--preflight"),args.Contains("--control")?29602:0).GetAwaiter().GetResult();
            }
            catch(Exception error) {TestReport(error.GetType().Name+": "+error.Message); Environment.ExitCode=1;}
            return;
        }
        Application.EnableVisualStyles(); Application.Run(new LauncherWindow());
    }
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern IntPtr CreateJobObject(IntPtr security,string name);
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool SetInformationJobObject(IntPtr job,int type,byte[] data,uint size);
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern SafeFileHandle CreateFile(string path,uint access,uint share,IntPtr security,uint creation,uint flags,IntPtr template);
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool DeviceIoControl(SafeFileHandle file,uint code,byte[] data,int size,IntPtr output,int outputSize,out int returned,IntPtr overlap);
    static void Junction(string link,string target) {
        Directory.CreateDirectory(link);
        using(var handle=CreateFile(link,0x40000000,0,IntPtr.Zero,3,0x02200000,IntPtr.Zero)) {
            if(handle.IsInvalid) throw new IOException("Не удалось создать ссылку на ресурсы");
            byte[] substitute=Encoding.Unicode.GetBytes(@"\??\"+Path.GetFullPath(target).TrimEnd('\\')), print=Encoding.Unicode.GetBytes(Path.GetFullPath(target));
            byte[] data=new byte[16+substitute.Length+2+print.Length+2];
            using(var writer=new BinaryWriter(new MemoryStream(data))) {writer.Write(0xA0000003u); writer.Write((ushort)(data.Length-8)); writer.Write((ushort)0); writer.Write((ushort)0); writer.Write((ushort)substitute.Length); writer.Write((ushort)(substitute.Length+2)); writer.Write((ushort)print.Length); writer.Write(substitute); writer.Write((ushort)0); writer.Write(print); writer.Write((ushort)0);}
            int returned; if(!DeviceIoControl(handle,0x000900A4,data,data.Length,IntPtr.Zero,0,out returned,IntPtr.Zero)) throw new IOException("Не удалось связать папки ресурсов");
        }
    }
}

sealed class LauncherWindow:Form {
    readonly TextBox token=new TextBox {UseSystemPasswordChar=true}, game=new TextBox(), server=new TextBox();
    readonly Label status=new Label(); readonly Button launch=new Button(); bool running;
    public LauncherWindow() {
        Text="SZCustom"; ClientSize=new Size(600,330); FormBorderStyle=FormBorderStyle.FixedSingle; MaximizeBox=false; StartPosition=FormStartPosition.CenterScreen; Font=new Font("Segoe UI",10);
        var title=new Label {Text="SZCustom",Location=new Point(24,16),AutoSize=true,Font=new Font("Segoe UI",16,FontStyle.Bold)}; Controls.Add(title);
        Controls.Add(new Label {Text="18.11.2026 · исправлено",Location=new Point(405,23),AutoSize=true,ForeColor=Color.DimGray});
        Field("Токен доступа",token,62); Field("Папка с игрой",game,126); Field("Адрес сервера",server,190);
        game.Width=420; var browse=new Button {Text="Обзор",Location=new Point(456,148),Size=new Size(115,28)};
        browse.Click+=(s,e)=>{using(var picker=new FolderBrowserDialog()) if(picker.ShowDialog(this)==DialogResult.OK) game.Text=picker.SelectedPath;}; Controls.Add(browse);
        launch.Text="Запустить игру"; launch.Location=new Point(395,269); launch.Size=new Size(175,36); Controls.Add(launch);
        status.Location=new Point(24,270); status.Size=new Size(360,42); status.AutoEllipsis=true; status.Text="Готов к запуску"; Controls.Add(status);
        try {string p=Path.Combine(LightLauncher.Root,"settings.json"); if(File.Exists(p)) {var data=new JavaScriptSerializer().Deserialize<Dictionary<string,string>>(File.ReadAllText(p)); game.Text=data["game"]; server.Text=data["server"];}} catch { }
        launch.Click+=async(s,e)=> {
            launch.Enabled=false; running=true;
            try {
                Directory.CreateDirectory(LightLauncher.Root); File.WriteAllText(Path.Combine(LightLauncher.Root,"settings.json"),new JavaScriptSerializer().Serialize(new{game=game.Text,server=server.Text}));
                await LightLauncher.Run(token.Text.Trim(),game.Text.Trim(),server.Text.Trim(),m=> {if(IsHandleCreated) BeginInvoke(new Action(()=>status.Text=m));},false,0);
                status.Text="Игра закрыта";
            } catch(Exception failure) {string message=LightLauncher.UserMessage(failure); status.Text=message; MessageBox.Show(this,message,"SZCustom",MessageBoxButtons.OK,MessageBoxIcon.Warning);}
            finally {running=false; launch.Enabled=true;}
        };
        FormClosing+=(s,e)=>{if(running) {e.Cancel=true; WindowState=FormWindowState.Minimized;}};
    }
    void Field(string caption,TextBox input,int y) {Controls.Add(new Label {Text=caption,Location=new Point(24,y),AutoSize=true}); input.Location=new Point(24,y+22); input.Size=new Size(546,28); Controls.Add(input);}
}
