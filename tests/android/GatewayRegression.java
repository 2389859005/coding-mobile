package io.github.abyxs.codingremote.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.util.*;

/** Real QR decode and native HTTP against an isolated Windows gateway. */
public final class GatewayRegression extends Instrumentation {
    String origin;Activity app;
    Object call(Class<?> type,Object target,String method,Class<?>[] signature,Object...args)throws Exception {
        Method m=type.getDeclaredMethod(method,signature);m.setAccessible(true);
        try{return m.invoke(target,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw e;}
    }
    void require(boolean value,String label){if(!value)throw new AssertionError(label);}
    JSONObject fixture(String path)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(origin+path).openConnection(java.net.Proxy.NO_PROXY);
        c.setConnectTimeout(3000);c.setReadTimeout(3000);
        try(InputStream in=c.getInputStream()){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[4096];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);return new JSONObject(out.toString("UTF-8"));}finally{c.disconnect();}
    }
    public void onCreate(Bundle args){super.onCreate(args);origin=args.getString("liveOrigin","");start();}
    public void onStart(){Bundle result=new Bundle();ProxySelector old=ProxySelector.getDefault();try{
        require(origin.startsWith("http://10.0.2.2:"),"isolated emulator only");Context context=getTargetContext();
        Class<?> secure=Class.forName("io.github.abyxs.codingremote.SecureStore");call(secure,null,"clear",new Class[]{Context.class},context);
        context.getSharedPreferences("ui",0).edit().putBoolean("overlay",false).putBoolean("notifications",false).commit();
        app=startActivitySync(new Intent().setClassName(context,"io.github.abyxs.codingremote.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Class<?> netType=Class.forName("io.github.abyxs.codingremote.ConnectionProfiles$Transport"),pairType=Class.forName("io.github.abyxs.codingremote.GatewayPairing"),qrType=Class.forName("io.github.abyxs.codingremote.PairingCode");
        List<String> methods=new ArrayList<>();
        Object net=java.lang.reflect.Proxy.newProxyInstance(netType.getClassLoader(),new Class[]{netType},(p,m,args)->{methods.add((args[3]==null?"GET ":"POST ")+args[2]);return call(app.getClass(),app,"networkRequest",new Class[]{String.class,String.class,String.class,JSONObject.class},args);});
        // Reproduce an unrelated system HTTP proxy. Gateway traffic
        // must still go directly to the computer, with no WebSocket handshake.
        ProxySelector.setDefault(new ProxySelector(){public List<java.net.Proxy> select(URI uri){return Collections.singletonList(new java.net.Proxy(java.net.Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",1)));}public void connectFailed(URI uri,SocketAddress address,IOException e){}});
        for(boolean link:new boolean[]{false,true}){
            JSONObject source=fixture("/fixture/qr");JSONObject advertised=new JSONObject(source.toString()).put("origin","https://fixture.trycloudflare.com");String raw=link?advertised.getString("origin")+"/#pair="+source.getString("token"):advertised.toString();
            JSONObject qr=(JSONObject)call(qrType,null,"parse",new Class[]{String.class},raw);qr.put("origin",origin);
            JSONObject body=new JSONObject().put("token",qr.getString("token")).put("requestId",UUID.randomUUID().toString()).put("device","gateway regression");
            methods.clear();JSONObject receipt=(JSONObject)call(pairType,null,"exchange",new Class[]{Context.class,netType,JSONObject.class,JSONObject.class},context,net,qr,body);
            require(methods.equals(Arrays.asList("GET /api/gateway","POST /api/pair")),"native HTTP probe then exactly one pairing POST");
            require(!receipt.optString("token").isEmpty()&&receipt.optString("computerId").equals(source.getString("computerId")),"real pairing receipt matches the QR computer");
        }
        JSONObject qr=fixture("/fixture/qr"),body=new JSONObject().put("token",qr.getString("token")).put("requestId",UUID.randomUUID().toString());
        fixture("/fixture/wrong-gateway");methods.clear();
        try{call(pairType,null,"exchange",new Class[]{Context.class,netType,JSONObject.class,JSONObject.class},context,net,qr,body);throw new AssertionError("foreign Responses service must be rejected");}catch(IOException expected){require(!expected.getMessage().contains("转发端口")&&expected.getMessage().contains("托盘"),"recovery instructions reference available controls");}
        require(methods.equals(Arrays.asList("GET /api/gateway")),"foreign service receives no pairing credentials or POST");
        fixture("/fixture/restore-gateway");methods.clear();qr.put("computerId","wrong-computer");
        try{call(pairType,null,"exchange",new Class[]{Context.class,netType,JSONObject.class,JSONObject.class},context,net,qr,body);throw new AssertionError("mismatched computer must be rejected");}catch(SecurityException expected){}
        require(methods.equals(Arrays.asList("GET /api/gateway")),"computer mismatch never reaches POST");
        JSONObject metrics=fixture("/fixture/metrics");require(metrics.optInt("pairPosts")==2&&metrics.optInt("upgrades")==0,"no WebSocket upgrades and only two successful pair writes");
        result.putString("stream","PASS: real legacy JSON QR and URL QR pairing; native GET + HTTP POST; broken system proxy bypassed; wrong Responses endpoint and computer identity rejected before credentials; no WebSocket upgrade.\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}
    finally{ProxySelector.setDefault(old);if(app!=null)runOnMainSync(()->app.finish());}}
}
