package io.github.abyxs.codingremote;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;

/**
 * A draggable desktop pet that floats as a cut-out: there is no bubble or box
 * behind it, and it never prints the task status. What is happening is shown only
 * through the pet's motion; a new message makes it hop and pop a heart.
 *
 * Tapping the pet makes a speech bubble grow from its mouth with the message
 * details. The bubble is part of the pet's window, so it travels with the pet and
 * the desktop underneath is never covered.
 */
final class StatusOverlay {
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final WindowManager manager;
    private final NativeUi ui;
    private LinearLayout panel;
    private WindowManager.LayoutParams params;
    /** The touch catcher that dismisses the bubble when anything outside it is tapped. */
    private View scrim;
    private boolean useScrim;
    private int anchorX,anchorY;
    private final MessageChangeTracker messageChanges=new MessageChangeTracker();
    private final AttentionTracker attention=new AttentionTracker("");
    private boolean primed;
    private long lastNudge=-4000;
    private String snapshotKey="";
    private JSONObject snapshot=new JSONObject();
    private String connection="";
    /** Why the window is not on screen right now, or "" when it is. */
    private String blockReason="";
    static volatile String lastBlockReason="尚未收到电脑状态";
    static volatile boolean hosted;
    /** The overlay the running service created, so the settings page can force a frame. */
    private static volatile StatusOverlay live;
    /** Set while a manual prime is in flight: see render()'s foreground gate. */
    private boolean forced;
    private int background=0xffeaf1e9,foreground=0xff27634d;
    private boolean closed;
    private final Runnable visibilityChanged=()->{if(!closed)render();};
    /** The desktop pet: one sprite per render, hosted by the collapsed cut-out image. */
    private PetSprite pet;
    private View petHost;
    private Runnable petFrame;
    private long petStarted,petHopAt=-5000,petNoticeAt=-5000,petGreetAt=-5000,idleSince=SystemClock.uptimeMillis();
    // Touch tracking for the pet. pressAt times the hold so a long press can be told
    // from a tap; petDown keeps the stroke alive between frames; petStartleAt lets the
    // startle decay on the animation clock rather than blocking the touch thread.
    private long pressAt,lastTapAt,petStartleAt=-5000;
    private boolean petDown;
    private int petCombos;
    private Runnable holdFrame;
    private String petForm="linepup";
    /**
     * The bubble lives inside the pet's own window, not in a second overlay.
     *
     * Tapping used to cover the desktop with a scrim and a centred card. That hid
     * what the user was working on in order to report a status the pet is already
     * on top of, and it detached the message from the thing it was about. The
     * bubble grows out of the pet's mouth instead, and the pet's window widens to
     * hold it.
     */
    private FrameLayout bubbleLayer;
    private SpeechBubble bubbleBack;
    private LinearLayout bubbleList;
    private boolean bubbleOpen;
    /** Which side the bubble extends to: away from the screen edge the pet sits on. */
    private int bubbleSide=SpeechBubble.TAIL_LEFT;
    private int bubbleWidth,bubbleHeight;

    StatusOverlay(Context c){
        // `context` is the context the caller handed in and is kept as-is: it is the one
        // used to start activities, and a caller may legitimately wrap it (to intercept
        // startActivity, to scope preferences, or to pin a service). Replacing it with
        // the application context silently dropped those overrides.
        context=c;
        // The WindowManager, by contrast, must come from the process-wide application
        // context. The overlay is a system window that outlives any Activity, and binding
        // it to an Activity's WindowManager is what made addView throw once that Activity
        // went away - the pet appeared for a moment and then the next render closed the app.
        Context app=c==null?null:c.getApplicationContext();
        manager=(WindowManager)(app==null?c:app).getSystemService(Context.WINDOW_SERVICE);
        ui=new NativeUi(c);
        live=this;
        main.post(()->{if(!closed)AppVisibility.add(visibilityChanged);});
    }

