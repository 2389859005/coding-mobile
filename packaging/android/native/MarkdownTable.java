package io.github.abyxs.codingremote;

import android.content.Context;
import android.view.*;
import android.widget.*;
import java.util.*;

/** GFM table parsing and horizontally scrollable native cells. */
final class MarkdownTable {
    static List<String> cells(String source){
        String line=source.trim();ArrayList<String> cells=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean escaped=false;int code=0;
        if(line.startsWith("|"))line=line.substring(1);
        for(int i=0;i<line.length();i++){
            char ch=line.charAt(i);
            if(escaped){if(ch!='|')cell.append('\\');cell.append(ch);escaped=false;continue;}
            if(ch=='\\'){escaped=true;continue;}
            if(ch=='`'){int end=i+1;while(end<line.length()&&line.charAt(end)=='`')end++;int ticks=end-i;if(code==0)code=ticks;else if(code==ticks)code=0;while(i<end)cell.append(line.charAt(i++));i--;continue;}
            if(ch=='|'&&code==0){cells.add(cell.toString().trim());cell.setLength(0);}else cell.append(ch);
        }
        if(escaped)cell.append('\\');
        if(cell.length()>0||!line.endsWith("|"))cells.add(cell.toString().trim());
        return cells;
    }
    static boolean starts(String header,String separator){
        List<String> headings=cells(header),rules=cells(separator);
        if(!header.contains("|")||headings.size()!=rules.size()||headings.isEmpty())return false;
        for(String rule:rules)if(!rule.matches(":?-{3,}:?"))return false;
        return true;
    }
    static View render(Context context,NativeUi ui,List<String> lines,MessageContent content,String source){
        List<String> header=cells(lines.get(0)),rules=cells(lines.get(1));int columns=header.size();
        int[] widths=new int[columns];Arrays.fill(widths,112);
        for(int n=0;n<lines.size();n++){if(n==1)continue;List<String> values=cells(lines.get(n));for(int col=0;col<Math.min(columns,values.size());col++)widths[col]=Math.max(widths[col],Math.min(240,values.get(col).length()*9+24));}
        TableLayout table=new TableLayout(context);table.setTag("markdown-table");table.setBackground(ui.surface(NativeUi.LINE,12,false));table.setPadding(ui.dp(1),ui.dp(1),ui.dp(1),ui.dp(1));table.setClipToOutline(true);
        for(int n=0;n<lines.size();n++){
            if(n==1)continue;TableRow row=new TableRow(context);List<String> values=cells(lines.get(n));
            for(int col=0;col<columns;col++){
                String value=col<values.size()?values.get(col):"";
                TextView cell=ui.text("",NativeUi.BODY,NativeUi.INK);cell.setText(MessageContent.format(value.replaceAll("(?i)<br\\s*/?>","\n")));cell.setPadding(ui.dp(12),ui.dp(10),ui.dp(12),ui.dp(10));cell.setMinHeight(ui.dp(48));cell.setLineSpacing(ui.dp(3),1f);cell.setBackgroundColor(n==0?NativeUi.SOFT:NativeUi.WHITE);
                cell.setGravity(Gravity.TOP|(rules.get(col).startsWith(":")&&rules.get(col).endsWith(":")?Gravity.CENTER_HORIZONTAL:rules.get(col).endsWith(":")?Gravity.RIGHT:Gravity.LEFT));
                if(n==0)NativeUi.strong(cell);content.selectable(cell,source);
                TableRow.LayoutParams lp=new TableRow.LayoutParams(ui.dp(widths[col]),-1);lp.rightMargin=ui.dp(1);lp.bottomMargin=ui.dp(1);row.addView(cell,lp);
            }
            table.addView(row);
        }
        HorizontalScrollView scroll=new HorizontalScrollView(context);scroll.setContentDescription("表格，可左右滑动查看所有列");scroll.setHorizontalScrollBarEnabled(true);scroll.addView(table,new ViewGroup.LayoutParams(-2,-2));
        return scroll;
    }
}
