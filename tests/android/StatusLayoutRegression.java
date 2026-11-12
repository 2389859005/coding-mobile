package io.github.abyxs.codingremote.tests;
import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.lang.reflect.*;
import java.util.*;

public final class StatusLayoutRegression extends Instrumentation {
    Activity app;Throwable failure;String theme="light";
    interface Check{void run()throws Exception;}
    Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    void set(Object o,String n,Object value)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,value);}
    Object call(Object o,String n,Class<?>[] types,Object...args)throws Exception{Class<?> type=o instanceof Class?(Class<?>)o:o.getClass();Method m=type.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o instanceof Class?null:o,args);}
    void ui(Check check)throws Exception{failure=null;runOnMainSync(()->{try{check.run();}catch(Throwable e){failure=e;}});if(failure!=null)throw new Exception(failure);waitForIdleSync();}
    void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    void shot(String name)throws Exception{SystemClock.sleep(2200);Bitmap image=getUiAutomation().takeScreenshot();java.io.File folder=new java.io.File(getTargetContext().getExternalFilesDir(null),"ui-captures");folder.mkdirs();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(folder,"revision-status-"+theme+"-"+name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
    void bounds()throws Exception{View tray=(View)get(app,"statusTray"),panel=(View)get(app,"statusOverlay"),composer=(View)get(app,"composer");int[] t=new int[2],p=new int[2],c=new int[2];tray.getLocationOnScreen(t);panel.getLocationOnScreen(p);composer.getLocationOnScreen(c);require(panel.getParent()==get(app,"stage"),"details overlay the stage above the fixed dock");require(Math.abs(t[0]-p[0])<=1&&Math.abs(tray.getWidth()-panel.getWidth())<=1,"details align with composer width");require(p[1]>=0&&p[1]+panel.getHeight()<=t[1],"details expand upward above the fixed disclosure");require(((View)get(app,"messageList")).getHeight()>100*app.getResources().getDisplayMetrics().density,"conversation remains visible above expanded details");}
    public void onCreate(Bundle b){super.onCreate(b);theme=b.getString("theme","light");start();}
    public void onStart(){Bundle result=new Bundle();try{
        Context context=getTargetContext();context.getSharedPreferences("ui",0).edit().putString("appearance",theme).remove("theme").putBoolean("notifications",false).putBoolean("overlay",false).putBoolean("resumeChat",false).commit();
        Class<?> secure=Class.forName("io.github.abyxs.codingremote.SecureStore");call(secure,"clear",new Class[]{Context.class},context);
        app=startActivitySync(new Intent().setClassName(context,"io.github.abyxs.codingremote.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        JSONObject fixture=new JSONObject().put("session",new JSONObject().put("id","fixture").put("title","执行详情预览").put("status","idle").put("contextUsage",new JSONObject().put("available",true).put("usedTokens",12800).put("maxTokens",128000)))
            .put("connected",true).put("messages",new JSONArray().put(new JSONObject().put("id","one").put("role","assistant").put("text","审批、排队与执行步骤可以在输入框上方展开查看。")));
        Class<?> transport=Class.forName("io.github.abyxs.codingremote.MainActivity$Transport");Object net=Proxy.newProxyInstance(transport.getClassLoader(),new Class[]{transport},(p,m,a)->((String)a[2]).contains("/api/workspace/state")?new JSONObject().put("status","saved").put("revision",1):new JSONObject(fixture.toString()));
        ui(()->{set(app,"transport",net);set(app,"origin","https://fixture.example");set(app,"token","fixture");set(app,"backend","codex");call(app,"openSession",new Class[]{String.class,String.class},"fixture","执行详情预览");});SystemClock.sleep(800);
        Class<?> pageType=Class.forName("io.github.abyxs.codingremote.SessionPage"),supervisor=Class.forName("io.github.abyxs.codingremote.ConnectionSupervisor");Object owner=call(supervisor,"get",new Class[]{Context.class},context);call(owner,"unwatch",new Class[]{Object.class},app);
        Object page=call(pageType,"prepare",new Class[]{JSONObject.class},fixture);Map<?,?> caches=(Map<?,?>)get(app,"chatCaches");Object cache=caches.get(get(app,"sessionKey"));
        ui(()->{set(app,"paused",true);set(cache,"page",page);call(app,"renderSession",new Class[]{pageType,boolean.class},page,false);((EditText)get(app,"composer")).setText("保留我的输入");call(app,"connection",new Class[]{String.class,boolean.class},"已同步",true);call(app,"refreshStatusTray",new Class[]{});require(((View)get(app,"statusTray")).getVisibility()==View.VISIBLE,"idle preserves the fixed status disclosure");});shot("idle");
        JSONObject busy=new JSONObject(fixture.toString());JSONArray steps=new JSONArray();for(int i=0;i<35;i++)steps.put(new JSONObject().put("content","步骤 "+(i+1)+"：检查实现、验证变更并整理执行结果").put("status",i<5?"completed":"in_progress"));busy.put("steps",steps);busy.put("changes",new JSONObject().put("files",3).put("added",42).put("deleted",8));
        busy.getJSONObject("session").put("requests",new JSONArray().put(new JSONObject().put("id","approval").put("tool","执行命令").put("input",new JSONObject())));Object busyPage=call(pageType,"prepare",new Class[]{JSONObject.class},busy);
        ui(()->{set(cache,"page",busyPage);call(app,"renderSession",new Class[]{pageType,boolean.class},busyPage,false);call(app,"refreshStatusTray",new Class[]{});require(((TextView)get(app,"statusTrayText")).getText().toString().contains("步"),"no conversation or synchronization status in disclosure");});shot("collapsed");
        int transcriptHeight=((View)get(app,"messageList")).getHeight();ui(()->call(app,"showStatusSheet",new Class[]{}));SystemClock.sleep(200);ui(()->bounds());shot("expanded");
        FrameLayout panel=(FrameLayout)get(app,"statusOverlay");ScrollView scroll=panel.findViewWithTag("status-scroll");ui(()->scroll.scrollTo(0,240));int saved=scroll.getScrollY();require(saved>0,"long content scrolls inside accordion");
        ui(()->{call(app,"refreshStatusTray",new Class[]{});require(panel.findViewWithTag("status-scroll")==scroll&&scroll.getScrollY()==saved,"unchanged refresh preserves actual scroll view and reading position");call(app,"showStatusSheet",new Class[]{});require(panel.getVisibility()==View.GONE,"collapse hides details");require(((EditText)get(app,"composer")).getText().toString().equals("保留我的输入"),"expansion preserves draft");});
        require(((View)get(app,"messageList")).getHeight()==transcriptHeight,"collapse restores transcript height");
        result.putString("stream","PASS: fixed idle disclosure; overlay expands upward; bounded long content; conversation stays visible; scroll and draft retained; collapse restores space.\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}finally{if(app!=null)runOnMainSync(()->app.finish());}}
}
