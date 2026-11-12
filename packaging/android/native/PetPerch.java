package io.github.abyxs.codingremote;

import android.content.Context;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.util.function.Supplier;

/** Small companion anchored above the composer; detached pages stop their clock. */
final class PetPerch extends ImageView {
    private final NativeUi ui;private final PetSprite sprite;private final Supplier<String> state;private final Supplier<Boolean> unobstructed;private final Runnable attention;
    private long touched=SystemClock.uptimeMillis(),pulse,lastTap;private int taps;private float downX,downY,base;private boolean dragged,held;private PopupWindow menu;
    private final Runnable longPress=()->{held=true;showMenu();};
    PetPerch(Context context,NativeUi ui,Supplier<String> state,Supplier<Boolean> unobstructed,Runnable attention){
        super(context);this.ui=ui;this.state=state;this.unobstructed=unobstructed;this.attention=attention;
        sprite=new PetSprite(PetSprite.migrate(prefs().getString("pet",null)),NativeUi.GREEN,NativeUi.TINT,ui.dp(60));setImageDrawable(sprite);setScaleType(ScaleType.FIT_CENTER);setContentDescription("会话宠物，轻点互动，长按菜单");setFocusable(true);
        setOnClickListener(v->{touched=SystemClock.uptimeMillis();if(state.get().equals("waiting")){attention.run();return;}taps=touched-lastTap<1100?taps+1:1;lastTap=touched;pulse=touched;performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);});
    }
    private android.content.SharedPreferences prefs(){return getContext().getSharedPreferences("ui",0);}
    private final Runnable frame=new Runnable(){public void run(){
        if(!isAttachedToWindow())return;
        long now=SystemClock.uptimeMillis();boolean visible=prefs().getBoolean("petInChat",true)&&!prefs().getString("pet","").equals("none")&&prefs().getLong("chatPetHiddenUntil",0)<System.currentTimeMillis()&&unobstructed.get()&&!(prefs().getBoolean("overlay",false)&&prefs().getBoolean("overlayForeground",false));
        setVisibility(visible?VISIBLE:INVISIBLE);
        if(visible){String current=state.get();if(!current.equals("idle"))touched=now;if(current.equals("idle")&&now-touched>60000)current="sleep";
            sprite.form(PetSprite.migrate(prefs().getString("pet",null)));sprite.state(current);
            boolean reduced=prefs().getBoolean("reduceMotion",false)||android.provider.Settings.Global.getFloat(getContext().getContentResolver(),"animator_duration_scale",1)==0;
            float phase=reduced?0:now/1400f;float reaction=reduced?0:Math.max(0,1-(now-pulse)/650f);
            sprite.advance(phase,now%4200<120?1:0,reaction*.3f,0,0,0,dragged?1:0,held?1:0,reaction>0,taps);setAlpha(current.equals("sleep")?.58f:1f);
        }
        postDelayed(this,visible?120:400);
    }};
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();removeCallbacks(frame);post(frame);}
    @Override protected void onDetachedFromWindow(){removeCallbacks(frame);removeCallbacks(longPress);if(menu!=null)menu.dismiss();super.onDetachedFromWindow();}
    @Override public boolean onTouchEvent(MotionEvent e){
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                if(e.getX()<getWidth()*.1f||e.getX()>getWidth()*.9f||e.getY()<getHeight()*.08f)return false;
                touched=SystemClock.uptimeMillis();downX=e.getRawX();downY=e.getRawY();base=getTranslationX();dragged=false;held=false;postDelayed(longPress,600);return true;
            case MotionEvent.ACTION_MOVE:
                float delta=e.getRawX()-downX;if(Math.hypot(delta,e.getRawY()-downY)>ui.dp(6)){dragged=true;removeCallbacks(longPress);View parent=(View)getParent();setTranslationX(Math.max(0,Math.min(parent.getWidth()-getWidth()-ui.dp(70),base+delta)));}return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);if(!dragged&&!held)performClick();else if(dragged)animate().translationY(0).setDuration(120).start();dragged=false;return true;
            case MotionEvent.ACTION_CANCEL:removeCallbacks(longPress);dragged=false;return true;
        }return super.onTouchEvent(e);
    }
    private void showMenu(){
        LinearLayout box=new LinearLayout(getContext());box.setOrientation(1);box.setPadding(ui.dp(8),ui.dp(8),ui.dp(8),ui.dp(8));box.setBackground(ui.surface(NativeUi.WHITE,NativeUi.CORNER_CARD,true));
        menu=new PopupWindow(box,ui.dp(220),-2,true);menu.setBackgroundDrawable(ui.surface(NativeUi.WHITE,NativeUi.CORNER_CARD,true));menu.setOutsideTouchable(true);menu.setElevation(ui.dp(3));
        String[] labels={"查看当前任务","换一个宠物","隐藏 1 小时"};
        for(int i=0;i<labels.length;i++){final int index=i;Button action=ui.menu(labels[i],false);box.addView(action);action.setOnClickListener(v->{menu.dismiss();if(index==0)attention.run();else if(index==1)prefs().edit().putString("pet",sprite.form().equals("robot")?"maltese":"robot").apply();else prefs().edit().putLong("chatPetHiddenUntil",System.currentTimeMillis()+3600000).apply();});}
        menu.showAtLocation(getRootView(),Gravity.CENTER,0,0);
    }
}
