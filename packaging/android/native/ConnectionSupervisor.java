package io.github.abyxs.codingremote;

import android.content.Context;
import android.net.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.util.*;

/** One process-wide event connection. Views and the pet only own subscriptions. */
final class ConnectionSupervisor {
    interface Listener { void event(String topic,JSONObject value); }
    private static ConnectionSupervisor shared;
    static synchronized ConnectionSupervisor get(Context context){if(shared==null)shared=new ConnectionSupervisor(context.getApplicationContext());return shared;}
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<Object,Watch> watches=new HashMap<>();
    private final Map<String,JSONObject> cache=new HashMap<>();
    private final Map<String,String> revisions=new java.util.concurrent.ConcurrentHashMap<>();
    private volatile HttpURLConnection active;
    private volatile long generation;
    private volatile long streamLastSeen;
    private final java.util.concurrent.ExecutorService cancellations=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread thread=new Thread(r,"live-cancel");thread.setDaemon(true);return thread;});
    private Thread thread;
    private String scope="";
    private long lastRouteSwitch;
    private static final class Watch { final Set<String> topics;final Listener listener;Watch(Set<String> t,Listener l){topics=new HashSet<>(t);listener=l;} }
    private ConnectionSupervisor(Context c){
        context=c;
        main.postDelayed(new Runnable(){public void run(){
            if(active!=null&&streamLastSeen>0&&SystemClock.elapsedRealtime()-streamLastSeen>30000)reconnect();
            main.postDelayed(this,5000);
        }},5000);
        try{((ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE)).registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network network){reconnect();}
            @Override public void onLost(Network network){reconnect();}
        });}catch(Exception ignored){}
    }
    private void probeRoutes(){
        if(topics().isEmpty())return;
        try{JSONObject root=ConnectionProfiles.read(context);if(!root.has("token"))return;ArrayList<JSONObject> routes=ConnectionProfiles.candidates(root,root.getString("origin"),root.getString("token"));JSONObject best=null;long fastest=Long.MAX_VALUE;
            for(JSONObject route:routes){HttpURLConnection probe=null;long start=SystemClock.elapsedRealtime();try{
                String host=route.getString("origin");if(!TrustedOrigin.valid(host))continue;probe=EmbeddedNetwork.open(context,host+"/api/device");probe.setConnectTimeout(1800);probe.setReadTimeout(2500);probe.setInstanceFollowRedirects(false);probe.setRequestProperty("Origin",host);probe.setRequestProperty("Authorization","Bearer "+route.getString("token"));if(probe.getResponseCode()!=200)throw new IOException("线路不可用");ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream input=probe.getInputStream()){byte[] buffer=new byte[1024];int count;while((count=input.read(buffer))!=-1){if(bytes.size()+count>16384)throw new IOException("线路响应过大");bytes.write(buffer,0,count);}}JSONObject value=new JSONObject(bytes.toString("UTF-8"));String expected=route.optString("computerId");if(!expected.isEmpty()&&!expected.equals(value.optString("computerId")))throw new IOException("电脑身份不匹配");long elapsed=SystemClock.elapsedRealtime()-start;ConnectionProfiles.measured(route,elapsed,true);if(elapsed<fastest){fastest=elapsed;best=route;}
            }catch(Exception error){ConnectionProfiles.measured(route,0,false);}finally{if(probe!=null)probe.disconnect();}}
            if(best!=null&&!best.optString("origin").equals(root.optString("origin"))&&SystemClock.elapsedRealtime()-lastRouteSwitch>30000&&fastest+150<ConnectionProfiles.latency(root)){ConnectionProfiles.prefer(context,best,root);lastRouteSwitch=SystemClock.elapsedRealtime();reconnect();}
        }catch(Exception ignored){}
    }
    synchronized void watch(Object owner,Set<String> topics,Listener listener){
        try{JSONObject auth=ConnectionProfiles.read(context);String identity=auth.optString("computerId")+"|"+auth.optString("origin")+"|"+auth.optString("token");if(!scope.equals(identity)){scope=identity;cache.clear();revisions.clear();reconnect();}}catch(Exception invalid){cache.clear();revisions.clear();reconnect();}
        Set<String> before=topics();watches.put(owner,new Watch(topics,listener));
        final String expectedScope=scope;
        for(String topic:topics){JSONObject value=cache.get(topic);if(value!=null)main.post(()->deliver(owner,topic,value,expectedScope));}
        JSONObject connectionState=cache.get("connection");if(connectionState!=null)main.post(()->deliver(owner,"connection",connectionState,expectedScope));
        if(!before.equals(topics())){reconnect();}
        if(thread==null){thread=new Thread(this::loop,"shared-live");thread.setDaemon(true);thread.start();}
        notifyAll();
    }
    synchronized void unwatch(Object owner){Set<String> before=topics();watches.remove(owner);if(!before.equals(topics()))reconnect();}
    synchronized void reconnect(){generation++;streamLastSeen=0;HttpURLConnection current=active;if(current!=null)cancellations.execute(current::disconnect);notifyAll();}
    private synchronized Set<String> topics(){Set<String> result=new TreeSet<>();for(Watch w:watches.values())result.addAll(w.topics);return result;}
    private synchronized void deliver(Object owner,String topic,JSONObject value,String expectedScope){if(!scope.equals(expectedScope))return;Watch watch=watches.get(owner);if(watch!=null&&(watch.topics.contains(topic)||topic.equals("connection")))watch.listener.event(topic,value);}
    private void publish(String topic,JSONObject value){
        List<Object> owners;final String expectedScope;
        synchronized(this){cache.put(topic,value);Set<String> retained=topics();retained.add("connection");cache.keySet().retainAll(retained);revisions.keySet().retainAll(retained);owners=new ArrayList<>(watches.keySet());expectedScope=scope;}
        for(Object owner:owners)main.post(()->deliver(owner,topic,value,expectedScope));
    }
    private void state(String label,boolean online,long delay){try{publish("connection",new JSONObject().put("label",label).put("connected",online).put("latencyMs",delay).put("lastSync",System.currentTimeMillis()));}catch(Exception ignored){}}
    private HttpURLConnection open(JSONObject auth,String path)throws Exception{
        String origin=auth.getString("origin");if(!TrustedOrigin.valid(origin))throw new IOException("无效连接入口");
        HttpURLConnection c=EmbeddedNetwork.open(context,origin+path);active=c;
        c.setConnectTimeout(2500);c.setReadTimeout(25000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Origin",origin);c.setRequestProperty("Authorization","Bearer "+auth.getString("token"));c.setRequestProperty("Accept-Encoding","identity");c.setRequestProperty("Accept","text/event-stream");
        return c;
    }
    private void loop(){
        int backoff=500;
        while(true){
            synchronized(this){while(watches.isEmpty())try{wait();}catch(InterruptedException e){return;}}
            long version=generation;HttpURLConnection connection=null;
            try{
                JSONObject auth=ConnectionProfiles.read(context);if(!auth.has("token"))throw new IOException("尚未配对");
                String identity=auth.optString("computerId")+"|"+auth.optString("origin")+"|"+auth.optString("token");
                synchronized(this){if(!scope.equals(identity)){scope=identity;cache.clear();revisions.clear();}}
                Set<String> subscribed=topics();List<String> sessions=new ArrayList<>();for(String topic:subscribed)if(topic.startsWith("session:"))sessions.add(topic.substring(8));
                List<String> backends=new ArrayList<>();for(String topic:subscribed)if(topic.startsWith("list:"))backends.add(topic.substring(5));
                String path="/api/native/events?backends="+Uri.encode(android.text.TextUtils.join(",",backends))+"&sessions="+Uri.encode(android.text.TextUtils.join(",",sessions));
                long start=SystemClock.elapsedRealtime();connection=open(auth,path);
                int code=connection.getResponseCode();if(code!=200)throw new IOException("连接入口返回 "+code);
                String reported=connection.getHeaderField("X-Computer-Id"),expected=auth.optString("computerId");
                if(reported!=null&&!expected.isEmpty()&&!expected.equals(reported))throw new SecurityException("电脑身份不匹配");
                long handshake=SystemClock.elapsedRealtime()-start;if(version!=generation)continue;state("已连接",true,handshake);
                streamLastSeen=SystemClock.elapsedRealtime();
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(connection.getInputStream(),"UTF-8"))){
                    Set<String> received=new HashSet<>();
                    String line;StringBuilder data=new StringBuilder();
                    while(version==generation&&(line=reader.readLine())!=null){
                        if(version!=generation)break;
                        streamLastSeen=SystemClock.elapsedRealtime();
                        if(line.isEmpty()){
                            if(data.length()>0){JSONObject envelope=new JSONObject(data.toString());data.setLength(0);JSONObject value=envelope.optJSONObject("data");String topic=envelope.optString("topic"),revision=envelope.optString("revision");if(version!=generation)break;
                                if(value!=null&&!"checkpoint".equals(envelope.optString("type"))&&(received.add(topic)||!revision.equals(revisions.get(topic)))){revisions.put(topic,revision);publish(topic,value);}
                            }
                            JSONObject now=ConnectionProfiles.read(context);if(!auth.optString("origin").equals(now.optString("origin"))||!auth.optString("token").equals(now.optString("token")))break;
                            backoff=500;
                        }else if(line.startsWith("data:")){if(data.length()>24*1024*1024)throw new IOException("事件过大");data.append(line.substring(5).trim());}
                    }
                }
                if(version==generation)throw new EOFException("已断开");
            }catch(Exception error){if(version==generation){streamLastSeen=0;state("已断开",false,0);probeRoutes();}}
            finally{if(connection!=null)connection.disconnect();active=null;}
            synchronized(this){if(version==generation)try{wait(backoff);}catch(InterruptedException e){return;}}
            backoff=Math.min(4000,backoff*2);
        }
    }
}
