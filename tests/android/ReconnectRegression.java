package io.github.abyxs.codingremote.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Synthetic recovery only: no camera, real account, or real desktop request. */
public final class ReconnectRegression extends Instrumentation {
    Activity app;Throwable failure;Context context;
    interface Check{void run()throws Exception;}
    Object get(String name)throws Exception{Field f=app.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(app);}
    void set(String name,Object v)throws Exception{Field f=app.getClass().getDeclaredField(name);f.setAccessible(true);f.set(app,v);}
    Object call(String name,Class<?>[] types,Object...args)throws Exception{Method m=app.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(app,args);}
    void ui(Check c)throws Exception{failure=null;runOnMainSync(()->{try{c.run();}catch(Throwable e){failure=e;}});if(failure!=null)throw new Exception(failure);}
    void require(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    void await(Check c)throws Exception{Throwable error=null;for(int i=0;i<80;i++){try{ui(c);return;}catch(Throwable e){error=e;}SystemClock.sleep(100);}throw new Exception(error);}
    @Override public void onCreate(Bundle args){super.onCreate(args);context=getTargetContext();context.getSharedPreferences("ui",0).edit().putBoolean("notifications",false).putBoolean("overlay",false).putBoolean("resumeChat",false).putString("backend","codex").commit();context.getSharedPreferences("coding-remote",0).edit().putString("device","unreadable-fixture").commit();start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        app=startActivitySync(new Intent().setClassName(context,"io.github.abyxs.codingremote.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        require(context.getSharedPreferences("coding-remote",0).getString("device","").equals("unreadable-fixture"),"startup read failure must not erase binding");
        AtomicBoolean offline=new AtomicBoolean(true);Class<?> transport=Class.forName("io.github.abyxs.codingremote.MainActivity$Transport");
        Class<?> apiError=Class.forName("io.github.abyxs.codingremote.MainActivity$ApiError");Constructor<?> error=apiError.getDeclaredConstructor(int.class,String.class);error.setAccessible(true);
        Object mock=Proxy.newProxyInstance(transport.getClassLoader(),new Class[]{transport},(o,m,args)->{
            require(args[3]==null,"reconnection performs no writes or pairing");
            if(offline.get())throw (Exception)error.newInstance(401,"synthetic startup unavailable");
            return new JSONObject().put("sessions",new JSONArray().put(new JSONObject().put("id","fixture").put("title","Saved connection restored").put("cwd","D:/fixture")));
        });
        ui(()->set("transport",mock));
        Class<?> secure=Class.forName("io.github.abyxs.codingremote.SecureStore");Method save=secure.getDeclaredMethod("save",Context.class,String.class);save.setAccessible(true);
        JSONObject saved=new JSONObject().put("origin","http://100.64.1.2:18787").put("token","fixture-secret").put("computerId","fixture-computer").put("mode","permanent");save.invoke(null,context,saved.toString());
        String encrypted=context.getSharedPreferences("coding-remote",0).getString("device","");
        ui(()->call("retrySavedConnection",new Class[]{}));
        await(()->{Map<?,?> caches=(Map<?,?>)get("listCaches");require(!caches.isEmpty(),"list state survives authentication failure");Object cache=caches.get(get("displayedListKey"));Field f=cache.getClass().getDeclaredField("error");f.setAccessible(true);require(!((String)f.get(cache)).isEmpty(),"failed route remains retryable");});
        require(context.getSharedPreferences("coding-remote",0).getString("device","").equals(encrypted),"401 preserves encrypted credential");
        offline.set(false);
        ui(()->call("loadSessions",new Class[]{String.class,boolean.class},(String)get("displayedListKey"),false));
        await(()->{Object cache=((Map<?,?>)get("listCaches")).get(get("displayedListKey"));Field f=cache.getClass().getDeclaredField("rows");f.setAccessible(true);require(((JSONArray)f.get(cache)).length()==1,"list resumes without scanning or manual navigation");});
        ui(()->{require(get("token").equals("fixture-secret"),"existing credential reused");callActivityOnPause(app);callActivityOnResume(app);});
        require(context.getSharedPreferences("coding-remote",0).getString("device","").equals(encrypted),"resume preserves permanent binding");
        result.putString("stream","PASS: startup read failure preserves credentials; saved connection restores; 401 keeps retry state; later success without QR; resume keeps binding; zero writes.\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}finally{if(app!=null)runOnMainSync(()->app.finish());context.getSharedPreferences("coding-remote",0).edit().clear().commit();}}
}
