package io.github.abyxs.codingremote;
import android.view.MotionEvent;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import android.view.ViewGroup;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.inputmethod.InputMethodManager;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native Android UI. No WebView, HTML page, login password, or fixed domain UI. */
public final class MainActivity extends Activity {
    private int INK, MUTED, GREEN, BG;
    private String settingsReturn="claude",activeQuestion="",lastDesktopKey="";
    private TextView sessionTitle,effortLabel,pluginLabel;
    private FrameLayout stage; private View optionsDismiss,optionAnchor; private LinearLayout inlineOptions; private String expandedOption=""; private int navigationVersion=0; private LinearLayout taskPanel; private FloatingNavigation floating;
    private boolean scrolling=false,taskExpanded=false; private Boolean requestedHeader=null;
    private SessionPage deferredPage; private String permissionRevision="",taskRevision="";
    private Dialog questionDialog;
    private PopupWindow applicationMenu;
    private GitPage gitPage; private final Map<String,GitPage.State> gitStates=new HashMap<>(); private boolean conversationSwipe,conversationCaptured;
    /** Set once per conversation so the floating handle is shown on arrival, then
     * governed by scrolling alone. */
    private boolean navigationShown;
    private LinearLayout queuePanel;private JSONArray queuedMessages=new JSONArray();private boolean queueExpanded=false;
    private int pollTicket=0,historyTicket=0;
    private final java.util.concurrent.ConcurrentHashMap<Thread,HttpURLConnection> liveConnections=new java.util.concurrent.ConcurrentHashMap<>();
    private final ExecutorService liveWorkers=Executors.newFixedThreadPool(2,r->new Thread(r,"conversation-live"));
    /** Paging back through history is a user-initiated action, so it must not queue
     * behind the 15-second poll. Sharing liveWorkers meant a slow poll could hold
     * both threads and "查看更早的消息" waited for the next poll to finish before its
     * request was even sent. One dedicated thread is enough: the button is disabled
     * while a page is in flight. */
    private final ExecutorService historyWorkers=Executors.newSingleThreadExecutor(r->new Thread(r,"conversation-history"));
    private NativeUi ui;
    private LinearLayout appBar,historyBar,emptyState;
    /** The tab bar and its surface. The shell owns the visibility, because hiding
     * only the inner row left a blank white strip above the content. */
    private LinearLayout bottomShell;
    private TextView connectionLabel,recordCount,modelLabel,emptyTitle,emptyDetail;
    private long appStatusUntil;private String connectionText="连接中";private boolean connectionGood=false;
    private Button projectButton,groupButton,refreshButton;
    private EditText search;
    private boolean followingLatest=true;
    private boolean keyboardShown=false,hasEarlier=false;
    private LinearLayout chatHeader,attachmentRow;
    private boolean headerHidden=false;
    private String selectedModel=""; private TextView accessLabel; private int catalogTicket;
    private final Runnable revealHeader=()->{if(!this.paused&&!this.scrolling&&this.chatHeader!=null)setHeaderHidden(false);};
    private FastScroller fastScroller;
    private String pickerKey="";
    private boolean uploading=false;
    private NativeUi.DockScroll composerScroll;
    private Button attachButton;
    /** Folds the chip row under the input box; the caret mirrors the folded state. */
    private Button foldButton;
    private View optionsRow;
    private boolean composerCollapsed=false;
    /** Guards against a strip rebuild re-entering while one is already on the stack. */
    private boolean renderingTasks=false;
    private Button actionHint;
    private int permissionCount=0;
    private String chatPath="";
    private final Handler handler=new Handler();
    private final ExecutorService workers=Executors.newFixedThreadPool(3);
    private LinearLayout root,screen,bottom,chatMessages,permissions,pendingPanel;
    private TextView status,chatMeta;
    private EditText composer;
    private Button sendButton;
    private ListView messageList,sessionList;
    private ScrollView permissionScroll;
    private MessageAdapter messageAdapter;
    private SessionAdapter sessionAdapter;
    private Button earlierButton,latestButton;
    private String displayedListKey=null,sessionKey=null;
    private Integer pageBefore=null;
    private boolean historyLoading=false;
    private long identityClock;private final Map<String,Long> identityVersions=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,ListCache> listCaches=new java.util.concurrent.ConcurrentHashMap<>();
    // Accessed from the UI thread and from the worker pools, so a plain
    // LinkedHashMap was unsafe: access-order get() rewrites the internal list and
    // a resize racing with the reader could corrupt or throw.
    private final Map<String,ChatCache> chatCaches=java.util.Collections.synchronizedMap(new LinkedHashMap<String,ChatCache>(8,.75f,false){
        protected boolean removeEldestEntry(Map.Entry<String,ChatCache> e){return size()>6;}
    });
    private static final class ListCache { JSONArray rows=new JSONArray(); long loaded=0; boolean loading=false,grouped=true,connected=false; int first=0,top=0; String error="",query="",project="",notice=""; final java.util.Set<String> collapsed=java.util.concurrent.ConcurrentHashMap.newKeySet(); }
    private static final class ChatCache { SessionPage page; int first=-1,top=0; String title="",cwd="",project="",draft=""; boolean followingLatest=true; Boolean running=null; String completed=""; JSONObject catalog; final java.util.Set<String> plugins=java.util.concurrent.ConcurrentHashMap.newKeySet(); final java.util.List<JSONObject> images=java.util.Collections.synchronizedList(new ArrayList<JSONObject>()); final java.util.Set<String> expanded=java.util.concurrent.ConcurrentHashMap.newKeySet(),collapsed=java.util.concurrent.ConcurrentHashMap.newKeySet(); }

    private String origin="",token="",backend="claude",sessionId=null,lastMessages="";
    private JSONObject pending=null;
    private int epoch=0;
    private boolean paused=false,sending=false;
    /** Text of the last message sent, kept so a stopped task can be re-edited. */
    private String withdrawable="";
    private long lastBack=0;
    private Toast lastToast;
    private int pollEpoch=-1;
    private Runnable nextPoll;
    private interface Done { void accept(JSONObject value) throws Exception; }
    private interface Work { JSONObject run() throws Exception; }
    private interface Transport {JSONObject request(String address,String credential,String path,JSONObject body)throws Exception;}
    private Transport transport=this::networkRequest;
    private static final class ApiError extends ConnectionProfiles.HttpFailure {ApiError(int c,String m){super(c,m,c==401||c>=500||(c==403&&(m.equals("不允许的入口")||m.equals("不允许的来源"))));} }

