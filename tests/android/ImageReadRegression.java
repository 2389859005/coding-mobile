package io.github.abyxs.codingremote.tests;
import android.app.*;import android.content.*;import android.graphics.*;import android.graphics.drawable.*;import android.os.*;import android.view.*;import android.widget.*;import org.json.*;import java.lang.reflect.*;import java.util.*;

/** Synthetic completed desktop image read, bitmap decoding, viewer and retry. */
public final class ImageReadRegression extends Instrumentation {
    Activity app;Throwable failure;volatile boolean corrupt=false;JSONObject fixture;
    interface Check{void run()throws Exception;}
    Object get(Object o,String n)throws Exception{Class<?> c=o instanceof Class?(Class<?>)o:o.getClass();Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o instanceof Class?null:o);}
    void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
    Object call(Object o,String n,Class<?>[] types,Object...args)throws Exception{Class<?> c=o instanceof Class?(Class<?>)o:o.getClass();Method m=c.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o instanceof Class?null:o,args);}
    void ui(Check c)throws Exception{failure=null;runOnMainSync(()->{try{c.run();}catch(Throwable e){failure=e;}});if(failure!=null)throw new Exception(failure);waitForIdleSync();}
    void require(boolean b,String why){if(!b)throw new AssertionError(why);}
    void await(Check check)throws Exception{Throwable last=null;for(int n=0;n<70;n++){try{ui(check);return;}catch(Throwable e){last=e;}SystemClock.sleep(100);}throw new Exception(last);}
    View find(View v,String text){if(v instanceof TextView&&text.equals(((TextView)v).getText().toString()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View r=find(((ViewGroup)v).getChildAt(i),text);if(r!=null)return r;}return null;}
    ImageView bitmap(View v){if(v instanceof ImageView&&((ImageView)v).getDrawable() instanceof BitmapDrawable)return (ImageView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){ImageView r=bitmap(((ViewGroup)v).getChildAt(i));if(r!=null)return r;}return null;}
    void tap(View v)throws Exception{int[] p=new int[2];ui(()->v.getLocationOnScreen(p));long t=SystemClock.uptimeMillis();float x=p[0]+v.getWidth()/2f,y=p[1]+v.getHeight()/2f;sendPointerSync(MotionEvent.obtain(t,t,0,x,y,0));sendPointerSync(MotionEvent.obtain(t,t+50,1,x,y,0));waitForIdleSync();}
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){Bundle result=new Bundle();try{
        Context context=getTargetContext();context.getSharedPreferences("ui",0).edit().putBoolean("overlay",false).putBoolean("notifications",false).putBoolean("resumeChat",false).putString("appearance","light").commit();
        call(Class.forName("io.github.abyxs.codingremote.SecureStore"),"clear",new Class[]{Context.class},context);
        app=startActivitySync(new Intent().setClassName(context,"io.github.abyxs.codingremote.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Bitmap source=Bitmap.createBitmap(180,100,Bitmap.Config.ARGB_8888);source.eraseColor(Color.rgb(31,171,104));java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();source.compress(Bitmap.CompressFormat.PNG,100,bytes);source.recycle();String png=android.util.Base64.encodeToString(bytes.toByteArray(),2);
        fixture=new JSONObject().put("connected",true).put("session",new JSONObject().put("id","image-reader").put("title","读取设计图片").put("cwd","C:/project").put("status","idle")).put("messages",new JSONArray().put(new JSONObject().put("id","read").put("kind","image").put("role","activity").put("title","查看图片").put("text","![图片](<C:/Temp/read image.png>)")));
        Class<?> transport=Class.forName("io.github.abyxs.codingremote.MainActivity$Transport");Object net=Proxy.newProxyInstance(transport.getClassLoader(),new Class[]{transport},(p,m,a)->{String route=String.valueOf(a[2]);if(route.contains("/file?")){require(route.contains("read%20image.png"),"image path is URI encoded");return new JSONObject().put("name","read image.png").put("mime","image/png").put("data",corrupt?"aW52YWxpZA==":png);}return new JSONObject(fixture.toString());});
        ui(()->{set(app,"transport",net);set(app,"origin","https://fixture.example");set(app,"token","fixture");set(app,"backend","codex");call(app,"openSession",new Class[]{String.class,String.class},"image-reader","读取设计图片");});
        await(()->{View list=(View)get(app,"messageList");ImageView image=bitmap(list);require(image!=null&&image.isShown(),"completed image read automatically shows decoded preview");Bitmap actual=((BitmapDrawable)image.getDrawable()).getBitmap();require(actual.getWidth()==180&&actual.getPixel(20,20)==Color.rgb(31,171,104),"thumbnail is the real fixture, not an icon");require(find(list,"点击查看原图")!=null,"preview has visible action");});
        Class<?> supervisor=Class.forName("io.github.abyxs.codingremote.ConnectionSupervisor");Object owner=call(supervisor,"get",new Class[]{Context.class},context);call(owner,"unwatch",new Class[]{Object.class},app);ui(()->set(app,"paused",true));
        tap(bitmap((View)get(app,"messageList")));Class<?> preview=Class.forName("io.github.abyxs.codingremote.NativePreview");
        await(()->{Dialog dialog=(Dialog)get(preview,"activeDialog");require(dialog!=null&&dialog.isShowing(),"image tap opens full preview");ImageView image=bitmap(dialog.getWindow().getDecorView());require(image!=null&&image.getClass().getSimpleName().equals("ZoomImage"),"native zoomable image is present");require(((BitmapDrawable)image.getDrawable()).getBitmap().getPixel(20,20)==Color.rgb(31,171,104),"full preview retains image bytes");});
        ui(()->((Dialog)get(preview,"activeDialog")).dismiss());
        ui(()->{corrupt=true;((android.util.LruCache<?,?>)get(app,"inlineImages")).evictAll();((android.util.LruCache<?,?>)get(app,"filePreviewCache")).evictAll();((android.widget.BaseAdapter)((ListView)get(app,"messageList")).getAdapter()).notifyDataSetChanged();});
        await(()->require(find((View)get(app,"messageList"),"图片加载失败 · 点击重试")!=null,"decode errors are visible"));
        corrupt=false;tap(find((View)get(app,"messageList"),"图片加载失败 · 点击重试"));await(()->require(bitmap((View)get(app,"messageList"))!=null&&find((View)get(app,"messageList"),"点击查看原图")!=null,"retry restores preview"));
        Bitmap shot=getUiAutomation().takeScreenshot();java.io.File dir=new java.io.File(context.getExternalFilesDir(null),"ui-captures");dir.mkdirs();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,"223-image-read.png"))){shot.compress(Bitmap.CompressFormat.PNG,100,out);}shot.recycle();
        result.putString("stream","PASS: completed image read, exact bitmap pixels, encoded path, native zoom preview and visible failure/retry.\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}finally{if(app!=null)runOnMainSync(()->app.finish());}}
}
