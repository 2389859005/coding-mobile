using System;
using System.Drawing;
using System.Text;
using System.Threading;
using System.Windows.Forms;

class CodingTray {
    static void Emit(string value) {Console.WriteLine(value);Console.Out.Flush();}
    static ContextMenuStrip Menu() {
        var menu=new ContextMenuStrip();
        string[] labels={"显示 coding远程","最小化到任务栏","设置","查看日志","退出后台服务"};
        string[] actions={"show","minimize","settings","logs","quit"};
        for(int i=0;i<labels.Length;i++){string action=actions[i];var item=menu.Items.Add(labels[i]);item.Click+=(s,e)=>Emit(action);}
        return menu;
    }
    [STAThread] static int Main(string[] args) {
        Console.OutputEncoding=new UTF8Encoding(false);
        if(args.Length==1&&args[0]=="--self-check") {using(var menu=Menu()){if(menu.Items.Count!=5)return 1;}Emit("tray menu OK");return 0;}
        if(args.Length!=1)return 1;
        Application.EnableVisualStyles();
        using(var dispatcher=new Control())using(var icon=new Icon(args[0]))using(var menu=Menu())using(var tray=new NotifyIcon()) {
            var handle=dispatcher.Handle;
            tray.Icon=icon;tray.Text="coding远程 · 后台服务运行中";tray.ContextMenuStrip=menu;
            tray.DoubleClick+=(s,e)=>Emit("show");tray.Visible=true;Emit("ready");
            var reader=new Thread(()=>{while(Console.ReadLine()!=null){}try{dispatcher.BeginInvoke(new Action(Application.ExitThread));}catch{}});
            reader.IsBackground=true;reader.Start();
            Application.Run();tray.Visible=false;
        }
        return 0;
    }
}
