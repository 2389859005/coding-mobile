package io.github.abyxs.codingremote;
import android.content.*;
import android.graphics.PixelFormat;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;

/** Non-modal progress bubble. Single tap expands; double tap confirms closing. */
final class StatusOverlay {
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final WindowManager manager;
    private final NativeUi ui;
    private LinearLayout panel;
    private WindowManager.LayoutParams params;
    private boolean expanded,confirmClose;
    private int anchorX,anchorY,renderedMode=-1;
    private android.animation.ValueAnimator resize,pulse;
    private android.animation.ValueAnimator messagePulse;
    private final MessageChangeTracker messageChanges=new MessageChangeTracker();
    private long lastMessageAnimation=-8000;
    private Runnable pendingMessageAnimation;
    private Runnable pendingTap;
    private JSONObject snapshot=new JSONObject();
    private String connection="";
    private int background=0xffeaf1e9,foreground=0xff27634d;
    private boolean closed;
    private final Runnable visibilityChanged=()->{if(!closed)render();};
    StatusOverlay(Context c){context=c;manager=(WindowManager)c.getSystemService(Context.WINDOW_SERVICE);ui=new NativeUi(c);main.post(()->{if(!closed)AppVisibility.add(visibilityChanged);});}
    void update(JSONObject status){main.post(()->{boolean changed=messageChanges.update(status);if(closed)return;snapshot=status;connection="";render();if(changed&&panel!=null)animateMessageChange();});}
    void disconnected(){main.post(()->{connection="连接中断";stopMessageAnimation();stopAlert();render();});}
    private void animateMessageChange(){
        if(panel==null||!connection.isEmpty()||confirmClose)return;
        long remaining=8000-(SystemClock.uptimeMillis()-lastMessageAnimation);
        if(remaining>0){if(pendingMessageAnimation==null){pendingMessageAnimation=()->{pendingMessageAnimation=null;animateMessageChange();};main.postDelayed(pendingMessageAnimation,remaining);}return;}
        lastMessageAnimation=SystemClock.uptimeMillis();
        if(messagePulse!=null)messagePulse.cancel();
        messagePulse=android.animation.ValueAnimator.ofFloat(0f,1f);messagePulse.setDuration(2400);messagePulse.setInterpolator(new android.view.animation.LinearInterpolator());
        FlowBorderDrawable border=new FlowBorderDrawable(background,ui.dp(expanded?18:24),ui.dp(3));panel.setBackground(border);
        messagePulse.addUpdateListener(a->{if(panel==null)return;float progress=(Float)a.getAnimatedValue();border.phase(progress);});
        messagePulse.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator animation){if(messagePulse==animation){messagePulse=null;restoreMessageSurface();}}});messagePulse.start();
    }
    private void restoreMessageSurface(){if(panel!=null){panel.setScaleX(1f);panel.setScaleY(1f);panel.setBackground(ui.surface(background,expanded?18:24,false));}}
    private void stopMessageAnimation(){if(pendingMessageAnimation!=null)main.removeCallbacks(pendingMessageAnimation);pendingMessageAnimation=null;if(messagePulse!=null){messagePulse.cancel();messagePulse=null;}restoreMessageSurface();}
    private void stopAlert(){if(pulse!=null){pulse.cancel();pulse=null;}if(panel!=null)panel.setAlpha(1);}
    private void colors(){
        String theme=context.getSharedPreferences("ui",0).getString("theme","light");
        background=0xffeaf1e9;foreground=0xff27634d;
        if(theme.equals("ocean")){background=0xffdeedf7;foreground=0xff246d99;}
        if(theme.equals("lavender")){background=0xffe8dcf2;foreground=0xff765198;}
        if(theme.equals("sand")){background=0xffeedfc8;foreground=0xff896338;}
        if(theme.equals("midnight")){background=0xff253d59;foreground=0xffd2e4fc;}
    }
    private void collapse(){cancelTap();expanded=false;confirmClose=false;render();}
    private void cancelTap(){if(pendingTap!=null)main.removeCallbacks(pendingTap);pendingTap=null;}
    private void tapped(){
        if(pendingTap!=null){cancelTap();expanded=false;confirmClose=true;render();return;}
        pendingTap=()->{pendingTap=null;expanded=!expanded;confirmClose=false;render();};
        main.postDelayed(pendingTap,ViewConfiguration.getDoubleTapTimeout());
    }
    private void render(){
        if(closed||AppVisibility.visible()||!context.getSharedPreferences("ui",0).getBoolean("overlay",false)||!Settings.canDrawOverlays(context)){remove();return;}
        colors();
        if(panel==null){
            panel=new LinearLayout(context);panel.setOrientation(1);panel.setElevation(0);panel.setClipToOutline(true);
            params=new WindowManager.LayoutParams(ui.dp(72),-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,PixelFormat.TRANSLUCENT);
            params.gravity=Gravity.TOP|Gravity.LEFT;
            anchorX=context.getSharedPreferences("ui",0).getInt("overlayX",ui.dp(8));anchorY=context.getSharedPreferences("ui",0).getInt("overlayY",ui.dp(160));params.x=anchorX;params.y=anchorY;
            manager.addView(panel,params);
            panel.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_OUTSIDE&&(expanded||confirmClose)){collapse();return true;}return false;});
        }
        panel.removeAllViews();panel.setPadding(ui.dp(8),ui.dp(6),ui.dp(8),ui.dp(6));if(messagePulse==null)panel.setBackground(ui.surface(background,expanded?18:24,false));
        JSONArray tasks=snapshot.optJSONArray("tasks");java.util.ArrayList<JSONObject> active=new java.util.ArrayList<>();
        int questions=0,approvals=0,other=0;
        if(connection.isEmpty()&&tasks!=null)for(int i=0;i<tasks.length();i++){JSONObject task=tasks.optJSONObject(i);if(task==null||!AttentionTracker.available(snapshot,task.optString("backend")))continue;JSONArray pending=AttentionTracker.pending(task);for(int n=0;n<pending.length();n++){JSONObject request=pending.optJSONObject(n);if(request==null)continue;if(request.optString("kind").equals("question"))questions++;else if(request.optString("kind").equals("approval"))approvals++;else other++;}if(pending.length()>0||java.util.Arrays.asList("active","running","working","inProgress").contains(task.optString("status")))active.add(task);}
        active.sort((a,b)->Boolean.compare(AttentionTracker.pending(b).length()>0,AttentionTracker.pending(a).length()>0));
        boolean waiting=questions+approvals+other>0;if(waiting){background=0xffffebcc;foreground=0xff8a4800;panel.setBackground(ui.surface(background,expanded?18:24,false));}else stopAlert();
        String separator=expanded?" · ":"\n";
        String attentionLabel=(approvals>0?"待审批":"")+(approvals>0&&questions>0?separator:"")+(questions>0?"待回答":"")+(other>0?(questions+approvals>0?separator:"")+"待处理":"");
        int statusLines=expanded?1:(questions>0?1:0)+(approvals>0?1:0)+(other>0?1:0);
        if(confirmClose){
            LinearLayout choices=new LinearLayout(context);choices.setBaselineAligned(false);panel.addView(choices);
            Button close=ui.iconButton("check","确认关闭悬浮窗"),cancel=ui.iconButton("close","取消关闭悬浮窗");
            for(Button button:new Button[]{close,cancel}){button.setMinHeight(0);button.setMinimumHeight(0);button.setBackground(ui.touch(android.graphics.Color.TRANSPARENT,20,false));choices.addView(button,new LinearLayout.LayoutParams(ui.dp(40),ui.dp(36)));}
            ui.centerIcon(close,"check",foreground,22);ui.centerIcon(cancel,"close",foreground,22);
            close.setOnClickListener(v->{context.getSharedPreferences("ui",0).edit().putBoolean("overlay",false).apply();remove();MonitorConnection.start(context);});
            cancel.setOnClickListener(v->collapse());
        }else{
            String statusLabel=connection.isEmpty()?(waiting?attentionLabel:active.isEmpty()?"就绪":"执行中"):"离线";
            if(!expanded){TextView label=ui.text(statusLabel,13,foreground);label.setGravity(Gravity.CENTER);panel.addView(label,new LinearLayout.LayoutParams(-1,ui.dp(Math.max(36,statusLines*20))));label.setContentDescription("实时任务悬浮窗 · "+statusLabel);
            label.setOnTouchListener(new View.OnTouchListener(){float x,y;int baseX,baseY;boolean dragged;public boolean onTouch(View v,MotionEvent e){
                if(e.getActionMasked()==MotionEvent.ACTION_DOWN){x=e.getRawX();y=e.getRawY();baseX=params.x;baseY=params.y;dragged=false;return true;}
                if(e.getActionMasked()==MotionEvent.ACTION_MOVE){float dx=e.getRawX()-x,dy=e.getRawY()-y;if(Math.hypot(dx,dy)>ui.dp(8))dragged=true;if(dragged){cancelTap();if(resize!=null)resize.cancel();params.x=Math.max(0,Math.min(context.getResources().getDisplayMetrics().widthPixels-panel.getWidth(),baseX+(int)dx));params.y=Math.max(0,Math.min(context.getResources().getDisplayMetrics().heightPixels-panel.getHeight(),baseY+(int)dy));manager.updateViewLayout(panel,params);}return true;}
                if(e.getActionMasked()==MotionEvent.ACTION_UP){if(!dragged)tapped();else{anchorX=params.x;anchorY=params.y;context.getSharedPreferences("ui",0).edit().putInt("overlayX",anchorX).putInt("overlayY",anchorY).apply();}return true;}
                if(e.getActionMasked()==MotionEvent.ACTION_CANCEL)cancelTap();return true;
            }});
            }
            if(expanded){
                if(!connection.isEmpty())panel.addView(ui.text(connection,13,foreground));
                else if(active.isEmpty())panel.addView(ui.text("当前没有正在执行的任务",13,foreground));
                LinearLayout list=new LinearLayout(context);list.setOrientation(1);ScrollView scroller=new ScrollView(context);scroller.addView(list);panel.addView(scroller,new LinearLayout.LayoutParams(-1,Math.min(ui.dp(340),Math.max(ui.dp(80),active.size()*ui.dp(108)))));
                for(JSONObject task:active){
                    LinearLayout item=new LinearLayout(context);item.setOrientation(1);item.setPadding(ui.dp(6),ui.dp(8),ui.dp(6),ui.dp(8));item.setBackground(ui.touch(android.graphics.Color.TRANSPARENT,12,false));
                    String backend=task.optString("backend"),name=backend.equals("claude")?"Claude":backend.equals("deepseek")?"DeepSeek":"Codex";
                    JSONArray pending=AttentionTracker.pending(task);JSONObject first=pending.optJSONObject(0);java.util.LinkedHashSet<String> states=new java.util.LinkedHashSet<>();for(int i=0;i<pending.length();i++){JSONObject request=pending.optJSONObject(i);if(request!=null)states.add(request.optString("label","待处理"));}String state=first==null?"执行中":android.text.TextUtils.join(" · ",states);
                    TextView title=ui.text(name+" · "+state+" · "+task.optString("title","会话"),12,foreground);title.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);title.setSingleLine();title.setEllipsize(android.text.TextUtils.TruncateAt.END);item.addView(title);TextView progress=ui.text(first==null?task.optString("progress","正在思考…"):first.optString("preview","点击处理"),14,foreground);progress.setMaxLines(4);progress.setEllipsize(android.text.TextUtils.TruncateAt.END);progress.setPadding(0,ui.dp(5),0,0);item.addView(progress);list.addView(item);
                    item.setContentDescription((first==null?"打开运行会话 ":"打开待处理会话 ")+task.optString("id"));item.setOnClickListener(v->{context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("backend",backend).putExtra("sessionId",task.optString("id")).putExtra("title",task.optString("title","会话")));collapse();});
                }

            }
        }
        int mode=confirmClose?2:expanded?1:0,width=ui.dp(confirmClose?96:expanded?288:72),left=Math.min(anchorX,Math.max(0,context.getResources().getDisplayMetrics().widthPixels-width));
        if(resize!=null)resize.cancel();
        if(renderedMode!=mode){renderedMode=mode;int startWidth=params.width,startX=params.x;resize=android.animation.ValueAnimator.ofFloat(0,1);resize.setDuration(180);resize.setInterpolator(new android.view.animation.DecelerateInterpolator());resize.addUpdateListener(a->{if(panel==null)return;float fraction=(Float)a.getAnimatedValue();params.width=Math.round(startWidth+(width-startWidth)*fraction);params.x=Math.round(startX+(left-startX)*fraction);params.y=anchorY;manager.updateViewLayout(panel,params);});resize.start();panel.setAlpha(.72f);panel.animate().alpha(1).setDuration(160).start();}
        else{params.width=width;params.x=left;params.y=anchorY;manager.updateViewLayout(panel,params);}
    }
    private void remove(){cancelTap();stopMessageAnimation();stopAlert();if(resize!=null)resize.cancel();if(panel!=null){manager.removeView(panel);panel=null;}renderedMode=-1;}
    void close(){main.post(()->{closed=true;AppVisibility.remove(visibilityChanged);remove();});}
}
