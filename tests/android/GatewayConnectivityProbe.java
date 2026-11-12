package io.github.abyxs.codingremote.tests;
import android.app.*;import android.os.*;import org.json.*;import java.net.*;import java.io.*;import java.lang.reflect.Method;
/** Read-only /api/gateway diagnostics. Does not read or modify pairing/preferences. */
public final class GatewayConnectivityProbe extends Instrumentation {
    static final class Tls12 extends javax.net.ssl.SSLSocketFactory {
        final javax.net.ssl.SSLSocketFactory base=(javax.net.ssl.SSLSocketFactory)javax.net.ssl.SSLSocketFactory.getDefault();
        java.net.Socket configure(java.net.Socket s){((javax.net.ssl.SSLSocket)s).setEnabledProtocols(new String[]{"TLSv1.2"});return s;}
        public String[] getDefaultCipherSuites(){return base.getDefaultCipherSuites();}public String[] getSupportedCipherSuites(){return base.getSupportedCipherSuites();}
        public java.net.Socket createSocket(java.net.Socket s,String h,int p,boolean a)throws IOException{return configure(base.createSocket(s,h,p,a));}
        public java.net.Socket createSocket(String h,int p)throws IOException{return configure(base.createSocket(h,p));}
        public java.net.Socket createSocket(String h,int p,InetAddress l,int q)throws IOException{return configure(base.createSocket(h,p,l,q));}
        public java.net.Socket createSocket(InetAddress h,int p)throws IOException{return configure(base.createSocket(h,p));}
        public java.net.Socket createSocket(InetAddress h,int p,InetAddress l,int q)throws IOException{return configure(base.createSocket(h,p,l,q));}
    }
    String origin;public void onCreate(Bundle args){super.onCreate(args);origin=args.getString("liveOrigin","");start();}
    public void onStart(){Bundle result=new Bundle();JSONArray checks=new JSONArray();try{
        if(!origin.startsWith("https://")||new URL(origin).getPath().length()>1)throw new Exception("HTTPS origin required");
        for(String mode:new String[]{"system","health"}){JSONObject row=new JSONObject().put("mode",mode).put("dns",java.util.Arrays.toString(InetAddress.getAllByName(new URL(origin).getHost())));HttpURLConnection c=null;long started=SystemClock.elapsedRealtime();try{
            if(mode.equals("installed-app")){Class<?> type=Class.forName("io.github.abyxs.codingremote.EmbeddedNetwork");Method m=type.getDeclaredMethod("open",android.content.Context.class,String.class);m.setAccessible(true);c=(HttpURLConnection)m.invoke(null,getTargetContext(),origin+"/api/gateway");}
            else c=(HttpURLConnection)new URL(origin+(mode.equals("health")?"/healthz":"/api/gateway")).openConnection();
            if(mode.equals("tls12"))((javax.net.ssl.HttpsURLConnection)c).setSSLSocketFactory(new Tls12());
            c.setConnectTimeout(7000);c.setReadTimeout(12000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","application/json");c.setRequestProperty("Origin",origin);
            row.put("status",c.getResponseCode()).put("proxy",c.usingProxy());try(InputStream in=c.getInputStream()){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>16384)throw new IOException("response limit");bytes.write(b,0,n);}JSONObject body=new JSONObject(bytes.toString("UTF-8"));row.put("service",body.optString("service")).put("version",body.optString("version"));}
        }catch(Throwable e){JSONArray causes=new JSONArray();for(Throwable error=e;error!=null;error=error.getCause())causes.put(error.getClass().getSimpleName()+": "+error.getMessage());row.put("errors",causes);}finally{if(c!=null)c.disconnect();row.put("ms",SystemClock.elapsedRealtime()-started);checks.put(row);}}
        result.putString("stream","PASS: read-only gateway diagnostics\n"+checks.toString(2)+"\n");finish(Activity.RESULT_OK,result);
    }catch(Exception e){result.putString("stream","FAIL: "+e);finish(Activity.RESULT_CANCELED,result);}}
}
