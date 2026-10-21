package io.github.abyxs.codingremote.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.ListView;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Button;
import android.view.ViewGroup;
import android.graphics.Bitmap;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.*;
import java.util.Map;

/** Device regression using synthetic data; never sends messages or changes saved pairing. */
public final class UiRegression extends Instrumentation {
    private Activity app;
    private Throwable failure;
    private long worstMainMillis;
    private void keyboard(boolean show)throws Exception{
        ui(()->{EditText input=(EditText)get(app,"composer");android.view.inputmethod.InputMethodManager manager=(android.view.inputmethod.InputMethodManager)app.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);if(show){input.requestFocus();manager.showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);}else{manager.hideSoftInputFromWindow(input.getWindowToken(),0);input.clearFocus();}});
        boolean ready=false;for(int i=0;i<40;i++){SystemClock.sleep(100);final boolean[] value={false};ui(()->value[0]=(Boolean)get(app,"keyboardShown"));if(value[0]==show){ready=true;break;}}require(ready,"keyboard visibility changed");
    }
    private static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private Object fresh(String name)throws Exception{Constructor<?> c=Class.forName("io.github.abyxs.codingremote.MainActivity$"+name).getDeclaredConstructor();c.setAccessible(true);return c.newInstance();}
    private Object call(String name,Class<?>[] types,Object...args)throws Exception{Method m=app.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(app,args);}
    private interface Check {void run()throws Exception;}
    private void ui(Check check)throws Exception{
        runOnMainSync(()->{long start=SystemClock.uptimeMillis();try{check.run();}catch(Throwable e){failure=e;}worstMainMillis=Math.max(worstMainMillis,SystemClock.uptimeMillis()-start);});
        if(failure!=null)throw new Exception(failure);waitForIdleSync();
    }
    private static void require(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    private void snapshot(String name)throws Exception{
        waitForIdleSync();SystemClock.sleep(3000);Bitmap bitmap=getUiAutomation().takeScreenshot();require(bitmap!=null,"screenshot available");
        File directory=new File(getTargetContext().getExternalFilesDir(null),"ui-captures");directory.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(directory,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
    }
    private static Button findButton(View view,String description){if(view instanceof Button&&description.equals(String.valueOf(view.getContentDescription())))return (Button)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Button found=findButton(group.getChildAt(i),description);if(found!=null)return found;}}return null;}
    @Override public void onCreate(Bundle args){super.onCreate(args);getTargetContext().getSharedPreferences("coding-remote",0).edit().clear().commit();getTargetContext().getSharedPreferences("ui",0).edit().putString("theme",args.getString("theme","light")).commit();start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            Intent launch=new Intent().setClassName(getTargetContext(),"io.github.abyxs.codingremote.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app=startActivitySync(launch);waitForIdleSync();File captureDir=new File(getTargetContext().getExternalFilesDir(null),"ui-captures");File[] oldCaptures=captureDir.listFiles();if(oldCaptures!=null)for(File file:oldCaptures)if(file.isFile()&&file.getName().endsWith(".png"))file.delete();snapshot("01-connect");
            JSONArray sessions=new JSONArray();
            for(int i=0;i<5000;i++)sessions.put(new JSONObject().put("id","fixture-"+i).put("title","Synthetic session "+i).put("cwd","D:/fixture").put("backend","codex"));
            JSONObject raw=new JSONObject().put("session",new JSONObject().put("id","fixture-0").put("cwd","D:/fixture").put("status","idle").put("requests",new JSONArray()));
            JSONArray messages=new JSONArray();StringBuilder longText=new StringBuilder();for(int i=0;i<10000;i++)longText.append("Long conversation 汉字 😀 ");
            for(int i=0;i<1000;i++)messages.put(new JSONObject().put("id","message-"+i).put("role",i%2==0?"user":"assistant").put("text",i==999?longText.toString():"Earlier message "+i));raw.put("messages",messages);
            Class<?> pageClass=Class.forName("io.github.abyxs.codingremote.SessionPage");Method prepare=pageClass.getDeclaredMethod("prepare",JSONObject.class);prepare.setAccessible(true);
            Object page=prepare.invoke(null,raw);require(((java.util.List<?>)get(page,"rows")).size()<=60,"prepared page bounded");
            ui(()->{
                set(app,"token","fixture");set(app,"origin","https://fixture.invalid");set(app,"paused",true);
                Map lists=(Map)get(app,"listCaches");for(String key:new String[]{"codex","claude"}){Object cache=fresh("ListCache");set(cache,"rows",sessions);set(cache,"loaded",System.currentTimeMillis());lists.put(key,cache);}
                Object chat=fresh("ChatCache");set(chat,"page",page);((Map)get(app,"chatCaches")).put("codex:fixture-0",chat);
                call("showTab",new Class[]{String.class},"codex");
            });
            ui(()->{ListView list=(ListView)get(app,"sessionList");require(list.getAdapter().getCount()==5002,"sessions grouped by default");require(list.getChildCount()<40,"session views recycled");list.setSelectionFromTop(30,0);});
            for(int i=0;i<12;i++){
                ui(()->call("showTab",new Class[]{String.class},"claude"));
                ui(()->call("showTab",new Class[]{String.class},"codex"));
                ui(()->{require(((ListView)get(app,"sessionList")).getFirstVisiblePosition()==30,"list scroll retained");Object cache=((Map)get(app,"listCaches")).get("codex");require(!(Boolean)get(cache,"loading"),"tab uses cache without network reload");});
            }
            ui(()->call("openSession",new Class[]{String.class,String.class},"fixture-0","Long synthetic conversation"));
            ui(()->{require(((View)get(app,"bottom")).getVisibility()==View.GONE,"bottom hidden in conversation");ListView list=(ListView)get(app,"messageList");require(list.getAdapter().getCount()<=60,"message page bounded");require(list.getChildCount()<20,"only visible message views");});
            ui(()->((ListView)get(app,"messageList")).setSelection(0));
            ui(()->{View thumb=(View)get(app,"fastScroller");long t=SystemClock.uptimeMillis();thumb.dispatchTouchEvent(android.view.MotionEvent.obtain(t,t,0,thumb.getWidth()/2,thumb.getHeight()*.8f,0));thumb.dispatchTouchEvent(android.view.MotionEvent.obtain(t,t+40,1,thumb.getWidth()/2,thumb.getHeight()*.8f,0));});
            ui(()->require(((ListView)get(app,"messageList")).getFirstVisiblePosition()>0,"drag actually moves through long transcript"));
            for(int i=0;i<10;i++)ui(()->call("renderSession",new Class[]{pageClass,boolean.class},page,false));
            ui(()->call("showTab",new Class[]{String.class},"codex"));
            ui(()->require(((View)get(app,"bottom")).getVisibility()==View.VISIBLE,"bottom restored on list"));
            // Search, grouping and project filters must keep every matching record reachable.
            ui(()->{((EditText)get(app,"search")).setText("Synthetic session 4999");require(((ListView)get(app,"sessionList")).getAdapter().getCount()==3,"search includes project heading and archive entry");});
            ui(()->{((EditText)get(app,"search")).setText("no-such-title");require(((View)get(app,"emptyState")).getVisibility()==View.VISIBLE,"empty search state");});
            ui(()->{((EditText)get(app,"search")).setText("");require(((ListView)get(app,"sessionList")).getAdapter().getCount()==5002,"group includes all sessions plus the archive entry");});
            ui(()->{ListView list=(ListView)get(app,"sessionList");list.performItemClick(list.getChildAt(0),0,0);require(list.getAdapter().getCount()==2,"project collapses; the archive entry stays reachable");list.performItemClick(list.getChildAt(0),0,0);require(list.getAdapter().getCount()==5002,"project expands");});

            JSONArray sampleSessions=new JSONArray();String[] titles={"重构移动端的聊天界面","为项目添加深色模式","整理 API 文档与示例","优化首页加载速度","修复登录状态同步","设计一组简洁的图标"};
            for(int i=0;i<titles.length;i++)sampleSessions.put(new JSONObject().put("id","preview-"+i).put("title",titles[i]).put("cwd",i<3?"D:/work/coding-remote":"D:/work/design-system").put("backend","codex").put("status",i==0?"active":"idle").put("updatedAt",System.currentTimeMillis()-i*86400000L));
            JSONObject sample=new JSONObject().put("connected",true).put("session",new JSONObject().put("id","preview-0").put("cwd","D:/work/coding-remote").put("status","idle").put("model","桌面默认模型").put("turnCount",1).put("requests",new JSONArray()));
            sample.put("messages",new JSONArray()
                .put(new JSONObject().put("id","p1").put("role","user").put("text","参考这个风格，优化聊天界面。希望更简洁，阅读起来更舒服。"))
                .put(new JSONObject().put("id","p2").put("role","reasoning").put("text","已检查页面结构。\n正在调整阅读间距和操作区布局。"))
                .put(new JSONObject().put("id","p3").put("role","assistant").put("text","已调整页面布局，保留更清晰的信息层级。\n\n### 这次更新\n- 浅色背景与绿色点缀\n- 可折叠的执行记录\n- 固定在底部的消息输入区\n\n```diff\n- background: #ffffff;\n+ background: #f6f7f2;\n+ accent: #27634d;\n```\n现在可以继续查看和调整细节。")));
            Object samplePage=prepare.invoke(null,sample);
            ui(()->{Object cache=((Map)get(app,"listCaches")).get("codex");set(cache,"rows",sampleSessions);set(cache,"grouped",false);set(cache,"query","");set(cache,"first",0);set(cache,"top",0);call("showTab",new Class[]{String.class},"codex");});
            snapshot("02-chats");
            ui(()->((Button)get(app,"groupButton")).performClick());snapshot("03-projects");
            ui(()->{Object cache=((Map)get(app,"listCaches")).get("codex");set(cache,"project","D:/work/design-system");set(cache,"grouped",false);call("showSessions",new Class[]{cache.getClass()},cache);require(((ListView)get(app,"sessionList")).getAdapter().getCount()==4,"project filter keeps the three design-system sessions plus the archive entry");});
            ui(()->{Object cache=((Map)get(app,"listCaches")).get("codex");set(cache,"project","");call("showSessions",new Class[]{cache.getClass()},cache);});
            ui(()->{Object chat=fresh("ChatCache");set(chat,"page",samplePage);((Map)get(app,"chatCaches")).put("codex:preview-0",chat);call("openSession",new Class[]{String.class,String.class},"preview-0",titles[0]);((ListView)get(app,"messageList")).setSelection(0);});
            ui(()->{require(!((Button)get(app,"sendButton")).isEnabled(),"empty composer disabled");((EditText)get(app,"composer")).setText("继续优化间距");require(((Button)get(app,"sendButton")).isEnabled(),"nonempty composer enabled");});
            snapshot("04-conversation");
            ui(()->{ListView list=(ListView)get(app,"messageList");View item=list.getAdapter().getView(1,null,list);Button toggle=findButton(item,"展开思考摘要");require(toggle!=null,"activity collapsed by default");toggle.performClick();View expanded=list.getAdapter().getView(1,null,list);require(((ViewGroup)expanded).getChildCount()>1,"activity expands");});
            snapshot("05-execution");
            ui(()->{ListView list=(ListView)get(app,"messageList");View item=list.getAdapter().getView(2,null,list);Button copy=findButton(item,"复制代码");require(copy!=null,"fenced code has copy control");copy.performClick();android.content.ClipboardManager clipboard=(android.content.ClipboardManager)app.getSystemService(android.content.Context.CLIPBOARD_SERVICE);require(clipboard.getPrimaryClip().getItemAt(0).getText().toString().contains("+ accent: #27634d;"),"clipboard contains actual code");call("showTab",new Class[]{String.class},"codex");call("openSession",new Class[]{String.class,String.class},"preview-0",titles[0]);require(((EditText)get(app,"composer")).getText().toString().equals("继续优化间距"),"draft retained");});
            JSONObject running=new JSONObject(sample.toString());running.getJSONObject("session").put("status","active");Object runningPage=prepare.invoke(null,running);
            ui(()->{call("renderSession",new Class[]{pageClass,boolean.class},runningPage,false);Object cache=((Map)get(app,"chatCaches")).get("codex:preview-0");((java.util.Set)get(cache,"expanded")).add("p2:0");call("renderSession",new Class[]{pageClass,boolean.class},samplePage,false);require(((java.util.Set)get(cache,"expanded")).isEmpty(),"completion folds execution");((java.util.Set)get(cache,"expanded")).add("p2:0");call("renderSession",new Class[]{pageClass,boolean.class},samplePage,false);require(!((java.util.Set)get(cache,"expanded")).isEmpty(),"completed records may be reopened");require(get(app,"fastScroller")!=null,"draggable scroller exists");});
            final int[] viewport={0};ui(()->{viewport[0]=((View)get(app,"messageList")).getHeight();call("setHeaderHidden",new Class[]{boolean.class},true);});SystemClock.sleep(300);
            ui(()->{View header=(View)get(app,"chatHeader");require(header.getAlpha()<.01f&&header.getTranslationY()<=-header.getHeight()+1,"header finishes hidden animation");require(((View)get(app,"messageList")).getHeight()==viewport[0],"header animation retains viewport height");call("setHeaderHidden",new Class[]{boolean.class},false);});SystemClock.sleep(300);
            ui(()->{View header=(View)get(app,"chatHeader");require(header.getAlpha()>.99f&&Math.abs(header.getTranslationY())<1,"header finishes visible animation");});
            JSONObject singleLine=new JSONObject(sample.toString());singleLine.put("messages",new JSONArray().put(new JSONObject().put("id","single-command").put("role","activity").put("text","git status --short")));Object singlePage=prepare.invoke(null,singleLine);
            ui(()->{set(app,"followingLatest",true);call("renderSession",new Class[]{pageClass,boolean.class},singlePage,false);ListView list=(ListView)get(app,"messageList");View item=list.getAdapter().getView(0,null,list);require(findButton(item,"展开执行记录")==null,"single command requires no expand action");});
            boolean commandVisible=false;for(int attempt=0;attempt<30;attempt++){SystemClock.sleep(100);android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root!=null&&!root.findAccessibilityNodeInfosByText("git status --short").isEmpty()){commandVisible=true;break;}}
            require(commandVisible,"single command text visible without expanding");
            ui(()->call("renderSession",new Class[]{pageClass,boolean.class},samplePage,false));
            Class<?> trackerClass=Class.forName("io.github.abyxs.codingremote.CompletionTracker");Constructor<?> trackerCtor=trackerClass.getDeclaredConstructor(String.class);trackerCtor.setAccessible(true);Object tracker=trackerCtor.newInstance("{}");Method update=trackerClass.getDeclaredMethod("update",JSONObject.class);update.setAccessible(true);JSONObject task=new JSONObject().put("id","test-task").put("backend","codex").put("status","completed");JSONObject snapshot=new JSONObject().put("tasks",new JSONArray().put(task));require(((java.util.List)update.invoke(tracker,snapshot)).isEmpty(),"old completed tasks do not notify");task.put("status","active");update.invoke(tracker,snapshot);require(((java.util.List)update.invoke(tracker,new JSONObject().put("tasks",new JSONArray()))).isEmpty(),"missing tasks never complete");task.put("status","completed");require(((java.util.List)update.invoke(tracker,snapshot)).size()==1,"completion notifies once");require(((java.util.List)update.invoke(tracker,snapshot)).isEmpty(),"completion is deduplicated");
            ui(()->{((EditText)get(app,"composer")).setText("");JSONObject pending=new JSONObject().put("origin","https://fixture.invalid").put("path","/api/native/codex/preview-0/send").put("body",new JSONObject());set(app,"pending",pending);call("pendingControls",new Class[]{});require(!((Button)get(app,"sendButton")).isEnabled(),"pending operation blocks send");});snapshot("06-pending");
            ui(()->{set(app,"pending",null);call("pendingControls",new Class[]{});});
            JSONObject approval=new JSONObject(sample.toString());approval.getJSONObject("session").put("requests",new JSONArray().put(new JSONObject().put("id","approval-preview").put("tool","执行命令").put("input","npm run build").put("needsInput",false)));Object approvalPage=prepare.invoke(null,approval);
            ui(()->call("renderSession",new Class[]{pageClass,boolean.class},approvalPage,false));snapshot("07-approval");
            keyboard(true);snapshot("09-keyboard-approval");
            ui(()->{Button send=(Button)get(app,"sendButton");android.graphics.Rect visible=new android.graphics.Rect();require(send.getGlobalVisibleRect(visible)&&visible.height()>=send.getHeight()-2,"send remains visible above IME");require(((View)get(app,"messageList")).getHeight()>40,"transcript retains space above IME");});
            keyboard(false);
            ui(()->call("renderSession",new Class[]{pageClass,boolean.class},samplePage,false));
            keyboard(true);snapshot("10-keyboard");keyboard(false);
            ui(()->call("openSettings",new Class[]{}));snapshot("08-settings");
            require(worstMainMillis<2000,"UI operation below ANR budget");
            result.putString("stream","PASS: 5,000 sessions; long transcript; recycled views; 24 cached tab switches; scroll retention; search; empty state; project filters; group folding; activity folding; code controls; draft retention; composer state; keyboard and approval layout; 10 screenshots; max UI operation "+worstMainMillis+" ms.\n");
            ui(()->app.finish());finish(Activity.RESULT_OK,result);
        }catch(Throwable error){result.putString("stream","FAIL: "+error+"\n");result.putString("stack",android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result);}
    }
}
