package io.github.abyxs.codingremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Bundle;
import android.widget.ListView;

/** A real 28dp touch target, independent of Android's fading decorative scrollbar. */
final class FastScroller extends View {
    private final ListView list;private final Runnable moved,released;private final Paint paint=new Paint(3);private boolean dragging;
    private final java.util.IdentityHashMap<Object,Integer> heights=new java.util.IdentityHashMap<>();private Object firstItem;
    FastScroller(Context context,ListView view,Runnable callback,Runnable onRelease){super(context);list=view;moved=callback;released=onRelease;setContentDescription("拖动浏览会话");setFocusable(true);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
    private float[] metrics(){int count=list.getCount();if(count==0)return new float[]{0,0,1};Object current=list.getAdapter().getItem(0);if(current!=firstItem){heights.clear();firstItem=current;}int first=list.getFirstVisiblePosition();for(int i=0;i<list.getChildCount()&&first+i<count;i++){View child=list.getChildAt(i);if(child.getHeight()>0)heights.put(list.getAdapter().getItem(first+i),child.getHeight());}float average=0;for(int h:heights.values())average+=h;average=heights.isEmpty()?120*getResources().getDisplayMetrics().density:average/heights.size();float total=0,offset=0;for(int i=0;i<count;i++){float h=height(i,average);if(i<first)offset+=h;total+=h;}if(list.getChildCount()>0)offset+=list.getPaddingTop()-list.getChildAt(0).getTop();return new float[]{total+list.getPaddingTop()+list.getPaddingBottom(),Math.max(0,offset),average};}
    private float height(int index,float average){Integer measured=heights.get(list.getAdapter().getItem(index));return measured==null?average:measured;}
    private float fraction(){float[] m=metrics();return Math.max(0,Math.min(1,m[1]/Math.max(1,m[0]-getHeight())));}
    boolean isDragging(){return dragging;}
    private float thumb(){float[] m=metrics();return Math.min(getHeight(),Math.max(40*getResources().getDisplayMetrics().density,getHeight()*getHeight()/Math.max(1,m[0])));}
    @Override protected void onDraw(Canvas canvas){if(metrics()[0]<=getHeight()+2)return;float h=thumb(),top=(getHeight()-h)*fraction();paint.setColor(dragging?NativeUi.GREEN:0xffb7c5b8);float width=dragging?8:4;float density=getResources().getDisplayMetrics().density;canvas.drawRoundRect(getWidth()-width*density-3*density,top,getWidth()-3*density,top+h,8,8,paint);}
    private void seek(float fraction){float[] m=metrics();float target=Math.max(0,Math.min(1,fraction))*Math.max(0,m[0]-getHeight());int index=0;while(index<list.getCount()-1&&target>height(index,m[2])){target-=height(index,m[2]);index++;}list.setSelectionFromTop(index,-Math.round(target));moved.run();invalidate();}
    @Override public boolean onTouchEvent(MotionEvent e){if(metrics()[0]<=getHeight()+2&&!dragging)return false;switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:dragging=true;getParent().requestDisallowInterceptTouchEvent(true);case MotionEvent.ACTION_MOVE:seek((e.getY()-thumb()/2)/Math.max(1,getHeight()-thumb()));return true;case MotionEvent.ACTION_UP:performClick();case MotionEvent.ACTION_CANCEL:dragging=false;getParent().requestDisallowInterceptTouchEvent(false);invalidate();released.run();return true;}return true;}
    @Override public boolean performClick(){super.performClick();return true;}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.SeekBar");info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(1,0,100,fraction()*100));info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);}
    @Override public boolean performAccessibilityAction(int action,Bundle args){if(action==android.R.id.accessibilityActionSetProgress&&args!=null){seek(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)/100);return true;}return super.performAccessibilityAction(action,args);}
}
