package io.github.abyxs.codingremote;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;

/** Non-blocking progress for slow control-server handshakes and browser login. */
final class NetworkDialog {
    static void show(Activity activity,NativeUi ui){
        LinearLayout body=new LinearLayout(activity);body.setOrientation(1);body.setPadding(ui.dp(24),ui.dp(12),ui.dp(24),ui.dp(8));TextView state=ui.text("正在读取设备网络状态…",NativeUi.TITLE,NativeUi.INK),detail=ui.text("连接较慢时会持续重试，可先关闭此窗口。",NativeUi.LABEL,NativeUi.MUTED);body.addView(state);detail.setPadding(0,ui.dp(12),0,ui.dp(16));body.addView(detail);ProgressBar progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(NativeUi.GREEN));body.addView(progress,new LinearLayout.LayoutParams(-1,ui.dp(8)));Button login=ui.button("在浏览器继续登录",true);login.setVisibility(android.view.View.GONE);body.addView(login);
        AlertDialog dialog=new NativeDialogBuilder(activity,ui).setTitle("设备网络").setView(body).setPositiveButton("关闭",null).show();Handler main=new Handler(Looper.getMainLooper());ExecutorService worker=Executors.newSingleThreadExecutor();java.util.HashSet<String> opened=new java.util.HashSet<>();activity.getSharedPreferences("ui",0).edit().putBoolean("embeddedNetwork",true).apply();
        Runnable poll=new Runnable(){public void run(){if(!dialog.isShowing()||activity.isDestroyed())return;worker.execute(()->{JSONObject result;try{result=EmbeddedNetwork.status(activity,true);}catch(Exception e){result=new JSONObject();}final JSONObject network=result;main.post(()->{if(!dialog.isShowing()||activity.isDestroyed())return;state.setText(EmbeddedNetwork.describe(network));String url=EmbeddedNetwork.loginUrl(network);boolean ready=network.optString("state").equals("Running");progress.setVisibility(ready?android.view.View.GONE:android.view.View.VISIBLE);detail.setText(ready?"设备网络已就绪，已保存的电脑连接会自动重试。":network.optString("state").equals("NoState")?"正在等待网络服务响应，连接较慢时会继续重试。请保持互联网可用。":network.length()==0?"暂时无法读取网络状态，正在重试。":"登录后会自动刷新连接状态。");login.setVisibility(url.isEmpty()?android.view.View.GONE:android.view.View.VISIBLE);login.setOnClickListener(v->open(activity,url));if(!url.isEmpty()&&AppVisibility.visible()&&opened.add(url))open(activity,url);main.postDelayed(this,2500);});});}};
        dialog.setOnDismissListener(d->{main.removeCallbacksAndMessages(null);worker.shutdownNow();});main.post(poll);
    }
    private static void open(Activity activity,String url){try{activity.startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)));}catch(ActivityNotFoundException e){Toast.makeText(activity,"请安装浏览器后重试登录",Toast.LENGTH_LONG).show();}}
}
