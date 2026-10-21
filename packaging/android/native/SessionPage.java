package io.github.abyxs.codingremote;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.security.MessageDigest;

/** Prepared off the UI thread. One bounded page, never a full transcript view tree. */
final class SessionPage {
    static final int PAGE_SIZE=60, CHUNK_SIZE=6000;
    static final class Row {
        final String id,role,text,kind,title,language,turnId;
        final boolean continuation;
        Row(String i,String r,String t,boolean c){this(i,r,t,c,new JSONObject());}
        Row(String i,String r,String t,boolean c,JSONObject m){id=i;role=r;text=t;continuation=c;kind=m.optString("kind");title=m.optString("title");language=m.optString("language");turnId=m.optString("turnId");}
        boolean activity(){
            if(kind.equals("code")||kind.equals("step")||kind.equals("executionGroup")||kind.equals("image"))return true;
            if(!role.equals("user")&&!role.equals("assistant"))return true;
            if(!role.equals("assistant")||!text.trim().startsWith("[工具调用]"))return false;
            for(String line:text.trim().split("\n"))if(!line.trim().isEmpty()&&!line.trim().startsWith("[工具调用]"))return false;
            return true;
        }
        boolean imageStep(){String name=title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]","");return kind.equals("image")||name.contains("imageview")||name.contains("viewimage");}
        boolean groupable(){return activity()&&!imageStep()&&!kind.equals("code");}
        String label(){
            if(!title.isEmpty())return title;
            if(role.equals("reasoning")||role.equals("thought")||role.equals("analysis"))return "思考摘要";
            if(text.trim().startsWith("[工具调用]"))return text.trim().split("\n",2)[0];
            if(role.equals("tool")||role.equals("commandExecution"))return "执行命令";
            if(role.equals("fileChange"))return "文件变更";
            return "执行记录";
        }
    }
    static final class Permission {
        final Object id;
        final String tool,preview; final JSONObject input; final String method;
        final boolean needsInput;
        Permission(JSONObject p){input=p.optJSONObject("input")==null?new JSONObject():p.optJSONObject("input");method=p.optString("method");id=p.opt("id");tool=p.optString("tool");needsInput=p.optBoolean("needsInput");
            String full=String.valueOf(p.opt("input"));preview=full.length()>1200?full.substring(0,1200)+"…":full;}
    }
    final ArrayList<Row> rows=new ArrayList<>();
    final ArrayList<Permission> permissions=new ArrayList<>();
    String completed=""; String token=""; String projectCwd=""; String revision,meta,notice,cwd,model,effort,title,permissionMode; JSONObject source,changes,contextUsage; JSONArray steps; int turns;
    boolean running,hasEarlier,hasLater,connected;
    int before,total;

    static SessionPage prepare(JSONObject value)throws Exception {
        SessionPage page=new SessionPage();page.source=value;JSONObject s=value.getJSONObject("session");page.turns=s.optInt("turnCount");page.token=value.optString("token","");page.completed=s.optString("completedTurnId",s.optString("turnId",""));page.title=s.optString("title");page.effort=s.optString("effort","");page.changes=value.optJSONObject("changes");page.steps=value.optJSONArray("steps");
        String state=s.optString("status","idle");page.running=state.equals("active")||state.equals("inProgress")||state.equals("running");
        page.cwd=s.optString("cwd","");page.projectCwd=s.optString("projectCwd","");page.model=s.isNull("model")?"":s.optString("model","");page.connected=value.optBoolean("connected",false);
        page.permissionMode=s.isNull("permissionMode")?"":s.optString("permissionMode","");
        page.contextUsage=s.optJSONObject("contextUsage");
        page.meta=(page.running?"正在执行":"等待新消息");
        page.notice=value.optString("notice","");
        if(page.notice.isEmpty()&&!value.isNull("error"))page.notice=value.optString("error","");
        JSONArray messages=value.optJSONArray("messages");
        // Older desktop builds can still send an unbounded transcript. Keep only a tail.
        if(messages!=null)for(int i=messages.length()-1;i>=0&&page.rows.size()<PAGE_SIZE;i--){
            JSONObject m=messages.getJSONObject(i);String body=m.optString("text","");int end=body.length();
            ArrayList<Row> parts=new ArrayList<>();
            while(end>0&&parts.size()+page.rows.size()<PAGE_SIZE){
                int start=Math.max(0,end-CHUNK_SIZE);
                if(start>0&&Character.isLowSurrogate(body.charAt(start))&&Character.isHighSurrogate(body.charAt(start-1)))start++;
                parts.add(0,new Row(m.optString("id",String.valueOf(i))+":"+start,m.optString("role","activity"),body.substring(start,end),start>0||m.optBoolean("continuation"),m));end=start;
            }
            page.rows.addAll(0,parts);
        }
        ArrayList<Row> grouped=new ArrayList<>();for(int i=0;i<page.rows.size();){Row first=page.rows.get(i);if(!first.groupable()){grouped.add(first);i++;continue;}int end=i+1;while(end<page.rows.size()&&page.rows.get(end).groupable())end++;if(end-i==1){grouped.add(first);i=end;continue;}StringBuilder detail=new StringBuilder();for(int n=i;n<end;n++){Row item=page.rows.get(n);if(detail.length()>0)detail.append("\n\n");detail.append("### ").append(item.label()).append("\n").append(item.text);}JSONObject meta=new JSONObject().put("kind","executionGroup").put("title","执行过程 · "+(end-i)+" 项").put("turnId",first.turnId);grouped.add(new Row("group:"+first.id,"activity",detail.toString(),false,meta));i=end;}page.rows.clear();page.rows.addAll(grouped);
        JSONObject history=value.optJSONObject("history");
        page.total=page.rows.size();
        if(history!=null){page.before=history.optInt("before");page.hasEarlier=history.optBoolean("hasEarlier");page.hasLater=history.optBoolean("hasLater");page.total=history.optInt("total",page.rows.size());}
        JSONArray requests=s.optJSONArray("requests");
        if(requests!=null)for(int i=0;i<Math.min(10,requests.length());i++)page.permissions.add(new Permission(requests.getJSONObject(i)));
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        digest.update((byte)(page.running?1:0));
        for(Row row:page.rows){for(String part:new String[]{row.id,row.role,row.text,row.kind,row.title,row.language,String.valueOf(row.continuation)}){digest.update(part.getBytes("UTF-8"));digest.update((byte)0);}}
        StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format("%02x",b&255));page.revision=hash.toString();
        return page;
    }
}
