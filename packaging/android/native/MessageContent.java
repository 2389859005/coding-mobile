package io.github.abyxs.codingremote;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.*;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.regex.*;

/** Bounded native transcript renderer. Source text is never interpreted as HTML. */
final class MessageContent {
    private final Context context;
    private final NativeUi ui;
    MessageContent(Context c,NativeUi u){context=c;ui=u;}

    static String selectedText(TextView text){int a=text.getSelectionStart(),b=text.getSelectionEnd();if(a<0||b<0||a==b)return "";return text.getText().subSequence(Math.min(a,b),Math.max(a,b)).toString();}
    void selectable(TextView text,String source){
        text.setTextIsSelectable(true);
        if(!(context instanceof MainActivity))return;MainActivity activity=(MainActivity)context;
        text.setOnLongClickListener(v->{activity.messageActions(source);return true;});
        text.setCustomSelectionActionModeCallback(new android.view.ActionMode.Callback(){
            public boolean onCreateActionMode(android.view.ActionMode mode,android.view.Menu menu){activity.textSelection(true);menu.add(0,0x20601,10,"引用所选内容");menu.add(0,0x20602,11,"整条消息操作");return true;}
            public boolean onPrepareActionMode(android.view.ActionMode mode,android.view.Menu menu){return false;}
            public boolean onActionItemClicked(android.view.ActionMode mode,android.view.MenuItem item){
                if(item.getItemId()==0x20601){String chosen=selectedText(text);if(!chosen.isEmpty())activity.quoteText(chosen);if(mode!=null)mode.finish();return true;}
                if(item.getItemId()==0x20602){if(mode!=null)mode.finish();activity.messageActions(source);return true;}return false;
            }
            public void onDestroyActionMode(android.view.ActionMode mode){activity.textSelection(false);}
        });
    }

