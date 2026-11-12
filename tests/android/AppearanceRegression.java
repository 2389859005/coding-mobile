package io.github.abyxs.codingremote.tests;
import android.app.*;import android.content.*;import android.graphics.*;import android.graphics.drawable.*;import android.net.Uri;import android.os.*;import android.view.*;import android.widget.*;import java.lang.reflect.*;import java.io.*;import org.json.*;

/** Theme geometry, bounded wallpaper import, distinct list/chat backgrounds and settings. */
public final class AppearanceRegression extends Instrumentation {
    Activity app;Throwable failure;String style="catppuccin",theme="light",fontFamily="sans-serif";
    interface Check{void run()throws Exception;}
    void require(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    Object call(Object target,String name,Class<?>[] types,Object...args)throws Exception{Class<?> c=target instanceof Class?(Class<?>)target:target.getClass();Method m=c.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target instanceof Class?null:target,args);}
    Object get(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    void ui(Check check)throws Exception{failure=null;runOnMainSync(()->{try{check.run();}catch(Throwable e){failure=e;}});if(failure!=null)throw new Exception(failure);waitForIdleSync();}
    void shot(String name)throws Exception{SystemClock.sleep(250);Bitmap b=getUiAutomation().takeScreenshot();File d=new File(getTargetContext().getExternalFilesDir(null),"ui-captures");d.mkdirs();try(OutputStream out=new FileOutputStream(new File(d,"continue-"+style+"-"+theme+"-"+name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
    View find(View v,String text){if(v instanceof TextView&&text.equals(((TextView)v).getText().toString()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}return null;}
    View described(View v,String text){if(text.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=described(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}return null;}
    void chooseDialogAction(String label)throws Exception{
        android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
        for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(label)){
            if(node.getText()==null||!label.contentEquals(node.getText()))continue;
            for(android.view.accessibility.AccessibilityNodeInfo target=node;target!=null;target=target.getParent())if(target.isClickable()){require(target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),"dialog action clickable");return;}
        }
        throw new AssertionError("Missing dialog action: "+label);
    }
    int colour(String name)throws Exception{Field f=Class.forName("io.github.abyxs.codingremote.NativeUi").getDeclaredField(name);f.setAccessible(true);return f.getInt(null);}
    void checkRenderedPalette()throws Exception{
        Class<?> uiClass=Class.forName("io.github.abyxs.codingremote.NativeUi"),renderer=Class.forName("io.github.abyxs.codingremote.MessageContent");Object ui=get(app,"ui");
        Constructor<?> constructor=renderer.getDeclaredConstructor(Context.class,uiClass);constructor.setAccessible(true);Object content=constructor.newInstance(app,ui);
        LinearLayout parent=new LinearLayout(app);parent.setOrientation(1);String code="const text = \"return 42\";\nreturn 225; // comment";
        call(content,"render",new Class[]{LinearLayout.class,String.class,boolean.class},parent,"  ```javascript\n"+code+"\n  ```",false);
        TextView actual=(TextView)find(parent,code);require(actual!=null,"indented code fence renders as a code block");
        require(actual.getCurrentTextColor()==colour("CODE"),"code base foreground uses theme");android.text.Spanned spans=(android.text.Spanned)actual.getText();
        String[] tokens={"const","\"return 42\"","225","// comment"},roles={"KEYWORD","STRING","EMPHASIS","MUTED"};
        for(int i=0;i<tokens.length;i++){int start=code.indexOf(tokens[i]);android.text.style.ForegroundColorSpan[] colors=spans.getSpans(start,start+tokens[i].length(),android.text.style.ForegroundColorSpan.class);require(colors.length==1&&colors[0].getForegroundColor()==colour(roles[i]),"rendered "+roles[i]+" colour, without nested string recolouring");}
        CharSequence formatted=(CharSequence)call(renderer,"format",new Class[]{String.class},"## Heading\n**Bold** `inline`");android.text.Spanned rich=(android.text.Spanned)formatted;
        require(rich.getSpans(0,7,android.text.style.ForegroundColorSpan.class)[0].getForegroundColor()==colour("ACCENT"),"heading foreground uses secondary accent");
    }
    void checkPalettes()throws Exception{
        Class<?> c=Class.forName("io.github.abyxs.codingremote.NativeUi");
        for(String name:new String[]{"catppuccin","tokyo","everforest"})for(boolean dark:new boolean[]{false,true}){
            call(c,"palette",new Class[]{String.class,boolean.class},name,dark);
            checkRenderedPalette();
            java.util.HashSet<Integer> accents=new java.util.HashSet<>();
            for(String role:new String[]{"GREEN","ACCENT","RUN","RED","KEYWORD","STRING","EMPHASIS"})accents.add(colour(role));
            require(accents.size()>=5,"each palette has independent semantic colours");
            for(String[] pair:new String[][]{{"INK","WHITE"},{"MUTED","CANVAS"},{"MUTED","WHITE"},{"CODE","SOFT"},{"KEYWORD","SOFT"},{"STRING","SOFT"},{"EMPHASIS","SOFT"},{"WAIT","WAIT_BG"},{"DIFF_ADD_TEXT","DIFF_ADD"},{"DIFF_REMOVE_TEXT","DIFF_REMOVE"}}){
                float ratio=(Float)call(c,"contrast",new Class[]{int.class,int.class},colour(pair[0]),colour(pair[1]));require(ratio>=4.49f,name+" "+dark+" "+pair[0]+" contrast "+ratio);
            }
        }
        call(c,"palette",new Class[]{String.class,boolean.class},style,theme.equals("dark"));
        android.content.SharedPreferences prefs=app.getSharedPreferences("ui",0);String saved=prefs.getString("fontFamily","sans-serif");java.util.HashSet<Typeface> fonts=new java.util.HashSet<>();
        for(String family:new String[]{"sans-serif","serif","sans-serif-condensed","monospace"}){
            prefs.edit().putString("fontFamily",family).commit();call(c,"theme",new Class[]{Context.class},app);
            TextView sample=(TextView)call(get(app,"ui"),"text",new Class[]{String.class,int.class,int.class},"字体 Aa",16,colour("INK"));fonts.add(sample.getTypeface());
        }
        require(fonts.size()==4,"four font selections reach actual text widgets");prefs.edit().putString("fontFamily",saved).commit();call(c,"theme",new Class[]{Context.class},app);
    }
    public void onCreate(Bundle args){super.onCreate(args);style=args.getString("colorStyle","catppuccin");theme=args.getString("theme","light");fontFamily=args.getString("fontFamily","sans-serif");start();}
    public void onStart(){Bundle result=new Bundle();Context context=getTargetContext();try{
        context.getSharedPreferences("ui",0).edit().putString("colorStyle",style).putString("fontFamily",fontFamily).putString("appearance",theme).putBoolean("resumeChat",false).putBoolean("overlay",false).putBoolean("reduceMotion",true).putInt("backgroundOpacity_list",40).putInt("backgroundOpacity_chat",20).commit();
        Class<?> backdrop=Class.forName("io.github.abyxs.codingremote.ConversationBackdrop");
        File source=new File(context.getCacheDir(),"wallpaper-fixture.png");Bitmap image=Bitmap.createBitmap(2400,1600,Bitmap.Config.ARGB_8888);Canvas drawing=new Canvas(image);Paint paint=new Paint();paint.setShader(new LinearGradient(0,0,2400,1600,0xff86b8d0,0xffeecb95,Shader.TileMode.CLAMP));drawing.drawRect(0,0,2400,1600,paint);try(OutputStream out=new FileOutputStream(source)){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
        call(backdrop,"save",new Class[]{Context.class,String.class,Uri.class},context,"list",Uri.fromFile(source));call(backdrop,"save",new Class[]{Context.class,String.class,Uri.class},context,"chat",Uri.fromFile(source));
        File stored=(File)call(backdrop,"file",new Class[]{Context.class,String.class},context,"list");BitmapFactory.Options size=new BitmapFactory.Options();size.inJustDecodeBounds=true;BitmapFactory.decodeFile(stored.getPath(),size);require(size.outWidth<=1920,"large background is decoded to bounded size");
        Class<?> secure=Class.forName("io.github.abyxs.codingremote.SecureStore");call(secure,"clear",new Class[]{Context.class},context);
        app=startActivitySync(new Intent().setClassName(context,"io.github.abyxs.codingremote.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ui(()->{checkPalettes();Object ui=get(app,"ui");GradientDrawable surface=(GradientDrawable)call(ui,"surface",new Class[]{int.class,int.class,boolean.class},Color.WHITE,18,true);float density=context.getResources().getDisplayMetrics().density;int radius=18;require(Math.abs(surface.getCornerRadius()-radius*density)<1,"themes preserve card geometry");TextView sample=(TextView)call(ui,"text",new Class[]{String.class,int.class,int.class},"正文",16,Color.BLACK);int spacing=4;require(Math.abs(sample.getLineSpacingExtra()-spacing*density)<1,"themes preserve reading rhythm");});
        JSONObject row=new JSONObject().put("id","fixture").put("title","这是一个需要完整换行显示的长会话名称，用于检查手机窄屏上的两行标题和独立的会话分隔线").put("cwd","D:/Projects/demo").put("status","idle");JSONObject detail=new JSONObject().put("session",row).put("connected",true).put("messages",new JSONArray().put(new JSONObject().put("id","a").put("role","assistant").put("text","## 主题标题\n正文与 **重点文字**、`行内代码`。\n```javascript\nconst theme = \"Hello 你好\";\nreturn 225; // 注释\n```")));
        final java.util.List<String> reconnects=new java.util.concurrent.CopyOnWriteArrayList<>();Class<?> transport=Class.forName("io.github.abyxs.codingremote.MainActivity$Transport");Object net=Proxy.newProxyInstance(transport.getClassLoader(),new Class[]{transport},(p,m,a)->{String route=(String)a[2];if(route.endsWith("/reconnect")){reconnects.add(route);return new JSONObject().put("status","accepted").put("connected",true);}if(route.equals("/api/device"))return new JSONObject().put("hostname","DESKTOP-FIXTURE").put("platform","Windows").put("computerId","fixture");if(route.endsWith("/codex"))return new JSONObject().put("sessions",new JSONArray().put(row).put(new JSONObject(row.toString()).put("id","second").put("title","另一条独立会话"))).put("connected",true);if(route.equals("/api/apps"))return new JSONObject().put("apps",new JSONArray().put(new JSONObject().put("id","codex").put("running",true)).put(new JSONObject().put("id","claude").put("running",true)).put(new JSONObject().put("id","deepseek").put("running",true)).put(new JSONObject().put("id","assistant").put("running",true)));return new JSONObject(detail.toString());});
        ui(()->{set(app,"transport",net);set(app,"origin","https://fixture.example");set(app,"token","fixture");set(app,"backend","codex");call(app,"showTab",new Class[]{String.class},"codex");});SystemClock.sleep(600);shot("list");
        ui(()->{ListView list=(ListView)get(app,"sessionList");TextView title=list.findViewWithTag("session-title");require(title!=null&&title.getLineCount()==2,"long session titles use two visible lines");TextView shortTitle=(TextView)find(list,"另一条独立会话");require(shortTitle!=null&&shortTitle.getLineCount()==1,"short session title keeps one line");
            require(Math.abs(((View)shortTitle.getParent()).getHeight()-shortTitle.getLineHeight()*1.5f)<=2,"short row occupies 1.5 lines");
            require(Math.abs(((View)title.getParent()).getHeight()-title.getLineHeight()*2.5f)<=2,"long row occupies 2.5 lines");
            TextView project=list.findViewWithTag("session-project");require(project!=null&&project.getTextSize()>title.getTextSize(),"project name is larger than session title");require(project.getHeight()<=36*context.getResources().getDisplayMetrics().density+1,"compact directory row above sessions");
            require(Math.abs(list.getDividerHeight()-title.getLineHeight()*.5f)<=1,"half-line row spacing");require(list.getDivider() instanceof ColorDrawable&&((ColorDrawable)list.getDivider()).getColor()==Color.TRANSPARENT,"no straight divider across rounded cards");});
        ui(()->{View anchor=(View)((View)get(app,"connectionLabel")).getParent();call(app,"toggleApplicationMenu",new Class[]{View.class,String.class},anchor,"codex");});SystemClock.sleep(300);shot("client-menu");
        ui(()->{Object menu=get(app,"applicationMenu");require(menu instanceof LinearLayout&&((View)menu).getParent()!=null,"client actions expand in the page");View menuView=(View)menu;View parent=(View)menuView.getParent();require(menuView.getWidth()<=176*context.getResources().getDisplayMetrics().density+1&&menuView.getWidth()<=parent.getWidth(),"client list stays compact and fits the viewport");app.onBackPressed();require(get(app,"applicationMenu")==null,"Back collapses client actions");});
        ui(()->{require(((View)get(app,"screen")).getBackground().getClass()==backdrop,"list wallpaper applied");call(app,"openSession",new Class[]{String.class,String.class},"fixture","主题与背景检查");});SystemClock.sleep(600);shot("chat");
        ui(()->require(((View)get(app,"screen")).getBackground().getClass()==backdrop,"chat wallpaper applied"));
        Drawable list=(Drawable)call(backdrop,"load",new Class[]{Context.class,String.class},context,"list"),chat=(Drawable)call(backdrop,"load",new Class[]{Context.class,String.class},context,"chat");Bitmap a=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888),b=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);list.setBounds(0,0,100,100);chat.setBounds(0,0,100,100);list.draw(new Canvas(a));chat.draw(new Canvas(b));require(a.getPixel(50,50)!=b.getPixel(50,50),"independent opacity changes rendered image");a.recycle();b.recycle();
        ui(()->call(app,"showTab",new Class[]{String.class},"settings"));SystemClock.sleep(500);shot("settings");
        ui(()->{View settings=(View)get(app,"settingsView");require(find(settings,"搜索设置")==null,"no settings search");require(find(settings,"DESKTOP-FIXTURE · Windows")!=null,"bound computer metadata visible");View appearance=find(settings,"外观与主题");((View)appearance.getParent()).performClick();require(find(settings,"界面与正文字体")!=null&&settings.findViewWithTag("appearance-preview")!=null,"font choice and live syntax preview");require(find(settings,"主题风格")==null,"old theme style control removed");require(find(settings,"会话列表背景")==null,"appearance page contains only theme and display options");});shot("appearance");
        ui(()->{app.onBackPressed();View settings=(View)get(app,"settingsView");require(find(settings,"外观与背景")!=null&&find(settings,"永久连接")==null,"settings grouping");TextView version=((View)settings).findViewWithTag("settings-version");require((version.getGravity()&Gravity.CENTER_HORIZONTAL)!=0,"version centred");View notify=find(settings,"任务通知");require(((ViewGroup)notify.getParent()).getChildAt(0) instanceof ImageView,"task notification icon");((View)find(settings,"背景与阅读").getParent()).performClick();require(find(settings,"会话列表背景")!=null&&find(settings,"会话消息背景")!=null,"independent reading page");});shot("reading");
        ui(()->{app.onBackPressed();View settings=(View)get(app,"settingsView");((View)find(settings,"连接详情").getParent()).performClick();require(find(settings,"永久连接")!=null,"permanent network inside details");require(find(settings,"扫描电脑二维码")==null,"no duplicated scan in connection details");View unlink=find(settings,"解除当前绑定");require(((ViewGroup)unlink.getParent()).getChildAt(0) instanceof ImageView,"unbind icon");});shot("connection");
        ui(()->{app.onBackPressed();View settings=(View)get(app,"settingsView");((View)find(settings,"桌面应用").getParent()).performClick();});SystemClock.sleep(600);shot("desktop-apps");
        ui(()->{View settings=(View)get(app,"settingsView");require(find(settings,"重新连接")==null,"desktop rows have no duplicate reconnect button");for(String name:new String[]{"Claude","ChatGPT","DeepSeek","桌面助手"})require(find(settings,name)!=null,"application row remains visible: "+name);});
        String[] providers={"Claude","ChatGPT","DeepSeek","桌面助手"},ids={"claude","codex","deepseek","assistant"};
        for(int i=0;i<providers.length;i++){final String name=providers[i];ui(()->((View)find((View)get(app,"settingsView"),name).getParent()).performClick());SystemClock.sleep(250);chooseDialogAction("重新连接");SystemClock.sleep(500);require(reconnects.contains("/api/apps/"+ids[i]+"/reconnect"),"dialog reconnect dispatch for "+ids[i]);}
        call(secure,"save",new Class[]{Context.class,String.class},context,new JSONObject().put("origin","https://fixture.example").put("token","fixture").put("mode","permanent").put("computerId","fixture").toString());
        ui(()->{app.onBackPressed();((View)find((View)get(app,"settingsView"),"外观与主题").getParent()).performClick();});
        for(String[] change:new String[][]{{"主题配色","Tokyo Night · 东京"},{"界面与正文字体","衬线宋体"},{"显示模式",theme.equals("dark")?"浅色":"深色"}}){
            ActivityMonitor monitor=addMonitor(app.getClass().getName(),null,false);
            ui(()->((View)find((View)get(app,"settingsView"),change[0]).getParent()).performClick());SystemClock.sleep(150);chooseDialogAction(change[1]);
            Activity next=waitForMonitorWithTimeout(monitor,5000);removeMonitor(monitor);require(next!=null,"appearance selection recreates activity");app=next;waitForIdleSync();SystemClock.sleep(250);
            ui(()->{View settings=(View)get(app,"settingsView");require(find(settings,"界面与正文字体")!=null&&settings.findViewWithTag("appearance-preview")!=null,"appearance page is restored after "+change[0]);});
        }
        shot("appearance-retained");
        result.putString("stream","PASS: "+style+" / "+theme+"; rendered syntax colours, four fonts, adaptive session heights, half-line gaps, compact menu, dialog-only reconnect controls and connection settings.\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}finally{if(app!=null)runOnMainSync(()->app.finish());context.getSharedPreferences("ui",0).edit().remove("colorStyle").commit();for(String page:new String[]{"list","chat"})new File(context.getFilesDir(),"background-"+page+".jpg").delete();}}
}
