package io.github.abyxs.codingremote.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.Bitmap;
import java.lang.reflect.*;
import java.io.*;
import java.net.*;
import org.json.*;

/** Real SSH, Android Keystore and HTTP forwarding against a disposable host. */
public final class SshRegression extends Instrumentation {
    Bundle args;Context context;Class<?> ssh,secure;String origin="http://192.168.123.45:18787";
    Object call(Class<?> cls,String name,Class<?>[] signature,Object...values)throws Exception{Method m=cls.getDeclaredMethod(name,signature);m.setAccessible(true);try{return m.invoke(null,values);}catch(InvocationTargetException e){throw (Exception)e.getCause();}}
    Object field(Object obj,String name)throws Exception{Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(obj);}
    void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    String body(HttpURLConnection conn)throws Exception{conn.setConnectTimeout(5000);conn.setReadTimeout(5000);try(InputStream input=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=input.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}finally{conn.disconnect();}}
    int connect(JSONObject config)throws Exception{return (Integer)call(ssh,"connect",new Class[]{String.class,JSONObject.class},origin,config);}
    void save(JSONObject config)throws Exception{call(ssh,"save",new Class[]{Context.class,String.class,JSONObject.class},context,origin,config);}
    void closeLink()throws Exception{call(ssh,"invalidate",new Class[]{});}
    String echo()throws Exception{return body((HttpURLConnection)call(ssh,"open",new Class[]{Context.class,URL.class},context,new URL(origin+"/echo")));}
    @Override public void onCreate(Bundle args){super.onCreate(args);this.args=args;start();}
    @Override public void onStart(){Bundle result=new Bundle();String original=null;Activity activity=null;String savedDevice=null;try{
        context=getTargetContext();ssh=Class.forName("io.github.abyxs.codingremote.SshTunnel");secure=Class.forName("io.github.abyxs.codingremote.SecureStore");
        original=(String)call(secure,"read",new Class[]{Context.class,String.class},context,"ssh");
        String fixture=args.getString("liveOrigin");JSONObject setup=new JSONObject(body((HttpURLConnection)new URL(fixture+"/fixture").openConnection()));
        JSONObject config=new JSONObject().put("host","10.0.2.2").put("port",setup.getInt("sshPort")).put("gatewayPort",setup.getInt("httpPort")).put("user","fixture").put("password","disposable-test-password");
        closeLink();
        try{connect(config);throw new AssertionError("unknown host must not authenticate");}catch(Exception trust){require(trust.getClass().getSimpleName().equals("TrustRequired"),"first connection requires trust: "+trust);require(((String)field(trust,"fingerprint")).startsWith("SHA256:"),"SHA256 fingerprint");config.put("hostKey",field(trust,"key"));}
        JSONObject before=new JSONObject(body((HttpURLConnection)new URL(fixture+"/fixture").openConnection()));require(before.getInt("authCount")==0,"no credential authentication before host trust");
        int first=connect(config);require(first>0,"loopback tunnel assigned");save(config);
        require(echo().contains("192.168.123.45:18787"),"gateway Host survives forwarding");
        String encrypted=context.getSharedPreferences("coding-remote",0).getString("ssh","");require(!encrypted.contains("disposable-test-password")&&!encrypted.contains("hostKey"),"SSH secrets encrypted at rest");
        closeLink();require(echo().contains("192.168.123.45:18787"),"stored configuration reconnects after disconnect");
        closeLink();JSONObject wrong=new JSONObject(config.toString()).put("hostKey","invalid-key");
        try{connect(wrong);throw new AssertionError("changed key accepted");}catch(Exception trust){require(trust.getClass().getSimpleName().equals("TrustRequired")&&(Boolean)field(trust,"changed"),"changed key fails closed");}
        closeLink();JSONObject keyAuth=new JSONObject(config.toString()).put("password","").put("privateKey",setup.getString("privateKey"));connect(keyAuth);save(keyAuth);require(echo().contains("192.168.123.45:18787"),"private key authentication and forwarding");
        Class<?> parser=Class.forName("io.github.abyxs.codingremote.PairingCode");String token="abcdefghijklmnopqrstuvwxyz1234567890ABCDEFG";
        JSONObject link=(JSONObject)call(parser,"parse",new Class[]{String.class},origin+"/#pair="+token);require(link.getString("token").equals(token),"new QR links parsed");
        JSONObject legacy=new JSONObject().put("app","coding-remote").put("v",1).put("origin",origin).put("token",token).put("expires",System.currentTimeMillis()/1000.0+300);
        require(((JSONObject)call(parser,"parse",new Class[]{String.class},legacy.toString())).getString("origin").equals(origin),"legacy QR parsed");
        for(String bad:new String[]{origin+"/outside#pair="+token,"https://user:password@example.com/#pair="+token,"http://example.com:8787/#pair="+token})try{call(parser,"parse",new Class[]{String.class},bad);throw new AssertionError("invalid QR accepted");}catch(IllegalArgumentException expected){}
        savedDevice=(String)call(secure,"read",new Class[]{Context.class},context);call(secure,"save",new Class[]{Context.class,String.class},context,"");
        context.getSharedPreferences("ui",0).edit().putString("theme","mist").putString("appearance","light").putBoolean("resumeChat",false).commit();
        activity=startActivitySync(new Intent(context,Class.forName("io.github.abyxs.codingremote.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        screenshot("dsh-welcome");final Activity shown=activity;runOnMainSync(()->{try{Object ui=field(shown,"ui");Class<?> sheet=Class.forName("io.github.abyxs.codingremote.SshConnectionSheet");Method show=sheet.getDeclaredMethod("show",Activity.class,ui.getClass(),String.class,java.util.function.Consumer.class);show.setAccessible(true);show.invoke(null,shown,ui,"",(java.util.function.Consumer<String>)value->{});}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();screenshot("dsh-ssh-sheet");
        result.putString("stream","PASS: first-use trust before authentication; password and private-key SSH; Host header preserved; Keystore encryption; reconnect; changed-key rejection; old/new QR formats; native connection screenshots.\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable error){StringWriter out=new StringWriter();error.printStackTrace(new PrintWriter(out));result.putString("stream","FAIL: "+out);finish(Activity.RESULT_CANCELED,result);}finally{try{closeLink();if(original!=null)call(secure,"save",new Class[]{Context.class,String.class,String.class},context,"ssh",original);if(savedDevice!=null)call(secure,"save",new Class[]{Context.class,String.class},context,savedDevice);}catch(Exception ignored){}if(activity!=null){final Activity a=activity;runOnMainSync(a::finish);}}}
    void screenshot(String name)throws Exception{Thread.sleep(400);Bitmap bitmap=getUiAutomation().takeScreenshot();File dir=new File(context.getExternalFilesDir(null),"ui-captures");dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}}
}
