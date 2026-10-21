package io.github.abyxs.codingremote;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** The whole perimeter carries a rainbow band that travels around the border.
 * A sweep shader colours every edge at once and the phase rotates it, so the
 * ring moves as a whole instead of a short trail crossing one side. */
final class FlowBorderDrawable extends Drawable {
    // First and last entries match, so the seam of the sweep is invisible.
    private static final int[] COLORS={0xff16d6ae,0xff43a4ff,0xff8965ff,0xffef67d1,0xffffc75c,0xff16d6ae};
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline=new Path();
    private final Matrix rotate=new Matrix();
    private final RectF cached=new RectF();
    private final int fill;
    private final float radius,stroke;
    private SweepGradient sweep;
    private float phase;
    FlowBorderDrawable(int fill,float radius,float stroke){this.fill=fill;this.radius=radius;this.stroke=stroke;}
    void phase(float value){phase=value;invalidateSelf();}
    @Override public void draw(Canvas canvas){
        Rect bounds=getBounds();RectF box=new RectF(bounds);
        paint.setStyle(Paint.Style.FILL);paint.setShader(null);paint.setAlpha(255);paint.setColor(fill);
        canvas.drawRoundRect(box,radius,radius,paint);
        box.inset(stroke/2,stroke/2);
        outline.reset();outline.addRoundRect(box,radius,radius,Path.Direction.CW);
        if(sweep==null||Math.abs(cached.width()-box.width())>.5f||Math.abs(cached.height()-box.height())>.5f
            ||Math.abs(cached.centerX()-box.centerX())>.5f||Math.abs(cached.centerY()-box.centerY())>.5f){
            cached.set(box);
            sweep=new SweepGradient(box.centerX(),box.centerY(),COLORS,null);
        }
        rotate.setRotate(phase*360f,box.centerX(),box.centerY());
        sweep.setLocalMatrix(rotate);
        paint.setShader(sweep);paint.setStyle(Paint.Style.STROKE);
        // A soft outer glow plus the crisp ring; both cover the entire border.
        paint.setStrokeWidth(stroke*2.8f);paint.setAlpha(46);canvas.drawPath(outline,paint);
        paint.setStrokeWidth(stroke);paint.setAlpha(255);canvas.drawPath(outline,paint);
        paint.setShader(null);
    }
    @Override public void getOutline(Outline out){out.setRoundRect(getBounds(),radius);}
    @Override public void setAlpha(int alpha){}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
