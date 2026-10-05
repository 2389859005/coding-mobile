package io.github.abyxs.codingremote;

import org.json.*;
import java.util.*;

/** Stable request IDs survive polling, reconnects and monitor recreation. */
final class AttentionTracker {
    private final JSONObject seen;
    AttentionTracker(String saved){JSONObject value;try{value=new JSONObject(saved);}catch(Exception e){value=new JSONObject();}seen=value;}
    static boolean available(JSONObject snapshot,String backend){JSONArray unavailable=snapshot.optJSONArray("unavailable");if(unavailable!=null)for(int i=0;i<unavailable.length();i++)if(backend.equals(unavailable.optString(i)))return false;return true;}
    static JSONArray pending(JSONObject task){JSONArray rows=task.optJSONArray("attention");return rows==null?new JSONArray():rows;}
    boolean update(JSONObject snapshot)throws Exception{
        boolean fresh=false;long now=System.currentTimeMillis();JSONArray tasks=snapshot.optJSONArray("tasks");
        if(tasks!=null)for(int i=0;i<tasks.length();i++){JSONObject task=tasks.optJSONObject(i);if(task==null||!available(snapshot,task.optString("backend")))continue;JSONArray rows=pending(task);for(int n=0;n<rows.length();n++){JSONObject item=rows.optJSONObject(n);if(item==null)continue;String key=new JSONArray().put(task.optString("backend")).put(task.optString("id")).put(item.optString("kind")).put(item.optString("id")).toString();if(!seen.has(key))fresh=true;seen.put(key,now);}}
        ArrayList<String> expired=new ArrayList<>();Iterator<String> keys=seen.keys();while(keys.hasNext()){String key=keys.next();if(now-seen.optLong(key)>7L*24*3600*1000)expired.add(key);}for(String key:expired)seen.remove(key);return fresh;
    }
    String save(){return seen.toString();}
}
