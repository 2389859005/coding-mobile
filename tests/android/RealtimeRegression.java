package io.github.abyxs.codingremote.tests;
import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.util.*;

/** Real HTTP/SSE over the emulator network; all upstream data is synthetic. */
public final class RealtimeRegression extends Instrumentation {
    String origin;Context context;Class<?> supervisorType,secure;Object supervisor;
    volatile int step=-1,replayed=-1,sessionStep=-1;volatile boolean connected;
    volatile int connectionUpdates;
    interface Check{void run()throws Exception;}
    void require(boolean condition,String label){if(!condition)throw new AssertionError(label);}
    Object call(Class<?> type,Object target,String name,Class<?>[] args,Object... values)throws Exception{Method method=type.getDeclaredMethod(name,args);method.setAccessible(true);return method.invoke(target,values);}
    void await(Check check)throws Exception{Throwable error=null;for(int i=0;i<100;i++){try{check.run();return;}catch(Throwable e){error=e;}SystemClock.sleep(100);}throw new Exception(error);}
    void awaitFresh(Check check)throws Exception{Throwable error=null;for(int i=0;i<300;i++){try{check.run();return;}catch(Throwable e){error=e;}SystemClock.sleep(100);}throw new Exception(error);}
    JSONObject request(String path)throws Exception{HttpURLConnection connection=(HttpURLConnection)new URL(origin+path).openConnection();connection.setConnectTimeout(2000);connection.setReadTimeout(3000);try(InputStream input=connection.getInputStream()){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);return new JSONObject(bytes.toString("UTF-8"));}finally{connection.disconnect();}}
    public void onCreate(Bundle args){super.onCreate(args);origin=args.getString("liveOrigin","");start();}
    public void onStart(){Bundle result=new Bundle();Object owner=new Object(),second=new Object();try{
        require(origin.startsWith("http://10.0.2.2:"),"emulator fixture only");context=getTargetContext();
        secure=Class.forName("io.github.abyxs.codingremote.SecureStore");JSONObject auth=request("/fixture/auth");
        call(secure,null,"save",new Class[]{Context.class,String.class},context,auth.toString());
        supervisorType=Class.forName("io.github.abyxs.codingremote.ConnectionSupervisor");supervisor=call(supervisorType,null,"get",new Class[]{Context.class},context);
        Class<?> listener=Class.forName("io.github.abyxs.codingremote.ConnectionSupervisor$Listener");
        Object firstListener=java.lang.reflect.Proxy.newProxyInstance(listener.getClassLoader(),new Class[]{listener},(p,m,args)->{String topic=(String)args[0];JSONObject value=(JSONObject)args[1];if(topic.equals("status")&&value.has("step"))step=value.optInt("step");if(topic.equals("connection")){connected=value.optBoolean("connected");connectionUpdates++;}if(topic.equals("session:codex:fixture")&&value.has("messages"))sessionStep=Integer.parseInt(value.getJSONArray("messages").getJSONObject(0).getString("text").substring(5));return null;});
        Set<String> topics=new HashSet<>(Arrays.asList("status"));
        call(supervisorType,supervisor,"watch",new Class[]{Object.class,Set.class,listener},owner,topics,firstListener);
        await(()->require(connected&&step==0,"first authenticated SSE snapshot"));
        Field generation=supervisorType.getDeclaredField("generation");generation.setAccessible(true);long before=generation.getLong(supervisor);
        Object secondListener=java.lang.reflect.Proxy.newProxyInstance(listener.getClassLoader(),new Class[]{listener},(p,m,args)->{if(args[0].equals("status"))replayed=((JSONObject)args[1]).optInt("step",-1);return null;});
        call(supervisorType,supervisor,"watch",new Class[]{Object.class,Set.class,listener},second,topics,secondListener);
        await(()->require(replayed==0,"new view immediately receives cached snapshot"));
        require(generation.getLong(supervisor)==before,"adding a view does not rebuild the connection");
        topics.add("session:codex:fixture");topics.add("list:codex");
        call(supervisorType,supervisor,"watch",new Class[]{Object.class,Set.class,listener},owner,topics,firstListener);
        await(()->require(sessionStep==0,"opening a conversation while status stream is idle receives content"));
        long started=SystemClock.elapsedRealtime();request("/fixture/advance");await(()->require(step==1,"live state change received"));long latency=SystemClock.elapsedRealtime()-started;require(latency<1500,"local source update arrives promptly");
        await(()->require(sessionStep==1,"message body refreshes even when metadata token is unchanged"));
        int reads=request("/fixture/metrics").getInt("nativeReads"),states=connectionUpdates;SystemClock.sleep(15000);
        require(connectionUpdates==states,"transport keepalive must not refresh connection UI");
        require(request("/fixture/metrics").getInt("nativeReads")==reads,"healthy idle SSE does not trigger periodic HTTP fallback");
        request("/fixture/drop");request("/fixture/advance");SystemClock.sleep(2500);
        require(step==1,"Disconnected SSE keeps the last snapshot without HTTP polling");
        require(request("/fixture/metrics").getInt("nativeReads")==reads,"Disconnected stream does not poll business APIs");
        request("/fixture/restore");await(()->require(connected&&step==2&&sessionStep==2,"SSE reconnect replays the current snapshot"));
        request("/fixture/advance");await(()->require(step==3&&sessionStep==3,"event callback pushes updates after recovery"));
        JSONObject changed=new JSONObject(auth.toString()).put("computerId","different-computer").put("token","synthetic-invalid-token");call(secure,null,"save",new Class[]{Context.class,String.class},context,changed.toString());replayed=-1;
        call(supervisorType,supervisor,"watch",new Class[]{Object.class,Set.class,listener},second,topics,secondListener);SystemClock.sleep(500);require(replayed==-1,"changing computer credentials never replays the previous computer cache");
        call(supervisorType,supervisor,"unwatch",new Class[]{Object.class},owner);call(supervisorType,supervisor,"unwatch",new Class[]{Object.class},second);call(secure,null,"clear",new Class[]{Context.class},context);
        result.putString("stream","PASS: real emulator HTTP/SSE; cached view replay without reconnect; source update "+latency+" ms; native event updates without HTTP polling, forced EOF and automatic stream recovery; computer-scoped cache isolation.\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}}
}
