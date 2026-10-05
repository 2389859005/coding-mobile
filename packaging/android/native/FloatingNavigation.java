package io.github.abyxs.codingremote;

import android.content.Context;
import android.view.*;
import android.widget.*;

/** One draggable handle containing independent, accessible top/latest actions. */
final class FloatingNavigation extends LinearLayout {
    final Button top,latest;
    private float startX,startY,baseX,baseY;
    private boolean dragged;
    FloatingNavigation(Context context,NativeUi ui,Runnable toTop,Runnable toLatest){
        super(context);setOrientation(VERTICAL);setPadding(ui.dp(3),ui.dp(3),ui.dp(3),ui.dp(3));
        setBackgroundColor(android.graphics.Color.TRANSPARENT);
        top=ui.iconButton("arrow","回到顶部");latest=ui.iconButton("latest","回到最新");
        top.setBackground(ui.touch(NativeUi.WHITE,24,true));latest.setBackground(ui.touch(NativeUi.WHITE,24,true));top.setElevation(ui.dp(3));latest.setElevation(ui.dp(3));
        addView(top,new LayoutParams(ui.dp(44),ui.dp(44)));View gap=new View(context);addView(gap,new LayoutParams(1,ui.dp(8)));addView(latest,new LayoutParams(ui.dp(44),ui.dp(44)));
        top.setOnClickListener(v->toTop.run());latest.setOnClickListener(v->toLatest.run());
        View.OnTouchListener drag=(v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){startX=e.getRawX();startY=e.getRawY();baseX=getX();baseY=getY();dragged=false;v.setPressed(true);return true;}
            else if(e.getActionMasked()==MotionEvent.ACTION_MOVE){float dx=e.getRawX()-startX,dy=e.getRawY()-startY;
                if(dragged||Math.hypot(dx,dy)>ViewConfiguration.get(context).getScaledTouchSlop()){
                    dragged=true;getParent().requestDisallowInterceptTouchEvent(true);View p=(View)getParent();
                    setX(Math.max(0,Math.min(p.getWidth()-getWidth()-ui.dp(28),baseX+dx)));setY(Math.max(0,Math.min(p.getHeight()-getHeight(),baseY+dy)));return true;}}
            else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){getParent().requestDisallowInterceptTouchEvent(false);
                if(dragged){View p=(View)getParent();context.getSharedPreferences("ui",0).edit().putFloat("floatX",getX()/Math.max(1,p.getWidth()-getWidth())).putFloat("floatY",getY()/Math.max(1,p.getHeight()-getHeight())).apply();v.setPressed(false);return true;}v.setPressed(false);if(e.getActionMasked()==MotionEvent.ACTION_UP)v.performClick();return true;}
            return true;
        };top.setOnTouchListener(drag);latest.setOnTouchListener(drag);
    }
    void place(){post(()->{View p=(View)getParent();if(p==null)return;android.content.SharedPreferences pref=getContext().getSharedPreferences("ui",0);
        setX(Math.max(0,Math.min(p.getWidth()-getWidth()-28*getResources().getDisplayMetrics().density,pref.getFloat("floatX",.95f)*(p.getWidth()-getWidth()))));setY(Math.max(0,pref.getFloat("floatY",.86f)*(p.getHeight()-getHeight())));});}
}
