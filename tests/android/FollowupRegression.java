package io.github.abyxs.codingremote.tests;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import org.json.*;

public final class FollowupRegression extends Instrumentation {
    Activity app;Throwable failure;
    interface Check{void run()throws Exception;}
    Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    Object invoke(Class<?> type,Object target,String name,Class<?>[] types,Object...args)throws Exception{Method m=type.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);}
    void ui(Check check)throws Exception{failure=null;runOnMainSync(()->{try{check.run();}catch(Throwable t){failure=t;}});if(failure!=null)throw new Exception(failure);waitForIdleSync();}
    void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    boolean hasImage(View view){if(view instanceof ImageView&&((ImageView)view).getDrawable()!=null)return true;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(hasImage(((ViewGroup)view).getChildAt(i)))return true;return false;}
    @Override public void onCreate(Bundle args){super.onCreate(args);getTargetContext().getSharedPreferences("coding-remote",0).edit().clear().commit();getTargetContext().getSharedPreferences("ui",0).edit().putBoolean("notifications",false).putBoolean("overlay",false).putString("theme","ocean").commit();start();}
    @Override public void onStart(){Bundle result=new Bundle();Object overlay=null;try{
        Context context=getTargetContext();Class<?> trackerType=Class.forName("io.github.abyxs.codingremote.CompletionTracker");Constructor<?> constructor=trackerType.getDeclaredConstructor(String.class);constructor.setAccessible(true);Object tracker=constructor.newInstance("{}");
        JSONObject task=new JSONObject().put("id","t").put("backend","codex").put("status","idle").put("token","turn1"),snapshot=new JSONObject().put("tasks",new JSONArray().put(task));
        require(((java.util.List<?>)invoke(trackerType,tracker,"update",new Class[]{JSONObject.class},snapshot)).isEmpty(),"initial history is not notified");
        // Only a run this device actually watched can finish. A task that is idle at both
        // polls was never running here, so a turnId bump is a desktop-side edit and must
        // stay silent - otherwise a rename or a turn started elsewhere reads as a completion.
        task.put("token","turn2");require(((java.util.List<?>)invoke(trackerType,tracker,"update",new Class[]{JSONObject.class},snapshot)).isEmpty(),"idle-to-idle edit is not a completion");
        task.put("status","running");require(((java.util.List<?>)invoke(trackerType,tracker,"update",new Class[]{JSONObject.class},snapshot)).isEmpty(),"running is observed but not reported");
        task.put("token","turn3").put("status","idle");require(((java.util.List<?>)invoke(trackerType,tracker,"update",new Class[]{JSONObject.class},snapshot)).size()==1,"observed run finishing is reported");
        require(((java.util.List<?>)invoke(trackerType,tracker,"update",new Class[]{JSONObject.class},snapshot)).isEmpty(),"completion is not repeated");
        Class<?> service=Class.forName("io.github.abyxs.codingremote.TaskNotificationService");NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);require(manager.getNotificationChannel("completion_v2").getImportance()==NotificationManager.IMPORTANCE_HIGH,"heads-up channel uses high importance");
        require(java.util.Arrays.stream(manager.getActiveNotifications()).noneMatch(n->n.getId()==90),"no ongoing monitor notification");
        // Notifications are for a user who is looking somewhere else: becoming visible cancels
        // both channels, so the completion is only observable while the app is in the
        // background. This test process has no started activity yet, which is that state.
        Class<?> visibilityType=Class.forName("io.github.abyxs.codingremote.AppVisibility");
        require(!(Boolean)invoke(visibilityType,null,"visible",new Class[]{}),"no activity is on screen yet");
        invoke(service,null,"testNotification",new Class[]{Context.class},context);SystemClock.sleep(400);
        require(java.util.Arrays.stream(manager.getActiveNotifications()).anyMatch(n->n.getNotification().getChannelId().equals("completion_v2")),"completion notification posted while the app is not visible");manager.cancelAll();
        JSONObject page=new JSONObject().put("connected",true).put("session",new JSONObject().put("id","fixture").put("title","回归会话").put("status","idle").put("turnCount",7).put("completedTurnId","done").put("requests",new JSONArray()));JSONArray messages=new JSONArray();for(int i=0;i<20;i++)messages.put(new JSONObject().put("id","tool"+i).put("role","activity").put("text","执行步骤 "+i));messages.put(new JSONObject().put("id","answer").put("role","assistant").put("text","完成的文字回复"));page.put("messages",messages);
        Class<?> pageType=Class.forName("io.github.abyxs.codingremote.SessionPage");Object prepared=invoke(pageType,null,"prepare",new Class[]{JSONObject.class},page);java.util.List<?> rows=(java.util.List<?>)field(prepared,"rows");require(rows.size()==2,"twenty execution records become one foldable group plus answer");require(field(rows.get(0),"kind").equals("executionGroup"),"execution group is explicit");
        app=startActivitySync(new Intent().setClassName(context,"io.github.abyxs.codingremote.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Class<?> transport=Class.forName("io.github.abyxs.codingremote.MainActivity$Transport");android.graphics.Bitmap fixtureImage=android.graphics.Bitmap.createBitmap(24,24,android.graphics.Bitmap.Config.ARGB_8888);fixtureImage.eraseColor(0xff246d99);java.io.ByteArrayOutputStream imageBytes=new java.io.ByteArrayOutputStream();fixtureImage.compress(android.graphics.Bitmap.CompressFormat.PNG,100,imageBytes);fixtureImage.recycle();Object mock=Proxy.newProxyInstance(transport.getClassLoader(),new Class[]{transport},(o,m,args)->((String)args[2]).contains("/file?")?new JSONObject().put("mime","image/png").put("data",android.util.Base64.encodeToString(imageBytes.toByteArray(),android.util.Base64.NO_WRAP)):new JSONObject(page.toString()));
        ui(()->{set(app,"transport",mock);set(app,"origin","https://fixture.trycloudflare.com");set(app,"token","fixture");set(app,"backend","codex");invoke(app.getClass(),app,"openSession",new Class[]{String.class,String.class},"fixture","回归会话");});SystemClock.sleep(500);
        ui(()->{set(app,"paused",true);invoke(app.getClass(),app,"renderSession",new Class[]{pageType,boolean.class},prepared,false);TextView count=(TextView)field(app,"recordCount");require(count.getParent()!=null&&count.getText().toString().equals("7 轮对话"),"turn count attached and visible");EditText input=(EditText)field(app,"composer");Button send=(Button)field(app,"sendButton");require(input.getParent()==send.getParent(),"input uses area left of send");require(send.getForeground()!=null&&send.getCompoundDrawables()[0]==null,"send arrow uses centered foreground drawable");});
        // Two rows in a tall viewport sit at the top and the end at once, and the handle
        // deliberately reports "top" in that case, so this fixture could never show a real
        // bottom boundary. A transcript that overflows makes the assertion mean something.
        JSONObject tallPage=new JSONObject(page.toString());JSONArray tall=new JSONArray();
        for(int i=0;i<45;i++)tall.put(new JSONObject().put("id","pad"+i).put("role",i%2==0?"user":"assistant").put("text","消息 "+i+"\n第二行内容\n第三行内容"));
        tall.put(new JSONObject().put("id","answer").put("role","assistant").put("text","完成的文字回复"));
        tallPage.put("messages",tall);
        Object tallPrepared=invoke(pageType,null,"prepare",new Class[]{JSONObject.class},tallPage);
        // jumpToLatest re-renders the cached page, so the tall transcript has to be the one
        // the cache holds - otherwise the jump repaints the short page and the boundary this
        // assertion is about never exists.
        ui(()->{Object cache=((java.util.Map)field(app,"chatCaches")).get("codex:fixture");set(cache,"page",tallPrepared);set(app,"paused",true);invoke(app.getClass(),app,"renderSession",new Class[]{pageType,boolean.class},tallPrepared,false);});SystemClock.sleep(400);
        ui(()->{invoke(app.getClass(),app,"jumpToLatest",new Class[]{});});SystemClock.sleep(400);ui(()->{invoke(app.getClass(),app,"updateNavigation",new Class[]{});View latest=(View)field(app,"latestButton");ListView list=(ListView)field(app,"messageList");
            // A disc that cannot act is drawn dimmed rather than INVISIBLE: an INVISIBLE child
            // is skipped by the parent's hit test, which left a transparent hole and a tap
            // that went nowhere. Availability is therefore alpha, not visibility.
            require(list.getCount()>10,"tall fixture actually expanded into a long transcript, got "+list.getCount());
            require(list.getLastVisiblePosition()==list.getCount()-1,"jumpToLatest reached the last row, got "+list.getLastVisiblePosition()+" of "+list.getCount());
            require(latest.getVisibility()==View.VISIBLE,"the latest disc stays tappable at the bottom");require(latest.getAlpha()<0.5f,"latest disc reads as unavailable at the bottom, alpha was "+latest.getAlpha());});
        JSONObject imagePage=new JSONObject(page.toString());imagePage.getJSONObject("session").put("status","active");imagePage.put("messages",new JSONArray().put(new JSONObject().put("id","inline-image").put("kind","image").put("role","activity").put("text","![图片](<D:/demo/image.png>)")));Object imagePrepared=invoke(pageType,null,"prepare",new Class[]{JSONObject.class},imagePage);ui(()->{set(app,"followingLatest",true);invoke(app.getClass(),app,"renderSession",new Class[]{pageType,boolean.class},imagePrepared,false);});SystemClock.sleep(800);ui(()->require(hasImage((View)field(app,"messageList")),"image view renders decoded bitmap rather than only path"));
        JSONObject longPage=new JSONObject(page.toString());JSONArray plain=new JSONArray();for(int i=0;i<45;i++)plain.put(new JSONObject().put("id","line"+i).put("role",i%2==0?"user":"assistant").put("text","消息 "+i+"\n第二行内容\n第三行内容"));longPage.put("messages",plain);Object longPrepared=invoke(pageType,null,"prepare",new Class[]{JSONObject.class},longPage);
        ui(()->{((Handler)field(app,"handler")).removeCallbacksAndMessages(null);set(app,"followingLatest",true);invoke(app.getClass(),app,"renderSession",new Class[]{pageType,boolean.class},longPrepared,false);});ui(()->{set(app,"followingLatest",false);((ListView)field(app,"messageList")).setSelectionFromTop(10,0);});ui(()->{set(app,"paused",false);invoke(app.getClass(),app,"setHeaderHidden",new Class[]{boolean.class},true);invoke(app.getClass(),app,"scheduleHeaderReveal",new Class[]{});require((Boolean)field(app,"headerHidden"),"header hides away from boundaries");});SystemClock.sleep(2600);ui(()->require(!(Boolean)field(app,"headerHidden"),"idle restores header without a gesture"));
        ui(()->invoke(app.getClass(),app,"messageActions",new Class[]{String.class},"测试长按消息"));boolean menuVisible=false;for(int attempt=0;attempt<50;attempt++){SystemClock.sleep(100);android.view.accessibility.AccessibilityNodeInfo node=getUiAutomation().getRootInActiveWindow();if(node!=null&&!node.findAccessibilityNodeInfosByText("引用到输入框").isEmpty()){menuVisible=true;break;}}require(menuVisible,"message actions include quote");getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        Class<?> network=Class.forName("io.github.abyxs.codingremote.EmbeddedNetwork");JSONObject state=(JSONObject)invoke(network,null,"status",new Class[]{Context.class,boolean.class},context,false);require(!state.optString("state").isEmpty(),"bundled Android Tailscale node starts without installed app");JSONObject login=(JSONObject)invoke(network,null,"status",new Class[]{Context.class,boolean.class},context,true);
        // Reaching login.tailscale.com needs the bundled tailscaled to actually connect, which
        // this emulator has no binary for. What is testable offline is the contract: the node
        // reports a known state, and a login URL only ever appears alongside NeedsLogin.
        String stateName=login.optString("state"),authUrl=login.optString("authUrl");
        require(!stateName.isEmpty(),"embedded node reports a state, got "+login);
        require(authUrl.isEmpty()||authUrl.startsWith("https://login.tailscale.com/"),"a login URL is only ever a Tailscale one, got "+authUrl);
        require(!"Running".equals(stateName)||authUrl.isEmpty(),"a running node offers no login URL");
        Field process=network.getDeclaredField("process");process.setAccessible(true);java.lang.Process spawned=(java.lang.Process)process.get(null);if(spawned!=null)spawned.destroy();process.set(null,null);
        Class<?> overlayType=Class.forName("io.github.abyxs.codingremote.StatusOverlay");Constructor<?> overlayConstructor=overlayType.getDeclaredConstructor(Context.class);overlayConstructor.setAccessible(true);overlay=overlayConstructor.newInstance(context);Object bubble=overlay;context.getSharedPreferences("ui",0).edit().putBoolean("overlay",true).commit();
        // The overlay refuses to paint over a foreground app, and app is already started by
        // this point, so it has to go to the background before the pet can exist.
        ui(()->app.moveTaskToBack(true));
        for(int i=0;i<30&&(Boolean)invoke(visibilityType,null,"visible",new Class[]{});i++)SystemClock.sleep(100);
        invoke(overlayType,bubble,"update",new Class[]{JSONObject.class},snapshot);SystemClock.sleep(250);
        for(int i=0;i<20&&field(bubble,"panel")==null;i++)SystemClock.sleep(100);
        ui(()->{LinearLayout panel=(LinearLayout)field(bubble,"panel");require(panel!=null,"overlay visible with permission");View pet=panel.getChildAt(panel.getChildCount()-1);require(pet instanceof ImageView,"overlay is a cut-out pet");
            // A tap is affection, not a command: only a hold of 380ms or more opens the bubble,
            // so an accidental brush cannot throw a window over whatever is underneath.
            long t=SystemClock.uptimeMillis();pet.dispatchTouchEvent(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,10,10,0));SystemClock.sleep(500);pet.dispatchTouchEvent(MotionEvent.obtain(t,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,10,10,0));
            require((Boolean)field(bubble,"bubbleOpen"),"holding the pet opens the message bubble");invoke(overlayType,bubble,"closeBubbleNow",new Class[]{});require(!(Boolean)field(bubble,"bubbleOpen"),"the bubble dismisses");});
        invoke(overlayType,bubble,"close",new Class[]{});context.getSharedPreferences("ui",0).edit().putBoolean("overlay",false).commit();ui(()->app.finish());
        result.putString("stream","PASS: completion catch-up and deduplication; high-priority posted notification with no ongoing card; execution group folding; fixed turn count; composer uses send-left space; centered icon; boundary hiding; message menu; embedded Android network startup; overlay pet tap-to-dialog and dismiss.\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){if(overlay!=null)try{invoke(overlay.getClass(),overlay,"close",new Class[]{});}catch(Exception ignored){}getTargetContext().getSharedPreferences("ui",0).edit().putBoolean("overlay",false).commit();String networkLog="";try{networkLog=new String(java.nio.file.Files.readAllBytes(new java.io.File(getTargetContext().getFilesDir(),"tailnet/network.log").toPath()),"UTF-8").replaceAll("https?://[^\\s]+","[URL]");if(networkLog.length()>3500)networkLog=networkLog.substring(networkLog.length()-3500);}catch(Exception ignored){}result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e)+"\n"+networkLog);result.putString("stack",android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}}
}
