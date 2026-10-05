package io.github.abyxs.codingremote.tests;
import android.app.*;
import android.os.*;
import android.content.*;
import java.lang.reflect.*;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** Real Android Keystore plus mock network: credential migration, scoping, and no write replay. */
public final class ConnectionRegression extends Instrumentation {
    Class<?> profiles,transport,secure;Context context;final List<String> calls=new ArrayList<>();
    void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    Object call(Class<?> type,String name,Class<?>[] signature,Object...args)throws Exception {Method m=type.getDeclaredMethod(name,signature);m.setAccessible(true);return m.invoke(null,args);}
    JSONObject route(String origin,String token,String id,String mode)throws Exception{return new JSONObject().put("origin",origin).put("token",token).put("computerId",id).put("mode",mode);}
    void save(JSONObject root)throws Exception{call(secure,"save",new Class[]{Context.class,String.class},context,root.toString());}
    JSONObject read()throws Exception{return (JSONObject)call(profiles,"read",new Class[]{Context.class},context);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();String saved=null;try{
        context=getTargetContext();profiles=Class.forName("io.github.abyxs.codingremote.ConnectionProfiles");secure=Class.forName("io.github.abyxs.codingremote.SecureStore");transport=Class.forName("io.github.abyxs.codingremote.ConnectionProfiles$Transport");saved=(String)call(secure,"read",new Class[]{Context.class},context);
        String privateHost="http://100.64.1.2:18787",publicHost="https://fixture.trycloudflare.com";
        JSONObject permanent=route(privateHost,"private-token","computer-a","permanent"),temporary=route(publicHost,"public-token","computer-a","temporary");
        JSONObject legacy=new JSONObject().put("origin",privateHost).put("token","legacy");JSONArray migrated=(JSONArray)call(profiles,"rows",new Class[]{JSONObject.class},legacy);require(migrated.getJSONObject(0).getString("mode").equals("permanent"),"legacy private route migrates");
        JSONObject merged=(JSONObject)call(profiles,"merge",new Class[]{JSONObject.class,JSONObject.class},permanent,temporary);require(merged.getJSONArray("profiles").length()==2,"QR preserves permanent credential");save(merged);require(read().getJSONArray("profiles").length()==2,"encrypted Keystore roundtrip preserves both routes");
        JSONObject stranger=route("https://other.trycloudflare.com","other-token","computer-b","temporary");merged=(JSONObject)call(profiles,"merge",new Class[]{JSONObject.class,JSONObject.class},merged,stranger);save(merged);
        ArrayList<?> candidates=(ArrayList<?>)call(profiles,"candidates",new Class[]{JSONObject.class,String.class,String.class},merged,privateHost,"private-token");require(candidates.size()==2,"fallback excludes other computer");
        final int[] postCount={0};final boolean[] breakPost={false};
        Object mock=Proxy.newProxyInstance(transport.getClassLoader(),new Class[]{transport},(o,m,args)->{
            String origin=(String)args[0],token=(String)args[1],path=(String)args[2];JSONObject body=(JSONObject)args[3];calls.add(origin+path);
            require(!origin.contains("other"),"never touches another computer");
            if(origin.equals(privateHost)){require(token.equals("private-token"),"private credential scoped");throw new IOException("Tailscale disconnected");}
            require(token.equals("public-token"),"public origin uses only scanned temporary credential");
            if(body!=null){postCount[0]++;if(breakPost[0])throw new IOException("connection lost after sending");return new JSONObject().put("status","accepted");}
            return new JSONObject().put("computerId","computer-a").put("sessions",new JSONArray());
        });
        Class<?>[] signature={Context.class,transport,String.class,String.class,String.class,JSONObject.class};
        call(profiles,"request",signature,context,mock,privateHost,"private-token","/api/native/codex",null);require(read().getString("token").equals("public-token"),"GET selects available temporary route after Tailscale outage");
        call(profiles,"request",signature,context,mock,privateHost,"private-token","/api/native/codex/test/send",new JSONObject().put("id","unique"));require(postCount[0]==1,"write probes then sends exactly once");
        breakPost[0]=true;try{call(profiles,"request",signature,context,mock,privateHost,"private-token","/api/native/codex/test/send",new JSONObject());throw new AssertionError("expected unknown write result");}catch(InvocationTargetException expected){require(expected.getCause() instanceof IOException,"network failure propagated");}require(postCount[0]==2,"unknown write is never replayed on another route");
        JSONObject remaining=(JSONObject)call(profiles,"remove",new Class[]{Context.class,String.class,String.class},context,publicHost,"public-token");boolean bound=false;for(int i=0;i<remaining.getJSONArray("profiles").length();i++)if(remaining.getJSONArray("profiles").getJSONObject(i).optString("token").equals("private-token"))bound=true;require(bound,"removing temporary route retains permanent binding");
        save(permanent);final int[] reads={0},writes={0};
        Object transientNet=Proxy.newProxyInstance(transport.getClassLoader(),new Class[]{transport},(o,m,args)->{
            if(args[3]!=null){writes[0]++;return new JSONObject().put("ok",true);}
            if(reads[0]++%2==0)throw new IOException("network transitioning");
            return new JSONObject().put("computerId","computer-a");
        });
        call(profiles,"request",signature,context,transientNet,privateHost,"private-token","/api/native/codex",null);
        require(reads[0]==2,"read retries transient network failure");
        call(profiles,"request",signature,context,transientNet,privateHost,"private-token","/api/native/codex/test/send",new JSONObject());
        require(reads[0]==4&&writes[0]==1,"only safe preflight is retried before exactly one POST");
        final int[] pairs={0};JSONObject pairBody=new JSONObject().put("requestId",UUID.randomUUID().toString()).put("token","qr-fixture");
        Object pairingNet=Proxy.newProxyInstance(transport.getClassLoader(),new Class[]{transport},(o,m,args)->{
            require(((JSONObject)args[3]).getString("requestId").equals(pairBody.getString("requestId")),"pair retry keeps same receipt id");
            if(pairs[0]++==0)throw new IOException("pair reply lost");return new JSONObject().put("token","recovered");
        });
        JSONObject recovered=(JSONObject)call(profiles,"request",signature,context,pairingNet,publicHost,"","/api/pair",pairBody);
        require(pairs[0]==2&&recovered.getString("token").equals("recovered"),"lost pairing reply recovers without a new scan");
        final int[] invalid={0};Object wrongComputer=Proxy.newProxyInstance(transport.getClassLoader(),new Class[]{transport},(o,m,args)->{invalid[0]++;return new JSONObject().put("computerId","computer-b");});
        try{call(profiles,"request",signature,context,wrongComputer,privateHost,"private-token","/api/native/codex/test/send",new JSONObject());throw new AssertionError("identity mismatch should stop write");}catch(InvocationTargetException e){require(e.getCause() instanceof SecurityException,"identity mismatch rejected");}
        require(invalid[0]==1,"identity mismatch never reaches POST or fallback");
        result.putString("stream","PASS: Android Keystore; independent credentials; scoped fallback; safe read/preflight retry; lost pairing receipt recovery; identity check; no business POST replay; route-only removal.\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+e+"\n");result.putString("stack",android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}finally{try{if(saved!=null&&!saved.isEmpty())call(secure,"save",new Class[]{Context.class,String.class},context,saved);else if(secure!=null)call(secure,"clear",new Class[]{Context.class},context);}catch(Exception ignored){}}}
}