    /**
     * Draws the pet now, without waiting for the computer to report anything.
     *
     * The only two things that used to call render() were a status payload and a
     * disconnect, and the status poll returns early when no token has been paired -
     * before it ever reaches the overlay. So a user who had switched the overlay on
     * and granted the permission, on a device that had not paired yet, saw nothing at
     * all and no reason: the pet was waiting for a message that could never arrive.
     * The pet is the feature; the status is what it reports. It now comes up on an
     * empty status and fills in when the first poll lands.
     */
    /** Binds the monitor so a pet can exist, without forcing a draw. Used when the app
     *  returns to the foreground or has just been switched on: the monitor has to be
     *  running for the pet to appear at all, but whether it should draw right now is
     *  still up to the ordinary gates. */
    static void ensureRunning(Context context){
        StatusOverlay current=live;
        if(current!=null&&!current.closed)return;
        live=null;
        lastBlockReason="正在启动监控服务…";
        try{MonitorConnection.start(context.getApplicationContext());}
        catch(Throwable failure){lastBlockReason="无法启动监控服务："+failure.getClass().getSimpleName();}
    }
    /** The manual "show it now" path: binds the monitor and forces one draw even though
     *  the app asking for it is necessarily in the foreground. */
    static void prime(Context context){
        StatusOverlay current=live;
        if(current==null||current.closed){
            // No overlay exists yet, or the one that existed has been closed with its
            // service. Both cases need the monitor bound; the difference is that a closed
            // instance must not be reused, because its window is gone and its panel and
            // params refer to a WindowManager that no longer accepts either.
            live=null;
            lastBlockReason="正在启动监控服务…";
            try{MonitorConnection.start(context.getApplicationContext());}
            catch(Throwable failure){lastBlockReason="无法启动监控服务："+failure.getClass().getSimpleName();}
            return;
        }
        // Posted, not called directly: render() builds and adds a window, and the caller
        // may be a settings click on the UI thread but could also be the polling worker.
        // update() posts for the same reason. The body is wrapped because a throw on the
        // main thread here would close the app the moment the button is pressed.
        current.main.post(()->{
            try{
                if(current.closed)return;
                // The settings dialog this button lives in is still up when the post runs,
                // so the Activity is still foreground and the ordinary gate would refuse
                // the draw the user just asked for. Forcing covers that; the flag is
                // cleared in renderNow's finally.
                current.renderNow(true);
            }
            catch(Throwable failure){
                String message=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
                lastBlockReason="显示悬浮窗失败："+message;
                // A window that failed to build must not be left half-attached, or the
                // next attempt reuses a panel the system already rejected.
                try{current.remove();}catch(Throwable ignored){}
            }
        });
    }
    /** A one-off draw that ignores the front-of-app gate, for the manual check button. */
    private void renderNow(boolean force){
        // try/finally, not a straight pair of assignments: render() catches its own
        // throw now, but a failure inside it must still not leave `forced` set, or the
        // foreground gate stays disabled for every later poll.
        forced=force;
        try{render();}
        finally{forced=false;}
    }

    private SharedPreferences prefs(){return context.getSharedPreferences("ui",0);}
    private android.util.DisplayMetrics metrics(){return context.getResources().getDisplayMetrics();}
    private static int withAlpha(int color,int alpha){return (color&0x00ffffff)|(alpha<<24);}

    void update(JSONObject status){main.post(()->{
        boolean changed=messageChanges.update(status);
        java.util.ArrayList<JSONObject> fresh=new java.util.ArrayList<>();
        try{fresh=attention.fresh(status);}catch(Exception ignored){}
        if(closed)return;
        String key=status.toString();
        // The computer caches this payload, so half the polls repeat the last one.
        if(key.equals(snapshotKey)&&connection.isEmpty()&&panel!=null){snapshot=status;return;}
        snapshotKey=key;snapshot=status;connection="";
        boolean wasPrimed=primed;primed=true;
        render();
        if(bubbleOpen)populateBubble();
        // First sync, repeat polls and pure status changes stay quiet; a genuinely new
        // reply or a new approval/question makes the pet react.
        if(wasPrimed&&(!fresh.isEmpty()||changed))nudgePet();
    });}
    void disconnected(){main.post(()->{connection="连接中断";snapshotKey="";render();if(bubbleOpen)populateBubble();});}

    private static boolean hasProgress(JSONObject task){
        String value=task.optString("progress","");
        return !value.isEmpty()&&!value.equals("正在思考…")&&!value.equals("等待你的回答或确认");
    }
    /** The active tasks, with counts[0..2]=questions/approvals/other pending requests. */
    private java.util.ArrayList<JSONObject> collectActive(int[] counts){
        java.util.ArrayList<JSONObject> active=new java.util.ArrayList<>();
        counts[0]=counts[1]=counts[2]=0;
        JSONArray tasks=snapshot.optJSONArray("tasks");
        if(connection.isEmpty()&&tasks!=null)for(int i=0;i<tasks.length();i++){
            JSONObject task=tasks.optJSONObject(i);
            if(task==null||!AttentionTracker.available(snapshot,task.optString("backend")))continue;
            JSONArray pending=AttentionTracker.pending(task);
            for(int n=0;n<pending.length();n++){JSONObject request=pending.optJSONObject(n);if(request==null)continue;String kind=request.optString("kind");if(kind.equals("question"))counts[0]++;else if(kind.equals("approval"))counts[1]++;else counts[2]++;}
            if(pending.length()>0||java.util.Arrays.asList("active","running","working","inProgress").contains(task.optString("status")))active.add(task);
        }
        active.sort((a,b)->Boolean.compare(AttentionTracker.pending(b).length()>0,AttentionTracker.pending(a).length()>0));
        return active;
    }
    /** The label that drives the pet's mood; it is never shown to the user. */
    private String statusFor(java.util.ArrayList<JSONObject> active,int[] counts){
        if(!connection.isEmpty())return "离线";
        if(counts[0]+counts[1]+counts[2]>0)return "待处理";
        if(active.isEmpty())return "就绪";
        return hasProgress(active.get(0))?"执行中":"思考中";
    }
    private String petState(String statusLabel){
        if(!connection.isEmpty())return "offline";
        if(statusLabel.contains("待"))return "waiting";
        if(statusLabel.equals("执行中"))return "working";
        if(statusLabel.equals("思考中"))return "thinking";
        // "就绪": rest, and doze off once nothing has happened for a while.
        return SystemClock.uptimeMillis()-idleSince>90000?"sleep":"idle";
    }
    private void dress(PetSprite sprite,String statusLabel){
        if(sprite==null)return;
        String state=petState(statusLabel);
        if(!state.equals("idle")&&!state.equals("sleep"))idleSince=SystemClock.uptimeMillis();
        if(!state.equals(sprite.state()))petHopAt=SystemClock.uptimeMillis();
        sprite.state(state);
    }
    /** Called when the pet is first hosted or its form changes: a small hello. */
    private void greetPet(){petGreetAt=SystemClock.uptimeMillis();startPet();}

