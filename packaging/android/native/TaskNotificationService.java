package io.github.abyxs.codingremote;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/** Foreground, authenticated task monitoring; no external push service or token relay. */
public final class TaskNotificationService extends Service {
    private ScheduledExecutorService worker;private StatusOverlay overlay;
    @Override public void onCreate(){super.onCreate();((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(90);overlay=new StatusOverlay(this);worker=Executors.newSingleThreadScheduledExecutor();worker.scheduleWithFixedDelay(()->checkOnce(this,overlay),0,4,TimeUnit.SECONDS);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_NOT_STICKY;}
    private static int icon(Context c){return c.getResources().getIdentifier("notification_task","drawable",c.getPackageName());}
    static synchronized void checkOnce(Context context,StatusOverlay overlay){
        boolean notifications=context.getSharedPreferences("ui",0).getBoolean("notifications",false);if(!notifications&&!context.getSharedPreferences("ui",0).getBoolean("overlay",false))return;
        try {JSONObject auth=ConnectionProfiles.read(context);if(!auth.has("token"))return;
            JSONObject status=ConnectionProfiles.request(context,(origin,token,path,body)->readStatus(context,origin,token,path),auth.getString("origin"),auth.getString("token"),"/api/native/status",null);
            String key="active:"+auth.optString("computerId",auth.optString("origin"));CompletionTracker tracker=new CompletionTracker(context.getSharedPreferences("task-monitor",0).getString(key,"{}"));
            java.util.ArrayList<JSONObject> completed=tracker.update(status);context.getSharedPreferences("task-monitor",0).edit().putString(key,tracker.save()).commit();if(notifications)for(JSONObject task:completed)notifyTask(context,task);if(overlay!=null)overlay.update(new JSONObject(status.toString()).put("attentionScope",key));
        }catch(Exception e){if(overlay!=null)overlay.disconnected();}
    }
    private static JSONObject readStatus(Context context,String origin,String token,String path)throws Exception {
        HttpURLConnection c=EmbeddedNetwork.open(context,origin+path);c.setConnectTimeout(7000);c.setReadTimeout(25000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Origin",origin);
        try {int code=c.getResponseCode();if(code!=200)throw new ConnectionProfiles.HttpFailure(code,"通知连接不可用");ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>4*1024*1024)throw new IOException("status too large");out.write(b,0,n);}}return new JSONObject(out.toString("UTF-8"));}finally{c.disconnect();}
    }
    static void testNotification(Context c)throws Exception{notifyTask(c,new JSONObject().put("id","notification-test").put("backend","codex").put("title","测试任务已完成").put("status","completed"));}
    private static void notifyTask(Context context,JSONObject task){new Handler(Looper.getMainLooper()).post(()->{if(!AppVisibility.visible())postTask(context,task);});}
    private static void postTask(Context context,JSONObject task){NotificationManager manager=(NotificationManager)context.getSystemService(NOTIFICATION_SERVICE);NotificationChannel channel=new NotificationChannel("completion_v2","任务完成提醒",NotificationManager.IMPORTANCE_HIGH);channel.enableVibration(true);manager.createNotificationChannel(channel);String id=task.optString("id"),backend=task.optString("backend"),title=task.optString("title","任务");int notificationId=(backend+id).hashCode()&0x7fffffff;if(notificationId==90)notificationId=91;Intent open=new Intent(context,MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("sessionId",id).putExtra("backend",backend).putExtra("title",title);PendingIntent tap=PendingIntent.getActivity(context,notificationId,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);String state=task.optString("status");boolean error=task.optBoolean("error")||state.equals("failed")||state.equals("error");manager.notify(notificationId,new Notification.Builder(context,"completion_v2").setSmallIcon(icon(context)).setCategory(Notification.CATEGORY_MESSAGE).setDefaults(Notification.DEFAULT_ALL).setVisibility(Notification.VISIBILITY_PRIVATE).setContentTitle(error?"任务结束，需要查看":state.equals("interrupted")?"任务已停止":"任务已完成").setContentText(title).setStyle(new Notification.BigTextStyle().bigText(title+"\n"+(backend.equals("codex")?"Codex":backend.equals("deepseek")?"DeepSeek":"Claude")+" · 点击查看结果")).setContentIntent(tap).setAutoCancel(true).build());}
    @Override public void onDestroy(){if(worker!=null)worker.shutdownNow();if(overlay!=null)overlay.close();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return new Binder();}
}
