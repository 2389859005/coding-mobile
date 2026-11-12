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
    private int background=NativeUi.WHITE,foreground=NativeUi.INK;
    private boolean closed;
    private int imeBottom;
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
    private boolean menuOpen;
    private String petForm="maltese";
    /** Most recent meaningful transition, retained briefly for important-only mode. */
    private String petEvent=PetStateMachine.NONE;
    private long petEventAt=-5000;
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

    private final Runnable dismissBubble=()->{if(!closed&&!menuOpen)closeBubble();};
    private final Runnable policyTick=new Runnable(){public void run(){if(closed)return;render();main.postDelayed(this,30000);}};
    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesChanged=(prefs,key)->{if(key!=null&&key.startsWith("overlay"))main.post(()->{if(!closed)render();});};
    private final BroadcastReceiver environmentChanged=new BroadcastReceiver(){public void onReceive(Context c,Intent i){main.post(()->{if(!closed)render();});}};
    private Rect safeBounds(){
        Rect bounds=new Rect(0,0,metrics().widthPixels,metrics().heightPixels);
        if(Build.VERSION.SDK_INT>=30)try{WindowMetrics window=manager.getCurrentWindowMetrics();bounds=new Rect(window.getBounds());android.graphics.Insets insets=window.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());bounds.inset(insets.left,insets.top,insets.right,Math.max(insets.bottom,imeBottom));}catch(Exception ignored){}
        return bounds;
    }
    private boolean reducedMotion(){PowerManager power=(PowerManager)context.getSystemService(Context.POWER_SERVICE);BatteryManager battery=(BatteryManager)context.getSystemService(Context.BATTERY_SERVICE);return StatusSpine.reduced(context)||power!=null&&(!power.isInteractive()||power.isPowerSaveMode())||battery!=null&&battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)<15;}
    StatusOverlay(Context c){
        // `context` is the context the caller handed in and is kept as-is: it is the one
        // used to start activities, and a caller may legitimately wrap it (to intercept
        // startActivity, to scope preferences, or to pin a service). Replacing it with
        // the application context silently dropped those overrides.
        context=c;
        prefs().registerOnSharedPreferenceChangeListener(preferencesChanged);
        IntentFilter changes=new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);changes.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);changes.addAction(Intent.ACTION_SCREEN_ON);changes.addAction(Intent.ACTION_SCREEN_OFF);
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(environmentChanged,changes,Context.RECEIVER_NOT_EXPORTED);else context.registerReceiver(environmentChanged,changes);
        main.postDelayed(policyTick,30000);

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
        JSONObject previous=snapshot;
        snapshotKey=key;snapshot=status;connection="";
        boolean wasPrimed=primed;primed=true;
        String event=wasPrimed?PetStateMachine.reduce(previous,status,fresh,changed):PetStateMachine.NONE;
        if(!event.isEmpty())markPetEvent(event);
        render();
        if(bubbleOpen)populateBubble();
        // First sync, repeat polls and pure status changes stay quiet; a genuinely new
        // reply or a new approval/question makes the pet react.
        if(wasPrimed&&(!fresh.isEmpty()||changed||!event.isEmpty()))nudgePet(event);
    });}
    void disconnected(){main.post(()->{connection="连接中断";markPetEvent("offline");snapshotKey="";render();if(bubbleOpen)populateBubble();nudgePet("offline");});}

    private void markPetEvent(String event){
        if(event==null||event.isEmpty())return;
        if(PetStateMachine.priority(event)>=PetStateMachine.priority(petEvent)
            ||SystemClock.uptimeMillis()-petEventAt>4200){
            petEvent=event;petEventAt=SystemClock.uptimeMillis();
            main.postDelayed(()->{if(!closed)render();},5400);
        }
    }

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
        if(PetStateMachine.ERROR.equals(petEvent)&&SystemClock.uptimeMillis()-petEventAt<4200)return "error";
        if(PetStateMachine.COMPLETED.equals(petEvent)&&SystemClock.uptimeMillis()-petEventAt<3200)return "completed";
        if(statusLabel.contains("待"))return "waiting";
        if(statusLabel.equals("执行中"))return "working";
        if(statusLabel.equals("思考中"))return "thinking";
        // "就绪": rest, and doze off once nothing has happened for a while.
        return SystemClock.uptimeMillis()-idleSince>60000?"sleep":"idle";
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
        if(closed||panel==null||pet==null||petHost==null||(AppVisibility.visible()&&!prefs().getBoolean("overlayForeground",false)))return;
        int[] pendingCounts=new int[3];dress(pet,statusFor(collectActive(pendingCounts),pendingCounts));
        String state=pet.state();
        long now=SystemClock.uptimeMillis(),elapsed=(now-petStarted)*new OverlayPolicy(prefs()).speed/100;
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
        petFrame=this;main.postDelayed(this,reducedMotion()?250:urgent?50:state.equals("working")?50:state.equals("offline")?500:120);
    }};
    private void startPet(){if(petFrame!=null)return;petStarted=SystemClock.uptimeMillis();petFrame=petLoop;main.post(petLoop);}
    private void stopPet(){if(petFrame!=null)main.removeCallbacks(petFrame);petFrame=null;}
    /** The pet's reaction to something new: a hop and a popping heart. */
    private void nudgePet(){nudgePet(PetStateMachine.MESSAGE);}
    private void nudgePet(String event){
        long now=SystemClock.uptimeMillis();
        if(now-lastNudge<1500)return;
        lastNudge=now;petNoticeAt=now;petHopAt=now;
        if(event!=null&&!event.isEmpty()){markPetEvent(event);if(pet!=null)pet.event(petEvent);}
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
        NativeUi.theme(context);
        background=NativeUi.WHITE;foreground=NativeUi.INK;
    }

    private String displayMode(){
        String value=prefs().getString("overlayMode","full");
        return value.equals("important")||value.equals("badge")||value.equals("quiet")?value:"full";
    }

    private boolean importantNow(int[] counts){
        if(bubbleOpen)return true;
        if(!connection.isEmpty())return true;
        if(counts!=null&&counts[0]+counts[1]+counts[2]>0)return true;
        if(petEvent.isEmpty())return false;
        long age=SystemClock.uptimeMillis()-petEventAt;
        return age<5200&&PetStateMachine.priority(petEvent)>=PetStateMachine.priority(PetStateMachine.COMPLETED);
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
        else if(AppVisibility.visible()&&!forced&&!prefs().getBoolean("overlayForeground",false))blocked="App 正在前台，切到其他应用后显示";
        else if(!prefs().getBoolean("overlay",false))blocked="「显示任务悬浮窗」未开启";
        else if(prefs().getLong("overlayHiddenUntil",0)>System.currentTimeMillis())blocked="悬浮窗暂时隐藏，到点后自动恢复";
        else if(!Settings.canDrawOverlays(context))blocked="缺少「显示在其他应用上层」权限";
        else if(displayMode().equals("quiet"))blocked="悬浮窗处于静默模式";
        else if(new OverlayPolicy(prefs()).quiet)blocked="当前为勿扰时段";
        if(blocked!=null){blockReason=blocked;remove();return;}
        blockReason="";
        colors();
        int[] counts=new int[3];
        java.util.ArrayList<JSONObject> active=collectActive(counts);
        String statusLabel=statusFor(active,counts);
        String mode=displayMode();
        if(mode.equals("important")&&!importantNow(counts)){
            blockReason="仅重要事件模式：当前没有待处理事项";remove();return;
        }
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
            params.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
            if(Build.VERSION.SDK_INT>=30)panel.setOnApplyWindowInsetsListener((view,insets)->{int bottom=insets.getInsets(WindowInsets.Type.ime()).bottom;if(bottom!=imeBottom){imeBottom=bottom;main.post(()->{if(!closed&&panel!=null&&params!=null)layout(ui.dp(petForm.equals("none")||displayMode().equals("badge")?54:64),params.height);});}return insets;});
            anchorX=prefs().getInt("overlayX",ui.dp(8));anchorY=prefs().getInt("overlayY",ui.dp(160));params.x=anchorX;params.y=anchorY;
            // Adding the window is the one step that throws outright when the overlay
            // permission is missing or was revoked while the app was running. It used to
            // propagate out of render() and abort the whole method, so the pet never
            // appeared and nothing said why. Now the panel is released and the reason is
            // kept for the settings page to show.
            try{manager.addView(panel,params);}
            catch(Exception failure){panel=null;blockReason="无法创建悬浮窗："+(failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage());lastBlockReason=blockReason;return;}
        }
        // A cut-out: this window carries only the pet.
        panel.setBackground(null);
        PetSprite previousPet=pet;panel.removeAllViews();pet=null;petHost=null;
        boolean collapsed=petForm.equals("none")||mode.equals("badge");
        int petSize=ui.dp(64),chipSize=ui.dp(54);

        // The bubble sits on the side of the pet that has room for it, so it opens
        // towards the middle of the screen instead of off the edge.
        // Keep the chosen side while the bubble is open, including content updates.
        Rect safe=safeBounds();int size=collapsed?chipSize:petSize;
        if(!bubbleOpen)bubbleSide=anchorX+size/2<safe.centerX()?SpeechBubble.TAIL_LEFT:SpeechBubble.TAIL_RIGHT;
        int available=bubbleSide==SpeechBubble.TAIL_LEFT?safe.right-anchorX-size-ui.dp(6):anchorX-safe.left-ui.dp(6);
        bubbleWidth=Math.max(ui.dp(80),Math.min(ui.dp(new OverlayPolicy(prefs()).width),available));
        bubbleHeight=ui.dp(menuOpen?276:196);

        if(collapsed){
            // "简洁": a small neutral handle so the overlay is still reachable.
            ImageView chip=new ImageView(context);
            GradientDrawable shape=new GradientDrawable();shape.setShape(GradientDrawable.OVAL);int pendingCount=counts[0]+counts[1]+counts[2];shape.setColor(pendingCount>0?withAlpha(0xffffa62b,0x28):background);shape.setStroke(ui.dp(1),withAlpha(foreground,0x40));
            chip.setBackground(mode.equals("badge")?new InsetDrawable(shape,ui.dp(10)):shape);chip.setScaleType(ImageView.ScaleType.CENTER);chip.setImageDrawable(new NativeUi.Symbol("chat",foreground,ui.dp(mode.equals("badge")?16:24)));
            chip.setContentDescription("任务悬浮窗");
            petHost=chip;attachTouch(chip);stopPet();

            panel.addView(chip,new LinearLayout.LayoutParams(chipSize,chipSize));

            layout(chipSize,chipSize);showBubbleWindow(chipSize);
            return;
        }

        ImageView host=new ImageView(context);
        boolean reused=previousPet!=null&&previousPet.form().equals(petForm);pet=reused?previousPet:new PetSprite(petForm,foreground,background,ui.dp(64));host.setImageDrawable(pet);host.setScaleType(ImageView.ScaleType.FIT_CENTER);
        dress(pet,statusLabel);
        if(!reused&&!petEvent.isEmpty()&&SystemClock.uptimeMillis()-petEventAt<5000)pet.event(petEvent);
        int pendingCount=counts[0]+counts[1]+counts[2];
        host.setContentDescription("任务悬浮窗");
        petHost=host;attachTouch(host);
        // greetPet rather than startPet: the notes only make sense on the frame the
        // animal first appears.
        if(reused)startPet();else greetPet();

        panel.addView(host,new LinearLayout.LayoutParams(petSize,petSize));

        layout(petSize,petSize);showBubbleWindow(petSize);
    }

    // The pet keeps its own fixed-size window. Resizing a left-opening window
    // makes Android animate the window origin across the screen on dismissal.
    private void removeBubbleWindow(){
        if(bubbleLayer!=null){try{manager.removeViewImmediate(bubbleLayer);}catch(Exception ignored){}bubbleLayer=null;}
    }
    private void showBubbleWindow(int petSize){
        removeBubbleWindow();if(!bubbleOpen)return;
        View view=buildBubble();Rect safe=safeBounds();
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(bubbleWidth,bubbleHeight,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.LEFT;lp.windowAnimations=0;
        lp.x=bubbleSide==SpeechBubble.TAIL_LEFT?params.x+petSize+ui.dp(6):params.x-bubbleWidth-ui.dp(6);
        lp.x=Math.max(safe.left,Math.min(lp.x,safe.right-bubbleWidth));
        lp.y=Math.max(safe.top,Math.min(params.y-(bubbleHeight-petSize)/2,safe.bottom-bubbleHeight));
        try{manager.addView(view,lp);}catch(Exception failure){removeBubbleWindow();}
    }

    /** Keep the pet window at a constant size and screen anchor. */
    private void layout(int petSize,int rowHeight){
        if(panel==null||params==null)return;
        int width=petSize;
        int height=petSize;
        Rect safe=safeBounds();int maxX=Math.max(safe.left,safe.right-width);
        // Bubble placement never changes this window origin.
        int left=anchorX;

        left=Math.max(safe.left,Math.min(left,maxX));
        int top=Math.max(safe.top,Math.min(anchorY-(height-petSize)/2,Math.max(safe.top,safe.bottom-height)));
        params.width=width;params.height=height;params.x=left;params.y=top;
        // Swallowing this exception was the one failure with no trace at all: the pet
        // would simply never appear and the log said nothing. If the window cannot be
        // laid out, that is now the reported reason.
        try{manager.updateViewLayout(panel,params);blockReason="";hosted=true;lastBlockReason="";}
        catch(Exception failure){blockReason="窗口布局失败："+failure.getClass().getSimpleName();hosted=false;lastBlockReason=blockReason;}
    }

    /** Stick the pet to the nearest screen edge after a drag. */
    private void snapToEdge(){
        if(panel==null||params==null)return;
        if(!new OverlayPolicy(prefs()).snap){anchorX=params.x;anchorY=params.y;prefs().edit().putInt("overlayX",anchorX).putInt("overlayY",anchorY).apply();return;}
        int width=metrics().widthPixels, target=params.x+panel.getWidth()/2<width/2?ui.dp(8):Math.max(ui.dp(8),width-panel.getWidth()-ui.dp(8));
        params.x=Math.max(0,Math.min(Math.max(0,width-panel.getWidth()),target));
        anchorX=params.x;anchorY=params.y;
        try{manager.updateViewLayout(panel,params);}catch(Exception ignored){}
        prefs().edit().putInt("overlayX",anchorX).putInt("overlayY",anchorY).apply();
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
                        if(e.getActionMasked()==MotionEvent.ACTION_CANCEL)return true;
                        if(dragged){
                            anchorX=params.x;anchorY=params.y;
                            if(bubbleOpen)closeBubble();
                            snapToEdge();
                            return true;
                        }
                        if(held>=520){
                            // Long press is a visible shortcut menu. The old hidden
                            // four-tap gesture was impossible to discover and collided
                            // with ordinary petting.
                            petStartleAt=SystemClock.uptimeMillis();
                            showPetMenu();
                        }else if(wasDown){
                            if(pet!=null)pet.tap();
                            long now=SystemClock.uptimeMillis();
                            boolean doubleTap=lastTapAt>0&&now-lastTapAt<360;
                            lastTapAt=now;petCombos=doubleTap?Math.min(5,petCombos+1):1;
                            if(doubleTap)openCurrentSession();
                            else toggleBubble();
                        }
                        return true;
                }
                return true;
            }
        });
    }

    private void showPetMenu(){
        if(closed)return;
        // Keep the menu in the same anchored bubble as task details. A dialog creates
        // a second window, steals focus, and feels disconnected from the pet.
        if(menuOpen)return;
        menuOpen=true;
        if(bubbleOpen){render();showScrim();}else openBubble();
    }

    private void openCurrentSession(){
        int[] counts=new int[3];java.util.ArrayList<JSONObject> active=collectActive(counts);
        JSONObject task=active.isEmpty()?null:active.get(0);
        try{
            Intent intent=new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if(task!=null)intent.putExtra("backend",task.optString("backend")).putExtra("sessionId",task.optString("id")).putExtra("title",task.optString("title","会话"));
            context.startActivity(intent);
        }catch(Exception ignored){}
        closeBubble();
    }

    /** Keeps the stroke alive for as long as the finger is down. */
    private void holdPet(){
        if(holdFrame!=null)return;
        holdFrame=new Runnable(){@Override public void run(){
            if(!petDown||closed||pet==null){holdFrame=null;return;}
            long now=SystemClock.uptimeMillis();
            long down=now-pressAt;
            // Past the long-press threshold the pet stops enjoying it.
            if(down>=520)pet.hold(Math.min(1f,(down-520)/420f));
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
        if(menuOpen){populatePetMenu();return;}
        if(!connection.isEmpty()){bubbleList.addView(bubbleNote(connection));return;}
        int[] counts=new int[3];
        java.util.ArrayList<JSONObject> active=collectActive(counts);
        if(active.isEmpty()){
            bubbleList.addView(bubbleNote(counts[0]+counts[1]+counts[2]>0?"有待处理的请求，点开进入会话":"正在待命，暂时没有任务"));
            return;
        }
        for(JSONObject task:active){
            LinearLayout item=new LinearLayout(context);item.setOrientation(1);item.setPadding(ui.dp(8),ui.dp(8),ui.dp(8),ui.dp(8));
            String backend=task.optString("backend"),name=backend.equals("claude")?"Claude":backend.equals("deepseek")?"DeepSeek":"ChatGPT";
            JSONArray pending=AttentionTracker.pending(task);JSONObject first=pending.optJSONObject(0);
            java.util.LinkedHashSet<String> states=new java.util.LinkedHashSet<>();
            for(int i=0;i<pending.length();i++){JSONObject request=pending.optJSONObject(i);if(request!=null)states.add(request.optString("label","待处理"));}
            String state=first==null?"执行中":android.text.TextUtils.join(" · ",states);
            TextView head=ui.text(name+" · "+state,NativeUi.CAPTION,NativeUi.MUTED);
            head.setSingleLine();head.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout heading=new LinearLayout(context);heading.setGravity(Gravity.CENTER_VERTICAL);
            ImageView appIcon=ui.providerIcon(backend,22);appIcon.setContentDescription(name+"应用图标");appIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            LinearLayout.LayoutParams iconLayout=new LinearLayout.LayoutParams(ui.dp(22),ui.dp(22));iconLayout.rightMargin=ui.dp(8);heading.addView(appIcon,iconLayout);heading.addView(head,new LinearLayout.LayoutParams(0,-2,1));
            item.addView(heading);
            TextView session=ui.text(task.optString("title","会话"),NativeUi.BODY,NativeUi.ACCENT);NativeUi.strong(session);session.setTag("overlay-session-title");session.setMaxLines(2);session.setEllipsize(android.text.TextUtils.TruncateAt.END);session.setPadding(0,ui.dp(8),0,ui.dp(8));item.addView(session);
            View separator=new View(context);separator.setTag("overlay-title-separator");separator.setBackgroundColor(NativeUi.EDGE);item.addView(separator,new LinearLayout.LayoutParams(-1,Math.max(1,ui.dp(.5f))));
            item.setBackground(new StatusSpine(context,first!=null?"waiting":task.optString("status"),first!=null?NativeUi.WAIT_BG:NativeUi.WHITE));
            String raw=first==null?task.optString("progress",""):first.optString("preview","点击处理");
            String line=prose(raw);
            // "正在思考…" rather than an empty row: a task with nothing readable left
            // after filtering is still running, and a blank line looks like a bug.
            if(line.isEmpty())line=first!=null?"点开查看待处理事项":"正在处理任务，等待新的回复…";
            // One step under the body size: the bubble is a glance, not a transcript, and
            // at body size three lines of preview filled the whole speech bubble.
            TextView progress=ui.text(line,NativeUi.LABEL,foreground);
            progress.setTag("overlay-session-content");progress.setVisibility(line.isEmpty()?View.GONE:View.VISIBLE);progress.setMaxLines(3);progress.setEllipsize(android.text.TextUtils.TruncateAt.END);progress.setPadding(0,ui.dp(8),0,0);item.addView(progress);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,ui.dp(2),0,ui.dp(2));bubbleList.addView(item,lp);
            item.setContentDescription((first==null?"打开运行会话 ":"打开待处理会话 ")+task.optString("id"));
            item.setOnClickListener(v->{context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("backend",backend).putExtra("sessionId",task.optString("id")).putExtra("title",task.optString("title","会话")));closeBubble();});
        }
    }

    private void populatePetMenu(){
        TextView title=ui.text("悬浮窗操作",NativeUi.TITLE,foreground);NativeUi.strong(title);
        title.setPadding(ui.dp(4),ui.dp(6),ui.dp(4),ui.dp(8));bubbleList.addView(title);
        menuAction("打开 coding远程","home",()->{try{context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));}catch(Exception ignored){}closeBubble();});
        menuAction("打开当前会话","chat",this::openCurrentSession);
        String[] modes={"full","important","badge","quiet"},labels={"完整动作","仅重要事件","状态角标","静默"};for(int i=0;i<modes.length;i++){final String mode=modes[i];menuAction(labels[i],displayMode().equals(mode)?"check":"dot",()->{prefs().edit().putString("overlayMode",mode).apply();closeBubble();render();});}
        menuAction("隐藏 1 小时","sleep",()->{prefs().edit().putLong("overlayHiddenUntil",System.currentTimeMillis()+60L*60L*1000L).apply();remove();});
        menuAction("关闭悬浮窗","close",()->{prefs().edit().putBoolean("overlay",false).remove("overlayHiddenUntil").apply();remove();});
    }

    private void menuAction(String label,String icon,final Runnable action){
        LinearLayout item=new LinearLayout(context);item.setOrientation(LinearLayout.HORIZONTAL);item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(ui.dp(6),0,ui.dp(6),0);
        TextView text=ui.text(label,NativeUi.LABEL,foreground);ui.leading(text,icon,foreground,NativeUi.GLYPH_INLINE);item.addView(text,new LinearLayout.LayoutParams(0,ui.dp(42),1));
        item.setContentDescription(label);item.setBackground(ui.touch(withAlpha(foreground,0x16),NativeUi.CORNER_ROW,true));item.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,ui.dp(44));lp.setMargins(0,ui.dp(2),0,ui.dp(2));bubbleList.addView(item,lp);
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
            if(!hasChinese(trimmed)&&!trimmed.matches(".*[A-Za-z]{2,}\\s+[A-Za-z]{2,}.*"))continue;
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
            String piece=part.trim().replaceAll("[.。…!！\\s]+$","");
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
        Rect safe=safeBounds();int size=ui.dp(petForm.equals("none")||displayMode().equals("badge")?54:64);
        bubbleSide=anchorX+size/2<safe.centerX()?SpeechBubble.TAIL_LEFT:SpeechBubble.TAIL_RIGHT;
        bubbleOpen=true;
        useScrim=true;
        render();
        showScrim();
        main.removeCallbacks(dismissBubble);int duration=new OverlayPolicy(prefs()).duration;if(duration>0)main.postDelayed(dismissBubble,duration*1000L);
    }
    private void closeBubble(){
        if(!bubbleOpen)return;
        // A tap outside is an ordinary dismissal, not a reason to stop showing the pet.
        useScrim=false;hideScrim();
        bubbleOpen=false;menuOpen=false;
        removeBubbleWindow();bubbleBack=null;bubbleList=null;
        render();
    }
    private void closeBubbleNow(){useScrim=false;hideScrim();removeBubbleWindow();bubbleBack=null;bubbleList=null;bubbleOpen=false;menuOpen=false;}

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
            if(raisePet){manager.addView(panel,params);showBubbleWindow(params.width);}
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
    void close(){main.post(()->{closed=true;main.removeCallbacks(policyTick);main.removeCallbacks(dismissBubble);prefs().unregisterOnSharedPreferenceChangeListener(preferencesChanged);try{context.unregisterReceiver(environmentChanged);}catch(Exception ignored){}AppVisibility.remove(visibilityChanged);remove();
        // The instance is finished: clearing the static reference is what stops the
        // settings button from posting a render against a service that has already been
        // torn down, which used to throw on the main thread and close the app.
        if(live==this)live=null;});}
}
