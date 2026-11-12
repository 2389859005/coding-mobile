package io.github.abyxs.codingremote.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.lang.reflect.*;
import java.util.*;

/** Retained view identity and the standalone session terminal. */
public final class LiveWorkspaceRegression extends Instrumentation {
    Activity app;Throwable failure;
    interface Check{void run()throws Exception;}
    Object get(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    void set(Object object,String name,Object value)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    Object call(Object object,String name,Class<?>[] types,Object...args)throws Exception{Class<?> type=object instanceof Class?(Class<?>)object:object.getClass();Method method=type.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(object instanceof Class?null:object,args);}
    void ui(Check action)throws Exception{failure=null;runOnMainSync(()->{try{action.run();}catch(Throwable e){failure=e;}});if(failure!=null)throw new Exception(failure);waitForIdleSync();}
    void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    void await(Check check)throws Exception{Throwable last=null;for(int i=0;i<80;i++){try{check.run();return;}catch(Throwable e){last=e;}SystemClock.sleep(100);}throw new Exception(last);}
    public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    public void onStart(){
        Bundle output=new Bundle();Context context=getTargetContext();
        try{
            context.getSharedPreferences("ui",0).edit().putBoolean("overlay",false).putBoolean("notifications",false).putBoolean("resumeChat",false).commit();
            Class<?> secure=Class.forName("io.github.abyxs.codingremote.SecureStore");call(secure,"clear",new Class[]{Context.class},context);
            app=startActivitySync(new Intent().setClassName(context,"io.github.abyxs.codingremote.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            String sid="11111111-1111-4111-8111-111111111111";
            JSONObject fixture=new JSONObject().put("session",new JSONObject().put("id",sid).put("title","常驻会话页面").put("cwd","D:/fixture").put("status","idle").put("model","fixture"))
                .put("messages",new JSONArray().put(new JSONObject().put("id","message-1").put("role","assistant").put("text","页面切换会保留输入、阅读位置和终端。")))
                .put("steps",new JSONArray().put(new JSONObject().put("content","保留的执行步骤").put("status","completed"))).put("connected",true).put("token","fixture-token").put("revision","fixture-revision");
            Class<?> transport=Class.forName("io.github.abyxs.codingremote.MainActivity$Transport");
            Object mock=Proxy.newProxyInstance(transport.getClassLoader(),new Class[]{transport},(proxy,method,args)->{
                String path=(String)args[2];JSONObject body=(JSONObject)args[3];
                if(path.contains("/api/workspace/state"))return new JSONObject().put("status","saved").put("revision",1);
                if(path.endsWith("/open"))return new JSONObject().put("ok",true);
                if(path.contains("/agents"))return new JSONObject().put("agents",new JSONArray());
                if(path.equals("/api/native/codex")||path.equals("/api/native/claude")||path.equals("/api/native/deepseek"))return new JSONObject().put("sessions",new JSONArray().put(new JSONObject().put("id",sid).put("title","常驻会话页面").put("cwd","D:/fixture").put("source","chatgpt"))).put("connected",true);
                return new JSONObject(fixture.toString());
            });
            ui(()->{set(app,"transport",mock);set(app,"origin","https://fixture.example");set(app,"token","fixture-token");call(app,"showTab",new Class[]{String.class},"codex");call(app,"openSession",new Class[]{String.class,String.class},sid,"常驻会话页面");});
            await(()->ui(()->require(((ListView)get(app,"messageList")).getCount()>0,"initial conversation appears")));
            Object list=get(app,"messageList"),composer=get(app,"composer");
            ui(()->{((EditText)composer).setText("保留的输入草稿");call(app,"showStatusSheet",new Class[]{});});
            Object tray=get(app,"statusOverlay");
            require(tray instanceof FrameLayout,"execution details use an inline frame");
            ui(()->{call(app,"showTab",new Class[]{String.class},"claude");call(app,"showTab",new Class[]{String.class},"codex");call(app,"openSession",new Class[]{String.class,String.class},sid,"常驻会话页面");});
            ui(()->{require(get(app,"messageList")==list,"same message view survives switching");require(get(app,"composer")==composer,"same composer survives switching");require(((EditText)composer).getText().toString().equals("保留的输入草稿"),"draft survives");require(get(app,"statusOverlay")==tray,"statistics expansion survives");});
            ui(()->call(app,"openSettings",new Class[]{}));Object settings=get(app,"screen");
            ui(()->{call(app,"showTab",new Class[]{String.class},"codex");call(app,"openSettings",new Class[]{});require(get(app,"screen")==settings,"settings view is retained");});
            ui(()->{call(app,"showTab",new Class[]{String.class},"codex");call(app,"openSession",new Class[]{String.class,String.class},sid,"常驻会话页面");call(app,"openTerminal",new Class[]{});});
            Object workspace=get(app,"terminalPane");
            ui(()->{call(app,"showTab",new Class[]{String.class},"claude");call(app,"showTab",new Class[]{String.class},"codex");call(app,"openSession",new Class[]{String.class,String.class},sid,"常驻会话页面");require(get(app,"terminalPane")==workspace,"terminal view survives navigation");app.onBackPressed();require(((View)workspace).getVisibility()==View.GONE,"back closes the terminal");});
            Class<?> reducer=Class.forName("io.github.abyxs.codingremote.PetStateMachine");
            JSONObject running=new JSONObject().put("tasks",new JSONArray().put(new JSONObject().put("backend","codex").put("id",sid).put("status","active")));
            JSONObject completed=new JSONObject().put("tasks",new JSONArray().put(new JSONObject().put("backend","codex").put("id",sid).put("status","idle")));
            Object event=call(reducer,"reduce",new Class[]{JSONObject.class,JSONObject.class,List.class,boolean.class},running,completed,new ArrayList<JSONObject>(),false);
            require("completed".equals(event),"observed active to idle triggers completion");
            Object repeated=call(reducer,"reduce",new Class[]{JSONObject.class,JSONObject.class,List.class,boolean.class},completed,completed,new ArrayList<JSONObject>(),false);
            require("".equals(repeated),"repeated terminal snapshots stay quiet");
            Class<?> policy=Class.forName("io.github.abyxs.codingremote.OverlayPolicy");
            context.getSharedPreferences("ui",0).edit().putBoolean("overlayQuietHours",true).putInt("overlayQuietStart",0).putInt("overlayQuietEnd",0).commit();
            Constructor<?> constructor=policy.getDeclaredConstructor(android.content.SharedPreferences.class);constructor.setAccessible(true);require((Boolean)get(constructor.newInstance(context.getSharedPreferences("ui",0)),"quiet"),"full-day quiet-hours policy works");
            Class<?> preview=Class.forName("io.github.abyxs.codingremote.NativePreview");final Dialog[] loading={null};
            ui(()->{loading[0]=(Dialog)call(preview,"showLoading",new Class[]{Activity.class,String.class},app,"readme.md");loading[0].dismiss();require(!(Boolean)call(preview,"current",new Class[]{Dialog.class},loading[0]),"cancelled loading cannot reopen");});
            ui(()->call(app,"openTerminal",new Class[]{}));
            context.getSharedPreferences("ui",0).edit().putBoolean("overlayQuietHours",false).commit();
            SystemClock.sleep(300);ui(()->{ViewGroup terminal=(ViewGroup)workspace;require(terminal.getChildAt(1).getHeight()>24*app.getResources().getDisplayMetrics().density,"terminal output remains visible on short displays");});
            SystemClock.sleep(300);android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();java.io.File captures=new java.io.File(context.getExternalFilesDir(null),"ui-captures");captures.mkdirs();try(java.io.FileOutputStream stream=new java.io.FileOutputStream(new java.io.File(captures,"222-workspace.png"))){screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream);}screenshot.recycle();
            output.putString("stream","PASS: retained Android conversation/settings/workspace views; draft and statistics expansion; terminal in-page; back behavior; pet completion dedupe and quiet-hours; preview cancellation.\n");finish(Activity.RESULT_OK,output);
        }catch(Throwable e){output.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,output);}
        finally{context.getSharedPreferences("ui",0).edit().putBoolean("overlayQuietHours",false).commit();try{if(app!=null)ui(()->app.finish());}catch(Exception ignored){}}
    }
}

