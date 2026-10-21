package io.github.abyxs.codingremote;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import org.json.JSONObject;

/** Context occupancy starts at twelve o'clock and advances clockwise. */
final class ContextRing extends Drawable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final JSONObject usage;
    ContextRing(float density,JSONObject usage){this.density=density;this.usage=usage;}
    static float fraction(JSONObject usage){return usage!=null&&usage.optBoolean("available")&&usage.optDouble("maxTokens")>0?(float)Math.max(0,Math.min(1,usage.optDouble("usedTokens")/usage.optDouble("maxTokens"))):0;}
    static String label(JSONObject usage){return usage!=null&&usage.optBoolean("available")?Math.round(fraction(usage)*100)+"%":"—";}
    static String description(JSONObject usage){return usage!=null&&usage.optBoolean("available")?String.format(java.util.Locale.CHINA,"上下文已用 %,d / %,d tokens（%s）",usage.optLong("usedTokens"),usage.optLong("maxTokens"),label(usage)):"上下文用量暂不可用";}
    @Override public void draw(Canvas canvas){
        Rect bounds=getBounds();
        float cx=bounds.exactCenterX(),cy=bounds.exactCenterY();
        float radius=Math.min(bounds.width(),bounds.height())/2f;
        // The fill is inset by the width of the ring itself, so the arc is painted on the
        // button's own background rather than on top of the green disc.
        //
        // The stroke is derived from the radius, which is already in pixels: scaling it by
        // density as well made the ring 13px wide on a 28px radius at xxhdpi, so the disc
        // vanished and the arc read as a smudge. A ring of about a fifth of the radius
        // keeps the arc legible against the fill at every density - at an eighth it was a
        // hairline that disappeared as soon as the phone was held at arm's length.
        float ring=Math.max(3.4f*density,radius*0.20f);
        // The disc is inset by more than the stroke so the arc sits on green, not half on
        // the fill and half off it.
        float fillRadius=Math.max(0f,radius-ring*1.75f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(NativeUi.GREEN);
        canvas.drawCircle(cx,cy,fillRadius,paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(ring);
        paint.setStrokeCap(Paint.Cap.ROUND);
        // The track and the arc are both derived from what is painted behind them, not
        // from the canvas: fixed black and white were calibrated for the light themes and
        // inverted against the saturated dark fills.
        // The arc is inset by half the stroke so the ring stays inside the disc it is
        // drawn on; at the full radius the outer half of the stroke fell on the padding
        // and the ring looked clipped on the sides.
        float arcRadius=Math.max(0f,radius-ring/2f);
        RectF arc=new RectF(cx-arcRadius,cy-arcRadius,cx+arcRadius,cy+arcRadius);
        paint.setColor(NativeUi.ringTrack());
        canvas.drawArc(arc,-90,360,false,paint);
        float fraction=fraction(usage);
        if(fraction>0){
            paint.setColor(fraction>=.9?NativeUi.ringDanger():fraction>=.75?NativeUi.ringWarning():NativeUi.ringProgress());
            canvas.drawArc(arc,-90,360*fraction,false,paint);
        }
    }
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
