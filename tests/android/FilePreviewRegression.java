package io.github.abyxs.codingremote.tests;
import android.app.*;import android.content.*;import android.graphics.*;import android.graphics.pdf.*;import android.os.*;import android.view.*;import android.widget.*;import org.json.*;import java.io.*;import java.lang.reflect.*;import java.util.zip.*;

/** Native document viewer, using private synthetic files and no external apps. */
public final class FilePreviewRegression extends Instrumentation {
    Activity activity;Class<?> preview;Dialog dialog;Throwable failure;
    interface Check{void run()throws Exception;}
    void require(boolean value,String label){if(!value)throw new AssertionError(label);}
    void ui(Check action)throws Exception{failure=null;runOnMainSync(()->{try{action.run();}catch(Throwable e){failure=e;}});if(failure!=null)throw new Exception(failure);waitForIdleSync();}
    Object call(String name,Class<?>[] args,Object...values)throws Exception{Method method=preview.getDeclaredMethod(name,args);method.setAccessible(true);return method.invoke(null,values);}
    View find(View root,String text){if(root instanceof TextView&&((TextView)root).getText().toString().contains(text))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=find(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;}return null;}
    void await(Check check)throws Exception{Throwable last=null;for(int i=0;i<70;i++){try{ui(check);return;}catch(Throwable e){last=e;}SystemClock.sleep(100);}throw new Exception(last);}
    void show(JSONObject value)throws Exception{ui(()->{dialog=(Dialog)call("show",new Class[]{Activity.class,JSONObject.class},activity,value);require(dialog.getWindow().getAttributes().gravity==Gravity.CENTER,"file preview centered");require(dialog.getWindow().getAttributes().height<getTargetContext().getResources().getDisplayMetrics().heightPixels,"bounded preview leaves space around the modal");});}
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){Bundle result=new Bundle();try{
        Context context=getTargetContext();context.getSharedPreferences("ui",0).edit().putBoolean("overlay",false).putBoolean("notifications",false).putBoolean("resumeChat",false).commit();
        preview=Class.forName("io.github.abyxs.codingremote.NativePreview");Class<?> secure=Class.forName("io.github.abyxs.codingremote.SecureStore");Method clear=secure.getDeclaredMethod("clear",Context.class);clear.setAccessible(true);clear.invoke(null,context);
        activity=startActivitySync(new Intent().setClassName(context,"io.github.abyxs.codingremote.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        File directory=new File(context.getCacheDir(),"previews/viewer-test");directory.mkdirs();File file=new File(directory,"slides.pdf");PdfDocument document=new PdfDocument();for(int i=0;i<2;i++){PdfDocument.Page page=document.startPage(new PdfDocument.PageInfo.Builder(400,560,i).create());Paint paint=new Paint();paint.setTextSize(26);page.getCanvas().drawText("Page "+(i+1),30,60,paint);document.finishPage(page);}try(FileOutputStream output=new FileOutputStream(file)){document.writeTo(output);}document.close();
        show(new JSONObject().put("localPath",file.getPath()).put("name","slides.pptx").put("mime","application/pdf").put("rendered",true).put("sourceReference","slides.pptx"));
        await(()->require(find(dialog.getWindow().getDecorView(),"1 / 2")!=null,"Office PDF first page rendered from disk"));ui(()->find(dialog.getWindow().getDecorView(),"下一页").performClick());await(()->require(find(dialog.getWindow().getDecorView(),"2 / 2")!=null,"Office PDF pagination works"));
        ui(()->{View root=dialog.getWindow().getDecorView();require(find(root,"分享 / 保存").isEnabled()&&find(root,"其他应用打开").isEnabled(),"original document actions available");});
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){zip.putNextEntry(new ZipEntry("word/document.xml"));zip.write("<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body><w:p><w:r><w:t>Office 文字回退检查</w:t></w:r></w:p></w:body></w:document>".getBytes("UTF-8"));zip.closeEntry();}
        show(new JSONObject().put("name","report.docx").put("mime","application/vnd.openxmlformats-officedocument.wordprocessingml.document").put("data",android.util.Base64.encodeToString(bytes.toByteArray(),2)));
        await(()->require(find(dialog.getWindow().getDecorView(),"Office 文字回退检查")!=null,"bounded Office XML fallback readable"));
        show(new JSONObject().put("name","archive.zip").put("mime","application/zip").put("data",android.util.Base64.encodeToString(new byte[]{1,2,3},2)));
        await(()->require(find(dialog.getWindow().getDecorView(),"此文件可保存")!=null&&find(dialog.getWindow().getDecorView(),"其他应用打开").isEnabled(),"other mobile formats retain external open option"));
        ui(()->{dialog.dismiss();activity.finish();});result.putString("stream","PASS: rendered Office PDF from disk; page navigation; original file actions; bounded DOCX text fallback; other-format handoff.\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}}
}
