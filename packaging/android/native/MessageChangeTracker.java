package io.github.abyxs.codingremote;

import org.json.*;
import java.util.*;

/** In-memory baseline per computer. A polling refresh itself is never a change. */
final class MessageChangeTracker {
    private final Map<String,String> revisions=new HashMap<>();
    private final Set<String> initialized=new HashSet<>();
    boolean update(JSONObject snapshot){
        String scope=snapshot.optString("attentionScope","default");boolean changed=false;
        JSONArray tasks=snapshot.optJSONArray("tasks");Set<String> available=new HashSet<>();
        for(String backend:new String[]{"codex","claude","deepseek"})if(AttentionTracker.available(snapshot,backend))available.add(scope+"|"+backend);
        for(int i=0;tasks!=null&&i<tasks.length();i++){
            JSONObject task=tasks.optJSONObject(i);if(task==null)continue;String backend=task.optString("backend");
            if(!AttentionTracker.available(snapshot,backend))continue;
            String baseline=scope+"|"+backend;available.add(baseline);
            if(!task.has("messageRevision")||task.isNull("messageRevision"))continue;
            String revision=task.optString("messageRevision");
            String key=new JSONArray().put(scope).put(backend).put(task.optString("id")).toString();
            String previous=revisions.get(key);
            if(previous!=null&&!revision.isEmpty()&&!revision.equals(previous))changed=true;
            revisions.put(key,revision);
        }
        initialized.addAll(available);
        if(revisions.size()>2000){revisions.clear();initialized.clear();}
        return changed;
    }
}
