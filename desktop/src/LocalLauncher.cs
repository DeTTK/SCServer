using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Diagnostics;
using System.Drawing;
using System.Threading.Tasks;
using System.Windows.Forms;
using System.Web.Script.Serialization;

static class LocalProgram {
    [STAThread] static void Main(string[] args) {
        if(args.Length==1&&args[0]=="--smoke-server"){
            Portable.Root=Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"Server");
            var host=new ServerHost();
            try{
                host.Start(Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"Map"),"127.0.0.1",39545);
                host.WaitReady().GetAwaiter().GetResult();
                string health=host.Api("/health",null);
                string login=Portable.Http("http://127.0.0.1:39545/session/game",null,"login=OneClickCheck");
                if(!login.Contains("session"))throw new IOException("Native session was not issued");
                host.Stop().GetAwaiter().GetResult();
                Portable.Json(Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"launcher-smoke.json"),new{ok=true,stopped=host.Exited,nativeLogin=true,health=health});
            }catch(Exception e){try{host.Stop().GetAwaiter().GetResult();}catch{}Portable.Json(Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"launcher-smoke.json"),new{ok=false,error=e.ToString()});Environment.ExitCode=1;}return;
        }
        if(args.Length==2&&args[0]=="--verify") {
            try { LocalForm.Check(args[1]);Console.WriteLine("PASS: complete local bundle and installed game; bundled Java, no PATH needed"); }
            catch(Exception e){Console.Error.WriteLine(e.Message);Environment.ExitCode=1;}return;
        }
        Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new LocalForm());
    }
}
sealed class LocalForm:Form {
    static readonly string Root=AppDomain.CurrentDomain.BaseDirectory;
    readonly TextBox game=new TextBox(),login=new TextBox(),log=new TextBox();
    readonly Button play=new Button(),stop=new Button();readonly Label status=new Label();
    readonly ServerHost host=new ServerHost();bool busy;Process client;
    public LocalForm() {
        Portable.Root=Path.Combine(Root,"Server");
        Text="Бскпкрафт — локальная игра";ClientSize=new Size(660,450);MinimumSize=new Size(660,450);
        Font=new Font("Segoe UI",10);StartPosition=FormStartPosition.CenterScreen;
        Controls.Add(new Label{Text="Папка установленной игры",Left=20,Top=18,Width=610});
        game.SetBounds(20,46,510,28);Controls.Add(game);
        var browse=new Button{Text="Выбрать…",Left=540,Top=45,Width=100,Height=30};Controls.Add(browse);
        browse.Click+=(s,e)=>{using(var d=new FolderBrowserDialog{Description="Выберите установленную игру. Внутри должны быть assets, modassets и classes.jar."})if(d.ShowDialog()==DialogResult.OK)game.Text=FindGame(d.SelectedPath);};
        Controls.Add(new Label{Text="Ваш ник",Left=20,Top=90,Width=610});login.SetBounds(20,117,620,28);login.Text="Player";Controls.Add(login);
        play.Text="Играть в локалку";play.SetBounds(20,163,405,40);Controls.Add(play);
        stop.Text="Сохранить и остановить";stop.SetBounds(435,163,205,40);stop.Enabled=false;Controls.Add(stop);
        status.SetBounds(20,216,620,48);status.Text="Выберите игру и нажмите «Играть». Сервер запустится сам.";Controls.Add(status);
        log.SetBounds(20,273,620,155);log.Multiline=true;log.ReadOnly=true;log.ScrollBars=ScrollBars.Vertical;log.Anchor=AnchorStyles.Top|AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right;Controls.Add(log);
        try { var saved=new JavaScriptSerializer().Deserialize<System.Collections.Generic.Dictionary<string,string>>(File.ReadAllText(Path.Combine(Root,"local-settings.json")));game.Text=saved["game"];login.Text=saved["login"]; }catch{}
        if(String.IsNullOrWhiteSpace(game.Text))foreach(string path in new[]{Path.Combine(Root,"Game"),Path.Combine(Root,"game")})if(ValidGame(path)){game.Text=path;break;}
        host.Line+=line=>{if(!IsDisposed&&IsHandleCreated)BeginInvoke(new Action(()=>{if(log.TextLength>60000)log.Text=log.Text.Substring(log.TextLength-30000);log.AppendText(line+Environment.NewLine);}));};
        play.Click+=async(s,e)=>await Play();stop.Click+=async(s,e)=>await Stop();
        FormClosing+=async(s,e)=>{if(!host.Exited){e.Cancel=true;if(!busy){await Stop();if(host.Exited)Close();}}};
    }
    static bool ValidGame(string path){return File.Exists(Path.Combine(path,"classes.jar"))&&File.Exists(Path.Combine(path,"modassets/assets/bundle/configs.xeon"))&&Directory.Exists(Path.Combine(path,"assets"));}
    static string FindGame(string root){foreach(string child in new[]{"","game","client","contents"}){string p=Path.Combine(root,child);if(ValidGame(p))return p;}return root;}
    internal static void Check(string game) {
        foreach(string name in new[]{"Client/Launcher.exe","Client/jre/bin/javaw.exe","Client/compat/game-resource-compat.jar","Server/server.jar","Server/jre/bin/java.exe","Server/server.template.properties","Map/registry.json","Map/region"})
            if(!File.Exists(Path.Combine(Root,name))&&!Directory.Exists(Path.Combine(Root,name)))throw new IOException("Комплект распакован не полностью: нет "+name+". Распакуйте весь архив локалки в одну папку и запускайте Играть.exe оттуда. ZIP с исходниками с GitHub для этого не подходит.");
        if(!ValidGame(game))throw new IOException("Выбрана не папка установленной игры. Нужна папка с classes.jar, assets и modassets. Нажмите «Выбрать…».");
    }
    async Task Play() {
        if(busy)return;busy=true;play.Enabled=false;
        try {
            game.Text=FindGame(game.Text);Check(game.Text);
            if(String.IsNullOrWhiteSpace(login.Text)||login.Text.Any(char.IsControl))throw new IOException("Введите ник.");
            if(client!=null&&!client.HasExited)throw new IOException("Клиент уже запущен. Вернитесь в его окно.");
            Portable.Json(Path.Combine(Root,"local-settings.json"),new{game=Path.GetFullPath(game.Text),login=login.Text.Trim()});
            if(host.Exited){status.Text="Запускается сервер. Первая загрузка карты может занять несколько минут…";await Task.Run(()=>host.Start(Path.Combine(Root,"Map"),"127.0.0.1",29545));}
            await host.WaitReady();status.Text="Сервер готов. Запускается игра…";
            string folder=Path.Combine(Root,"Client");
            var args=new[]{"--play",Path.GetFullPath(game.Text),"127.0.0.1:29545",login.Text.Trim()};
            client=Process.Start(new ProcessStartInfo(Path.Combine(folder,"Launcher.exe"),String.Join(" ",args.Select(Portable.Q))){WorkingDirectory=folder,UseShellExecute=false,CreateNoWindow=true});
            status.Text="Игра запущена. Это окно оставьте открытым: здесь работает сервер.";
        }catch(Exception error){status.Text=error.Message;MessageBox.Show(this,error.Message,"Не удалось запустить",MessageBoxButtons.OK,MessageBoxIcon.Warning);}
        finally{busy=false;play.Enabled=true;stop.Enabled=!host.Exited;}
    }
    async Task Stop(){if(busy)return;busy=true;play.Enabled=stop.Enabled=false;try{status.Text="Сохраняется мир…";await host.Stop();status.Text="Сервер остановлен, мир сохранён.";}catch(Exception e){status.Text=e.Message;MessageBox.Show(this,e.Message);}finally{busy=false;play.Enabled=true;stop.Enabled=!host.Exited;}}
}
