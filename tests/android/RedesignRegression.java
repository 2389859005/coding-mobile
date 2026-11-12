package io.github.abyxs.codingremote.tests;
import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.lang.reflect.*;
import java.util.*;

/** Uses synthetic content and an isolated emulator, never a personal conversation. */
public final class RedesignRegression extends Instrumentation {
    Activity app;Throwable failure;String theme="light";
    interface Check{void run()throws Exception;}
    Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
    Object call(Object o,String n,Class<?>[] types,Object...args)throws Exception{Class<?> c=o instanceof Class?(Class<?>)o:o.getClass();Method m=c.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o instanceof Class?null:o,args);}
    void ui(Check check)throws Exception{failure=null;runOnMainSync(()->{try{check.run();}catch(Throwable e){failure=e;}});if(failure!=null)throw new Exception(failure);waitForIdleSync();}
    void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    void tap(View view)throws Exception{int[] xy=new int[2];ui(()->view.getLocationOnScreen(xy));long now=SystemClock.uptimeMillis();float x=xy[0]+view.getWidth()/2f,y=xy[1]+view.getHeight()/2f;sendPointerSync(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0));sendPointerSync(MotionEvent.obtain(now,now+80,MotionEvent.ACTION_UP,x,y,0));waitForIdleSync();}
    View find(View v,String text){if(text.equals(String.valueOf(v.getContentDescription()))||v instanceof TextView&&text.equals(((TextView)v).getText().toString()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View result=find(((ViewGroup)v).getChildAt(i),text);if(result!=null)return result;}return null;}
    void shot(String name)throws Exception{SystemClock.sleep(400);Bitmap b=getUiAutomation().takeScreenshot();java.io.File folder=new java.io.File(getTargetContext().getExternalFilesDir(null),"ui-captures");folder.mkdirs();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(folder,"223-"+theme+"-"+name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
    public void onCreate(Bundle b){super.onCreate(b);theme=b.getString("theme","light");start();}
    public void onStart(){Bundle result=new Bundle();try{
        Context context=getTargetContext();context.getSharedPreferences("ui",0).edit().putString("appearance",theme).remove("theme").putBoolean("notifications",false).putBoolean("overlay",false).putBoolean("resumeChat",false).putBoolean("reduceMotion",true).commit();
        call(Class.forName("io.github.abyxs.codingremote.SecureStore"),"clear",new Class[]{Context.class},context);
        app=startActivitySync(new Intent().setClassName(context,"io.github.abyxs.codingremote.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        JSONObject fixture=new JSONObject().put("session",new JSONObject().put("id","fixture").put("title","检查会话同步").put("cwd","D:/Projects/product").put("status","active").put("model","gpt-5.1").put("effort","high").put("contextUsage",new JSONObject().put("available",true).put("usedTokens",12800).put("maxTokens",128000)))
            .put("connected",true).put("messages",new JSONArray().put(new JSONObject().put("id","u").put("role","user").put("text","请用表格汇总检查结果。"))
            .put(new JSONObject().put("id","a").put("role","assistant").put("text","检查结果如下：\n\n| 检查项 | 状态 | 说明 |\n| :--- | :---: | ---: |\n| 会话更新 | **通过** | 新内容及时显示 |\n| 断线恢复 | 通过 | 自动恢复订阅 |\n\n表格支持左右滑动。")));
        Class<?> transport=Class.forName("io.github.abyxs.codingremote.MainActivity$Transport");Object net=Proxy.newProxyInstance(transport.getClassLoader(),new Class[]{transport},(p,m,a)->{String route=String.valueOf(a[2]);if(route.contains("workspace/state"))return new JSONObject().put("status","saved").put("revision",1);return new JSONObject(fixture.toString());});
        ui(()->{set(app,"transport",net);set(app,"origin","https://fixture.example");set(app,"token","fixture");set(app,"backend","codex");call(app,"openSession",new Class[]{String.class,String.class},"fixture","检查会话同步");});SystemClock.sleep(800);
        Class<?> pageType=Class.forName("io.github.abyxs.codingremote.SessionPage"),supervisor=Class.forName("io.github.abyxs.codingremote.ConnectionSupervisor");Object owner=call(supervisor,"get",new Class[]{Context.class},context);call(owner,"unwatch",new Class[]{Object.class},app);
        Object page=call(pageType,"prepare",new Class[]{JSONObject.class},fixture);Object cache=((Map<?,?>)get(app,"chatCaches")).get(get(app,"sessionKey"));
        ui(()->{set(app,"paused",true);set(cache,"page",page);call(app,"renderSession",new Class[]{pageType,boolean.class},page,false);((EditText)get(app,"composer")).setText("保留我的草稿");});
        ui(()->{
            require(((TextView)get(app,"sendButton")).getText().length()==0,"send button shows an icon, not percentage");
            require(((TextView)get(app,"modelLabel")).getText().toString().equals("gpt-5.1 · 高"),"current model visible");
            require(((View)get(app,"recordCount")).getParent()==null,"turn count moved out of header");
            ListView list=(ListView)get(app,"messageList");require(list.findViewWithTag("markdown-table")!=null,"native Markdown table is visible");
            require(find(list,"会话更新")!=null&&find(list,"通过")!=null,"table cells preserve content and inline formatting");
        });shot("table");
        Class<?> table=Class.forName("io.github.abyxs.codingremote.MarkdownTable");List<?> escaped=(List<?>)call(table,"cells",new Class[]{String.class},"| a\\|b | `x|y` | **ok** |");require(escaped.size()==3&&escaped.get(0).equals("a|b")&&escaped.get(1).equals("`x|y`"),"escaped and code pipes stay in one cell");
        StringBuilder large=new StringBuilder("| Item | Result |\n| --- | --- |\n");for(int n=0;n<500;n++)large.append("| check-").append(n).append(" | passed with complete content |\n");
        ArrayList<?> chunks=(ArrayList<?>)call(pageType,"tableChunks",new Class[]{String.class},large.toString());require(chunks.size()>1,"large table is split into bounded rows");for(Object chunk:chunks)if(chunk.toString().contains("check-"))require(chunk.toString().startsWith("| Item | Result |\n| --- | --- |"),"continuation retains table header");
        JSONObject busy=new JSONObject(fixture.toString());busy.getJSONObject("session").put("requests",new JSONArray().put(new JSONObject().put("id","approval").put("tool","运行测试").put("input",new JSONObject().put("command","npm run test:auth"))));
        Object busyPage=call(pageType,"prepare",new Class[]{JSONObject.class},busy);
        ui(()->{set(cache,"page",busyPage);call(app,"renderSession",new Class[]{pageType,boolean.class},busyPage,false);});
        ui(()->{
            View permissions=(View)get(app,"permissionScroll"),composer=(View)get(app,"composer");require(permissions.isShown(),"approval is immediately visible");require(find(permissions,"允许本次")!=null&&find(permissions,"拒绝")!=null,"approval decisions present");
            int[] a=new int[2],b=new int[2];permissions.getLocationOnScreen(a);composer.getLocationOnScreen(b);require(a[1]+permissions.getHeight()<=b[1],"approval above composer");
            require(((EditText)composer).getText().toString().equals("保留我的草稿"),"updates preserve draft");
        });shot("approval");
        JSONObject details=new JSONObject(busy.toString()).put("steps",new JSONArray().put(new JSONObject().put("content","检查回归测试").put("status","completed"))).put("changes",new JSONObject().put("files",3).put("added",48).put("deleted",12));
        details.getJSONArray("messages").put(new JSONObject().put("id","patch").put("role","tool").put("kind","code").put("title","D:/Projects/product/src/a-very-long-directory-name/components/authentication/SessionConnectionController.java").put("language","diff").put("text","- stale\n+ refreshed"));
        Object detailPage=call(pageType,"prepare",new Class[]{JSONObject.class},details);
        ui(()->{set(cache,"page",detailPage);call(app,"renderSession",new Class[]{pageType,boolean.class},detailPage,false);set(app,"withdrawable","old message");call(app,"adaptComposer",new Class[]{});require(((View)get(app,"actionHint")).getVisibility()==View.GONE,"old recall hint does not stay above composer");});
        View tray=(View)get(app,"statusTray");require(tray.isShown()&&tray.getHeight()>0,"execution details has a real touchable height");
        ui(()->{TextView stats=(TextView)get(app,"statusTrayText");require((stats.getGravity()&Gravity.HORIZONTAL_GRAVITY_MASK)==Gravity.CENTER_HORIZONTAL,"change statistics centered");require(stats.getText().toString().contains("+48")&&stats.getText().toString().contains("−12"),"added and deleted counts shown");android.text.Spanned spans=(android.text.Spanned)stats.getText();android.text.style.ForegroundColorSpan[] colors=spans.getSpans(0,spans.length(),android.text.style.ForegroundColorSpan.class);Class<?> uiType=Class.forName("io.github.abyxs.codingremote.NativeUi");Field run=uiType.getDeclaredField("RUN"),red=uiType.getDeclaredField("RED");run.setAccessible(true);red.setAccessible(true);require(colors.length==2&&colors[0].getForegroundColor()==run.getInt(null)&&colors[1].getForegroundColor()==red.getInt(null),"additions green and deletions red");});
        tap(tray);SystemClock.sleep(300);require((Boolean)get(app,"statusOverlayExpanded")&&((View)get(app,"statusOverlay")).isShown()&&((View)get(app,"statusOverlay")).getHeight()>0,"real touch expands execution details");
        ui(()->{View send=(View)get(app,"sendButton");Rect visible=new Rect();require(send.getGlobalVisibleRect(visible)&&visible.height()==send.getHeight(),"expanded details keep the send button fully visible");require(!((View)get(app,"permissionScroll")).isShown(),"approval is not duplicated outside expanded details");});shot("execution-details");tap(tray);
        ui(()->{ListView list=(ListView)get(app,"messageList");android.widget.ListAdapter adapter=list.getAdapter();for(int i=0;i<adapter.getCount();i++){Object row=adapter.getItem(i);if("patch:0".equals(get(row,"id"))){View view=adapter.getView(i,null,list);TextView label=(TextView)find(view,"展开D:/Projects/product/src/a-very-long-directory-name/components/authentication/SessionConnectionController.java");require(label!=null&&label.getMaxLines()==1&&label.getEllipsize()==android.text.TextUtils.TruncateAt.MIDDLE,"file change path is single line with middle ellipsis");}}});
        // Returning to the cached page used to leave the panel detached from its dock.
        fixture.put("steps",details.getJSONArray("steps")).put("changes",details.getJSONObject("changes"));
        for(int n=0;n<3;n++){
            ui(()->{call(app,"showTab",new Class[]{String.class},"codex");call(app,"openSession",new Class[]{String.class,String.class},"fixture","检查会话同步");set(cache,"page",detailPage);call(app,"renderSession",new Class[]{pageType,boolean.class},detailPage,false);});
            View restored=(View)get(app,"statusTray");tap(restored);
            require((Boolean)get(app,"statusOverlayExpanded")&&((View)get(app,"statusOverlay")).isShown(),"execution details expand after cached page return");tap(restored);
        }
        tap((View)get(app,"composer"));
        for(int n=0;n<30&&!(Boolean)get(app,"keyboardShown");n++){ui(()->{EditText composer=(EditText)get(app,"composer");composer.requestFocus();((android.view.inputmethod.InputMethodManager)app.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(composer,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});SystemClock.sleep(100);}
        ui(()->{require((Boolean)get(app,"keyboardShown"),"real keyboard is open");EditText composer=(EditText)get(app,"composer");require(composer.getMinLines()==2,"typing keeps two lines of input");View decisions=find((View)get(app,"permissionScroll"),"允许本次");require(decisions!=null&&!decisions.isShown(),"typing compacts approval card");require(((View)get(app,"sendButton")).isShown(),"send stays visible with keyboard");});shot("keyboard");
        ui(()->((android.view.inputmethod.InputMethodManager)app.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(((View)get(app,"composer")).getWindowToken(),0));SystemClock.sleep(300);
        ui(()->{Object rows=get(page,"rows");Object user=((ArrayList<?>)rows).get(0);call(app,"messageActions",new Class[]{user.getClass()},user);});shot("message-actions");
        // The body layer is independent from phase/hop. Only expressions and cues animate.
        Class<?> pet=Class.forName("io.github.abyxs.codingremote.PetSprite");Constructor<?> constructor=pet.getDeclaredConstructor(String.class,int.class,int.class,int.class);constructor.setAccessible(true);Object sprite=constructor.newInstance("maltese",0xff1b2230,0xffffffff,128);
        call(sprite,"state",new Class[]{String.class},"idle");Bitmap first=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888),second=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
        call(sprite,"advance",new Class[]{float.class,float.class,float.class},0f,0f,0f);((android.graphics.drawable.Drawable)sprite).draw(new Canvas(first));call(sprite,"advance",new Class[]{float.class,float.class,float.class},.25f,0f,1f);((android.graphics.drawable.Drawable)sprite).draw(new Canvas(second));
        int moving=0,stable=0;for(int y=0;y<128;y++)for(int x=0;x<128;x++){if(first.getPixel(x,y)==second.getPixel(x,y))stable++;else moving++;}require(moving>0&&stable>128*128*.8,"local ear and paw frames animate while most of the body stays fixed");first.recycle();second.recycle();
        Class<?> overlay=Class.forName("io.github.abyxs.codingremote.StatusOverlay");require(call(overlay,"prose",new Class[]{String.class},"正在思考…").equals(""),"thinking placeholder filtered");
        result.putString("stream","PASS: native tables, escaped pipes, model values, icon send, visible approval, persistent draft, message sheet and stationary pet.\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}finally{if(app!=null)runOnMainSync(()->app.finish());}}
}