    void render(LinearLayout parent,String source,boolean literal){
        if(literal){paragraph(parent,source,false);return;}
        String[] lines=source.split("\n",-1);StringBuilder prose=new StringBuilder(),code=new StringBuilder();
        boolean inCode=false;String language="";int blocks=0;
        for(int i=0;i<lines.length;i++){
            String line=lines[i];
            if(line.startsWith("```")&&blocks<18){
                if(inCode){code(parent,code.toString(),language);code.setLength(0);blocks++;}
                else{if(prose.length()>0){paragraph(parent,prose.toString(),true);prose.setLength(0);}language=line.substring(3).trim();}
                inCode=!inCode;continue;
            }
            StringBuilder target=inCode?code:prose;if(target.length()>0)target.append('\n');target.append(line);
        }
        if(inCode)code(parent,code.toString(),language);
        if(prose.length()>0)paragraph(parent,prose.toString(),true);
        if(context instanceof MainActivity){java.util.LinkedHashSet<String> images=new java.util.LinkedHashSet<>();Matcher imageLinks=Pattern.compile("!?\\[[^\\]\\n]*\\]\\((?:<([^>]+)>|([^\\n)]+))\\)").matcher(source);while(imageLinks.find()){String path=imageLinks.group(1)!=null?imageLinks.group(1):imageLinks.group(2);if(path.matches("(?i).*\\.(png|jpe?g|webp|gif|bmp)$")&&!path.matches("(?i)^[a-z]+://.*"))images.add(path);}Matcher bare=Pattern.compile("(?im)(?:^|[ `\"])((?:[A-Z]:[/\\\\]|/)[^\\r\\n<>\"`]*?\\.(?:png|jpe?g|webp|gif|bmp))(?=$|[ `\")])").matcher(source);while(bare.find())images.add(bare.group(1));int shown=0;for(String reference:images){if(shown++>=6)break;ImageView image=new ImageView(context);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setBackground(ui.surface(NativeUi.SOFT,12,true));image.setContentDescription("图片预览");LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,ui.dp(200));lp.bottomMargin=ui.dp(10);parent.addView(image,lp);image.setOnClickListener(v->((MainActivity)context).openReference(reference));image.setOnLongClickListener(v->{((MainActivity)context).messageActions(source);return true;});((MainActivity)context).loadInlineImage(image,reference);}}
        if(context instanceof MainActivity){Matcher links=Pattern.compile("!?\\[([^\\]\\n]*)\\]\\((?:<([^>]+)>|([^\\n)]+))\\)").matcher(source);int count=0;while(links.find()&&count++<8){String path=links.group(2)!=null?links.group(2):links.group(3);if(path.matches("(?i)^[a-z]+://.*"))continue;final String reference=path.replaceFirst(":\\d+$","");if(reference.matches("(?i).*\\.(png|jpe?g|webp|gif|bmp)$"))continue;Button open=ui.button("预览 · "+(links.group(1).isEmpty()?reference:links.group(1)),false);open.setSingleLine();open.setEllipsize(android.text.TextUtils.TruncateAt.END);open.setOnClickListener(v->((MainActivity)context).openReference(reference));parent.addView(open,new LinearLayout.LayoutParams(-1,ui.dp(48)));}}
    }

    private void paragraph(LinearLayout parent,String source,boolean markdown){
        TextView text=ui.text("",16,NativeUi.INK);text.setTextIsSelectable(true);text.setLineSpacing(ui.dp(5),1.08f);
        text.setText(markdown?format(source):source);selectable(text,source);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.bottomMargin=ui.dp(10);parent.addView(text,lp);
    }
    static CharSequence format(String source){
        SpannableStringBuilder result=new SpannableStringBuilder();
        for(String raw:source.split("\n",-1)){
            if(result.length()>0)result.append('\n');int start=result.length();
            Matcher heading=Pattern.compile("^(#{1,4})\\s+(.+)$").matcher(raw);
            boolean isHeading=heading.matches();String line=isHeading?heading.group(2):raw.replaceFirst("^[-*] ","• ");
            Matcher inline=Pattern.compile("\\*\\*(.+?)\\*\\*|`([^`]+)`").matcher(line);int offset=0;
            while(inline.find()){
                result.append(line.substring(offset,inline.start()));int mark=result.length();
                result.append(inline.group(1)!=null?inline.group(1):inline.group(2));
                if(inline.group(1)!=null){result.setSpan(new StyleSpan(Typeface.BOLD),mark,result.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);result.setSpan(new ForegroundColorSpan(NativeUi.EMPHASIS),mark,result.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
                else{result.setSpan(new TypefaceSpan("monospace"),mark,result.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);result.setSpan(new ForegroundColorSpan(NativeUi.CODE),mark,result.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
                offset=inline.end();
            }
            result.append(line.substring(offset));
            if(isHeading){result.setSpan(new StyleSpan(Typeface.BOLD),start,result.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);result.setSpan(new RelativeSizeSpan(1.14f),start,result.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
        }return result;
    }
    void code(LinearLayout parent,String source,String language){
        LinearLayout card=new LinearLayout(context);card.setOrientation(1);card.setBackground(ui.surface(NativeUi.WHITE,14,true));card.setClipToOutline(true);
        LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(ui.dp(12),0,0,0);
        String name=language.isEmpty()?"代码":language;
        TextView label=ui.text(name,12,NativeUi.MUTED);label.setSingleLine();label.setEllipsize(android.text.TextUtils.TruncateAt.END);ui.leading(label,"code",NativeUi.GREEN,16);
        header.addView(label,new LinearLayout.LayoutParams(0,ui.dp(44),1));label.setGravity(Gravity.CENTER_VERTICAL);
        Button copy=ui.iconButton("copy","复制代码");header.addView(copy,new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));
        copy.setOnClickListener(v->{((ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("代码",source));Toast.makeText(context,"代码已复制",Toast.LENGTH_SHORT).show();});
        card.addView(header);
        View rule=new View(context);rule.setBackgroundColor(NativeUi.LINE);card.addView(rule,new LinearLayout.LayoutParams(-1,ui.dp(1)));
        TextView code=ui.text("",13,NativeUi.CODE);code.setTypeface(Typeface.MONOSPACE);code.setTextIsSelectable(true);code.setPadding(ui.dp(12),ui.dp(12),ui.dp(12),ui.dp(12));code.setHorizontallyScrolling(true);
        boolean diff=language.equalsIgnoreCase("diff")||language.equalsIgnoreCase("patch");
        SpannableStringBuilder styled=new SpannableStringBuilder(source);
        if(diff){int start=0;for(String line:source.split("\n",-1)){int end=start+line.length();if(end>start&&(line.startsWith("+")||line.startsWith("-"))){styled.setSpan(new BackgroundColorSpan(line.startsWith("+")?NativeUi.DIFF_ADD:NativeUi.DIFF_REMOVE),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);styled.setSpan(new ForegroundColorSpan(line.startsWith("+")?NativeUi.GREEN:NativeUi.RED),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}start=end+1;}}
        if(!diff){Matcher words=Pattern.compile("\\b(class|def|return|if|else|import|from|const|let|var|function|public|private|void|new|true|false|null|async|await)\\b").matcher(source);while(words.find())styled.setSpan(new ForegroundColorSpan(NativeUi.KEYWORD),words.start(),words.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);Matcher strings=Pattern.compile("[\"'][^\"'\\n]*[\"']").matcher(source);while(strings.find())styled.setSpan(new ForegroundColorSpan(NativeUi.STRING),strings.start(),strings.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
        code.setText(styled);selectable(code,source);HorizontalScrollView horizontal=new HorizontalScrollView(context);horizontal.setFillViewport(true);horizontal.addView(code);
        ScrollView vertical=new NativeUi.InnerScroll(context);vertical.setFillViewport(false);vertical.setBackgroundColor(NativeUi.SOFT);vertical.addView(horizontal);
        // Only long code blocks scroll vertically; short snippets keep their natural height.
        int lines=source.split("\n",-1).length;card.addView(vertical,new LinearLayout.LayoutParams(-1,lines>11?ui.dp(252):-2));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,ui.dp(5),0,ui.dp(14));parent.addView(card,lp);
    }
}
