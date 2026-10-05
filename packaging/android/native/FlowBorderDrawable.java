package io.github.abyxs.codingremote;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Short rainbow trails travel by distance along the rounded window perimeter. */
final class FlowBorderDrawable extends Drawable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline=new Path(),trail=new Path();
    private final int fill;
    private final float radius,stroke;
    private float phase;
    FlowBorderDrawable(int fill,float radius,float stroke){this.fill=fill;this.radius=radius;this.stroke=stroke;}
    void phase(float value){phase=value;invalidateSelf();}
    @Override public void draw(Canvas canvas){
        Rect bounds=getBounds();RectF box=new RectF(bounds);paint.setStyle(Paint.Style.FILL);paint.setColor(fill);canvas.drawRoundRect(box,radius,radius,paint);
        box.inset(stroke/2,stroke/2);outline.reset();outline.addRoundRect(box,radius,radius,Path.Direction.CW);
        PathMeasure measure=new PathMeasure(outline,false);float length=measure.getLength();if(length<=0)return;
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setStrokeCap(Paint.Cap.ROUND);
        int[] colors={0xff16d6ae,0xff43a4ff,0xff8965ff,0xffef67d1,0xffffc75c};
        for(int i=0;i<60;i++){
            float start=((phase+i*.0035f)%1f)*length,end=start+length*.005f;
            paint.setColor(colors[i/12]);paint.setAlpha(Math.round(255f*(.15f+.85f*i/59f)));
            trail.reset();measure.getSegment(start,Math.min(length,end),trail,true);if(end>length)measure.getSegment(0,end-length,trail,true);canvas.drawPath(trail,paint);
        }
        paint.setAlpha(255);
    }
    @Override public void getOutline(Outline out){out.setRoundRect(getBounds(),radius);}
    @Override public void setAlpha(int alpha){}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
