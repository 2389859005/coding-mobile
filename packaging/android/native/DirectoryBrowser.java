package io.github.abyxs.codingremote;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** One persistent directory sheet; cached navigation never recreates the window. */
final class DirectoryBrowser {
    interface Result {void done(JSONObject value,String error);}
    interface Loader {void load(String path,int offset,Result result);}
    interface Open {void open(JSONObject entry);}
    private final Activity activity;private final NativeUi ui;private final Loader loader;private final Open open;
    private final Map<String,Page> cache=new LinkedHashMap<>();
    private final LinearLayout body;private final TextView location,status;private final ListView list;private final Button up,more;
    private final ArrayList<JSONObject> rows=new ArrayList<>();private final BaseAdapter adapter;
    private AlertDialog dialog;private String root,path="";private int ticket;private boolean loading;
    private static final class Page {JSONObject value;int first,top;Page(JSONObject v){value=v;}}
    DirectoryBrowser(Activity activity,NativeUi ui,String root,Loader loader,Open open){
        this.activity=activity;this.ui=ui;this.root=root;this.loader=loader;this.open=open;
        body=new LinearLayout(activity);body.setOrientation(1);body.setPadding(ui.dp(16),0,ui.dp(16),ui.dp(12));
        LinearLayout bar=new LinearLayout(activity);up=ui.iconButton("back","上一级目录");bar.addView(up,new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));
        location=ui.text(root,13,NativeUi.INK);location.setMaxLines(2);location.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);bar.addView(location,new LinearLayout.LayoutParams(0,ui.dp(48),1));location.setGravity(Gravity.CENTER_VERTICAL);
        Button refresh=ui.iconButton("refresh","刷新工作目录");bar.addView(refresh,new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));body.addView(bar);
        status=ui.text("长按文件或目录复制路径",12,NativeUi.MUTED);body.addView(status,new LinearLayout.LayoutParams(-1,ui.dp(32)));
        list=new ListView(activity);list.setDivider(null);list.setSelector(ui.touch(NativeUi.TINT,10,false));
        adapter=new BaseAdapter(){public int getCount(){return rows.size();}public Object getItem(int n){return rows.get(n);}public long getItemId(int n){return n;}
            public View getView(int n,View recycled,ViewGroup parent){TextView label=recycled instanceof TextView?(TextView)recycled:ui.text("",14,NativeUi.INK);JSONObject row=rows.get(n);label.setText(row.optString("name"));label.setSingleLine();label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setGravity(Gravity.CENTER_VERTICAL);label.setPadding(ui.dp(10),0,ui.dp(10),0);label.setLayoutParams(new AbsListView.LayoutParams(-1,ui.dp(52)));ui.leading(label,row.optBoolean("directory")?"folder":"file",NativeUi.MUTED,20);return label;}};
        list.setAdapter(adapter);body.addView(list,new LinearLayout.LayoutParams(-1,Math.min(ui.dp(380),activity.getResources().getDisplayMetrics().heightPixels/2)));
        more=ui.button("加载更多",false);body.addView(more,new LinearLayout.LayoutParams(-1,ui.dp(48)));more.setVisibility(View.GONE);
        up.setOnClickListener(v->parent());location.setOnLongClickListener(v->{copy(location.getText().toString());return true;});refresh.setOnClickListener(v->navigate(path,true));
        list.setOnItemClickListener((p,v,n,id)->{JSONObject row=rows.get(n);if(row.optBoolean("directory"))navigate(row.optString("path"),false);else open.open(row);});
        list.setOnItemLongClickListener((p,v,n,id)->{JSONObject row=rows.get(n);copy(row.optString("absolutePath",absolute(row.optString("path"))));return true;});
        more.setOnClickListener(v->{if(!loading)load(path,rows.size());});
    }
    void show(String initial){dialog=new NativeDialogBuilder(activity,ui).setTitle("工作目录").setView(body).setNegativeButton("关闭",null).create();dialog.setOnDismissListener(d->ticket++);dialog.setOnKeyListener((d,key,event)->{if(key==KeyEvent.KEYCODE_BACK&&!path.isEmpty()&&!path.equals(".")){if(event.getAction()==KeyEvent.ACTION_UP)parent();return true;}return false;});dialog.show();ui.window(dialog.getWindow(),true);navigate(initial,false);}
    private void copy(String value){if(value.isEmpty())return;((ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("路径",value));Toast.makeText(activity,"路径已复制",Toast.LENGTH_SHORT).show();}
    private String absolute(String relative){if(relative.startsWith("/")||relative.matches("^[A-Za-z]:.*"))return relative;String separator=root.contains("\\")?"\\":"/";return root.replaceAll("[/\\\\]+$","")+(relative.isEmpty()||relative.equals(".")?"":separator+relative.replace("/",separator));}
    private void saveScroll(){Page previous=cache.get(path);if(previous!=null){previous.first=list.getFirstVisiblePosition();previous.top=list.getChildCount()==0?0:list.getChildAt(0).getTop();}}
    private void parent(){Page current=cache.get(path);String parent=current!=null&&!current.value.isNull("parent")?current.value.optString("parent"):path.contains("/")?path.substring(0,path.lastIndexOf('/')):"";navigate(parent,false);}
    private void navigate(String target,boolean refresh){saveScroll();path=target.equals(".")?"":target;ticket++;loading=false;location.setText(absolute(path));up.setEnabled(!path.isEmpty());Page page=cache.get(path);if(page!=null){render(page);if(!refresh)return;}else{rows.clear();adapter.notifyDataSetChanged();more.setVisibility(View.GONE);}load(path,0);}
    private void load(String requested,int offset){final int request=++ticket;loading=true;status.setText("正在读取目录…");more.setEnabled(false);loader.load(requested,offset,(value,error)->{
        if(dialog==null||!dialog.isShowing()||request!=ticket)return;loading=false;more.setEnabled(true);
        if(error!=null){status.setText(error+" · 点击刷新重试");return;}
        try{if(!value.optString("root").isEmpty())root=value.optString("root");Page previous=cache.get(requested);if(offset>0&&previous!=null){JSONArray all=new JSONArray();JSONArray old=previous.value.optJSONArray("entries"),next=value.optJSONArray("entries");for(int i=0;old!=null&&i<old.length();i++)all.put(old.get(i));for(int i=0;next!=null&&i<next.length();i++)all.put(next.get(i));value.put("entries",all);saveScroll();}
            Page page=new Page(value);if(previous!=null){page.first=previous.first;page.top=previous.top;}cache.put(requested,page);if(cache.size()>40){String oldest=cache.keySet().iterator().next();if(!oldest.equals(requested))cache.remove(oldest);}render(page);
        }catch(Exception e){status.setText("目录读取失败，请刷新重试");}
    });}
    private void render(Page page){rows.clear();JSONArray entries=page.value.optJSONArray("entries");for(int i=0;entries!=null&&i<entries.length();i++){JSONObject row=entries.optJSONObject(i);if(row!=null)rows.add(row);}location.setText(page.value.optString("absolutePath",absolute(path)));up.setEnabled(!page.value.isNull("parent")&&!path.isEmpty());status.setText(rows.isEmpty()?"当前目录为空":"长按文件或目录复制路径");more.setVisibility(page.value.optBoolean("more")?View.VISIBLE:View.GONE);adapter.notifyDataSetChanged();list.setSelectionFromTop(page.first,page.top);list.animate().cancel();list.setAlpha(.7f);list.animate().alpha(1f).setDuration(120).start();}
}
