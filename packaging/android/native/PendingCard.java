package io.github.abyxs.codingremote;

import android.content.Context;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import java.util.List;
import java.util.function.Consumer;

/** One actionable request above the composer, independent of execution disclosure. */
final class PendingCard extends LinearLayout {
    interface Decision {void respond(SessionPage.Permission permission,String decision);}
    private final NativeUi ui;
    private final List<SessionPage.Permission> requests;
    private final boolean enabled;
    private final Consumer<SessionPage.Permission> answer;
    private final Decision decision;
    private int index;
    private boolean compact;
    void compact(boolean value){compact=value;for(int i=1;i<getChildCount();i++)getChildAt(i).setVisibility(value?GONE:VISIBLE);}
    PendingCard(Context context,NativeUi ui,List<SessionPage.Permission> requests,boolean enabled,Consumer<SessionPage.Permission> answer,Decision decision){
        super(context);this.ui=ui;this.requests=requests;this.enabled=enabled;this.answer=answer;this.decision=decision;
        setOrientation(VERTICAL);setPadding(ui.dp(14),ui.dp(12),ui.dp(14),ui.dp(12));android.graphics.drawable.GradientDrawable background=ui.surface(NativeUi.WAIT_BG,14,false);background.setStroke(ui.dp(1),android.graphics.Color.luminance(NativeUi.CANVAS)<.2?0xff5a4524:0xffebd3a6);setBackground(background);render();
        setOnTouchListener(new OnTouchListener(){float x;public boolean onTouch(View view,MotionEvent event){if(event.getActionMasked()==0){x=event.getX();return true;}if(event.getActionMasked()==1){float dx=event.getX()-x;if(Math.abs(dx)>ui.dp(40)){index=(index+(dx<0?1:requests.size()-1))%requests.size();render();}return true;}return true;}});
    }
    private void render(){
        removeAllViews();SessionPage.Permission request=requests.get(index);
        LinearLayout head=new LinearLayout(getContext());head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=ui.text((request.needsInput?"需要你回答":"需要你确认")+(requests.size()>1?" "+(index+1)+"/"+requests.size():""),NativeUi.LABEL,NativeUi.WAIT);
        head.addView(title,new LayoutParams(0,-2,1));
        if(requests.size()>1){Button next=ui.iconButton("right","下一件待处理事项");next.setOnClickListener(v->{index=(index+1)%requests.size();render();});head.addView(next,new LayoutParams(ui.dp(44),ui.dp(44)));}
        TextView more=ui.text("更多选项",12,NativeUi.MUTED);more.setPadding(ui.dp(8),0,0,0);more.setOnClickListener(v->new NativeDialogBuilder(getContext(),ui).setTitle("确认详情").setMessage(request.tool+"\n"+request.preview).setPositiveButton("关闭",null).show());head.addView(more);
        addView(head,new LayoutParams(-1,ui.dp(24)));
        head.setOnClickListener(v->{if(compact)((android.view.inputmethod.InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(getWindowToken(),0);});
        TextView question=ui.text(request.needsInput?"请回答以下问题":"允许运行此命令？",15,NativeUi.INK);NativeUi.strong(question);question.setPadding(0,ui.dp(6),0,ui.dp(6));addView(question,new LayoutParams(-1,-2));
        String command=request.input.optString("command",request.input.optString("commandLine",request.input.optString("prompt","")));
        if(command.isEmpty())command=request.preview;
        TextView preview=ui.text(command,13,NativeUi.INK);preview.setMaxLines(3);preview.setEllipsize(android.text.TextUtils.TruncateAt.END);preview.setTypeface(Typeface.MONOSPACE);preview.setPadding(ui.dp(10),ui.dp(8),ui.dp(10),ui.dp(8));preview.setBackground(ui.surface(NativeUi.WHITE,8,false));addView(preview,new LayoutParams(-1,-2));
        String cwd=request.input.optString("cwd",request.input.optString("workdir",""));TextView where=ui.text(cwd.isEmpty()?"仅对本次请求生效":"工作目录 · "+cwd,12,NativeUi.MUTED);where.setMaxLines(1);where.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);where.setPadding(0,ui.dp(6),0,ui.dp(10));addView(where,new LayoutParams(-1,-2));
        LinearLayout actions=new LinearLayout(getContext());
        if(request.needsInput){Button reply=ui.button("回答问题",true);reply.setEnabled(enabled);reply.setOnClickListener(v->answer.accept(request));actions.addView(reply,new LayoutParams(-1,ui.dp(48)));}
        else for(String value:new String[]{"decline","accept"}){
            boolean accept=value.equals("accept");Button button=ui.button(accept?"允许本次":"拒绝",accept);button.setBackground(ui.touch(accept?NativeUi.GREEN:NativeUi.WHITE,24,!accept));button.setMinHeight(0);button.setMinimumHeight(0);button.setPadding(ui.dp(8),0,ui.dp(8),0);button.setEnabled(enabled);button.setOnClickListener(v->decision.respond(request,value));
            LayoutParams lp=new LayoutParams(0,ui.dp(40),1);if(accept)lp.leftMargin=ui.dp(8);actions.addView(button,lp);
        }
        addView(actions,new LayoutParams(-1,-2));
        compact(compact);
    }
}