    private int dp(int x){return (int)(getResources().getDisplayMetrics().density*x+.5f);}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(1);return v;}
    private TextView text(String label,int size,int color){return ui.text(label,size,color);}
    private TextView title(String label){TextView v=text(label,NativeUi.HEADING,INK);NativeUi.emphasize(v);return v;}
    private Button button(String label,boolean primary){return ui.button(label,primary);}
    private ImageView logo(int size){ImageView image=new ImageView(this);image.setImageResource(ui.res("brand","drawable",android.R.drawable.sym_def_app_icon));image.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));
        // The brand mark repeats the wordmark next to it, so it must not be read out.
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return image;}
    private void add(LinearLayout parent,View child,int height){parent.addView(child,new LinearLayout.LayoutParams(-1,height<0?height:dp(height)));}
    private void gap(LinearLayout parent,int h){View v=new View(this);add(parent,v,h);}
    /**
     * The shared empty state: a muted glyph, a line of what is missing, and an
     * optional hint on what to do about it.
     *
     * Every list used to say "暂无…" as a bare grey line at whatever size the call
     * site happened to pass, so an empty archive, an empty search and an empty task
     * list all looked like a rendering glitch. One shape for all of them makes
     * "nothing here" read as a state rather than as a failure.
     */
    private View emptyState(String icon,String message,String hint){
        LinearLayout box=column();box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24),dp(40),dp(24),dp(40));
        ImageView glyph=ui.icon(icon,NativeUi.LINE,dp(40));
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams glyphLp=new LinearLayout.LayoutParams(dp(40),dp(40));
        glyphLp.bottomMargin=dp(14);box.addView(glyph,glyphLp);
        TextView title=text(message,NativeUi.BODY,MUTED);title.setGravity(Gravity.CENTER);box.addView(title,-2);
        if(hint!=null&&!hint.isEmpty()){
            TextView sub=text(hint,NativeUi.LABEL,MUTED);sub.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams subLp=new LinearLayout.LayoutParams(-2,-2);
            subLp.topMargin=dp(NativeUi.SPACE_INLINE);sub.setAlpha(.8f);box.addView(sub,subLp);
        }
        box.setContentDescription(message+(hint==null||hint.isEmpty()?"":"，"+hint));
        return box;
    }
    private void toast(String value){if(lastToast!=null)lastToast.cancel();lastToast=Toast.makeText(this,value,Toast.LENGTH_LONG);lastToast.show();}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    // NativeUi.LINE sat at 1.15-1.24:1 against the canvas, which is below the point
    // where a 1px rule reads as a divider at all. Blend it toward the ink instead.
    private void rule(LinearLayout parent){rule(parent,NativeUi.CANVAS);}
    /** Divider on an explicit surface. The one-argument form assumes the canvas,
     * which is right for page chrome but wrong inside a card - the strength of the
     * hairline depends on what it is drawn against. */
    private void rule(LinearLayout parent,int surface){View v=new View(this);v.setBackgroundColor(NativeUi.rule(surface));add(parent,v,1);}
    private void inset(View v,int x,int y){v.setPadding(dp(x),dp(y),dp(x),dp(y));}
    private void medium(TextView v){NativeUi.emphasize(v);}
    /** Turns a full-size button into an inline one.
     *
     * button() pins a 48dp minimum height and 48dp minimum touch padding for a real
     * button; an inline toggle that has to be one text line tall has to clear both, or
     * the label gets centred inside 48dp and clips against the padding. */
    private void compact(Button b){b.setBackground(ui.touch(Color.TRANSPARENT,NativeUi.CORNER_CHIP,false));b.setTextColor(GREEN);b.setMinHeight(0);b.setMinimumHeight(0);b.setIncludeFontPadding(false);}
    private void single(TextView v){v.setSingleLine();v.setEllipsize(TextUtils.TruncateAt.END);}
    private String projectName(String path){if(path==null||path.isEmpty())return "未指定项目";String p=path.replace('\\','/').replaceAll("/+$","");if(p.isEmpty())return "未指定项目";int i=p.lastIndexOf('/');String name=i>=0?p.substring(i+1):p;return name.isEmpty()?"未指定项目":name;}
    /** Claude is grouped by the project a session came from, not by its current directory. */
    private String groupPath(JSONObject row){String project=row.optString("projectCwd","");return project.isEmpty()?row.optString("cwd",""):project;}
    private void brandBar(){
        appBar.removeAllViews();appBar.setVisibility(View.VISIBLE);inset(appBar,24,8);appBar.addView(logo(28));
        TextView brand=text("CODING  /  MOBILE",NativeUi.CAPTION,INK);brand.setLetterSpacing(.08f);medium(brand);brand.setPadding(dp(10),0,0,0);appBar.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        if(token.isEmpty()){Button scan=ui.iconButton("scan","扫描配对二维码");appBar.addView(scan,new LinearLayout.LayoutParams(dp(48),dp(48)));scan.setOnClickListener(v->scan());}
        else{Button settings=ui.iconButton("settings","设置");appBar.addView(settings,new LinearLayout.LayoutParams(dp(48),dp(48)));settings.setOnClickListener(v->openSettings());}
    }
    private void connection(String label,boolean good){connectionText=label;connectionGood=good;if(connectionLabel!=null&&System.currentTimeMillis()>=appStatusUntil){connectionLabel.setText("●  "+label);connectionLabel.setTextColor(good?GREEN:MUTED);}}
    private TextWatcher changed(Runnable change){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){change.run();}public void afterTextChanged(Editable e){}};}

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);String theme=getSharedPreferences("ui",0).getString("theme","light");if(theme.equals("warm")){theme="green";getSharedPreferences("ui",0).edit().putString("theme",theme).apply();}NativeUi.theme(theme);if((theme.equals("dark")||theme.equals("midnight")))setTheme(android.R.style.Theme_Material_NoActionBar);INK=NativeUi.INK;MUTED=NativeUi.MUTED;GREEN=NativeUi.GREEN;BG=NativeUi.CANVAS;ui=new NativeUi(this);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility((theme.equals("dark")||theme.equals("midnight"))?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        root=column();root.setFocusableInTouchMode(true);root.setBackgroundColor(BG);setContentView(root);
        if(Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(false);root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());v.setPadding(b.left,b.top,b.right,Math.max(b.bottom,ime.bottom));boolean opening=!keyboardShown&&ime.bottom>b.bottom;keyboardShown=ime.bottom>b.bottom;
            adaptComposer();
            if(opening&&composerScroll!=null){final NativeUi.DockScroll dock=composerScroll;dock.post(()->dock.fullScroll(View.FOCUS_DOWN));}return insets;});}
        else{
            // Below API 30 there is no WindowInsets.Type.ime(), so the flag was never
            // set and the composer kept all of its rows while the soft keyboard
            // covered them. Infer it from the visible display frame instead.
            final View decor=getWindow().getDecorView();
            decor.getViewTreeObserver().addOnGlobalLayoutListener(()->{
                android.graphics.Rect visible=new android.graphics.Rect();decor.getWindowVisibleDisplayFrame(visible);
                int hidden=decor.getRootView().getHeight()-visible.bottom;boolean shown=hidden>decor.getRootView().getHeight()/4;
                if(shown!=keyboardShown){keyboardShown=shown;adaptComposer();}
            });
        }
        appBar=row();add(root,appBar,NativeUi.BAR);brandBar();
        stage=new FrameLayout(this);screen=column();stage.addView(screen,new FrameLayout.LayoutParams(-1,-1));root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        // The tab bar gets its own surface. Sharing the canvas colour meant scrolling
        // content ran straight into the tabs with nothing between them, so the bar
        // read as part of the list rather than as chrome.
        bottomShell=column();bottomShell.setBackgroundColor(NativeUi.WHITE);
        // rule(), not LINE: this divider sits on the bar's own white surface, and
        // LINE is tuned to sit against the canvas. Every other divider in the app
        // already asks rule() for the value, so using the raw token here made the
        // one line under the tab bar the faintest in the interface.
        View barRule=new View(this);barRule.setBackgroundColor(NativeUi.rule(NativeUi.WHITE));
        bottomShell.addView(barRule,new LinearLayout.LayoutParams(-1,dp(1)));
        bottom=new LinearLayout(this);bottom.setBackgroundColor(NativeUi.WHITE);bottom.setPadding(dp(12),dp(4),dp(12),dp(4));
        bottomShell.addView(bottom,new LinearLayout.LayoutParams(-1,0,1f));
        add(root,bottomShell,76);
        try{String stored=SecureStore.read(this);if(!stored.isEmpty()){JSONObject d=new JSONObject(stored);origin=d.getString("origin");token=d.getString("token");}}
        catch(Exception e){handler.postDelayed(this::retrySavedConnection,1500);}
        try{String p=getSharedPreferences("pending",0).getString("request","");if(!p.isEmpty())pending=new JSONObject(p);}catch(Exception ignored){}
        if(token.isEmpty())home();else if(getSharedPreferences("ui",0).getBoolean("resumeChat",false)&&!getSharedPreferences("ui",0).getString("lastSession","").isEmpty()){backend=getSharedPreferences("ui",0).getString("lastBackend",firstTab());String id=getSharedPreferences("ui",0).getString("lastSession","");lastDesktopKey=backend+":"+id;openSession(id,getSharedPreferences("ui",0).getString("lastTitle","会话"));}else showTab(getSharedPreferences("ui",0).getString("backend",firstTab()));
        handler.post(new Runnable(){int tick=0;public void run(){if(isDestroyed())return;ChatCache c=chatCaches.get(sessionKey);
            // The animated dots go through connection() rather than writing the label
            // directly. Writing it directly produced a string without the status dot
            // and without the connected/disconnected colour, so every poll tick
            // flickered between two different-looking labels.
            if(!paused&&c!=null&&Boolean.TRUE.equals(c.running)){String[] dots={".","..","..."};connection("正在思考"+dots[tick++%3],true);}
            handler.postDelayed(this,650);}});
        handleNotification(getIntent());startNotifications();
    }

    private void retrySavedConnection(){
        if(isDestroyed()||!token.isEmpty())return;
        if(paused){handler.postDelayed(this::retrySavedConnection,1500);return;}
        try{
            JSONObject saved=ConnectionProfiles.read(this);if(!saved.has("token"))return;
            origin=saved.getString("origin");token=saved.getString("token");showTab(firstTab());startNotifications();
        }catch(Exception error){
            // Keystore can be temporarily unavailable at unlock/startup. Never
            // erase a permanent binding because one read failed.
            if(status!=null)status.setText("正在恢复已保存的设备连接…");
            handler.postDelayed(this::retrySavedConnection,3000);
        }
    }

    private void reset(){
        closeGit();
        if(optionsDismiss!=null){stage.removeView(optionsDismiss);optionsDismiss=null;}if(inlineOptions!=null)stage.removeView(inlineOptions);optionAnchor=null;if(applicationMenu!=null){applicationMenu.dismiss();applicationMenu=null;}queuePanel=null;queuedMessages=new JSONArray();queueExpanded=false;
        if(displayedListKey!=null&&sessionList!=null){ListCache c=listCaches.get(displayedListKey);if(c!=null){c.first=sessionList.getFirstVisiblePosition();c.top=sessionList.getChildCount()>0?sessionList.getChildAt(0).getTop():0;}}
        if(sessionKey!=null){ChatCache c=chatCaches.get(sessionKey);if(c!=null){if(composer!=null)c.draft=composer.getText().toString();if(messageList!=null&&pageBefore==null){c.first=messageList.getFirstVisiblePosition();c.top=messageList.getChildCount()>0?messageList.getChildAt(0).getTop()-messageList.getPaddingTop():0;c.followingLatest=followingLatest;}}}
        navigationVersion++;selectingText=false;catalogTicket++;selectedModel="";inlineOptions=null;expandedOption="";epoch++;pollTicket++;historyTicket++;pollEpoch=-1;for(HttpURLConnection active:liveConnections.values())active.disconnect();permissionRevision="";taskRevision="";scrolling=false;deferredPage=null;requestedHeader=null;if(questionDialog!=null){questionDialog.dismiss();questionDialog=null;}activeQuestion="";if(nextPoll!=null)handler.removeCallbacks(nextPoll);nextPoll=null;sessionId=null;sessionKey=null;displayedListKey=null;pageBefore=null;historyLoading=false;lastMessages="";
        if(fileBrowser!=null){fileBrowser.close();fileBrowser=null;}fileBrowserKey="";sessionSignature="";withdrawable="";
        // The image picker returns through onActivityResult long after reset(); a
        // stale key would silently drop the user's selection.
        pickerKey="";uploading=false;
        pendingPanel=null;sendButton=null;status=null;composer=null;sessionList=null;sessionAdapter=null;messageList=null;messageAdapter=null;permissions=null;
        appStatusUntil=0;connectionLabel=null;search=null;historyBar=null;emptyState=null;composerScroll=null;actionHint=null;permissionCount=0;followingLatest=true;chatPath="";hasEarlier=false;
        chatHeader=null;attachmentRow=null;fastScroller=null;headerHidden=false;floating=null;taskPanel=null;sessionTitle=null;
        // The composer's fold state is per-session chrome; leaving the stale references
        // behind let a later toggle touch a view from the previous session's tree.
        foldButton=null;optionsRow=null;renderingTasks=false;
        // pageSettling must be cleared here, not only from the animation's end action:
        // the cancel() below (and Activity teardown) can drop that callback, and a
        // stranded flag makes dispatchTouchEvent swallow every touch until restart.
        pageSettling=false;swipeCaptured=false;pageSwipe=false;conversationCaptured=false;conversationSwipe=false;navigationShown=false;
        screen.animate().cancel();screen.animate().withEndAction(null);screen.setTranslationX(0);screen.setAlpha(0);screen.removeAllViews();screen.animate().alpha(1).setDuration(160).start();root.requestFocus();screen.setPadding(0,0,0,0);root.setBackgroundColor(BG);getWindow().setNavigationBarColor(BG);getWindow().setStatusBarColor(BG);brandBar();
    }
    private void openGit(){if(sessionId==null||gitPage!=null)return;closeOptions();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);root.requestFocus();final int generation=epoch;final String address=origin,credential=token,route="/api/native/"+backend+"/"+sessionId+"/git";gitPage=new GitPage(this,ui,new GitPage.Host(){public void back(){closeGit();}public void request(String query,JSONObject body,GitPage.Reply reply){workers.execute(()->{try{JSONObject value=MainActivity.this.request(address,credential,route+query,body);runOnUiThread(()->{if(generation==epoch&&gitPage!=null)reply.done(value,null);});}catch(Exception error){runOnUiThread(()->{if(generation==epoch&&gitPage!=null)reply.done(null,errorMessage(error));});}});}},gitStates,address);stage.addView(gitPage,new FrameLayout.LayoutParams(-1,-1));gitPage.setTranslationX(-Math.max(stage.getWidth(),dp(320)));gitPage.animate().translationX(0).setDuration(200).start();}
    private void closeGit(){if(gitPage!=null){gitPage.dispose();stage.removeView(gitPage);gitPage=null;}}
    private boolean horizontalAt(View v,float x,float y){if(v.getVisibility()!=View.VISIBLE)return false;int[] point=new int[2];v.getLocationOnScreen(point);if(x<point[0]||x>point[0]+v.getWidth()||y<point[1]||y>point[1]+v.getHeight())return false;if(v instanceof HorizontalScrollView)return true;if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++)if(horizontalAt(group.getChildAt(i),x,y))return true;}return false;}
    private float swipeX,swipeY;private boolean pageSwipe,swipeCaptured,pageSettling;
    private String adjacentPage(float dx){ArrayList<String> tabs=new ArrayList<>();for(String key:new String[]{"claude","codex","deepseek"})if(getSharedPreferences("ui",0).getBoolean("show_"+key,true))tabs.add(key);int next=tabs.indexOf(backend)+(dx<0?1:-1);return next>=0&&next<tabs.size()?tabs.get(next):null;}
    private void settlePage(float dx,boolean commit){
        String next=commit?adjacentPage(dx):null;pageSettling=true;screen.animate().cancel();
        if(next==null){screen.animate().translationX(0).alpha(1).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).withEndAction(()->pageSettling=false).start();return;}
        int direction=dx<0?-1:1;float width=screen.getWidth();long duration=Math.max(100,Math.round(200*(1-Math.min(1,Math.abs(dx)/width))));
        screen.animate().translationX(direction*width).setDuration(duration).setInterpolator(new android.view.animation.DecelerateInterpolator()).withEndAction(()->{
            // showTab() runs reset(), which already cancels animations and clears the
            // settling flag; re-entering the page here only has to lay out the slide-in.
            showTab(next);screen.setAlpha(1);screen.setTranslationX(-direction*width);
            screen.animate().translationX(0).setDuration(210).setInterpolator(new android.view.animation.DecelerateInterpolator()).withEndAction(()->pageSettling=false).start();
        }).start();
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        int action=event.getActionMasked();if(pageSettling)return true;
        if(action==MotionEvent.ACTION_DOWN){swipeX=event.getX();swipeY=event.getY();conversationCaptured=false;conversationSwipe=sessionId!=null&&!keyboardShown&&!selectingText&&expandedOption.isEmpty()&&!horizontalAt(gitPage==null?screen:gitPage,event.getRawX(),event.getRawY());if(conversationSwipe&&gitPage==null&&composerScroll!=null){int[] p=new int[2];composerScroll.getLocationOnScreen(p);conversationSwipe=event.getRawY()<p[1];}}
        if(conversationSwipe){float move=event.getX()-swipeX,vertical=event.getY()-swipeY;if(action==MotionEvent.ACTION_MOVE&&!conversationCaptured){if(Math.abs(vertical)>dp(16)&&Math.abs(vertical)>Math.abs(move))conversationSwipe=false;else if(Math.abs(move)>dp(28)&&Math.abs(move)>Math.abs(vertical)*1.7f&&(gitPage==null?move>0:move<0)){conversationCaptured=true;MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);super.dispatchTouchEvent(cancel);cancel.recycle();}}if(conversationCaptured){if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){conversationCaptured=false;conversationSwipe=false;if(action==MotionEvent.ACTION_UP&&Math.abs(move)>dp(72)){if(gitPage==null)openGit();else closeGit();}}return true;}}

        if(action==MotionEvent.ACTION_DOWN){swipeX=event.getX();swipeY=event.getY();swipeCaptured=false;pageSwipe=sessionId==null&&displayedListKey!=null&&!keyboardShown;}
        float dx=event.getX()-swipeX,dy=event.getY()-swipeY;
        if(action==MotionEvent.ACTION_MOVE&&pageSwipe&&!swipeCaptured){
            if(Math.abs(dy)>dp(12)&&Math.abs(dy)>Math.abs(dx))pageSwipe=false;
            else if(Math.abs(dx)>dp(12)&&Math.abs(dx)>Math.abs(dy)*1.5f){swipeCaptured=true;MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);super.dispatchTouchEvent(cancel);cancel.recycle();screen.animate().cancel();}
        }
        if(swipeCaptured){
            if(action==MotionEvent.ACTION_MOVE){screen.setTranslationX(adjacentPage(dx)==null?dx*.18f:dx);return true;}
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){swipeCaptured=false;pageSwipe=false;settlePage(dx,action==MotionEvent.ACTION_UP&&Math.abs(dx)>Math.max(dp(56),screen.getWidth()*.18f));return true;}
            return true;
        }
        return super.dispatchTouchEvent(event);
    }
    private void clearCaches(){listCaches.clear();chatCaches.clear();identityVersions.clear();writtenRevisions.clear();ConversationCache.clear(this);}
    /** Shows or hides the tab bar together with the surface behind it. */
    private void bars(int visibility){
        if(bottomShell!=null)bottomShell.setVisibility(visibility);
        bottom.setVisibility(visibility);
    }
    private void navigation(){
        bottom.removeAllViews();bars(View.VISIBLE);
        String[] keys={"claude","codex","deepseek"},names={"Claude","Codex","DeepSeek"};
        String[] icons={"spark","code","spark"};
        // Provider marks are optional: a package without <key>_official keeps the
        // neutral glyph ui.icon() already set instead of blanking the ImageView.
        //
        // The marks are full-colour PNGs, so they cannot follow the theme the way a
        // Symbol does. They are only used for the unselected state, where the tab is
        // already fighting for attention anyway; the selected tab always draws the
        // themed glyph, so the active tab is the one colour that belongs to the
        // current style.
        int[] marks={ui.res("claude_official","drawable",0),ui.res("codex_official","drawable",0),ui.res("deepseek_official","drawable",0)};
        int visible=0;for(String key:keys)if(getSharedPreferences("ui",0).getBoolean("show_"+key,true))visible++;
        for(int i=0;i<3;i++){final String key=keys[i];if(!getSharedPreferences("ui",0).getBoolean("show_"+key,true))continue;boolean selected=backend.equals(key);
            LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setBackground(ui.touch(selected?NativeUi.TINT:Color.TRANSPARENT,NativeUi.CORNER_ROW,false));
            ImageView tabIcon=ui.icon(icons[i],selected?GREEN:MUTED,NativeUi.GLYPH_ACTION);
            if(marks[i]!=0&&!selected)tabIcon.setImageResource(marks[i]);
            item.addView(tabIcon,new LinearLayout.LayoutParams(dp(24),dp(24)));
            gap(item,NativeUi.SPACE_TIGHT);
            TextView label=text(names[i],NativeUi.CAPTION,selected?GREEN:MUTED);medium(label);label.setGravity(Gravity.CENTER);item.addView(label,new LinearLayout.LayoutParams(-2,-2));
            item.setContentDescription(names[i]);item.setSelected(selected);item.setFocusable(true);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);
            // A single tab should not stretch across the whole bar, so the margin is
            // only applied when there is something to be separated from.
            p.setMargins(visible>1?dp(5):0,dp(4),visible>1?dp(5):0,dp(4));
            bottom.addView(item,p);item.setOnClickListener(v->showTab(key));}
    }
    private void home(){
        reset();bars(View.GONE);ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);LinearLayout card=column();card.setPadding(dp(28),dp(22),dp(28),dp(24));
        LinearLayout artwork=row();artwork.setGravity(Gravity.CENTER);artwork.setBackground(ui.surface(NativeUi.TINT,NativeUi.CORNER_SHEET,false));
        artwork.addView(ui.icon("monitor",GREEN,54),new LinearLayout.LayoutParams(dp(64),dp(64)));TextView dots=text("· · ·",NativeUi.HEADING,MUTED);dots.setPadding(dp(16),0,dp(16),0);artwork.addView(dots);artwork.addView(ui.icon("phone",GREEN,38),new LinearLayout.LayoutParams(dp(44),dp(54)));add(card,artwork,118);
        gap(card,32);TextView eyebrow=text("继续电脑上的工作",NativeUi.CAPTION,GREEN);eyebrow.setLetterSpacing(.14f);add(card,eyebrow,-2);gap(card,14);
        TextView welcome=title("灵感随行，\n工作继续。");welcome.setTextSize(NativeUi.DISPLAY);welcome.setLineSpacing(dp(6),1);add(card,welcome,-2);gap(card,16);
        add(card,text("连接你的电脑，随时查看进度、\n继续对话，处理需要确认的操作。",NativeUi.BODY,MUTED),-2);gap(card,28);
        LinearLayout steps=column();inset(steps,16,16);steps.setBackground(ui.surface(NativeUi.WHITE,NativeUi.CORNER_ROW,true));
        add(steps,text("01   在电脑助手中开启设备连接",NativeUi.LABEL,INK),-2);gap(steps,12);add(steps,text("02   扫描二维码，安全连接",NativeUi.LABEL,INK),-2);add(card,steps,-2);gap(card,22);
        // Both buttons on the welcome card used to differ in height - 54 against 48 -
        // for no reason a reader could see. The primary action keeps a taller row so
        // it still reads as primary.
        Button scan=button("扫码连接电脑",true);ui.leading(scan,"scan",NativeUi.onPrimary(),NativeUi.GLYPH_INLINE);add(card,scan,NativeUi.ROW_TALL);scan.setOnClickListener(v->scan());gap(card,8);
        Button paste=button("粘贴配对码",false);compact(paste);add(card,paste,NativeUi.ROW);paste.setOnClickListener(v->{ClipboardManager c=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData data=c.getPrimaryClip();if(data!=null&&data.getItemCount()>0)pair(data.getItemAt(0).coerceToText(this).toString());else toast("剪贴板为空");});
        gap(card,16);status=text("无需账号密码 · 配对后自动重连",NativeUi.CAPTION,MUTED);status.setGravity(Gravity.CENTER);add(card,status,-2);gap(card,12);scroll.addView(card);add(screen,scroll,-1);
    }

    private void scan(){
        if(!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)){toast("此设备没有可用相机，请粘贴配对码");return;}
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},21);return;}
        try{Intent i=new Intent("com.google.zxing.client.android.SCAN");i.setClassName(this,"io.github.abyxs.codexmobile.QrScannerActivity");i.putExtra("SCAN_FORMATS","QR_CODE");i.putExtra("PROMPT_MESSAGE","扫描电脑 coding远程上的配对二维码");i.putExtra("BEEP_ENABLED",false);i.putExtra("SCAN_ORIENTATION_LOCKED",false);startActivityForResult(i,700);}
        catch(RuntimeException e){toast("相机无法打开，请检查权限或粘贴配对码");}
    }
    @Override public void onRequestPermissionsResult(int request,String[] perms,int[] results){super.onRequestPermissionsResult(request,perms,results);if(request==22){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startNotifications();else{getSharedPreferences("ui",0).edit().putBoolean("notifications",false).apply();toast("通知权限未开启，可在系统设置中启用");}}if(request==21){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)scan();else toast("未获得相机权限，可以粘贴配对码连接");}}
    @Override public void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==701&&result==RESULT_OK&&data!=null){uploadPicked(data);return;}if(request==700&&result==RESULT_OK&&data!=null){String value=data.getStringExtra("SCAN_RESULT");if(value!=null)pair(value);}}
    private void pair(String raw){
        try{
            JSONObject qr=new JSONObject(raw);if(!"coding-remote".equals(qr.optString("app"))||qr.optInt("v")!=1)throw new Exception("请扫描新版 coding远程的配对二维码");
            String address=qr.getString("origin"),secret=qr.getString("token");Uri u=Uri.parse(address);
            if(!TrustedOrigin.valid(address))throw new Exception("配对二维码的连接入口无效");
            if(qr.optDouble("expires",0)<System.currentTimeMillis()/1000.0)throw new Exception("二维码已过期，请在电脑刷新");
            toast("正在配对…");
            workers.execute(()->{try{String install=getSharedPreferences("ui",0).getString("installation","");if(install.isEmpty()){install=UUID.randomUUID().toString();getSharedPreferences("ui",0).edit().putString("installation",install).commit();}JSONObject body=new JSONObject().put("requestId",UUID.randomUUID().toString()).put("token",secret).put("device",Build.MANUFACTURER+" "+Build.MODEL).put("deviceInfo",new JSONObject().put("installationId",install).put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL).put("android",Build.VERSION.RELEASE));
                JSONObject value=request(address,"","/api/pair",body);String deviceToken=value.getString("token");
                JSONObject previous=ConnectionProfiles.read(this);
                if(previous.has("token")&&previous.optString("computerId").isEmpty())try{JSONObject identity=transport.request(previous.getString("origin"),previous.getString("token"),"/api/device",null);if(!identity.optString("computerId").isEmpty()){previous.put("computerId",identity.getString("computerId"));ConnectionProfiles.add(this,previous);}}catch(Exception ignored){}
                ConnectionProfiles.add(this,new JSONObject().put("origin",address).put("token",deviceToken).put("computerId",value.optString("computerId",qr.optString("computerId"))).put("mode",value.optString("mode",qr.optString("mode",address.startsWith("http://100.")?"permanent":"temporary"))));
                runOnUiThread(()->{origin=address;token=deviceToken;clearCaches();showTab(firstTab());startNotifications();toast("配对成功");});
            }catch(Exception e){runOnUiThread(()->toast(e.getMessage()));}});
        }catch(Exception e){toast(e.getMessage());}
    }

    private JSONObject request(String address,String credential,String path,JSONObject body)throws Exception{return ConnectionProfiles.request(this,transport::request,address,credential,path,body);}
    private JSONObject networkRequest(String address,String credential,String path,JSONObject body)throws Exception{
        if(!TrustedOrigin.valid(address))throw new IOException("连接地址不受支持，请重新扫码绑定");HttpURLConnection c=EmbeddedNetwork.open(this,address+path);
        // A read may wait for a long transcript over a slow tunnel; writes already
        // have their own server-side bounds.
        c.setConnectTimeout(7000);c.setReadTimeout(body==null?30000:110000);c.setInstanceFollowRedirects(false);
        if(Thread.currentThread().getName().equals("conversation-live"))liveConnections.put(Thread.currentThread(),c);c.setRequestProperty("Accept","application/json");c.setRequestProperty("Origin",address);if(!credential.isEmpty())c.setRequestProperty("Authorization","Bearer "+credential);
        boolean reusable=false;
        try{if(body!=null){byte[] bytes=body.toString().getBytes("UTF-8");c.setRequestMethod("POST");c.setRequestProperty("Content-Type","application/json");c.setDoOutput(true);c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
            int code=c.getResponseCode();InputStream in=code<400?c.getInputStream():c.getErrorStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(in!=null)try(InputStream input=in){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(bytes.size()+n>32*1024*1024)throw new IOException("会话内容过大，请在电脑查看");bytes.write(buffer,0,n);}}
            JSONObject result;try{result=new JSONObject(bytes.toString("UTF-8"));}catch(Exception e){throw new ConnectionProfiles.HttpFailure(code>=500?code:502,"连接入口暂时未就绪，正在尝试恢复");}
            if(code>=300)throw new ApiError(code,result.optString("error","连接失败"));
            // Keep a fully read response in the pool: the next poll then reuses the
            // tunnel's connection, which is also where most transient failures came from.
            reusable=true;return result;
        }finally{liveConnections.remove(Thread.currentThread());if(!reusable)c.disconnect();}
    }
    private void fetch(String path,JSONObject body,int generation,Done done){final String host=origin,credential=token;
        workers.execute(()->{try{JSONObject value=request(host,credential,path,body);runOnUiThread(()->{if(generation!=epoch||isFinishing())return;try{done.accept(value);}catch(Exception e){showError(e);}});}
        catch(Exception e){runOnUiThread(()->{if(generation==epoch&&!isFinishing())showError(e);});}});
    }
    private String errorMessage(Exception e){if(e instanceof java.net.UnknownHostException||e instanceof java.net.ConnectException)return "电脑暂时离线，已保留设备绑定；网络恢复后会自动重连。";if(e instanceof java.net.SocketTimeoutException)return "连接超时，请确认电脑助手仍在运行。";if(e instanceof javax.net.ssl.SSLException)return "安全连接未建立，请检查网络后重试。";return e.getMessage()==null?"连接失败，请重试":e.getMessage();}
    private void showError(Exception e){String message=errorMessage(e);if(status!=null){status.setText(message);status.setTextColor(NativeUi.RED);}toast(message);}

    private void pendingControls(){
        if(pendingPanel==null)return;pendingPanel.removeAllViews();
        updateSend();adaptComposer();
        if(pending==null)return;
        LinearLayout card=column();inset(card,12,10);card.setBackground(ui.surface(NativeUi.SOFT,NativeUi.CORNER_ROW,true));add(card,text(sending?"正在提交到电脑…":"上次操作的结果尚未确认",NativeUi.CAPTION,MUTED),-2);gap(card,8);
        // An unresolved submission can be re-checked, retried, withdrawn for
        // editing, or reported as failed; it is never replayed on its own.
        LinearLayout first=row();
        Button requery=button("重查结果",false);compact(requery);requery.setEnabled(!sending);first.addView(requery,new LinearLayout.LayoutParams(0,dp(48),1));requery.setOnClickListener(v->sendPending());
        Button retry=button("重试一次",false);compact(retry);retry.setEnabled(!sending);first.addView(retry,new LinearLayout.LayoutParams(0,dp(48),1));add(card,first,48);
        LinearLayout second=row();gap(card,4);
        Button withdraw=button("撤回并编辑",false);compact(withdraw);withdraw.setEnabled(!sending);second.addView(withdraw,new LinearLayout.LayoutParams(0,dp(48),1));
        Button failed=button("标记失败",false);compact(failed);failed.setTextColor(NativeUi.RED);failed.setEnabled(!sending);second.addView(failed,new LinearLayout.LayoutParams(0,dp(48),1));add(card,second,48);
        Button checked=button("已在电脑端核对",false);compact(checked);checked.setEnabled(!sending);add(card,checked,40);
        add(pendingPanel,card,-2);gap(pendingPanel,8);
        requery.setContentDescription("重新查询上次提交在电脑上的结果");
        retry.setOnClickListener(v->alert().setTitle("重新发送这条操作？").setMessage("电脑可能已经执行过。重新发送会使用新的提交标识，可能重复执行一次。").setPositiveButton("重新发送",(d,w)->retryPending()).setNegativeButton("取消",null).show());
        withdraw.setOnClickListener(v->withdrawPending());
        failed.setOnClickListener(v->{clearPending();toast("已标记为发送失败，可重新编辑后发送");pendingControls();});
        checked.setOnClickListener(v->alert().setMessage("确认已检查原会话？清除后可提交新消息。").setPositiveButton("已核对",(d,w)->{clearPending();pendingControls();}).setNegativeButton("取消",null).show());
    }
    private String pendingDraft(){
        if(pending==null)return "";
        try{JSONObject body=pending.getJSONObject("body");
            if(body.has("message"))return body.getJSONObject("message").optString("text","");
            return body.optString("text","");
        }catch(Exception e){return "";}
    }
    private void retryPending(){
        if(pending==null||sending)return;
        try{
            final JSONObject body=new JSONObject(pending.getJSONObject("body").toString());
            body.put("id",UUID.randomUUID().toString());
            final String path=pending.getString("path");
            pending=new JSONObject().put("path",path).put("body",body).put("origin",origin);
            getSharedPreferences("pending",0).edit().putString("request",pending.toString()).commit();
            sendPending();
        }catch(Exception e){showError(e);}
    }
    private void withdrawPending(){
        String draft=pendingDraft();clearPending();
        if(composer!=null&&!draft.isEmpty()){composer.setText(draft);composer.setSelection(composer.length());toast("已撤回，电脑上若已收到原消息不会自动删除");}
        else toast("已撤回上次提交");
        pendingControls();updateSend();
    }

    private void showTab(String key){
        if(token.isEmpty()){home();return;}reset();backend=key;if(!key.equals("settings")&&!getSharedPreferences("ui",0).getBoolean("show_"+key,true)){showTab(firstTab());return;}navigation();
        if(key.equals("settings")){settings();return;}
        getSharedPreferences("ui",0).edit().putString("backend",key).putBoolean("resumeChat",false).apply();
        displayedListKey=key;ListCache cache=listCaches.get(key);if(cache==null){cache=new ListCache();listCaches.put(key,cache);}
        final ListCache current=cache;if(cache.loaded==0){final String cacheKey=origin+token+"list:"+key;workers.execute(()->{JSONObject raw=ConversationCache.read(this,cacheKey);if(raw!=null)runOnUiThread(()->{if(current.loaded==0&&raw.optJSONArray("sessions")!=null){current.rows=raw.optJSONArray("sessions");current.loaded=1;for(int i=0;i<current.rows.length();i++){JSONObject r=current.rows.optJSONObject(i);if(r!=null)syncIdentity(key,r.optString("id"),r.optString("title"),r.optString("cwd"),0);}if(key.equals(displayedListKey))showSessions(current);}});});}
        appBar.setVisibility(View.GONE);LinearLayout heading=column();inset(heading,20,4);LinearLayout top=row();TextView providerTitle=text(providerName(key),NativeUi.HEADING,INK);top.addView(providerTitle,new LinearLayout.LayoutParams(-2,-2));
        // The chevron is a glyph, not the "⌄" character: that codepoint is missing
        // from several Android system fonts and fell back to a box.
        ImageView menuMark=ui.icon("down",MUTED,NativeUi.GLYPH_INLINE);LinearLayout.LayoutParams markLp=new LinearLayout.LayoutParams(dp(18),dp(18));markLp.leftMargin=dp(2);markLp.gravity=Gravity.CENTER_VERTICAL;top.addView(menuMark,markLp);
        connectionLabel=text("",NativeUi.CAPTION,MUTED);connectionLabel.setPadding(dp(12),0,0,0);single(connectionLabel);top.addView(connectionLabel,new LinearLayout.LayoutParams(0,-2,1));Button settings=ui.iconButton("settings","设置");top.addView(settings,new LinearLayout.LayoutParams(dp(48),dp(48)));settings.setOnClickListener(v->openSettings());refreshButton=ui.iconButton("refresh","刷新会话");Button create=ui.iconButton("plus","新建会话");create.setOnClickListener(v->newSession());add(heading,top,NativeUi.ROW_TALL);
        // Focusable so TalkBack treats the row as the clickable control it is; it was
        // reachable by touch but invisible to accessibility navigation.
        top.setContentDescription(providerName(key)+" 客户端状态，点击切换客户端");top.setFocusable(true);top.setOnClickListener(v->toggleApplicationMenu(top,key));LinearLayout localActions=row();localActions.addView(refreshButton,new LinearLayout.LayoutParams(dp(48),dp(48)));localActions.addView(create,new LinearLayout.LayoutParams(dp(48),dp(48)));refreshButton.setOnClickListener(v->loadSessions(key,true));
        
        LinearLayout searchBox=row();inset(searchBox,14,0);searchBox.setBackground(ui.surface(NativeUi.WHITE,NativeUi.CORNER_ROW,true));searchBox.addView(ui.icon("search",MUTED,NativeUi.GLYPH_INLINE),new LinearLayout.LayoutParams(dp(20),dp(20)));
        search=new EditText(this);search.setSingleLine();search.setTextSize(NativeUi.LABEL);search.setTextColor(INK);search.setHintTextColor(MUTED);search.setHint("搜索聊天或项目");search.setContentDescription("搜索聊天或项目");search.setBackgroundColor(Color.TRANSPARENT);inset(search,10,10);search.setText(cache.query);search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);searchBox.addView(search,new LinearLayout.LayoutParams(0,dp(44),1));
        Button clear=ui.iconButton("close","清除搜索");clear.setVisibility(cache.query.isEmpty()?View.GONE:View.VISIBLE);searchBox.addView(clear,new LinearLayout.LayoutParams(dp(48),dp(48)));clear.setOnClickListener(v->search.setText(""));
        search.addTextChangedListener(changed(()->{current.query=search.getText().toString();current.first=0;current.top=0;clear.setVisibility(current.query.isEmpty()?View.GONE:View.VISIBLE);showSessions(current);}));
        search.setOnEditorActionListener((v,action,event)->{((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(),0);search.clearFocus();return true;});
        // Index 0 puts the search field to the left of refresh/create, which is where
        // it reads; the trailing buttons were added first.
        localActions.addView(searchBox,0,new LinearLayout.LayoutParams(0,dp(44),1));add(heading,localActions,48);gap(heading,2);
        LinearLayout filters=row();projectButton=button("全部项目",false);projectButton.setTextSize(NativeUi.CAPTION);single(projectButton);ui.leading(projectButton,"folder",MUTED,NativeUi.GLYPH_INLINE);projectButton.setOnClickListener(v->chooseProject());filters.addView(projectButton,new LinearLayout.LayoutParams(0,dp(48),1));
        groupButton=button("最近会话",false);groupButton.setTextSize(NativeUi.CAPTION);compact(groupButton);ui.leading(groupButton,"down",GREEN,NativeUi.GLYPH_INLINE);LinearLayout.LayoutParams groupLp=new LinearLayout.LayoutParams(dp(120),dp(48));groupLp.leftMargin=dp(8);filters.addView(groupButton,groupLp);groupButton.setOnClickListener(v->{current.grouped=!current.grouped;current.first=0;current.top=0;showSessions(current);});add(heading,filters,48);
        status=text("",NativeUi.CAPTION,MUTED);status.setPadding(0,0,0,dp(4));add(heading,status,-2);add(screen,heading,-2);
        FrameLayout listArea=new FrameLayout(this);sessionList=new ListView(this);sessionList.setDivider(null);sessionList.setCacheColorHint(Color.TRANSPARENT);sessionList.setSelector(ui.touch(NativeUi.TINT,NativeUi.CORNER_ROW,false));sessionList.setPadding(dp(16),0,dp(16),dp(12));sessionList.setClipToPadding(false);
        sessionAdapter=new SessionAdapter();sessionList.setAdapter(sessionAdapter);listArea.addView(sessionList,new FrameLayout.LayoutParams(-1,-1));
        emptyState=column();emptyState.setGravity(Gravity.CENTER);inset(emptyState,32,24);emptyState.addView(ui.icon("chat",NativeUi.LINE,NativeUi.GLYPH_DISPLAY),new LinearLayout.LayoutParams(dp(40),dp(40)));gap(emptyState,14);emptyTitle=text("",NativeUi.TITLE,INK);medium(emptyTitle);emptyState.addView(emptyTitle);gap(emptyState,NativeUi.SPACE_INLINE);emptyDetail=text("",NativeUi.LABEL,MUTED);emptyDetail.setGravity(Gravity.CENTER);add(emptyState,emptyDetail,-2);listArea.addView(emptyState,new FrameLayout.LayoutParams(-1,-1));emptyState.setVisibility(View.GONE);screen.addView(listArea,new LinearLayout.LayoutParams(-1,0,1));
        sessionList.setOnItemClickListener((parent,view,position,itemId)->{CatalogItem item=sessionAdapter.items.get(position);if(item.archive){archivedSessions();return;}if(item.header){if(!current.collapsed.add(item.path))current.collapsed.remove(item.path);current.first=sessionList.getFirstVisiblePosition();current.top=sessionList.getChildCount()>0?sessionList.getChildAt(0).getTop():0;showSessions(current);}else{((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(),0);openSession(item.row.optString("id"),item.row.optString("title"));}});
        showSessions(cache);loadSessions(key,false);final int listGeneration=epoch;handler.postDelayed(new Runnable(){public void run(){if(isDestroyed()||isFinishing()||listGeneration!=epoch||!key.equals(displayedListKey))return;if(!paused)loadSessions(key,false);handler.postDelayed(this,15000);}},15000);
    }
    private String sessionSignature="";
    private final java.util.concurrent.ConcurrentHashMap<String,String> writtenRevisions=new java.util.concurrent.ConcurrentHashMap<>();
    private static final int SESSION_ROW_PAD=15;   // 1.5x the 10dp the rows used to use
    private void showSessions(ListCache cache){
        if(sessionAdapter==null)return;
        // A background refresh that changed nothing must not rebuild the list.
        String signature=cache.rows.length()+"|"+cache.query+"|"+cache.project+"|"+cache.grouped+"|"+cache.collapsed.toString().hashCode()+"|"+cache.rows.toString().hashCode();
        if(!signature.equals(sessionSignature)){sessionSignature=signature;sessionAdapter.filter(cache);}
        projectButton.setText(cache.project.isEmpty()?"全部项目":projectName(cache.project));projectButton.setContentDescription("筛选项目："+(cache.project.isEmpty()?"全部项目":cache.project));groupButton.setText(cache.grouped?"按项目":"最近会话");
        status.setText(cache.loaded==0?"正在读取电脑会话…":sessionAdapter.matches+" 条会话"+(cache.loading?" · 更新中":"")+(cache.notice.isEmpty()?"":" · "+cache.notice));status.setTextColor(MUTED);
        connection(cache.loaded>0?"已缓存":"连接中",false);
        if(cache.loaded>0&&System.currentTimeMillis()-cache.loaded<15000&&cache.error.isEmpty())connection(cache.connected?providerName(backend)+" 已连接":"助手可达 · 应用未连接",cache.connected);
        if(!cache.error.isEmpty()){status.setText(cache.error+(cache.loaded>0?" · 显示上次记录":""));status.setTextColor(NativeUi.RED);connection("连接中断",false);}
        refreshButton.setEnabled(!cache.loading);refreshButton.setAlpha(cache.loading?.4f:1f);
        // The adapter always carries the archive entry, so its count is never zero and
        // gating on it meant the empty state could not appear at all - a search with no
        // matches showed a lone archive row and no explanation. Whether there is anything
        // to read is decided by the rows that actually survived the filter instead.
        emptyState.setVisibility(sessionAdapter.matches==0?View.VISIBLE:View.GONE);
        emptyTitle.setText(cache.loaded==0?(cache.error.isEmpty()?"正在连接你的电脑":"暂时无法读取聊天"):cache.rows.length()==0?"开始一段新对话":"没有找到相关聊天");
        emptyDetail.setText(cache.loaded==0?(cache.error.isEmpty()?"会话会在这里同步显示":"确认电脑助手正在运行，点击刷新重试。"):(cache.rows.length()==0?"点击 +，选择项目后开始。":"试试其他关键词，或切换到全部项目。"));
        sessionList.setSelectionFromTop(cache.first,cache.top);
    }
    private void loadSessions(String key,boolean force){
        final ListCache cache=listCaches.get(key);if(cache==null||cache.loading||(!force&&cache.error.isEmpty()&&cache.loaded>0&&System.currentTimeMillis()-cache.loaded<15000))return;
        cache.loading=true;cache.error="";if(key.equals(displayedListKey)&&status!=null)showSessions(cache);
        final String host=origin,credential=token;final long identityVersion=++identityClock;
        workers.execute(()->{try{JSONObject value=request(host,credential,"/api/native/"+key,null);JSONArray rows=value.optJSONArray("sessions");ConversationCache.write(this,host+credential+"list:"+key,value);
            runOnUiThread(()->{if(isDestroyed()||!host.equals(origin)||!credential.equals(token))return;cache.loading=false;cache.connected=value.optBoolean("connected",false);cache.rows=rows==null?new JSONArray():rows;cache.loaded=System.currentTimeMillis();for(int i=0;i<cache.rows.length();i++){JSONObject row=cache.rows.optJSONObject(i);if(row!=null)syncIdentity(key,row.optString("id"),row.optString("title"),row.optString("cwd"),identityVersion);}cache.notice=value.optString("notice","");if(value.optJSONArray("errors")!=null&&value.optJSONArray("errors").length()>0)cache.notice="部分会话暂不可用，可刷新重试";
                if(key.equals(displayedListKey)&&sessionList!=null){cache.first=sessionList.getFirstVisiblePosition();cache.top=sessionList.getChildCount()>0?sessionList.getChildAt(0).getTop():0;showSessions(cache);}});
        }catch(Exception e){runOnUiThread(()->{cache.loading=false;if(isDestroyed()||!host.equals(origin)||!credential.equals(token))return;cache.error=errorMessage(e);if(key.equals(displayedListKey))showSessions(cache);});}});
    }
    private static final class CatalogItem {final JSONObject row;final String path;final boolean header;boolean archive;int count;CatalogItem(JSONObject r,String p){row=r;path=p;header=r==null;}}
    private final class SessionAdapter extends BaseAdapter {
        final ArrayList<CatalogItem> items=new ArrayList<>();final ArrayList<JSONObject> archivedRows=new ArrayList<>();int matches=0;ListCache cache;
        void filter(ListCache value){cache=value;items.clear();archivedRows.clear();matches=0;ArrayList<CatalogItem> archived=new ArrayList<>();String q=value.query.trim().toLowerCase(Locale.ROOT);LinkedHashMap<String,ArrayList<JSONObject>> groups=new LinkedHashMap<>();
            for(int i=0;i<value.rows.length();i++){JSONObject r=value.rows.optJSONObject(i);if(r==null)continue;String path=groupPath(r);if(!value.project.isEmpty()&&!value.project.equals(path))continue;if(!q.isEmpty()&&!(r.optString("title","")+" "+path).toLowerCase(Locale.ROOT).contains(q))continue;matches++;
                if(r.optBoolean("archived")){archived.add(new CatalogItem(r,path));continue;}if(!value.grouped)items.add(new CatalogItem(r,path));else{ArrayList<JSONObject> group=groups.get(path);if(group==null){group=new ArrayList<>();groups.put(path,group);}group.add(r);}}
            for(Map.Entry<String,ArrayList<JSONObject>> group:groups.entrySet()){CatalogItem h=new CatalogItem(null,group.getKey());h.count=group.getValue().size();items.add(h);if(!value.collapsed.contains(h.path))for(JSONObject r:group.getValue())items.add(new CatalogItem(r,h.path));}for(CatalogItem archivedItem:archived)archivedRows.add(archivedItem.row);CatalogItem archiveEntry=new CatalogItem(null,"");archiveEntry.archive=true;items.add(archiveEntry);notifyDataSetChanged();}
        public int getCount(){return items.size();}public Object getItem(int p){return items.get(p);}public long getItemId(int p){return p;}
        // Three distinct recycled layouts: archive row, project header and session row.
        // Grouping the archive Button with the session LinearLayout made ListView hand
        // back the wrong tree for the type, so any list holding archived sessions could
        // fail to reuse and crash on scroll.
        public int getViewTypeCount(){return 3;}public int getItemViewType(int p){CatalogItem item=items.get(p);return item.archive?2:item.header?1:0;}
        public View getView(int position,View recycled,ViewGroup parent){
            CatalogItem data=items.get(position);
            if(data.archive){Button archive=button("归档",false);archive.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);archive.setBackground(ui.touch(Color.TRANSPARENT,NativeUi.CORNER_ROW,false));ui.leading(archive,"folder",MUTED,NativeUi.GLYPH_INLINE);archive.setOnClickListener(v->archivedSessions());return archive;}
            if(data.header){LinearLayout group=row();TextView header=text(projectName(data.path)+"   ·   "+data.count,NativeUi.TITLE,GREEN);header.setPadding(dp(8),dp(10),0,dp(10));single(header);ui.leading(header,cache.collapsed.contains(data.path)?"right":"down",MUTED,NativeUi.GLYPH_INLINE);group.addView(header,new LinearLayout.LayoutParams(0,dp(48),1));header.setOnClickListener(v->{if(!cache.collapsed.add(data.path))cache.collapsed.remove(data.path);showSessions(cache);});Button create=ui.iconButton("plus","在此项目新建会话");group.addView(create,new LinearLayout.LayoutParams(dp(48),dp(48)));create.setOnClickListener(v->newSession(data.path));return group;}
            // 1.5x line spacing between conversations. The rows were separated by 10dp
            // above and 10dp below, which put a title, its meta line, a date and a rule
            // inside roughly 66dp - dense enough that two conversations read as one
            // block. The padding is scaled by half again on both sides, which is what
            // gives each session its own band without shrinking the text.
            LinearLayout item=column();item.setPadding(dp(16),dp(SESSION_ROW_PAD),dp(16),0);LinearLayout top=row();TextView label=text("",NativeUi.TITLE,INK);medium(label);label.setMaxLines(2);label.setEllipsize(TextUtils.TruncateAt.END);top.addView(label,new LinearLayout.LayoutParams(0,-2,1));TextView date=text("",NativeUi.CAPTION,MUTED);date.setPadding(dp(8),0,0,0);single(date);top.addView(date);add(item,top,-2);gap(item,3);TextView meta=text("",NativeUi.CAPTION,MUTED);single(meta);add(item,meta,-2);gap(item,SESSION_ROW_PAD);rule(item);
            JSONObject r=data.row;label.setText(r.optString("title","未命名会话"));String surface=r.optString("backend",backend);String active=r.optString("status","");boolean running=active.equals("active")||active.equals("running")||active.equals("inProgress");
            // Name the project on every row: with the same title in two directories
            // the path is the only way to tell two conversations apart.
            meta.setText((cache.grouped||data.path.isEmpty()?"":projectName(data.path)+" · ")+(r.has("turnCount")?r.optInt("turnCount")+" 轮对话":""));meta.setVisibility(meta.getText().length()==0?View.GONE:View.VISIBLE);date.setText(r.optBoolean("archived")?"已归档":running?"●  进行中":sessionDate(r));date.setTextColor(running?GREEN:MUTED);item.setOnClickListener(v->openSession(r.optString("id"),r.optString("title")));item.setOnLongClickListener(v->{sessionActions(r);return true;});return item;
        }
    }
    private void archivedSessions(){
        ArrayList<JSONObject> archived=new ArrayList<>(sessionAdapter.archivedRows);ArrayList<String> labels=new ArrayList<>();for(JSONObject item:archived)labels.add(item.optString("title","未命名会话")+"\n"+projectName(groupPath(item)));
        LinearLayout body=column();final Dialog dialog=sheet("归档","选择会话查看",body);if(archived.isEmpty()){add(body,emptyState("folder","还没有归档的会话","归档后的会话会出现在这里"),-2);}else{ListView list=new ListView(this);list.setDivider(null);list.setAdapter(ui.choices(labels));add(body,list,Math.min(380,Math.max(56,labels.size()*64)));list.setOnItemClickListener((parent,view,position,id)->{JSONObject item=archived.get(position);dialog.dismiss();openSession(item.optString("id"),item.optString("title"));});}
    }
    private void sessionActions(JSONObject session){
        LinearLayout body=column();Dialog dialog=sheet("编辑会话",session.optString("title"),body);
        for(String action:new String[]{"重命名","归档","删除","复制项目路径"}){if(action.equals("删除")&&backend.equals("deepseek"))continue;Button choice=button(action,false);add(body,choice,48);choice.setOnClickListener(v->{dialog.dismiss();
            if(action.equals("复制项目路径")){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("项目路径",groupPath(session)));toast("已复制");return;}
            if(action.equals("重命名")){EditText name=new EditText(this);name.setTextColor(INK);name.setText(session.optString("title"));name.setSingleLine();alert().setTitle("重命名会话").setView(name).setPositiveButton("保存",(d,w)->changeSession(session,"rename",name.getText().toString())).setNegativeButton("取消",null).show();}
            else alert().setTitle(action+"此会话？").setMessage(action.equals("删除")?"将从电脑端删除会话，无法撤销。":"会话将移入归档，正在执行的任务可能停止。").setPositiveButton(action,(d,w)->changeSession(session,action.equals("删除")?"delete":"archive","")).setNegativeButton("取消",null).show();
        });}
    }
    private void changeSession(JSONObject session,String action,String title){try{String key=backend;JSONObject body=new JSONObject().put("id",UUID.randomUUID().toString()).put("confirmed",true).put("title",title);fetch("/api/native/"+key+"/"+session.getString("id")+"/"+action,body,epoch,result->{if(result.optString("status").equals("accepted")){if(action.equals("rename"))syncIdentity(key,session.optString("id"),title.trim(),session.optString("cwd"));toast("已保存");loadSessions(key,true);}else toast(result.optString("message","操作尚未确认，请刷新核对"));});}catch(Exception e){showError(e);}}
    private String sessionDate(JSONObject row){
        Object value=row.opt("updatedAt");if(value==null||value==JSONObject.NULL)return "";
        try{long stamp;if(value instanceof Number){stamp=((Number)value).longValue();if(stamp<100000000000L)stamp*=1000;}else{String raw=value.toString();try{stamp=java.time.Instant.parse(raw).toEpochMilli();}catch(Exception e){return "";}}
            if(stamp<=0)return "";java.text.SimpleDateFormat day=new java.text.SimpleDateFormat("yyyyMMdd",Locale.ROOT);java.util.Date date=new java.util.Date(stamp);return day.format(date).equals(day.format(new java.util.Date()))?"今天":new java.text.SimpleDateFormat("M/d",Locale.ROOT).format(date);
        }catch(Exception e){return "";}
    }
    private Dialog sheet(String title,String subtitle,View content){
        Dialog dialog=new Dialog(this);LinearLayout panel=column();panel.setPadding(dp(20),dp(10),dp(20),dp(16));panel.setBackground(ui.surface(NativeUi.WHITE,NativeUi.CORNER_CARD,true));
        // A grip and a tighter header make every sheet read as one component.
        View grip=new View(this);grip.setBackground(ui.surface(NativeUi.LINE,2,false));LinearLayout.LayoutParams gripLp=new LinearLayout.LayoutParams(dp(36),dp(4));gripLp.gravity=Gravity.CENTER_HORIZONTAL;gripLp.bottomMargin=dp(8);panel.addView(grip,gripLp);
        LinearLayout top=row();TextView heading=text(title,NativeUi.HEADING,INK);medium(heading);top.addView(heading,new LinearLayout.LayoutParams(0,-2,1));Button close=ui.iconButton("close","关闭");top.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));close.setOnClickListener(v->dialog.dismiss());add(panel,top,48);
        if(!subtitle.isEmpty()){TextView note=text(subtitle,NativeUi.CAPTION,MUTED);note.setMaxLines(4);note.setEllipsize(TextUtils.TruncateAt.END);add(panel,note,-2);gap(panel,14);}else gap(panel,10);
        add(panel,content,-2);dialog.setContentView(panel);dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);dialog.getWindow().setDimAmount(android.graphics.Color.luminance(NativeUi.CANVAS)<.2?.46f:.28f);dialog.show();ui.window(dialog.getWindow(),true);return dialog;
    }
    private void chooseProject(){
        final ListCache cache=listCaches.get(displayedListKey);if(cache==null)return;ArrayList<String> paths=new ArrayList<>();paths.add("");
        for(int i=0;i<cache.rows.length();i++){JSONObject r=cache.rows.optJSONObject(i);String path=r==null?"":groupPath(r);if(!paths.contains(path))paths.add(path);}
        ListView list=new ListView(this);list.setDivider(null);list.setAdapter(new BaseAdapter(){public int getCount(){return paths.size();}public Object getItem(int p){return paths.get(p);}public long getItemId(int p){return p;}public View getView(int p,View old,ViewGroup parent){TextView label=old instanceof TextView?(TextView)old:text("",NativeUi.LABEL,INK);label.setPadding(dp(10),dp(18),dp(10),dp(18));single(label);label.setText(p==0?"全部项目":paths.get(p));label.setBackground(ui.touch(cache.project.equals(paths.get(p))?NativeUi.TINT:Color.TRANSPARENT,NativeUi.CORNER_ROW,false));ui.leading(label,cache.project.equals(paths.get(p))?"check":"folder",GREEN,NativeUi.GLYPH_INLINE);return label;}});
        LinearLayout body=column();add(body,list,Math.min(360,paths.size()*58));final Dialog dialog=sheet("选择项目","只查看这个项目中的聊天",body);
        list.setOnItemClickListener((p,v,index,id)->{cache.project=paths.get(index);cache.first=0;cache.top=0;showSessions(cache);dialog.dismiss();});
    }

    private void openSession(String id,String name){
        reset();sessionId=id;sessionKey=backend+":"+id;bars(View.GONE);
        ChatCache cache=chatCaches.get(sessionKey);if(cache==null){cache=new ChatCache();cache.plugins.addAll(getSharedPreferences("composer-plugins",0).getStringSet(origin+"|"+sessionKey,java.util.Collections.emptySet()));chatCaches.put(sessionKey,cache);}
        if(cache.title.isEmpty())cache.title=name==null||name.isEmpty()?"未命名会话":name;chatPath=cache.project.isEmpty()?cache.cwd:cache.project;
        root.setBackgroundColor(BG);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);appBar.setVisibility(View.GONE);
        chatHeader=column();chatHeader.setBackgroundColor(BG);chatHeader.setElevation(dp(NativeUi.LIFT_HEADER));LinearLayout top=row();inset(top,12,2);Button back=ui.iconButton("back","返回聊天列表");top.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));back.setOnClickListener(v->showTab(backend));
        // The header carries the title and the live state. It used to stack three
        // lines - title, directory, status - inside a 56dp row, so the status line
        // was clipped in half and "正在思考" never rendered whole. The directory is
        // gone and the state now sits on its own line that the row can fit.
        LinearLayout identity=column();identity.setGravity(Gravity.CENTER_VERTICAL);identity.setPadding(dp(4),0,dp(4),0);
        sessionTitle=text(cache.title,NativeUi.BODY,INK);medium(sessionTitle);single(sessionTitle);add(identity,sessionTitle,-2);
        gap(identity,1);
        connectionLabel=text("",NativeUi.CAPTION,MUTED);single(connectionLabel);add(identity,connectionLabel,-2);
        connection("正在读取",false);
        top.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
        Button info=ui.iconButton("info","会话信息");top.addView(info,new LinearLayout.LayoutParams(dp(48),dp(48)));info.setOnClickListener(v->sessionInfo());add(chatHeader,top,NativeUi.ROW_TALL);rule(chatHeader);
        // Insert before the info button by index, not by getChildCount()-1: that
        // expression put the counter ahead of info whenever the row's composition
        // changed, and only looked right while identity happened to carry weight 1.
        recordCount=text("0 轮对话",NativeUi.CAPTION,MUTED);recordCount.setPadding(dp(4),0,dp(8),0);top.addView(recordCount,top.indexOfChild(info));projectButton=null;
        historyBar=row();historyBar.setGravity(Gravity.CENTER);earlierButton=button("查看更早的消息",false);compact(earlierButton);earlierButton.setTextSize(NativeUi.CAPTION);earlierButton.setBackground(ui.touch(Color.TRANSPARENT,NativeUi.CORNER_ROW,false));historyBar.addView(earlierButton,new LinearLayout.LayoutParams(-2,dp(48)));historyBar.setVisibility(View.GONE);earlierButton.setOnClickListener(v->loadEarlier());
        FrameLayout transcript=new FrameLayout(this);messageList=new ListView(this);messageList.setDivider(null);messageList.setVerticalScrollBarEnabled(false);messageList.setSelector(android.R.color.transparent);messageList.setCacheColorHint(Color.TRANSPARENT);messageList.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_DISABLED);messageList.setPadding(dp(20),dp(68),dp(20),dp(20));messageList.setClipToPadding(false);messageAdapter=new MessageAdapter();messageList.setAdapter(messageAdapter);transcript.addView(messageList,new FrameLayout.LayoutParams(-1,-1));
        floating=new FloatingNavigation(this,ui,()->jumpToTop(),()->jumpToLatest());latestButton=floating.latest;        fastScroller=new FastScroller(this,messageList,()->{followingLatest=false;},()->finishScroll());transcript.addView(fastScroller,new FrameLayout.LayoutParams(dp(28),-1,Gravity.RIGHT));
        // Sized from the handle's own constants. It used to be a fixed 50x102, but two
        // 46dp discs plus an 8dp gap need 100dp, so the container clipped both discs -
        // which is why the buttons looked cut off.
        transcript.addView(floating,new FrameLayout.LayoutParams(dp(FloatingNavigation.DISC_DP),dp(FloatingNavigation.spanDp())));transcript.addView(chatHeader,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));FrameLayout.LayoutParams historyLayout=new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.CENTER_HORIZONTAL);historyLayout.topMargin=dp(58);transcript.addView(historyBar,historyLayout);chatHeader.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(messageList==null||b-t==ob-ot)return;int padding=b-t+dp(hasEarlier?42:10);if(messageList.getPaddingTop()!=padding)messageList.setPadding(dp(20),padding,dp(20),dp(20));});final FloatingNavigation nav=floating;nav.place();transcript.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot)nav.place();});
        messageList.setOnTouchListener(new View.OnTouchListener(){float start,last;public boolean onTouch(View v,android.view.MotionEvent e){
            if(e.getActionMasked()==0){start=last=e.getRawY();requestedHeader=null;scrolling=true;handler.removeCallbacks(revealHeader);}
            else if(e.getActionMasked()==2){float delta=e.getRawY()-last;if(Math.abs(delta)>dp(24)){requestedHeader=delta<0;last=e.getRawY();}}
            else if(e.getActionMasked()==1||e.getActionMasked()==3){scrolling=false;handler.postDelayed(()->{if(isDestroyed()||scrolling)return;finishScroll();scheduleHeaderReveal();},100);}return false;}});
        messageList.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView v,int state){scrolling=state!=SCROLL_STATE_IDLE;if(!scrolling){finishScroll();scheduleHeaderReveal();}}
            public void onScroll(AbsListView v,int first,int visible,int total){if(fastScroller!=null)fastScroller.invalidate();updateNavigation();if(scrolling&&total>0){View last=v.getChildAt(v.getChildCount()-1);followingLatest=first+visible>=total&&last!=null&&last.getBottom()<=v.getHeight()-v.getPaddingBottom()+dp(12);}}});screen.addView(transcript,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout dock=column();dock.setPadding(dp(16),0,dp(16),dp(8));actionHint=button("",false);actionHint.setTextSize(NativeUi.CAPTION);actionHint.setTextColor(GREEN);actionHint.setBackground(ui.touch(NativeUi.TINT,NativeUi.CORNER_ROW,false));ui.leading(actionHint,"shield",GREEN,NativeUi.GLYPH_INLINE);add(dock,actionHint,48);actionHint.setVisibility(View.GONE);actionHint.setOnClickListener(v->{
            if(pending!=null){
                // Every path out of a stale submission goes through here, including
                // "I checked the computer and it is done", which simply drops it.
                alert().setTitle("上次操作还没有结果")
                    .setMessage("电脑可能没有收到，也可能已经执行。重新发送会使用新的提交标识，可能重复一次。")
                    .setPositiveButton("重新发送",(d,w)->sendPending())
                    .setNeutralButton("电脑已执行，清除提示",(d,w)->{clearPending();pendingControls();toast("已清除提示");})
                    .setNegativeButton("暂不处理",null).show();
                return;
            }
            if(!withdrawable.isEmpty()){String draft=withdrawable;withdrawable="";if(composer!=null){composer.setText(draft);composer.setSelection(composer.length());}toast("已撤回，电脑上若已收到原消息不会自动删除");adaptComposer();updateSend();pendingControls();return;}
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(composer.getWindowToken(),0);composer.clearFocus();final NativeUi.DockScroll scroller=composerScroll;if(scroller!=null)scroller.postDelayed(()->scroller.fullScroll(View.FOCUS_UP),200);
        });permissionScroll=new ScrollView(this);permissions=column();permissionScroll.addView(permissions);add(dock,permissionScroll,0);
        status=text("",NativeUi.CAPTION,NativeUi.RED);status.setPadding(dp(4),dp(4),dp(4),dp(6));status.setVisibility(View.GONE);status.addTextChangedListener(changed(()->{if(status!=null)status.setVisibility(status.getText().length()==0?View.GONE:View.VISIBLE);}));add(dock,status,-2);
        pendingPanel=column();add(dock,pendingPanel,-2);
        queuePanel=column();add(dock,queuePanel,-2);taskPanel=column();add(dock,taskPanel,-2);inlineOptions=column();inlineOptions.setVisibility(View.GONE);inlineOptions.setPadding(dp(12),dp(8),dp(12),dp(8));inlineOptions.setBackground(ui.surface(BG,NativeUi.CORNER_ROW,true));inlineOptions.setElevation(dp(NativeUi.LIFT_DIALOG));optionsDismiss=new View(this);optionsDismiss.setVisibility(View.GONE);optionsDismiss.setOnClickListener(v->closeOptions());stage.addView(optionsDismiss,new FrameLayout.LayoutParams(-1,-1));stage.addView(inlineOptions,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));LinearLayout compose=column();inset(compose,12,6);compose.setBackground(ui.field(NativeUi.SOFT,NativeUi.CORNER_CARD));compose.setClipToOutline(true);
        composer=new EditText(this);composer.setTextSize(NativeUi.BODY);composer.setTextColor(INK);composer.setHintTextColor(MUTED);composer.setHint("继续这段对话…");composer.setContentDescription("消息输入框");composer.setGravity(Gravity.TOP);composer.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);composer.setMinLines(3);composer.setMaxLines(5);composer.setBackgroundColor(Color.TRANSPARENT);composer.setPadding(0,dp(6),0,dp(6));composer.setText(cache.draft);LinearLayout inputLine=row();inputLine.setBaselineAligned(false);inputLine.setClipToPadding(false);inputLine.setGravity(Gravity.BOTTOM);inputLine.addView(composer,new LinearLayout.LayoutParams(0,-2,1));sendButton=button("",true);sendButton.setMinHeight(0);sendButton.setMinimumHeight(0);sendButton.setBackground(ui.touch(GREEN,NativeUi.CORNER_SHEET,false));ui.centerIcon(sendButton,"arrow",BG,20,-4);sendButton.setTextSize(NativeUi.CAPTION);sendButton.setIncludeFontPadding(false);sendButton.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);sendButton.setPadding(0,0,0,dp(10));sendButton.setOnLongClickListener(v->{showContextUsage();return true;});inputLine.addView(sendButton,new LinearLayout.LayoutParams(dp(56),dp(56)));add(compose,inputLine,-2);attachmentRow=row();add(compose,attachmentRow,-2);renderAttachments();
        LinearLayout controls=row();optionAnchor=controls;controls.setGravity(Gravity.CENTER_VERTICAL);
        // The four choices sit on one line with the attach button, at the foot of the
        // composer. They are chips now rather than full-width buttons: a chip sizes to
        // its own text, so nothing has to be weighted down to a fifth of the screen -
        // which is what squeezed the labels to zero width and left four bare arrows.
        // The row scrolls horizontally when the labels are long, so no label is ever
        // truncated to make room for another.
        attachButton=ui.iconButton("plus","添加附件");
        attachButton.setBackground(ui.touch(NativeUi.SOFT,NativeUi.CORNER_CHIP,true));
        attachButton.setOnClickListener(v->pickImages());
        controls.addView(attachButton,new LinearLayout.LayoutParams(dp(40),dp(40)));
        // The composer's own fold control. The options row used to be a fixed slab under
        // the input with no way to put it away, so a one-line message still paid for the
        // chip row. The caret sits at the right end of the same row and folds it.
        //
        // It carries a real edge. A SOFT disc on a canvas that is a shade away from SOFT
        // left the caret looking like a gap at the end of the row rather than a control,
        // which is why it read as absent even though it was there and worked. Higher
        // contrast on the glyph and an outline make it a button at a glance.
        foldButton=ui.iconButton("down","收起输入选项");
        foldButton.setBackground(ui.touch(NativeUi.TINT,NativeUi.CORNER_CHIP,true));
        foldButton.setOnClickListener(v->toggleComposerCollapsed());
        LinearLayout optionRow=row();optionRow.setGravity(Gravity.CENTER_VERTICAL);
        modelLabel=option("模型",()->toggleCatalog("model"));
        effortLabel=option("思考",()->toggleCatalog("model"));
        accessLabel=option("审批",()->chooseAccess());
        pluginLabel=option("插件",()->toggleCatalog("plugins"));
        // Model first, then the three that describe the run. The order used to depend on
        // which loop happened to add them, and the previous edit left 模型 last with the
        // for-loop glued onto the previous statement.
        for(TextView option:new TextView[]{modelLabel,effortLabel,accessLabel,pluginLabel}){
            optionRow.addView(option);
            gap(optionRow,6);}
        // A horizontal scroller, not DockScroll. DockScroll is a vertical ScrollView with
        // a height cap, so putting a single horizontal row inside it clipped the row to
        // the viewport and only the first chip or two stayed visible - which is what left
        // "only the plus button and 思考" on screen. This scrolls sideways, which is the
        // axis the row actually needs.
        android.widget.HorizontalScrollView options=new android.widget.HorizontalScrollView(this);
        options.setHorizontalScrollBarEnabled(false);
        options.setClipToPadding(false);
        options.addView(optionRow,new android.view.ViewGroup.LayoutParams(-2,-2));
        LinearLayout.LayoutParams optionsLp=new LinearLayout.LayoutParams(0,-2,1);
        optionsLp.leftMargin=dp(8);
        controls.addView(options,optionsLp);
        controls.addView(foldButton,new LinearLayout.LayoutParams(dp(40),dp(40)));
        optionsRow=options;
        applyComposerCollapsed();
        add(compose,controls,-2);add(dock,compose,-2);
        NativeUi.DockScroll dockScroll=new NativeUi.DockScroll(this);composerScroll=dockScroll;dockScroll.addView(dock);add(screen,dockScroll,-2);
        composer.addTextChangedListener(changed(()->updateSend()));
        sendButton.setOnClickListener(v->{String value=composer.getText().toString();ChatCache current=chatCaches.get(sessionKey);JSONArray images=new JSONArray();if(current!=null)for(JSONObject image:current.images)images.put(image.optString("id"));
            if(value.trim().isEmpty()&&images.length()==0){if(current!=null&&Boolean.TRUE.equals(current.running))submit("stop",new JSONObject());return;}
            try{JSONArray plugins=new JSONArray();if(current!=null)for(String plugin:current.plugins)plugins.put(plugin);JSONObject message=new JSONObject().put("text",value).put("images",images).put("plugins",plugins).put("mode","auto");if(current!=null&&Boolean.TRUE.equals(current.running)){JSONArray attachments=new JSONArray();for(JSONObject image:current.images)attachments.put(image);message.put("attachments",attachments);submit("queue",new JSONObject().put("operation","enqueue").put("message",message));}else{withdrawable=value;submit("send",message);}}catch(Exception e){showError(e);}});
        pendingControls();if(cache.page!=null){followingLatest=cache.followingLatest;renderSession(cache.page,false);if(cache.first>=0)messageList.setSelectionFromTop(cache.first,cache.top);}root.requestFocus();root.requestApplyInsets();getSharedPreferences("ui",0).edit().putBoolean("resumeChat",true).putString("lastSession",id).putString("lastTitle",cache.title).putString("lastBackend",backend).apply();
        if(cache.page==null)restoreConversation(sessionKey,epoch);if(!paused){final int generation=epoch;if(!sessionKey.equals(lastDesktopKey)){lastDesktopKey=sessionKey;fetch("/api/native/"+backend+"/"+id+"/open",new JSONObject(),generation,value->{});}poll(generation,id);}
    }
    private void updateSend(){if(sendButton==null)return;ChatCache current=chatCaches.get(sessionKey);boolean draft=composer!=null&&(!composer.getText().toString().trim().isEmpty()||(current!=null&&!current.images.isEmpty()));boolean stop=!draft&&current!=null&&Boolean.TRUE.equals(current.running);boolean enabled=!uploading&&!sending&&pending==null&&(draft||stop);sendButton.setEnabled(enabled);sendButton.setAlpha(enabled?1f:.6f);JSONObject usage=current!=null&&current.page!=null?current.page.contextUsage:null;boolean hasUsage=usage!=null&&usage.optBoolean("available");
        sendButton.setBackground(new ContextRing(getResources().getDisplayMetrics().density,usage));
        // The percentage and the arrow were both drawn at the button's centre, so the
        // arrow sat across the ring and over the number and neither was readable. The
        // ring carries the number; the arrow is only for the state where there is no
        // ring to read (no usage data yet), and stop is always an arrow.
        String label=hasUsage?ContextRing.label(usage):"";
        sendButton.setText(label);
        sendButton.setTextSize(hasUsage?NativeUi.CAPTION:0);
        sendButton.setTextColor(NativeUi.onPrimary());
        sendButton.setTooltipText(ContextRing.description(usage));
        sendButton.setContentDescription(stop?"停止执行":draft&&current!=null&&Boolean.TRUE.equals(current.running)?"加入队列":"发送消息");
        if(hasUsage&&!stop)sendButton.setForeground(null);
        else ui.centerIcon(sendButton,stop?"stop":"arrow",NativeUi.onPrimary(),20,0);
        // No extra bottom padding: it pushed the glyph off the ring's centre, so the icon
        // and the arc no longer shared an axis.
        sendButton.setPadding(0,0,0,0);
        if(chatMeta!=null)chatMeta.setText("");}
    private void showContextUsage(){ChatCache c=chatCaches.get(sessionKey);alert().setTitle("上下文窗口").setMessage(ContextRing.description(c==null||c.page==null?null:c.page.contextUsage)).setPositiveButton("知道了",null).show();}
    private void toggleComposerCollapsed(){composerCollapsed=!composerCollapsed;applyComposerCollapsed();}
    /** Folds or unfolds the chip row and points the caret at what the next tap will do. */
    private void applyComposerCollapsed(){
        if(optionsRow!=null)optionsRow.setVisibility(composerCollapsed?View.GONE:View.VISIBLE);
        if(foldButton!=null){
            boolean up=composerCollapsed;
            // "arrow" points up and "down" is the chevron; there is no "up" glyph in
            // Symbol, and an unknown name draws an empty drawable with no warning.
            // A larger glyph than the 22 used elsewhere in the row: this is the only
            // control whose whole job is to look like something to tap.
            ui.centerIcon(foldButton,up?"arrow":"down",INK,26,0);
            foldButton.setContentDescription(up?"展开输入选项":"收起输入选项");
        }
    }
    private void adaptComposer(){
        if(composer==null)return;composer.setMinLines(keyboardShown?1:3);if(historyBar!=null)historyBar.setVisibility(hasEarlier&&!keyboardShown?View.VISIBLE:View.GONE);
        if(actionHint!=null){
            // Stopping a task makes the message just sent available for re-editing.
            // The strip has two jobs and both need a way out. A stale pending request
            // has to be dismissable, otherwise a single unresolved submission left a
            // permanent bar above the composer; the recall action only appears once
            // there is actually a draft to restore.
            boolean stale=pending!=null;
            boolean withdraw=!stale&&permissionCount==0&&!withdrawable.isEmpty();
            actionHint.setText(stale?"上次操作待核对 · 点此处理":withdraw?"撤回刚才的消息并重新编辑":"");
            actionHint.setVisibility((stale||withdraw)&&!keyboardShown?View.VISIBLE:View.GONE);}
        if(permissionScroll!=null)permissionScroll.setVisibility(keyboardShown?View.GONE:View.VISIBLE);if(pendingPanel!=null)pendingPanel.setVisibility(keyboardShown?View.GONE:View.VISIBLE);
    }
    private void jumpToLatest(){
        if(messageList==null)return;
        navigationVersion++;historyTicket++;scrolling=false;requestedHeader=null;deferredPage=null;pageBefore=null;historyLoading=false;lastMessages="";followingLatest=true;setHeaderHidden(false);
        ChatCache current=chatCaches.get(sessionKey);
        if(current!=null&&current.page!=null)renderSession(current.page,false);
        // Deferred rather than posted straight: renderSession can leave a repaint queued,
        // and jumping against the adapter mid-repaint scrolled to a stale row. The retry
        // covers the first pass after a cold open, where the list has rows but has not
        // been laid out yet and the measured row height is still zero.
        final ListView list=messageList;
        // renderSession() queues its own positionBoundary() scroll, so navigation computed
        // inside it still sees the pre-jump viewport: the list is at the bottom a moment
        // later, but the latest handle had already been decided as "available" and stayed
        // on screen. Recompute once the pinned row has actually been placed.
        list.post(()->jumpToLastRow(list,0));
        pollEpoch=-1;poll(epoch,sessionId);
    }
    /** Pins the last row to the bottom, retrying once if the list was not laid out yet. */
    private void jumpToLastRow(ListView list,int attempt){
        if(list!=messageList)return;
        int count=messageAdapter==null?0:messageAdapter.getCount();
        if(count==0){updateNavigation();return;}
        int last=count-1;
        View row=messageAdapter.getView(last,null,list);
        int width=Math.max(1,list.getWidth()-list.getPaddingLeft()-list.getPaddingRight());
        row.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int viewport=list.getHeight()-list.getPaddingTop()-list.getPaddingBottom();
        if(viewport<=0&&attempt<2){list.post(()->jumpToLastRow(list,attempt+1));return;}
        list.setSelectionFromTop(last,viewport-row.getMeasuredHeight());
        // The handle state is derived from the viewport, and setSelectionFromTop only takes
        // effect on the next layout pass - so this has to wait for that pass to be truthful.
        list.post(()->{if(list==messageList)updateNavigation();});
    }
    private void positionBoundary(boolean bottom){final ListView list=messageList;final int version=navigationVersion;list.post(()->{if(list!=messageList||version!=navigationVersion||messageAdapter.getCount()==0)return;if(!bottom){list.setSelectionFromTop(0,0);return;}int last=messageAdapter.getCount()-1;View row=messageAdapter.getView(last,null,list);row.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1,list.getWidth()-list.getPaddingLeft()-list.getPaddingRight()),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));list.setSelectionFromTop(last,list.getHeight()-list.getPaddingTop()-list.getPaddingBottom()-row.getMeasuredHeight());});}

    private void syncIdentity(String kind,String id,String title,String cwd){syncIdentity(kind,id,title,cwd,++identityClock);}
    private void syncIdentity(String kind,String id,String title,String cwd,long version){
        if(id.isEmpty())return;String key=kind+":"+id;ChatCache cache=chatCaches.get(key);
        if(version<identityVersions.getOrDefault(key,0L)){if(cache==null)return;title=cache.title;cwd=cache.cwd;}else identityVersions.put(key,version);
        if(cache!=null){if(!title.isEmpty())cache.title=title;if(!cwd.isEmpty())cache.cwd=cwd;}
        ListCache list=listCaches.get(kind);if(list!=null)for(int i=0;i<list.rows.length();i++){JSONObject row=list.rows.optJSONObject(i);if(row!=null&&id.equals(row.optString("id")))try{if(!title.isEmpty())row.put("title",title);if(!cwd.isEmpty())row.put("cwd",cwd);}catch(Exception ignored){}}
        if(key.equals(sessionKey)){if(!title.isEmpty()&&sessionTitle!=null)sessionTitle.setText(title);if(!cwd.isEmpty())chatPath=cache!=null&&!cache.project.isEmpty()?cache.project:cwd;}
        if(id.equals(getSharedPreferences("ui",0).getString("lastSession",""))&&kind.equals(getSharedPreferences("ui",0).getString("lastBackend",""))&&!title.isEmpty())getSharedPreferences("ui",0).edit().putString("lastTitle",title).apply();
    }
    private void metadata(LinearLayout body,String label,String value){
        if(value==null||value.isEmpty()||value.equals("null"))return;TextView caption=text(label,NativeUi.CAPTION,MUTED);add(body,caption,-2);gap(body,6);
        TextView detail=text(value,NativeUi.BODY,INK);detail.setTextIsSelectable(true);
        // Values here are a mix of short states and full filesystem paths. A path is
        // truncated in the middle so both the root and the leaf stay visible; the
        // short ones wrap normally.
        boolean path=value.indexOf('\\')>=0||value.indexOf('/')>=0;
        if(path){detail.setSingleLine();detail.setEllipsize(TextUtils.TruncateAt.MIDDLE);}
        else detail.setMaxLines(3);
        add(body,detail,-2);gap(body,18);
    }
    private String appVersion(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception e){return "未知版本";}}
    private void sessionInfo(){
        LinearLayout body=column();ChatCache cache=chatCaches.get(sessionKey);SessionPage page=cache==null?null:cache.page;JSONObject meta=page==null?new JSONObject():page.source.optJSONObject("session");if(meta==null)meta=new JSONObject();
        metadata(body,"会话名称",sessionTitle==null?"未命名会话":sessionTitle.getText().toString());metadata(body,"客户端",providerName(backend));
        // The project and the session's own working directory can differ (a worktree,
        // or a workspace the desktop substituted); show both so the path is explicable.
        String projectCwd=meta.optString("projectCwd","");
        metadata(body,"项目目录",projectCwd.isEmpty()?(chatPath.isEmpty()?"未指定项目":chatPath):projectCwd);
        if(page!=null&&!page.cwd.isEmpty()&&!page.cwd.equals(projectCwd))metadata(body,"会话工作目录",page.cwd);
        metadata(body,"会话 ID",sessionId);metadata(body,"当前状态",page==null?"正在同步":page.running?"执行中":"空闲");metadata(body,"模型",page==null?"":page.model);metadata(body,"思考强度",page==null?"":page.effort);metadata(body,"审批方式",page==null||page.permissionMode.isEmpty()?"":modeName(page.permissionMode));metadata(body,"最近更新",sessionDate(meta));
        ScrollView scroll=new NativeUi.FormScroll(this);scroll.addView(body);LinearLayout wrapper=column();add(wrapper,scroll,-2);Dialog dialog=sheet("会话信息","会话的基本信息",wrapper);
        LinearLayout actions=row();gap(wrapper,12);add(wrapper,actions,48);Button git=button("源代码管理",false),files=button("工作目录",false);actions.addView(git,new LinearLayout.LayoutParams(0,dp(48),1));View space=new View(this);actions.addView(space,new LinearLayout.LayoutParams(dp(8),1));actions.addView(files,new LinearLayout.LayoutParams(0,dp(48),1));git.setOnClickListener(v->{dialog.dismiss();openGit();});files.setOnClickListener(v->{dialog.dismiss();browseFiles("");});
    }
    private void scheduleHeaderReveal(){handler.removeCallbacks(revealHeader);handler.postDelayed(revealHeader,2200);}
    private void updateNavigation(){
        if(messageList==null||floating==null)return;
        boolean jumpable=refreshNavigationTargets();
        // The handle follows the reader's attention: it returns while the list is
        // moving, or while a jump is genuinely available, and fades once the
        // transcript settles. Leaving it always on drew a white disc over the last
        // lines of every message for no benefit.
        // On the first pass the reader has not scrolled yet, so showing the handle
        // once is what tells them it exists at all.
        if(!navigationShown){navigationShown=true;floating.hold();}
        else if(scrolling||jumpable)floating.wake();else floating.rest();
        if(floating.latest.getAlpha()>=1f&&headerHidden){handler.removeCallbacks(revealHeader);handler.post(revealHeader);}}
    /**
     * Resolves which of the two jumps is available, from the list's state right now.
     *
     * Split out of updateNavigation because the two had different triggers. The buttons
     * were only re-resolved when the list scrolled, so after the handle had faded the
     * buttons still carried whatever visibility the last scroll left them: both could be
     * INVISIBLE on a handle that wake() had just brought back, and a tap then had no
     * child to land on. Nothing about a faded handle says its buttons are gone, which is
     * why this read as "the buttons do not work at all".
     *
     * Returns whether either jump is actually available, which is what decides between
     * waking the handle and letting it settle.
     */
    private boolean refreshNavigationTargets(){
        if(messageList==null||floating==null)return false;
        int count=messageList.getCount();
        View first=messageList.getChildAt(0),last=messageList.getChildAt(messageList.getChildCount()-1);
        boolean atTop=count==0||messageList.getFirstVisiblePosition()==0&&first!=null&&first.getTop()>=messageList.getPaddingTop()-dp(2)&&!hasEarlier;
        boolean atEnd=count==0||messageList.getLastVisiblePosition()==count-1&&last!=null&&last.getBottom()<=messageList.getHeight()-messageList.getPaddingBottom()+dp(2)&&pageBefore==null;
        // Never both, and never by INVISIBLE alone. An INVISIBLE child is skipped by the
        // parent's hit test, so hiding both - which is what a short transcript produced,
        // since it sits at the top and the end at the same time - left a handle with no
        // child under the finger and a tap that went nowhere. A disc that cannot act is
        // now drawn as a dimmed disc that still takes the tap and reports why.
        if(atTop&&atEnd)atEnd=false;
        applyTarget(floating.top,atTop);
        applyTarget(floating.latest,atEnd);
        return !atTop||!atEnd;
    }
    /** Dims a disc that has nowhere to jump, without taking it out of the hit test.
     *
     * Not setEnabled(false): a disabled View is skipped by the parent's hit test exactly
     * like an INVISIBLE one, so the tap would go nowhere again - which is the failure
     * this replaced. Alpha alone expresses "nothing to do here" while the disc still
     * receives the touch, and the click handler decides what to do about it. */
    private void applyTarget(Button disc,boolean unavailable){
        disc.setAlpha(unavailable?0.3f:1f);
        disc.setVisibility(View.VISIBLE);
        disc.setContentDescription(unavailable
            ?(disc==floating.top?"已经在顶部":"已经在最新位置")
            :(disc==floating.top?"回到顶部":"回到最新"));
    }
    void messageActions(String source){messageActions(new SessionPage.Row("selection","assistant",source,false));}
    private boolean selectingText;
    void textSelection(boolean active){selectingText=active;if(!active)handler.post(()->{if(!scrolling&&deferredPage!=null){SessionPage page=deferredPage;deferredPage=null;renderSession(page,false);}});}
    void quoteText(String source){if(composer==null||source.isEmpty())return;String draft=composer.getText().toString();composer.setText(draft+(draft.isEmpty()?"":"\n")+"> "+source.replace("\n","\n> ")+"\n");composer.setSelection(composer.length());}
    @Override public void onUserInteraction(){super.onUserInteraction();scheduleHeaderReveal();}
    private void selectMessageText(String source){LinearLayout body=column();TextView chosen=text(source,NativeUi.TITLE,INK);new MessageContent(this,ui).selectable(chosen,source);chosen.setOnLongClickListener(null);ScrollView scroll=new NativeUi.InnerScroll(this);scroll.addView(chosen);add(body,scroll,340);sheet("选择文字","拖动选择手柄，复制或引用所选内容",body);}
    void messageActions(SessionPage.Row row){LinearLayout actions=column();Dialog dialog=sheet("消息操作","",actions);for(String name:new String[]{"复制全文","选择部分文字","引用到输入框","分享","折叠全部执行过程"}){Button button=button(name,false);add(actions,button,48);button.setOnClickListener(v->{if(name.equals("复制全文")){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("消息",row.text));toast("已复制");}else if(name.equals("选择部分文字")){selectMessageText(row.text);}else if(name.equals("引用到输入框")){quoteText(row.text);}else if(name.equals("分享")){startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,row.text),"分享消息"));}else{ChatCache cache=chatCaches.get(sessionKey);if(cache!=null){cache.expanded.clear();for(SessionPage.Row step:messageAdapter.rows)cache.collapsed.add(step.id);}messageAdapter.notifyDataSetChanged();}dialog.dismiss();});}}
    private void setHeaderHidden(boolean hide){
        if(chatHeader==null||hide==headerHidden||hide&&!getSharedPreferences("ui",0).getBoolean("hideHeader",true))return;
        // Keep the list's viewport stable: hiding the header must never trigger
        // a layout/scroll correction loop at the bottom boundary.
        headerHidden=hide;chatHeader.animate().cancel();chatHeader.setVisibility(View.VISIBLE);
        chatHeader.setImportantForAccessibility(hide?View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS:View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if(historyBar!=null)historyBar.animate().translationY(hide?-chatHeader.getHeight():0).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        chatHeader.animate().translationY(hide?-chatHeader.getHeight():0).alpha(hide?0:1).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }
    private void finishScroll(){if(messageList==null||scrolling||fastScroller!=null&&fastScroller.isDragging())return;if(deferredPage!=null){SessionPage page=deferredPage;deferredPage=null;renderSession(page,false);}if(requestedHeader!=null){boolean hide=requestedHeader;requestedHeader=null;final ListView list=messageList;list.post(()->{if(list==messageList&&!scrolling)setHeaderHidden(hide);});}}
    private void jumpToTop(){
        if(messageList==null)return;
        navigationVersion++;scrolling=false;deferredPage=null;requestedHeader=null;followingLatest=false;setHeaderHidden(false);
        ChatCache c=chatCaches.get(sessionKey);
        // Always move the list. Loading the earlier page is an extra, not a substitute:
        // the old code fetched history and then returned without ever scrolling, so with
        // any earlier page available "back to top" moved nothing at all. The fetch still
        // happens, but the jump does not wait on it.
        final ListView list=messageList;
        final int version=navigationVersion;
        if(c!=null&&c.page!=null&&c.page.hasEarlier&&!historyLoading){
            pageBefore=60;historyLoading=true;earlierButton.setEnabled(false);
            fetchPage(epoch,sessionId,60);
        }
        list.post(()->{
            // Always move the list, even while an earlier-history fetch is in flight. This
            // guard used to bail whenever historyLoading was set - and jumpToTop sets it
            // itself just above, so the scroll never happened and the top button looked
            // dead. The fetched page scrolls on its own after it lands; the reader's own
            // jump must not be swallowed while waiting for it.
            if(list!=messageList||version!=navigationVersion)return;
            list.setSelectionFromTop(0,0);
            // setHeaderHidden above animates the chat header open, and the header changing
            // height re-lays the list out around whatever row was visible - which put the
            // transcript back where it started a frame later. Re-assert the jump once that
            // animation has finished so the reader actually lands on the first message.
            list.postDelayed(()->{if(list==messageList&&version==navigationVersion)list.setSelectionFromTop(0,0);},220);
        });
    }

    private final class MessageAdapter extends BaseAdapter {
        ArrayList<SessionPage.Row> rows=new ArrayList<>();public int getCount(){return rows.size();}public Object getItem(int p){return rows.get(p);}public long getItemId(int p){return p;}
        public View getView(int position,View recycled,ViewGroup parent){
            LinearLayout outer=recycled instanceof LinearLayout?(LinearLayout)recycled:column();outer.removeAllViews();outer.setPadding(0,dp(12),0,dp(8));
            SessionPage.Row row=rows.get(position);boolean user=row.role.equals("user"),activity=row.activity();ChatCache cache=chatCaches.get(sessionKey);
            LinearLayout content=column();
            outer.setOnLongClickListener(v->{messageActions(row);return true;});
            if(activity&&!row.imageStep()&&!row.kind.equals("executionGroup")&&!row.text.trim().contains("\n")&&row.text.length()<160){new MessageContent(MainActivity.this,ui).render(content,row.text,false);outer.setPadding(0,dp(2),0,dp(2));add(outer,content,-2);return outer;}
            if(activity){outer.setPadding(0,dp(2),0,dp(2));
                boolean expanded=cache!=null&&(cache.expanded.contains(row.id)||row.kind.equals("image")&&Boolean.TRUE.equals(cache.running)&&!cache.collapsed.contains(row.id));
                // A rail down the left edge ties an expanded group to its header, so
                // the folded rows read as belonging to the toggle above them rather
                // than as another sibling message.
                LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);
                View rail=new View(MainActivity.this);rail.setBackgroundColor(expanded?GREEN:NativeUi.LINE);
                LinearLayout.LayoutParams railLp=new LinearLayout.LayoutParams(dp(2),dp(expanded?0:28));
                railLp.setMargins(dp(6),0,dp(4),0);
                head.addView(rail,expanded?new LinearLayout.LayoutParams(dp(2),-2):railLp);
                // The chevron trails the label rather than leading it. A leading glyph is
                // laid out before the text, so on a Button - which brings its own minimum
                // width and compound-drawable padding - the label is the part that gets
                // squeezed, and the row collapsed to a bare arrow with a blank label.
                // Trailing it leaves the text at the natural start of the line.
                // One line of text tall, and no taller. button() pins a 48dp minimum
                // height for a touch target, which is right for a real button but here
                // it forced a single line of label into a 48dp cell and the label ended
                // up clipped against the padding. compact() clears the minimum and the
                // height is set to the measured text line plus symmetric padding.
                Button toggle=button(row.label()+(row.continuation?" · 续":""),false);
                compact(toggle);
                toggle.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
                toggle.setTextColor(expanded?GREEN:MUTED);
                toggle.setTextSize(NativeUi.LABEL);
                toggle.setIncludeFontPadding(false);
                android.graphics.Paint.FontMetrics metrics=toggle.getPaint().getFontMetrics();
                int lineH=Math.max(dp(30),(int)Math.ceil(metrics.descent-metrics.ascent)+dp(10));
                toggle.setPadding(dp(12),0,dp(10),0);
                toggle.setBackground(ui.touch(expanded?NativeUi.TINT:NativeUi.SOFT,NativeUi.CORNER_CHIP,true));
                ui.trailing(toggle,expanded?"down":"right",expanded?GREEN:MUTED,NativeUi.GLYPH_INLINE);
                toggle.setContentDescription((expanded?"折叠":"展开")+row.label());
                head.addView(toggle,new LinearLayout.LayoutParams(0,lineH,1));
                // The row draws one text line tall, but still takes a 48dp tap. Sized
                // purely to its text it would be about 30dp, which is short of the
                // minimum touch target; the delegate widens the hit rect around it
                // without adding visible height back to the row.
                //
                // Like the statistics strip, the delegate is installed on the parent and
                // forwards to the button. A delegate pointing at its own View recurses
                // through onTouchEvent until the stack runs out, and this is the row that
                // made expanding a step group close the app.
                head.post(()->{
                    int w=toggle.getWidth(),h=toggle.getHeight();
                    if(w<=0||h<=0)return;
                    int grow=Math.max(0,(dp(NativeUi.TOUCH)-h)/2+dp(6));
                    android.graphics.Rect hit=new android.graphics.Rect(0,-grow,w,h+grow);
                    head.setTouchDelegate(new android.view.TouchDelegate(hit,toggle));
                });
                add(outer,head,-2);
                toggle.setOnLongClickListener(v->{messageActions(row);return true;});toggle.setOnClickListener(v->{if(cache==null)return;if(expanded){cache.expanded.remove(row.id);cache.collapsed.add(row.id);}else{cache.expanded.add(row.id);cache.collapsed.remove(row.id);}followingLatest=false;latestButton.setVisibility(View.VISIBLE);notifyDataSetChanged();});
                if(expanded){content.setPadding(dp(16),dp(6),dp(6),0);if(row.kind.equals("code"))new MessageContent(MainActivity.this,ui).code(content,row.text,row.language);else new MessageContent(MainActivity.this,ui).render(content,row.text,false);if(row.kind.equals("executionGroup")){ScrollView records=new NativeUi.InnerScroll(MainActivity.this);records.addView(content);add(outer,records,260);}else add(outer,content,-2);}return outer;
            }
            if(user){content.setPadding(dp(16),dp(14),dp(16),dp(6));content.setBackground(ui.surface(NativeUi.SOFT,NativeUi.CORNER_ROW,false));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.leftMargin=dp(40);outer.addView(content,lp);}
            else{if(position==0||!rows.get(position-1).role.equals("assistant")){TextView author=text(providerName(backend),NativeUi.CAPTION,MUTED);medium(author);ui.leading(author,backend.equals("claude")?"spark":"code",GREEN,NativeUi.GLYPH_INLINE);add(outer,author,28);gap(outer,6);}add(outer,content,-2);}
            if(row.continuation){TextView continued=text("接上段",NativeUi.CAPTION,MUTED);add(content,continued,-2);gap(content,6);}if(row.kind.equals("snippet"))new MessageContent(MainActivity.this,ui).code(content,row.text,row.language);else new MessageContent(MainActivity.this,ui).render(content,row.text,user);return outer;
        }
    }
    private void loadEarlier(){
        if(historyLoading||messageAdapter==null)return;ChatCache cache=chatCaches.get(sessionKey);if(cache==null)return;
        final int before=earlierButton.getTag() instanceof Integer?(Integer)earlierButton.getTag():0;if(before<=0)return;
        pageBefore=before;historyLoading=true;if(nextPoll!=null)handler.removeCallbacks(nextPoll);
        // poll() bails out when pollEpoch already equals the generation, so cancelling
        // the poll without clearing that guard would make the resumed poll a no-op.
        pollEpoch=-1;
        earlierButton.setEnabled(false);earlierButton.setText("正在读取…");latestButton.setVisibility(View.VISIBLE);
        fetchPage(epoch,sessionId,before);
    }
    private void poll(int generation,String id){
        if(id==null||paused||isDestroyed()||generation!=epoch||!id.equals(sessionId)||pollEpoch==generation)return;
        pollEpoch=generation;fetchPage(generation,id,null);
    }
    private void fetchPage(int generation,String id,Integer before){
        final String host=origin,credential=token,key=backend+":"+id;
        ChatCache previous=before==null?chatCaches.get(key):null;
        // An idle conversation that has not changed asks the computer for nothing.
        String known=previous!=null&&previous.page!=null&&!previous.page.running?previous.page.token:"";
        final String path="/api/native/"+backend+"/"+id+(before!=null?"?before="+before:(known.isEmpty()?"":"?known="+Uri.encode(known)));
        final long identityVersion=++identityClock;final int ticket=before==null?++pollTicket:++historyTicket;
        ExecutorService pool=before!=null?historyWorkers:liveWorkers;
        pool.execute(()->{try{JSONObject raw=request(host,credential,path,null);
            if(before==null&&raw.optBoolean("unchanged",false)){runOnUiThread(()->{
                if(ticket!=pollTicket||isDestroyed()||!host.equals(origin)||!credential.equals(token))return;pollEpoch=-1;if(generation!=epoch)return;
                JSONObject session=raw.optJSONObject("session");if(session!=null)syncIdentity(backend,id,session.optString("title"),session.optString("cwd"),identityVersion);
                JSONArray queue=raw.optJSONArray("queue");if(queue!=null)queuedMessages=queue;renderQueue();
                connection("已同步",true);schedulePoll(generation,id,3000);
            });return;}
            SessionPage result=SessionPage.prepare(raw);
            if(before==null&&!result.revision.equals(writtenRevisions.put(host+key,result.revision)))ConversationCache.write(this,host+credential+key,raw);
            runOnUiThread(()->{
            if(ticket!=(before==null?pollTicket:historyTicket))return;if(before==null&&pollEpoch==generation)pollEpoch=-1;if(isDestroyed()||!host.equals(origin)||!credential.equals(token))return;
            if(before==null){ChatCache cache=chatCaches.get(key);if(cache!=null)cache.page=result;syncIdentity(key.substring(0,key.indexOf(':')),id,result.title,result.cwd,identityVersion);}
            if(generation!=epoch)return;if(before==null&&pageBefore!=null){observeCompletion(result);connection(result.running?"正在思考…":"已同步",result.connected);schedulePoll(generation,id,2500);return;}if(!java.util.Objects.equals(before,pageBefore))return;historyLoading=false;
            renderSession(result,before!=null);schedulePoll(generation,id,result.running?1000:2200);
        });}catch(Exception e){runOnUiThread(()->{if(ticket!=(before==null?pollTicket:historyTicket))return;if(before==null&&pollEpoch==generation)pollEpoch=-1;if(generation!=epoch||isDestroyed()||before!=null&&!java.util.Objects.equals(before,pageBefore))return;historyLoading=false;earlierButton.setEnabled(true);
            if(e instanceof ApiError&&((ApiError)e).code==401){showError(e);return;}
            status.setText(before==null?"连接暂时中断，正在重试…":"历史读取失败，可点击回到最新重试");connection(before==null?"连接中断":"历史读取失败",false);earlierButton.setText("重试读取更早消息");latestButton.setVisibility(View.VISIBLE);
            // loadEarlier() cancels the poll while a page is in flight, so the poll
            // has to come back on the failure path too. It only used to restart for
            // a failed poll, which meant one failed history read froze the whole
            // conversation: the transcript stopped updating with no way to resume
            // short of leaving and reopening the session.
            schedulePoll(generation,id,before==null?5000:2500);
        });}});
    }
    private void schedulePoll(int generation,String id,int delay){if(nextPoll!=null)handler.removeCallbacks(nextPoll);if(paused||isDestroyed()||generation!=epoch)return;nextPoll=()->poll(generation,id);handler.postDelayed(nextPoll,delay);}
    /**
     * Reacts to the turn ending: fold the whole execution process away and leave the
     * summary.
     *
     * Clearing `expanded` was not enough. A row is expanded when it is in `expanded`,
     * and an activity row also re-opens itself when it is *not* in `collapsed` - so
     * clearing the open set handed the decision back to the default, and the run the
     * user had just watched re-expanded itself a moment after it finished. Both sets
     * are written here: nothing open, everything explicitly closed.
     */
    private void observeCompletion(SessionPage page){
        ChatCache state=chatCaches.get(sessionKey);if(state==null)return;
        boolean finished=!page.running&&(Boolean.TRUE.equals(state.running)||!page.completed.isEmpty()&&!page.completed.equals(state.completed));
        if(finished){
            state.expanded.clear();
            // Every activity row in the finished page is pinned closed, so the turn
            // reads as its own summary plus the folded header above it.
            if(messageAdapter!=null)for(SessionPage.Row row:messageAdapter.rows)if(row.activity())state.collapsed.add(row.id);
            taskExpanded=false;taskRevision="";
            if(messageAdapter!=null)messageAdapter.notifyDataSetChanged();
            renderTasks(page);
            // The transcript is scrolled to the summary rather than left wherever the
            // last expanded row happened to put it.
            if(followingLatest)positionBoundary(true);
        }
        state.completed=page.completed;state.running=page.running;updateSend();
    }
    private void renderSession(SessionPage page,boolean history){
        if(messageAdapter==null)return;if((selectingText||scrolling||fastScroller!=null&&fastScroller.isDragging())&&!history){deferredPage=page;return;}
        ChatCache previous=chatCaches.get(sessionKey);if(previous!=null&&Boolean.TRUE.equals(previous.running)&&!page.running)taskExpanded=false;connection(page.connected?(page.running?"正在思考…":"已同步"):"正在重连",page.connected);ChatCache identity=chatCaches.get(sessionKey);if(identity!=null&&!page.projectCwd.isEmpty())identity.project=page.projectCwd;chatPath=identity!=null&&!identity.project.isEmpty()?identity.project:identity!=null&&!identity.cwd.isEmpty()?identity.cwd:page.cwd;if(accessLabel!=null&&!page.permissionMode.isEmpty())accessLabel.setText("审批 · "+modeName(page.permissionMode));recordCount.setText(page.turns+" 轮对话");if(sessionTitle!=null)sessionTitle.setText(identity!=null&&!identity.title.isEmpty()?identity.title:page.title);
        // The chips carry the shortest label that still says what they set; the value
        // itself is ellipsised by the chip's own width cap and stated in full in the
        // content description, which is what a screen reader announces and what the
        // picker shows. Writing the whole model name into the chip is what made the row
        // wider than the screen.
        modelLabel.setText("模型");
        modelLabel.setContentDescription("模型："+(page.model.isEmpty()?"默认":page.model)+"，点击选择");
        effortLabel.setText("思考");
        effortLabel.setContentDescription("思考强度："+(page.effort.isEmpty()||page.effort.equals("null")?"默认":page.effort)+"，点击选择");
        // The plugin chip is driven from the cache rather than from a page field, so it
        // needs the same refresh the two chips above get. Without it a reopened session
        // showed "插件" while the selection was in fact still loaded.
        ChatCache pluginCache=chatCaches.get(sessionKey);
        if(pluginCache!=null)showPluginCount(pluginCache);
        renderTasks(page);
        int first=messageList.getFirstVisiblePosition(),top=messageList.getChildCount()>0?messageList.getChildAt(0).getTop()-messageList.getPaddingTop():0;
        boolean atEnd=followingLatest;
        if(!history)observeCompletion(page);
        if(!page.revision.equals(lastMessages)){
            int anchor=-1;if(!atEnd&&!history&&first<messageAdapter.rows.size()){String id=messageAdapter.rows.get(first).id;for(int i=0;i<page.rows.size();i++)if(page.rows.get(i).id.equals(id)){anchor=i;break;}}
            if(atEnd||history||messageAdapter.rows.isEmpty()||anchor>=0){lastMessages=page.revision;messageAdapter.rows=page.rows;messageAdapter.notifyDataSetChanged();
            if(history)messageList.setSelection(0);else if(atEnd)positionBoundary(true);else messageList.setSelectionFromTop(anchor,top);}
        }
        earlierButton.setTag(page.before);hasEarlier=page.hasEarlier;historyBar.setVisibility(page.hasEarlier&&!keyboardShown?View.VISIBLE:View.GONE);earlierButton.setText("查看更早的消息");earlierButton.setEnabled(!historyLoading);int transcriptPadding=chatHeader.getHeight()+dp(hasEarlier?42:10);if(messageList.getPaddingTop()!=transcriptPadding)messageList.setPadding(dp(20),transcriptPadding,dp(20),dp(20));
        updateNavigation();
        if(!sending&&pending==null)status.setText(page.notice);
        queuedMessages=page.source.optJSONArray("queue");if(queuedMessages==null)queuedMessages=new JSONArray();renderQueue();renderPermissions(page);adaptComposer();
    }

    private void renderQueue(){
        if(queuePanel==null)return;queuePanel.removeAllViews();if(queuedMessages.length()==0){queuePanel.setVisibility(View.GONE);return;}queuePanel.setVisibility(View.VISIBLE);
        Button title=button((queueExpanded?"⌄ ":"› ")+"排队消息 · "+queuedMessages.length(),false);title.setTextSize(NativeUi.CAPTION);title.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);title.setBackground(ui.touch(NativeUi.TINT,NativeUi.CORNER_ROW,false));title.setMinHeight(0);title.setMinimumHeight(0);add(queuePanel,title,36);title.setOnClickListener(v->{queueExpanded=!queueExpanded;renderQueue();});
        if(!queueExpanded)return;
        LinearLayout items=column();ScrollView scroll=new ScrollView(this);scroll.addView(items);add(queuePanel,scroll,Math.min(220,queuedMessages.length()*100));
        for(int i=0;i<queuedMessages.length();i++){JSONObject item=queuedMessages.optJSONObject(i);if(item==null)continue;JSONObject message=item.optJSONObject("body");String state=item.optString("state");TextView text=text((message==null?"":message.optString("text").isEmpty()?"图片消息":message.optString("text"))+(state.equals("unknown")?" · 结果待核对":state.equals("sending")?" · 提交中":state.equals("rejected")?" · 未发送":""),NativeUi.LABEL,INK);text.setMaxLines(2);text.setEllipsize(TextUtils.TruncateAt.END);text.setPadding(dp(8),dp(7),dp(8),0);add(items,text,-2);
            LinearLayout actions=row();for(String operation:new String[]{"recall","remove","sendnow"}){Button action=button(operation.equals("recall")?"撤回编辑":operation.equals("remove")?"删除":"立即发送",false);action.setTextSize(NativeUi.CAPTION);action.setMinHeight(dp(48));action.setMinimumHeight(dp(48));action.setPadding(dp(8),0,dp(8),0);action.setBackground(ui.touch(Color.TRANSPARENT,NativeUi.CORNER_CHIP,false));action.setEnabled(!sending&&pending==null&&(state.equals("waiting")||state.equals("rejected")||state.equals("unknown")&&operation.equals("remove")));actions.addView(action,new LinearLayout.LayoutParams(0,dp(48),1));action.setContentDescription(action.getText()+" "+item.optString("id"));action.setOnClickListener(v->{if(operation.equals("recall")&&composer.length()>0){toast("请先发送或清空输入框，再撤回编辑");return;}try{JSONObject actionBody=new JSONObject().put("operation",operation).put("queueId",item.optString("id"));if(state.equals("unknown")){alert().setTitle("已在电脑核对这条消息？").setMessage("仅从队列移除，不会撤销电脑上已执行的内容。").setPositiveButton("已核对，移除",(d,w)->{try{actionBody.put("confirmed",true);submit("queue",actionBody);}catch(Exception e){showError(e);}}).setNegativeButton("取消",null).show();}else submit("queue",actionBody);}catch(Exception e){showError(e);}});}add(items,actions,48);
        }
    }
    private void submit(String action,JSONObject body){
        if(sending)return;if(pending!=null){toast("请先核对上次提交结果");return;}
        try{body.put("id",UUID.randomUUID().toString());pending=new JSONObject().put("path","/api/native/"+backend+"/"+sessionId+"/"+action).put("body",body).put("origin",origin);
            if(!getSharedPreferences("pending",0).edit().putString("request",pending.toString()).commit()){pending=null;throw new Exception("无法保存提交标识，消息未发送");}sendPending();
        }catch(Exception e){showError(e);}
    }
    private void clearPending(){pending=null;getSharedPreferences("pending",0).edit().remove("request").apply();}
    private void sendPending(){
        if(pending==null||sending)return;
        try{if(!origin.equals(pending.optString("origin")))throw new Exception("上次提交属于其他连接，请先在电脑核对");
            final JSONObject saved=pending,body=saved.getJSONObject("body");final String path=saved.getString("path"),host=origin,credential=token;final int generation=epoch;
            sending=true;pendingControls();
            workers.execute(()->{try{JSONObject result=request(host,credential,path,body);String outcome=result.optString("status","unknown");
                runOnUiThread(()->{sending=false;if(pending!=saved)return;
                    if(!outcome.equals("unknown"))clearPending();
                    boolean queued=path.endsWith("/queue")&&body.optString("operation").equals("enqueue");
                    if(!outcome.equals("unknown")&&!outcome.equals("rejected")&&(path.endsWith("/send")||queued)){
                        String[] parts=path.split("/");ChatCache cache=parts.length>4?chatCaches.get(parts[3]+":"+parts[4]):null;if(cache!=null){cache.draft="";cache.images.clear();}
                    }
                    pendingControls();if(generation!=epoch)return;
                    if(outcome.equals("unknown")){status.setText("结果不确定，请检查电脑会话；可重查原请求。");return;}
                    if(outcome.equals("rejected")){status.setText(result.optString("message","桌面未接受该操作"));return;}
                    if((path.endsWith("/send")||queued)&&composer!=null){JSONObject original=queued?body.optJSONObject("message"):body;if(original!=null&&composer.getText().toString().equals(original.optString("text")))composer.setText("");renderAttachments();}
                    // Remember what was just sent so the recall strip has something to
                    // restore. Nothing ever wrote this field before, so the "撤回刚才
                    // 的消息并重新编辑" action could never become available - the only
                    // thing the strip ever showed was the stale-pending warning.
                    if(path.endsWith("/send")&&body.optString("text").length()>0)withdrawable=body.optString("text");
                    if(path.endsWith("/settings")||path.endsWith("/access")||path.endsWith("/goal")){pollTicket++;ChatCache current=chatCaches.get(sessionKey);if(current!=null)current.catalog=null;
                        if(path.endsWith("/access")){
                            // Show what the computer reports, not what we asked for: a
                            // rejected change must not look applied on the phone.
                            String reported=result.optString("mode",body.optString("mode"));
                            JSONObject actual=result.optJSONObject("actual");
                            if(actual!=null)reported=actual.optString("permissionMode",reported);
                            if(reported.isEmpty())reported=body.optString("mode");
                            accessLabel.setText("审批 · "+modeName(reported));
                            if(outcome.equals("rejected"))status.setTextColor(NativeUi.RED);
                        }
                        status.setText(outcome.equals("rejected")?"电脑未接受该设置："+result.optString("message",""):"设置已送达电脑，正在核对…");}
                    if(path.endsWith("/queue")&&body.optString("operation").equals("recall")){JSONObject recalled=result.optJSONObject("messageBody");if(recalled!=null){composer.setText(recalled.optString("text")+(composer.length()>0?"\n"+composer.getText():""));ChatCache current=chatCaches.get(sessionKey);if(current!=null){JSONArray plugins=recalled.optJSONArray("plugins");current.plugins.clear();if(plugins!=null)for(int i=0;i<plugins.length();i++)current.plugins.add(plugins.optString(i));savePlugins(current);JSONArray attachments=recalled.optJSONArray("attachments");if(attachments!=null)for(int i=0;i<attachments.length();i++){JSONObject image=attachments.optJSONObject(i);if(image!=null)current.images.add(image);}renderAttachments();}}}status.setTextColor(GREEN);status.setText(outcome.equals("queued")?"已加入队列":"已送达电脑");if(sessionId!=null){pollEpoch=-1;poll(epoch,sessionId);}});
            }catch(Exception e){runOnUiThread(()->{sending=false;pendingControls();if(generation!=epoch)return;status.setText("提交结果未确认，请检查电脑会话，再重查原请求。");});}});
        }catch(Exception e){sending=false;showError(e);}
    }

    private LinearLayout settingsGroup(LinearLayout parent,String icon,String title,String value){
        // The header and its body share one card, but they are no longer painted the
        // same. The header is a filled row and the body is a distinctly lighter well
        // inside the card, so which part is the control and which is the content is
        // legible without reading anything.
        LinearLayout card=column();card.setBackground(ui.surface(NativeUi.WHITE,NativeUi.CORNER_CARD,true));
        card.setClipToOutline(true);

        LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(16),0,dp(14),0);
        line.addView(ui.icon(icon,INK,NativeUi.GLYPH_ACTION),new LinearLayout.LayoutParams(dp(24),dp(24)));
        LinearLayout titles=column();titles.setGravity(Gravity.CENTER_VERTICAL);
        TextView label=text(title,NativeUi.TITLE,INK);NativeUi.emphasize(label);
        titles.addView(label,-2);
        // The current value used to sit at the far right, competing with the chevron for
        // the same visual slot. Under the title it reads as the row's setting rather than
        // as another control.
        if(!value.isEmpty()){TextView selected=text(value,NativeUi.CAPTION,MUTED);titles.addView(selected,-2);}
        LinearLayout.LayoutParams titlesLp=new LinearLayout.LayoutParams(0,-2,1);
        titlesLp.leftMargin=dp(14);
        line.addView(titles,titlesLp);
        ImageView marker=ui.icon("right",MUTED,NativeUi.GLYPH_INLINE);
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(18),dp(18));mp.leftMargin=dp(8);
        line.addView(marker,mp);

        // The well: inset from the card's own edge on every side, tinted against the
        // header, and given its own corner so it reads as a container rather than as the
        // header's fill having leaked downward.
        LinearLayout well=column();
        well.setBackground(ui.surface(NativeUi.SOFT,NativeUi.CORNER_ROW,false));
        LinearLayout body=column();
        body.setPadding(dp(14),dp(10),dp(14),dp(12));
        well.addView(body,new LinearLayout.LayoutParams(-1,-2));
        well.setVisibility(View.GONE);

        card.addView(line,new LinearLayout.LayoutParams(-1,dp(64)));
        card.addView(well,new LinearLayout.LayoutParams(-1,-2));
        add(parent,card,-2);gap(parent,NativeUi.SPACE_STACK);
        // Only the header row responds to a tap, so a control inside the open body
        // does not also toggle the group.
        line.setBackground(ui.touch(NativeUi.WHITE,NativeUi.CORNER_CARD,false));
        line.setContentDescription(title+(value.isEmpty()?"":"，当前 "+value));
        line.setOnClickListener(v->{boolean open=well.getVisibility()!=View.VISIBLE;
            // The chevron is a vector glyph, not "›"/"⌄": those render as tofu or sit off
            // the baseline depending on the device font, and the app already ships a
            // Symbol for exactly this.
            marker.setImageDrawable(new NativeUi.Symbol(open?"down":"right",open?GREEN:MUTED,dp(NativeUi.GLYPH_INLINE)));
            // The header tints while open so the row itself reports its state.
            line.setBackground(ui.touch(open?NativeUi.TINT:NativeUi.WHITE,NativeUi.CORNER_CARD,false));
            togglePanel(well,open);});return body;
    }
    private String themeName(String key){for(int i=0;i<NativeUi.THEMES.length;i++)if(NativeUi.THEMES[i].equals(key))return NativeUi.THEME_NAMES[i];return NativeUi.THEME_NAMES[0];}
    private void settingsSection(LinearLayout parent,String name){
        gap(parent,NativeUi.SPACE_GROUP);
        LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label=text(name,NativeUi.CAPTION,GREEN);
        NativeUi.strong(label);
        label.setLetterSpacing(.08f);
        head.addView(label,-2);
        // A hairline running from the label to the edge, which groups the cards below
        // it without adding another filled block to the page.
        View line=new View(this);line.setBackgroundColor(NativeUi.rule(NativeUi.WHITE));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(1),1);lp.leftMargin=dp(12);
        head.addView(line,lp);
        add(parent,head,28);
    }
    private void settings(){
        bars(View.GONE);appBar.removeAllViews();appBar.setVisibility(View.VISIBLE);inset(appBar,12,4);Button back=ui.iconButton("back","返回");appBar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));back.setOnClickListener(v->showTab(settingsReturn));TextView title=text("设置",NativeUi.HEADING,INK);medium(title);appBar.addView(title);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);LinearLayout content=column();content.setPadding(dp(NativeUi.SPACE_PAGE),dp(4),dp(NativeUi.SPACE_PAGE),dp(28));
        settingsSection(content,"通用");
        LinearLayout appearance=settingsGroup(content,"settings","主题配色",themeName(getSharedPreferences("ui",0).getString("theme","light")));
        GridLayout themes=new GridLayout(this);themes.setColumnCount(4);themes.setContentDescription("主题配色网格");String[] keys=NativeUi.THEMES,labels=NativeUi.THEME_NAMES;
        appearance.setPadding(0,0,0,dp(8));
        for(int i=0;i<keys.length;i++){final String key=keys[i];int[] swatch=NativeUi.preview(key);
            // Three swatches per theme: canvas, primary and secondary. A single dot
            // made every theme look like one colour.
            boolean current=getSharedPreferences("ui",0).getString("theme","light").equals(key);
            LinearLayout card=column();card.setPadding(dp(6),dp(6),dp(6),dp(6));card.setBackground(ui.touch(swatch[0],NativeUi.CORNER_ROW,true));
            card.setContentDescription(labels[i]+"主题"+(current?"，当前使用":""));
            LinearLayout dots=row();dots.setGravity(Gravity.CENTER);for(int color:swatch){View dot=new View(this);dot.setBackground(ui.surface(color,NativeUi.CORNER_CHIP,false));LinearLayout.LayoutParams dlp=new LinearLayout.LayoutParams(0,dp(14),1);dlp.setMargins(dp(2),0,dp(2),0);dots.addView(dot,dlp);}card.addView(dots,-2);
            // The selected marker is a check glyph plus colour, not a "✓" character:
            // the text form sat above the label's cap height and read as a stray tick.
            LinearLayout caption=row();caption.setGravity(Gravity.CENTER);
            if(current){ImageView mark=ui.icon("check",GREEN,NativeUi.GLYPH_INLINE);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(12),dp(12));mp.rightMargin=dp(3);caption.addView(mark,mp);}
            TextView name=text(labels[i],NativeUi.CAPTION,current?GREEN:INK);name.setGravity(Gravity.CENTER);caption.addView(name,-2);
            card.addView(caption,-2);
            GridLayout.LayoutParams lp=new GridLayout.LayoutParams(GridLayout.spec(i/4),GridLayout.spec(i%4,1f));lp.width=0;lp.height=dp(58);lp.setMargins(dp(3),dp(4),dp(3),dp(4));themes.addView(card,lp);
            card.setOnClickListener(v->{getSharedPreferences("ui",0).edit().putString("theme",key).apply();recreate();});}add(appearance,themes,-2);
        LinearLayout pages=settingsGroup(content,"folder","显示页面","");for(String key:new String[]{"claude","codex","deepseek"}){Switch toggle=settingSwitch(pages,providerName(key),"show_"+key,true);toggle.setOnCheckedChangeListener((v,on)->{int visible=0;for(String k:new String[]{"claude","codex","deepseek"})if(!k.equals(key)&&getSharedPreferences("ui",0).getBoolean("show_"+k,true))visible++;if(!on&&visible==0){toast("至少保留一个会话页面");toggle.setChecked(true);return;}getSharedPreferences("ui",0).edit().putBoolean("show_"+key,on).apply();});}
        // Grouped like every other settings block; these three used to sit loose in
        // the scrolling list, visually unrelated to the sections around them.
        LinearLayout behaviour=settingsGroup(content,"spark","通知与悬浮窗","");
        Switch header=settingSwitch(behaviour,"滑动时收起会话顶部","hideHeader",true);header.setOnCheckedChangeListener((v,on)->getSharedPreferences("ui",0).edit().putBoolean("hideHeader",on).apply());
        Switch notifications=settingSwitch(behaviour,"任务完成通知","notifications",false);notifications.setOnCheckedChangeListener((v,on)->{getSharedPreferences("ui",0).edit().putBoolean("notifications",on).apply();if(on){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},22);else startNotifications();}else MonitorConnection.start(this);});Switch overlay=settingSwitch(behaviour,"显示实时任务悬浮窗","overlay",false);overlay.setOnCheckedChangeListener((v,on)->{getSharedPreferences("ui",0).edit().putBoolean("overlay",on).apply();
            // Order matters. The permission prompt is a separate activity, so priming
            // before it would try to add a window that is not allowed yet and record a
            // permission failure as the reason.
            if(on&&!android.provider.Settings.canDrawOverlays(this)){startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));}
            else if(on)StatusOverlay.prime(this);
            MonitorConnection.start(this);});        // The overlay's desktop pet. The roster lives in PetSprite, so a new form is
        // added in one place; "简洁" keeps the plain window with no character.
        final String[] petForms=PetSprite.picker();
        // migrate, not a raw read: the roster has changed, so a device can still hold
        // the name of a pet that no longer exists and the group header would then label
        // the wrong animal.
        final String chosenPet=PetSprite.migrate(getSharedPreferences("ui",0).getString("pet",null));
        LinearLayout petGroup=settingsGroup(content,"spark","悬浮窗宠物",PetSprite.label(chosenPet));petGroup.setPadding(0,0,0,dp(8));
        // Three columns: seven choices leave no orphan row, and the avatars get the
        // width they need to be told apart at a glance.
        final GridLayout pets=new GridLayout(this);pets.setColumnCount(3);pets.setContentDescription("悬浮窗宠物形象");
        for(int i=0;i<petForms.length;i++){final String form=petForms[i];boolean picked=form.equals(chosenPet);
            LinearLayout card=column();card.setPadding(dp(4),dp(8),dp(4),dp(4));card.setGravity(Gravity.CENTER_HORIZONTAL);card.setBackground(ui.touch(picked?NativeUi.TINT:NativeUi.SOFT,NativeUi.CORNER_ROW,true));card.setContentDescription(PetSprite.label(form)+"宠物");
            ImageView avatar=new ImageView(this);avatar.setScaleType(ImageView.ScaleType.CENTER_INSIDE);avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if(form.equals("none")){avatar.setImageDrawable(new NativeUi.Symbol("chat",picked?GREEN:MUTED,dp(24)));}
            else{PetSprite sprite=new PetSprite(form,GREEN,NativeUi.SOFT,dp(52));sprite.advance(.15f,0f,0f);avatar.setImageDrawable(sprite);}
            card.addView(avatar,new LinearLayout.LayoutParams(dp(56),dp(56)));
            TextView name=text(PetSprite.label(form),NativeUi.CAPTION,picked?GREEN:MUTED);name.setGravity(Gravity.CENTER);card.addView(name,-2);
            // Tag the label instead of reaching for getChildAt(1): the selection
            // highlight used to depend on the avatar always being the first child.
            name.setTag("petLabel");
            card.setOnClickListener(v->{getSharedPreferences("ui",0).edit().putString("pet",form).apply();
                for(int n=0;n<pets.getChildCount();n++){LinearLayout other=(LinearLayout)pets.getChildAt(n);boolean now=petForms[n].equals(form);other.setBackground(ui.touch(now?NativeUi.TINT:NativeUi.SOFT,NativeUi.CORNER_ROW,true));View text=other.findViewWithTag("petLabel");if(text instanceof TextView)((TextView)text).setTextColor(now?GREEN:MUTED);}
                toast("悬浮窗宠物已改为 "+PetSprite.label(form)+"，离开 App 后在悬浮窗显示");});
            GridLayout.LayoutParams lp=new GridLayout.LayoutParams(GridLayout.spec(i/3),GridLayout.spec(i%3,1f));lp.width=0;lp.height=dp(96);lp.setMargins(dp(4),dp(4),dp(4),dp(4));pets.addView(card,lp);}
        add(petGroup,pets,-2);
        // A self-check rather than a support question. Four separate conditions can keep
        // the overlay off screen, and before this the only symptom was that nothing
        // appeared - no hint about which condition failed.
        Button checkOverlay=button("检查悬浮窗状态",false);add(petGroup,checkOverlay,48);
        checkOverlay.setOnClickListener(v->{String reason=StatusOverlay.lastBlockReason;boolean on=getSharedPreferences("ui",0).getBoolean("overlay",false);boolean granted=android.provider.Settings.canDrawOverlays(this);
            StringBuilder report=new StringBuilder();
            report.append(on?"· 悬浮窗开关：已开启":"· 悬浮窗开关：未开启，先打开上面这一项");
            report.append("\n").append(granted?"· 悬浮权限：已授予":"· 悬浮权限：未授予，需要在系统设置里允许「显示在其他应用上层」");
            report.append("\n").append(StatusOverlay.hosted?"· 窗口状态：已在屏幕上":"· 窗口状态：未显示");
            if(!StatusOverlay.hosted&&!reason.isEmpty())report.append("\n· 原因：").append(reason);
            if(!on||!granted)report.append("\n\n打开开关并授予权限后，离开 App 即可看到宠物。");
            else if(reason.contains("前台"))report.append("\n\n此时 App 在前台，悬浮窗按设计不显示；切到桌面或其他应用即可看到。");
            else if(!StatusOverlay.hosted)report.append("\n\n开关与权限都正常但没有窗口。点下面的按钮立即让它出现；仍未出现时再确认电脑助手在线并已完成配对。");
            alert().setTitle("悬浮窗状态").setMessage(report.toString())
                .setPositiveButton("立即显示悬浮窗",(d,w)->StatusOverlay.prime(this))
                .setNegativeButton("知道了",null).show();});
        settingsSection(content,"连接与设备");
        LinearLayout linkCard=column();inset(linkCard,14,12);linkCard.setBackground(ui.surface(NativeUi.SOFT,NativeUi.CORNER_ROW,true));add(content,linkCard,-2);connectionStatus(linkCard);
        LinearLayout networkGroup=settingsGroup(content,"code","设备网络","");Switch embedded=settingSwitch(networkGroup,"使用内置设备网络","embeddedNetwork",false);embedded.setOnCheckedChangeListener((v,on)->{getSharedPreferences("ui",0).edit().putBoolean("embeddedNetwork",on).apply();if(!on)workers.execute(()->EmbeddedNetwork.stop());});Button loginNetwork=button("登录 / 查看内置网络",false);add(networkGroup,loginNetwork,48);loginNetwork.setOnClickListener(v->{embedded.setChecked(true);NetworkDialog.show(this,ui);});
        LinearLayout devices=settingsGroup(content,"scan","已保存的连接","");connectionChoices(devices);
        Button scan=button("扫码连接或绑定",false);add(devices,scan,48);scan.setOnClickListener(v->scan());
        Button disconnect=button("移除当前连接",false);disconnect.setTextColor(NativeUi.RED);add(devices,disconnect,48);disconnect.setOnClickListener(v->alert().setTitle("移除当前连接？").setMessage("仅移除此连接凭据，其他临时连接或永久绑定会保留。").setPositiveButton("解除绑定",(d,w)->{final String host=origin,credential=token;workers.execute(()->{try{request(host,credential,"/api/unpair",new JSONObject());}catch(Exception ignored){}});stopService(new Intent(this,TaskNotificationService.class));try{JSONObject next=ConnectionProfiles.remove(this,host,credential);origin=next.optString("origin");token=next.optString("token");clearCaches();if(token.isEmpty())home();else showTab(firstTab());}catch(Exception e){showError(e);}}).setNegativeButton("取消",null).show());
        Button assistant=button("桌面助手",false);assistant.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);assistant.setTextSize(NativeUi.TITLE);assistant.setBackground(ui.touch(Color.TRANSPARENT,NativeUi.CORNER_ROW,false));add(content,assistant,NativeUi.ROW);
        // Flattened to the right instead of a collapsing list: every client and its
        // three actions are visible at once.
        LinearLayout applications=column();add(content,applications,-2);flatApplications(applications);
        settingsSection(content,"电源");
        Button shutdown=button("关闭电脑",false);shutdown.setTextColor(NativeUi.RED);add(content,shutdown,NativeUi.ROW);
        shutdown.setOnClickListener(v->alert().setTitle("关闭电脑？").setMessage("电脑将在 60 秒后关机，正在执行的任务会中断。可在电脑执行 shutdown /a 取消，也可以在这里取消。").setPositiveButton("关机",(d,w)->controlApplication("assistant","shutdown",true,applications)).setNegativeButton("取消",null).show());
        Button cancelShutdown=button("取消关机",false);add(content,cancelShutdown,NativeUi.ROW);
        cancelShutdown.setOnClickListener(v->controlApplication("assistant","cancel",true,applications));
        settingsSection(content,"存储与系统");Button cache=button("清理会话缓存",false);add(content,cache,NativeUi.ROW);cache.setOnClickListener(v->{clearCaches();toast("会话缓存已清理");});
        Button notificationSettings=button("系统通知设置",false);add(content,notificationSettings,NativeUi.ROW);notificationSettings.setOnClickListener(v->startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName())));
        gap(content,32);TextView version=text("coding远程 · "+appVersion(),NativeUi.CAPTION,MUTED);version.setGravity(Gravity.CENTER);add(content,version,40);scroll.addView(content);add(screen,scroll,-1);
    }
    private void connectionStatus(LinearLayout card){
        card.removeAllViews();
        String mode="";try{mode=ConnectionProfiles.read(this).optString("mode","");}catch(Exception ignored){}
        add(card,text(origin.isEmpty()?"尚未连接电脑":"连接方式 · "+(mode.equals("permanent")?"Tailscale 永久绑定":"扫码临时连接"),NativeUi.CAPTION,MUTED),22);
        TextView link=text(origin.isEmpty()?"—":origin,NativeUi.LABEL,INK);link.setTextIsSelectable(true);single(link);add(card,link,26);
        final TextView live=text("正在检测电脑助手…",NativeUi.CAPTION,MUTED);live.setMaxLines(2);add(card,live,30);
        Button again=button("重新检测连接",false);compact(again);add(card,again,40);again.setOnClickListener(v->connectionStatus(card));
        if(origin.isEmpty()||token.isEmpty()){live.setText("配对后这里会显示电脑助手的连接状态");return;}
        final String host=origin,credential=token;
        workers.execute(()->{try{JSONObject value=request(host,credential,"/api/device",null);runOnUiThread(()->{if(isFinishing())return;live.setTextColor(GREEN);live.setText("电脑助手在线 · "+value.optString("name","coding远程")+" "+value.optString("version","")+(value.optBoolean("claudeConnected",false)?" · Claude 已连接":""));});}
        catch(Exception error){runOnUiThread(()->{if(isFinishing())return;live.setTextColor(NativeUi.RED);live.setText("电脑助手离线 · "+errorMessage(error));});}});
    }
    /** Flat, right-aligned client controls; no collapsing list. */
    private void flatApplications(LinearLayout target){
        target.removeAllViews();add(target,text("正在读取客户端状态…",NativeUi.CAPTION,MUTED),30);
        fetch("/api/apps",null,epoch,value->{
            target.removeAllViews();JSONArray rows=value.optJSONArray("apps");if(rows==null)return;
            for(int i=0;i<rows.length();i++){JSONObject item=rows.getJSONObject(i);final String key=item.getString("id");
                String name=key.equals("assistant")?"桌面助手":providerName(key);boolean running=item.optBoolean("running");
                LinearLayout line=row();line.setPadding(dp(14),0,dp(4),0);line.setBackground(ui.surface(NativeUi.SOFT,NativeUi.CORNER_ROW,false));add(target,line,NativeUi.ROW_TALL);gap(target,6);
                TextView label=text(name,NativeUi.BODY,INK);medium(label);line.addView(label,new LinearLayout.LayoutParams(0,-2,1));
                TextView state=text(running?(item.optBoolean("connected")?"已连接":"已打开"):"已关闭",NativeUi.CAPTION,running?GREEN:MUTED);state.setPadding(0,0,dp(6),0);line.addView(state);
                for(String action:new String[]{"start","stop","restart"}){final String step=action;Button control=ui.iconButton(action.equals("start")?"arrow":action.equals("stop")?"stop":"refresh",name+(action.equals("start")?"启动":action.equals("stop")?"关闭":"重启"));
                    control.setEnabled(action.equals("start")||running);control.setAlpha(action.equals("start")||running?1f:.4f);line.addView(control,new LinearLayout.LayoutParams(dp(48),dp(48)));
                    control.setOnClickListener(v->{if(step.equals("start"))controlApplication(key,step,false,target);else alert().setTitle((step.equals("stop")?"关闭 ":"重启 ")+name+"？").setMessage(key.equals("assistant")?"控制助手窗口，后台连接保持运行。":"这会中断该客户端当前任务。").setPositiveButton("继续",(d,w)->controlApplication(key,step,true,target)).setNegativeButton("取消",null).show();});}
            }
        });
    }
    private void connectionChoices(LinearLayout parent){
        try{JSONObject root=ConnectionProfiles.read(this);JSONArray profiles=ConnectionProfiles.rows(root);for(int i=0;i<profiles.length();i++){final JSONObject route=profiles.getJSONObject(i);String label=route.optString("mode").equals("permanent")?"Tailscale 永久绑定":"扫码临时连接";Button choose=button(label+(route.optString("token").equals(root.optString("token"))?" · 当前":""),false);add(parent,choose,44);choose.setOnClickListener(v->{try{ConnectionProfiles.select(this,route);origin=route.getString("origin");token=route.getString("token");clearCaches();showTab("settings");}catch(Exception e){showError(e);}});}}catch(Exception e){toast("连接配置读取失败");}
    }
    private void toggleApplicationMenu(View anchor,String selected){
        if(applicationMenu!=null){applicationMenu.dismiss();applicationMenu=null;return;}
        LinearLayout content=column();content.setPadding(dp(4),dp(4),dp(4),dp(4));content.setBackground(ui.surface(NativeUi.WHITE,NativeUi.CORNER_ROW,true));TextView loading=text("读取中…",NativeUi.CAPTION,MUTED);loading.setPadding(dp(12),dp(8),dp(12),dp(8));content.addView(loading);
        applicationMenu=new PopupWindow(content,-2,-2,true);applicationMenu.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));applicationMenu.setOutsideTouchable(true);applicationMenu.setElevation(dp(NativeUi.LIFT_POPUP));applicationMenu.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);applicationMenu.setEnterTransition(new android.transition.Fade().setDuration(140));applicationMenu.setExitTransition(new android.transition.Fade().setDuration(100));applicationMenu.showAsDropDown(anchor,0,0);loadApplications(content,selected);
    }
    private void loadApplications(LinearLayout target){loadApplications(target,backend);}
    private void loadApplications(LinearLayout target,String selected){
        fetch("/api/apps",null,epoch,value->{target.removeAllViews();JSONArray rows=value.optJSONArray("apps");if(rows==null)return;
            for(int i=0;i<rows.length();i++){JSONObject item=rows.getJSONObject(i);final String key=item.getString("id");if(!key.equals(selected))continue;
                String name=key.equals("assistant")?"桌面助手":providerName(key);TextView state=text(item.optBoolean("running")?"已打开":"已关闭",NativeUi.CAPTION,MUTED);state.setPadding(dp(12),dp(6),dp(12),dp(6));target.addView(state,new LinearLayout.LayoutParams(-2,-2));
                for(String action:new String[]{"start","stop","restart"}){Button control=button(action.equals("start")?"启动":action.equals("stop")?"关闭":"重启",false);control.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);control.setContentDescription(name+" · "+control.getText());control.setMinHeight(0);control.setMinimumHeight(0);control.setPadding(dp(14),dp(8),dp(14),dp(8));control.setBackground(ui.touch(Color.TRANSPARENT,NativeUi.CORNER_CHIP,false));target.addView(control,new LinearLayout.LayoutParams(-1,-2));
                    control.setOnClickListener(v->{if(action.equals("start"))controlApplication(key,action,false,target);else alert().setTitle((action.equals("stop")?"关闭 ":"重启 ")+name+"？").setMessage(key.equals("assistant")?"控制助手窗口，后台连接保持运行。":"这会中断该客户端当前任务。").setPositiveButton("继续",(d,w)->controlApplication(key,action,true,target)).setNegativeButton("取消",null).show();});}
            }
            if(applicationMenu!=null)applicationMenu.update(-2,-2);
        });
    }
    private void togglePanel(LinearLayout panel,boolean open){
        panel.animate().cancel();panel.animate().withEndAction(null);if(open){panel.setVisibility(View.VISIBLE);panel.setAlpha(0);panel.setTranslationY(-dp(6));panel.animate().alpha(1).translationY(0).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}
        else panel.animate().alpha(0).translationY(-dp(6)).setDuration(140).withEndAction(()->{panel.setVisibility(View.GONE);panel.setAlpha(1);panel.setTranslationY(0);}).start();
    }
    private void controlApplication(String app,String action,boolean confirmed,LinearLayout target){
        final String key=origin+":"+app+":"+action;
        try{
            JSONObject stored=readApplicationRequest(key);
            if(stored!=null&&"unknown".equals(stored.optString("outcome"))){
                // Reusing that stored receipt would answer every later tap with the
                // same unresolved result, so the button looked permanently dead.
                alert().setTitle("上次操作结果未确认").setMessage("电脑可能没有执行，也可能已经执行。重新发送会使用新的提交标识，可能重复一次。")
                    .setPositiveButton("重新发送",(d,w)->{getSharedPreferences("app-requests",0).edit().remove(key).apply();controlApplication(app,action,confirmed,target);})
                    .setNegativeButton("取消",null).show();
                return;
            }
            sendApplicationRequest(key,app,action,confirmed,target,stored);
        }catch(Exception e){showError(e);}
    }
    private JSONObject readApplicationRequest(String key)throws Exception{String stored=getSharedPreferences("app-requests",0).getString(key,"");return stored.isEmpty()?null:new JSONObject(stored);}
    private void sendApplicationRequest(String key,String app,String action,boolean confirmed,LinearLayout target,JSONObject previous)throws Exception{
        // Reuse a pending identifier: an identical request is answered from the
        // computer's receipt instead of executing a second time.
        JSONObject body=previous==null?new JSONObject():new JSONObject(previous.toString());body.remove("outcome");
        if(!body.has("id"))body.put("id",UUID.randomUUID().toString());
        body.put("confirmed",confirmed);
        final JSONObject saved=body;
        getSharedPreferences("app-requests",0).edit().putString(key,saved.toString()).commit();
        fetch("/api/apps/"+app+"/"+action,saved,epoch,result->{String outcome=result.optString("status");
            if(outcome.equals("unknown")){try{saved.put("outcome","unknown");getSharedPreferences("app-requests",0).edit().putString(key,saved.toString()).commit();}catch(Exception ignored){}}
            else getSharedPreferences("app-requests",0).edit().remove(key).apply();
            toast(result.optString("message",outcome.equals("accepted")?"操作已提交":"请刷新应用状态核对结果"));
            if(outcome.equals("needs_restart"))alert().setTitle("重启 Claude 以启用自动连接？").setMessage("开发者设置已启用，需要重启一次才能生效。重启会中断当前任务。").setPositiveButton("重启并连接",(d,w)->controlApplication(app,"restart",true,target)).setNegativeButton("取消",null).show();
            if(applicationMenu!=null){applicationMenu.dismiss();applicationMenu=null;}
            ListCache changedCache=listCaches.get(app);if(changedCache!=null){changedCache.connected=false;changedCache.loaded=1;if(app.equals(displayedListKey))loadSessions(app,true);}
            if(connectionLabel!=null){final TextView badge=connectionLabel;appStatusUntil=System.currentTimeMillis()+5000;badge.setText(result.optString("message","操作已提交"));handler.postDelayed(()->{if(isDestroyed()||badge!=connectionLabel)return;appStatusUntil=0;connection(connectionText,connectionGood);},5000);}});
    }
    private Switch settingSwitch(LinearLayout parent,String label,String key,boolean defaultValue){Switch v=new Switch(this);v.setText(label);v.setTextSize(NativeUi.TITLE);v.setTextColor(INK);v.setThumbTintList(android.content.res.ColorStateList.valueOf(GREEN));v.setChecked(getSharedPreferences("ui",0).getBoolean(key,defaultValue));v.setBackground(ui.touch(NativeUi.SOFT,NativeUi.CORNER_ROW,false));v.setPadding(dp(14),0,dp(14),0);add(parent,v,NativeUi.ROW_TALL);return v;}

    private String providerName(String key){return key.equals("claude")?"Claude":key.equals("deepseek")?"DeepSeek":"Codex";}
    private String firstTab(){for(String k:new String[]{"claude","codex","deepseek"})if(getSharedPreferences("ui",0).getBoolean("show_"+k,true))return k;return "claude";}
    private AlertDialog.Builder alert(){return new NativeDialogBuilder(this,ui);}
    private void openSettings(){settingsReturn=backend.equals("settings")?firstTab():backend;showTab("settings");}
    private TextView option(String label,Runnable action){
        // A compact chip that sizes to its own text, up to a ceiling. The ceiling stops
        // a long runtime-supplied name from widening the chip and pushing the others off
        // the screen; a longer name is ellipsised here and shown in full in the picker
        // the chip opens, which is also what the content description announces.
        //
        // No chevron. Four chips plus the attach button have to fit the narrowest phone,
        // and a trailing glyph costs 24dp each - 96dp across the row, which is more than
        // the whole margin. The chip's own fill already reads as a control, so the
        // chevron was repeating what the surface says.
        TextView v=text(label,NativeUi.CAPTION,INK);
        v.setPadding(dp(9),0,dp(9),0);v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setMaxWidth(dp(optionMaxWidthDp));
        v.setBackground(ui.touch(NativeUi.SOFT,NativeUi.CORNER_CHIP,true));
        v.setLayoutParams(new LinearLayout.LayoutParams(-2,dp(38)));
        v.setContentDescription(label+"选择");v.setFocusable(true);v.setOnClickListener(w->action.run());
        return v;
    }
    /**
     * The widest a chip may grow, in dp.
     *
     * Measured, not guessed. A 320dp phone is the narrowest the app supports; the
     * composer insets take 24dp and the attach button takes 40dp plus an 8dp margin,
     * leaving 248dp for four chips and three 6dp gaps. Three of the chips are two
     * characters and land near 41dp each; this cap is what the fourth may use when it
     * carries a name, and it is set so that all four still close.
     */
    private static final int optionMaxWidthDp=82;
    private void restoreConversation(String key,int generation){final String address=origin,credential=token;workers.execute(()->{JSONObject raw=ConversationCache.read(this,address+credential+key);if(raw==null)return;try{SessionPage page=SessionPage.prepare(raw);runOnUiThread(()->{ChatCache c=chatCaches.get(key);if(c!=null&&c.page==null&&generation==epoch){c.page=page;renderSession(page,false);connection("已缓存 · 正在同步",false);}});}catch(Exception ignored){}});}
    private void toggleCatalog(String kind){if(sending||pending!=null){toast("请先等待当前操作确认");return;}if(kind.equals(expandedOption)){closeOptions();return;}closeOptions();expandedOption=kind;chooseCatalog(kind);}
    private void closeOptions(){catalogTicket++;selectedModel="";expandedOption="";if(optionsDismiss!=null)optionsDismiss.setVisibility(View.GONE);if(inlineOptions!=null){final LinearLayout panel=inlineOptions;final int closing=catalogTicket;panel.animate().cancel();panel.animate().alpha(0).translationY(dp(6)).setDuration(120).withEndAction(()->{if(closing==catalogTicket&&expandedOption.isEmpty()){panel.removeAllViews();panel.setVisibility(View.GONE);panel.setAlpha(1);panel.setTranslationY(0);}}).start();}}
    private void positionOptions(){if(inlineOptions==null||optionAnchor==null)return;final LinearLayout panel=inlineOptions;panel.post(()->{if(panel!=inlineOptions||optionAnchor==null)return;int[] a=new int[2],b=new int[2];optionAnchor.getLocationOnScreen(a);stage.getLocationOnScreen(b);FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);lp.leftMargin=dp(16);lp.rightMargin=dp(16);lp.bottomMargin=Math.max(dp(8),stage.getHeight()-(a[1]-b[1])+dp(4));panel.setLayoutParams(lp);if(optionsDismiss!=null)optionsDismiss.setVisibility(panel.getVisibility());panel.bringToFront();});}
    private void loadingOptions(String label){if(inlineOptions==null)return;inlineOptions.removeAllViews();ui.reveal(inlineOptions,true);add(inlineOptions,text(label,NativeUi.LABEL,MUTED),48);positionOptions();}
    private void chooseCatalog(String kind){
        if(sessionId==null)return;final String key=sessionKey;final int ticket=++catalogTicket;ChatCache c=chatCaches.get(key);
        String section=kind.equals("plugins")?"plugins":"models";if(c!=null&&c.catalog!=null&&c.catalog.has(section.equals("plugins")?"skills":"models")){showCatalog(kind,c.catalog);return;}
        loadingOptions("正在读取电脑提供的选项…");final int generation=epoch;final String host=origin,credential=token,route="/api/native/"+backend+"/"+sessionId+"/catalog?section="+section;workers.execute(()->{try{JSONObject value=request(host,credential,route,null);runOnUiThread(()->{if(generation!=epoch||ticket!=catalogTicket||!key.equals(sessionKey)||!kind.equals(expandedOption))return;if(section.equals("models"))value.remove("skills");else value.remove("models");ChatCache cache=chatCaches.get(key);if(cache!=null)cache.catalog=value;showCatalog(kind,value);});}catch(Exception error){runOnUiThread(()->{if(generation!=epoch||ticket!=catalogTicket||!key.equals(sessionKey))return;loadingOptions(errorMessage(error));Button retry=button("重试读取选项",false);add(inlineOptions,retry,48);retry.setOnClickListener(v->chooseCatalog(kind));});}});
    }
    private void savePlugins(ChatCache cache){getSharedPreferences("composer-plugins",0).edit().putStringSet(origin+"|"+sessionKey,new HashSet<>(cache.plugins)).apply();showPluginCount(cache);}
    /**
     * The chip's own label, kept in one place.
     *
     * This used to live only inside savePlugins, so it ran when the user ticked a plugin
     * and never again. Reopening a session restored the selection into the cache - the
     * prefered store was correct - but the chip was rebuilt from scratch and stayed on
     * its initial "插件", which reads as "your selection was lost". Splitting it out lets
     * the render path refresh the label from the cache it just loaded.
     */
    private void showPluginCount(ChatCache cache){
        if(pluginLabel==null||cache==null)return;
        // A count on the chip, not a sentence. "插件 · 12" is too wide for a row that has
        // to hold four chips and the attach button on a narrow phone; the number alone
        // keeps the count visible without the words.
        pluginLabel.setText(cache.plugins.isEmpty()?"插件":"插件 "+cache.plugins.size());
        pluginLabel.setContentDescription(cache.plugins.isEmpty()?"插件：未选择，点击选择":"插件：已选 "+cache.plugins.size()+" 个，点击修改");
    }
    private void showCatalog(String kind,JSONObject catalog){
        ChatCache cache=chatCaches.get(sessionKey);if(cache==null)return;JSONArray entries=catalog.optJSONArray(kind.equals("plugins")?"skills":"models");if(entries==null)entries=new JSONArray();
        LinearLayout body=column(),items=column();ScrollView scroll=new NativeUi.MenuScroll(this,kind.equals("plugins")?272:168);scroll.addView(items);add(body,scroll,-2);
        if(kind.equals("plugins")){
            JSONArray modes=catalog.optJSONArray("modes");if(modes!=null&&modes.length()>0){add(items,text("会话模式",NativeUi.CAPTION,MUTED),28);LinearLayout modeRow=row();add(items,modeRow,48);for(int n=0;n<modes.length();n++){JSONObject m=modes.optJSONObject(n);if(m==null)continue;String mode=m.optString("id");JSONObject goal=catalog.optJSONObject("goal");boolean active=mode.equals("goal")?goal!=null&&goal.optString("status").equals("active"):mode.equals(catalog.optString("currentMode"));Button choose=button((active?"✓ ":"")+m.optString("name"),false);choose.setTextSize(NativeUi.CAPTION);modeRow.addView(choose,new LinearLayout.LayoutParams(0,dp(48),1));choose.setOnClickListener(v->{if(mode.equals("goal")){closeOptions();goalOptions(catalog);return;}try{closeOptions();submit("settings",new JSONObject().put("collaborationMode",mode));}catch(Exception e){showError(e);}});}}
            for(int n=0;n<entries.length();n++){JSONObject item=entries.optJSONObject(n);if(item==null)continue;JSONArray members=item.optJSONArray("memberIds");boolean wasSelected=false;for(int k=0;members!=null&&k<members.length();k++)wasSelected|=cache.plugins.remove(members.optString(k));if(wasSelected&&item.optBoolean("selectable",true))cache.plugins.add(item.optString("id"));if(!item.optBoolean("selectable",true))cache.plugins.remove(item.optString("id"));}savePlugins(cache);
            String error=catalog.optString("pluginError",catalog.optString("goalError"));if(!error.isEmpty())add(items,text(error,NativeUi.CAPTION,NativeUi.RED),-2);
            add(items,text("插件与技能",NativeUi.CAPTION,MUTED),28);
        }
        ArrayList<String> ids=new ArrayList<>();ArrayList<String> names=new ArrayList<>();
        if(kind.equals("effort")){String selected=!selectedModel.isEmpty()?selectedModel:catalog.optString("currentModel",cache.page==null?"":cache.page.model);JSONObject model=null;for(int i=0;i<entries.length();i++){JSONObject m=entries.optJSONObject(i);if(m!=null&&m.optString("id").equals(selected))model=m;}if(model!=null){JSONArray efforts=model.optJSONArray("efforts");if(efforts!=null)for(int i=0;i<efforts.length();i++){Object e=efforts.opt(i);String id=e instanceof JSONObject?((JSONObject)e).optString("id",((JSONObject)e).optString("reasoningEffort")):String.valueOf(e);ids.add(id);names.add(id);}}}
        else for(int i=0;i<entries.length();i++){JSONObject entry=entries.optJSONObject(i);if(entry==null)continue;ids.add(entry.optString("id"));names.add(entry.optString("displayName",entry.optString("name",entry.optString("id"))));}
        if(ids.isEmpty())add(items,text(kind.equals("plugins")?"当前项目没有可选插件或技能":"电脑尚未提供可选项，请在桌面检查模型配置",NativeUi.LABEL,MUTED),-2);
        if(inlineOptions==null||!kind.equals(expandedOption))return;inlineOptions.removeAllViews();ui.reveal(inlineOptions,true);inlineOptions.addView(body);scroll.setLayoutParams(new LinearLayout.LayoutParams(-1,-2));
        for(int i=0;i<ids.size();i++){final String id=ids.get(i);if(kind.equals("plugins")){JSONObject item=entries.optJSONObject(i);if(item!=null&&!item.optBoolean("selectable",true)){TextView installed=text(names.get(i)+" · "+item.optString("status","已安装"),13,MUTED);installed.setPadding(dp(8),dp(8),dp(8),dp(8));installed.setTooltipText(item.optString("description"));add(items,installed,-2);continue;}CheckBox choice=new CheckBox(this);choice.setText(names.get(i));choice.setTextColor(INK);choice.setTooltipText(item==null?"":item.optString("description"));choice.setButtonTintList(android.content.res.ColorStateList.valueOf(GREEN));choice.setBackground(ui.touch(cache.plugins.contains(id)?NativeUi.TINT:Color.TRANSPARENT,NativeUi.CORNER_ROW,false));choice.setPadding(dp(8),0,dp(8),0);choice.setChecked(cache.plugins.contains(id));add(items,choice,NativeUi.ROW);choice.setOnCheckedChangeListener((v,on)->{if(sending||pending!=null){choice.setChecked(cache.plugins.contains(id));return;}if(on)cache.plugins.add(id);else cache.plugins.remove(id);choice.setBackground(ui.touch(on?NativeUi.TINT:Color.TRANSPARENT,NativeUi.CORNER_ROW,false));savePlugins(cache);});}
            else{Button choose=ui.menu(names.get(i),id.equals(kind.equals("model")?catalog.optString("currentModel",cache.page==null?"":cache.page.model):cache.page==null?"":cache.page.effort));add(items,choose,NativeUi.ROW);gap(items,4);choose.setOnClickListener(v->{try{if(kind.equals("model")){selectedModel=id;JSONObject chosen=null;for(int j=0;j<catalog.optJSONArray("models").length();j++)if(catalog.optJSONArray("models").getJSONObject(j).optString("id").equals(id))chosen=catalog.optJSONArray("models").getJSONObject(j);if(chosen!=null&&chosen.optJSONArray("efforts")!=null&&chosen.optJSONArray("efforts").length()>0){expandedOption="effort";showCatalog("effort",catalog);return;}}JSONObject bodyValue=new JSONObject().put(kind.equals("model")?"model":"effort",id);if(kind.equals("effort"))bodyValue.put("model",!selectedModel.isEmpty()?selectedModel:catalog.optString("currentModel",cache.page==null?"":cache.page.model));closeOptions();cache.catalog=null;submit("settings",bodyValue);}catch(Exception e){showError(e);}});}}
        Button refresh=button("刷新可用选项",false);compact(refresh);add(body,refresh,48);refresh.setOnClickListener(v->{cache.catalog=null;chooseCatalog(kind);});positionOptions();
    }
    private void goalOptions(JSONObject catalog){
        JSONObject goal=catalog.optJSONObject("goal");if(goal!=null&&!goal.optString("status").equals("complete")){
            boolean active=goal.optString("status").equals("active");String[] options={active?"暂停目标":"继续目标","清除目标"};alert().setTitle("目标模式 · "+goal.optString("status")).setView(text(goal.optString("objective")+"\n已用 "+goal.optLong("tokensUsed")+" tokens",NativeUi.BODY,INK)).setItems(options,(d,which)->{try{String operation=which==1?"clear":active?"pause":"resume";if(which==1)alert().setTitle("清除当前目标？").setMessage(goal.optString("objective")).setNegativeButton("取消",null).setPositiveButton("清除",(a,b)->{try{submit("goal",new JSONObject().put("operation","clear"));}catch(Exception e){showError(e);}}).show();else submit("goal",new JSONObject().put("operation",operation));}catch(Exception e){showError(e);}}).setNegativeButton("关闭",null).show();return;
        }
        LinearLayout body=column();EditText objective=new EditText(this);objective.setHint("描述需要持续完成的目标");objective.setMinLines(3);objective.setTextColor(INK);add(body,objective,-2);EditText budget=new EditText(this);budget.setHint("Token 预算（可选）");budget.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);add(body,budget,NativeUi.ROW);alert().setTitle("设置目标模式").setView(body).setNegativeButton("取消",null).setPositiveButton("设置目标",(d,w)->{try{String value=objective.getText().toString().trim();if(value.isEmpty()){toast("请填写目标");return;}JSONObject request=new JSONObject().put("operation","set").put("objective",value);String tokens=budget.getText().toString().trim();if(!tokens.isEmpty())request.put("tokenBudget",Long.parseLong(tokens));submit("goal",request);}catch(Exception e){showError(e);}}).show();
    }
    private void renderTasks(SessionPage page){
        if(taskPanel==null)return;
        // Re-entrancy guard. The strip's click handler posts a rebuild, and the polling
        // path can call renderTasks again before that post runs. Two passes then interleave
        // removeAllViews() with addView() on the same children, and the strip ends up
        // holding views whose parent was detached underneath it - which is the throw that
        // closed the app on expanding the steps.
        if(renderingTasks)return;
        String revision=String.valueOf(page.steps)+String.valueOf(page.changes)+page.running+taskExpanded;
        // taskExpanded is part of the revision now. Without it a toggle that only flips the
        // flag hit this early return and the panel never opened or closed.
        if(revision.equals(taskRevision))return;
        renderingTasks=true;
        try{
        taskRevision=revision;taskPanel.removeAllViews();
        int count=page.steps==null?0:page.steps.length(),added=page.changes==null?0:page.changes.optInt("added"),deleted=page.changes==null?0:page.changes.optInt("deleted"),files=page.changes==null?0:page.changes.optInt("files");
        if(count==0&&files==0){renderingTasks=false;return;}
        // The changed files are named on this page, so the expanded panel can list
        // them instead of only offering a button.
        ArrayList<String> changed=new ArrayList<>();
        if(files>0)for(SessionPage.Row row:page.rows)if(row.kind.equals("code")&&!row.title.isEmpty()&&!changed.contains(row.title)&&changed.size()<12)changed.add(row.title);
        LinearLayout detail=column();detail.setVisibility(taskExpanded?View.VISIBLE:View.GONE);
        // The bar is a three-slot row: chevron, then the statistics centred in the space
        // that is left, then a same-width spacer on the right so "centred" means centred
        // on the bar rather than centred between two unequal neighbours. The chevron sits
        // on the left, and the whole bar is one line of text plus padding - it used to be
        // pinned to 44dp, which is taller than the caption line needs and left the label
        // floating in the middle of a banded strip.
        android.text.SpannableStringBuilder summary=new android.text.SpannableStringBuilder((count>0?count+" 项步骤":"")+(count>0&&files>0?"   ":"")+(files>0?files+" 个文件":""));
        if(count==0&&files==0)summary.append("执行过程");
        if(files>0){summary.append("   ");int start=summary.length();summary.append("+"+added);summary.setSpan(new android.text.style.ForegroundColorSpan(GREEN),start,summary.length(),33);summary.append("  ");start=summary.length();summary.append("−"+deleted);summary.setSpan(new android.text.style.ForegroundColorSpan(NativeUi.RED),start,summary.length(),33);}
        // The chevron is its own view so it can hold the left edge while the text takes
        // the full remaining width and centres in it. A compound drawable cannot do that:
        // it is measured against the text, so the glyph and the label move together.
        LinearLayout bar=row();bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(ui.touch(NativeUi.SOFT,NativeUi.CORNER_CHIP,true));
        ImageView chevron=ui.icon(taskExpanded?"down":"right",GREEN,NativeUi.GLYPH_INLINE);
        LinearLayout.LayoutParams chevronLp=new LinearLayout.LayoutParams(dp(18),dp(18));chevronLp.leftMargin=dp(10);
        bar.addView(chevron,chevronLp);
        TextView stats=text("",NativeUi.CAPTION,GREEN);
        stats.setText(summary);stats.setGravity(Gravity.CENTER);stats.setSingleLine(true);
        stats.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams statsLp=new LinearLayout.LayoutParams(0,-2,1);
        statsLp.leftMargin=dp(6);
        bar.addView(stats,statsLp);
        // Measured from `stats` itself, after its text is set: this is the view whose
        // line height the strip has to fit.
        int barH=taskBarHeight(stats);
        // Balances the chevron and its margin so the statistics are optically centred.
        View balance=new View(this);
        bar.addView(balance,new LinearLayout.LayoutParams(dp(18)+dp(10),1));
        // Rebuilt on the next frame rather than inside the handler. renderTasks() starts
        // by calling taskPanel.removeAllViews(), and this bar is one of those children -
        // tearing down the view whose click listener is still on the stack, then touching
        // it again, is what made tapping the bar crash the app.
        bar.setOnClickListener(v->{taskExpanded=!taskExpanded;taskRevision="";taskPanel.post(()->{if(!isDestroyed())renderTasks(page);});});
        for(int i=0;i<count;i++){JSONObject step=page.steps.optJSONObject(i);if(step==null)continue;add(detail,text((step.optString("status").equals("completed")?"✓  ":step.optString("status").equals("in_progress")?"◌  ":"○  ")+step.optString("content",step.optString("step",step.optString("subject"))),13,INK),-2);gap(detail,8);}
        if(files>0){
            add(detail,text("文件改动",NativeUi.CAPTION,MUTED),28);
            for(String name:changed){TextView row=text(name,NativeUi.CAPTION,INK);single(row);row.setPadding(dp(2),0,0,0);ui.leading(row,"file",MUTED,NativeUi.GLYPH_INLINE);add(detail,row,32);}
            if(changed.isEmpty())add(detail,emptyState("file","本页没有可列出的文件名","可用下方按钮查看改动详情"),-2);
            gap(detail,6);Button view=button("查看代码改动",false);compact(view);add(detail,view,44);view.setOnClickListener(v->showChanges(page));
        }
        ScrollView sc=new NativeUi.InnerScroll(this);sc.addView(detail);sc.setVisibility(taskExpanded?View.VISIBLE:View.GONE);
        // Detail first, bar second. The bar is the anchor the thumb already knows, so
        // the panel opens above it and grows upward into the transcript instead of
        // pushing the composer down.
        //
        // The panel's height is capped by what is actually in it, not only by the step
        // and file counts: a group with steps but nothing else still reserved 64dp, and
        // a group with neither reserved 64dp for a panel that was empty whenever it did
        // open. The cap is now zero when there is nothing to show.
        int detailDp=count*32+changed.size()*32+(files>0?112:0);
        add(taskPanel,sc,detailDp<=0?0:Math.min(240,Math.max(48,detailDp)));
        // Wrapped rather than added straight, so a WRAP_CONTENT parent can never stretch
        // a fixed-height strip into the leftover space of the column.
        LinearLayout barHolder=column();
        // barH is already a pixel measurement - taskBarHeight() measured the label and
        // clamped it between 32dp and 48dp. Passing it through add() applied the density
        // a second time and made the strip three times taller than a line of text on a
        // 3x screen, which is the band the statistics were floating in the middle of.
        barHolder.setLayoutParams(new LinearLayout.LayoutParams(-1,barH));
        barHolder.addView(bar,new LinearLayout.LayoutParams(-1,barH));
        taskPanel.addView(barHolder,new LinearLayout.LayoutParams(-1,barH));
        // One text line tall, but a full 48dp of tap area. A bar sized to the text is
        // only about 32dp, which is under the minimum a finger can reliably hit; a
        // TouchDelegate extends the hit rect upward and downward without making the bar
        // look like a strip again.
        //
        // The delegate belongs on the parent and forwards to the bar. Handing the bar a
        // delegate whose target is the bar itself makes View.onTouchEvent call the
        // delegate, which dispatches straight back into the same View, forever: the
        // stack ran out with "StackOverflowError" the first time a finger touched the
        // strip, which is why tapping the statistics closed the app while performClick()
        // - which never goes through touch dispatch - looked fine.
        barHolder.post(()->{
            int w=barHolder.getWidth(),h=barHolder.getHeight();
            if(w<=0||h<=0)return;
            android.graphics.Rect hit=new android.graphics.Rect(0,-dp(8),w,h+dp(8));
            barHolder.setTouchDelegate(new android.view.TouchDelegate(hit,bar));
        });
        if(taskExpanded){ui.reveal(sc,true);sc.post(()->{if(sc.getParent()!=null)sc.fullScroll(View.FOCUS_UP);});}
        }finally{renderingTasks=false;}
    }
    /** One line of text plus symmetric padding: the natural height of a caption bar.
     *
     * Measured from an attached-style TextView that has been laid out with the real
     * width, not from a bare probe. A detached probe reports font metrics at the
     * default density before the view has resolved its own text size, and the result
     * was a bar whose height had nothing to do with the line it was drawing - which is
     * what made the statistics strip taller than a line of text. */
    private int toggleLineHeight(TextView v){
        android.graphics.Paint.FontMetrics fm=v.getPaint().getFontMetrics();
        float text=Math.abs(fm.descent)+Math.abs(fm.ascent);
        int height=(int)Math.ceil(text+dp(12));
        // A caption line is a caption line: never below the touch floor, and never
        // above what one line plus symmetric padding can possibly need.
        return Math.max(dp(32),Math.min(height,dp(48)));
    }
    /** Height for the strip, resolved from the view that actually draws it. */
    private int taskBarHeight(TextView stats){
        stats.measure(View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int measured=stats.getMeasuredHeight();
        if(measured<=0)measured=toggleLineHeight(stats);
        return Math.max(dp(32),Math.min(measured+dp(6),dp(48)));
    }
    private void showChanges(SessionPage page){LinearLayout changes=column();for(SessionPage.Row r:page.rows)if(r.kind.equals("code")){add(changes,text(r.title,NativeUi.CAPTION,INK),-2);gap(changes,8);new MessageContent(this,ui).code(changes,r.text,r.language);gap(changes,12);}if(changes.getChildCount()==0)add(changes,text("当前消息页没有改动详情，可在历史消息中展开文件变更。",NativeUi.LABEL,MUTED),-2);ScrollView scroll=new ScrollView(this);scroll.addView(changes);LinearLayout body=column();add(body,scroll,360);sheet("代码改动","当前消息页的变更",body);}
    private void renderPermissions(SessionPage page){StringBuilder stamp=new StringBuilder();for(SessionPage.Permission p:page.permissions)stamp.append(p.id).append(p.input);String revision=stamp.toString()+sending+(pending!=null);if(revision.equals(permissionRevision))return;permissionRevision=revision;permissions.removeAllViews();permissionCount=page.permissions.size();permissionScroll.setLayoutParams(new LinearLayout.LayoutParams(-1,permissionCount==0?0:dp(Math.min(156,permissionCount*108))));
        boolean active=false;for(SessionPage.Permission permission:page.permissions){if(String.valueOf(permission.id).equals(activeQuestion))active=true;LinearLayout card=column();inset(card,12,8);card.setBackground(ui.surface(NativeUi.TINT,NativeUi.CORNER_ROW,true));add(card,text(permission.needsInput?"有问题需要你回答":"需要确认 · "+permission.tool,NativeUi.CAPTION,INK),-2);gap(card,4);
            if(permission.needsInput){Button answer=button("回答问题",true);add(card,answer,44);answer.setOnClickListener(v->showQuestion(permission));if(activeQuestion.isEmpty()&&!paused){activeQuestion=String.valueOf(permission.id);handler.post(()->{if(sessionId!=null)showQuestion(permission);});active=true;}}
            else{LinearLayout actions=row();for(String decision:new String[]{"accept","decline"}){Button b=button(decision.equals("accept")?"本次允许":"拒绝",decision.equals("accept"));actions.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));b.setEnabled(!sending&&pending==null);b.setOnClickListener(v->{try{submit("respond",new JSONObject().put("requestId",permission.id).put("decision",decision));}catch(Exception e){showError(e);}});}add(card,actions,48);card.setOnLongClickListener(v->{TextView full=text(permission.preview,NativeUi.CAPTION,INK);full.setTextIsSelectable(true);LinearLayout body=column();add(body,full,-2);sheet("操作详情",permission.tool,body);return true;});}add(permissions,card,-2);gap(permissions,6);}
        if(!active&&questionDialog!=null){questionDialog.dismiss();questionDialog=null;activeQuestion="";}}
    private void showQuestion(SessionPage.Permission permission){if(gitPage!=null)return;if(questionDialog!=null&&questionDialog.isShowing())return;activeQuestion=String.valueOf(permission.id);JSONArray qs=permission.input.optJSONArray("questions");JSONObject schema=permission.input.optJSONObject("requestedSchema");
        if(qs==null&&schema!=null){qs=new JSONArray();JSONObject properties=schema.optJSONObject("properties");if(properties!=null){java.util.Iterator<String> keys=properties.keys();while(keys.hasNext()){String key=keys.next();JSONObject prop=properties.optJSONObject(key);try{JSONObject q=new JSONObject().put("id",key).put("question",prop.optString("title",key)).put("type",prop.optString("type","string"));JSONArray opts=prop.optJSONArray("enum");if(opts!=null){JSONArray options=new JSONArray();for(int i=0;i<opts.length();i++)options.put(new JSONObject().put("label",opts.getString(i)));q.put("options",options);}qs.put(q);}catch(Exception ignored){}}}}
        if(qs==null||qs.length()==0){toast("当前桌面没有提供可填写的选项，请在电脑查看");return;}
        LinearLayout form=column(),body=column();ScrollView scroll=new NativeUi.FormScroll(this);scroll.addView(form);add(body,scroll,-2);
        final JSONArray questions=qs;final ArrayList<EditText> free=new ArrayList<>();final ArrayList<ArrayList<CheckBox>> choices=new ArrayList<>();
        for(int i=0;i<qs.length();i++){JSONObject q=qs.optJSONObject(i);if(q==null)continue;
            // Each question is its own card: a long form of bare checkboxes was hard
            // to scan and the options did not look selectable.
            LinearLayout card=column();inset(card,14,14);card.setBackground(ui.surface(NativeUi.SOFT,NativeUi.CORNER_ROW,true));add(form,card,-2);gap(form,NativeUi.SPACE_STACK);
            if(qs.length()>1){TextView index=text("问题 "+(i+1)+" / "+qs.length(),NativeUi.CAPTION,MUTED);add(card,index,-2);gap(card,NativeUi.SPACE_TIGHT);}
            TextView prompt=text(q.optString("question",q.optString("header","请选择")),NativeUi.TITLE,INK);prompt.setMaxLines(4);prompt.setEllipsize(TextUtils.TruncateAt.END);add(card,prompt,-2);gap(card,NativeUi.SPACE_TIGHT);
            ArrayList<CheckBox> boxes=new ArrayList<>();JSONArray opts=q.optJSONArray("options");boolean multi=q.optBoolean("multiSelect");
            // Options are rows, not checkboxes. A raw CheckBox brings the platform's
            // own square indicator and its own padding, which sits badly inside a
            // rounded card and ignores the theme; a themed row with a check glyph
            // reads as part of the same interface and can show its selected state.
            if(opts!=null)for(int j=0;j<opts.length();j++){Object option=opts.opt(j);JSONObject o=option instanceof JSONObject?(JSONObject)option:new JSONObject();String label=option instanceof String?(String)option:o.optString("label",o.optString("value"));String detail=o.optString("description");
                CheckBox choice=new CheckBox(this);
                choice.setText(detail.isEmpty()?label:label+"\n"+detail);
                choice.setTag(label);
                choice.setTextColor(INK);
                choice.setTextSize(NativeUi.LABEL);
                choice.setGravity(Gravity.CENTER_VERTICAL);
                choice.setPadding(dp(14),dp(10),dp(14),dp(10));
                // null, not a transparent ColorDrawable: the platform still reserves
                // the indicator's width for a transparent drawable, which left a gap
                // in front of the glyph.
                choice.setButtonDrawable(null);
                choice.setCompoundDrawablePadding(dp(10));
                choice.setBackground(ui.touch(NativeUi.WHITE,NativeUi.CORNER_ROW,false));
                choice.setChecked(false);
                choice.setCompoundDrawablesWithIntrinsicBounds(new NativeUi.Symbol("right",MUTED,dp(NativeUi.GLYPH_INLINE)),null,null,null);
                add(card,choice,-2);gap(card,NativeUi.SPACE_TIGHT);boxes.add(choice);
                choice.setOnCheckedChangeListener((v,on)->{
                    // The glyph and the fill are the only feedback that a tap landed:
                    // the platform indicator is gone, and tint alone is not enough
                    // for anyone who cannot separate the two hues.
                    ((CheckBox)v).setCompoundDrawablesWithIntrinsicBounds(new NativeUi.Symbol(on?"check":"right",on?GREEN:MUTED,dp(NativeUi.GLYPH_INLINE)),null,null,null);
                    v.setBackground(ui.touch(on?NativeUi.TINT:NativeUi.WHITE,NativeUi.CORNER_ROW,false));
                    if(on&&!multi)for(CheckBox other:boxes)if(other!=v)other.setChecked(false);});}
            EditText input=new EditText(this);ui.input(input);input.setHint("也可以直接输入补充或自定义答案");input.setMinLines(1);input.setMaxLines(4);add(card,input,-2);choices.add(boxes);free.add(input);}
        gap(body,NativeUi.SPACE_STACK);Button send=button("提交回答",true);add(body,send,48);questionDialog=sheet("需要你的意见","选择选项，或直接输入你自己的答案",body);final Dialog dialog=questionDialog;
        send.setOnClickListener(v->{try{JSONObject answers=new JSONObject(),content=new JSONObject();for(int i=0;i<questions.length();i++){JSONObject q=questions.getJSONObject(i);String key=q.optString("id",q.optString("question",String.valueOf(i)));JSONArray answer=new JSONArray();for(CheckBox choice:choices.get(i))if(choice.isChecked())answer.put(String.valueOf(choice.getTag()));String typed=free.get(i).getText().toString().trim();if(!typed.isEmpty())answer.put(typed);if(answer.length()==0){toast("请回答每一项问题");return;}answers.put(key,new JSONObject().put("answers",answer));String value=answer.getString(0);String type=q.optString("type");content.put(key,type.equals("boolean")?Boolean.parseBoolean(value):type.equals("number")||type.equals("integer")?Double.valueOf(value):value);}submit("respond",new JSONObject().put("requestId",permission.id).put("decision","accept").put("answers",answers).put("content",content));dialog.dismiss();questionDialog=null;}catch(Exception e){showError(e);}});
    }

    private void newSession(){ListCache c=listCaches.get(backend);newSession(c==null?"":c.project);}
    private void newSession(String preferredProject){
        String saved=getSharedPreferences("creation",0).getString(backend+origin,"");
        if(!saved.isEmpty()){try{JSONObject receipt=new JSONObject(saved);alert().setTitle("有一项创建待核对").setMessage("使用原请求查询结果，避免重复创建。确认电脑上的结果后可清除记录。").setPositiveButton("查询结果",(d,w)->createSession(receipt)).setNeutralButton("已在电脑核对",(d,w)->getSharedPreferences("creation",0).edit().remove(backend+origin).apply()).setNegativeButton("取消",null).show();}catch(Exception e){showError(e);}return;}
        fetch("/api/native/"+backend+"/projects",null,epoch,value->{JSONArray projects=value.optJSONArray("projects");if(projects==null||projects.length()==0){toast("暂无可用项目，请先在电脑创建或打开一个项目");return;}
            LinearLayout body=column();Spinner mode=ui.spinner(new String[]{"Code · 编程","Work · 通用任务"});mode.setContentDescription("会话模式");if(backend.equals("claude"))add(body,mode,48);String[] names=new String[projects.length()];for(int i=0;i<names.length;i++){JSONObject p=projects.getJSONObject(i);names[i]=p.optString("name","项目")+" · "+p.optString("cwd",p.optString("hostLabel"));}Spinner project=ui.spinner(names);int chosen=-1;for(int pi=0;pi<projects.length();pi++)if(preferredProject.equals(projects.getJSONObject(pi).optString("cwd"))){chosen=pi;break;}
            // Never default a new conversation into the assistant's own bridge
            // directory just because it happens to be the most recent session.
            if(chosen<0)for(int pi=0;pi<projects.length();pi++)if(!projects.getJSONObject(pi).optBoolean("internal",false)){chosen=pi;break;}
            project.setSelection(chosen<0?0:chosen);project.setContentDescription("新会话项目");
            // Creating from a project header already fixes the directory, so the
            // picker is replaced by a plain label instead of a second choice.
            boolean fixed=chosen>=0&&!preferredProject.isEmpty();
            if(fixed){TextView target=text("项目目录 · "+projects.getJSONObject(chosen).optString("cwd"),NativeUi.CAPTION,MUTED);target.setPadding(dp(2),0,0,0);target.setMaxLines(2);target.setEllipsize(TextUtils.TruncateAt.MIDDLE);add(body,target,40);}
            else add(body,project,NativeUi.ROW);
            gap(body,12);EditText name=new EditText(this);ui.input(name);name.setHint("会话名称（可选）");name.setSingleLine();name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});add(body,name,NativeUi.ROW);gap(body,16);Button create=button("创建会话",true);add(body,create,NativeUi.ROW);Dialog dialog=sheet("新建会话",fixed?"在这个项目目录中创建":"选择工作目录，在电脑上创建并同步到手机",body);
            create.setOnClickListener(v->{try{JSONObject picked=projects.getJSONObject(project.getSelectedItemPosition());JSONObject request=new JSONObject().put("id",UUID.randomUUID().toString()).put("projectKey",picked.getString("key")).put("cwd",picked.optString("cwd")).put("title",name.getText().toString()).put("mode",mode.getSelectedItemPosition()==1?"work":"code");if(!getSharedPreferences("creation",0).edit().putString(backend+origin,request.toString()).commit())throw new IOException("无法保存创建请求");dialog.dismiss();createSession(request);}catch(Exception e){showError(e);}});
        });
    }
    private void createSession(JSONObject receipt){toast("正在创建或核对会话…");final String key=backend+origin;fetch("/api/native/"+backend+"/create",receipt,epoch,value->creationResult(receipt,value,key));}
    private void creationResult(JSONObject receipt,JSONObject value,String key)throws Exception{
        String state=value.optString("status");if(state.equals("ready")&&value.optJSONObject("result")!=null){JSONObject result=value.getJSONObject("result");getSharedPreferences("creation",0).edit().remove(key).apply();ListCache c=listCaches.get(backend);if(c!=null)c.loaded=0;
            // Say so when the desktop put the session somewhere else, instead of
            // silently opening a conversation in the wrong directory.
            if(value.has("cwdVerified")&&!value.isNull("cwdVerified")&&!value.optBoolean("cwdVerified",true))toast("电脑把会话建在了 "+value.optString("actualCwd","其他目录")+"，请在电脑确认目录信任");
            openSession(result.getString("id"),result.optString("title","新会话"));return;}
        if(state.equals("failed")||state.equals("unknown")||state.equals("missing")){toast(value.optString("message","创建结果待核对，点击新建可查询原请求"));return;}
        final int generation=epoch;final String path="/api/native/"+backend+"/creation/"+receipt.getString("id");handler.postDelayed(()->{if(isDestroyed()||generation!=epoch||paused)return;fetch(path,null,generation,r->creationResult(receipt,r,key));},2000);
    }
    private String modeName(String mode){switch(mode){case "read-only":return "只读 · 修改时询问";case "workspace-write":return "项目内读写 · 越界时询问";case "full-access":return "完全访问 · 不再询问";case "plan":return "计划模式 · 只读分析";case "acceptEdits":return "允许编辑 · 其他操作询问";case "bypassPermissions":return "完全访问 · 跳过权限确认";case "default":return "标准权限 · 按需询问";default:return "沿用电脑配置";}}
    private void chooseAccess(){if(sessionId==null||inlineOptions==null)return;if(sending||pending!=null){toast("请先等待当前操作确认");return;}if(expandedOption.equals("access")){closeOptions();return;}closeOptions();expandedOption="access";loadingOptions("正在读取审批选项…");fetch("/api/native/"+backend+"/"+sessionId+"/access",null,epoch,value->{if(!expandedOption.equals("access")||inlineOptions==null)return;inlineOptions.removeAllViews();ui.reveal(inlineOptions,true);LinearLayout entries=column();ScrollView scroll=new ScrollView(this);scroll.addView(entries);add(inlineOptions,scroll,168);JSONArray modes=value.getJSONArray("modes");for(int i=0;i<modes.length();i++){final String mode=modes.getString(i);Button choose=ui.menu((mode.equals(value.optString("mode"))?"✓ ":"")+modeName(mode),mode.equals(value.optString("mode")));add(entries,choose,48);choose.setOnClickListener(v->{if(mode.equals("full-access")||mode.equals("bypassPermissions")){entries.removeAllViews();add(entries,text("允许修改电脑文件与运行命令，且不再逐次询问。",NativeUi.CAPTION,INK),-2);Button confirm=button("确认完全访问",true);add(entries,confirm,48);confirm.setOnClickListener(w->applyAccess(mode));}else applyAccess(mode);});}positionOptions();});}
    private void applyAccess(String mode){try{closeOptions();submit("access",new JSONObject().put("mode",mode));}catch(Exception e){showError(e);}}

    private void pickImages(){ChatCache cache=chatCaches.get(sessionKey);if(uploading||cache==null)return;if(cache.images.size()>=4){toast("每条消息最多 4 张图片");return;}pickerKey=sessionKey;Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);picker.setType("image/*");picker.addCategory(Intent.CATEGORY_OPENABLE);picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(picker,701);}
    private void uploadPicked(Intent result){final String key=pickerKey,host=origin,credential=token;final ChatCache cache=chatCaches.get(key);if(cache==null){toast("会话已切换，请重新选择图片");return;}ArrayList<Uri> uris=new ArrayList<>();if(result.getClipData()!=null)for(int i=0;i<result.getClipData().getItemCount();i++)uris.add(result.getClipData().getItemAt(i).getUri());else if(result.getData()!=null)uris.add(result.getData());if(uris.isEmpty()){toast("没有读取到所选图片，请重试");return;}final int available=4-cache.images.size();if(uris.size()>available)toast("最多添加 4 张图片，已选取前 "+available+" 张");uploading=true;updateSend();toast("正在处理图片…");workers.execute(()->{ArrayList<JSONObject> completed=new ArrayList<>();String error="";try{String[] parts=key.split(":",2);for(int i=0;i<Math.min(available,uris.size());i++){JSONObject body=ImageAttachment.prepare(this,uris.get(i));JSONObject staged=request(host,credential,"/api/native/"+parts[0]+"/"+parts[1]+"/upload",body);staged.put("thumbnail",body.optString("thumbnail"));completed.add(staged);}}catch(Exception e){error=errorMessage(e);}final String message=error;runOnUiThread(()->{uploading=false;if(!host.equals(origin)||!credential.equals(token))return;cache.images.addAll(completed);if(key.equals(sessionKey)){renderAttachments();updateSend();}if(!message.isEmpty())toast(message);});});}
    private void renderAttachments(){if(attachmentRow==null)return;attachmentRow.removeAllViews();ChatCache cache=chatCaches.get(sessionKey);if(cache==null)return;
        // Take a snapshot: an upload completing on a worker thread appends here
        // while the UI thread renders, which a live iterator would reject.
        java.util.List<JSONObject> snapshot;java.util.List<JSONObject> list=cache.images;
        synchronized(list){snapshot=new ArrayList<JSONObject>(list);}
        for(JSONObject image:snapshot){
            LinearLayout item=column();item.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView thumbnail=new ImageView(this);thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            try{byte[] data=android.util.Base64.decode(image.optString("thumbnail"),android.util.Base64.DEFAULT);thumbnail.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(data,0,data.length));}catch(Exception ignored){}
            // The decoration is announced by its neighbours, which already carry the
            // attachment name and the remove action.
            thumbnail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.addView(thumbnail,new LinearLayout.LayoutParams(dp(52),dp(44)));
            Button remove=ui.iconButton("close","移除附件 "+image.optString("name"));
            remove.setBackground(ui.touch(Color.TRANSPARENT,NativeUi.CORNER_ROW,false));
            // The listener used to sit on the whole row, so tapping the thumbnail
            // deleted the attachment. Only the remove button removes now.
            remove.setOnClickListener(v->{if(sending||pending!=null)return;cache.images.remove(image);renderAttachments();updateSend();});
            item.addView(remove,new LinearLayout.LayoutParams(dp(52),dp(48)));
            attachmentRow.addView(item,new LinearLayout.LayoutParams(dp(56),-2));
        }
        attachmentRow.setVisibility(snapshot.isEmpty()?View.GONE:View.VISIBLE);}
    private DirectoryBrowser fileBrowser;private String fileBrowserKey="";
    private void browseFiles(String path){browseFiles(path,0);}
    private final android.util.LruCache<String,android.graphics.Bitmap> inlineImages=new android.util.LruCache<String,android.graphics.Bitmap>(12*1024*1024){protected int sizeOf(String key,android.graphics.Bitmap image){return image.getByteCount();}};
    void loadInlineImage(ImageView view,String reference){if(sessionId==null)return;final String key=origin+sessionKey+reference;view.setTag(key);android.graphics.Bitmap cached=inlineImages.get(key);if(cached!=null){view.setImageBitmap(cached);return;}final String host=origin,credential=token,path="/api/native/"+backend+"/"+sessionId+"/file?path="+Uri.encode(reference);final int generation=epoch;workers.execute(()->{try{JSONObject file=request(host,credential,path,null);if(!file.optString("mime").startsWith("image/"))return;byte[] bytes=android.util.Base64.decode(file.getString("data"),android.util.Base64.DEFAULT);android.graphics.BitmapFactory.Options opts=new android.graphics.BitmapFactory.Options();opts.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts);opts.inSampleSize=1;while(Math.max(opts.outWidth,opts.outHeight)/opts.inSampleSize>1400)opts.inSampleSize*=2;opts.inJustDecodeBounds=false;android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts);if(bitmap!=null){inlineImages.put(key,bitmap);runOnUiThread(()->{if(generation==epoch&&key.equals(view.getTag()))view.setImageBitmap(bitmap);});}}catch(Exception ignored){runOnUiThread(()->{if(generation==epoch&&key.equals(view.getTag()))view.setContentDescription("图片暂不可用，点击重试预览");});}});}
    void openReference(String reference){if(sessionId==null)return;fetch("/api/native/"+backend+"/"+sessionId+"/file?path="+Uri.encode(reference),null,epoch,file->NativePreview.show(this,file));}
    private void browseFiles(String path,int offset){
        if(sessionId==null)return;
        // One persistent sheet per session: reopening it is instant and its page
        // cache survives, instead of asking the computer for the same directory again.
        final String browserKey=origin+"|"+token+"|"+sessionKey;
        if(fileBrowser==null||!browserKey.equals(fileBrowserKey)){
            fileBrowserKey=browserKey;
            fileBrowser=new DirectoryBrowser(this,ui,chatPath,(folder,start,result)->{
                final String host=origin,credential=token,base="/api/native/"+backend+"/"+sessionId;final int generation=epoch;
                workers.execute(()->{
                    try{JSONObject value=request(host,credential,base+"/files?path="+Uri.encode(folder)+"&offset="+start,null);runOnUiThread(()->{if(generation==epoch&&!isFinishing())result.done(value,null);});}
                    catch(Exception e){runOnUiThread(()->{if(generation==epoch&&!isFinishing())result.done(null,errorMessage(e));});}
                });
            },item->{final String host=origin,credential=token,base="/api/native/"+backend+"/"+sessionId;final int generation=epoch;toast("正在读取文件…");fetch(base+"/file?path="+Uri.encode(item.optString("path")),null,generation,file->NativePreview.show(this,file));});
        }
        fileBrowser.show(path);
    }
    private void showAgents(){
        if(!backend.equals("codex")){ArrayList<String> steps=new ArrayList<>();if(messageAdapter!=null)for(SessionPage.Row r:messageAdapter.rows)if(r.activity())steps.add(r.label()+"\n"+r.text);LinearLayout body=column();if(steps.isEmpty()){add(body,emptyState("code","本页暂无任务步骤","执行中的工具操作会同步显示在会话中"),-2);}else{TextView view=text(TextUtils.join("\n\n",steps),NativeUi.BODY,INK);view.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.addView(view);add(body,scroll,360);}sheet(providerName(backend)+" 任务步骤","当前消息页",body);return;}
        fetch("/api/native/codex/"+sessionId+"/agents",null,epoch,value->{JSONArray agents=value.optJSONArray("agents");LinearLayout body=column();if(agents==null||agents.length()==0)add(body,emptyState("spark","此任务暂无智能体","执行中的子智能体会显示在这里"),-2);else for(int i=0;i<agents.length();i++){JSONObject a=agents.getJSONObject(i);String state=a.optString("status");add(body,text(a.optString("name","智能体")+"  ·  "+(state.equals("running")?"执行中":state.equals("completed")?"已完成":"空闲"),NativeUi.BODY,INK),48);}ScrollView scroll=new ScrollView(this);scroll.addView(body);LinearLayout wrapper=column();add(wrapper,scroll,Math.min(360,Math.max(64,(agents==null?0:agents.length())*48)));sheet("Codex 智能体","当前任务的子智能体",wrapper);});
    }
    private void startNotifications(){if(token.isEmpty())return;MonitorConnection.start(this);}
    private void handleNotification(Intent intent){if(intent==null||token.isEmpty())return;String id=intent.getStringExtra("sessionId"),kind=intent.getStringExtra("backend");if(id!=null&&( "codex".equals(kind)||"claude".equals(kind)||"deepseek".equals(kind))){backend=kind;openSession(id,intent.getStringExtra("title"));intent.removeExtra("sessionId");}}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleNotification(intent);}
    @Override public void onBackPressed(){if(gitPage!=null){closeGit();return;}if(!expandedOption.isEmpty()){closeOptions();return;}if(sessionId!=null){showTab(backend);return;}if(backend.equals("settings")){showTab(settingsReturn);return;}if(!token.isEmpty()&&!backend.equals(firstTab())){showTab(firstTab());return;}if(System.currentTimeMillis()-lastBack<1500){super.onBackPressed();return;}lastBack=System.currentTimeMillis();toast("再按一次返回退出");}
    @Override public void onPause(){super.onPause();paused=true;pollTicket++;pollEpoch=-1;for(HttpURLConnection active:liveConnections.values())active.disconnect();if(composer!=null){composer.clearFocus();root.requestFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(composer.getWindowToken(),0);}if(nextPoll!=null)handler.removeCallbacks(nextPoll);}
    @Override public void onResume(){super.onResume();boolean was=paused;paused=false;if(was){root.requestFocus();getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);}if(was)MonitorConnection.start(this);
        // Returning from the overlay-permission screen is the exact moment the grant
        // becomes true. Nothing else looked at it again, so the user had to toggle the
        // switch a second time before the pet could possibly appear. This only makes sure
        // the monitor is bound - force-drawing here would paint the pet over the app the
        // user just came back to, which is the one thing the foreground gate exists to
        // prevent, and it made the overlay reappear on every resume.
        if(was&&getSharedPreferences("ui",0).getBoolean("overlay",false)&&android.provider.Settings.canDrawOverlays(this))StatusOverlay.ensureRunning(this);
        if(was&&sessionId!=null){pollEpoch=-1;poll(epoch,sessionId);}else if(was&&displayedListKey!=null)loadSessions(displayedListKey,false);}
    @Override public void onDestroy(){super.onDestroy();handler.removeCallbacksAndMessages(null);workers.shutdownNow();liveWorkers.shutdownNow();historyWorkers.shutdownNow();
        // The directory browser holds the Activity and a dialog window; leaving it
        // open across a rotation or a finish leaked both until the process died.
        if(fileBrowser!=null){fileBrowser.close();fileBrowser=null;}
        if(questionDialog!=null){questionDialog.dismiss();questionDialog=null;}}
}
