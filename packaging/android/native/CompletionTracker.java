package io.github.abyxs.codingremote;

import org.json.*;
import java.util.ArrayList;

/** Remembers observed execution. Missing/offline tasks never count as completed. */
final class CompletionTracker {
    private final JSONObject active;
    CompletionTracker(String saved){JSONObject parsed;try{parsed=new JSONObject(saved);}catch(Exception e){parsed=new JSONObject();}active=parsed;}
    ArrayList<JSONObject> update(JSONObject snapshot)throws Exception {
        ArrayList<JSONObject> finished=new ArrayList<>();JSONArray rows=snapshot.optJSONArray("tasks");if(rows==null)return finished;
        JSONArray unavailable=snapshot.optJSONArray("unavailable");JSONObject known=active.optJSONObject("__known");if(known==null){known=new JSONObject();active.put("__known",known);}JSONObject initialized=active.optJSONObject("__providers");if(initialized==null){initialized=new JSONObject();active.put("__providers",initialized);}java.util.HashSet<String> available=new java.util.HashSet<>();
        for(int i=0;i<rows.length();i++){JSONObject task=rows.optJSONObject(i);if(task==null)continue;String backend=task.optString("backend"),key=backend+":"+task.optString("id"),state=task.optString("status");boolean offline=false;if(unavailable!=null)for(int j=0;j<unavailable.length();j++)if(backend.equals(unavailable.optString(j)))offline=true;if(offline)continue;
            boolean running=state.equals("active")||state.equals("running")||state.equals("inProgress")||state.equals("working");
            boolean terminal=state.equals("idle")||state.equals("completed")||state.equals("complete")||state.equals("failed")||state.equals("error")||state.equals("interrupted");
            String stamp=task.optString("token",task.optString("turnId",task.optString("updatedAt","")));boolean changed=!stamp.isEmpty()&&!stamp.equals(known.optString(key));
            JSONArray attention=task.optJSONArray("attention"),requests=task.optJSONArray("requests");boolean waiting=attention!=null&&attention.length()>0||requests!=null&&requests.length()>0;
            if(running||waiting)active.put(key,System.currentTimeMillis());else if(terminal&&(active.has(key)||initialized.optBoolean(backend)&&changed)){finished.add(task);active.remove(key);}if(!stamp.isEmpty())known.put(key,stamp);available.add(backend);
        }
        // Expire abandoned observations rather than retaining arbitrary task history forever.
        for(String backend:available)initialized.put(backend,true);
        ArrayList<String> expired=new ArrayList<>();java.util.Iterator<String> keys=active.keys();while(keys.hasNext()){String key=keys.next();if(!key.startsWith("__")&&System.currentTimeMillis()-active.optLong(key)>7L*24*3600*1000)expired.add(key);}for(String key:expired)active.remove(key);
        return finished;
    }
    String save(){return active.toString();}
}
