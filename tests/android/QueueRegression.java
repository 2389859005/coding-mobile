package io.github.abyxs.codingremote.tests;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.*;

/** UI-to-transport integration with a synthetic desktop. Never calls a real API. */
public final class QueueRegression extends Instrumentation {
    Activity app;Throwable failure;final List<String> paths=new CopyOnWriteArrayList<>();final List<JSONObject> writes=new CopyOnWriteArrayList<>();
    private interface Check{void run()throws Exception;}
    Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    Object call(String name,Class<?>[] types,Object...args)throws Exception{Method m=app.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(app,args);}
    void ui(Check c)throws Exception{failure=null;runOnMainSync(()->{try{c.run();}catch(Throwable e){failure=e;}});if(failure!=null)throw new Exception(failure);waitForIdleSync();}
    void require(boolean value,String label){if(!value)throw new AssertionError(label);}
    void await(Check c)throws Exception{Throwable error=null;for(int i=0;i<70;i++){try{c.run();return;}catch(Throwable e){error=e;}SystemClock.sleep(100);}throw new Exception(error);}
    AccessibilityNodeInfo find(AccessibilityNodeInfo node,String text){if(node==null)return null;if(text.equals(String.valueOf(node.getText()))||text.equals(String.valueOf(node.getContentDescription())))return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo r=find(node.getChild(i),text);if(r!=null)return r;}return null;}
    void click(String text)throws Exception{await(()->require(find(getUiAutomation().getRootInActiveWindow(),text)!=null,"visible: "+text));AccessibilityNodeInfo node=find(getUiAutomation().getRootInActiveWindow(),text);Rect bounds=new Rect();node.getBoundsInScreen(bounds);require(!bounds.isEmpty(),"bounds: "+text);long time=SystemClock.uptimeMillis();getUiAutomation().injectInputEvent(MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0),true);getUiAutomation().injectInputEvent(MotionEvent.obtain(time,time+80,MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0),true);SystemClock.sleep(300);waitForIdleSync();}
    void shot(String name)throws Exception{SystemClock.sleep(3200);Bitmap b=getUiAutomation().takeScreenshot();File dir=new File(getTargetContext().getExternalFilesDir(null),"ui-captures");dir.mkdirs();try(OutputStream out=new FileOutputStream(new File(dir,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
    JSONObject file(String name,String mime,byte[] bytes)throws Exception{return new JSONObject().put("name",name).put("mime",mime).put("data",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP));}
    void swipe(View view,float dx,float dy)throws Exception{Rect r=new Rect();view.getGlobalVisibleRect(r);float x=r.exactCenterX(),y=r.exactCenterY();long t=SystemClock.uptimeMillis();getUiAutomation().injectInputEvent(MotionEvent.obtain(t,t,0,x,y,0),true);for(int i=1;i<=8;i++){SystemClock.sleep(30);getUiAutomation().injectInputEvent(MotionEvent.obtain(t,SystemClock.uptimeMillis(),2,x+dx*i/8,y+dy*i/8,0),true);}getUiAutomation().injectInputEvent(MotionEvent.obtain(t,SystemClock.uptimeMillis(),1,x+dx,y+dy,0),true);SystemClock.sleep(700);waitForIdleSync();}
    @Override public void onCreate(Bundle args){super.onCreate(args);getTargetContext().getSharedPreferences("coding-remote",0).edit().clear().commit();getTargetContext().getSharedPreferences("ui",0).edit().putBoolean("notifications",false).putBoolean("overlay",false).putBoolean("resumeChat",false).commit();start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        JSONArray queue=new JSONArray();JSONObject fixture=new JSONObject().put("connected",true).put("session",new JSONObject().put("id","fixture").put("title","队列测试").put("status","active").put("requests",new JSONArray())).put("messages",new JSONArray());
        app=startActivitySync(new Intent().setClassName(getTargetContext(),"io.github.abyxs.codingremote.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Class<?> transport=Class.forName("io.github.abyxs.codingremote.MainActivity$Transport");Object mock=Proxy.newProxyInstance(transport.getClassLoader(),new Class[]{transport},(proxy,method,args)->{
            String path=(String)args[2];JSONObject body=(JSONObject)args[3];if(body!=null)writes.add(new JSONObject(body.toString()).put("route",path));
            if(path.endsWith("/queue")&&body!=null){String operation=body.optString("operation");if(operation.equals("enqueue")){queue.put(new JSONObject().put("id",body.getString("id")).put("state","waiting").put("body",body.getJSONObject("message")));return new JSONObject().put("status","queued");}for(int i=0;i<queue.length();i++)if(queue.getJSONObject(i).getString("id").equals(body.optString("queueId"))){JSONObject item=queue.getJSONObject(i);queue.remove(i);return new JSONObject().put("status","accepted").put("messageBody",item.getJSONObject("body"));}}
            if(body!=null)return new JSONObject().put("status","accepted");return new JSONObject(fixture.toString()).put("queue",new JSONArray(queue.toString()));
        });
        ui(()->{set(app,"origin","https://fixture.trycloudflare.com");set(app,"token","fixture");set(app,"backend","codex");set(app,"transport",mock);call("openSession",new Class[]{String.class,String.class},"fixture","队列测试");});SystemClock.sleep(600);
        ui(()->{((EditText)get(app,"composer")).setText("任务完成后检查测试");((Button)get(app,"sendButton")).performClick();});await(()->require(((JSONArray)get(app,"queuedMessages")).length()==1,"queue visible after enqueue"));
        require(writes.stream().noneMatch(w->w.optString("route").endsWith("/send")),"running task never receives direct send by default");
        ui(()->{require(((EditText)get(app,"composer")).length()==0,"queued draft cleared after acknowledgement");set(app,"queueExpanded",true);call("renderQueue",new Class[]{});});String id=queue.getJSONObject(0).getString("id");click("撤回编辑 "+id);await(()->ui(()->require(((EditText)get(app,"composer")).getText().toString().equals("任务完成后检查测试"),"recall restores editable draft")));
        ui(()->{((EditText)get(app,"composer")).append("，补充边界情况");((Button)get(app,"sendButton")).performClick();});await(()->require(((JSONArray)get(app,"queuedMessages")).length()==1,"edited message queued again"));ui(()->{set(app,"queueExpanded",true);call("renderQueue",new Class[]{});});id=queue.getJSONObject(0).getString("id");click("删除 "+id);await(()->require(((JSONArray)get(app,"queuedMessages")).length()==0,"delete removes queued entry"));
        ui(()->{((EditText)get(app,"composer")).setText("立即追加说明");((Button)get(app,"sendButton")).performClick();});await(()->require(((JSONArray)get(app,"queuedMessages")).length()==1,"third message queued"));ui(()->{set(app,"queueExpanded",true);call("renderQueue",new Class[]{});});id=queue.getJSONObject(0).getString("id");click("立即发送 "+id);await(()->require(writes.stream().anyMatch(w->w.optString("operation").equals("sendnow")),"explicit immediate action sent"));await(()->require(((JSONArray)get(app,"queuedMessages")).length()==0,"immediate item leaves queue"));ui(()->app.finish());
        result.putString("stream","PASS: running send defaults to queue; queue visibility; recall and edit; delete; explicit immediate delivery.\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}}
}
