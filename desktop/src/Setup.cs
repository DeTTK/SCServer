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
    static string PackageMarker(){return Hash+":"+String.Join(":",ToolNames.Select(name=>ToolDigest(Tool(name))));}
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
    internal static void NoLinks(string path) {
        for(string current=Path.GetFullPath(path);!String.IsNullOrEmpty(current);current=Path.GetDirectoryName(current))
            if((Directory.Exists(Extended(current))||File.Exists(Extended(current)))&&(File.GetAttributes(Extended(current))&FileAttributes.ReparsePoint)!=0)
                throw new IOException("В пути есть ссылка на другую папку. Выберите обычную папку установки.");
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
    internal static void Extract(string archive,string stage,Action<string,int> progress,CancellationToken cancel) {
        NoLinks(stage);Directory.CreateDirectory(stage);
        using(var zip=ZipFile.OpenRead(archive)) {
            if(zip.Entries.Count>500000)throw new InvalidDataException("Слишком много файлов в архиве");
            long sum=0;int done=0;var clock=Stopwatch.StartNew();
            foreach(var entry in zip.Entries) {
                cancel.ThrowIfCancellationRequested();sum=checked(sum+entry.Length);
                if(sum>5L*1024*1024*1024)throw new InvalidDataException("Слишком большой распакованный архив");
                int mode=(entry.ExternalAttributes>>16)&0xF000;
                if(mode!=0&&mode!=0x8000&&mode!=0x4000)throw new InvalidDataException("Ссылки в архиве не поддерживаются");
                string path=EntryPath(stage,entry.FullName);NoLinks(path);
                if(String.IsNullOrEmpty(entry.Name))Directory.CreateDirectory(Extended(path));
                else {
                    Directory.CreateDirectory(Extended(Path.GetDirectoryName(path)));
                    using(var input=entry.Open())using(var output=new FileStream(Extended(path),FileMode.CreateNew,FileAccess.Write,FileShare.None))input.CopyTo(output,1024*1024);
                }
                done++;if(clock.ElapsedMilliseconds>250){progress("Распаковывается: "+done+" / "+zip.Entries.Count+" файлов",80+(done*18/zip.Entries.Count));clock.Restart();}
            }
        }
    }
    internal static void RemoveStage(string cache,string stage) {
        if(!Directory.Exists(stage))return;
        if(!String.Equals(Path.GetDirectoryName(Path.GetFullPath(stage)),Path.GetFullPath(cache),StringComparison.OrdinalIgnoreCase)||!Path.GetFileName(stage).StartsWith("unpack-",StringComparison.Ordinal))throw new IOException("Неверный временный путь");
        NoLinks(stage);
        foreach(string child in Directory.EnumerateDirectories(Extended(stage),"*",SearchOption.AllDirectories))NoLinks(child);
        Directory.Delete(Extended(stage),true);
    }
    internal static string Install(string folder,Action<string,int> progress,CancellationToken cancel) {
        folder=Path.GetFullPath(folder);NoLinks(folder);Directory.CreateDirectory(folder);
        string cache=Path.Combine(folder,".setup"),target=Path.Combine(folder,"Local"),marker=Path.Combine(target,".installed-package.sha256"),identity=PackageMarker();
        NoLinks(cache);NoLinks(target);Directory.CreateDirectory(cache);
        using(var lease=new FileStream(Path.Combine(cache,"setup.lock"),FileMode.OpenOrCreate,FileAccess.ReadWrite,FileShare.None)) {
            if(Directory.Exists(target)) {
                if(File.Exists(marker)&&File.ReadAllText(marker).Trim()==identity&&Complete(target)){progress("Комплект уже установлен",100);return target;}
                throw new IOException("В выбранной папке уже есть Local без отметки этой установки. Чтобы сохранить ваши файлы, выберите другую папку.");
            }
            string partial=Path.Combine(cache,"package-"+Hash+".partial");
            long cached=File.Exists(partial)?new FileInfo(partial).Length:0;
            if(new DriveInfo(Path.GetPathRoot(folder)).AvailableFreeSpace<5L*1024*1024*1024-Math.Min(cached,Bytes))throw new IOException("Для установки нужно около 5 ГБ свободного места. Выберите другой диск.");
            Download(partial,progress,cancel);progress("Проверяется скачанный архив…",76);
            if(Digest(partial,cancel)!=Hash){File.Delete(partial);throw new IOException("Архив скачался с ошибкой. Он удалён; нажмите установить ещё раз.");}
            string stage=Path.Combine(cache,"unpack-"+Guid.NewGuid().ToString("N"));
            try {
                Extract(partial,stage,progress,cancel);
                if(!Complete(stage))throw new InvalidDataException("В архиве не хватает файлов клиента, сервера или карты");
                ApplyTools(stage);File.WriteAllText(Path.Combine(stage,".installed-package.sha256"),identity);cancel.ThrowIfCancellationRequested();Directory.Move(stage,target);
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
        if(!File.Exists(Path.Combine(game.Text,"classes.jar"))||!File.Exists(Path.Combine(game.Text,"modassets/assets/bundle/configs.xeon"))||!Directory.Exists(Path.Combine(game.Text,"assets"))){MessageBox.Show(this,"Выберите папку установленной игры. Внутри должны быть classes.jar, assets и modassets.");return;}
        if(String.IsNullOrWhiteSpace(login.Text)||login.Text.Any(char.IsControl)){MessageBox.Show(this,"Введите ник.");return;}
        string path=folder.Text,installedGame=Path.GetFullPath(game.Text),name=login.Text.Trim();cancellation=new CancellationTokenSource();var token=cancellation.Token;install.Enabled=false;cancel.Enabled=true;
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