    /**
     * Frame loop for breathing, blinking, the hop on a change and the new-message
     * heart. It runs only while a sprite is hosted on screen, and slows when idle.
     */
    private final Runnable petLoop=new Runnable(){@Override public void run(){
        petFrame=null;
        if(closed||panel==null||pet==null||petHost==null||AppVisibility.visible())return;
        String state=pet.state();
        long now=SystemClock.uptimeMillis(),elapsed=now-petStarted;
        long period=state.equals("working")?1200:state.equals("thinking")?2000:state.equals("waiting")?1400:state.equals("sleep")?4600:3000;
        float blink=0f,position=(elapsed%3400)/3400f;
        if(position<.05f)blink=1f-Math.abs(position-.025f)/.025f;
        float hop=0f;long hd=now-petHopAt;if(hd<520){float t=hd/520f;hop=(1f-t)*(1f-t);}
        float notice=0f;long nd=now-petNoticeAt;if(nd<1500)notice=1f-nd/1500f;
        // Greeting: a pair of notes for the first second and a half after the pet
        // appears or its form is changed, so the switch is visible.
        float greet=0f;long gd=now-petGreetAt;if(gd<1500)greet=1f-gd/1500f;
        // Idle affection: while nothing else is happening the pet drifts a small
        // heart up every few seconds. Keyed off elapsed time rather than an event so
        // it stays in step with the breathing loop.
        float idleLove=0f;
        if(!state.equals("working")&&!state.equals("thinking")&&!state.equals("sleep")){
            long il=elapsed%5200;
            if(il<1200)idleLove=1f-il/1200f;
        }
        // The touch layer. stroke is held for as long as the finger is down; startle
        // decays on this clock once a long press has passed its threshold; the poke
        // count is the tap combo, which the sprite reads to size its star burst.
        float startle=0f;
        if(petStartleAt>=0&&now-petStartleAt<900)startle=1f-(now-petStartleAt)/900f;
        pet.advance((elapsed%period)/(float)period,blink,hop,notice,greet,idleLove,
                    petDown?1f:0f,startle,now-lastTapAt<420,petCombos);
        petHost.invalidate();
        // A tap needs the next frame to land soon; the slow idle cadence is too coarse
        // for a reaction that is over in a quarter of a second.
        boolean urgent=petDown||startle>0||now-lastTapAt<420;
        petFrame=this;main.postDelayed(this,urgent?50:state.equals("working")?50:80);
    }};
    private void startPet(){if(petFrame!=null)return;petStarted=SystemClock.uptimeMillis();petFrame=petLoop;main.post(petLoop);}
    private void stopPet(){if(petFrame!=null)main.removeCallbacks(petFrame);petFrame=null;}
    /** The pet's reaction to something new: a hop and a popping heart. */
    private void nudgePet(){
        long now=SystemClock.uptimeMillis();
        if(now-lastNudge<1500)return;
        lastNudge=now;petNoticeAt=now;petHopAt=now;
        startPet();
    }

    private void colors(){
        String theme=prefs().getString("theme","light");
        // The overlay lives in its own process and cannot rely on an Activity having
        // already applied the theme, so apply it here and read the palette back.
        // This used to be a second, hand-copied table of six themes; it drifted from
        // NativeUi every time the palette changed, and the floating pet kept the old
        // colours - unreadable against a dark canvas once the theme was retuned.
        if(theme.equals("warm"))theme="green";
        NativeUi.theme(theme);
        background=NativeUi.TINT;foreground=NativeUi.GREEN;
    }

