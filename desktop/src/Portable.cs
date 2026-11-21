using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Net;
using System.Diagnostics;
using System.Collections.Generic;
using System.Threading.Tasks;
using System.Drawing;
using System.Windows.Forms;
using System.Web.Script.Serialization;

static class Portable {
    public static string Root=AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');
    public static string P(params string[] parts){return Path.Combine(new[]{Root}.Concat(parts).ToArray());}
    public static string Q(string s){
        var result=new StringBuilder("\"");int slashes=0;
        foreach(char c in s){if(c=='\\'){slashes++;continue;}result.Append('\\',c=='"'?slashes*2+1:slashes);result.Append(c);slashes=0;}
        return result.Append('\\',slashes*2).Append('"').ToString();
    }
    public static void Need(string p){if(!File.Exists(p)&&!Directory.Exists(p))throw new IOException("Не найден файл или каталог: "+p);}
    public static string Http(string url,string token,string body){
        var request=(HttpWebRequest)WebRequest.Create(url);request.Timeout=5000;request.ReadWriteTimeout=5000;
        if(token!=null)request.Headers["Authorization"]="Bearer "+token;
        if(body!=null){request.Method="POST";request.ContentType=body.StartsWith("{")?"application/json":"application/x-www-form-urlencoded";byte[] data=Encoding.UTF8.GetBytes(body);request.ContentLength=data.Length;using(var s=request.GetRequestStream())s.Write(data,0,data.Length);}
        using(var response=request.GetResponse())using(var reader=new StreamReader(response.GetResponseStream()))return reader.ReadToEnd();
    }
    public static string Endpoint(string input){Uri uri;string s=input.Trim();if(!s.Contains("://"))s="http://"+s;
        if(!Uri.TryCreate(s,UriKind.Absolute,out uri)||!(uri.Scheme=="http"||uri.Scheme=="https")||uri.AbsolutePath!="/"||uri.Query!=""||uri.UserInfo!="")throw new IOException("Адрес: IP или домен, например 192.168.1.10:29545");
        var b=new UriBuilder(uri);if(uri.IsDefaultPort)b.Port=29545;return b.Uri.GetLeftPart(UriPartial.Authority);
    }
    public static void Json(string path,object value){Directory.CreateDirectory(Path.GetDirectoryName(path));File.WriteAllText(path,new JavaScriptSerializer().Serialize(value),Encoding.UTF8);}
    public static string BaseConfig(){return File.ReadAllText(P("server.template.properties"),Encoding.UTF8);}
    public static string Prop(string s){s=s.Replace("\\","/").Replace(":","\\:").Replace("=","\\=");var b=new StringBuilder();foreach(char c in s)b.Append(c>127?"\\u"+((int)c).ToString("x4"):c.ToString());return b.ToString();}
    public static string NormalizeHost(string host){host=host.Trim();if(Uri.CheckHostName(host)==UriHostNameType.Unknown||host.Contains("://"))throw new IOException("Укажите IP или домен без порта и http://");return host;}
}

