package io.github.abyxs.codingremote.tests;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;import org.json.*;import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;

/** Exercise the actual activity listener and write response, with isolated data. */
public final class MobileUpdateRegression extends Instrumentation {
    Activity app;Throwable failure;volatile JSONObject current;volatile boolean hold=false;
    CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);Object listener;Method event;int sends=0;
    interface Check{void run()throws Exception;}
    Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
    Object call(Object o,String n,Class<?>[] t,Object...a)throws Exception{Class<?> c=o instanceof Class?(Class<?>)o:o.getClass();Method m=c.getDeclaredMethod(n,t);m.setAccessible(true);return m.invoke(o instanceof Class?null:o,a);}
    void ui(Check c)throws Exception{failure=null;runOnMainSync(()->{try{c.run();}catch(Throwable e){failure=e;}});if(failure!=null)throw new Exception(failure);waitForIdleSync();}
    void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    void settle(){SystemClock.sleep(500);waitForIdleSync();}
    JSONObject page(String text,String status)throws Exception{return new JSONObject().put("connected",true).put("session",new JSONObject().put("id","fixture").put("title","手机更新验证").put("cwd","D:/hidden-directory").put("status",status)).put("messages",new JSONArray().put(new JSONObject().put("id","a").put("role","assistant").put("text",text))).put("queue",new JSONArray());}
    Object cache()throws Exception{return ((Map<?,?>)get(app,"chatCaches")).get("claude:fixture");}
    String shown()throws Exception{StringBuilder s=new StringBuilder();for(Object row:(List<?>)get(get(app,"messageAdapter"),"rows"))s.append(get(row,"text"));return s.toString();}
    void push(JSONObject value)throws Exception{ui(()->event.invoke(listener,"session:claude:fixture",value));settle();}
    public void onCreate(Bundle b){super.onCreate(b);start();}
    public void onStart(){Bundle result=new Bundle();try{
        Context c=getTargetContext();c.getSharedPreferences("ui",0).edit().putBoolean("notifications",false).putBoolean("overlay",false).putBoolean("resumeChat",false).putBoolean("petInChat",false).commit();c.getSharedPreferences("pending",0).edit().clear().commit();
        call(Class.forName("io.github.abyxs.codingremote.SecureStore"),"clear",new Class[]{Context.class},c);
        current=page("初始回复","idle");app=startActivitySync(new Intent().setClassName(c,"io.github.abyxs.codingremote.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Class<?> transport=Class.forName("io.github.abyxs.codingremote.MainActivity$Transport");Object net=Proxy.newProxyInstance(transport.getClassLoader(),new Class[]{transport},(p,m,a)->{
            String path=(String)a[2];JSONObject body=(JSONObject)a[3];
            if(path.endsWith("/send")){sends++;current=page("初始回复","active");current.getJSONArray("messages").put(new JSONObject().put("id","sent").put("role","user").put("text",body.getString("text")));return new JSONObject().put("status","accepted");}
            if(path.contains("workspace/state"))return new JSONObject().put("status","saved").put("revision",1);
            if(path.contains("catalog"))return new JSONObject().put("models",new JSONArray());
            if(path.contains("/fixture")){JSONObject snapshot=new JSONObject(current.toString());if(hold){started.countDown();release.await(8,TimeUnit.SECONDS);}return snapshot;}
            return new JSONObject().put("connected",true).put("sessions",new JSONArray());
        });
        ui(()->{set(app,"transport",net);set(app,"origin","https://fixture.invalid");set(app,"token","fixture");call(app,"showTab",new Class[]{String.class},"claude");call(app,"openSession",new Class[]{String.class,String.class},"fixture","手机更新验证");});settle();
        Object supervisor=call(Class.forName("io.github.abyxs.codingremote.ConnectionSupervisor"),"get",new Class[]{Context.class},c);
        Object watch=((Map<?,?>)get(supervisor,"watches")).get(app);listener=get(watch,"listener");event=Class.forName("io.github.abyxs.codingremote.ConnectionSupervisor$Listener").getDeclaredMethod("event",String.class,JSONObject.class);event.setAccessible(true);
        ui(()->call(supervisor,"unwatch",new Class[]{Object.class},app));
        ui(()->{((EditText)get(app,"composer")).setText("手机发出的消息");((View)get(app,"sendButton")).performClick();});settle();settle();
        require(sends==1&&shown().contains("手机发出的消息"),"accepted send immediately appears in the phone transcript");
        require(((View)get(app,"actionHint")).getVisibility()==View.GONE,"no receipt strip above composer");
        JSONObject streaming=page("流式片段一","active");push(streaming);require(shown().contains("流式片段一"),"native event renders without changing pages");
        hold=true;ui(()->{set(app,"pollEpoch",-1);call(app,"poll",new Class[]{int.class,String.class},get(app,"epoch"),"fixture");});require(started.await(2,TimeUnit.SECONDS),"older HTTP read began");
        push(page("流式片段二","active"));hold=false;release.countDown();settle();require(shown().contains("流式片段二"),"older HTTP response cannot replace newer event");
        ui(()->set(app,"pageBefore",Integer.valueOf(30)));push(page("最终回复","idle"));
        require(Boolean.FALSE.equals(get(cache(),"running")),"completed task becomes idle while reading history");require(shown().contains("流式片段二"),"history reading position is not replaced");
        ui(()->{set(app,"pageBefore",null);set(app,"scrolling",true);});push(page("另一轮","active"));push(page("任务停止","idle"));require(Boolean.FALSE.equals(get(cache(),"running")),"stopped task becomes idle even during scrolling");
        JSONObject changed=page("任务停止","idle");changed.getJSONObject("session").put("model","desktop-selected-model").put("effort","high");
        push(changed);require(((TextView)get(app,"modelLabel")).getText().toString().contains("desktop-selected-model"),"desktop model change is visible during scrolling without new content");
        ui(()->{set(app,"pageBefore",Integer.valueOf(30));set(app,"scrolling",false);});changed.getJSONObject("session").put("model","history-model");push(changed);
        require(((TextView)get(app,"modelLabel")).getText().toString().contains("history-model"),"desktop model change is visible while reading history");
        result.putString("stream","PASS: phone send visible immediately; no receipt strip; streaming event rendering; stale HTTP response ignored; completion and metadata-only model changes in history and during scrolling.\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}finally{release.countDown();if(app!=null)runOnMainSync(()->app.finish());}}
}