    /**
     * Draws the pet, and cannot take the app down with it.
     *
     * This runs on the main thread, so anything it throws ends the process. It is also
     * the one path a user can trigger by hand - the settings page's "show it now" button
     * - which makes an uncaught throw there look exactly like "pressing the button
     * crashes the app". Every failure is now recorded as the block reason and surfaced
     * in the check dialog, so a bad sprite, a rejected layout or a revoked permission
     * all report themselves instead of closing the window.
     */
    private void render(){
        try{renderUnchecked();}
        catch(Throwable failure){
            panel=null;
            String message=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
            blockReason="绘制悬浮窗失败："+message;
            lastBlockReason=blockReason;
            try{remove();}catch(Throwable ignored){}
        }
    }
    private void renderUnchecked(){
        // The four conditions below are what keeps the pet off screen, and until now
        // each of them returned silently: a user who had toggled the overlay on, granted
        // the permission and still saw nothing had no way to find out which one failed.
        // The reason is recorded so the settings page can report it.
        String blocked=null;
        if(closed)blocked="悬浮窗已关闭";
        // `forced` is set only by the settings check button. Without it the front-of-app
        // gate below would refuse the very draw the user just asked for, and the button
        // labelled "show it now" would do nothing while the app it was pressed in is on
        // screen - which is the state it is always pressed in.
        else if(AppVisibility.visible()&&!forced)blocked="App 正在前台，切到其他应用后显示";
        else if(!prefs().getBoolean("overlay",false))blocked="「显示实时任务悬浮窗」未开启";
        else if(!Settings.canDrawOverlays(context))blocked="缺少「显示在其他应用上层」权限";
        if(blocked!=null){blockReason=blocked;remove();return;}
        blockReason="";
        colors();
        int[] counts=new int[3];
        java.util.ArrayList<JSONObject> active=collectActive(counts);
        String statusLabel=statusFor(active,counts);
        // migrate, not a raw read: an installed device may still hold the name of a
        // pet that has since been removed from the roster.
        petForm=PetSprite.migrate(prefs().getString("pet",null));
        // A panel that the system has already detached - permission revoked, the window
        // removed behind our back, or a service restart - must not be reused. Its parent
        // is gone, so removeAllViews()/addView() on it leave a view tree that throws on
        // the next layout, which is the crash seen when forcing the overlay back on.
        if(panel!=null&&panel.getParent()==null&&panel.getWindowToken()==null){
            try{manager.removeView(panel);}catch(Throwable ignored){}
            panel=null;params=null;hosted=false;
        }
        if(panel==null){
            panel=new LinearLayout(context);panel.setOrientation(0);panel.setGravity(Gravity.CENTER_VERTICAL);panel.setElevation(NativeUi.LIFT_FLAT);
            params=new WindowManager.LayoutParams(ui.dp(72),ui.dp(72),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
            params.gravity=Gravity.TOP|Gravity.LEFT;
            anchorX=prefs().getInt("overlayX",ui.dp(8));anchorY=prefs().getInt("overlayY",ui.dp(160));params.x=anchorX;params.y=anchorY;
            // Adding the window is the one step that throws outright when the overlay
            // permission is missing or was revoked while the app was running. It used to
            // propagate out of render() and abort the whole method, so the pet never
            // appeared and nothing said why. Now the panel is released and the reason is
            // kept for the settings page to show.
            try{manager.addView(panel,params);}
            catch(Exception failure){panel=null;blockReason="无法创建悬浮窗："+(failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage());lastBlockReason=blockReason;return;}
        }
        // A cut-out: the window carries no surface, only the pet and its bubble.
        panel.setBackground(null);
        panel.removeAllViews();pet=null;petHost=null;
        boolean collapsed=petForm.equals("none");
        int petSize=ui.dp(64),chipSize=ui.dp(54);

        // The bubble sits on the side of the pet that has room for it, so it opens
        // towards the middle of the screen instead of off the edge.
        boolean petOnLeft=(params.x+ui.dp(72)/2)<metrics().widthPixels/2;
        bubbleSide=petOnLeft?SpeechBubble.TAIL_RIGHT:SpeechBubble.TAIL_LEFT;

        bubbleWidth=Math.min(ui.dp(260),metrics().widthPixels-ui.dp(96));
        bubbleHeight=ui.dp(132);

        if(collapsed){
            // "简洁": a small neutral handle so the overlay is still reachable.
            ImageView chip=new ImageView(context);
            GradientDrawable shape=new GradientDrawable();shape.setShape(GradientDrawable.OVAL);shape.setColor(background);shape.setStroke(ui.dp(1),withAlpha(foreground,0x40));
            chip.setBackground(shape);chip.setScaleType(ImageView.ScaleType.CENTER);chip.setImageDrawable(new NativeUi.Symbol("chat",foreground,ui.dp(24)));
            chip.setContentDescription("实时任务悬浮窗");
            petHost=chip;attachTouch(chip);stopPet();
            if(bubbleOpen)panel.addView(buildBubble(),bubbleParams(true));
            panel.addView(chip,new LinearLayout.LayoutParams(chipSize,chipSize));
            layout(collapsed?chipSize:petSize,Math.max(chipSize,petSize));
            return;
        }

        ImageView host=new ImageView(context);
        pet=new PetSprite(petForm,foreground,background,ui.dp(64));host.setImageDrawable(pet);host.setScaleType(ImageView.ScaleType.FIT_CENTER);
        dress(pet,statusLabel);
        host.setContentDescription("实时任务悬浮窗");
        petHost=host;attachTouch(host);
        // greetPet rather than startPet: the notes only make sense on the frame the
        // animal first appears.
        greetPet();
        if(bubbleOpen)panel.addView(buildBubble(),bubbleParams(true));
        panel.addView(host,new LinearLayout.LayoutParams(petSize,petSize));
        layout(petSize,Math.max(petSize,bubbleHeight));
    }

    /** Places the bubble on the correct side of the pet inside the panel. */
    private LinearLayout.LayoutParams bubbleParams(boolean leading){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(bubbleWidth,bubbleHeight);
        // The layer itself is the bubble plus the room its tail needs.
        lp.setMargins(leading?0:ui.dp(6),0,leading?ui.dp(6):0,0);
        return lp;
    }

    /** Sizes the window to the pet plus, when open, the bubble beside it. */
    private void layout(int petSize,int rowHeight){
        if(panel==null||params==null)return;
        int width=bubbleOpen?petSize+bubbleWidth+ui.dp(6):petSize;
        int height=Math.max(petSize,bubbleOpen?rowHeight:petSize);
        int maxX=Math.max(0,metrics().widthPixels-width);
        // The panel is laid out pet-first, so with the bubble on the left the window
        // has to start a bubble-width earlier to keep the pet where the user left it.
        int left=anchorX;
        if(bubbleOpen&&bubbleSide==SpeechBubble.TAIL_LEFT)left=anchorX-bubbleWidth-ui.dp(6);
        left=Math.max(0,Math.min(left,maxX));
        int top=Math.max(0,Math.min(anchorY,Math.max(0,metrics().heightPixels-height)));
        params.width=width;params.height=height;params.x=left;params.y=top;
        // Swallowing this exception was the one failure with no trace at all: the pet
        // would simply never appear and the log said nothing. If the window cannot be
        // laid out, that is now the reported reason.
        try{manager.updateViewLayout(panel,params);blockReason="";hosted=true;lastBlockReason="";}
        catch(Exception failure){blockReason="窗口布局失败："+failure.getClass().getSimpleName();hosted=false;lastBlockReason=blockReason;}
    }

    private void attachTouch(final View host){
        host.setOnTouchListener(new View.OnTouchListener(){float x,y;int baseX,baseY;boolean dragged;
            public boolean onTouch(View v,MotionEvent e){
                switch(e.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:
                        x=e.getRawX();y=e.getRawY();baseX=params.x;baseY=params.y;dragged=false;
                        pressAt=SystemClock.uptimeMillis();
                        // The pet answers the finger immediately. Waiting for the tap to
                        // resolve would make the first touch feel like nothing happened.
                        petDown=true;
                        if(pet!=null)pet.stroke(1f);
                        holdPet();
                        brief();
                        return true;
                    case MotionEvent.ACTION_MOVE:{
                        float dx=e.getRawX()-x,dy=e.getRawY()-y;
                        if(Math.hypot(dx,dy)>ui.dp(8))dragged=true;
                        if(dragged){
                            // Dragging is not petting: drop the touch reaction so the pet
                            // does not keep wriggling while it is being moved.
                            if(petDown&&pet!=null){petDown=false;pet.release();}
                            params.x=Math.max(0,Math.min(metrics().widthPixels-panel.getWidth(),baseX+(int)dx));
                            params.y=Math.max(0,Math.min(metrics().heightPixels-panel.getHeight(),baseY+(int)dy));
                            try{manager.updateViewLayout(panel,params);}catch(Exception ignored){}
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        long held=SystemClock.uptimeMillis()-pressAt;
                        boolean wasDown=petDown;
                        petDown=false;
                        if(pet!=null)pet.release();
                        stopHold();
                        if(dragged){
                            anchorX=params.x;anchorY=params.y;
                            prefs().edit().putInt("overlayX",anchorX).putInt("overlayY",anchorY).apply();
                            return true;
                        }
                        if(held>=380){
                            // A long press is an instruction, not a pet: answer it with the
                            // bubble near the pet and let the startle die down on its own.
                            petStartleAt=SystemClock.uptimeMillis();
                            openBubble();
                        }else if(wasDown){
                            // A tap is affection. It pokes the pet, and only opens the
                            // bubble if the pet is not the thing being tapped - otherwise
                            // every accidental brush would throw a window over the screen.
                            if(pet!=null)pet.tap();
                            lastTapAt=SystemClock.uptimeMillis();
                            int combo=pet!=null?pet.combo():0;
                            if(combo>=4){
                                // Four taps in a row was meant as "open it".
                                petCombos=0;
                                openBubble();
                            }else{
                                petCombos++;
                                petHopAt=SystemClock.uptimeMillis();
                            }
                        }
                        return true;
                }
                return true;
            }
        });
    }

    /** Keeps the stroke alive for as long as the finger is down. */
    private void holdPet(){
        if(holdFrame!=null)return;
        holdFrame=new Runnable(){@Override public void run(){
            if(!petDown||closed||pet==null){holdFrame=null;return;}
            long now=SystemClock.uptimeMillis();
            long down=now-pressAt;
            // Past the long-press threshold the pet stops enjoying it.
            if(down>=380)pet.hold(Math.min(1f,(down-380)/420f));
            else pet.stroke(1f);
            pet.invalidateSelf();
            main.postDelayed(this,60);
        }};
        main.post(holdFrame);
    }

    private void stopHold(){
        if(holdFrame!=null)main.removeCallbacks(holdFrame);
        holdFrame=null;
    }

    /** One extra frame soon, so a tap does not wait for the next slow tick. */
    private void brief(){startPet();}

    // --- Speech bubble ------------------------------------------------------
    /**
     * The bubble, built as a layer: the drawn bubble is the background and the list
     * of messages sits on top of it with room left for the tail.
     */
    private FrameLayout buildBubble(){
        FrameLayout layer=new FrameLayout(context);
        bubbleLayer=layer;
        bubbleBack=new SpeechBubble(metrics().density,ui.dp(14),ui.dp(13),ui.dp(11));
        bubbleBack.tail(bubbleSide);
        bubbleBack.colors(background,withAlpha(foreground,0x33),withAlpha(foreground,0x1a));
        layer.setBackground(bubbleBack);

        // The tail is cut out of the side facing the pet, so the content is inset on that
        // side instead of along the whole bottom edge.
        int inset=ui.dp(11);
        int tailGap=ui.dp(9);
        int insetLeft=bubbleSide==SpeechBubble.TAIL_LEFT?inset+tailGap:inset;
        int insetRight=bubbleSide==SpeechBubble.TAIL_RIGHT?inset+tailGap:inset;
        bubbleList=new LinearLayout(context);bubbleList.setOrientation(1);
        NativeUi.MenuScroll scroller=new NativeUi.MenuScroll(context,320);
        scroller.addView(bubbleList);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-1);
        lp.setMargins(insetLeft,inset,insetRight,inset);
        scroller.setPadding(0,0,0,0);
        // The bubble must not eat drags meant for the pet, so it swallows its own.
        scroller.setClickable(true);
        layer.addView(scroller,lp);
        populateBubble();
        layer.setAlpha(0f);layer.setScaleX(.9f);layer.setScaleY(.9f);
        layer.setPivotX(bubbleSide==SpeechBubble.TAIL_LEFT?0:bubbleWidth);
        layer.setPivotY(bubbleHeight);
        layer.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180)
            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        return layer;
    }

    private void populateBubble(){
        if(bubbleList==null)return;
        bubbleList.removeAllViews();
        if(!connection.isEmpty()){bubbleList.addView(bubbleNote(connection));return;}
        int[] counts=new int[3];
        java.util.ArrayList<JSONObject> active=collectActive(counts);
        if(active.isEmpty()){
            bubbleList.addView(bubbleNote(counts[0]+counts[1]+counts[2]>0?"有待处理的请求，点开进入会话":"正在待命，暂时没有任务"));
            return;
        }
        for(JSONObject task:active){
            LinearLayout item=new LinearLayout(context);item.setOrientation(1);item.setPadding(ui.dp(8),ui.dp(8),ui.dp(8),ui.dp(8));
            String backend=task.optString("backend"),name=backend.equals("claude")?"Claude":backend.equals("deepseek")?"DeepSeek":"Codex";
            JSONArray pending=AttentionTracker.pending(task);JSONObject first=pending.optJSONObject(0);
            java.util.LinkedHashSet<String> states=new java.util.LinkedHashSet<>();
            for(int i=0;i<pending.length();i++){JSONObject request=pending.optJSONObject(i);if(request!=null)states.add(request.optString("label","待处理"));}
            String state=first==null?"执行中":android.text.TextUtils.join(" · ",states);
            TextView head=ui.text(name+" · "+state,NativeUi.CAPTION,foreground);
            NativeUi.strong(head);
            head.setSingleLine();head.setEllipsize(android.text.TextUtils.TruncateAt.END);
            ui.leading(head,backend.equals("deepseek")?"spark":"code",foreground,NativeUi.GLYPH_INLINE);
            item.addView(head);
            item.setBackground(ui.touch(first!=null?withAlpha(foreground,0x1f):android.graphics.Color.TRANSPARENT,NativeUi.CORNER_ROW,first!=null));
            String raw=first==null?task.optString("progress","正在思考…"):first.optString("preview","点击处理");
            String line=prose(raw);
            // "正在思考…" rather than an empty row: a task with nothing readable left
            // after filtering is still running, and a blank line looks like a bug.
            if(line.isEmpty())line=first==null?"正在思考…":"点开查看详情";
            // One step under the body size: the bubble is a glance, not a transcript, and
            // at body size three lines of preview filled the whole speech bubble.
            TextView progress=ui.text(line,NativeUi.LABEL,foreground);
            progress.setMaxLines(3);progress.setEllipsize(android.text.TextUtils.TruncateAt.END);progress.setPadding(0,ui.dp(4),0,0);item.addView(progress);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,ui.dp(2),0,ui.dp(2));bubbleList.addView(item,lp);
            item.setContentDescription((first==null?"打开运行会话 ":"打开待处理会话 ")+task.optString("id"));
            item.setOnClickListener(v->{context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("backend",backend).putExtra("sessionId",task.optString("id")).putExtra("title",task.optString("title","会话")));closeBubble();});
        }
    }
    private TextView bubbleNote(String message){
        // A note is already curated, so the filter is only there to strip an
        // embedded command; falling back to the original keeps the bubble from
        // rendering blank when the whole note happens to look machine-generated.
        String text=prose(message);
        if(text.isEmpty())text=message;
        TextView note=ui.text(text,NativeUi.LABEL,foreground);
        note.setPadding(ui.dp(4),ui.dp(10),ui.dp(4),ui.dp(6));
        return note;
    }

    /**
     * Keeps only the prose of a reply, dropping commands, paths, diffs and code.
     *
     * The desktop already reduces the progress line before sending it, so this is
     * the second line of defence: a cached payload written by an older build, a
     * Codex rollout read straight off disk, or any future source that forgets the
     * rule would otherwise put a shell transcript in a speech bubble. The bubble is
     * a glanceable summary on the home screen, which is the worst possible place
     * for one.
     */
    static String prose(String raw){
        if(raw==null||raw.isEmpty())return "";
        StringBuilder kept=new StringBuilder();
        boolean fenced=false;
        for(String line:raw.split("\n",-1)){
            String trimmed=line.trim();
            if(trimmed.startsWith("```")||trimmed.startsWith("~~~")){fenced=!fenced;continue;}
            if(fenced)continue;
            if(trimmed.isEmpty()){if(kept.length()>0&&kept.charAt(kept.length()-1)!='\n')kept.append('\n');continue;}
            if(startsAny(trimmed,"[工具调用]","[Tool","工具调用","调用工具","$ ","> ","diff ","--- ","+++ "))continue;
            if(trimmed.length()>1&&(trimmed.charAt(0)=='+'||trimmed.charAt(0)=='-')
                &&(trimmed.charAt(1)=='+'||trimmed.charAt(1)=='-'||trimmed.charAt(1)==' '))continue;
            if(isPath(trimmed))continue;
            // A status label is not content. "正在执行：执行结果" and its cousins are pure
            // ceremony - they carry Chinese, so the CJK test below let them through and the
            // bubble ended up printing a stage name instead of what the task actually said.
            if(isStatusLabel(trimmed))continue;
            // No CJK means the line is code, an identifier or a command.
            if(!hasChinese(trimmed))continue;
            kept.append(trimmed).append('\n');
        }
        int end=kept.length();
        while(end>0&&kept.charAt(end-1)=='\n')end--;
        if(end==0)return "";
        String text=kept.substring(0,end);
        return text.length()>240?text.substring(text.length()-240):text;
    }
    private static boolean startsAny(String value,String... prefixes){
        for(String prefix:prefixes)if(value.startsWith(prefix))return true;
        return false;
    }
    private static boolean isPath(String line){
        if(line.startsWith("\\\\")||line.startsWith("~/")||line.startsWith("../")||line.startsWith("./"))return true;
        if(line.startsWith("/"))return true;
        return line.length()>2&&Character.isLetter(line.charAt(0))&&line.charAt(1)==':'
            &&(line.charAt(2)=='\\'||line.charAt(2)=='/');
    }
    /** Stage and status labels the desktop emits around the real message.
     *
     * These are all Chinese, so the "does it contain CJK" gate that strips code cannot
     * see them, and the bubble printed things like "正在执行：执行结果" where a sentence
     * about the task belongs. A label is recognised by its own vocabulary, not by being
     * short: the whole line has to be stage wording, optionally followed by another stage
     * word after a colon or dash, so a real sentence that merely opens with 正在执行 or
     * mentions 执行结果 still survives. */
    private static final String[] STAGE_WORDS={
        "正在执行","执行结果","执行中","执行过程","执行步骤","正在思考","思考中","思考过程",
        "正在生成","生成结果","生成中","正在调用","调用工具","工具调用","正在读取","读取结果",
        "正在写入","写入结果","正在搜索","搜索结果","正在分析","分析结果","任务状态","当前状态",
        "状态更新","进行中","已完成","已取消","已失败","等待中","准备中","初始化"};
    private static boolean isStatusLabel(String line){
        // Split on the punctuation that joins a stage name to its "结果" tail.
        String[] parts=line.split("[：:，,、·\\-—>》]+");
        int words=0,meaningful=0;
        for(String part:parts){
            String piece=part.trim();
            if(piece.isEmpty())continue;
            meaningful++;
            boolean stage=false;
            for(String word:STAGE_WORDS)if(piece.equals(word)){stage=true;break;}
            if(!stage&&piece.length()<=6)for(String word:STAGE_WORDS)if(piece.endsWith(word)){stage=true;break;}
            if(stage)words++;
        }
        // Every fragment is stage wording: there is no statement in this line.
        return meaningful>0&&words==meaningful;
    }
    private static boolean hasChinese(String line){
        for(int i=0;i<line.length();i++){
            char c=line.charAt(i);
            if(c>=0x4e00&&c<=0x9fff)return true;
        }
        return false;
    }
    /** Opens or closes the bubble without rebuilding the whole overlay. */
    private void toggleBubble(){if(bubbleOpen)closeBubble();else openBubble();}
    private void openBubble(){
        if(bubbleOpen)return;
        bubbleOpen=true;
        useScrim=true;
        render();
        showScrim();
    }
    private void closeBubble(){
        if(!bubbleOpen)return;
        // A tap outside is an ordinary dismissal, not a reason to stop showing the pet.
        useScrim=false;hideScrim();
        bubbleOpen=false;
        bubbleLayer=null;bubbleBack=null;bubbleList=null;
        render();
    }
    private void closeBubbleNow(){useScrim=false;hideScrim();bubbleLayer=null;bubbleBack=null;bubbleList=null;bubbleOpen=false;}

    /** A full-width, fully transparent catcher under the bubble.
     *
     * The bubble window is FLAG_NOT_TOUCH_MODAL, so a touch beside it goes to whatever is
     * underneath and never reaches us - which is why tapping elsewhere did nothing and the
     * bubble could only be dismissed by finding the pet again. This second window sits
     * below the pet's and above everything else for exactly as long as the bubble is open,
     * so any touch outside dismisses it. It draws nothing: the point is the touch, not the
     * view, and a visible scrim would be the full-screen overlay this design removed.
     *
     * The pet's window is re-added after the catcher so it stays on top: overlay windows
     * stack in the order they were added, and a catcher added last would have swallowed
     * the drags meant for the pet. */
    private void showScrim(){
        if(!useScrim||scrim!=null||closed)return;
        View catcher=new View(context);
        catcher.setBackgroundColor(Color.TRANSPARENT);
        catcher.setOnTouchListener((v,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN)closeBubble();
            return true;
        });
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(-1,-1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.LEFT;
        boolean raisePet=panel!=null&&params!=null;
        try{
            if(raisePet)manager.removeViewImmediate(panel);
            manager.addView(catcher,lp);
            scrim=catcher;
            if(raisePet)manager.addView(panel,params);
        }catch(Exception failure){
            // Any failure here leaves the pet's window detached, so put it back before
            // giving up: the bubble stays open until the pet is tapped again, which is the
            // old behaviour rather than a missing pet.
            try{if(raisePet&&panel.getParent()==null)manager.addView(panel,params);}catch(Exception ignored){}
            if(scrim!=null){try{manager.removeView(scrim);}catch(Exception ignored){}scrim=null;}
            useScrim=false;
        }
    }
    private void hideScrim(){
        if(scrim==null)return;
        try{manager.removeViewImmediate(scrim);}catch(Exception ignored){}
        scrim=null;
    }

    private void remove(){stopPet();pet=null;petHost=null;snapshotKey="";closeBubbleNow();if(panel!=null){try{manager.removeView(panel);}catch(Exception ignored){}panel=null;}hosted=false;lastBlockReason=blockReason.isEmpty()?"尚未收到电脑状态":blockReason;}
    void close(){main.post(()->{closed=true;AppVisibility.remove(visibilityChanged);remove();
        // The instance is finished: clearing the static reference is what stops the
        // settings button from posting a render against a service that has already been
        // torn down, which used to throw on the main thread and close the app.
        if(live==this)live=null;});}
}
