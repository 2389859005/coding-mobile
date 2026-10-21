package io.github.abyxs.codingremote;

import android.content.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import org.json.*;

/** Credentials stay bound to their scanned origin and authenticated computer identity. */
final class ConnectionProfiles {
    interface Transport {JSONObject request(String origin,String token,String path,JSONObject body)throws Exception;}
    static class HttpFailure extends IOException {final int code;final boolean routeFailure;HttpFailure(int code,String text){this(code,text,code==401||code>=500);}HttpFailure(int code,String text,boolean routeFailure){super(text);this.code=code;this.routeFailure=routeFailure;}}
    private static final HashMap<String,Long> unhealthy=new HashMap<>();
    private static synchronized boolean cooling(JSONObject route){return unhealthy.getOrDefault(route.optString("origin"),0L)>android.os.SystemClock.elapsedRealtime();}
    private static synchronized void health(JSONObject route,boolean good){String key=route.optString("origin");if(good)unhealthy.remove(key);else {if(unhealthy.size()>32)unhealthy.clear();unhealthy.put(key,android.os.SystemClock.elapsedRealtime()+15000);}}
    private static boolean retryable(IOException error){return !(error instanceof HttpFailure)||((HttpFailure)error).routeFailure;}
    private static void pause()throws InterruptedException{Thread.sleep(450);}
    static JSONObject read(Context c)throws Exception {String raw=SecureStore.read(c);return raw.isEmpty()?new JSONObject():new JSONObject(raw);}
    static JSONArray rows(JSONObject root)throws Exception {JSONArray rows=root.optJSONArray("profiles");if(rows!=null)return rows;rows=new JSONArray();if(root.has("token"))rows.put(copy(root));return rows;}
    static JSONObject copy(JSONObject row)throws Exception {return new JSONObject().put("origin",row.getString("origin")).put("token",row.getString("token")).put("computerId",row.optString("computerId")).put("mode",row.optString("mode",row.optString("origin").startsWith("http://100.")?"permanent":"temporary"));}
    static boolean same(JSONObject a,JSONObject b){return !a.optString("computerId").isEmpty()&&a.optString("computerId").equals(b.optString("computerId"));}
    static JSONObject merge(JSONObject root,JSONObject route)throws Exception {
        JSONArray out=new JSONArray(),old=rows(root);
        for(int i=0;i<old.length();i++){JSONObject p=old.getJSONObject(i);if(p.optString("origin").equals(route.optString("origin")))continue;if(same(p,route)&&p.optString("mode").equals(route.optString("mode")))continue;out.put(copy(p));}
        out.put(copy(route));return copy(route).put("profiles",out);
    }
    static synchronized void add(Context c,JSONObject route)throws Exception {SecureStore.save(c,merge(read(c),route).toString());}
    static synchronized void select(Context c,JSONObject route)throws Exception {JSONObject root=read(c);if(root.optString("token").equals(route.optString("token")))return;JSONArray rows=rows(root);boolean present=false;for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).optString("token").equals(route.optString("token")))present=true;if(!present)return;SecureStore.save(c,copy(route).put("profiles",rows).toString());}
    static synchronized void prefer(Context c,JSONObject route,JSONObject previous)throws Exception {JSONObject current=read(c);if(same(current,route)||current.optString("token").equals(previous.optString("token")))select(c,route);}
    static ArrayList<JSONObject> candidates(JSONObject root,String origin,String token)throws Exception {
        ArrayList<JSONObject> out=new ArrayList<>();JSONArray rows=rows(root);JSONObject original=null;
        for(int i=0;i<rows.length();i++){JSONObject p=rows.getJSONObject(i);if(origin.equals(p.optString("origin"))&&token.equals(p.optString("token")))original=p;}
        if(original==null){out.add(new JSONObject().put("origin",origin).put("token",token));return out;}
        if(same(original,root)&&root.has("token"))out.add(copy(root));
        if(out.isEmpty()||!out.get(0).optString("token").equals(token))out.add(original);
        for(int i=0;i<rows.length();i++){JSONObject p=rows.getJSONObject(i);if(!same(p,original))continue;boolean present=false;for(JSONObject r:out)if(r.optString("token").equals(p.optString("token")))present=true;if(!present)out.add(p);}
        return out;
    }
    static JSONObject request(Context c,Transport net,String origin,String token,String path,JSONObject body)throws Exception {
        if(token.isEmpty()){
            // Pairing alone is idempotent by requestId. Business POSTs never replay.
            try{return net.request(origin,token,path,body);}
            catch(IOException e){if(!path.equals("/api/pair")||body==null||body.optString("requestId").length()<32||!retryable(e)||e instanceof HttpFailure&&((HttpFailure)e).code==401)throw e;pause();return net.request(origin,token,path,body);}
        }
        JSONObject root=read(c);ArrayList<JSONObject> candidates=candidates(root,origin,token);Exception last=null;JSONObject chosen=null,result=null;
        // A recently broken route does not delay a working alternative on every poll.
        candidates.sort((a,b)->Boolean.compare(cooling(a),cooling(b)));HashSet<String> unauthorized=new HashSet<>();
        for(int round=0;round<2&&chosen==null;round++){
        if(round>0)pause();
        for(JSONObject route:candidates){
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("连接请求已取消");
            if(unauthorized.contains(route.optString("token")))continue;
            try{
                String host=route.getString("origin"),secret=route.getString("token");
                // Probe before writes. Once POST begins, there is exactly one transport attempt.
                // Writes probe /api/device, reads probe the path itself, but the identity
                // check uses whichever payload carries computerId, so polling GETs are
                // no longer accepted from a host that merely holds a valid token.
                String expected=route.optString("computerId");
                result=net.request(host,secret,body==null?path:"/api/device",null);
                String reported=result.optString("computerId");
                if(!expected.isEmpty()&&!reported.isEmpty()&&!expected.equals(reported))throw new SecurityException("电脑身份不匹配，请重新扫码");
                health(route,true);chosen=route;break;
            }catch(IOException e){last=e;if(!retryable(e))throw e;health(route,false);if(e instanceof HttpFailure&&((HttpFailure)e).code==401)unauthorized.add(route.optString("token"));}
        }
        }
        if(chosen==null)throw last==null?new IOException("无可用连接"):last;
        // Only already stored credentials may become the preferred route.
        if(chosen.has("mode"))prefer(c,chosen,root);
        return body==null?result:net.request(chosen.getString("origin"),chosen.getString("token"),path,body);
    }
    static synchronized JSONObject remove(Context c,String origin,String token)throws Exception {
        JSONObject root=read(c);JSONArray out=new JSONArray(),old=rows(root);for(int i=0;i<old.length();i++){JSONObject p=old.getJSONObject(i);if(!(origin.equals(p.optString("origin"))&&token.equals(p.optString("token"))))out.put(p);}
        if(out.length()==0){SecureStore.clear(c);return new JSONObject();}JSONObject next=copy(out.getJSONObject(0)).put("profiles",out);SecureStore.save(c,next.toString());return next;
    }
}
