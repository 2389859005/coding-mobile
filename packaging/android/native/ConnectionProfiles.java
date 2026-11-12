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
    /** A recently identity-checked route can carry the next request directly. */
    private static final HashMap<String,Long> validated=new HashMap<>();
    private static final HashMap<String,Long> latency=new HashMap<>();
    static synchronized long latency(JSONObject route){return latency.getOrDefault(routeKey(route.optString("origin"),route.optString("token")),4000L);}
    static synchronized void measured(JSONObject route,long elapsed,boolean good){String key=routeKey(route.optString("origin"),route.optString("token"));health(route,good);if(good){long old=latency.getOrDefault(key,elapsed);latency.put(key,(old+elapsed)/2);markValidated(route.optString("origin"),route.optString("token"));}if(latency.size()>64)latency.clear();}
    static int compare(JSONObject a,JSONObject b){int cooldown=Boolean.compare(cooling(a),cooling(b));return cooldown!=0?cooldown:Long.compare(latency(a),latency(b));}
    private static String routeKey(String origin,String token){return origin+"\n"+token;}
    private static synchronized boolean recentlyValidated(String origin,String token){return validated.getOrDefault(routeKey(origin,token),0L)>android.os.SystemClock.elapsedRealtime();}
    private static synchronized void markValidated(String origin,String token){if(validated.size()>64)validated.clear();validated.put(routeKey(origin,token),android.os.SystemClock.elapsedRealtime()+30000);}
    private static synchronized void forgetValidated(String origin,String token){validated.remove(routeKey(origin,token));}
    private static synchronized boolean cooling(JSONObject route){return unhealthy.getOrDefault(route.optString("origin"),0L)>android.os.SystemClock.elapsedRealtime();}
    private static synchronized void health(JSONObject route,boolean good){String key=route.optString("origin");if(good)unhealthy.remove(key);else {if(unhealthy.size()>32)unhealthy.clear();unhealthy.put(key,android.os.SystemClock.elapsedRealtime()+15000);}}
    private static boolean retryable(IOException error){return !(error instanceof HttpFailure)||((HttpFailure)error).routeFailure;}
    private static void pause()throws InterruptedException{Thread.sleep(450);}
    static JSONObject read(Context c)throws Exception {String raw=SecureStore.read(c);return raw.isEmpty()?new JSONObject():new JSONObject(raw);}
    static synchronized void migrateScanOnly(Context c)throws Exception {
        if(c.getSharedPreferences("ui",0).getBoolean("scanOnlyV4",false))return;
        JSONObject root=read(c);JSONArray kept=new JSONArray(),old=rows(root);JSONObject selected=null;
        for(int i=0;i<old.length();i++){JSONObject route=old.getJSONObject(i);if(!TrustedOrigin.scanOrigin(route.optString("origin")))continue;kept.put(copy(route));if(selected==null||route.optString("token").equals(root.optString("token")))selected=route;}
        if(kept.length()!=old.length()){
            SecureStore.save(c,"legacy-device-v3",root.toString());
            if(selected==null)SecureStore.clear(c);else SecureStore.save(c,copy(selected).put("profiles",kept).toString());
        }
        c.getSharedPreferences("ui",0).edit().putBoolean("scanOnlyV4",true).apply();
    }
    static JSONArray rows(JSONObject root)throws Exception {JSONArray rows=root.optJSONArray("profiles");if(rows!=null)return rows;rows=new JSONArray();if(root.has("token"))rows.put(copy(root));return rows;}
    static JSONObject copy(JSONObject row)throws Exception {return new JSONObject().put("origin",row.getString("origin")).put("token",row.getString("token")).put("computerId",row.optString("computerId")).put("mode",mode(row)).put("expires",row.opt("expires"));}
    private static String mode(JSONObject row){String mode=row.optString("mode");return mode.equals("permanent")||mode.equals("server")||mode.equals("ssh")||TrustedOrigin.tailnet(row.optString("origin"))?"permanent":"temporary";}
    /** Cold launches prefer a durable route for the same paired computer. */
    static synchronized JSONObject restore(Context c)throws Exception {
        JSONObject root=read(c);if(!root.has("token")||mode(root).equals("permanent"))return root;
        JSONArray saved=rows(root);
        for(int i=saved.length()-1;i>=0;i--){JSONObject route=saved.getJSONObject(i);if(same(root,route)&&mode(route).equals("permanent")){select(c,route);return read(c);}}
        return root;
    }
    static synchronized void reconcile(Context c,JSONObject route,JSONObject device)throws Exception {
        if(!device.optString("computerId").equals(route.optString("computerId"))||device.optString("computerId").isEmpty())return;
        String reported=device.optString("mode");if(!reported.equals("permanent")&&!reported.equals("temporary"))return;
        JSONObject root=read(c);JSONArray profiles=rows(root);boolean changed=false;
        for(int i=0;i<profiles.length();i++){JSONObject row=profiles.getJSONObject(i);if(row.optString("origin").equals(route.optString("origin"))&&row.optString("token").equals(route.optString("token"))&&!row.optString("mode").equals(reported)){row.put("mode",reported);if(reported.equals("permanent"))row.remove("expires");changed=true;}}
        if(changed){if(root.optString("token").equals(route.optString("token")))root.put("mode",reported);root.put("profiles",profiles);SecureStore.save(c,root.toString());}
    }
    static boolean same(JSONObject a,JSONObject b){return !a.optString("computerId").isEmpty()&&a.optString("computerId").equals(b.optString("computerId"));}
    static String label(JSONObject route){
        if(TrustedOrigin.tailnet(route.optString("origin")))return "永久连接";
        return "permanent".equals(mode(route))?"SSH连接":"临时连接";
    }
    /** History is retained for failover, but temporary routes for this computer
     * are not useful as duplicate settings entries. Show one latest route per
     * computer and connection type, excluding the currently selected route. */
    static JSONArray visibleAlternatives(JSONObject root)throws Exception {
        JSONArray all=rows(root),out=new JSONArray();HashSet<String> seen=new HashSet<>();
        String currentKey=root.optString("computerId")+"\n"+label(root);
        seen.add(currentKey);
        for(int i=all.length()-1;i>=0;i--){JSONObject route=all.getJSONObject(i);String address=route.optString("origin");
            if(!TrustedOrigin.valid(address))continue;
            if(address.equals(root.optString("origin"))&&route.optString("token").equals(root.optString("token")))continue;
            if(same(root,route)&&mode(route).equals("temporary"))continue;
            double expires=route.optDouble("expires",0);if(expires>0&&expires<System.currentTimeMillis()/1000.0)continue;
            String identity=route.optString("computerId");String key=(identity.isEmpty()?address:identity)+"\n"+label(route);
            if(seen.add(key))out.put(copy(route));
        }
        return out;
    }
    static JSONObject merge(JSONObject root,JSONObject route)throws Exception {
        JSONArray out=new JSONArray(),old=rows(root);
        for(int i=0;i<old.length();i++){JSONObject p=old.getJSONObject(i);if(p.optString("origin").equals(route.optString("origin")))continue;out.put(copy(p));}
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
        out.sort(ConnectionProfiles::compare);return out;
    }
    static JSONObject request(Context c,Transport net,String origin,String token,String path,JSONObject body)throws Exception {
        if(token.isEmpty()){
            // Pairing alone is idempotent by requestId. Business POSTs never replay.
            try{return net.request(origin,token,path,body);}
            catch(IOException e){if(!path.equals("/api/pair")||body==null||body.optString("requestId").length()<32||!retryable(e)||e instanceof HttpFailure&&((HttpFailure)e).code==401)throw e;pause();return net.request(origin,token,path,body);}
        }
        JSONObject root=read(c);ArrayList<JSONObject> candidates=candidates(root,origin,token);Exception last=null;JSONObject chosen=null,result=null;
        // Route reuse removes the extra /api/device round trip for the common case.
        // A POST is never replayed after a transport failure because its outcome may
        // already be committed on the desktop; reads may fall back to the normal
        // candidate search when the cached route has gone stale.
        for(JSONObject route:candidates){
            String host=route.optString("origin"),secret=route.optString("token");
            if(!origin.equals(host)||!token.equals(secret)||!recentlyValidated(host,secret))continue;
            try{JSONObject direct=net.request(host,secret,path,body);if(path.equals("/api/device"))reconcile(c,route,direct);health(route,true);return direct;}
            catch(IOException e){forgetValidated(host,secret);health(route,false);if(body!=null)throw e;last=e;}
        }
        // A recently broken route does not delay a working alternative on every poll.
        candidates.sort(ConnectionProfiles::compare);HashSet<String> unauthorized=new HashSet<>();
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
                if(body!=null||path.equals("/api/device")){reconcile(c,route,result);if(result.has("mode"))route.put("mode",result.optString("mode"));}
                health(route,true);markValidated(host,secret);chosen=route;break;
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
