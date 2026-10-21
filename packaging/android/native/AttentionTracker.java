package io.github.abyxs.codingremote;

import org.json.*;
import java.util.*;

/** Stable request IDs survive polling, reconnects and monitor recreation. */
final class AttentionTracker {
    private final JSONObject seen;
    AttentionTracker(String saved){JSONObject value;try{value=new JSONObject(saved);}catch(Exception e){value=new JSONObject();}seen=value;}
    static boolean available(JSONObject snapshot,String backend){JSONArray unavailable=snapshot.optJSONArray("unavailable");if(unavailable!=null)for(int i=0;i<unavailable.length();i++)if(backend.equals(unavailable.optString(i)))return false;return true;}
    static JSONArray pending(JSONObject task){JSONArray rows=task.optJSONArray("attention");return rows==null?new JSONArray():rows;}
    /** Rows the device has not reported before, with the context an alert needs. */
    ArrayList<JSONObject> fresh(JSONObject snapshot)throws Exception{
        ArrayList<JSONObject> result=new ArrayList<>();long now=System.currentTimeMillis();JSONArray tasks=snapshot.optJSONArray("tasks");
        if(tasks!=null)for(int i=0;i<tasks.length();i++){JSONObject task=tasks.optJSONObject(i);if(task==null||!available(snapshot,task.optString("backend")))continue;
            JSONArray rows=pending(task);
            for(int n=0;n<rows.length();n++){JSONObject item=rows.optJSONObject(n);if(item==null)continue;
                String key=new JSONArray().put(task.optString("backend")).put(task.optString("id")).put(item.optString("kind")).put(item.optString("id")).toString();
                if(!seen.has(key)){try{result.add(new JSONObject().put("backend",task.optString("backend")).put("sessionId",task.optString("id")).put("title",task.optString("title","会话")).put("kind",item.optString("kind")).put("label",item.optString("label","待处理")).put("preview",item.optString("preview","")));}catch(Exception ignored){result.add(item);}}
                seen.put(key,now);}}
        ArrayList<String> expired=new ArrayList<>();Iterator<String> keys=seen.keys();while(keys.hasNext()){String key=keys.next();if(now-seen.optLong(key)>7L*24*3600*1000)expired.add(key);}for(String key:expired)seen.remove(key);return result;
    }
    boolean update(JSONObject snapshot)throws Exception{return !fresh(snapshot).isEmpty();}
    String save(){return seen.toString();}
}
