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
    @Override public void draw(Canvas canvas){Rect bounds=getBounds();float cx=bounds.exactCenterX(),cy=bounds.exactCenterY(),radius=Math.min(bounds.width(),bounds.height())/2f;paint.setStyle(Paint.Style.FILL);paint.setColor(NativeUi.GREEN);canvas.drawCircle(cx,cy,radius-7*density,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.5f*density);paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(NativeUi.LINE);RectF arc=new RectF(cx-radius+2*density,cy-radius+2*density,cx+radius-2*density,cy+radius-2*density);canvas.drawArc(arc,-90,360,false,paint);float fraction=fraction(usage);if(fraction>0){paint.setColor(fraction>=.9?NativeUi.RED:fraction>=.75?NativeUi.EMPHASIS:NativeUi.GREEN);canvas.drawArc(arc,-90,360*fraction,false,paint);}}
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
