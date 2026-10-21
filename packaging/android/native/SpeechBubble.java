package io.github.abyxs.codingremote;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/**
 * The speech bubble that comes out of the pet's mouth.
 *
 * The overlay used to answer a tap with a second, full-screen window: a scrim over
 * the whole desktop with a centred card in it, which covered whatever the user was
 * doing to show a status the pet could have said itself. A bubble drawn as part of
 * the pet's own window keeps the interaction where the pet is, moves with it when
 * dragged, and needs no scrim at all.
 *
 * The tail is drawn as part of the body rather than as a separate triangle, so
 * there is no seam where the two outlines meet.
 */
final class SpeechBubble extends Drawable {
    /** Which way the bubble points: the tail sits on the bubble's lower left for a
     * pet on the right half of the screen, and lower right for a pet on the left,
     * so the bubble always opens towards the middle of the screen. */
    static final int TAIL_LEFT=0, TAIL_RIGHT=1;

    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final float radius,tailWidth,tailHeight;
    private int tail=TAIL_LEFT;
    private int fillColor=0xffffffff,borderColor=0x1a000000,shadowColor=0x14000000;
    private final float density;

    SpeechBubble(float density,float radius,float tailWidth,float tailHeight){
        this.density=density;this.radius=radius;this.tailWidth=tailWidth;this.tailHeight=tailHeight;
    }
    void tail(int value){tail=value;invalidateSelf();}
    int tail(){return tail;}
    void colors(int fill,int border,int shadow){fillColor=fill;borderColor=border;shadowColor=shadow;invalidateSelf();}

    @Override public void draw(Canvas canvas){
        Rect bounds=getBounds();
        if(bounds.width()<=0||bounds.height()<=0)return;
        // The tail is cut out of the side the pet is on, not out of the bottom. The
        // bubble is laid out beside the pet, so a downward tail pointed at empty desktop
        // and the bubble looked like a card that happened to sit next to an animal.
        float left=tailHeight,right=bounds.width()-tailHeight,top=0,bottom=bounds.height();
        if(tail==TAIL_LEFT){left=tailHeight;right=bounds.width();}
        else{left=0;right=bounds.width()-tailHeight;}
        float r=Math.min(radius,Math.min((right-left)/2f,(bottom-top)/2f));
        // Slightly below the middle: the tail should meet the pet's body, and the pet is
        // centred on the row while the bubble is a little taller than it is.
        float tipY=top+(bottom-top)*0.62f;
        float half=tailWidth*.5f;

        path.reset();
        path.moveTo(left+r,top);
        path.lineTo(right-r,top);
        path.quadTo(right,top,right,top+r);
        if(tail==TAIL_RIGHT){
            path.lineTo(right,tipY-half);
            path.lineTo(right+tailHeight,tipY);
            path.lineTo(right,tipY+half);
        }
        path.lineTo(right,bottom-r);
        path.quadTo(right,bottom,right-r,bottom);
        path.lineTo(left+r,bottom);
        path.quadTo(left,bottom,left,bottom-r);
        if(tail==TAIL_LEFT){
            path.lineTo(left,tipY+half);
            path.lineTo(left-tailHeight,tipY);
            path.lineTo(left,tipY-half);
        }
        path.lineTo(left,top+r);
        path.quadTo(left,top,left+r,top);
        path.close();

        // A soft drop shadow, drawn as an offset copy of the same outline.
        int savedAlpha=paint.getAlpha();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(shadowColor);
        canvas.save();canvas.translate(0,density*1.4f);canvas.drawPath(path,paint);canvas.restore();

        paint.setColor(fillColor);
        canvas.drawPath(path,paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f,density*.8f));
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(borderColor);
        canvas.drawPath(path,paint);
        paint.setAlpha(savedAlpha);
    }

    /** Height of the box the bubble needs, tail included. */
    static float requiredHeight(float tailHeight,float contentHeight){return contentHeight+tailHeight;}
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
