package io.github.abyxs.codingremote;

import android.app.Activity;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.UUID;

/** A session-owned terminal. Its command and output survive page navigation. */
final class TerminalPane extends LinearLayout {
    interface Reply {void done(JSONObject value,String error);}
    interface Host {void request(String path,JSONObject body,Reply reply);void back();}
    private boolean disposed;
    TerminalPane(Activity activity,NativeUi ui,String backend,String sid,Host host){
        super(activity);setOrientation(VERTICAL);setPadding(ui.dp(16),ui.dp(8),ui.dp(16),ui.dp(16));
        setBackground(ui.surface(NativeUi.WHITE,NativeUi.CORNER_SHEET,true));
        ui.sheetHeader(this,"终端",host::back);
        TextView output=ui.text("命令在当前会话工作目录运行。",14,NativeUi.INK);output.setTextIsSelectable(true);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);output.setPadding(ui.dp(8),ui.dp(8),ui.dp(8),ui.dp(8));
        ScrollView scroll=new ScrollView(activity);scroll.addView(output);addView(scroll,new LayoutParams(-1,0,1));
        LinearLayout environmentRow=new LinearLayout(activity);
        Spinner environment=ui.spinner(new String[]{"本机","WSL","SSH"});environmentRow.addView(environment,new LayoutParams(ui.dp(110),ui.dp(48)));
        EditText remote=new EditText(activity);ui.input(remote);remote.setHint("SSH user@host");remote.setSingleLine();remote.setVisibility(GONE);environmentRow.addView(remote,new LayoutParams(0,ui.dp(48),1));addView(environmentRow);
        environment.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View view,int index,long id){remote.setVisibility(index==2?VISIBLE:GONE);}});
        LinearLayout entry=new LinearLayout(activity);entry.setGravity(Gravity.CENTER_VERTICAL);
        EditText command=new EditText(activity);ui.input(command);command.setHint("输入命令");command.setMaxLines(3);entry.addView(command,new LayoutParams(0,ui.dp(56),1));
        Button run=ui.button("运行",true);LayoutParams action=new LayoutParams(ui.dp(76),ui.dp(48));action.leftMargin=ui.dp(8);entry.addView(run,action);addView(entry);
        run.setOnClickListener(v->{String text=command.getText().toString().trim();if(text.isEmpty())return;
            try{JSONObject request=new JSONObject().put("id",UUID.randomUUID().toString()).put("command",text).put("environment",new String[]{"local","wsl","ssh"}[environment.getSelectedItemPosition()]).put("host",remote.getText().toString());
                run.setEnabled(false);output.setText("正在执行…");host.request("/api/native/"+backend+"/"+sid+"/terminal",request,(value,error)->{if(disposed)return;run.setEnabled(true);output.setText(error==null?value.optString("output",value.optString("message")):error);});
            }catch(Exception error){output.setText(error.getMessage());}
        });
    }
    void dispose(){disposed=true;}
}
