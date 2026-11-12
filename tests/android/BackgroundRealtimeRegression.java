package io.github.abyxs.codingremote.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import org.json.*;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;

/** The real monitor consumes synthetic HTTP/SSE while the Activity is in the background. */
public final class BackgroundRealtimeRegression extends Instrumentation {
    String origin;Context context;Class<?> overlayType;Throwable uiError;
    interface Check { void run() throws Exception; }
    void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    Object call(Class<?> type,Object target,String name,Class<?>[] types,Object...args)throws Exception{Method m=type.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);}
    Object field(Class<?> type,Object target,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    void ui(Check check)throws Exception{uiError=null;runOnMainSync(()->{try{check.run();}catch(Throwable e){uiError=e;}});if(uiError!=null)throw new Exception(uiError);}
    void await(Check check,int seconds)throws Exception{Throwable last=null;for(int i=0;i<seconds*10;i++){try{check.run();return;}catch(Throwable e){last=e;}SystemClock.sleep(100);}throw new Exception(last);}
    JSONObject request(String path)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(origin+path).openConnection();c.setConnectTimeout(2000);c.setReadTimeout(3000);try(InputStream in=c.getInputStream()){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new JSONObject(out.toString("UTF-8"));}finally{c.disconnect();}}
    boolean contains(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().contains(text))return true;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(contains(((ViewGroup)view).getChildAt(i),text))return true;return false;}
    void frame(int step,boolean bubble)throws Exception{ui(()->{Object overlay=field(overlayType,null,"live");require(overlay!=null,"monitor owns a live overlay");require(((JSONObject)field(overlayType,overlay,"snapshot")).optInt("step",-1)==step,"overlay receives source step "+step);if(bubble)require(contains((View)field(overlayType,overlay,"bubbleList"),"Background step "+step),"visible bubble renders source progress "+step);});}
    boolean foreground(){NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);for(android.service.notification.StatusBarNotification n:manager.getActiveNotifications())if(n.getId()==90&&(n.getNotification().flags&Notification.FLAG_FOREGROUND_SERVICE)!=0)return true;return false;}
    public void onCreate(Bundle args){super.onCreate(args);origin=args.getString("liveOrigin","");start();}
    public void onStart(){Bundle result=new Bundle();Activity activity=null;try{
        require(origin.startsWith("http://10.0.2.2:"),"isolated emulator fixture only");context=getTargetContext();
        require(android.provider.Settings.canDrawOverlays(context),"overlay permission on emulator");
        Class<?> secure=Class.forName("io.github.abyxs.codingremote.SecureStore");
        call(secure,null,"save",new Class[]{Context.class,String.class},context,request("/fixture/auth").toString());
        context.getSharedPreferences("ui",0).edit().putBoolean("notifications",true).putBoolean("overlay",true).putBoolean("overlayForeground",false).putBoolean("overlayQuietHours",false).putLong("overlayHiddenUntil",0).putString("overlayMode","full").putInt("overlayBubbleSeconds",0).commit();
        overlayType=Class.forName("io.github.abyxs.codingremote.StatusOverlay");
        activity=startActivitySync(new Intent().setClassName(context,"io.github.abyxs.codingremote.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));

        final int initial=request("/fixture/metrics").getInt("step");await(()->frame(initial,false),10);
        final Activity app=activity;runOnMainSync(()->app.moveTaskToBack(true));
        Class<?> visibility=Class.forName("io.github.abyxs.codingremote.AppVisibility");
        await(()->require(!(Boolean)call(visibility,null,"visible",new Class[]{}),"Activity is in background"),10);
        await(()->require((Boolean)field(overlayType,null,"hosted"),"real overlay window is hosted in background"),10);
        ui(()->call(overlayType,field(overlayType,null,"live"),"openBubble",new Class[]{}));
        final int live=request("/fixture/advance").getInt("step");await(()->frame(live,true),10);
        final int resumed=request("/fixture/advance").getInt("step");await(()->frame(resumed,true),10);
        require(!foreground(),"Event monitor must not create a persistent notification");
        result.putString("stream","PASS: bound monitor remains active with a real background overlay; bubble updates from native events without a persistent notification.\n");
        finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}
    finally{try{request("/fixture/unfreeze");context.getSharedPreferences("ui",0).edit().putBoolean("notifications",false).putBoolean("overlay",false).commit();call(Class.forName("io.github.abyxs.codingremote.MonitorConnection"),null,"start",new Class[]{Context.class},context);call(Class.forName("io.github.abyxs.codingremote.SecureStore"),null,"clear",new Class[]{Context.class},context);if(activity!=null){final Activity app=activity;runOnMainSync(()->app.finish());}}catch(Exception ignored){}}}
}
