package io.github.abyxs.codingremote;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.*;

/** A shared left edge for sessions, replies and the floating summary. */
final class StatusSpine extends Drawable implements Runnable {
    private final Context context;
    private final String state;
    private final int fill;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    StatusSpine(Context context,String state,int fill){this.context=context;this.state=state;this.fill=fill;}
    static String state(JSONObject row){
        JSONArray requests=row.optJSONArray("requests"),attention=row.optJSONArray("attention");
        if(requests!=null&&requests.length()>0||attention!=null&&attention.length()>0)return "waiting";
        return row.optString("status","idle");
    }
    static boolean running(String state){return state.equals("active")||state.equals("running")||state.equals("inProgress")||state.equals("working");}
    static int color(String state){return state.equals("waiting")?NativeUi.WAIT:state.equals("failed")||state.equals("error")?NativeUi.RED:running(state)?NativeUi.RUN:NativeUi.LINE;}
    static String label(String state){return state.equals("waiting")?"待处理":state.equals("failed")||state.equals("error")?"出错":running(state)?"执行中":"";}
    static boolean reduced(Context c){return c.getSharedPreferences("ui",0).getBoolean("reduceMotion",false)||Settings.Global.getFloat(c.getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0;}
    public void draw(Canvas canvas){
        Rect b=getBounds();float d=context.getResources().getDisplayMetrics().density;
        paint.setShader(null);paint.setColor(fill);canvas.drawRoundRect(new RectF(b),14*d,14*d,paint);
        paint.setColor(color(state));RectF edge=new RectF(b.left,b.top+8*d,b.left+3*d,b.bottom-8*d);canvas.drawRoundRect(edge,1.5f*d,1.5f*d,paint);
        if(running(state)&&!reduced(context)&&isVisible()){
            canvas.save();canvas.clipRect(edge);float y=edge.top+((SystemClock.uptimeMillis()%1600)/1600f)*(edge.height()+24*d)-24*d;
            paint.setColor(NativeUi.INK);paint.setAlpha(95);canvas.drawRoundRect(new RectF(edge.left,y,edge.right,y+24*d),d,d,paint);paint.setAlpha(255);canvas.restore();scheduleSelf(this,SystemClock.uptimeMillis()+120);
        }
    }
    public void run(){invalidateSelf();}
    public boolean setVisible(boolean visible,boolean restart){if(!visible)unscheduleSelf(this);return super.setVisible(visible,restart);}
    public void setAlpha(int alpha){}
    public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}
    public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
