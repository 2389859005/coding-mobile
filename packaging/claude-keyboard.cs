// Product component. Only a verified Claude DevTools Console may receive input.
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Automation;

class ClaudeKeyboard {
    [StructLayout(LayoutKind.Sequential)] struct INPUT { public uint type; public UNION data; }
    [StructLayout(LayoutKind.Explicit)] struct UNION {
        [FieldOffset(0)] public KEYBDINPUT key;
        [FieldOffset(0)] public MOUSEINPUT mouse;
    }
    [StructLayout(LayoutKind.Sequential)] struct KEYBDINPUT { public ushort vk,scan; public uint flags,time; public UIntPtr extra; }
    [StructLayout(LayoutKind.Sequential)] struct MOUSEINPUT { public int x,y; public uint data,flags,time; public UIntPtr extra; }
    [DllImport("user32.dll")] static extern uint SendInput(uint n, INPUT[] input, int size);
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window, out uint pid);
    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr window);
    [DllImport("user32.dll")] static extern bool BringWindowToTop(IntPtr window);
    [DllImport("user32.dll")] static extern bool AttachThreadInput(uint first,uint second,bool attach);
    [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr window,int mode);
    [DllImport("user32.dll")] static extern short GetAsyncKeyState(int key);
    delegate bool EnumWindow(IntPtr window,IntPtr data);
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumWindow callback,IntPtr data);
    [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr window,StringBuilder text,int count);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr window);
    [DllImport("user32.dll")] static extern bool IsWindow(IntPtr window);
    [DllImport("user32.dll")] static extern bool PostMessage(IntPtr window,uint message,IntPtr wparam,IntPtr lparam);
    static string executable;
    static void Stage(string text) {Console.WriteLine(text);Console.Out.Flush();}
    static void Activate(IntPtr window) {
        uint ignored,current=GetCurrentThreadId(),foreground=GetWindowThreadProcessId(GetForegroundWindow(),out ignored);
        bool attached=foreground!=0&&foreground!=current&&AttachThreadInput(current,foreground,true);
        try {ShowWindow(window,9);BringWindowToTop(window);SetForegroundWindow(window);}
        finally {if(attached)AttachThreadInput(current,foreground,false);}
        Thread.Sleep(300);
        CheckForeground();
    }
    static bool Owned(int pid) {
        try { var p=Process.GetProcessById(pid); return p.ProcessName.Equals("Claude",StringComparison.OrdinalIgnoreCase) && p.MainModule.FileName.Equals(executable,StringComparison.OrdinalIgnoreCase); }
        catch { return false; }
    }
    static void CheckForeground() {
        if ((GetAsyncKeyState(27)&0x8001)!=0) throw new Exception("已取消键盘连接，点击 Claude 标题选择启动可重试");
        uint pid; GetWindowThreadProcessId(GetForegroundWindow(),out pid);
        if (!Owned((int)pid)) throw new Exception("焦点已离开 Claude（窗口进程 "+pid+"），自动输入已取消");
    }
    static bool PromptName(string name,string id) {
        return id=="console-prompt" || name=="Console prompt" || name=="控制台提示" || name=="控制台提示符";
    }
    static bool IsPrompt(AutomationElement e) {
        return e!=null && Owned(e.Current.ProcessId) && PromptName(e.Current.Name,e.Current.AutomationId);
    }
    static void CheckPrompt(AutomationElement prompt) {
        CheckForeground();
        var focused=AutomationElement.FocusedElement;
        for(int i=0;i<5 && focused!=null;i++,focused=TreeWalker.ControlViewWalker.GetParent(focused))
            if (Automation.Compare(prompt,focused)||IsPrompt(focused)) return;
        throw new Exception("Console 输入焦点已改变，自动输入已取消");
    }
    static void FocusPrompt(AutomationElement prompt) {
        prompt.SetFocus();
        for(int i=0;i<10;i++){Thread.Sleep(100);CheckForeground();try{CheckPrompt(prompt);return;}catch{}}
        CheckPrompt(prompt);
    }
    static INPUT Key(ushort vk,bool up) { var i=new INPUT(); i.type=1;i.data.key.vk=vk;i.data.key.flags=up?2u:0u;return i; }
    static void Send(INPUT[] keys) {
        if(SendInput((uint)keys.Length,keys,Marshal.SizeOf(typeof(INPUT)))!=keys.Length) throw new Exception("Windows 拒绝键盘输入，请检查 Claude 与助手的权限是否一致");
    }
    static void Shortcut() { CheckForeground();Send(new[]{Key(17,false),Key(18,false),Key(73,false),Key(73,true),Key(18,true),Key(17,true)}); }
    static AutomationElement ConsolePrompt() {
        return ConsolePrompt(AutomationElement.FromHandle(GetForegroundWindow()));
    }
    static AutomationElement ConsolePrompt(AutomationElement root) {
        var elements=root.FindAll(TreeScope.Descendants,new OrCondition(
            new PropertyCondition(AutomationElement.AutomationIdProperty,"console-prompt"),
            new PropertyCondition(AutomationElement.NameProperty,"Console prompt"),
            new PropertyCondition(AutomationElement.NameProperty,"控制台提示"),
            new PropertyCondition(AutomationElement.NameProperty,"控制台提示符")));
        foreach(AutomationElement e in elements) if(IsPrompt(e)&&!e.Current.IsOffscreen) return e;
        return null;
    }
    static bool DevToolsTitle(string title) {
        return title.StartsWith("Developer Tools - ",StringComparison.Ordinal)||title.StartsWith("DevTools - ",StringComparison.Ordinal)||title.StartsWith("开发者工具 - ",StringComparison.Ordinal);
    }
    static void CloseDevTools() {
        // Called only after the parent verifies the signed bridge heartbeat.
        // Detached tools close by their exact Claude-owned window, without
        // activating another app or ever sending a close to Claude's main UI.
        var windows=new System.Collections.Generic.List<IntPtr>();
        EnumWindows(delegate(IntPtr window,IntPtr data){uint pid;GetWindowThreadProcessId(window,out pid);if(IsWindowVisible(window)&&Owned((int)pid))windows.Add(window);return true;},IntPtr.Zero);
        int closed=0;
        foreach(var window in windows) {
            if(!IsWindow(window))continue;
            var title=new StringBuilder(512);GetWindowText(window,title,title.Capacity);
            if(DevToolsTitle(title.ToString())) {
                if(!PostMessage(window,0x0010,IntPtr.Zero,IntPtr.Zero))throw new Exception("Claude 已连接，但关闭开发者工具失败");
                for(int i=0;i<30&&IsWindow(window);i++)Thread.Sleep(100);
                if(IsWindow(window))throw new Exception("Claude 已连接，开发者工具尚未关闭");
                closed++;continue;
            }
            var root=AutomationElement.FromHandle(window);
            var close=root.FindFirst(TreeScope.Descendants,new AndCondition(new PropertyCondition(AutomationElement.ControlTypeProperty,ControlType.Button),new OrCondition(new PropertyCondition(AutomationElement.NameProperty,"Close DevTools"),new PropertyCondition(AutomationElement.NameProperty,"关闭开发者工具"))));
            object pattern;
            if(close!=null&&Owned(close.Current.ProcessId)&&!close.Current.IsOffscreen&&close.TryGetCurrentPattern(InvokePattern.Pattern,out pattern)) {((InvokePattern)pattern).Invoke();closed++;continue;}
            if(ConsolePrompt(root)==null)continue;
            // A docked Console has no separate HWND. Never toggle the shortcut
            // unless the verified Console is still present in this window.
            GetAsyncKeyState(27);Activate(window);Shortcut();Thread.Sleep(300);
            if(ConsolePrompt(root)!=null)throw new Exception("Claude 已连接，开发者工具尚未关闭");
            closed++;
        }
        Stage("DevTools closed: "+closed);
    }
    static void SelectConsoleTab() {
        var root=AutomationElement.FromHandle(GetForegroundWindow());
        var tabs=root.FindAll(TreeScope.Descendants,new PropertyCondition(AutomationElement.ControlTypeProperty,ControlType.TabItem));
        foreach(AutomationElement tab in tabs) {
            string name=tab.Current.Name;
            if((name=="Console"||name=="控制台")&&!tab.Current.IsOffscreen&&Owned(tab.Current.ProcessId)) {
                object pattern;
                if(tab.TryGetCurrentPattern(SelectionItemPattern.Pattern,out pattern)) ((SelectionItemPattern)pattern).Select();
                return;
            }
        }
    }
    static string ReadContents(string text,string value) {
        // Chromium contenteditable exposes an empty ValuePattern even when its
        // TextPattern contains the entire CodeMirror document.
        if(!String.IsNullOrEmpty(text)) return text;
        if(value!=null) return value;
        if(text!=null) return text;
        throw new Exception("无法核对 Console 内容，已停止输入");
    }
    static string Contents(AutomationElement prompt) {
        object pattern;string text=null,value=null;
        if(prompt.TryGetCurrentPattern(TextPattern.Pattern,out pattern)) text=((TextPattern)pattern).DocumentRange.GetText(-1);
        if(!String.IsNullOrEmpty(text))return text.TrimEnd('\r','\n');
        if(prompt.TryGetCurrentPattern(ValuePattern.Pattern,out pattern)) value=((ValuePattern)pattern).Current.Value;
        return ReadContents(text,value).TrimEnd('\r','\n');
    }
    static void Verify(AutomationElement prompt,string expected,string path) {
        string actual="";
        for(int attempt=0;attempt<40;attempt++) {
            Thread.Sleep(50);CheckPrompt(prompt);actual=Contents(prompt);
            if(actual==expected)return;
        }
        File.WriteAllText(path+".observed",actual,new UTF8Encoding(false));
        Stage("Console 校验：预期 "+expected.Length+" 字符，读取 "+actual.Length+" 字符");
        throw new Exception("Console 本段输入未完整到达，未执行；再次连接会自动恢复连接脚本");
    }
    static void Type(AutomationElement prompt,string text,string path) {
        for(int offset=0;offset<text.Length;offset+=128) {
            CheckPrompt(prompt);int count=Math.Min(128,text.Length-offset);
            // Small paced bursts avoid saturating Chromium's event queue. Full
            // prefix verification happens every 128 characters; larger chunks
            // were observed to lose a whole segment in real Chromium consoles.
            for(int burst=0;burst<count;burst+=32) {
            CheckForeground();int length=Math.Min(32,count-burst);var keys=new INPUT[length*2];
            for(int n=0;n<length;n++) {
                var down=new INPUT();down.type=1;down.data.key.scan=text[offset+burst+n];down.data.key.flags=4;
                var up=down;up.data.key.flags=6;keys[2*n]=down;keys[2*n+1]=up;
            }
            Send(keys);
            Thread.Sleep(8);
            }
            // Backpressure is essential: SendInput success only means queued,
            // not that a long, syntax-highlighted Console accepted the text.
            Verify(prompt,text.Substring(0,offset+count),path);
            if((offset+count)%1024==0)Stage("已核对 "+(offset+count)+" / "+text.Length+" 字符");
        }
    }
    [STAThread] static int Main(string[] args) {
        Console.OutputEncoding=new UTF8Encoding(false);
        try {
            if(args.Length==1&&args[0]=="--self-check") {
                if(Marshal.SizeOf(typeof(INPUT))!=(IntPtr.Size==8?40:28)||!PromptName("Console prompt","")||PromptName("Message Claude","")) throw new Exception("键盘组件自检失败");
                if(ReadContents("script","")!="script"||ReadContents("","value")!="value"||ReadContents(null,"value")!="value"||ReadContents("",null)!="")throw new Exception("Console 文本读取自检失败");
                if(!DevToolsTitle("Developer Tools - app://localhost/new")||!DevToolsTitle("DevTools - app://localhost")||DevToolsTitle("Claude"))throw new Exception("开发者工具窗口匹配自检失败");
                Console.WriteLine("keyboard ABI, Console allowlist and contenteditable text fallback OK");return 0;
            }
            if(args.Length!=2) throw new Exception("键盘连接参数无效");
            var process=Process.GetProcessById(Int32.Parse(args[0]));executable=process.MainModule.FileName;
            if(!Owned(process.Id)) throw new Exception("目标不是 Claude");
            if(args[1]=="--close-devtools") {CloseDevTools();return 0;}
            string text=File.ReadAllText(args[1],Encoding.UTF8);
            if(text.Length>300000||text.Contains("\n")||text.Contains("\r")||!text.StartsWith("/* coding remote script */")) throw new Exception("连接脚本格式无效");
            Stage("正在等待 Claude 窗口");
            IntPtr window=process.MainWindowHandle;
            var readyDeadline=DateTime.UtcNow.AddSeconds(20);
            while(window==IntPtr.Zero&&DateTime.UtcNow<readyDeadline&&!process.HasExited){Thread.Sleep(250);process.Refresh();window=process.MainWindowHandle;}
            if(window==IntPtr.Zero) throw new Exception("Claude 窗口尚未准备好");
            GetAsyncKeyState(27); // Clear an old Escape press, subsequent presses cancel.
            Activate(window);
            Stage("已定位 Claude 窗口");
            var prompt=ConsolePrompt();
            if(prompt==null) Shortcut();
            Stage("等待 Console 输入框");
            var deadline=DateTime.UtcNow.AddSeconds(12);
            while(prompt==null&&DateTime.UtcNow<deadline) {
                Thread.Sleep(250);CheckForeground();SelectConsoleTab();prompt=ConsolePrompt();
            }
            if(prompt==null) throw new Exception("找不到 Claude Console 输入框，请确认已允许开发者工具后通过标题重试");
            Stage("已定位 Console 输入框");
            string pending=Contents(prompt).Trim('\r','\n',' ','\t','\u200b','\ufeff');
            if(!String.IsNullOrWhiteSpace(pending)) {
                // Recover only a draft emitted by this connector, preserving a
                // backup. Arbitrary user Console commands are never overwritten.
                if(!(pending.StartsWith("void eval(\"/* Injected into the Claude renderer")&&pending.Contains("__claudeMobileBridge"))&&!pending.StartsWith("void eval(\"/* coding remote loader */")&&!pending.StartsWith("/* coding remote script */"))
                    throw new Exception("Console 已有未提交内容，已保留（长度 "+pending.Length+"，首字符 U+"+((int)pending[0]).ToString("X4")+"）；清空后通过 Claude 标题重试");
                File.WriteAllText(args[1]+".previous",pending,new UTF8Encoding(false));
                FocusPrompt(prompt);Send(new[]{Key(17,false),Key(65,false),Key(65,true),Key(17,true),Key(8,false),Key(8,true)});
                Verify(prompt,"",args[1]);
                Stage("已备份并清除上次未提交的连接脚本");
            }
            FocusPrompt(prompt);Type(prompt,text,args[1]);CheckPrompt(prompt);
            Stage("脚本输入完成，正在核对");
            Verify(prompt,text,args[1]);
            Send(new[]{Key(13,false),Key(13,true)});Thread.Sleep(700);
            Console.WriteLine("脚本已输入，等待连接回执");return 0;
        } catch(Exception e) {Console.WriteLine(e is System.ComponentModel.Win32Exception?"无法读取 Claude 窗口，请检查运行权限":e.Message);return 1;}
    }
}
