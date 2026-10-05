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
    private LinearLayout queuePanel;private JSONArray queuedMessages=new JSONArray();private boolean queueExpanded=false;
    private int pollTicket=0,historyTicket=0;
    private final java.util.concurrent.ConcurrentHashMap<Thread,HttpURLConnection> liveConnections=new java.util.concurrent.ConcurrentHashMap<>();
    private final ExecutorService liveWorkers=Executors.newFixedThreadPool(2,r->new Thread(r,"conversation-live"));
    private NativeUi ui;
    private LinearLayout appBar,historyBar,emptyState;
    private TextView connectionLabel,recordCount,modelLabel,emptyTitle,emptyDetail;
    private long appStatusUntil;private String connectionText="连接中";private boolean connectionGood=false;
    private Button projectButton,groupButton,refreshButton;
    private EditText search;
    private boolean followingLatest=true;
    private boolean keyboardShown=false,hasEarlier=false;
    private LinearLayout chatContext,chatHeader,attachmentRow;
    private boolean headerHidden=false;
    private String selectedModel=""; private TextView accessLabel; private int catalogTicket;
    private final Runnable revealHeader=()->{if(!this.paused&&!this.scrolling&&this.chatHeader!=null)setHeaderHidden(false);};
    private FastScroller fastScroller;
    private String pickerKey="";
    private boolean uploading=false;
    private TextView composeFoot;
    private NativeUi.DockScroll composerScroll;
    private Button actionHint;
    private int permissionCount=0;
    private String chatPath="";
    private final Handler handler=new Handler();
    private final ExecutorService workers=Executors.newFixedThreadPool(3);
    private LinearLayout root,screen,bottom,chatMessages,permissions,pendingPanel;
    private TextView status,chatMeta;
    private EditText composer;
    private Button sendButton,stopButton;
    private ListView messageList,sessionList;
    private ScrollView permissionScroll;
    private MessageAdapter messageAdapter;
    private SessionAdapter sessionAdapter;
    private Button earlierButton,latestButton;
    private String displayedListKey=null,sessionKey=null;
    private Integer pageBefore=null;
    private boolean historyLoading=false;
    private long identityClock;private final Map<String,Long> identityVersions=new HashMap<>();
    private final Map<String,ListCache> listCaches=new HashMap<>();
    private final LinkedHashMap<String,ChatCache> chatCaches=new LinkedHashMap<String,ChatCache>(8,.75f,true){
        protected boolean removeEldestEntry(Map.Entry<String,ChatCache> e){return size()>6;}
    };
    private static final class ListCache { JSONArray rows=new JSONArray(); long loaded=0; boolean loading=false,grouped=true,connected=false; int first=0,top=0; String error="",query="",project="",notice=""; final HashSet<String> collapsed=new HashSet<>(); }
    private static final class ChatCache { SessionPage page; int first=-1,top=0; String title="",cwd="",draft=""; boolean followingLatest=true; Boolean running=null; String completed=""; JSONObject catalog; final HashSet<String> plugins=new HashSet<>(); final ArrayList<JSONObject> images=new ArrayList<>(); final HashSet<String> expanded=new HashSet<>(),collapsed=new HashSet<>(); }

    private String origin="",token="",backend="claude",sessionId=null,lastMessages="";
    private JSONObject pending=null;
    private int epoch=0;
    private boolean paused=false,sending=false;
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
    private TextView title(String label){TextView v=text(label,28,INK);v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return v;}
    private Button button(String label,boolean primary){return ui.button(label,primary);}
    private ImageView logo(int size){ImageView image=new ImageView(this);image.setImageResource(getResources().getIdentifier("brand","drawable",getPackageName()));image.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));return image;}
    private void add(LinearLayout parent,View child,int height){parent.addView(child,new LinearLayout.LayoutParams(-1,height<0?height:dp(height)));}
    private void gap(LinearLayout parent,int h){View v=new View(this);add(parent,v,h);}
    private void toast(String value){if(lastToast!=null)lastToast.cancel();lastToast=Toast.makeText(this,value,Toast.LENGTH_LONG);lastToast.show();}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private void rule(LinearLayout parent){View v=new View(this);v.setBackgroundColor(NativeUi.LINE);add(parent,v,1);}
    private void inset(View v,int x,int y){v.setPadding(dp(x),dp(y),dp(x),dp(y));}
    private void medium(TextView v){v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));}
    private void compact(Button b){b.setBackground(ui.touch(Color.TRANSPARENT,12,false));b.setTextColor(GREEN);}
    private void single(TextView v){v.setSingleLine();v.setEllipsize(TextUtils.TruncateAt.END);}
    private String projectName(String path){if(path==null||path.isEmpty())return "未指定项目";String p=path.replace('\\','/').replaceAll("/+$","");int i=p.lastIndexOf('/');return i>=0?p.substring(i+1):p;}
    private void brandBar(){
        appBar.removeAllViews();appBar.setVisibility(View.VISIBLE);inset(appBar,24,8);appBar.addView(logo(28));
        TextView brand=text("CODING  /  MOBILE",12,INK);brand.setLetterSpacing(.08f);medium(brand);brand.setPadding(dp(10),0,0,0);appBar.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
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
        appBar=row();add(root,appBar,68);brandBar();
        stage=new FrameLayout(this);screen=column();stage.addView(screen,new FrameLayout.LayoutParams(-1,-1));root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        bottom=new LinearLayout(this);bottom.setBackgroundColor(BG);bottom.setPadding(dp(18),dp(6),dp(18),dp(6));add(root,bottom,74);
        try{String stored=SecureStore.read(this);if(!stored.isEmpty()){JSONObject d=new JSONObject(stored);origin=d.getString("origin");token=d.getString("token");}}
        catch(Exception e){handler.postDelayed(this::retrySavedConnection,1500);}
        try{String p=getSharedPreferences("pending",0).getString("request","");if(!p.isEmpty())pending=new JSONObject(p);}catch(Exception ignored){}
        if(token.isEmpty())home();else if(getSharedPreferences("ui",0).getBoolean("resumeChat",false)&&!getSharedPreferences("ui",0).getString("lastSession","").isEmpty()){backend=getSharedPreferences("ui",0).getString("lastBackend",firstTab());String id=getSharedPreferences("ui",0).getString("lastSession","");lastDesktopKey=backend+":"+id;openSession(id,getSharedPreferences("ui",0).getString("lastTitle","会话"));}else showTab(getSharedPreferences("ui",0).getString("backend",firstTab()));
        handler.post(new Runnable(){int tick=0;public void run(){if(isDestroyed())return;ChatCache c=chatCaches.get(sessionKey);if(!paused&&connectionLabel!=null&&c!=null&&Boolean.TRUE.equals(c.running)){String[] dots={"·","··","···"};connectionLabel.setText("正在思考 "+dots[tick++%3]);}handler.postDelayed(this,650);}});
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
        pendingPanel=null;sendButton=null;stopButton=null;status=null;composer=null;sessionList=null;sessionAdapter=null;messageList=null;messageAdapter=null;permissions=null;
        appStatusUntil=0;connectionLabel=null;search=null;historyBar=null;emptyState=null;chatContext=null;composeFoot=null;composerScroll=null;actionHint=null;permissionCount=0;followingLatest=true;chatPath="";hasEarlier=false;
        chatHeader=null;attachmentRow=null;fastScroller=null;headerHidden=false;floating=null;taskPanel=null;sessionTitle=null;
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
            showTab(next);screen.animate().cancel();screen.setAlpha(1);screen.setTranslationX(-direction*width);
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
    private void clearCaches(){listCaches.clear();chatCaches.clear();identityVersions.clear();ConversationCache.clear(this);}
    private void navigation(){
        bottom.removeAllViews();bottom.setVisibility(View.VISIBLE);
        String[] keys={"claude","codex","deepseek"},names={"Claude","Codex","DeepSeek"};
        String[] icons={"spark","code","spark"};
        for(int i=0;i<3;i++){final String key=keys[i];if(!getSharedPreferences("ui",0).getBoolean("show_"+key,true))continue;boolean selected=backend.equals(key);LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setBackground(ui.touch(selected?NativeUi.TINT:Color.TRANSPARENT,18,false));
            ImageView tabIcon=ui.icon(icons[i],selected?GREEN:MUTED,23);tabIcon.setImageResource(getResources().getIdentifier(key+"_official","drawable",getPackageName()));item.addView(tabIcon,new LinearLayout.LayoutParams(dp(23),dp(23)));gap(item,5);TextView label=text(names[i],11,selected?GREEN:MUTED);medium(label);label.setGravity(Gravity.CENTER);item.addView(label,new LinearLayout.LayoutParams(-2,-2));
            item.setContentDescription(names[i]);item.setSelected(selected);item.setFocusable(true);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(dp(5),0,dp(5),0);bottom.addView(item,p);item.setOnClickListener(v->showTab(key));}
    }
    private void home(){
        reset();bottom.setVisibility(View.GONE);ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);LinearLayout card=column();card.setPadding(dp(28),dp(22),dp(28),dp(24));
        LinearLayout artwork=row();artwork.setGravity(Gravity.CENTER);artwork.setBackground(ui.surface(NativeUi.TINT,24,false));
        artwork.addView(ui.icon("monitor",GREEN,54),new LinearLayout.LayoutParams(dp(64),dp(64)));TextView dots=text("· · ·",24,0xffa0b7a4);dots.setPadding(dp(16),0,dp(16),0);artwork.addView(dots);artwork.addView(ui.icon("phone",GREEN,38),new LinearLayout.LayoutParams(dp(44),dp(54)));add(card,artwork,118);
        gap(card,32);TextView eyebrow=text("继续电脑上的工作",12,GREEN);eyebrow.setLetterSpacing(.14f);add(card,eyebrow,-2);gap(card,14);
        TextView welcome=title("灵感随行，\n工作继续。");welcome.setTextSize(34);welcome.setLineSpacing(dp(6),1);add(card,welcome,-2);gap(card,16);
        add(card,text("连接你的电脑，随时查看进度、\n继续对话，处理需要确认的操作。",15,MUTED),-2);gap(card,28);
        LinearLayout steps=column();inset(steps,16,16);steps.setBackground(ui.surface(NativeUi.WHITE,16,true));
        add(steps,text("01   在电脑助手中开启设备连接",13,INK),-2);gap(steps,12);add(steps,text("02   扫描二维码，安全连接",13,INK),-2);add(card,steps,-2);gap(card,22);
        Button scan=button("扫码连接电脑",true);ui.leading(scan,"scan",NativeUi.WHITE,20);add(card,scan,54);scan.setOnClickListener(v->scan());gap(card,8);
        Button paste=button("粘贴配对码",false);compact(paste);add(card,paste,48);paste.setOnClickListener(v->{ClipboardManager c=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData data=c.getPrimaryClip();if(data!=null&&data.getItemCount()>0)pair(data.getItemAt(0).coerceToText(this).toString());else toast("剪贴板为空");});
        gap(card,16);status=text("无需账号密码 · 配对后自动重连",12,MUTED);status.setGravity(Gravity.CENTER);add(card,status,-2);gap(card,12);scroll.addView(card);add(screen,scroll,-1);
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
        if(!TrustedOrigin.valid(address))throw new IOException("连接地址不受支持，请重新扫码绑定");HttpURLConnection c=EmbeddedNetwork.open(this,address+path);c.setConnectTimeout(7000);c.setReadTimeout(body==null?18000:110000);c.setInstanceFollowRedirects(false);
        if(Thread.currentThread().getName().equals("conversation-live"))liveConnections.put(Thread.currentThread(),c);c.setRequestProperty("Accept","application/json");c.setRequestProperty("Origin",address);if(!credential.isEmpty())c.setRequestProperty("Authorization","Bearer "+credential);
        try{if(body!=null){byte[] bytes=body.toString().getBytes("UTF-8");c.setRequestMethod("POST");c.setRequestProperty("Content-Type","application/json");c.setDoOutput(true);c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
            int code=c.getResponseCode();InputStream in=code<400?c.getInputStream():c.getErrorStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(in!=null)try(InputStream input=in){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(bytes.size()+n>32*1024*1024)throw new IOException("会话内容过大，请在电脑查看");bytes.write(buffer,0,n);}}
            JSONObject result;try{result=new JSONObject(bytes.toString("UTF-8"));}catch(Exception e){throw new ConnectionProfiles.HttpFailure(code>=500?code:502,"连接入口暂时未就绪，正在尝试恢复");}
            if(code>=300)throw new ApiError(code,result.optString("error","连接失败"));return result;
        }finally{liveConnections.remove(Thread.currentThread());c.disconnect();}
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
        LinearLayout card=column();inset(card,12,10);card.setBackground(ui.surface(NativeUi.SOFT,14,true));add(card,text(sending?"正在提交到电脑…":"上次操作的结果尚未确认",12,MUTED),-2);LinearLayout actions=row();
        Button retry=button("重查结果",false);compact(retry);retry.setEnabled(!sending);actions.addView(retry,new LinearLayout.LayoutParams(0,dp(48),1));retry.setOnClickListener(v->sendPending());
        Button clear=button("已在电脑核对",false);compact(clear);clear.setEnabled(!sending);actions.addView(clear,new LinearLayout.LayoutParams(0,dp(48),1));add(card,actions,48);add(pendingPanel,card,-2);gap(pendingPanel,8);
        clear.setOnClickListener(v->alert().setMessage("确认已检查原会话？清除后可提交新消息。").setPositiveButton("已核对",(d,w)->{clearPending();pendingControls();}).setNegativeButton("取消",null).show());
    }

    private void showTab(String key){
        if(token.isEmpty()){home();return;}reset();backend=key;if(!key.equals("settings")&&!getSharedPreferences("ui",0).getBoolean("show_"+key,true)){showTab(firstTab());return;}navigation();
        if(key.equals("settings")){settings();return;}
        getSharedPreferences("ui",0).edit().putString("backend",key).putBoolean("resumeChat",false).apply();
        displayedListKey=key;ListCache cache=listCaches.get(key);if(cache==null){cache=new ListCache();listCaches.put(key,cache);}
        final ListCache current=cache;if(cache.loaded==0){final String cacheKey=origin+token+"list:"+key;workers.execute(()->{JSONObject raw=ConversationCache.read(this,cacheKey);if(raw!=null)runOnUiThread(()->{if(current.loaded==0&&raw.optJSONArray("sessions")!=null){current.rows=raw.optJSONArray("sessions");current.loaded=1;for(int i=0;i<current.rows.length();i++){JSONObject r=current.rows.optJSONObject(i);if(r!=null)syncIdentity(key,r.optString("id"),r.optString("title"),r.optString("cwd"),0);}if(key.equals(displayedListKey))showSessions(current);}});});}
        appBar.setVisibility(View.GONE);LinearLayout heading=column();inset(heading,20,4);LinearLayout top=row();TextView providerTitle=text(providerName(key)+" ⌄",24,INK);top.addView(providerTitle,new LinearLayout.LayoutParams(-2,-2));
        connectionLabel=text("",11,MUTED);connectionLabel.setPadding(dp(12),0,0,0);single(connectionLabel);top.addView(connectionLabel,new LinearLayout.LayoutParams(0,-2,1));Button settings=ui.iconButton("settings","设置");top.addView(settings,new LinearLayout.LayoutParams(dp(48),dp(48)));settings.setOnClickListener(v->openSettings());refreshButton=ui.iconButton("refresh","刷新会话");Button create=ui.iconButton("plus","新建会话");create.setOnClickListener(v->newSession());add(heading,top,52);top.setContentDescription(providerName(key)+" 客户端状态");top.setOnClickListener(v->toggleApplicationMenu(top,key));LinearLayout localActions=row();localActions.addView(refreshButton,new LinearLayout.LayoutParams(dp(48),dp(48)));localActions.addView(create,new LinearLayout.LayoutParams(dp(48),dp(48)));refreshButton.setOnClickListener(v->loadSessions(key,true));
        
        LinearLayout searchBox=row();inset(searchBox,14,0);searchBox.setBackground(ui.surface(NativeUi.WHITE,14,true));searchBox.addView(ui.icon("search",MUTED,20),new LinearLayout.LayoutParams(dp(20),dp(20)));
        search=new EditText(this);search.setSingleLine();search.setTextSize(14);search.setTextColor(INK);search.setHintTextColor(MUTED);search.setHint("搜索聊天或项目");search.setContentDescription("搜索聊天或项目");search.setBackgroundColor(Color.TRANSPARENT);inset(search,10,10);search.setText(cache.query);search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);searchBox.addView(search,new LinearLayout.LayoutParams(0,dp(44),1));
        Button clear=ui.iconButton("close","清除搜索");clear.setVisibility(cache.query.isEmpty()?View.GONE:View.VISIBLE);searchBox.addView(clear,new LinearLayout.LayoutParams(dp(48),dp(48)));clear.setOnClickListener(v->search.setText(""));
        search.addTextChangedListener(changed(()->{current.query=search.getText().toString();current.first=0;current.top=0;clear.setVisibility(current.query.isEmpty()?View.GONE:View.VISIBLE);showSessions(current);}));
        search.setOnEditorActionListener((v,action,event)->{((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(),0);search.clearFocus();return true;});localActions.addView(searchBox,0,new LinearLayout.LayoutParams(0,dp(44),1));add(heading,localActions,48);gap(heading,2);
        LinearLayout filters=row();projectButton=button("全部项目",false);projectButton.setTextSize(12);single(projectButton);ui.leading(projectButton,"folder",MUTED,16);projectButton.setOnClickListener(v->chooseProject());filters.addView(projectButton,new LinearLayout.LayoutParams(0,dp(48),1));
        groupButton=button("最近会话",false);groupButton.setTextSize(12);compact(groupButton);ui.leading(groupButton,"down",GREEN,16);LinearLayout.LayoutParams groupLp=new LinearLayout.LayoutParams(dp(120),dp(48));groupLp.leftMargin=dp(8);filters.addView(groupButton,groupLp);groupButton.setOnClickListener(v->{current.grouped=!current.grouped;current.first=0;current.top=0;showSessions(current);});add(heading,filters,48);
        status=text("",12,MUTED);status.setPadding(0,0,0,dp(4));add(heading,status,-2);add(screen,heading,-2);
        FrameLayout listArea=new FrameLayout(this);sessionList=new ListView(this);sessionList.setDivider(null);sessionList.setCacheColorHint(Color.TRANSPARENT);sessionList.setSelector(ui.touch(NativeUi.TINT,12,false));sessionList.setPadding(dp(16),0,dp(16),dp(12));sessionList.setClipToPadding(false);
        sessionAdapter=new SessionAdapter();sessionList.setAdapter(sessionAdapter);listArea.addView(sessionList,new FrameLayout.LayoutParams(-1,-1));
        emptyState=column();emptyState.setGravity(Gravity.CENTER);inset(emptyState,32,24);emptyState.addView(ui.icon("chat",0xff9dab9c,40),new LinearLayout.LayoutParams(dp(40),dp(40)));gap(emptyState,18);emptyTitle=text("",18,INK);medium(emptyTitle);emptyState.addView(emptyTitle);gap(emptyState,10);emptyDetail=text("",14,MUTED);emptyDetail.setGravity(Gravity.CENTER);add(emptyState,emptyDetail,-2);listArea.addView(emptyState,new FrameLayout.LayoutParams(-1,-1));emptyState.setVisibility(View.GONE);screen.addView(listArea,new LinearLayout.LayoutParams(-1,0,1));
        sessionList.setOnItemClickListener((parent,view,position,itemId)->{CatalogItem item=sessionAdapter.items.get(position);if(item.archive){archivedSessions();return;}if(item.header){if(!current.collapsed.add(item.path))current.collapsed.remove(item.path);current.first=sessionList.getFirstVisiblePosition();current.top=sessionList.getChildCount()>0?sessionList.getChildAt(0).getTop():0;showSessions(current);}else{((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(),0);openSession(item.row.optString("id"),item.row.optString("title"));}});
        showSessions(cache);loadSessions(key,false);final int listGeneration=epoch;handler.postDelayed(new Runnable(){public void run(){if(listGeneration!=epoch||!key.equals(displayedListKey))return;if(!paused)loadSessions(key,false);handler.postDelayed(this,15000);}},15000);
    }
    private void showSessions(ListCache cache){
        if(sessionAdapter==null)return;sessionAdapter.filter(cache);
        projectButton.setText(cache.project.isEmpty()?"全部项目":projectName(cache.project));projectButton.setContentDescription("筛选项目："+(cache.project.isEmpty()?"全部项目":cache.project));groupButton.setText(cache.grouped?"按项目":"最近会话");
        status.setText(cache.loaded==0?"正在读取电脑会话…":sessionAdapter.matches+" 条会话"+(cache.loading?" · 更新中":"")+(cache.notice.isEmpty()?"":" · "+cache.notice));status.setTextColor(MUTED);
        connection(cache.loaded>0?"已缓存":"连接中",false);
        if(cache.loaded>0&&System.currentTimeMillis()-cache.loaded<15000&&cache.error.isEmpty())connection(cache.connected?providerName(backend)+" 已连接":"助手可达 · 应用未连接",cache.connected);
        if(!cache.error.isEmpty()){status.setText(cache.error+(cache.loaded>0?" · 显示上次记录":""));status.setTextColor(NativeUi.RED);connection("连接中断",false);}
        refreshButton.setEnabled(!cache.loading);refreshButton.setAlpha(cache.loading?.4f:1f);
        emptyState.setVisibility(sessionAdapter.getCount()==0?View.VISIBLE:View.GONE);
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
            for(int i=0;i<value.rows.length();i++){JSONObject r=value.rows.optJSONObject(i);if(r==null)continue;String path=r.optString("cwd","");if(!value.project.isEmpty()&&!value.project.equals(path))continue;if(!q.isEmpty()&&!(r.optString("title","")+" "+path).toLowerCase(Locale.ROOT).contains(q))continue;matches++;
                if(r.optBoolean("archived")){archived.add(new CatalogItem(r,path));continue;}if(!value.grouped)items.add(new CatalogItem(r,path));else{ArrayList<JSONObject> group=groups.get(path);if(group==null){group=new ArrayList<>();groups.put(path,group);}group.add(r);}}
            for(Map.Entry<String,ArrayList<JSONObject>> group:groups.entrySet()){CatalogItem h=new CatalogItem(null,group.getKey());h.count=group.getValue().size();items.add(h);if(!value.collapsed.contains(h.path))for(JSONObject r:group.getValue())items.add(new CatalogItem(r,h.path));}for(CatalogItem archivedItem:archived)archivedRows.add(archivedItem.row);CatalogItem archiveEntry=new CatalogItem(null,"");archiveEntry.archive=true;items.add(archiveEntry);notifyDataSetChanged();}
        public int getCount(){return items.size();}public Object getItem(int p){return items.get(p);}public long getItemId(int p){return p;}
        public int getViewTypeCount(){return 2;}public int getItemViewType(int p){return items.get(p).header?1:0;}
        public View getView(int position,View recycled,ViewGroup parent){
            CatalogItem data=items.get(position);
            if(data.archive){Button archive=button("归档",false);archive.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);archive.setBackground(ui.touch(Color.TRANSPARENT,12,false));ui.leading(archive,"folder",MUTED,18);archive.setOnClickListener(v->archivedSessions());return archive;}
            if(data.header){LinearLayout group=row();TextView header=text(projectName(data.path)+"   ·   "+data.count,16,GREEN);header.setPadding(dp(8),dp(10),0,dp(10));single(header);ui.leading(header,cache.collapsed.contains(data.path)?"right":"down",MUTED,16);group.addView(header,new LinearLayout.LayoutParams(0,dp(48),1));header.setOnClickListener(v->{if(!cache.collapsed.add(data.path))cache.collapsed.remove(data.path);showSessions(cache);});Button create=ui.iconButton("plus","在此项目新建会话");group.addView(create,new LinearLayout.LayoutParams(dp(44),dp(44)));create.setOnClickListener(v->newSession(data.path));return group;}
            LinearLayout item=column();item.setPadding(dp(12),dp(12),dp(12),0);LinearLayout top=row();TextView label=text("",16,INK);medium(label);label.setMaxLines(2);label.setEllipsize(TextUtils.TruncateAt.END);top.addView(label,new LinearLayout.LayoutParams(0,-2,1));TextView directory=text("",16,MUTED);single(directory);directory.setMaxWidth(dp(116));top.addView(directory,new LinearLayout.LayoutParams(-2,-2));TextView date=text("",11,MUTED);date.setPadding(dp(8),0,0,0);top.addView(date);add(item,top,-2);TextView meta=text("",12,MUTED);single(meta);add(item,meta,-2);gap(item,12);rule(item);
            JSONObject r=data.row;label.setText(r.optString("title","未命名会话"));directory.setText(!cache.grouped&&!data.path.isEmpty()?" · "+projectName(data.path):"");directory.setVisibility(directory.getText().length()==0?View.GONE:View.VISIBLE);String surface=r.optString("backend",backend);String active=r.optString("status","");boolean running=active.equals("active")||active.equals("running")||active.equals("inProgress");
            meta.setText(r.has("turnCount")?r.optInt("turnCount")+" 轮对话":"");meta.setVisibility(meta.getText().length()==0?View.GONE:View.VISIBLE);date.setText(r.optBoolean("archived")?"已归档":running?"●  进行中":sessionDate(r));date.setTextColor(running?GREEN:MUTED);item.setOnClickListener(v->openSession(r.optString("id"),r.optString("title")));item.setOnLongClickListener(v->{sessionActions(r);return true;});return item;
        }
    }
    private void archivedSessions(){
        ArrayList<JSONObject> archived=new ArrayList<>(sessionAdapter.archivedRows);ArrayList<String> labels=new ArrayList<>();for(JSONObject item:archived)labels.add(item.optString("title","未命名会话")+"\n"+projectName(item.optString("cwd")));
        LinearLayout body=column();ListView list=new ListView(this);list.setDivider(null);list.setAdapter(ui.choices(labels));add(body,list,Math.min(380,Math.max(56,labels.size()*64)));if(archived.isEmpty())add(body,text("暂无归档会话",14,MUTED),48);Dialog dialog=sheet("归档","选择会话查看",body);list.setOnItemClickListener((parent,view,position,id)->{JSONObject item=archived.get(position);dialog.dismiss();openSession(item.optString("id"),item.optString("title"));});
    }
    private void sessionActions(JSONObject session){
        LinearLayout body=column();Dialog dialog=sheet("编辑会话",session.optString("title"),body);
        for(String action:new String[]{"重命名","归档","删除","复制项目路径"}){if(action.equals("删除")&&backend.equals("deepseek"))continue;Button choice=button(action,false);add(body,choice,48);choice.setOnClickListener(v->{dialog.dismiss();
            if(action.equals("复制项目路径")){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("项目路径",session.optString("cwd")));toast("已复制");return;}
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
        Dialog dialog=new Dialog(this);LinearLayout panel=column();inset(panel,24,20);panel.setBackground(ui.surface(NativeUi.WHITE,24,true));LinearLayout top=row();TextView heading=text(title,21,INK);medium(heading);top.addView(heading,new LinearLayout.LayoutParams(0,-2,1));Button close=ui.iconButton("close","关闭");top.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));close.setOnClickListener(v->dialog.dismiss());add(panel,top,-2);
        if(!subtitle.isEmpty()){gap(panel,4);add(panel,text(subtitle,13,MUTED),-2);gap(panel,18);}add(panel,content,-2);gap(panel,8);dialog.setContentView(panel);dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);dialog.getWindow().setDimAmount(.28f);dialog.show();ui.window(dialog.getWindow(),true);return dialog;
    }
    private void chooseProject(){
        final ListCache cache=listCaches.get(displayedListKey);if(cache==null)return;ArrayList<String> paths=new ArrayList<>();paths.add("");
        for(int i=0;i<cache.rows.length();i++){JSONObject r=cache.rows.optJSONObject(i);String path=r==null?"":r.optString("cwd","");if(!paths.contains(path))paths.add(path);}
        ListView list=new ListView(this);list.setDivider(null);list.setAdapter(new BaseAdapter(){public int getCount(){return paths.size();}public Object getItem(int p){return paths.get(p);}public long getItemId(int p){return p;}public View getView(int p,View old,ViewGroup parent){TextView label=old instanceof TextView?(TextView)old:text("",14,INK);label.setPadding(dp(10),dp(18),dp(10),dp(18));single(label);label.setText(p==0?"全部项目":paths.get(p));label.setBackground(ui.touch(cache.project.equals(paths.get(p))?NativeUi.TINT:Color.TRANSPARENT,12,false));ui.leading(label,cache.project.equals(paths.get(p))?"check":"folder",GREEN,18);return label;}});
        LinearLayout body=column();add(body,list,Math.min(360,paths.size()*58));final Dialog dialog=sheet("选择项目","只查看这个项目中的聊天",body);
        list.setOnItemClickListener((p,v,index,id)->{cache.project=paths.get(index);cache.first=0;cache.top=0;showSessions(cache);dialog.dismiss();});
    }

    private void openSession(String id,String name){
        reset();sessionId=id;sessionKey=backend+":"+id;bottom.setVisibility(View.GONE);
        ChatCache cache=chatCaches.get(sessionKey);if(cache==null){cache=new ChatCache();cache.plugins.addAll(getSharedPreferences("composer-plugins",0).getStringSet(origin+"|"+sessionKey,java.util.Collections.emptySet()));chatCaches.put(sessionKey,cache);}
        if(cache.title.isEmpty())cache.title=name==null||name.isEmpty()?"未命名会话":name;chatPath=cache.cwd;
        root.setBackgroundColor(BG);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);appBar.setVisibility(View.GONE);
        chatHeader=column();chatHeader.setBackgroundColor(BG);chatHeader.setElevation(dp(2));LinearLayout top=row();inset(top,12,2);Button back=ui.iconButton("back","返回聊天列表");top.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));back.setOnClickListener(v->showTab(backend));
        LinearLayout identity=column();identity.setPadding(dp(4),0,0,0);sessionTitle=text(cache.title,17,INK);medium(sessionTitle);single(sessionTitle);add(identity,sessionTitle,-2);gap(identity,5);connectionLabel=text("",12,MUTED);add(identity,connectionLabel,-2);connection("正在读取",false);top.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
        Button info=ui.iconButton("info","会话信息");top.addView(info,new LinearLayout.LayoutParams(dp(48),dp(48)));info.setOnClickListener(v->sessionInfo());add(chatHeader,top,56);rule(chatHeader);
        recordCount=text("0 轮对话",11,MUTED);recordCount.setPadding(dp(4),0,dp(8),0);top.addView(recordCount,top.getChildCount()-1);projectButton=null;
        historyBar=row();historyBar.setGravity(Gravity.CENTER);earlierButton=button("查看更早的消息",false);compact(earlierButton);earlierButton.setTextSize(12);earlierButton.setMinHeight(0);earlierButton.setMinimumHeight(0);earlierButton.setBackground(ui.touch(Color.TRANSPARENT,12,false));historyBar.addView(earlierButton,new LinearLayout.LayoutParams(-2,dp(32)));historyBar.setVisibility(View.GONE);earlierButton.setOnClickListener(v->loadEarlier());
        FrameLayout transcript=new FrameLayout(this);messageList=new ListView(this);messageList.setDivider(null);messageList.setVerticalScrollBarEnabled(false);messageList.setSelector(android.R.color.transparent);messageList.setCacheColorHint(Color.TRANSPARENT);messageList.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_DISABLED);messageList.setPadding(dp(20),dp(68),dp(20),dp(20));messageList.setClipToPadding(false);messageAdapter=new MessageAdapter();messageList.setAdapter(messageAdapter);transcript.addView(messageList,new FrameLayout.LayoutParams(-1,-1));
        floating=new FloatingNavigation(this,ui,()->jumpToTop(),()->jumpToLatest());latestButton=floating.latest;
        fastScroller=new FastScroller(this,messageList,()->{followingLatest=false;},()->finishScroll());transcript.addView(fastScroller,new FrameLayout.LayoutParams(dp(28),-1,Gravity.RIGHT));
        transcript.addView(floating,new FrameLayout.LayoutParams(dp(50),dp(102)));transcript.addView(chatHeader,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));FrameLayout.LayoutParams historyLayout=new FrameLayout.LayoutParams(-2,dp(32),Gravity.TOP|Gravity.CENTER_HORIZONTAL);historyLayout.topMargin=dp(58);transcript.addView(historyBar,historyLayout);chatHeader.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(messageList==null||b-t==ob-ot)return;int padding=b-t+dp(hasEarlier?42:10);if(messageList.getPaddingTop()!=padding)messageList.setPadding(dp(20),padding,dp(20),dp(20));});final FloatingNavigation nav=floating;nav.place();transcript.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot)nav.place();});
        messageList.setOnTouchListener(new View.OnTouchListener(){float start,last;public boolean onTouch(View v,android.view.MotionEvent e){
            if(e.getActionMasked()==0){start=last=e.getRawY();requestedHeader=null;scrolling=true;handler.removeCallbacks(revealHeader);}
            else if(e.getActionMasked()==2){float delta=e.getRawY()-last;if(Math.abs(delta)>dp(24)){requestedHeader=delta<0;last=e.getRawY();}}
            else if(e.getActionMasked()==1||e.getActionMasked()==3){scrolling=false;handler.postDelayed(()->{if(!scrolling){finishScroll();scheduleHeaderReveal();}},100);}return false;}});
        messageList.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView v,int state){scrolling=state!=SCROLL_STATE_IDLE;if(!scrolling){finishScroll();scheduleHeaderReveal();}}
            public void onScroll(AbsListView v,int first,int visible,int total){if(fastScroller!=null)fastScroller.invalidate();updateNavigation();if(scrolling&&total>0){View last=v.getChildAt(v.getChildCount()-1);followingLatest=first+visible>=total&&last!=null&&last.getBottom()<=v.getHeight()-v.getPaddingBottom()+dp(12);}}});screen.addView(transcript,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout dock=column();dock.setPadding(dp(16),0,dp(16),dp(8));actionHint=button("",false);actionHint.setTextSize(12);actionHint.setTextColor(GREEN);actionHint.setBackground(ui.touch(NativeUi.TINT,12,false));ui.leading(actionHint,"shield",GREEN,18);add(dock,actionHint,48);actionHint.setVisibility(View.GONE);actionHint.setOnClickListener(v->{((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(composer.getWindowToken(),0);composer.clearFocus();final NativeUi.DockScroll scroller=composerScroll;if(scroller!=null)scroller.postDelayed(()->scroller.fullScroll(View.FOCUS_UP),200);});permissionScroll=new ScrollView(this);permissions=column();permissionScroll.addView(permissions);add(dock,permissionScroll,0);
        status=text("",12,NativeUi.RED);status.setPadding(dp(4),dp(4),dp(4),dp(6));status.setVisibility(View.GONE);status.addTextChangedListener(changed(()->{if(status!=null)status.setVisibility(status.getText().length()==0?View.GONE:View.VISIBLE);}));add(dock,status,-2);
        pendingPanel=column();add(dock,pendingPanel,-2);
        queuePanel=column();add(dock,queuePanel,-2);taskPanel=column();add(dock,taskPanel,-2);inlineOptions=column();inlineOptions.setVisibility(View.GONE);inlineOptions.setPadding(dp(12),dp(8),dp(12),dp(8));inlineOptions.setBackground(ui.surface(BG,16,true));inlineOptions.setElevation(dp(10));optionsDismiss=new View(this);optionsDismiss.setVisibility(View.GONE);optionsDismiss.setOnClickListener(v->closeOptions());stage.addView(optionsDismiss,new FrameLayout.LayoutParams(-1,-1));stage.addView(inlineOptions,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));LinearLayout compose=column();inset(compose,12,6);compose.setBackground(ui.surface(NativeUi.WHITE,20,true));
        composer=new EditText(this);composer.setTextSize(16);composer.setTextColor(INK);composer.setHintTextColor(0xffa2a8a1);composer.setHint("继续这段对话…");composer.setContentDescription("消息输入框");composer.setGravity(Gravity.TOP);composer.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);composer.setMinLines(3);composer.setMaxLines(5);composer.setBackgroundColor(Color.TRANSPARENT);composer.setPadding(0,dp(6),0,dp(6));composer.setText(cache.draft);LinearLayout inputLine=row();inputLine.setBaselineAligned(false);inputLine.setClipToPadding(false);inputLine.setGravity(Gravity.BOTTOM);inputLine.addView(composer,new LinearLayout.LayoutParams(0,-2,1));sendButton=button("",true);sendButton.setMinHeight(0);sendButton.setMinimumHeight(0);sendButton.setBackground(ui.touch(GREEN,22,false));ui.centerIcon(sendButton,"arrow",BG,20,-4);sendButton.setTextSize(9);sendButton.setIncludeFontPadding(false);sendButton.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);sendButton.setPadding(0,0,0,dp(10));sendButton.setOnLongClickListener(v->{showContextUsage();return true;});inputLine.addView(sendButton,new LinearLayout.LayoutParams(dp(56),dp(56)));add(compose,inputLine,-2);attachmentRow=row();add(compose,attachmentRow,-2);renderAttachments();
        LinearLayout controls=row();optionAnchor=controls;controls.setGravity(Gravity.BOTTOM);LinearLayout options=row();
        modelLabel=option("模型 · 思考",()->toggleCatalog("model"));effortLabel=text("",11,MUTED);pluginLabel=option(cache.plugins.isEmpty()?"插件":"插件 · "+cache.plugins.size(),()->toggleCatalog("plugins"));TextView access=option("审批",()->chooseAccess());accessLabel=access;
        for(TextView option:new TextView[]{modelLabel,access,pluginLabel}){option.setPadding(dp(2),0,dp(2),0);option.setTextSize(11);options.addView(option,new LinearLayout.LayoutParams(0,dp(44),option==modelLabel?2:1));}
        controls.addView(options,new LinearLayout.LayoutParams(0,dp(44),1));Button attach=ui.iconButton("plus","添加附件");controls.addView(attach,new LinearLayout.LayoutParams(dp(44),dp(44)));attach.setOnClickListener(v->pickImages());stopButton=sendButton;add(compose,controls,44);add(dock,compose,-2);NativeUi.DockScroll dockScroll=new NativeUi.DockScroll(this);composerScroll=dockScroll;dockScroll.addView(dock);add(screen,dockScroll,-2);
        composer.addTextChangedListener(changed(()->updateSend()));
        sendButton.setOnClickListener(v->{String value=composer.getText().toString();ChatCache current=chatCaches.get(sessionKey);JSONArray images=new JSONArray();if(current!=null)for(JSONObject image:current.images)images.put(image.optString("id"));
            if(value.trim().isEmpty()&&images.length()==0){if(current!=null&&Boolean.TRUE.equals(current.running))submit("stop",new JSONObject());return;}
            try{JSONArray plugins=new JSONArray();if(current!=null)for(String plugin:current.plugins)plugins.put(plugin);JSONObject message=new JSONObject().put("text",value).put("images",images).put("plugins",plugins).put("mode","auto");if(current!=null&&Boolean.TRUE.equals(current.running)){JSONArray attachments=new JSONArray();for(JSONObject image:current.images)attachments.put(image);message.put("attachments",attachments);submit("queue",new JSONObject().put("operation","enqueue").put("message",message));}else submit("send",message);}catch(Exception e){showError(e);}});
        pendingControls();if(cache.page!=null){followingLatest=cache.followingLatest;renderSession(cache.page,false);if(cache.first>=0)messageList.setSelectionFromTop(cache.first,cache.top);}root.requestFocus();root.requestApplyInsets();getSharedPreferences("ui",0).edit().putBoolean("resumeChat",true).putString("lastSession",id).putString("lastTitle",cache.title).putString("lastBackend",backend).apply();
        if(cache.page==null)restoreConversation(sessionKey,epoch);if(!paused){final int generation=epoch;if(!sessionKey.equals(lastDesktopKey)){lastDesktopKey=sessionKey;fetch("/api/native/"+backend+"/"+id+"/open",new JSONObject(),generation,value->{});}poll(generation,id);}
    }
    private void updateSend(){if(sendButton==null)return;ChatCache current=chatCaches.get(sessionKey);boolean draft=composer!=null&&(!composer.getText().toString().trim().isEmpty()||(current!=null&&!current.images.isEmpty()));boolean stop=!draft&&current!=null&&Boolean.TRUE.equals(current.running);boolean enabled=!uploading&&!sending&&pending==null&&(draft||stop);sendButton.setEnabled(enabled);sendButton.setAlpha(enabled?1f:.6f);JSONObject usage=current!=null&&current.page!=null?current.page.contextUsage:null;sendButton.setBackground(new ContextRing(getResources().getDisplayMetrics().density,usage));sendButton.setText(ContextRing.label(usage));sendButton.setTextColor(Color.WHITE);sendButton.setTooltipText(ContextRing.description(usage));sendButton.setContentDescription(stop?"停止执行":draft&&current!=null&&Boolean.TRUE.equals(current.running)?"加入队列":"发送消息");ui.centerIcon(sendButton,stop?"stop":"arrow",Color.WHITE,20,-4);sendButton.setPadding(0,0,0,dp(10));if(chatMeta!=null)chatMeta.setText("");}
    private void showContextUsage(){ChatCache c=chatCaches.get(sessionKey);alert().setTitle("上下文窗口").setMessage(ContextRing.description(c==null||c.page==null?null:c.page.contextUsage)).setPositiveButton("知道了",null).show();}
    private void adaptComposer(){
        if(composer==null)return;composer.setMinLines(3);if(chatContext!=null)chatContext.setVisibility(keyboardShown?View.GONE:View.VISIBLE);if(composeFoot!=null)composeFoot.setVisibility(keyboardShown?View.GONE:View.VISIBLE);if(historyBar!=null)historyBar.setVisibility(hasEarlier&&!keyboardShown?View.VISIBLE:View.GONE);
        if(actionHint!=null){actionHint.setText(pending!=null?"上次操作待核对 · 点击查看":permissionCount+" 项操作待确认 · 点击查看");actionHint.setVisibility(keyboardShown&&(pending!=null||permissionCount>0)?View.VISIBLE:View.GONE);}
        if(permissionScroll!=null)permissionScroll.setVisibility(keyboardShown?View.GONE:View.VISIBLE);if(pendingPanel!=null)pendingPanel.setVisibility(keyboardShown?View.GONE:View.VISIBLE);
    }
    private void jumpToLatest(){if(messageList==null)return;navigationVersion++;historyTicket++;scrolling=false;requestedHeader=null;deferredPage=null;pageBefore=null;historyLoading=false;lastMessages="";followingLatest=true;setHeaderHidden(false);ChatCache current=chatCaches.get(sessionKey);if(current!=null&&current.page!=null)renderSession(current.page,false);positionBoundary(true);pollEpoch=-1;poll(epoch,sessionId);}
    private void positionBoundary(boolean bottom){final ListView list=messageList;final int version=navigationVersion;list.post(()->{if(list!=messageList||version!=navigationVersion||messageAdapter.getCount()==0)return;if(!bottom){list.setSelectionFromTop(0,0);return;}int last=messageAdapter.getCount()-1;View row=messageAdapter.getView(last,null,list);row.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1,list.getWidth()-list.getPaddingLeft()-list.getPaddingRight()),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));list.setSelectionFromTop(last,list.getHeight()-list.getPaddingTop()-list.getPaddingBottom()-row.getMeasuredHeight());});}

    private void syncIdentity(String kind,String id,String title,String cwd){syncIdentity(kind,id,title,cwd,++identityClock);}
    private void syncIdentity(String kind,String id,String title,String cwd,long version){
        if(id.isEmpty())return;String key=kind+":"+id;ChatCache cache=chatCaches.get(key);
        if(version<identityVersions.getOrDefault(key,0L)){if(cache==null)return;title=cache.title;cwd=cache.cwd;}else identityVersions.put(key,version);
        if(cache!=null){if(!title.isEmpty())cache.title=title;if(!cwd.isEmpty())cache.cwd=cwd;}
        ListCache list=listCaches.get(kind);if(list!=null)for(int i=0;i<list.rows.length();i++){JSONObject row=list.rows.optJSONObject(i);if(row!=null&&id.equals(row.optString("id")))try{if(!title.isEmpty())row.put("title",title);if(!cwd.isEmpty())row.put("cwd",cwd);}catch(Exception ignored){}}
        if(key.equals(sessionKey)){if(!title.isEmpty()&&sessionTitle!=null)sessionTitle.setText(title);if(!cwd.isEmpty())chatPath=cwd;}
        if(id.equals(getSharedPreferences("ui",0).getString("lastSession",""))&&kind.equals(getSharedPreferences("ui",0).getString("lastBackend",""))&&!title.isEmpty())getSharedPreferences("ui",0).edit().putString("lastTitle",title).apply();
    }
    private void metadata(LinearLayout body,String label,String value){
        if(value==null||value.isEmpty()||value.equals("null"))return;TextView caption=text(label,12,MUTED);add(body,caption,-2);gap(body,6);TextView detail=text(value,15,INK);detail.setTextIsSelectable(true);add(body,detail,-2);gap(body,18);
    }
    private String appVersion(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception e){return "未知版本";}}
    private void sessionInfo(){
        LinearLayout body=column();ChatCache cache=chatCaches.get(sessionKey);SessionPage page=cache==null?null:cache.page;JSONObject meta=page==null?new JSONObject():page.source.optJSONObject("session");if(meta==null)meta=new JSONObject();
        metadata(body,"会话名称",sessionTitle==null?"未命名会话":sessionTitle.getText().toString());metadata(body,"客户端",providerName(backend));metadata(body,"项目路径",chatPath.isEmpty()?"未指定项目":chatPath);metadata(body,"会话 ID",sessionId);metadata(body,"当前状态",page==null?"正在同步":page.running?"执行中":"空闲");metadata(body,"模型",page==null?"":page.model);metadata(body,"思考强度",page==null?"":page.effort);metadata(body,"审批方式",page==null||page.permissionMode.isEmpty()?"":modeName(page.permissionMode));metadata(body,"最近更新",sessionDate(meta));
        ScrollView scroll=new NativeUi.FormScroll(this);scroll.addView(body);LinearLayout wrapper=column();add(wrapper,scroll,-2);Dialog dialog=sheet("会话信息","会话的基本信息",wrapper);
        LinearLayout actions=row();gap(wrapper,12);add(wrapper,actions,48);Button git=button("源代码管理",false),files=button("工作目录",false);actions.addView(git,new LinearLayout.LayoutParams(0,dp(48),1));View space=new View(this);actions.addView(space,new LinearLayout.LayoutParams(dp(8),1));actions.addView(files,new LinearLayout.LayoutParams(0,dp(48),1));git.setOnClickListener(v->{dialog.dismiss();openGit();});files.setOnClickListener(v->{dialog.dismiss();browseFiles("");});
    }
    private void scheduleHeaderReveal(){handler.removeCallbacks(revealHeader);handler.postDelayed(revealHeader,2200);}
    private void updateNavigation(){if(messageList==null||floating==null)return;int count=messageList.getCount();View first=messageList.getChildAt(0),last=messageList.getChildAt(messageList.getChildCount()-1);boolean top=count==0||messageList.getFirstVisiblePosition()==0&&first!=null&&first.getTop()>=messageList.getPaddingTop()-dp(2)&&!hasEarlier;ChatCache c=chatCaches.get(sessionKey);boolean end=count==0||messageList.getLastVisiblePosition()==count-1&&last!=null&&last.getBottom()<=messageList.getHeight()-messageList.getPaddingBottom()+dp(2)&&pageBefore==null;floating.top.setVisibility(top?View.INVISIBLE:View.VISIBLE);floating.latest.setVisibility(end?View.INVISIBLE:View.VISIBLE);if(end&&headerHidden){handler.removeCallbacks(revealHeader);handler.post(revealHeader);}}
    void messageActions(String source){messageActions(new SessionPage.Row("selection","assistant",source,false));}
    private boolean selectingText;
    void textSelection(boolean active){selectingText=active;if(!active)handler.post(()->{if(!scrolling&&deferredPage!=null){SessionPage page=deferredPage;deferredPage=null;renderSession(page,false);}});}
    void quoteText(String source){if(composer==null||source.isEmpty())return;String draft=composer.getText().toString();composer.setText(draft+(draft.isEmpty()?"":"\n")+"> "+source.replace("\n","\n> ")+"\n");composer.setSelection(composer.length());}
    @Override public void onUserInteraction(){super.onUserInteraction();scheduleHeaderReveal();}
    private void selectMessageText(String source){LinearLayout body=column();TextView chosen=text(source,16,INK);new MessageContent(this,ui).selectable(chosen,source);chosen.setOnLongClickListener(null);ScrollView scroll=new NativeUi.InnerScroll(this);scroll.addView(chosen);add(body,scroll,340);sheet("选择文字","拖动选择手柄，复制或引用所选内容",body);}
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
    private void jumpToTop(){if(messageList==null)return;scrolling=false;deferredPage=null;requestedHeader=null;followingLatest=false;setHeaderHidden(false);navigationVersion++;ChatCache c=chatCaches.get(sessionKey);if(c!=null&&c.page!=null&&c.page.hasEarlier){pageBefore=60;historyLoading=true;fetchPage(epoch,sessionId,60);}else positionBoundary(false);}

    private final class MessageAdapter extends BaseAdapter {
        ArrayList<SessionPage.Row> rows=new ArrayList<>();public int getCount(){return rows.size();}public Object getItem(int p){return rows.get(p);}public long getItemId(int p){return p;}
        public View getView(int position,View recycled,ViewGroup parent){
            LinearLayout outer=recycled instanceof LinearLayout?(LinearLayout)recycled:column();outer.removeAllViews();outer.setPadding(0,dp(12),0,dp(8));
            SessionPage.Row row=rows.get(position);boolean user=row.role.equals("user"),activity=row.activity();ChatCache cache=chatCaches.get(sessionKey);
            LinearLayout content=column();
            outer.setOnLongClickListener(v->{messageActions(row);return true;});
            if(activity&&!row.imageStep()&&!row.kind.equals("executionGroup")&&!row.text.trim().contains("\n")&&row.text.length()<160){new MessageContent(MainActivity.this,ui).render(content,row.text,false);outer.setPadding(0,dp(2),0,dp(2));add(outer,content,-2);return outer;}
            if(activity){outer.setPadding(0,dp(2),0,dp(2));
                boolean expanded=cache!=null&&(cache.expanded.contains(row.id)||row.kind.equals("image")&&Boolean.TRUE.equals(cache.running)&&!cache.collapsed.contains(row.id));Button toggle=button(row.label()+(row.continuation?" · 续":""),false);toggle.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);toggle.setTextColor(MUTED);toggle.setTextSize(14);single(toggle);toggle.setBackground(ui.touch(Color.TRANSPARENT,10,false));ui.leading(toggle,expanded?"down":"right",MUTED,16);toggle.setContentDescription((expanded?"折叠":"展开")+row.label());add(outer,toggle,36);
                toggle.setOnLongClickListener(v->{messageActions(row);return true;});toggle.setOnClickListener(v->{if(cache==null)return;if(expanded){cache.expanded.remove(row.id);cache.collapsed.add(row.id);}else{cache.expanded.add(row.id);cache.collapsed.remove(row.id);}followingLatest=false;latestButton.setVisibility(View.VISIBLE);notifyDataSetChanged();});
                if(expanded){content.setPadding(dp(16),dp(6),dp(6),0);if(row.kind.equals("code"))new MessageContent(MainActivity.this,ui).code(content,row.text,row.language);else new MessageContent(MainActivity.this,ui).render(content,row.text,false);if(row.kind.equals("executionGroup")){ScrollView records=new NativeUi.InnerScroll(MainActivity.this);records.addView(content);add(outer,records,260);}else add(outer,content,-2);}return outer;
            }
            if(user){content.setPadding(dp(16),dp(14),dp(16),dp(6));content.setBackground(ui.surface(NativeUi.SOFT,18,false));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.leftMargin=dp(40);outer.addView(content,lp);}
            else{if(position==0||!rows.get(position-1).role.equals("assistant")){TextView author=text(providerName(backend),12,MUTED);medium(author);ui.leading(author,backend.equals("claude")?"spark":"code",GREEN,16);add(outer,author,28);gap(outer,6);}add(outer,content,-2);}
            if(row.continuation){TextView continued=text("接上段",11,MUTED);add(content,continued,-2);gap(content,6);}if(row.kind.equals("snippet"))new MessageContent(MainActivity.this,ui).code(content,row.text,row.language);else new MessageContent(MainActivity.this,ui).render(content,row.text,user);return outer;
        }
    }
    private void loadEarlier(){
        if(historyLoading||messageAdapter==null)return;ChatCache cache=chatCaches.get(sessionKey);if(cache==null)return;
        final int before=earlierButton.getTag() instanceof Integer?(Integer)earlierButton.getTag():0;if(before<=0)return;
        pageBefore=before;historyLoading=true;if(nextPoll!=null)handler.removeCallbacks(nextPoll);earlierButton.setEnabled(false);earlierButton.setText("正在读取…");latestButton.setVisibility(View.VISIBLE);
        fetchPage(epoch,sessionId,before);
    }
    private void poll(int generation,String id){
        if(id==null||paused||isDestroyed()||generation!=epoch||!id.equals(sessionId)||pollEpoch==generation)return;
        pollEpoch=generation;fetchPage(generation,id,null);
    }
    private void fetchPage(int generation,String id,Integer before){
        final String host=origin,credential=token,path="/api/native/"+backend+"/"+id+(before==null?"":"?before="+before),key=backend+":"+id;
        final long identityVersion=++identityClock;final int ticket=before==null?++pollTicket:++historyTicket;liveWorkers.execute(()->{try{JSONObject raw=request(host,credential,path,null);SessionPage result=SessionPage.prepare(raw);if(before==null)ConversationCache.write(this,host+credential+key,raw);runOnUiThread(()->{
            if(ticket!=(before==null?pollTicket:historyTicket))return;if(before==null&&pollEpoch==generation)pollEpoch=-1;if(isDestroyed()||!host.equals(origin)||!credential.equals(token))return;
            if(before==null){ChatCache cache=chatCaches.get(key);if(cache!=null)cache.page=result;syncIdentity(key.substring(0,key.indexOf(':')),id,result.title,result.cwd,identityVersion);}
            if(generation!=epoch)return;if(before==null&&pageBefore!=null){observeCompletion(result);connection(result.running?"正在思考…":"已同步",result.connected);schedulePoll(generation,id,2500);return;}if(!java.util.Objects.equals(before,pageBefore))return;historyLoading=false;
            renderSession(result,before!=null);schedulePoll(generation,id,result.running?1200:3500);
        });}catch(Exception e){runOnUiThread(()->{if(ticket!=(before==null?pollTicket:historyTicket))return;if(before==null&&pollEpoch==generation)pollEpoch=-1;if(generation!=epoch||isDestroyed()||before!=null&&!java.util.Objects.equals(before,pageBefore))return;historyLoading=false;earlierButton.setEnabled(true);
            if(e instanceof ApiError&&((ApiError)e).code==401){showError(e);return;}
            status.setText(before==null?"连接暂时中断，正在重试…":"历史读取失败，可点击回到最新重试");connection("连接中断",false);earlierButton.setText("重试读取更早消息");latestButton.setVisibility(View.VISIBLE);if(before==null)schedulePoll(generation,id,5000);
        });}});
    }
    private void schedulePoll(int generation,String id,int delay){if(nextPoll!=null)handler.removeCallbacks(nextPoll);if(paused||isDestroyed()||generation!=epoch)return;nextPoll=()->poll(generation,id);handler.postDelayed(nextPoll,delay);}
    private void observeCompletion(SessionPage page){ChatCache state=chatCaches.get(sessionKey);if(state==null)return;boolean finished=!page.running&&(Boolean.TRUE.equals(state.running)||!page.completed.isEmpty()&&!page.completed.equals(state.completed));if(finished){state.expanded.clear();taskExpanded=false;taskRevision="";if(messageAdapter!=null)messageAdapter.notifyDataSetChanged();renderTasks(page);}state.completed=page.completed;state.running=page.running;updateSend();}
    private void renderSession(SessionPage page,boolean history){
        if(messageAdapter==null)return;if((selectingText||scrolling||fastScroller!=null&&fastScroller.isDragging())&&!history){deferredPage=page;return;}
        ChatCache previous=chatCaches.get(sessionKey);if(previous!=null&&Boolean.TRUE.equals(previous.running)&&!page.running)taskExpanded=false;connection(page.connected?(page.running?"正在思考…":"已同步"):"正在重连",page.connected);ChatCache identity=chatCaches.get(sessionKey);chatPath=identity!=null&&!identity.cwd.isEmpty()?identity.cwd:page.cwd;if(accessLabel!=null&&!page.permissionMode.isEmpty())accessLabel.setText("审批 · "+modeName(page.permissionMode));recordCount.setText(page.turns+" 轮对话");if(sessionTitle!=null)sessionTitle.setText(identity!=null&&!identity.title.isEmpty()?identity.title:page.title);modelLabel.setText((page.model.isEmpty()?"模型":page.model)+(page.effort.isEmpty()||page.effort.equals("null")?"":" · "+page.effort));effortLabel.setText(page.effort.isEmpty()||page.effort.equals("null")?"思考":page.effort);renderTasks(page);
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
        Button title=button((queueExpanded?"⌄ ":"› ")+"排队消息 · "+queuedMessages.length(),false);title.setTextSize(12);title.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);title.setBackground(ui.touch(NativeUi.TINT,12,false));title.setMinHeight(0);title.setMinimumHeight(0);add(queuePanel,title,36);title.setOnClickListener(v->{queueExpanded=!queueExpanded;renderQueue();});
        if(!queueExpanded)return;
        LinearLayout items=column();ScrollView scroll=new ScrollView(this);scroll.addView(items);add(queuePanel,scroll,Math.min(220,queuedMessages.length()*100));
        for(int i=0;i<queuedMessages.length();i++){JSONObject item=queuedMessages.optJSONObject(i);if(item==null)continue;JSONObject message=item.optJSONObject("body");String state=item.optString("state");TextView text=text((message==null?"":message.optString("text").isEmpty()?"图片消息":message.optString("text"))+(state.equals("unknown")?" · 结果待核对":state.equals("sending")?" · 提交中":state.equals("rejected")?" · 未发送":""),13,INK);text.setMaxLines(2);text.setEllipsize(TextUtils.TruncateAt.END);text.setPadding(dp(8),dp(7),dp(8),0);add(items,text,-2);
            LinearLayout actions=row();for(String operation:new String[]{"recall","remove","sendnow"}){Button action=button(operation.equals("recall")?"撤回编辑":operation.equals("remove")?"删除":"立即发送",false);action.setTextSize(11);action.setMinHeight(0);action.setMinimumHeight(0);action.setPadding(dp(8),0,dp(8),0);action.setBackground(ui.touch(Color.TRANSPARENT,10,false));action.setEnabled(!sending&&pending==null&&(state.equals("waiting")||state.equals("rejected")||state.equals("unknown")&&operation.equals("remove")));actions.addView(action,new LinearLayout.LayoutParams(0,dp(36),1));action.setContentDescription(action.getText()+" "+item.optString("id"));action.setOnClickListener(v->{if(operation.equals("recall")&&composer.length()>0){toast("请先发送或清空输入框，再撤回编辑");return;}try{JSONObject actionBody=new JSONObject().put("operation",operation).put("queueId",item.optString("id"));if(state.equals("unknown")){alert().setTitle("已在电脑核对这条消息？").setMessage("仅从队列移除，不会撤销电脑上已执行的内容。").setPositiveButton("已核对，移除",(d,w)->{try{actionBody.put("confirmed",true);submit("queue",actionBody);}catch(Exception e){showError(e);}}).setNegativeButton("取消",null).show();}else submit("queue",actionBody);}catch(Exception e){showError(e);}});}add(items,actions,36);
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
                    if(path.endsWith("/settings")||path.endsWith("/access")||path.endsWith("/goal")){pollTicket++;ChatCache current=chatCaches.get(sessionKey);if(current!=null)current.catalog=null;if(path.endsWith("/access")){accessLabel.setText("审批 · "+modeName(result.optString("mode",body.optString("mode"))));}status.setText("设置已送达电脑，正在核对…");}
                    if(path.endsWith("/queue")&&body.optString("operation").equals("recall")){JSONObject recalled=result.optJSONObject("messageBody");if(recalled!=null){composer.setText(recalled.optString("text")+(composer.length()>0?"\n"+composer.getText():""));ChatCache current=chatCaches.get(sessionKey);if(current!=null){JSONArray plugins=recalled.optJSONArray("plugins");current.plugins.clear();if(plugins!=null)for(int i=0;i<plugins.length();i++)current.plugins.add(plugins.optString(i));savePlugins(current);JSONArray attachments=recalled.optJSONArray("attachments");if(attachments!=null)for(int i=0;i<attachments.length();i++){JSONObject image=attachments.optJSONObject(i);if(image!=null)current.images.add(image);}renderAttachments();}}}status.setTextColor(GREEN);status.setText(outcome.equals("queued")?"已加入队列":"已送达电脑");if(sessionId!=null){pollEpoch=-1;poll(epoch,sessionId);}});
            }catch(Exception e){runOnUiThread(()->{sending=false;pendingControls();if(generation!=epoch)return;status.setText("提交结果未确认，请检查电脑会话，再重查原请求。");});}});
        }catch(Exception e){sending=false;showError(e);}
    }

    private LinearLayout settingsGroup(LinearLayout parent,String icon,String title,String value){
        LinearLayout body=column();body.setPadding(dp(36),0,0,dp(8));body.setVisibility(View.GONE);
        LinearLayout line=row();line.setPadding(0,dp(4),0,dp(4));line.addView(ui.icon(icon,INK,23),new LinearLayout.LayoutParams(dp(24),dp(24)));TextView label=text(title,17,INK);label.setPadding(dp(14),0,0,0);line.addView(label,new LinearLayout.LayoutParams(0,dp(52),1));label.setGravity(Gravity.CENTER_VERTICAL);TextView selected=text(value+"  ›",13,MUTED);line.addView(selected);add(parent,line,64);add(parent,body,-2);
        line.setBackground(ui.touch(Color.TRANSPARENT,12,false));line.setOnClickListener(v->{boolean open=body.getVisibility()!=View.VISIBLE;selected.setText(value+(open?"  ⌄":"  ›"));togglePanel(body,open);});return body;
    }
    private String themeName(String key){String[] keys={"light","dark","green","ocean","lavender","sand","midnight"},names={"白色","深色","浅绿","海蓝","紫藤","沙色","午夜"};for(int i=0;i<keys.length;i++)if(keys[i].equals(key))return names[i];return "白色";}
    private void settingsSection(LinearLayout parent,String name){gap(parent,22);TextView label=text(name,13,MUTED);add(parent,label,30);}
    private void settings(){
        bottom.setVisibility(View.GONE);appBar.removeAllViews();appBar.setVisibility(View.VISIBLE);inset(appBar,12,4);Button back=ui.iconButton("back","返回");appBar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));back.setOnClickListener(v->showTab(settingsReturn));TextView title=text("设置",24,INK);medium(title);appBar.addView(title);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);LinearLayout content=column();content.setPadding(dp(24),dp(4),dp(24),dp(28));
        settingsSection(content,"通用");
        LinearLayout appearance=settingsGroup(content,"settings","主题配色",themeName(getSharedPreferences("ui",0).getString("theme","light")));
        GridLayout themes=new GridLayout(this);themes.setColumnCount(7);themes.setContentDescription("主题配色网格");String[] keys={"light","dark","green","ocean","lavender","sand","midnight"},labels={"白色","深色","浅绿","海蓝","紫藤","沙色","午夜"};int[] swatches={0xffeeeeee,0xff222a25,0xffbdd5b3,0xff8fbdd7,0xffbaa2cf,0xffcfb185,0xff3c506d};
        appearance.setPadding(0,0,0,dp(8));
        for(int i=0;i<keys.length;i++){final String key=keys[i];Button color=button("",false);color.setMinHeight(0);color.setMinimumHeight(0);color.setPadding(0,0,0,0);color.setContentDescription(labels[i]+"主题");color.setBackground(ui.touch(swatches[i],10,false));if(getSharedPreferences("ui",0).getString("theme","light").equals(key))ui.centerIcon(color,"check",Color.luminance(swatches[i])>.45?0xff243a2d:Color.WHITE,18);GridLayout.LayoutParams lp=new GridLayout.LayoutParams(GridLayout.spec(0,GridLayout.FILL),GridLayout.spec(i,1f));lp.width=0;lp.height=dp(36);lp.setMargins(dp(3),dp(4),dp(3),dp(4));themes.addView(color,lp);color.setOnClickListener(v->{getSharedPreferences("ui",0).edit().putString("theme",key).apply();recreate();});}add(appearance,themes,-2);
        LinearLayout pages=settingsGroup(content,"folder","显示页面","");for(String key:new String[]{"claude","codex","deepseek"}){Switch toggle=settingSwitch(pages,providerName(key),"show_"+key,true);toggle.setOnCheckedChangeListener((v,on)->{int visible=0;for(String k:new String[]{"claude","codex","deepseek"})if(!k.equals(key)&&getSharedPreferences("ui",0).getBoolean("show_"+k,true))visible++;if(!on&&visible==0){toast("至少保留一个会话页面");toggle.setChecked(true);return;}getSharedPreferences("ui",0).edit().putBoolean("show_"+key,on).apply();});}
        Switch header=settingSwitch(content,"滑动时收起会话顶部","hideHeader",true);header.setOnCheckedChangeListener((v,on)->getSharedPreferences("ui",0).edit().putBoolean("hideHeader",on).apply());
        Switch notifications=settingSwitch(content,"任务完成通知","notifications",false);notifications.setOnCheckedChangeListener((v,on)->{getSharedPreferences("ui",0).edit().putBoolean("notifications",on).apply();if(on){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},22);else startNotifications();}else MonitorConnection.start(this);});Switch overlay=settingSwitch(content,"显示实时任务悬浮窗","overlay",false);overlay.setOnCheckedChangeListener((v,on)->{getSharedPreferences("ui",0).edit().putBoolean("overlay",on).apply();if(on&&!android.provider.Settings.canDrawOverlays(this))startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));MonitorConnection.start(this);});
        settingsSection(content,"连接与设备");
        LinearLayout networkGroup=settingsGroup(content,"code","设备网络","");Switch embedded=settingSwitch(networkGroup,"使用内置设备网络","embeddedNetwork",false);embedded.setOnCheckedChangeListener((v,on)->{getSharedPreferences("ui",0).edit().putBoolean("embeddedNetwork",on).apply();if(!on)workers.execute(()->EmbeddedNetwork.stop());});Button loginNetwork=button("登录 / 查看内置网络",false);add(networkGroup,loginNetwork,48);loginNetwork.setOnClickListener(v->{embedded.setChecked(true);NetworkDialog.show(this,ui);});
        LinearLayout devices=settingsGroup(content,"scan","已保存的连接","");connectionChoices(devices);
        Button scan=button("扫码连接或绑定",false);add(devices,scan,48);scan.setOnClickListener(v->scan());
        Button disconnect=button("移除当前连接",false);disconnect.setTextColor(NativeUi.RED);add(devices,disconnect,48);disconnect.setOnClickListener(v->alert().setTitle("移除当前连接？").setMessage("仅移除此连接凭据，其他临时连接或永久绑定会保留。").setPositiveButton("解除绑定",(d,w)->{final String host=origin,credential=token;workers.execute(()->{try{request(host,credential,"/api/unpair",new JSONObject());}catch(Exception ignored){}});stopService(new Intent(this,TaskNotificationService.class));try{JSONObject next=ConnectionProfiles.remove(this,host,credential);origin=next.optString("origin");token=next.optString("token");clearCaches();if(token.isEmpty())home();else showTab(firstTab());}catch(Exception e){showError(e);}}).setNegativeButton("取消",null).show());
        Button assistant=button("桌面助手  ⌄",false);assistant.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);assistant.setTextSize(17);assistant.setBackground(ui.touch(Color.TRANSPARENT,12,false));add(content,assistant,64);assistant.setOnClickListener(v->toggleApplicationMenu(assistant,"assistant"));
        settingsSection(content,"存储与系统");Button cache=button("清理会话缓存",false);add(content,cache,56);cache.setOnClickListener(v->{clearCaches();toast("会话缓存已清理");});
        Button notificationSettings=button("系统通知设置",false);add(content,notificationSettings,56);notificationSettings.setOnClickListener(v->startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName())));
        gap(content,32);TextView version=text("coding远程 · "+appVersion(),12,MUTED);version.setGravity(Gravity.CENTER);add(content,version,40);scroll.addView(content);add(screen,scroll,-1);
    }
    private void connectionChoices(LinearLayout parent){
        try{JSONObject root=ConnectionProfiles.read(this);JSONArray profiles=ConnectionProfiles.rows(root);for(int i=0;i<profiles.length();i++){final JSONObject route=profiles.getJSONObject(i);String label=route.optString("mode").equals("permanent")?"Tailscale 永久绑定":"扫码临时连接";Button choose=button(label+(route.optString("token").equals(root.optString("token"))?" · 当前":""),false);add(parent,choose,44);choose.setOnClickListener(v->{try{ConnectionProfiles.select(this,route);origin=route.getString("origin");token=route.getString("token");clearCaches();showTab("settings");}catch(Exception e){showError(e);}});}}catch(Exception e){toast("连接配置读取失败");}
    }
    private void toggleApplicationMenu(View anchor,String selected){
        if(applicationMenu!=null){applicationMenu.dismiss();applicationMenu=null;return;}
        LinearLayout content=column();content.setPadding(dp(4),dp(4),dp(4),dp(4));content.setBackground(ui.surface(NativeUi.WHITE,14,true));TextView loading=text("读取中…",12,MUTED);loading.setPadding(dp(12),dp(8),dp(12),dp(8));content.addView(loading);
        applicationMenu=new PopupWindow(content,-2,-2,true);applicationMenu.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));applicationMenu.setOutsideTouchable(true);applicationMenu.setElevation(dp(5));applicationMenu.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);applicationMenu.setEnterTransition(new android.transition.Fade().setDuration(140));applicationMenu.setExitTransition(new android.transition.Fade().setDuration(100));applicationMenu.showAsDropDown(anchor,0,0);loadApplications(content,selected);
    }
    private void loadApplications(LinearLayout target){loadApplications(target,backend);}
    private void loadApplications(LinearLayout target,String selected){
        fetch("/api/apps",null,epoch,value->{target.removeAllViews();JSONArray rows=value.optJSONArray("apps");if(rows==null)return;
            for(int i=0;i<rows.length();i++){JSONObject item=rows.getJSONObject(i);final String key=item.getString("id");if(!key.equals(selected))continue;
                String name=key.equals("assistant")?"桌面助手":providerName(key);TextView state=text(item.optBoolean("running")?"已打开":"已关闭",12,MUTED);state.setPadding(dp(12),dp(6),dp(12),dp(6));target.addView(state,new LinearLayout.LayoutParams(-2,-2));
                for(String action:new String[]{"start","stop","restart"}){Button control=button(action.equals("start")?"启动":action.equals("stop")?"关闭":"重启",false);control.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);control.setContentDescription(name+" · "+control.getText());control.setMinHeight(0);control.setMinimumHeight(0);control.setPadding(dp(14),dp(8),dp(14),dp(8));control.setBackground(ui.touch(Color.TRANSPARENT,10,false));target.addView(control,new LinearLayout.LayoutParams(-1,-2));
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
        try{String key=origin+":"+app+":"+action;String stored=getSharedPreferences("app-requests",0).getString(key,"");JSONObject body=stored.isEmpty()?new JSONObject().put("id",UUID.randomUUID().toString()).put("confirmed",confirmed):new JSONObject(stored);getSharedPreferences("app-requests",0).edit().putString(key,body.toString()).commit();fetch("/api/apps/"+app+"/"+action,body,epoch,result->{String outcome=result.optString("status");if(!outcome.equals("unknown"))getSharedPreferences("app-requests",0).edit().remove(key).apply();toast(result.optString("message",outcome.equals("accepted")?"操作已提交":"请刷新应用状态核对结果"));if(outcome.equals("needs_restart"))alert().setTitle("重启 Claude 以启用自动连接？").setMessage("开发者设置已启用，需要重启一次才能生效。重启会中断当前任务。").setPositiveButton("重启并连接",(d,w)->controlApplication(app,"restart",true,target)).setNegativeButton("取消",null).show();if(applicationMenu!=null){applicationMenu.dismiss();applicationMenu=null;}ListCache changedCache=listCaches.get(app);if(changedCache!=null){changedCache.connected=false;changedCache.loaded=1;if(app.equals(displayedListKey))loadSessions(app,true);}if(connectionLabel!=null){final TextView badge=connectionLabel;appStatusUntil=System.currentTimeMillis()+5000;badge.setText(result.optString("message","操作已提交"));handler.postDelayed(()->{if(badge==connectionLabel){appStatusUntil=0;connection(connectionText,connectionGood);}},5000);}});}catch(Exception e){showError(e);}
    }
    private Switch settingSwitch(LinearLayout parent,String label,String key,boolean defaultValue){Switch v=new Switch(this);v.setText(label);v.setTextSize(17);v.setTextColor(INK);v.setThumbTintList(android.content.res.ColorStateList.valueOf(GREEN));v.setChecked(getSharedPreferences("ui",0).getBoolean(key,defaultValue));add(parent,v,64);return v;}

    private String providerName(String key){return key.equals("claude")?"Claude":key.equals("deepseek")?"DeepSeek":"Codex";}
    private String firstTab(){for(String k:new String[]{"claude","codex","deepseek"})if(getSharedPreferences("ui",0).getBoolean("show_"+k,true))return k;return "claude";}
    private AlertDialog.Builder alert(){return new NativeDialogBuilder(this,ui);}
    private void openSettings(){settingsReturn=backend.equals("settings")?firstTab():backend;showTab("settings");}
    private TextView option(String label,Runnable action){TextView v=text(label,12,INK);v.setPadding(dp(10),0,dp(10),0);v.setGravity(Gravity.CENTER_VERTICAL);v.setMaxWidth(dp(185));single(v);v.setBackground(ui.touch(Color.TRANSPARENT,12,false));v.setContentDescription(label+"选择");v.setFocusable(true);v.setOnClickListener(w->action.run());ui.leading(v,"down",MUTED,12);return v;}
    private void restoreConversation(String key,int generation){final String address=origin,credential=token;workers.execute(()->{JSONObject raw=ConversationCache.read(this,address+credential+key);if(raw==null)return;try{SessionPage page=SessionPage.prepare(raw);runOnUiThread(()->{ChatCache c=chatCaches.get(key);if(c!=null&&c.page==null&&generation==epoch){c.page=page;renderSession(page,false);connection("已缓存 · 正在同步",false);}});}catch(Exception ignored){}});}
    private void toggleCatalog(String kind){if(sending||pending!=null){toast("请先等待当前操作确认");return;}if(kind.equals(expandedOption)){closeOptions();return;}closeOptions();expandedOption=kind;chooseCatalog(kind);}
    private void closeOptions(){catalogTicket++;selectedModel="";expandedOption="";if(optionsDismiss!=null)optionsDismiss.setVisibility(View.GONE);if(inlineOptions!=null){final LinearLayout panel=inlineOptions;final int closing=catalogTicket;panel.animate().cancel();panel.animate().alpha(0).translationY(dp(6)).setDuration(120).withEndAction(()->{if(closing==catalogTicket&&expandedOption.isEmpty()){panel.removeAllViews();panel.setVisibility(View.GONE);panel.setAlpha(1);panel.setTranslationY(0);}}).start();}}
    private void positionOptions(){if(inlineOptions==null||optionAnchor==null)return;final LinearLayout panel=inlineOptions;panel.post(()->{if(panel!=inlineOptions||optionAnchor==null)return;int[] a=new int[2],b=new int[2];optionAnchor.getLocationOnScreen(a);stage.getLocationOnScreen(b);FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);lp.leftMargin=dp(16);lp.rightMargin=dp(16);lp.bottomMargin=Math.max(dp(8),stage.getHeight()-(a[1]-b[1])+dp(4));panel.setLayoutParams(lp);if(optionsDismiss!=null)optionsDismiss.setVisibility(panel.getVisibility());panel.bringToFront();});}
    private void loadingOptions(String label){if(inlineOptions==null)return;inlineOptions.removeAllViews();ui.reveal(inlineOptions,true);add(inlineOptions,text(label,14,MUTED),48);positionOptions();}
    private void chooseCatalog(String kind){
        if(sessionId==null)return;final String key=sessionKey;final int ticket=++catalogTicket;ChatCache c=chatCaches.get(key);
        String section=kind.equals("plugins")?"plugins":"models";if(c!=null&&c.catalog!=null&&c.catalog.has(section.equals("plugins")?"skills":"models")){showCatalog(kind,c.catalog);return;}
        loadingOptions("正在读取电脑提供的选项…");final int generation=epoch;final String host=origin,credential=token,route="/api/native/"+backend+"/"+sessionId+"/catalog?section="+section;workers.execute(()->{try{JSONObject value=request(host,credential,route,null);runOnUiThread(()->{if(generation!=epoch||ticket!=catalogTicket||!key.equals(sessionKey)||!kind.equals(expandedOption))return;if(section.equals("models"))value.remove("skills");else value.remove("models");ChatCache cache=chatCaches.get(key);if(cache!=null)cache.catalog=value;showCatalog(kind,value);});}catch(Exception error){runOnUiThread(()->{if(generation!=epoch||ticket!=catalogTicket||!key.equals(sessionKey))return;loadingOptions(errorMessage(error));Button retry=button("重试读取选项",false);add(inlineOptions,retry,48);retry.setOnClickListener(v->chooseCatalog(kind));});}});
    }
    private void savePlugins(ChatCache cache){getSharedPreferences("composer-plugins",0).edit().putStringSet(origin+"|"+sessionKey,new HashSet<>(cache.plugins)).apply();pluginLabel.setText(cache.plugins.isEmpty()?"插件":"插件 · "+cache.plugins.size());}
    private void showCatalog(String kind,JSONObject catalog){
        ChatCache cache=chatCaches.get(sessionKey);if(cache==null)return;JSONArray entries=catalog.optJSONArray(kind.equals("plugins")?"skills":"models");if(entries==null)entries=new JSONArray();
        LinearLayout body=column(),items=column();ScrollView scroll=new NativeUi.MenuScroll(this,kind.equals("plugins")?272:168);scroll.addView(items);add(body,scroll,-2);
        if(kind.equals("plugins")){
            JSONArray modes=catalog.optJSONArray("modes");if(modes!=null&&modes.length()>0){add(items,text("会话模式",12,MUTED),28);LinearLayout modeRow=row();add(items,modeRow,48);for(int n=0;n<modes.length();n++){JSONObject m=modes.optJSONObject(n);if(m==null)continue;String mode=m.optString("id");JSONObject goal=catalog.optJSONObject("goal");boolean active=mode.equals("goal")?goal!=null&&goal.optString("status").equals("active"):mode.equals(catalog.optString("currentMode"));Button choose=button((active?"✓ ":"")+m.optString("name"),false);choose.setTextSize(12);modeRow.addView(choose,new LinearLayout.LayoutParams(0,dp(48),1));choose.setOnClickListener(v->{if(mode.equals("goal")){closeOptions();goalOptions(catalog);return;}try{closeOptions();submit("settings",new JSONObject().put("collaborationMode",mode));}catch(Exception e){showError(e);}});}}
            for(int n=0;n<entries.length();n++){JSONObject item=entries.optJSONObject(n);if(item==null)continue;JSONArray members=item.optJSONArray("memberIds");boolean wasSelected=false;for(int k=0;members!=null&&k<members.length();k++)wasSelected|=cache.plugins.remove(members.optString(k));if(wasSelected&&item.optBoolean("selectable",true))cache.plugins.add(item.optString("id"));if(!item.optBoolean("selectable",true))cache.plugins.remove(item.optString("id"));}savePlugins(cache);
            String error=catalog.optString("pluginError",catalog.optString("goalError"));if(!error.isEmpty())add(items,text(error,12,NativeUi.RED),-2);
            add(items,text("插件与技能",12,MUTED),28);
        }
        ArrayList<String> ids=new ArrayList<>();ArrayList<String> names=new ArrayList<>();
        if(kind.equals("effort")){String selected=!selectedModel.isEmpty()?selectedModel:catalog.optString("currentModel",cache.page==null?"":cache.page.model);JSONObject model=null;for(int i=0;i<entries.length();i++){JSONObject m=entries.optJSONObject(i);if(m!=null&&m.optString("id").equals(selected))model=m;}if(model!=null){JSONArray efforts=model.optJSONArray("efforts");if(efforts!=null)for(int i=0;i<efforts.length();i++){Object e=efforts.opt(i);String id=e instanceof JSONObject?((JSONObject)e).optString("id",((JSONObject)e).optString("reasoningEffort")):String.valueOf(e);ids.add(id);names.add(id);}}}
        else for(int i=0;i<entries.length();i++){JSONObject entry=entries.optJSONObject(i);if(entry==null)continue;ids.add(entry.optString("id"));names.add(entry.optString("displayName",entry.optString("name",entry.optString("id"))));}
        if(ids.isEmpty())add(items,text(kind.equals("plugins")?"当前项目没有可选插件或技能":"电脑尚未提供可选项，请在桌面检查模型配置",14,MUTED),-2);
        if(inlineOptions==null||!kind.equals(expandedOption))return;inlineOptions.removeAllViews();ui.reveal(inlineOptions,true);inlineOptions.addView(body);scroll.setLayoutParams(new LinearLayout.LayoutParams(-1,-2));
        for(int i=0;i<ids.size();i++){final String id=ids.get(i);if(kind.equals("plugins")){JSONObject item=entries.optJSONObject(i);if(item!=null&&!item.optBoolean("selectable",true)){TextView installed=text(names.get(i)+" · "+item.optString("status","已安装"),13,MUTED);installed.setPadding(dp(8),dp(8),dp(8),dp(8));installed.setTooltipText(item.optString("description"));add(items,installed,-2);continue;}CheckBox choice=new CheckBox(this);choice.setText(names.get(i));choice.setTextColor(INK);choice.setTooltipText(item==null?"":item.optString("description"));choice.setButtonTintList(android.content.res.ColorStateList.valueOf(GREEN));choice.setBackground(ui.touch(cache.plugins.contains(id)?NativeUi.TINT:Color.TRANSPARENT,12,false));choice.setPadding(dp(8),0,dp(8),0);choice.setChecked(cache.plugins.contains(id));add(items,choice,52);choice.setOnCheckedChangeListener((v,on)->{if(sending||pending!=null){choice.setChecked(cache.plugins.contains(id));return;}if(on)cache.plugins.add(id);else cache.plugins.remove(id);choice.setBackground(ui.touch(on?NativeUi.TINT:Color.TRANSPARENT,12,false));savePlugins(cache);});}
            else{Button choose=ui.menu(names.get(i),id.equals(kind.equals("model")?catalog.optString("currentModel",cache.page==null?"":cache.page.model):cache.page==null?"":cache.page.effort));add(items,choose,52);gap(items,4);choose.setOnClickListener(v->{try{if(kind.equals("model")){selectedModel=id;JSONObject chosen=null;for(int j=0;j<catalog.optJSONArray("models").length();j++)if(catalog.optJSONArray("models").getJSONObject(j).optString("id").equals(id))chosen=catalog.optJSONArray("models").getJSONObject(j);if(chosen!=null&&chosen.optJSONArray("efforts")!=null&&chosen.optJSONArray("efforts").length()>0){expandedOption="effort";showCatalog("effort",catalog);return;}}JSONObject bodyValue=new JSONObject().put(kind.equals("model")?"model":"effort",id);if(kind.equals("effort"))bodyValue.put("model",!selectedModel.isEmpty()?selectedModel:catalog.optString("currentModel",cache.page==null?"":cache.page.model));closeOptions();cache.catalog=null;submit("settings",bodyValue);}catch(Exception e){showError(e);}});}}
        Button refresh=button("刷新可用选项",false);compact(refresh);add(body,refresh,48);refresh.setOnClickListener(v->{cache.catalog=null;chooseCatalog(kind);});positionOptions();
    }
    private void goalOptions(JSONObject catalog){
        JSONObject goal=catalog.optJSONObject("goal");if(goal!=null&&!goal.optString("status").equals("complete")){
            boolean active=goal.optString("status").equals("active");String[] options={active?"暂停目标":"继续目标","清除目标"};alert().setTitle("目标模式 · "+goal.optString("status")).setView(text(goal.optString("objective")+"\n已用 "+goal.optLong("tokensUsed")+" tokens",14,INK)).setItems(options,(d,which)->{try{String operation=which==1?"clear":active?"pause":"resume";if(which==1)alert().setTitle("清除当前目标？").setMessage(goal.optString("objective")).setNegativeButton("取消",null).setPositiveButton("清除",(a,b)->{try{submit("goal",new JSONObject().put("operation","clear"));}catch(Exception e){showError(e);}}).show();else submit("goal",new JSONObject().put("operation",operation));}catch(Exception e){showError(e);}}).setNegativeButton("关闭",null).show();return;
        }
        LinearLayout body=column();EditText objective=new EditText(this);objective.setHint("描述需要持续完成的目标");objective.setMinLines(3);objective.setTextColor(INK);add(body,objective,-2);EditText budget=new EditText(this);budget.setHint("Token 预算（可选）");budget.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);add(body,budget,52);alert().setTitle("设置目标模式").setView(body).setNegativeButton("取消",null).setPositiveButton("设置目标",(d,w)->{try{String value=objective.getText().toString().trim();if(value.isEmpty()){toast("请填写目标");return;}JSONObject request=new JSONObject().put("operation","set").put("objective",value);String tokens=budget.getText().toString().trim();if(!tokens.isEmpty())request.put("tokenBudget",Long.parseLong(tokens));submit("goal",request);}catch(Exception e){showError(e);}}).show();
    }
    private void renderTasks(SessionPage page){if(taskPanel==null)return;String revision=String.valueOf(page.steps)+String.valueOf(page.changes)+page.running;if(revision.equals(taskRevision))return;taskRevision=revision;taskPanel.removeAllViews();
        int count=page.steps==null?0:page.steps.length(),added=page.changes==null?0:page.changes.optInt("added"),deleted=page.changes==null?0:page.changes.optInt("deleted"),files=page.changes==null?0:page.changes.optInt("files");if(count==0&&files==0)return;
        LinearLayout detail=column();detail.setVisibility(taskExpanded?View.VISIBLE:View.GONE);Button toggle=button("",false);android.text.SpannableStringBuilder summary=new android.text.SpannableStringBuilder((count>0?count+" 项步骤   ":"")+(files>0?files+" 个文件   ":""));if(files>0){int start=summary.length();summary.append("+"+added);summary.setSpan(new android.text.style.ForegroundColorSpan(0xff22863a),start,summary.length(),33);summary.append("  ");start=summary.length();summary.append("−"+deleted);summary.setSpan(new android.text.style.ForegroundColorSpan(0xffd73a49),start,summary.length(),33);}toggle.setText(summary);compact(toggle);toggle.setTextSize(12);toggle.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);ui.leading(toggle,taskExpanded?"down":"right",GREEN,16);add(taskPanel,toggle,44);toggle.setOnClickListener(v->{taskExpanded=!taskExpanded;taskRevision="";renderTasks(page);});
        for(int i=0;i<count;i++){JSONObject step=page.steps.optJSONObject(i);if(step==null)continue;add(detail,text((step.optString("status").equals("completed")?"✓  ":step.optString("status").equals("in_progress")?"◌  ":"○  ")+step.optString("content",step.optString("step",step.optString("subject"))),13,INK),-2);gap(detail,8);}if(files>0){Button view=button("查看代码改动",false);compact(view);add(detail,view,44);view.setOnClickListener(v->showChanges(page));}ScrollView sc=new NativeUi.InnerScroll(this);sc.addView(detail);sc.setVisibility(taskExpanded?View.VISIBLE:View.GONE);add(taskPanel,sc,Math.min(180,Math.max(48,count*36+48)));}
    private void showChanges(SessionPage page){LinearLayout changes=column();for(SessionPage.Row r:page.rows)if(r.kind.equals("code")){add(changes,text(r.title,13,INK),-2);gap(changes,8);new MessageContent(this,ui).code(changes,r.text,r.language);gap(changes,12);}if(changes.getChildCount()==0)add(changes,text("当前消息页没有改动详情，可在历史消息中展开文件变更。",14,MUTED),-2);ScrollView scroll=new ScrollView(this);scroll.addView(changes);LinearLayout body=column();add(body,scroll,360);sheet("代码改动","当前消息页的变更",body);}
    private void renderPermissions(SessionPage page){StringBuilder stamp=new StringBuilder();for(SessionPage.Permission p:page.permissions)stamp.append(p.id).append(p.input);String revision=stamp.toString()+sending+(pending!=null);if(revision.equals(permissionRevision))return;permissionRevision=revision;permissions.removeAllViews();permissionCount=page.permissions.size();permissionScroll.setLayoutParams(new LinearLayout.LayoutParams(-1,permissionCount==0?0:dp(Math.min(156,permissionCount*108))));
        boolean active=false;for(SessionPage.Permission permission:page.permissions){if(String.valueOf(permission.id).equals(activeQuestion))active=true;LinearLayout card=column();inset(card,12,8);card.setBackground(ui.surface(NativeUi.TINT,14,true));add(card,text(permission.needsInput?"有问题需要你回答":"需要确认 · "+permission.tool,13,INK),-2);gap(card,4);
            if(permission.needsInput){Button answer=button("回答问题",true);add(card,answer,44);answer.setOnClickListener(v->showQuestion(permission));if(activeQuestion.isEmpty()&&!paused){activeQuestion=String.valueOf(permission.id);handler.post(()->{if(sessionId!=null)showQuestion(permission);});active=true;}}
            else{LinearLayout actions=row();for(String decision:new String[]{"accept","decline"}){Button b=button(decision.equals("accept")?"本次允许":"拒绝",decision.equals("accept"));actions.addView(b,new LinearLayout.LayoutParams(0,dp(44),1));b.setEnabled(!sending&&pending==null);b.setOnClickListener(v->{try{submit("respond",new JSONObject().put("requestId",permission.id).put("decision",decision));}catch(Exception e){showError(e);}});}add(card,actions,44);card.setOnLongClickListener(v->{TextView full=text(permission.preview,13,INK);full.setTextIsSelectable(true);LinearLayout body=column();add(body,full,-2);sheet("操作详情",permission.tool,body);return true;});}add(permissions,card,-2);gap(permissions,6);}
        if(!active&&questionDialog!=null){questionDialog.dismiss();questionDialog=null;activeQuestion="";}}
    private void showQuestion(SessionPage.Permission permission){if(gitPage!=null)return;if(questionDialog!=null&&questionDialog.isShowing())return;activeQuestion=String.valueOf(permission.id);JSONArray qs=permission.input.optJSONArray("questions");JSONObject schema=permission.input.optJSONObject("requestedSchema");
        if(qs==null&&schema!=null){qs=new JSONArray();JSONObject properties=schema.optJSONObject("properties");if(properties!=null){java.util.Iterator<String> keys=properties.keys();while(keys.hasNext()){String key=keys.next();JSONObject prop=properties.optJSONObject(key);try{JSONObject q=new JSONObject().put("id",key).put("question",prop.optString("title",key)).put("type",prop.optString("type","string"));JSONArray opts=prop.optJSONArray("enum");if(opts!=null){JSONArray options=new JSONArray();for(int i=0;i<opts.length();i++)options.put(new JSONObject().put("label",opts.getString(i)));q.put("options",options);}qs.put(q);}catch(Exception ignored){}}}}
        if(qs==null||qs.length()==0){toast("当前桌面没有提供可填写的选项，请在电脑查看");return;}
        LinearLayout form=column(),body=column();ScrollView scroll=new NativeUi.FormScroll(this);scroll.addView(form);add(body,scroll,-2);
        final JSONArray questions=qs;final ArrayList<EditText> free=new ArrayList<>();final ArrayList<ArrayList<CheckBox>> choices=new ArrayList<>();
        for(int i=0;i<qs.length();i++){JSONObject q=qs.optJSONObject(i);if(q==null)continue;add(form,text(q.optString("question",q.optString("header","请选择")),16,INK),-2);gap(form,10);ArrayList<CheckBox> boxes=new ArrayList<>();JSONArray opts=q.optJSONArray("options");boolean multi=q.optBoolean("multiSelect");if(opts!=null)for(int j=0;j<opts.length();j++){Object option=opts.opt(j);JSONObject o=option instanceof JSONObject?(JSONObject)option:new JSONObject();String label=option instanceof String?(String)option:o.optString("label",o.optString("value"));CheckBox choice=new CheckBox(this);choice.setText(label+(o.optString("description").isEmpty()?"":"\n"+o.optString("description")));choice.setTag(label);choice.setTextColor(INK);choice.setTextSize(14);choice.setButtonTintList(android.content.res.ColorStateList.valueOf(GREEN));add(form,choice,-2);boxes.add(choice);choice.setOnCheckedChangeListener((v,on)->{if(on&&!multi)for(CheckBox other:boxes)if(other!=v)other.setChecked(false);});}
            EditText input=new EditText(this);input.setTextColor(INK);input.setHintTextColor(MUTED);input.setHint("输入补充或自定义答案");input.setMinLines(1);input.setMaxLines(4);input.setBackground(ui.surface(NativeUi.WHITE,12,true));inset(input,12,10);add(form,input,-2);gap(form,20);choices.add(boxes);free.add(input);}
        gap(body,12);Button send=button("提交回答",true);add(body,send,48);questionDialog=sheet("需要你的意见","选择选项或直接输入",body);final Dialog dialog=questionDialog;
        send.setOnClickListener(v->{try{JSONObject answers=new JSONObject(),content=new JSONObject();for(int i=0;i<questions.length();i++){JSONObject q=questions.getJSONObject(i);String key=q.optString("id",q.optString("question",String.valueOf(i)));JSONArray answer=new JSONArray();for(CheckBox choice:choices.get(i))if(choice.isChecked())answer.put(String.valueOf(choice.getTag()));String typed=free.get(i).getText().toString().trim();if(!typed.isEmpty())answer.put(typed);if(answer.length()==0){toast("请回答每一项问题");return;}answers.put(key,new JSONObject().put("answers",answer));String value=answer.getString(0);String type=q.optString("type");content.put(key,type.equals("boolean")?Boolean.parseBoolean(value):type.equals("number")||type.equals("integer")?Double.valueOf(value):value);}submit("respond",new JSONObject().put("requestId",permission.id).put("decision","accept").put("answers",answers).put("content",content));dialog.dismiss();questionDialog=null;}catch(Exception e){showError(e);}});
    }

    private void newSession(){ListCache c=listCaches.get(backend);newSession(c==null?"":c.project);}
    private void newSession(String preferredProject){
        String saved=getSharedPreferences("creation",0).getString(backend+origin,"");
        if(!saved.isEmpty()){try{JSONObject receipt=new JSONObject(saved);alert().setTitle("有一项创建待核对").setMessage("使用原请求查询结果，避免重复创建。确认电脑上的结果后可清除记录。").setPositiveButton("查询结果",(d,w)->createSession(receipt)).setNeutralButton("已在电脑核对",(d,w)->getSharedPreferences("creation",0).edit().remove(backend+origin).apply()).setNegativeButton("取消",null).show();}catch(Exception e){showError(e);}return;}
        fetch("/api/native/"+backend+"/projects",null,epoch,value->{JSONArray projects=value.optJSONArray("projects");if(projects==null||projects.length()==0){toast("暂无可用项目，请先在电脑创建或打开一个项目");return;}
            LinearLayout body=column();Spinner mode=ui.spinner(new String[]{"Code · 编程","Work · 通用任务"});mode.setContentDescription("会话模式");if(backend.equals("claude"))add(body,mode,48);String[] names=new String[projects.length()];for(int i=0;i<names.length;i++){JSONObject p=projects.getJSONObject(i);names[i]=p.optString("name","项目")+" · "+p.optString("cwd",p.optString("hostLabel"));}Spinner project=ui.spinner(names);for(int pi=0;pi<projects.length();pi++)if(preferredProject.equals(projects.getJSONObject(pi).optString("cwd"))){project.setSelection(pi);break;}project.setContentDescription("新会话项目");add(body,project,52);gap(body,12);EditText name=new EditText(this);ui.input(name);name.setHint("会话名称（可选）");name.setSingleLine();name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});add(body,name,52);gap(body,16);Button create=button("创建会话",true);add(body,create,50);Dialog dialog=sheet("新建会话","选择工作目录，在电脑上创建并同步到手机",body);
            create.setOnClickListener(v->{try{JSONObject request=new JSONObject().put("id",UUID.randomUUID().toString()).put("projectKey",projects.getJSONObject(project.getSelectedItemPosition()).getString("key")).put("title",name.getText().toString()).put("mode",mode.getSelectedItemPosition()==1?"work":"code");if(!getSharedPreferences("creation",0).edit().putString(backend+origin,request.toString()).commit())throw new IOException("无法保存创建请求");dialog.dismiss();createSession(request);}catch(Exception e){showError(e);}});
        });
    }
    private void createSession(JSONObject receipt){toast("正在创建或核对会话…");final String key=backend+origin;fetch("/api/native/"+backend+"/create",receipt,epoch,value->creationResult(receipt,value,key));}
    private void creationResult(JSONObject receipt,JSONObject value,String key)throws Exception{
        String state=value.optString("status");if(state.equals("ready")&&value.optJSONObject("result")!=null){JSONObject result=value.getJSONObject("result");getSharedPreferences("creation",0).edit().remove(key).apply();ListCache c=listCaches.get(backend);if(c!=null)c.loaded=0;openSession(result.getString("id"),result.optString("title","新会话"));return;}
        if(state.equals("failed")||state.equals("unknown")||state.equals("missing")){toast(value.optString("message","创建结果待核对，点击新建可查询原请求"));return;}
        final int generation=epoch;final String path="/api/native/"+backend+"/creation/"+receipt.getString("id");handler.postDelayed(()->{if(generation==epoch&&!paused)fetch(path,null,generation,r->creationResult(receipt,r,key));},2000);
    }
    private String modeName(String mode){switch(mode){case "read-only":return "只读 · 修改时询问";case "workspace-write":return "项目内读写 · 越界时询问";case "full-access":return "完全访问 · 不再询问";case "plan":return "计划模式 · 只读分析";case "acceptEdits":return "允许编辑 · 其他操作询问";case "bypassPermissions":return "完全访问 · 跳过权限确认";case "default":return "标准权限 · 按需询问";default:return "沿用电脑配置";}}
    private void chooseAccess(){if(sessionId==null||inlineOptions==null)return;if(sending||pending!=null){toast("请先等待当前操作确认");return;}if(expandedOption.equals("access")){closeOptions();return;}closeOptions();expandedOption="access";loadingOptions("正在读取审批选项…");fetch("/api/native/"+backend+"/"+sessionId+"/access",null,epoch,value->{if(!expandedOption.equals("access")||inlineOptions==null)return;inlineOptions.removeAllViews();ui.reveal(inlineOptions,true);LinearLayout entries=column();ScrollView scroll=new ScrollView(this);scroll.addView(entries);add(inlineOptions,scroll,168);JSONArray modes=value.getJSONArray("modes");for(int i=0;i<modes.length();i++){final String mode=modes.getString(i);Button choose=ui.menu((mode.equals(value.optString("mode"))?"✓ ":"")+modeName(mode),mode.equals(value.optString("mode")));add(entries,choose,48);choose.setOnClickListener(v->{if(mode.equals("full-access")||mode.equals("bypassPermissions")){entries.removeAllViews();add(entries,text("允许修改电脑文件与运行命令，且不再逐次询问。",13,INK),-2);Button confirm=button("确认完全访问",true);add(entries,confirm,48);confirm.setOnClickListener(w->applyAccess(mode));}else applyAccess(mode);});}positionOptions();});}
    private void applyAccess(String mode){try{closeOptions();submit("access",new JSONObject().put("mode",mode));}catch(Exception e){showError(e);}}

    private void pickImages(){ChatCache cache=chatCaches.get(sessionKey);if(uploading||cache==null)return;if(cache.images.size()>=4){toast("每条消息最多 4 张图片");return;}pickerKey=sessionKey;Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);picker.setType("image/*");picker.addCategory(Intent.CATEGORY_OPENABLE);picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(picker,701);}
    private void uploadPicked(Intent result){final String key=pickerKey,host=origin,credential=token;final ChatCache cache=chatCaches.get(key);if(cache==null)return;ArrayList<Uri> uris=new ArrayList<>();if(result.getClipData()!=null)for(int i=0;i<result.getClipData().getItemCount();i++)uris.add(result.getClipData().getItemAt(i).getUri());else if(result.getData()!=null)uris.add(result.getData());final int available=4-cache.images.size();if(uris.size()>available)toast("最多添加 4 张图片，已选取前 "+available+" 张");uploading=true;updateSend();toast("正在处理图片…");workers.execute(()->{ArrayList<JSONObject> completed=new ArrayList<>();String error="";try{String[] parts=key.split(":",2);for(int i=0;i<Math.min(available,uris.size());i++){JSONObject body=ImageAttachment.prepare(this,uris.get(i));JSONObject staged=request(host,credential,"/api/native/"+parts[0]+"/"+parts[1]+"/upload",body);staged.put("thumbnail",body.optString("thumbnail"));completed.add(staged);}}catch(Exception e){error=errorMessage(e);}final String message=error;runOnUiThread(()->{uploading=false;if(!host.equals(origin)||!credential.equals(token))return;cache.images.addAll(completed);if(key.equals(sessionKey)){renderAttachments();updateSend();}if(!message.isEmpty())toast(message);});});}
    private void renderAttachments(){if(attachmentRow==null)return;attachmentRow.removeAllViews();ChatCache cache=chatCaches.get(sessionKey);if(cache==null)return;for(JSONObject image:cache.images){LinearLayout item=column();ImageView thumbnail=new ImageView(this);thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);try{byte[] data=android.util.Base64.decode(image.optString("thumbnail"),android.util.Base64.DEFAULT);thumbnail.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(data,0,data.length));}catch(Exception ignored){}item.addView(thumbnail,new LinearLayout.LayoutParams(dp(50),dp(42)));TextView remove=text("移除 ×",11,GREEN);remove.setGravity(Gravity.CENTER);item.addView(remove,new LinearLayout.LayoutParams(dp(56),dp(26)));item.setContentDescription("移除附件 "+image.optString("name"));item.setOnClickListener(v->{if(sending||pending!=null)return;cache.images.remove(image);renderAttachments();updateSend();});attachmentRow.addView(item,new LinearLayout.LayoutParams(dp(64),-2));}attachmentRow.setVisibility(cache.images.isEmpty()?View.GONE:View.VISIBLE);}
    private void browseFiles(String path){browseFiles(path,0);}
    private final android.util.LruCache<String,android.graphics.Bitmap> inlineImages=new android.util.LruCache<String,android.graphics.Bitmap>(12*1024*1024){protected int sizeOf(String key,android.graphics.Bitmap image){return image.getByteCount();}};
    void loadInlineImage(ImageView view,String reference){if(sessionId==null)return;final String key=origin+sessionKey+reference;view.setTag(key);android.graphics.Bitmap cached=inlineImages.get(key);if(cached!=null){view.setImageBitmap(cached);return;}final String host=origin,credential=token,path="/api/native/"+backend+"/"+sessionId+"/file?path="+Uri.encode(reference);final int generation=epoch;workers.execute(()->{try{JSONObject file=request(host,credential,path,null);if(!file.optString("mime").startsWith("image/"))return;byte[] bytes=android.util.Base64.decode(file.getString("data"),android.util.Base64.DEFAULT);android.graphics.BitmapFactory.Options opts=new android.graphics.BitmapFactory.Options();opts.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts);opts.inSampleSize=1;while(Math.max(opts.outWidth,opts.outHeight)/opts.inSampleSize>1400)opts.inSampleSize*=2;opts.inJustDecodeBounds=false;android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts);if(bitmap!=null){inlineImages.put(key,bitmap);runOnUiThread(()->{if(generation==epoch&&key.equals(view.getTag()))view.setImageBitmap(bitmap);});}}catch(Exception ignored){runOnUiThread(()->{if(generation==epoch&&key.equals(view.getTag()))view.setContentDescription("图片暂不可用，点击重试预览");});}});}
    void openReference(String reference){if(sessionId==null)return;fetch("/api/native/"+backend+"/"+sessionId+"/file?path="+Uri.encode(reference),null,epoch,file->NativePreview.show(this,file));}
    private void browseFiles(String path,int offset){
        if(sessionId==null)return;final String base="/api/native/"+backend+"/"+sessionId,host=origin,credential=token;final int generation=epoch;
        DirectoryBrowser browser=new DirectoryBrowser(this,ui,chatPath,(folder,start,result)->workers.execute(()->{
            try{JSONObject value=request(host,credential,base+"/files?path="+Uri.encode(folder)+"&offset="+start,null);runOnUiThread(()->{if(generation==epoch&&!isFinishing())result.done(value,null);});}
            catch(Exception e){runOnUiThread(()->{if(generation==epoch&&!isFinishing())result.done(null,errorMessage(e));});}
        }),item->{toast("正在读取文件…");fetch(base+"/file?path="+Uri.encode(item.optString("path")),null,generation,file->NativePreview.show(this,file));});browser.show(path);
    }
    private void showAgents(){
        if(!backend.equals("codex")){ArrayList<String> steps=new ArrayList<>();if(messageAdapter!=null)for(SessionPage.Row r:messageAdapter.rows)if(r.activity())steps.add(r.label()+"\n"+r.text);TextView view=text(steps.isEmpty()?"本页暂无任务步骤。执行中的工具操作会同步显示在会话中。":TextUtils.join("\n\n",steps),14,INK);view.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.addView(view);LinearLayout body=column();add(body,scroll,360);sheet(providerName(backend)+" 任务步骤","当前消息页",body);return;}
        fetch("/api/native/codex/"+sessionId+"/agents",null,epoch,value->{JSONArray agents=value.optJSONArray("agents");LinearLayout body=column();if(agents==null||agents.length()==0)add(body,text("此任务暂无智能体",14,MUTED),-2);else for(int i=0;i<agents.length();i++){JSONObject a=agents.getJSONObject(i);String state=a.optString("status");add(body,text(a.optString("name","智能体")+"  ·  "+(state.equals("running")?"执行中":state.equals("completed")?"已完成":"空闲"),15,INK),48);}ScrollView scroll=new ScrollView(this);scroll.addView(body);LinearLayout wrapper=column();add(wrapper,scroll,Math.min(360,Math.max(64,(agents==null?0:agents.length())*48)));sheet("Codex 智能体","当前任务的子智能体",wrapper);});
    }
    private void startNotifications(){if(token.isEmpty())return;MonitorConnection.start(this);}
    private void handleNotification(Intent intent){if(intent==null||token.isEmpty())return;String id=intent.getStringExtra("sessionId"),kind=intent.getStringExtra("backend");if(id!=null&&( "codex".equals(kind)||"claude".equals(kind)||"deepseek".equals(kind))){backend=kind;openSession(id,intent.getStringExtra("title"));intent.removeExtra("sessionId");}}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleNotification(intent);}
    @Override public void onBackPressed(){if(gitPage!=null){closeGit();return;}if(!expandedOption.isEmpty()){closeOptions();return;}if(sessionId!=null){showTab(backend);return;}if(backend.equals("settings")){showTab(settingsReturn);return;}if(!token.isEmpty()&&!backend.equals(firstTab())){showTab(firstTab());return;}if(System.currentTimeMillis()-lastBack<1500){super.onBackPressed();return;}lastBack=System.currentTimeMillis();toast("再按一次返回退出");}
    @Override public void onPause(){super.onPause();paused=true;pollTicket++;pollEpoch=-1;for(HttpURLConnection active:liveConnections.values())active.disconnect();if(composer!=null){composer.clearFocus();root.requestFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(composer.getWindowToken(),0);}if(nextPoll!=null)handler.removeCallbacks(nextPoll);}
    @Override public void onResume(){super.onResume();boolean was=paused;paused=false;if(was){root.requestFocus();getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);}if(was)MonitorConnection.start(this);if(was&&sessionId!=null){pollEpoch=-1;poll(epoch,sessionId);}else if(was&&displayedListKey!=null)loadSessions(displayedListKey,false);}
    @Override public void onDestroy(){super.onDestroy();handler.removeCallbacksAndMessages(null);workers.shutdownNow();liveWorkers.shutdownNow();}
}
