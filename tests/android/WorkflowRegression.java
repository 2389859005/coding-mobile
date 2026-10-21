package io.github.abyxs.codingremote.tests;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;import android.view.accessibility.AccessibilityNodeInfo;import org.json.*;import java.lang.reflect.*;import java.util.*;

/** All API calls use synthetic data on a dedicated emulator. */
public final class WorkflowRegression extends Instrumentation {
    interface Check{void run()throws Exception;}Activity app;final ArrayList<String> reads=new ArrayList<>();final ArrayList<JSONObject> writes=new ArrayList<>();
    Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}Object call(Object o,String n,Class[] types,Object...args)throws Exception{Class<?> type=o instanceof Class?(Class)o:o.getClass();Method m=type.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o instanceof Class?null:o,args);}
    void require(boolean value,String why){if(!value)throw new AssertionError(why);}
    /** The fold control of an execution row: a compact Button inside the row's header line. */
    Button toggleOf(View outer){if(outer instanceof ViewGroup)for(int i=0;i<((ViewGroup)outer).getChildCount();i++){View child=((ViewGroup)outer).getChildAt(i);if(child instanceof Button)return (Button)child;if(child instanceof ViewGroup)for(int j=0;j<((ViewGroup)child).getChildCount();j++)if(((ViewGroup)child).getChildAt(j) instanceof Button)return (Button)((ViewGroup)child).getChildAt(j);}throw new IllegalStateException("no fold control in this row");}void ui(Check c)throws Exception{Throwable[] e={null};runOnMainSync(()->{try{c.run();}catch(Throwable x){e[0]=x;}});if(e[0]!=null)throw new Exception(e[0]);waitForIdleSync();}void await(Check c)throws Exception{Throwable last=null;for(int i=0;i<90;i++){try{c.run();return;}catch(Throwable e){last=e;}SystemClock.sleep(100);}throw new Exception(last);}
    View find(View v,String label){if(v instanceof TextView&&label.equals(((TextView)v).getText().toString())||label.equals(String.valueOf(v.getContentDescription())))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),label);if(found!=null)return found;}return null;}
    AccessibilityNodeInfo node(AccessibilityNodeInfo v,String label){if(v==null)return null;if(label.equals(String.valueOf(v.getText()))||label.equals(String.valueOf(v.getContentDescription())))return v;for(int i=0;i<v.getChildCount();i++){AccessibilityNodeInfo n=node(v.getChild(i),label);if(n!=null)return n;}return null;}
    void click(String label)throws Exception{await(()->ui(()->{View v=find(app.getWindow().getDecorView(),label);require(v!=null,"visible "+label);v.performClick();}));}
    /** A real pointer tap at the centre of a view, through the system's touch dispatch.
     *
     * performClick() calls the listener directly and never enters View.dispatchTouchEvent,
     * so it cannot see a broken TouchDelegate. Both of the crashes this guards against -
     * the statistics strip and an execution row's fold button - happened on the first real
     * finger, while every performClick()-based assertion stayed green.
     *
     * sendPointerSync refuses to run on the main thread, so the geometry is read there and
     * the events are injected from the instrumentation thread. */
    void tapView(View view,String what)throws Exception{
        final android.graphics.Rect r=new android.graphics.Rect();
        ui(()->view.getGlobalVisibleRect(r));
        require(!r.isEmpty(),"on screen: "+what);
        long t=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,r.exactCenterX(),r.exactCenterY(),0);sendPointerSync(down);down.recycle();
        SystemClock.sleep(70);
        MotionEvent up=MotionEvent.obtain(t,t+90,MotionEvent.ACTION_UP,r.exactCenterX(),r.exactCenterY(),0);sendPointerSync(up);up.recycle();
        waitForIdleSync();SystemClock.sleep(450);
        require(!app.isFinishing()&&!app.isDestroyed(),"still alive after a real tap on "+what);
    }
    /** The statistics strip: the bar inside its fixed-height holder. */
    View stripOf()throws Exception{LinearLayout panel=(LinearLayout)get(app,"taskPanel");return ((ViewGroup)panel.getChildAt(1)).getChildAt(0);}
    /** The fold button of the first execution row that is actually on screen.
     *
     * Adapter.getView builds a detached view, which has no visible rect and cannot be
     * tapped; the button has to come out of the live ListView. */
    View foldOfLiveRow()throws Exception{
        final View[] out={null};
        ui(()->{
            ListView list=(ListView)get(app,"messageList");
            for(int i=0;i<list.getChildCount();i++){
                View child=list.getChildAt(i);
                if(!(child instanceof ViewGroup))continue;
                try{out[0]=toggleOf((LinearLayout)child);return;}catch(IllegalStateException notThisRow){}
            }
        });
        return out[0];
    }
    void swipe(float x,float y,float endX,float endY)throws Exception{long start=SystemClock.uptimeMillis();for(int i=0;i<=16;i++){int action=i==0?0:i==16?1:2;MotionEvent e=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,x+(endX-x)*i/16f,y+(endY-y)*i/16f,0);sendPointerSync(e);e.recycle();SystemClock.sleep(18);}waitForIdleSync();}
    void shot(String name)throws Exception{SystemClock.sleep(350);android.graphics.Bitmap b=getUiAutomation().takeScreenshot();java.io.File d=new java.io.File(getTargetContext().getExternalFilesDir(null),"ui-captures");d.mkdirs();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(d,name+".png"))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
    public void onCreate(Bundle b){super.onCreate(b);getTargetContext().getSharedPreferences("ui",0).edit().putString("theme",b.getString("theme","light")).commit();start();}
    public void onStart(){Bundle result=new Bundle();try{
        Context c=getTargetContext();for(String name:new String[]{"coding-remote","pending","git-projects"})c.getSharedPreferences(name,0).edit().clear().commit();c.getSharedPreferences("ui",0).edit().putBoolean("overlay",false).putBoolean("notifications",false).putBoolean("resumeChat",false).commit();
        app=startActivitySync(new Intent().setClassName(c,"io.github.abyxs.codingremote.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        JSONArray messages=new JSONArray().put(new JSONObject().put("id","answer").put("role","assistant").put("text","长按直接显示操作菜单"));StringBuilder steps=new StringBuilder();for(int i=0;i<48;i++)steps.append("步骤 ").append(i).append("：检查当前处理进度\n");
        messages.put(new JSONObject().put("id","s1").put("role","activity").put("kind","step").put("title","检查项目").put("text",steps.toString())).put(new JSONObject().put("id","s2").put("role","activity").put("kind","step").put("title","运行测试").put("text","最后一个步骤"));
        messages.put(new JSONObject().put("id","image").put("role","activity").put("kind","image").put("title","imageview").put("text","图片单独一行"));
        JSONObject fixture=new JSONObject().put("connected",true).put("session",new JSONObject().put("id","fixture").put("title","交互测试").put("cwd","D:/fixture").put("model","model-a").put("status","idle").put("requests",new JSONArray())).put("messages",messages).put("changes",new JSONObject().put("files",1).put("added",12).put("deleted",3));
        JSONObject repo=new JSONObject().put("projectKey","same-project").put("root","D:/fixture").put("branch","main").put("upstream","origin/main").put("ahead",1).put("behind",0).put("branches",new JSONArray().put("main").put("feature"));JSONArray files=new JSONArray().put(new JSONObject().put("path","src/Main.java").put("index"," ").put("worktree","M"));repo.put("files",files);JSONArray history=new JSONArray();for(int i=0;i<10;i++)history.put(new JSONObject().put("hash","abcdef012345"+i).put("parents",new JSONArray().put("abcdef012345"+(i+1))).put("subject",i==0?"修复移动端模型选择":"提交记录 "+i).put("author","Fixture").put("refs",i==0?"HEAD -> main":""));repo.put("commits",history);
        Class<?> transport=Class.forName("io.github.abyxs.codingremote.MainActivity$Transport");Object mock=Proxy.newProxyInstance(transport.getClassLoader(),new Class[]{transport},(o,m,a)->{String raw=(String)a[2],path=raw.split("\\?",2)[0];JSONObject body=(JSONObject)a[3];reads.add(raw);if(body!=null)writes.add(new JSONObject(body.toString()).put("route",path));if(path.endsWith("/catalog")){SystemClock.sleep(550);return new JSONObject().put("models",new JSONArray().put(new JSONObject().put("id","model-a").put("name","Model A").put("efforts",new JSONArray()))).put("skills",new JSONArray()).put("currentModel","model-a");}if(path.endsWith("/git")){if(body!=null){if(body.optString("operation").equals("stage")){files.getJSONObject(0).put("index","M").put("worktree"," ");}return new JSONObject().put("status","accepted").put("message","完成");}if(raw.contains("?path="))return new JSONObject().put("diff","+added\n-removed");return new JSONObject(repo.toString());}if(body!=null)return new JSONObject().put("status","accepted");return new JSONObject(fixture.toString());});
        ui(()->{set(app,"transport",mock);set(app,"origin","https://fixture.trycloudflare.com");set(app,"token","fixture");set(app,"backend","claude");call(app,"openSession",new Class[]{String.class,String.class},"fixture","交互测试");});SystemClock.sleep(750);
        int[] transcriptHeight={0};ui(()->{transcriptHeight[0]=((View)get(app,"messageList")).getHeight();call(app,"toggleCatalog",new Class[]{String.class},"model");require(((View)get(app,"inlineOptions")).getVisibility()==View.VISIBLE,"loading menu immediately visible");});await(()->ui(()->require(find((View)get(app,"inlineOptions"),"Model A")!=null,"model list loaded")));ui(()->{require(((View)get(app,"messageList")).getHeight()==transcriptHeight[0],"options overlay does not shrink transcript");require(((View)get(app,"inlineOptions")).getParent() instanceof FrameLayout,"options are an overlay");});require(reads.stream().anyMatch(s->s.endsWith("/catalog?section=models")),"models requested separately from plugins");shot("209-options-overlay");ui(()->call(app,"closeOptions",new Class[]{}));
        ui(()->{Object adapter=get(app,"messageAdapter");ArrayList<?> rows=(ArrayList<?>)get(adapter,"rows");require(rows.size()==3,"image excluded from execution group");require(get(rows.get(2),"kind").equals("image"),"image remains own row");LinearLayout taskPanel=(LinearLayout)get(app,"taskPanel");LinearLayout barHolder=(LinearLayout)taskPanel.getChildAt(1);LinearLayout bar=(LinearLayout)barHolder.getChildAt(0);android.text.Spanned text=(android.text.Spanned)((TextView)bar.getChildAt(1)).getText();android.text.style.ForegroundColorSpan[] colors=text.getSpans(0,text.length(),android.text.style.ForegroundColorSpan.class);
            // The chip colours are derived from the active theme, so assert them against the
            // palette rather than against the GitHub hex values they used to be hard-coded to.
            Class<?> paletteType=Class.forName("io.github.abyxs.codingremote.NativeUi");java.lang.reflect.Field greenField=paletteType.getDeclaredField("GREEN"),redField=paletteType.getDeclaredField("RED");greenField.setAccessible(true);redField.setAccessible(true);int green=(Integer)greenField.get(null),red=(Integer)redField.get(null);
            require(colors.length==2,"additions and deletions each carry their own colour, got "+colors.length);require(colors[0].getForegroundColor()==green&&colors[1].getForegroundColor()==red,"green additions red deletions, got "+Integer.toHexString(colors[0].getForegroundColor())+"/"+Integer.toHexString(colors[1].getForegroundColor()));((ListView)get(app,"messageList")).setSelection(0);});SystemClock.sleep(300);
        // A finger on the statistics strip, twice: open and close. The strip's TouchDelegate
        // used to forward into its own View, and the recursion took the process down with a
        // StackOverflowError the moment it was touched.
        final boolean[] expanded={false};
        ui(()->{LinearLayout panel=(LinearLayout)get(app,"taskPanel");require(panel.getChildCount()>1,"statistics strip built");expanded[0]=(Boolean)get(app,"taskExpanded");});
        tapView(stripOf(),"statistics strip");
        ui(()->require((Boolean)get(app,"taskExpanded")!=expanded[0],"a real tap on the statistics strip toggles the panel"));
        tapView(stripOf(),"statistics strip");
        ui(()->require((Boolean)get(app,"taskExpanded")==expanded[0],"a second real tap closes it again"));
        // And a finger on an execution row's fold button, which had the same delegate.
        final View fold=foldOfLiveRow();
        require(fold!=null,"an execution row with a fold button is on screen");
        tapView(fold,"an execution row fold button");

        ui(()->{View text=find((View)get(app,"messageList"),"长按直接显示操作菜单");require(text!=null,"message visible");text.performLongClick();});await(()->require(node(getUiAutomation().getRootInActiveWindow(),"复制全文")!=null&&node(getUiAutomation().getRootInActiveWindow(),"选择部分文字")!=null,"long press directly shows actions"));sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        ui(()->{Object adapter=get(app,"messageAdapter");ArrayList<?> rows=(ArrayList<?>)get(adapter,"rows");Object group=rows.get(1);Map<?,?> caches=(Map<?,?>)get(app,"chatCaches");Object cache=caches.get(get(app,"sessionKey"));((Set)get(cache,"expanded")).add(get(group,"id"));((BaseAdapter)adapter).notifyDataSetChanged();((ListView)get(app,"messageList")).setSelection(1);});SystemClock.sleep(350);
        final ScrollView[] inner={null};ui(()->{ListView list=(ListView)get(app,"messageList");inner[0]=findScroll(list);require(inner[0]!=null,"execution group has scroll viewport");});int[] at=new int[2];ui(()->inner[0].getLocationOnScreen(at));swipe(at[0]+70,at[1]+inner[0].getHeight()-30,at[0]+70,at[1]+35);ui(()->require(inner[0].getScrollY()>0,"expanded execution process consumes scroll"));shot("209-execution-scroll");
        ui(()->{Object adapter=get(app,"messageAdapter");Object cache=((Map)get(app,"chatCaches")).get(get(app,"sessionKey"));set(cache,"running",Boolean.TRUE);ListView list=(ListView)get(app,"messageList");LinearLayout image=(LinearLayout)((BaseAdapter)adapter).getView(2,null,list);require(image.getChildCount()>1,"running image step auto expands");toggleOf(image).performClick();image=(LinearLayout)((BaseAdapter)adapter).getView(2,null,list);require(image.getChildCount()==1,"running image step can be manually collapsed");image=(LinearLayout)((BaseAdapter)adapter).getView(2,null,list);require(image.getChildCount()==1,"refresh does not reopen manually collapsed image");toggleOf(image).performClick();image=(LinearLayout)((BaseAdapter)adapter).getView(2,null,list);require(image.getChildCount()>1,"image can expand again");toggleOf(image).performClick();set(cache,"running",Boolean.FALSE);});
        int width=app.getResources().getDisplayMetrics().widthPixels;swipe(35,350,width*.7f,350);await(()->ui(()->require(get(app,"gitPage")!=null,"right swipe opens Git")));await(()->ui(()->require(find((View)get(app,"gitPage"),"源代码管理 · main")!=null,"Git repository loaded")));shot("209-git");click("暂存全部");await(()->require(writes.stream().anyMatch(v->v.optString("operation").equals("stage")),"stage command reaches scoped Git route"));await(()->ui(()->require(find((View)get(app,"gitPage"),"取消暂存 src/Main.java")!=null,"file moves to staged group")));
        ui(()->{Object git=get(app,"gitPage");((EditText)get(git,"message")).setText("同项目共享的提交草稿");call(app,"closeGit",new Class[]{});call(app,"openSession",new Class[]{String.class,String.class},"other-session","相同项目的会话");call(app,"openGit",new Class[]{});});await(()->ui(()->{Object git=get(app,"gitPage");require(((EditText)get(git,"message")).getText().toString().equals("同项目共享的提交草稿"),"same repository shares Git page state across sessions");}));swipe(width*.7f,350,35,350);await(()->ui(()->require(get(app,"gitPage")==null,"left swipe returns to conversation")));
        result.putString("stream","PASS: overlay menus with loading, isolated model catalog, direct message actions, green/red statistics, independent image row, real nested scrolling, swipe Git navigation, staged changes, commit graph and repository-shared state.\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}finally{try{if(app!=null)ui(()->app.finish());}catch(Exception ignored){}}}
    ScrollView findScroll(View v){if(v instanceof ScrollView)return (ScrollView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){ScrollView found=findScroll(((ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null;}
}
