package io.github.abyxs.codingremote.tests;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Tests the native screens with a synthetic desktop; no real chats or apps change. */
public final class ScanSettingsRegression extends Instrumentation {
    Activity app;String theme;Throwable failure;
    final List<String> paths=new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<JSONObject> edits=new java.util.concurrent.CopyOnWriteArrayList<>();
    interface Work {void run()throws Exception;}
    public void onCreate(Bundle args){super.onCreate(args);theme=args.getString("theme","light");start();}
    Object get(String name)throws Exception {Field f=app.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(app);}
    void set(String name,Object value)throws Exception {Field f=app.getClass().getDeclaredField(name);f.setAccessible(true);f.set(app,value);}
    Object call(String name,Class<?>[] types,Object...args)throws Exception {Method m=app.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(app,args);}
    void ui(Work work)throws Exception {failure=null;runOnMainSync(()->{try{work.run();}catch(Throwable e){failure=e;}});if(failure!=null)throw new Exception(failure);waitForIdleSync();}
    void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    void pause(){SystemClock.sleep(250);waitForIdleSync();}
    View text(View root,String value){if(root instanceof TextView&&((TextView)root).getText().toString().equals(value))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View v=text(((ViewGroup)root).getChildAt(i),value);if(v!=null)return v;}return null;}
    void click(String value)throws Exception {for(int attempt=0;attempt<20;attempt++){AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root!=null)for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText(value)){if(!value.contentEquals(n.getText()==null?"":n.getText()))continue;AccessibilityNodeInfo target=n;while(target!=null&&!target.isClickable())target=target.getParent();if(target!=null&&target.performAction(AccessibilityNodeInfo.ACTION_CLICK)){pause();return;}}pause();}throw new AssertionError("missing action: "+value);}
    void longPress(View view){int[] p=new int[2];view.getLocationOnScreen(p);float x=p[0]+view.getWidth()/2f,y=p[1]+view.getHeight()/2f;long now=SystemClock.uptimeMillis();sendPointerSync(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0));SystemClock.sleep(700);sendPointerSync(MotionEvent.obtain(now,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,y,0));pause();}
    void capture(String name)throws Exception {Bitmap bitmap=getUiAutomation().takeScreenshot();File folder=new File(app.getExternalFilesDir(null),"ui-captures");folder.mkdirs();try(OutputStream out=new FileOutputStream(new File(folder,name+"-"+theme+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();}
    JSONObject response(String path,JSONObject body)throws Exception {
        paths.add(path);
        if(path.equals("/api/device"))return new JSONObject().put("computerId","fixture").put("name","coding远程").put("version","2.2.2");
        if(path.contains("/edit")){
            if(body!=null){edits.add(new JSONObject(body.toString()));return new JSONObject().put("status","accepted");}
            return new JSONObject().put("text","Original message").put("messageId","u1").put("eventId","u1").put("turnId","t1").put("editBase","fixture-revision");
        }
        if(path.equals("/api/apps"))return new JSONObject().put("apps",new JSONArray());
        if(path.contains("/api/workspace/state"))return new JSONObject().put("status","saved").put("revision",body==null?1:body.optLong("revision")+1).put("value",body==null?new JSONObject():body.optJSONObject("value"));
        if(path.contains("catalog"))return new JSONObject().put("models",new JSONArray()).put("skills",new JSONArray());
        if(path.contains("/fixture"))return new JSONObject().put("connected",true).put("session",new JSONObject().put("id","fixture").put("title","消息编辑检查").put("status","idle").put("requests",new JSONArray())).put("messages",new JSONArray().put(new JSONObject().put("id","u1:0").put("sourceId","u1").put("role","user").put("turnId","t1").put("text","Original message")).put(new JSONObject().put("id","a1").put("role","assistant").put("turnId","t1").put("text","A reply from the desktop."))).put("queue",new JSONArray());
        return new JSONObject().put("connected",true).put("sessions",new JSONArray().put(new JSONObject().put("id","fixture").put("title","消息编辑检查").put("cwd","D:/fixture"))).put("token","fixture");
    }
    public void onStart(){Bundle result=new Bundle();try{
        Context c=getTargetContext();Class<?> secure=Class.forName("io.github.abyxs.codingremote.SecureStore");Method clear=secure.getDeclaredMethod("clear",Context.class);clear.setAccessible(true);clear.invoke(null,c);
        c.getSharedPreferences("ui",0).edit().putString("appearance",theme).putBoolean("notifications",false).putBoolean("overlay",false).putBoolean("petInChat",false).putBoolean("resumeChat",false).putBoolean("scanOnlyV4",true).commit();c.getSharedPreferences("pending",0).edit().clear().commit();c.getSharedPreferences("message-edits",0).edit().clear().commit();
        app=startActivitySync(new Intent().setClassName(c,"io.github.abyxs.codingremote.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Class<?> transport=Class.forName("io.github.abyxs.codingremote.MainActivity$Transport");Object mock=java.lang.reflect.Proxy.newProxyInstance(transport.getClassLoader(),new Class[]{transport},(p,m,args)->response((String)args[2],(JSONObject)args[3]));
        ui(()->{set("transport",mock);set("origin","https://fixture.invalid");set("token","fixture-token");call("showTab",new Class[]{String.class},"codex");});pause();
        for(String provider:new String[]{"claude","codex","deepseek"}){
            ui(()->call("showTab",new Class[]{String.class},provider));pause();
            require(((View)get("bottomShell")).getVisibility()==View.VISIBLE,"list bar stays visible");
            for(String key:new String[]{"claude","codex","deepseek"}){ImageView mark=((View)get("bottom")).findViewWithTag("provider-tab-"+key);require(mark!=null&&mark.isShown()&&mark.getDrawable() instanceof android.graphics.drawable.BitmapDrawable,"real provider icon "+key);}
        }
        capture("scan-v4-app-list");
        for(String provider:new String[]{"claude","codex"}){
            ui(()->{call("showTab",new Class[]{String.class},provider);call("openSession",new Class[]{String.class,String.class},"fixture","消息编辑检查");});pause();pause();
            require(((View)get("bottomShell")).getVisibility()==View.GONE,"chat has no bottom bar");
            require(((View)get("screen")).findViewWithTag("provider-message-"+provider)!=null,"message provider mark");
            ui(()->((EditText)get("composer")).setText("Preserved draft"));
            View user=text((View)get("screen"),"Original message");require(user!=null,"user message on screen");longPress(user);click("撤回编辑再发送");click("开始编辑");
            require(((EditText)get("composer")).getText().toString().equals("Original message"),"full original text becomes draft");
            int writesBeforeCancel=edits.size();
            ui(()->((EditText)get("composer")).setText("Unsent edit"));
            ui(()->call("showTab",new Class[]{String.class},"settings"));pause();
            ui(()->{call("showTab",new Class[]{String.class},provider);call("openSession",new Class[]{String.class,String.class},"fixture","消息编辑检查");});pause();
            require(((EditText)get("composer")).getText().toString().equals("Unsent edit"),"edit draft survives page switch");
            ui(()->((View)get("screen")).findViewWithTag("message-edit-banner").performClick());pause();
            require(((EditText)get("composer")).getText().toString().equals("Preserved draft"),"cancel restores previous draft");
            require(edits.size()==writesBeforeCancel,"cancel never changes desktop history");
            longPress(text((View)get("screen"),"Original message"));click("撤回编辑再发送");click("开始编辑");
            ui(()->((EditText)get("composer")).setText("Edited "+provider));capture("scan-v4-edit-"+provider);
            ui(()->((View)get("sendButton")).performClick());pause();pause();
            require(edits.size()==(provider.equals("claude")?1:2),"one edit write per user action");JSONObject sent=edits.get(edits.size()-1);require(sent.getString("messageId").equals("u1")&&sent.getBoolean("confirmed")&&sent.getString("text").equals("Edited "+provider),"edit carries target and new text");
            require(((EditText)get("composer")).getText().toString().equals("Preserved draft"),"previous draft restored after accepted edit");
        }
        ui(()->call("showTab",new Class[]{String.class},"settings"));pause();capture("scan-v4-settings");
        View settings=(View)get("settingsView");require(text(settings,"连接详情")!=null&&text(settings,"背景与阅读")!=null&&text(settings,"永久连接")==null,"scan settings hub");require(text(settings,"SSH 主机连接")==null&&text(settings,"局域网")==null,"removed connection modes absent");
        require(text(settings,"应用")==null&&text(settings,"存储与关于")==null,"removed app settings group absent");
        click("外观与主题");capture("scan-v4-settings-appearance");require(text(settings,"显示模式")!=null,"appearance detail page");ui(()->app.onBackPressed());require(text(settings,"连接详情")!=null,"back returns to settings hub");
        require(findSearch((View)get("settingsView"))==null,"settings search has been removed");require(text(settings,"通知记录")==null,"notification history removed");
        result.putString("stream","PASS: permanent bitmap app icons on lists; provider icons on messages; chat bottom bar hidden; actual user-text long press; Claude/ChatGPT edit writes with prior draft restored; settings without search and with detail/back navigation.\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}finally{if(app!=null)runOnMainSync(()->app.finish());}}
    EditText findSearch(View view){if(view instanceof EditText&&"搜索设置".contentEquals(view.getContentDescription()))return (EditText)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){EditText field=findSearch(((ViewGroup)view).getChildAt(i));if(field!=null)return field;}return null;}
}
