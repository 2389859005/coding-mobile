package io.github.abyxs.codingremote;

import android.content.Context;
import android.view.*;
import android.widget.*;

/** One draggable handle containing independent, accessible top/latest actions. */
final class FloatingNavigation extends LinearLayout {
    final Button top,latest;
    /** The disc size and the gap between the two discs. The height the handle needs is
     *  disc+gap+disc plus its own padding, and the call site sizes the container from
     *  these rather than from a number it guessed. */
    static final int DISC_DP=46;
    static final int GAP_DP=8;
    static int spanDp(){return DISC_DP*2+GAP_DP;}
    private float startX,startY,baseX,baseY;
    private boolean dragged;
    private final android.os.Handler idle=new android.os.Handler(android.os.Looper.getMainLooper());
    /** The handle fades out once the transcript stops moving. Leaving it up
     * permanently put a white disc over the last few lines of every message, and it
     * was only ever wanted at the moment the reader was scrolling. */
    private static final long IDLE_MS=1600;
    FloatingNavigation(Context context,NativeUi ui,Runnable toTop,Runnable toLatest){
        super(context);setOrientation(VERTICAL);
        // No horizontal padding: the discs are exactly as wide as the handle, so the
        // circular background is not squared off by the parent's edge.
        setPadding(0,0,0,0);setClipToPadding(false);setClipChildren(false);
        setBackgroundColor(android.graphics.Color.TRANSPARENT);
        top=ui.iconButton("arrow","回到顶部");latest=ui.iconButton("latest","回到最新");
        // A radius equal to half the disc is what makes it a circle; any of the named
        // corner steps rounds the corners and stops there. The discs are square by
        // construction and the background is drawn to that square.
        int disc=ui.dp(DISC_DP);
        top.setBackground(ui.touch(NativeUi.WHITE,DISC_DP/2,true));latest.setBackground(ui.touch(NativeUi.WHITE,DISC_DP/2,true));
        top.setElevation(ui.dp(NativeUi.LIFT_FLOATING));latest.setElevation(ui.dp(NativeUi.LIFT_FLOATING));
        // iconButton brings its own 12dp padding, which shrinks the drawn disc inside a
        // 46dp box; clearing it lets the background fill the whole square.
        top.setPadding(0,0,0,0);latest.setPadding(0,0,0,0);
        addView(top,new LayoutParams(disc,disc));
        LayoutParams latestLp=new LayoutParams(disc,disc);
        latestLp.topMargin=ui.dp(GAP_DP);
        addView(latest,latestLp);
        top.setOnClickListener(v->toTop.run());latest.setOnClickListener(v->toLatest.run());
        View.OnTouchListener drag=(v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){startX=e.getRawX();startY=e.getRawY();baseX=getX();baseY=getY();dragged=false;v.setPressed(true);
                // The handle is brought back before the gesture is judged, so a tap on
                // a faded-out handle still lights it up and still reaches the button.
                restore();wake();
                // Let the touch through until the slop is exceeded, so the button
                // still reports a normal click to click listeners and to TalkBack.
                return false;}
            else if(e.getActionMasked()==MotionEvent.ACTION_MOVE){float dx=e.getRawX()-startX,dy=e.getRawY()-startY;
                if(dragged||Math.hypot(dx,dy)>ViewConfiguration.get(context).getScaledTouchSlop()){
                    dragged=true;getParent().requestDisallowInterceptTouchEvent(true);View p=(View)getParent();
                    setX(Math.max(0,Math.min(Math.max(0,p.getWidth()-getWidth()-ui.dp(28)),baseX+dx)));setY(Math.max(0,Math.min(Math.max(0,p.getHeight()-getHeight()),baseY+dy)));return true;}}
            else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){getParent().requestDisallowInterceptTouchEvent(false);
                // A drag is ours to finish: store where it landed and consume the event so
                // the disc does not also fire its click on release.
                if(dragged){View p=(View)getParent();context.getSharedPreferences("ui",0).edit().putFloat("floatX",getX()/Math.max(1,p.getWidth()-getWidth())).putFloat("floatY",getY()/Math.max(1,p.getHeight()-getHeight())).apply();v.setPressed(false);rest();return true;}
                // A tap is not. Clearing the pressed state here ran before the Button's own
                // onTouchEvent, and that method performs the click only when it still sees
                // the view as pressed - so every tap on a disc was swallowed while
                // performClick() worked, which is exactly the "the buttons do nothing"
                // report. Let the Button finish its own gesture.
                rest();return false;}
            return false;
        };top.setOnTouchListener(drag);latest.setOnTouchListener(drag);
    }
    /** Makes the handle visible again and restarts the idle countdown. */
    void wake(){
        idle.removeCallbacksAndMessages(null);
        if(getAlpha()<1f){animate().cancel();animate().alpha(1f).setDuration(120).start();}
        // Fading is expressed as alpha only. The container stays VISIBLE, clickable and
        // focusable at every alpha, because a faded-out handle that is not clickable is
        // a button that silently does nothing - which is what "the buttons still do not
        // work" was: the countdown had faded the handle between the user's scroll and
        // their tap, and the tap landed on a view that had been switched off.
        setClickable(true);setFocusable(true);setVisibility(VISIBLE);
        idle.postDelayed(this::fade,IDLE_MS);
    }
    /** Restarts the countdown without forcing the handle back into view. */
    void rest(){idle.removeCallbacksAndMessages(null);idle.postDelayed(this::fade,IDLE_MS);}
    private void fade(){
        // Not fully transparent. A view at alpha 0 is still hit-tested by the parent,
        // but everything about it stops looking like a control, and on some OEM builds
        // the framework skips drawing entirely once alpha reaches zero - which makes the
        // handle both invisible and, in the user's experience, absent. Fading to a low
        // but non-zero alpha keeps it a real, tappable view at every moment.
        animate().cancel();
        animate().alpha(0.12f).setDuration(220).start();
    }
    /** Keeps the handle on screen while a button inside it is still relevant. */
    void hold(){idle.removeCallbacksAndMessages(null);animate().cancel();setAlpha(1f);setClickable(true);setFocusable(true);setVisibility(VISIBLE);}
    /** Any touch on the handle brings it back before the gesture is classified. */
    private void restore(){animate().cancel();if(getAlpha()<1f)animate().alpha(1f).setDuration(120).start();}
    void place(){post(()->{View p=(View)getParent();if(p==null)return;android.content.SharedPreferences pref=getContext().getSharedPreferences("ui",0);
        // Clamp before normalising: on a parent narrower than the handle the old
        // expression produced a negative bound that pinned the handle to the left.
        int spanX=Math.max(0,p.getWidth()-getWidth()),spanY=Math.max(0,p.getHeight()-getHeight());
        setX(Math.max(0,Math.min(Math.max(0,spanX-dp(28)),pref.getFloat("floatX",.95f)*spanX)));setY(Math.max(0,pref.getFloat("floatY",.86f)*spanY));});}
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
}