#if CLIENT
static class Program {
    [STAThread] static void Main(string[] args){
        if(args.Length>=3&&args[0]=="--check"){
            try{ClientForm.Check(args[1],args[2]);Portable.Json(Portable.P("preflight.json"),new{ok=true,gameplayTested=false});}
            catch(Exception e){Portable.Json(Portable.P("preflight.json"),new{ok=false,error=e.Message});Environment.ExitCode=1;}return;
        }
        Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);Application.Run(new ClientForm(args));
    }
}
sealed class ClientForm:Form {
    TextBox server=new TextBox(),login=new TextBox(),game=new TextBox();Label status=new Label();Button launch=new Button();Process activeClient;
    public ClientForm(string[] args=null){
        Text="Бскпкрафт — локальный клиент";ClientSize=new Size(620,355);FormBorderStyle=FormBorderStyle.FixedDialog;MaximizeBox=false;StartPosition=FormStartPosition.CenterScreen;Font=new Font("Segoe UI",10);
        Add("Адрес сервера (IP:29545)",server,20);server.Text="127.0.0.1:29545";
        Add("Логин",login,90);login.Text="Player";Add("Папка установленной игры",game,160);game.Width=450;
        var browse=new Button{Text="Обзор…",Left=485,Top=185,Width=105,Height=30};browse.Click+=(s,e)=>{using(var d=new FolderBrowserDialog())if(d.ShowDialog()==DialogResult.OK)game.Text=d.SelectedPath;};Controls.Add(browse);
        launch.Text="Запустить игру";launch.SetBounds(20,245,570,38);Controls.Add(launch);status.SetBounds(20,294,570,45);Controls.Add(status);
        try{var d=new JavaScriptSerializer().Deserialize<Dictionary<string,string>>(File.ReadAllText(Portable.P("settings.json")));server.Text=d["server"];login.Text=d["login"];game.Text=d["game"];}catch{}
        launch.Click+=async(s,e)=>{launch.Enabled=false;try{if(activeClient!=null&&!activeClient.HasExited)throw new IOException("Клиент уже запущен");Check(game.Text,server.Text);if(String.IsNullOrWhiteSpace(login.Text)||login.Text.Any(char.IsControl))throw new IOException("Введите логин");status.Text="Проверка сервера…";
            string endpoint=Portable.Endpoint(server.Text);await Task.Run(()=>Portable.Http(endpoint+"/health",null,null));
            Portable.Json(Portable.P("settings.json"),new{server=server.Text,login=login.Text.Trim(),game=game.Text});StartGame(Path.GetFullPath(game.Text),endpoint,login.Text.Trim());status.Text="Клиент запущен. Журнал — в папке logs.";
        }catch(Exception error){if(!Visible)Show();status.Text=error.Message;MessageBox.Show(this,error.Message,"Не удалось запустить игру",MessageBoxButtons.OK,MessageBoxIcon.Error);}finally{launch.Enabled=true;}};
        if(args!=null&&args.Length==4&&args[0]=="--play"){
            game.Text=args[1];server.Text=args[2];login.Text=args[3];
            Shown+=(s,e)=>{Hide();launch.PerformClick();};
        }
    }
    void Add(string text,TextBox box,int y){Controls.Add(new Label{Text=text,Left=20,Top=y,Width=560,Height=24});box.SetBounds(20,y+25,570,28);Controls.Add(box);}
    public static void Check(string game,string endpoint){
        Portable.Endpoint(endpoint);InstalledGame.Resolve(game);
        foreach(string part in new[]{"jre/bin/javaw.exe","runtime/bootstrap/StandardJvmBootstrap.class","runtime/classes","runtime/libs","runtime/imgui-compat","runtime/dependencies","runtime/natives","compat/game-resource-compat.jar","compat/startup-guard.jar","compat/weapon-events.jar","runtime/natives/fmod/x64/fmod.dll","runtime/natives/fmod/x64/fmodstudio.dll","runtime/natives/eos/x64/EOSSDK-Win64-Shipping.dll","runtime/natives/eos/x64/xaudio2_9redist.dll","runtime/natives/stove/BaseSDK.dll","runtime/natives/stove/GameSupportSDK.dll","runtime/natives/stove/IAPSDK.dll","runtime/natives/stove/LogSDK.dll","runtime/natives/stove/OwnershipSDK.dll","runtime/natives/stove/PCBangSDK.dll","runtime/natives/stove/WebView2Loader.dll"})Portable.Need(Portable.P(part));
        if(Directory.GetFiles(Portable.P("mods"),"*.jar").Length!=0)throw new IOException("Комплект должен запускаться без модов. Очистите папку mods.");
    }
    void StartGame(string game,string endpoint,string login){
        Directory.CreateDirectory(Portable.P("logs"));Directory.CreateDirectory(Portable.P("temp"));
        InstalledGame layout=InstalledGame.Resolve(game);game=layout.Root;
        string natives=Portable.P("runtime","natives"),nativeGame=layout.NativeBin??natives;
        string nativeSearch=String.Join(";",new[]{natives,nativeGame,Portable.P("jre/bin"),Portable.P("jre/bin/server")});
        string cp=String.Join(";",new[]{Portable.P("runtime/bootstrap"),Portable.P("runtime/classes"),Portable.P("runtime/imgui-compat")}.Concat(Directory.GetFiles(Portable.P("runtime/dependencies"),"*.jar").Where(p=>!Path.GetFileName(p).StartsWith("imgui-java-binding-"))).Concat(new[]{Portable.P("runtime/libs"),layout.ModAssets}).Concat(layout.PatchAssets==null?new string[0]:new[]{layout.PatchAssets}));
        var a=new List<string>{"-Xmx4096m","-XX:ErrorFile="+Portable.P("logs","hs_err_pid%p.log"),"-XX:+UseG1GC","-XX:MaxGCPauseMillis=3","--add-opens=java.base/java.lang=ALL-UNNAMED","--add-opens=java.base/java.net=ALL-UNNAMED","--add-opens=java.base/java.time=ALL-UNNAMED","--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED","-Dfile.encoding=UTF-8","-Dsz.mods.dir="+Portable.P("mods"),"-Djava.io.tmpdir="+Portable.P("temp"),"-Djava.library.path="+nativeSearch,"-Dorg.lwjgl.librarypath="+natives,"-Dstalcraft.native.dir="+natives,"-Dstalcraft.effekseer.path="+Path.Combine(natives,"stalcraft-effekseer-jni64.dll"),"-Dimgui.library.path="+natives,"-DZstdNativePath="+Path.Combine(natives,"libzstd-jni-1.5.0-5.dll"),"-Dbundle.backend_http_endpoint="+endpoint};
        if(layout.PatchAssets!=null)a.Add("-Dpatch_assets_dir="+layout.PatchAssets);
        foreach(string flag in new[]{"show_globally_enabled=false","read_derived=true","CustomNpcsSoundCache=true","load_dumped_event_classes=true","usePrestitchedAtlas=true","disable_item_atlas=true","disable_mod_parsing=false","use_system_class_loader=true"})a.Add("-D"+flag);
        foreach(string agent in new[]{"game-resource-compat.jar","weapon-events.jar","startup-guard.jar"})a.Add("-javaagent:"+Portable.P("compat",agent));
        string characterScale=Portable.P("compat","character-scale.jar");if(File.Exists(characterScale))a.Add("-javaagent:"+characterScale);
        a.AddRange(new[]{"-cp",cp,"StandardJvmBootstrap","--username",login,"--session","offline","--gameDir",game,"--assetsDir",layout.Assets,"--dlogin",login});
        var info=new ProcessStartInfo(Portable.P("jre/bin/javaw.exe"),String.Join(" ",a.Select(Portable.Q))){WorkingDirectory=game,UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true};
        info.EnvironmentVariables["PATH"]=nativeSearch+";"+info.EnvironmentVariables["PATH"];
        var process=new Process{StartInfo=info};string logPath=Portable.P("logs","client-"+DateTime.Now.ToString("yyyyMMdd-HHmmss")+".log");var output=new StreamWriter(logPath,false,Encoding.UTF8){AutoFlush=true};object gate=new object();
        DataReceivedEventHandler handler=(s,e)=>{if(e.Data!=null)lock(gate)output.WriteLine(e.Data);};process.OutputDataReceived+=handler;process.ErrorDataReceived+=handler;
        process.Start();activeClient=process;process.BeginOutputReadLine();process.BeginErrorReadLine();
        // The launcher stays alive to drain redirected streams until the game exits.
        Task.Run(()=>{process.WaitForExit();lock(gate)output.Dispose();if(!IsDisposed)BeginInvoke(new Action(()=>{status.Text="Клиент завершён. Код: "+process.ExitCode;if(process.ExitCode!=0){Show();Activate();MessageBox.Show(this,"Игра завершилась с кодом "+process.ExitCode+". Журнал запуска:\n"+logPath+"\n\nПришлите этот файл для разбора. Если есть hs_err_pid*.log в той же папке, приложите его тоже.","Ошибка запуска клиента",MessageBoxButtons.OK,MessageBoxIcon.Error);}else if(!Visible)Close();}));});
    }
    protected override void OnFormClosing(FormClosingEventArgs e){
        // Closing the launcher must not break stdout pipes of a running client.
        if(activeClient!=null&&!activeClient.HasExited){e.Cancel=true;Hide();}base.OnFormClosing(e);
    }
}
#else
static class Program {
    [STAThread] static void Main(string[] args){
        if(args.Length>=2&&args[0]=="--smoke"){
            var host=new ServerHost();try{host.Start(args[1],"127.0.0.1",39545);host.WaitReady().GetAwaiter().GetResult();string snapshot=host.Api("/health",null);string login=Portable.Http("http://127.0.0.1:39545/session/game",null,"login=PortableCheck");if(!login.Contains("session"))throw new IOException("Native login API failed");host.Stop().GetAwaiter().GetResult();Portable.Json(Portable.P("smoke.json"),new{ok=true,stopped=host.Exited,health=snapshot,nativeLogin=true});}catch(Exception e){try{host.Stop().GetAwaiter().GetResult();}catch{}Portable.Json(Portable.P("smoke.json"),new{ok=false,error=e.ToString()});Environment.ExitCode=1;}return;
        }
        Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);Application.Run(new ManagerForm());
    }
}
sealed class ServerHost {
    Process process;StreamWriter log;object gate=new object();public event Action<string> Line;int port;public bool Exited{get{return process==null||process.HasExited;}}
    public string Api(string path,string body){return Portable.Http("http://127.0.0.1:"+port+path,File.ReadAllText(Portable.P("data/admin-token.txt")).Trim(),body);}
    void Owned(){var state=new JavaScriptSerializer().Deserialize<Dictionary<string,object>>(Api("/server",null));if(!state.ContainsKey("pid")||Convert.ToInt64(state["pid"])!=process.Id)throw new IOException("Порт мониторинга занят другим сервером; остановка отменена.");}
    public void Start(string map,string address,int backend){
        if(!Exited)throw new IOException("Сервер уже запущен");Portable.NormalizeHost(address);map=Path.GetFullPath(map);Portable.Need(Path.Combine(map,"region"));Portable.Need(Path.Combine(map,"registry.json"));Portable.Need(Portable.P("jre/bin/java.exe"));
        Directory.CreateDirectory(Portable.P("data"));Directory.CreateDirectory(Portable.P("logs"));Directory.CreateDirectory(Portable.P("temp"));
        string config=Portable.BaseConfig().Replace("@ROOT@",Portable.Prop(Portable.Root)).Replace("@MAP@",Portable.Prop(map)).Replace("@HOST@",Portable.Prop(address)).Replace("@HTTP@",backend.ToString()).Replace("@GAME@",(backend+1).ToString()).Replace("@WORLD@",(backend+2).ToString()).Replace("@ADMIN@",(backend+100).ToString()).Replace("@DEV@",(backend+104).ToString());
        File.WriteAllText(Portable.P("generated.properties"),config,Encoding.ASCII);port=backend+100;
        string cp=String.Join(";",new[]{Portable.P("server.jar"),Portable.P("lib/gson-2.13.2.jar"),Portable.P("runtime/dependencies/zstd-jni-1.5.0-5.jar")});
        var a=new[]{"-Djava.awt.headless=true","-Dfile.encoding=UTF-8","-Djava.io.tmpdir="+Portable.P("temp"),"-Xms256m","-Xmx2g","-cp",cp,"szserver.ServerMain",Portable.P("generated.properties")};
        var info=new ProcessStartInfo(Portable.P("jre/bin/java.exe"),String.Join(" ",a.Select(Portable.Q))){WorkingDirectory=Portable.Root,UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true};
        log=new StreamWriter(Portable.P("logs","server-"+DateTime.Now.ToString("yyyyMMdd-HHmmss")+".log"),false,Encoding.UTF8){AutoFlush=true};
        process=new Process{StartInfo=info};DataReceivedEventHandler handler=(s,e)=>{if(e.Data!=null){lock(gate)log.WriteLine(e.Data);if(Line!=null)Line(e.Data);}};process.OutputDataReceived+=handler;process.ErrorDataReceived+=handler;process.Start();process.BeginOutputReadLine();process.BeginErrorReadLine();
    }
    public async Task WaitReady(){for(int i=0;i<240;i++){if(Exited)throw new IOException("Сервер завершился. Подробности в logs.");try{var health=new JavaScriptSerializer().Deserialize<Dictionary<string,object>>(Api("/health",null));if(health.ContainsKey("ready")&&true.Equals(health["ready"])){Owned();return;}}catch{}await Task.Delay(1000);}throw new IOException("Сервер ещё загружается. Проверьте журнал.");}
    public async Task Stop(){if(Exited)return;Owned();Api("/server/stop","{}");for(int i=0;i<180;i++){if(Exited){process.WaitForExit();lock(gate)log.Dispose();return;}await Task.Delay(1000);}throw new IOException("Сервер сохраняет мир. Принудительная остановка не выполнялась.");}
}
sealed class ManagerForm:Form {
    ServerHost host=new ServerHost();TextBox map=new TextBox(),address=new TextBox(),logs=new TextBox(),stats=new TextBox();Label state=new Label();Button start=new Button(),stop=new Button();bool busy;Timer timer=new Timer();
    public ManagerForm(){Text="Бскпкрафт — сервер";ClientSize=new Size(940,730);MinimumSize=new Size(850,700);Font=new Font("Segoe UI",10);StartPosition=FormStartPosition.CenterScreen;
        Controls.Add(new Label{Text="Исходная карта — папка Map",Left=20,Top=18,Width=700});map.SetBounds(20,43,770,28);map.Text=Path.GetFullPath(Portable.P("..","Map"));Controls.Add(map);var browse=new Button{Text="Обзор…",Left=800,Top=42,Width=110};Controls.Add(browse);browse.Click+=(s,e)=>{using(var d=new FolderBrowserDialog())if(d.ShowDialog()==DialogResult.OK)map.Text=d.SelectedPath;};
        Controls.Add(new Label{Text="Адрес этого компьютера для игроков (IP / домен)",Left=20,Top=87,Width=800});address.SetBounds(20,112,890,28);address.Text="127.0.0.1";Controls.Add(address);
        start.Text="Запустить";stop.Text="Сохранить и остановить";start.SetBounds(20,160,180,34);stop.SetBounds(215,160,250,34);Controls.Add(start);Controls.Add(stop);state.SetBounds(485,168,425,30);Controls.Add(state);
        Controls.Add(new Label{Text="Состояние мира и игроки",Left=20,Top=211,Width=800});stats.SetBounds(20,236,890,160);stats.Multiline=true;stats.ReadOnly=true;stats.ScrollBars=ScrollBars.Vertical;stats.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;Controls.Add(stats);
        logs.SetBounds(20,413,890,290);logs.Multiline=true;logs.ReadOnly=true;logs.ScrollBars=ScrollBars.Vertical;logs.Anchor=AnchorStyles.Top|AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right;Controls.Add(logs);
        try{var d=new JavaScriptSerializer().Deserialize<Dictionary<string,string>>(File.ReadAllText(Portable.P("manager-settings.json")));map.Text=d["map"];address.Text=d["address"];}catch{}
        host.Line+=s=>{if(!IsDisposed&&IsHandleCreated)BeginInvoke(new Action(()=>{if(logs.TextLength>100000)logs.Text=logs.Text.Substring(logs.TextLength-50000);logs.AppendText(s+Environment.NewLine);}));};
        start.Click+=async(s,e)=>{busy=true;UpdateState();try{host.Start(map.Text,address.Text,29545);Portable.Json(Portable.P("manager-settings.json"),new{map=map.Text,address=address.Text});await host.WaitReady();}catch(Exception ex){MessageBox.Show(this,ex.Message);}finally{busy=false;UpdateState();}};
        stop.Click+=async(s,e)=>{busy=true;UpdateState();try{await host.Stop();}catch(Exception ex){MessageBox.Show(this,ex.Message);}finally{busy=false;UpdateState();}};
        timer.Interval=3000;timer.Tick+=async(s,e)=>{UpdateState();if(!host.Exited&&!busy){try{string json=await Task.Run(()=>host.Api("/health",null));stats.Text=FormatWorld(json);}catch{stats.Text="Ожидание готовности сервера…";}}};timer.Start();UpdateState();
        FormClosing+=async(s,e)=>{if(!host.Exited){e.Cancel=true;if(!busy){busy=true;UpdateState();try{await host.Stop();busy=false;Close();}catch(Exception ex){busy=false;MessageBox.Show(this,ex.Message);}}}};
    }
    static string FormatWorld(string json){try{var d=new JavaScriptSerializer().Deserialize<Dictionary<string,object>>(json);var b=new StringBuilder();b.AppendLine("Готовность: "+(true.Equals(d["ready"])?"готов к подключению":"подготовка"));b.AppendLine("Состояние: "+(true.Equals(d["healthy"])?"исправен":"требует внимания"));var game=(Dictionary<string,object>)d["game"];var players=(object[])game["players"];b.AppendLine("Игроков онлайн: "+players.Length);foreach(Dictionary<string,object> p in players)b.AppendLine("  "+p["name"]+" — HP "+p["health"]);var w=(Dictionary<string,object>)d["world"];foreach(string k in new[]{"tick","entities","running"})if(w.ContainsKey(k))b.AppendLine(k+": "+w[k]);return b.ToString();}catch{return "Сервер работает. Подробности — в журнале.";}}
    void UpdateState(){start.Enabled=host.Exited&&!busy;stop.Enabled=!host.Exited&&!busy;state.Text=host.Exited?"Остановлен":busy?"Запуск / сохранение…":"Работает · 29545 / 29546 / 29547";}
}
#endif
