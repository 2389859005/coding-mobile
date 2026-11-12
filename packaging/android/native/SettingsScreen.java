package io.github.abyxs.codingremote;

import android.content.Context;
import android.graphics.Color;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.*;

/** Settings hub with ordinary, in-page detail navigation. */
final class SettingsScreen extends LinearLayout {
    interface Page {void build(LinearLayout parent);}
    private static final class Entry {
        String group,icon,title,keywords;Supplier<String> value;Page page;Runnable action;Supplier<Boolean> checked;Consumer<Boolean> toggle;
    }
    private final NativeUi ui;
    private final Runnable exit;
    private final TextView title;
    private final ScrollView scroll;
    private final LinearLayout content;
    private final ArrayList<Entry> entries=new ArrayList<>();
    private boolean detail;
    private int hubPosition;
    private String query="";
    SettingsScreen(Context context,NativeUi ui,Runnable exit){
        super(context);this.ui=ui;this.exit=exit;setOrientation(VERTICAL);setBackgroundColor(NativeUi.CANVAS);
        LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(ui.dp(8),0,ui.dp(16),0);
        Button back=ui.iconButton("back","返回");header.addView(back,new LayoutParams(ui.dp(48),ui.dp(52)));back.setOnClickListener(v->{if(!back())exit.run();});
        title=ui.text("设置",NativeUi.HEADING,NativeUi.INK);NativeUi.strong(title);header.addView(title);addView(header,new LayoutParams(-1,ui.dp(56)));
        scroll=new ScrollView(context);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        content=column();content.setPadding(ui.dp(16),ui.dp(8),ui.dp(16),ui.dp(28));scroll.addView(content);addView(scroll,new LayoutParams(-1,0,1));
    }
    void entry(String group,String icon,String title,String keywords,Supplier<String> value,Page page){
        Entry item=new Entry();item.group=group;item.icon=icon;item.title=title;item.keywords=keywords;item.value=value;item.page=page;entries.add(item);
    }
    void actionEntry(String group,String icon,String title,String keywords,Supplier<String> value,Runnable action){
        entry(group,icon,title,keywords,value,null);entries.get(entries.size()-1).action=action;
    }
    void toggleEntry(String group,String title,Supplier<Boolean> checked,Consumer<Boolean> change){
        entry(group,"",title,"",()->checked.get()?"已开启":"已关闭",null);Entry item=entries.get(entries.size()-1);item.checked=checked;item.toggle=change;
    }
    boolean back(){if(!detail)return false;show();return true;}
    android.os.Bundle savePage(){
        android.os.Bundle state=new android.os.Bundle();state.putString("title",detail?title.getText().toString():"");state.putInt("hub",hubPosition);state.putInt("scroll",scroll.getScrollY());return state;
    }
    void restorePage(android.os.Bundle state){
        if(state==null)return;hubPosition=state.getInt("hub");String name=state.getString("title","");
        for(Entry item:entries)if(item.page!=null&&item.title.equals(name)){detail=true;title.setText(item.title);content.removeAllViews();item.page.build(content);break;}
        scroll.post(()->scroll.scrollTo(0,state.getInt("scroll")));
    }
    void show(){
        detail=false;title.setText("设置");content.removeAllViews();
        LinearLayout results=column();content.addView(results,new LayoutParams(-1,-2));render(results);
        scroll.post(()->scroll.scrollTo(0,hubPosition));
    }
    private void render(LinearLayout results){
        results.removeAllViews();String previous="";LinearLayout card=null;int count=0;
        for(Entry item:entries){String value=item.value.get();String searchable=(item.group+item.title+item.keywords+value).toLowerCase(Locale.ROOT);if(!searchable.contains(query.trim().toLowerCase(Locale.ROOT)))continue;
            if(!previous.equals(item.group)){card=group(results,item.group);previous=item.group;}
            if(item.toggle!=null){toggle(card,item.title,item.checked.get(),item.toggle);count++;continue;}
            action(card,item.icon,item.title,value,()->{
                hubPosition=scroll.getScrollY();if(item.action!=null){item.action.run();return;}
                ((android.view.inputmethod.InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(getWindowToken(),0);
                detail=true;title.setText(item.title);content.removeAllViews();item.page.build(content);scroll.scrollTo(0,0);
            });count++;
        }
        if(count==0)note(results,"没有找到相关设置");
        if(query.isEmpty()){TextView version=ui.text("coding远程 · 2.2.5",12,NativeUi.MUTED);version.setGravity(Gravity.CENTER);version.setPadding(0,ui.dp(24),0,ui.dp(6));version.setTag("settings-version");results.addView(version,new LayoutParams(-1,-2));}
    }
    LinearLayout column(){LinearLayout view=new LinearLayout(getContext());view.setOrientation(VERTICAL);return view;}
    void refreshComputer(String value){TextView summary=findViewWithTag("computer-summary");if(summary!=null)summary.setText(value);}
    LinearLayout group(LinearLayout parent,String label){
        TextView caption=ui.text(label,13,NativeUi.ACCENT);LayoutParams heading=new LayoutParams(-1,-2);heading.setMargins(ui.dp(4),ui.dp(24),0,ui.dp(8));parent.addView(caption,heading);
        LinearLayout card=column();card.setBackground(ui.surface(NativeUi.WHITE,14,true));card.setClipToOutline(true);parent.addView(card,new LayoutParams(-1,-2));return card;
    }
    void note(LinearLayout parent,String value){TextView note=ui.text(value,12,NativeUi.MUTED);note.setPadding(ui.dp(4),ui.dp(14),ui.dp(4),ui.dp(6));note.setLineSpacing(ui.dp(4),1);parent.addView(note,new LayoutParams(-1,-2));}
    private String iconFor(String label){
        if(label.contains("通知"))return "bell";
        if(label.contains("解除"))return "unlink";
        if(label.contains("关机")||label.equals("关闭电脑"))return "power";
        if(label.contains("网络"))return "network";
        if(label.contains("背景"))return "image";
        if(label.contains("宠物")||label.contains("悬浮"))return "spark";
        if(label.contains("阅读")||label.contains("顶部"))return "file";
        return "settings";
    }
    private LinearLayout row(LinearLayout parent,String icon,String label){
        if(parent.getChildCount()>0){View line=new View(getContext());line.setBackgroundColor(NativeUi.LINE);LayoutParams rule=new LayoutParams(-1,Math.max(1,ui.dp(.5f)));rule.setMargins(ui.dp(46),0,0,0);parent.addView(line,rule);}
        LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(ui.dp(52));row.setPadding(ui.dp(16),ui.dp(8),ui.dp(16),ui.dp(8));
        if(!icon.isEmpty()){ImageView mark=ui.icon(icon,NativeUi.ACCENT,20);LayoutParams lp=new LayoutParams(ui.dp(24),ui.dp(24));lp.rightMargin=ui.dp(12);row.addView(mark,lp);}
        TextView text=ui.text(label,15,NativeUi.INK);text.setLineSpacing(ui.dp(2),1);row.addView(text,new LayoutParams(0,-2,1));parent.addView(row,new LayoutParams(-1,-2));return row;
    }
    void action(LinearLayout parent,String icon,String label,String value,Runnable click){
        LinearLayout row=row(parent,icon.isEmpty()?iconFor(label):icon,label);
        TextView current=ui.text(value,12,NativeUi.MUTED);current.setTag("setting-value");if(label.equals("连接详情"))current.setTag("computer-summary");current.setMaxWidth(ui.dp(124));current.setMaxLines(2);current.setEllipsize(TextUtils.TruncateAt.END);current.setGravity(Gravity.END);LayoutParams lp=new LayoutParams(-2,-2);lp.leftMargin=ui.dp(12);lp.rightMargin=ui.dp(4);row.addView(current,lp);
        row.addView(ui.icon("right",NativeUi.MUTED,16),new LayoutParams(ui.dp(16),ui.dp(20)));row.setBackground(ui.touch(Color.TRANSPARENT,0,false));row.setFocusable(true);row.setContentDescription(label+(value.isEmpty()?"":"，"+value));row.setOnClickListener(v->click.run());
    }
    TextView value(LinearLayout parent,String icon,String label,String value){
        LinearLayout row=row(parent,icon.isEmpty()?iconFor(label):icon,label);TextView current=ui.text(value,NativeUi.LABEL,NativeUi.MUTED);
        current.setGravity(Gravity.END);current.setMaxLines(3);current.setEllipsize(TextUtils.TruncateAt.MIDDLE);current.setTextIsSelectable(true);
        row.addView(current,new LayoutParams(0,-2,1));return current;
    }
    Switch toggle(LinearLayout parent,String label,boolean checked,Consumer<Boolean> change){
        LinearLayout row=row(parent,iconFor(label),label);Switch control=new Switch(getContext());control.setContentDescription(label);control.setChecked(checked);
        int[][] states={new int[]{android.R.attr.state_checked},new int[]{}};
        control.setThumbTintList(new android.content.res.ColorStateList(states,new int[]{NativeUi.GREEN,NativeUi.MUTED}));control.setTrackTintList(new android.content.res.ColorStateList(states,new int[]{NativeUi.TINT,NativeUi.LINE}));
        row.addView(control,new LayoutParams(ui.dp(48),ui.dp(44)));control.setOnCheckedChangeListener((v,on)->change.accept(on));row.setOnClickListener(v->control.setChecked(!control.isChecked()));return control;
    }
    void choice(LinearLayout parent,String label,String[] options,int selected,IntConsumer select){
        action(parent,"",label,options[selected],()->new NativeDialogBuilder(getContext(),ui).setTitle(label).setSingleChoiceItems(options,selected,(d,index)->{select.accept(index);d.dismiss();refreshCurrentRow(parent,label,options,index,select);}).setNegativeButton("取消",null).show());
    }
    private void refreshCurrentRow(LinearLayout parent,String label,String[] options,int selected,IntConsumer select){
        for(int i=0;i<parent.getChildCount();i++){View child=parent.getChildAt(i);if(!(child instanceof LinearLayout))continue;LinearLayout row=(LinearLayout)child;Object description=row.getContentDescription();if(description!=null&&description.toString().startsWith(label+"，")){((TextView)row.findViewWithTag("setting-value")).setText(options[selected]);row.setContentDescription(label+"，"+options[selected]);row.setOnClickListener(v->new NativeDialogBuilder(getContext(),ui).setTitle(label).setSingleChoiceItems(options,selected,(d,index)->{select.accept(index);d.dismiss();refreshCurrentRow(parent,label,options,index,select);}).setNegativeButton("取消",null).show());}}
    }
}
