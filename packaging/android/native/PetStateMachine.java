package io.github.abyxs.codingremote;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Small, deterministic event reducer for the floating pet.
 *
 * The monitor is allowed to poll or replay the same snapshot. This class only
 * emits an event when a meaningful transition happened, and ranks events so a
 * new error or approval cannot be hidden by a normal message update.
 */
final class PetStateMachine {
    static final String NONE="";
    static final String MESSAGE="message";
    static final String QUESTION="question";
    static final String APPROVAL="approval";
    static final String COMPLETED="completed";
    static final String ERROR="error";

    private PetStateMachine(){}

    static String reduce(JSONObject previous, JSONObject current,
                         java.util.List<JSONObject> fresh, boolean messageChanged){
        String best=NONE;
        if(fresh!=null)for(JSONObject item:fresh){
            if(item==null)continue;
            String kind=item.optString("kind","");
            String candidate=kind.equals("approval")?APPROVAL:kind.equals("question")?QUESTION:MESSAGE;
            if(priority(candidate)>priority(best))best=candidate;
        }
        JSONArray tasks=current==null?null:current.optJSONArray("tasks");
        if(tasks!=null)for(int i=0;i<tasks.length();i++){
            JSONObject task=tasks.optJSONObject(i);if(task==null)continue;
            String backend=task.optString("backend");if(!AttentionTracker.available(current,backend)||previous==null||!AttentionTracker.available(previous,backend))continue;
            String id=identity(task,i),state=task.optString("status","").toLowerCase(java.util.Locale.ROOT);
            String old=oldState(previous,id,i);
            if(!old.isEmpty()&&isError(state)&&!isError(old)&&priority(ERROR)>priority(best))best=ERROR;
            else if(isComplete(state)&&isRunning(old)&&priority(COMPLETED)>priority(best))best=COMPLETED;
        }
        if(best.equals(NONE)&&messageChanged)best=MESSAGE;
        return best;
    }

    static int priority(String event){
        if(ERROR.equals(event))return 5;
        if(APPROVAL.equals(event))return 4;
        if(QUESTION.equals(event))return 3;
        if(COMPLETED.equals(event))return 2;
        if(MESSAGE.equals(event))return 1;
        return 0;
    }

    private static String oldState(JSONObject snapshot,String id,int index){
        if(snapshot==null)return "";
        JSONArray tasks=snapshot.optJSONArray("tasks");if(tasks==null)return "";
        for(int i=0;i<tasks.length();i++){
            JSONObject task=tasks.optJSONObject(i);if(task==null)continue;
            if(identity(task,i).equals(id))return task.optString("status","").toLowerCase(java.util.Locale.ROOT);
        }
        return "";
    }

    private static String identity(JSONObject task,int index){
        String id=task.optString("id",task.optString("sessionId",task.optString("title","")));
        return task.optString("backend")+":"+(id.isEmpty()?"#"+index:id);
    }

    private static boolean isError(String state){
        return state.equals("error")||state.equals("failed")||state.equals("failure")||state.equals("crashed");
    }
    private static boolean isComplete(String state){
        return state.equals("idle")||state.equals("completed")||state.equals("complete")||state.equals("done")||state.equals("success");
    }
    private static boolean isRunning(String state){return state.equals("active")||state.equals("running")||state.equals("inprogress")||state.equals("working");}
}
