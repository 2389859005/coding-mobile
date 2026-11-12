package io.github.abyxs.codingremote.tests;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import org.json.*;import java.lang.reflect.*;

/** Real overlay windows on an isolated emulator; both sides and repeated snapshots. */
public final class OverlayStabilityRegression extends Instrumentation {
    Object overlay;Class<?> type;Throwable failure;String theme="light";
    interface Check{void run()throws Exception;}
    void ui(Check check)throws Exception{failure=null;runOnMainSync(()->{try{check.run();}catch(Throwable e){failure=e;}});if(failure!=null)throw new Exception(failure);waitForIdleSync();SystemClock.sleep(100);}
    Object get(String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(overlay);}
    void set(String name,Object value)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(overlay,value);}
    Object call(String name,Class<?>[] types,Object...args)throws Exception{Method m=type.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(overlay,args);}
    void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    int[] location(String name)throws Exception{int[] xy=new int[2];ui(()->((View)get(name)).getLocationOnScreen(xy));return xy;}
    void same(int[] a,int[] b,String why){require(Math.abs(a[0]-b[0])<=1&&Math.abs(a[1]-b[1])<=1,why+": "+a[0]+","+a[1]+" → "+b[0]+","+b[1]);}
    boolean content(View v){if(v instanceof android.widget.TextView&&((android.widget.TextView)v).getText().toString().contains("已完成第 5 项检查"))return v.isShown()&&v.getHeight()>0;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(content(((ViewGroup)v).getChildAt(i)))return true;return false;}
    boolean appIcon(View view){if(view instanceof android.widget.ImageView&&"ChatGPT应用图标".contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return true;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(appIcon(((ViewGroup)view).getChildAt(i)))return true;return false;}
    void shot(String name)throws Exception{android.graphics.Bitmap b=getUiAutomation().takeScreenshot();java.io.File d=new java.io.File(getTargetContext().getExternalFilesDir(null),"ui-captures");d.mkdirs();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(d,"223-overlay-"+name+".png"))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
    public void onCreate(Bundle args){super.onCreate(args);theme=args.getString("theme","light");start();}
    public void onStart(){Bundle result=new Bundle();Context c=getTargetContext();try{
        require(android.provider.Settings.canDrawOverlays(c),"overlay permission granted on isolated emulator");
        c.getSharedPreferences("ui",0).edit().putString("appearance",theme).commit();
        c.getSharedPreferences("ui",0).edit().putBoolean("overlay",true).putBoolean("overlayForeground",true).putBoolean("overlayQuietHours",false).putLong("overlayHiddenUntil",0).putString("overlayMode","full").putInt("overlayBubbleSeconds",0).putInt("overlayBubbleWidth",260).putBoolean("reduceMotion",false).putString("pet","maltese").commit();
        type=Class.forName("io.github.abyxs.codingremote.StatusOverlay");Constructor<?> ctor=type.getDeclaredConstructor(Context.class);ctor.setAccessible(true);ui(()->overlay=ctor.newInstance(c));
        float density=c.getResources().getDisplayMetrics().density;int width=c.getResources().getDisplayMetrics().widthPixels,size=Math.round(64*density);
        for(int anchor:new int[]{Math.round(8*density),width-size-Math.round(8*density),(width-size)/2}){
            ui(()->{call("closeBubble",new Class[]{});call("renderNow",new Class[]{boolean.class},true);set("anchorX",anchor);set("anchorY",Math.round(200*density));call("renderNow",new Class[]{boolean.class},true);});int[] before=location("petHost");
            ui(()->call("openBubble",new Class[]{}));same(before,location("petHost"),"opening keeps pet anchor");int[] bubble=location("bubbleLayer");Object side=get("bubbleSide");
            for(int i=0;i<6;i++){final int frame=i;ui(()->{set("snapshot",new JSONObject().put("tasks",new JSONArray().put(new JSONObject().put("id","fixture").put("backend","codex").put("title","同步检查").put("status","active").put("progress","已完成第 "+frame+" 项检查"))));call("renderNow",new Class[]{boolean.class},true);});same(before,location("petHost"),"snapshot does not move pet");same(bubble,location("bubbleLayer"),"snapshot does not move bubble");require(side.equals(get("bubbleSide")),"bubble direction remains stable");}
            require(content((View)get("bubbleList")),"bubble shows readable reply content");
            View list=(View)get("bubbleList");android.widget.TextView title=list.findViewWithTag("overlay-session-title"),body=list.findViewWithTag("overlay-session-content");
            require(title!=null&&body!=null&&title.getTextSize()>body.getTextSize()&&title.getTypeface().isBold(),"session title is larger and bold");
            require(title.getCurrentTextColor()!=body.getCurrentTextColor(),"title and content have distinct colours");
            require(list.findViewWithTag("overlay-title-separator")!=null&&body.getPaddingTop()>0,"rule and spacing separate title from reply");
            require(appIcon((View)get("bubbleList")),"bubble contains the provider application icon");
            shot(anchor<width/3?"left":anchor>width/2?"right":"center");ui(()->call("closeBubble",new Class[]{}));same(before,location("petHost"),"closing keeps pet anchor");
        }
        result.putString("stream","PASS: real overlay windows at left, right and center; opening, six content updates and closing preserve pet/bubble anchors and direction.\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}finally{try{if(overlay!=null)ui(()->call("close",new Class[]{}));}catch(Exception ignored){}c.getSharedPreferences("ui",0).edit().putBoolean("overlay",false).putBoolean("overlayForeground",false).commit();}}
}
