package io.github.abyxs.codingremote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;

/** Generated documents are isolated content, never application navigation or code. */
final class NativePreview {
    private static Dialog activeDialog;
    private static TextView activeStatus;
    static boolean current(Dialog dialog){return dialog!=null&&activeDialog==dialog&&dialog.isShowing();}
    static void status(Dialog dialog,String message){if(current(dialog)&&activeStatus!=null)activeStatus.setText(message);}
    static void close(Activity activity){if(activeDialog!=null&&activeDialog.getOwnerActivity()==activity)activeDialog.dismiss();}
    static void failed(Dialog dialog,String message,Runnable retry){
        if(!current(dialog))return;
        Activity activity=dialog.getOwnerActivity();NativeUi ui=new NativeUi(activity);
        LinearLayout body=new LinearLayout(activity);body.setOrientation(1);body.setGravity(Gravity.CENTER);body.setPadding(ui.dp(24),ui.dp(24),ui.dp(24),ui.dp(24));body.setBackgroundColor(NativeUi.CANVAS);
        TextView error=ui.text("文件未能打开\n"+message,NativeUi.BODY,NativeUi.INK);error.setGravity(Gravity.CENTER);body.addView(error);
        Button again=ui.button("重试",true),back=ui.button("返回",false);body.addView(again);body.addView(back);again.setOnClickListener(v->retry.run());back.setOnClickListener(v->dialog.dismiss());dialog.setContentView(body);activeStatus=null;
    }
    static Dialog showLoading(Activity activity,String name){
        if(activeDialog!=null){try{activeDialog.dismiss();}catch(Exception ignored){}activeDialog=null;}
        NativeUi ui=new NativeUi(activity);boolean dark=android.graphics.Color.luminance(NativeUi.CANVAS)<.2f;
        Dialog dialog=new Dialog(activity,dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);dialog.setOwnerActivity(activity);
        LinearLayout root=new LinearLayout(activity);root.setOrientation(1);root.setGravity(Gravity.CENTER);root.setBackgroundColor(NativeUi.CANVAS);root.setPadding(ui.dp(24),ui.dp(24),ui.dp(24),ui.dp(24));
        ProgressBar progress=new ProgressBar(activity);root.addView(progress,new LinearLayout.LayoutParams(ui.dp(42),ui.dp(42)));
        TextView title=ui.text("正在打开",NativeUi.TITLE,NativeUi.INK);title.setGravity(Gravity.CENTER);title.setPadding(0,ui.dp(14),0,ui.dp(4));root.addView(title,-2);
        TextView detail=ui.text(name==null?"文件":name,NativeUi.CAPTION,NativeUi.MUTED);detail.setGravity(Gravity.CENTER);detail.setMaxLines(2);root.addView(detail,-2);
        dialog.setContentView(root);dialog.show();if(dialog.getWindow()!=null){center(dialog,ui);dialog.getWindow().setStatusBarColor(NativeUi.CANVAS);}activeDialog=dialog;
        activeStatus=title;
        dialog.setOnDismissListener(d->{if(activeDialog==dialog){activeDialog=null;activeStatus=null;}});
        return dialog;
    }
    /** The current canvas as a CSS colour, so previewed media matches the theme. */
    private static void center(Dialog dialog,NativeUi ui){
        Window window=dialog.getWindow();if(window==null)return;ui.window(window,false);
        android.util.DisplayMetrics metrics=dialog.getContext().getResources().getDisplayMetrics();
        window.setLayout(Math.min(ui.dp(560),metrics.widthPixels-ui.dp(24)),Math.min(ui.dp(720),(int)(metrics.heightPixels*.84f)));
        View body=window.findViewById(android.R.id.content);if(body!=null){body.setBackground(ui.surface(NativeUi.CANVAS,NativeUi.CORNER_CARD,true));body.setClipToOutline(true);}
    }
    private static String canvasHex(){return String.format("#%06x",NativeUi.CANVAS & 0x00ffffff);}
    static Dialog show(Activity activity,JSONObject value){
        if(activeDialog!=null){try{activeDialog.dismiss();}catch(Exception ignored){}activeDialog=null;}
        NativeUi ui=new NativeUi(activity);
        // The dialog theme follows the app theme; a fixed Light theme gave dark and
        // midnight users a white chrome and status bar above a dark preview.
        boolean dark=android.graphics.Color.luminance(NativeUi.CANVAS)<.2f;
        Dialog dialog=new Dialog(activity,dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);LinearLayout root=new LinearLayout(activity);root.setOrientation(1);root.setBackgroundColor(NativeUi.CANVAS);root.setPadding(ui.dp(16),ui.dp(20),ui.dp(16),ui.dp(12));
        LinearLayout bar=new LinearLayout(activity);bar.setGravity(Gravity.CENTER_VERTICAL);TextView title=ui.text(value.optString("name","文件"),NativeUi.TITLE,NativeUi.INK);title.setMaxLines(2);bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));Button close=ui.iconButton("close","关闭预览");bar.addView(close,new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));close.setOnClickListener(v->dialog.dismiss());root.addView(bar);FrameLayout content=new FrameLayout(activity);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(activity);actions.setGravity(Gravity.CENTER_VERTICAL);Button share=ui.button("分享 / 保存",false),external=ui.button("其他应用打开",false);share.setEnabled(false);external.setEnabled(false);actions.addView(share,new LinearLayout.LayoutParams(0,ui.dp(48),1));LinearLayout.LayoutParams otherLp=new LinearLayout.LayoutParams(0,ui.dp(48),1);otherLp.leftMargin=ui.dp(8);actions.addView(external,otherLp);root.addView(actions,new LinearLayout.LayoutParams(-1,ui.dp(48)));dialog.setContentView(root);dialog.getWindow().setWindowAnimations(ui.style("DialogMotion",0));dialog.show();center(dialog,ui);dialog.getWindow().setStatusBarColor(NativeUi.CANVAS);activeDialog=dialog;
        if(android.os.Build.VERSION.SDK_INT>=30){root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(ui.dp(16)+bars.left,Math.max(ui.dp(20),bars.top),ui.dp(16)+bars.right,Math.max(ui.dp(12),bars.bottom));return insets;});root.requestApplyInsets();}
        dialog.setOwnerActivity(activity);activeStatus=ui.text("文件预览",NativeUi.CAPTION,NativeUi.MUTED);root.addView(activeStatus,1);
        ExecutorService worker=Executors.newSingleThreadExecutor();final WebView[] web={null};final PdfRenderer[] pdf={null};final Bitmap[] bitmap={null};final boolean[] closed={false};final VideoView[] media={null};
        // Decode and write off the UI thread; the provider exposes only this app's preview cache.
        worker.execute(()->{try{boolean hasData=value.has("data")||value.has("localPath");byte[] bytes=value.has("data")?Base64.decode(value.getString("data"),Base64.DEFAULT):new byte[0];if(bytes.length>24*1024*1024)throw new IOException("文件超过预览大小限制");File dir=new File(activity.getCacheDir(),"previews");dir.mkdirs();if(value.has("localPath")){File current=new File(value.optString("localPath")).getParentFile();if(current!=null)current.setLastModified(System.currentTimeMillis());}File[] old=dir.listFiles();if(old!=null)for(File f:old)if(System.currentTimeMillis()-f.lastModified()>24*3600000L){File[] children=f.listFiles();if(children!=null)for(File child:children)if(child.isFile())child.delete();f.delete();}String name=(value.optBoolean("rendered")?value.optString("name","文档").replaceFirst("\\.[^.]+$","")+".pdf":value.optString("name","文件")).replaceAll("[\\\\/:*?\"<>|]","_");File folder=value.has("localPath")?new File(value.getString("localPath")).getParentFile():new File(dir,java.util.UUID.randomUUID().toString());folder.mkdir();File file=new File(folder,name);if(!file.getCanonicalPath().startsWith(dir.getCanonicalPath()+File.separator))throw new IOException("文件缓存路径无效");if(hasData&&!value.has("localPath"))try(OutputStream out=new FileOutputStream(file)){out.write(bytes);}String mime=value.optString("mime","application/octet-stream");if(value.has("localPath")&&mime.startsWith("text/")&&!value.has("text")){try(Reader reader=new InputStreamReader(new FileInputStream(file),"UTF-8")){char[] characters=new char[300000];int count=reader.read(characters);value.put("text",count<0?"":new String(characters,0,count));value.put("truncated",file.length()>300000);}}final String extractedOffice=hasData&&isOffice(name,mime)?officeText(file,name):"";
                activity.runOnUiThread(()->{if(closed[0]||activity.isFinishing())return;
                    // A large text preview arrives without its bytes, because sending
                    // them would double the payload the phone has to receive.
                    final String openMime=mimeFor(name,mime);final boolean supported=isSupported(name,openMime);
                    if(!hasData){share.setEnabled(false);external.setEnabled(false);share.setText("内容过大，无法在手机缓存");}
                    else if(blocked(name)){content.addView(ui.text("此格式不能在手机端运行",NativeUi.BODY,NativeUi.MUTED));return;}
                    else{share.setEnabled(true);external.setEnabled(true);final Uri uri=Uri.parse("content://"+activity.getPackageName()+".previews/"+folder.getName()+"/"+Uri.encode(name));share.setOnClickListener(v->{if(value.optBoolean("rendered")&&activity instanceof MainActivity){((MainActivity)activity).openOriginalReference(value.optString("sourceReference"),true);return;}Intent intent=new Intent(Intent.ACTION_SEND).setType(openMime).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newUri(activity.getContentResolver(),name,uri));activity.startActivity(Intent.createChooser(intent,"分享或保存文件"));});external.setOnClickListener(v->{if(value.optBoolean("rendered")&&activity instanceof MainActivity){((MainActivity)activity).openOriginalReference(value.optString("sourceReference"),false);return;}Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,openMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{activity.startActivity(Intent.createChooser(intent,"选择应用打开"));}catch(Exception e){Toast.makeText(activity,"没有可打开此格式的应用",Toast.LENGTH_LONG).show();}});}
                    if(mime.startsWith("image/")){
                        ZoomImage image=new ZoomImage(activity);content.addView(image,new FrameLayout.LayoutParams(-1,-1));worker.execute(()->{android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(file.getPath(),options);options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>2400)options.inSampleSize*=2;options.inJustDecodeBounds=false;Bitmap decoded=android.graphics.BitmapFactory.decodeFile(file.getPath(),options);activity.runOnUiThread(()->{if(closed[0]){if(decoded!=null)decoded.recycle();return;}image.setImageBitmap(decoded);});});
                    }else if(mime.startsWith("audio/")||mime.startsWith("video/")){
                        VideoView video=new VideoView(activity);media[0]=video;content.addView(video,new FrameLayout.LayoutParams(-1,-1));MediaController controls=new MediaController(activity);controls.setAnchorView(video);video.setMediaController(controls);video.setVideoURI(Uri.parse("content://"+activity.getPackageName()+".previews/"+folder.getName()+"/"+Uri.encode(name)));video.setOnPreparedListener(player->controls.show(0));video.setOnErrorListener((player,what,extra)->{Toast.makeText(activity,"此编码无法播放，可选择其他应用打开",Toast.LENGTH_LONG).show();return true;});
                    }else if(mime.startsWith("image/")||mime.equals("text/html")){
                        WebView browser=new WebView(activity);web[0]=browser;WebSettings settings=browser.getSettings();settings.setJavaScriptEnabled(mime.equals("text/html"));settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setBlockNetworkLoads(true);settings.setBuiltInZoomControls(true);settings.setDisplayZoomControls(false);settings.setUseWideViewPort(true);settings.setLoadWithOverviewMode(true);browser.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request){return true;}});content.addView(browser,new FrameLayout.LayoutParams(-1,-1));
                        // The image backdrop used a hardcoded #f6f7f2 - the light
                        // canvas - so a picture opened on a dark theme sat in a
                        // bright box unrelated to the rest of the screen.
                        String html=mime.startsWith("image/")?"<meta name='viewport' content='width=device-width, initial-scale=1'><body style='margin:0;background:"+canvasHex()+"'><img style='width:100%;height:auto' src='data:"+mime+";base64,"+value.optString("data")+"'></body>":value.optString("text","");if(!html.toLowerCase(java.util.Locale.ROOT).contains("viewport"))html="<meta name='viewport' content='width=device-width, initial-scale=1'>"+html;browser.loadDataWithBaseURL("https://preview.invalid/",html,"text/html","UTF-8",null);
                    }else if(mime.equals("application/pdf")){
                        LinearLayout page=new LinearLayout(activity);page.setOrientation(1);ZoomImage image=new ZoomImage(activity);page.addView(image,new LinearLayout.LayoutParams(-1,0,1));LinearLayout controls=new LinearLayout(activity);Button previous=ui.button("上一页",false),next=ui.button("下一页",false);TextView number=ui.text("",NativeUi.LABEL,NativeUi.MUTED);number.setGravity(Gravity.CENTER);controls.addView(previous,new LinearLayout.LayoutParams(0,ui.dp(48),1));controls.addView(number,new LinearLayout.LayoutParams(0,ui.dp(48),1));controls.addView(next,new LinearLayout.LayoutParams(0,ui.dp(48),1));page.addView(controls);content.addView(page);final int[] index={0};final Runnable[] render={null};render[0]=()->{previous.setEnabled(false);next.setEnabled(false);worker.execute(()->{try{if(pdf[0]==null)pdf[0]=new PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY));int count=pdf[0].getPageCount();try(PdfRenderer.Page p=pdf[0].openPage(index[0])){float scale=Math.min(2,1600f/Math.max(p.getWidth(),p.getHeight()));Bitmap b=Bitmap.createBitmap(Math.max(1,(int)(p.getWidth()*scale)),Math.max(1,(int)(p.getHeight()*scale)),Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);p.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);activity.runOnUiThread(()->{if(closed[0]){b.recycle();return;}image.setImageBitmap(b);image.setContentDescription("PDF 第 "+(index[0]+1)+" 页，共 "+count+" 页");if(bitmap[0]!=null)bitmap[0].recycle();bitmap[0]=b;number.setText((index[0]+1)+" / "+count);number.setContentDescription("第 "+(index[0]+1)+" 页，共 "+count+" 页");previous.setEnabled(index[0]>0);next.setEnabled(index[0]+1<count);});}}catch(Exception e){activity.runOnUiThread(()->Toast.makeText(activity,"PDF 无法预览，可用其他应用打开",Toast.LENGTH_LONG).show());}});};previous.setOnClickListener(v->{index[0]--;render[0].run();});next.setOnClickListener(v->{index[0]++;render[0].run();});render[0].run();
                    }else if(isOffice(name, mime)){ScrollView scroll=new ScrollView(activity);LinearLayout body=new LinearLayout(activity);body.setOrientation(1);body.setPadding(ui.dp(8),ui.dp(12),ui.dp(8),ui.dp(12));body.addView(ui.text("文档文字预览",NativeUi.CAPTION,NativeUi.MUTED));body.addView(ui.text(extractedOffice.isEmpty()?"未提取到可显示文字":extractedOffice,NativeUi.BODY,NativeUi.INK));scroll.addView(body);content.addView(scroll);
                    }else if(value.has("text")){ScrollView scroll=new ScrollView(activity);LinearLayout body=new LinearLayout(activity);body.setOrientation(1);if(value.optBoolean("truncated"))body.addView(ui.text("预览已截断，完整内容请保存文件",NativeUi.CAPTION,NativeUi.MUTED));if(name.toLowerCase(java.util.Locale.ROOT).endsWith(".md")||mime.equals("text/markdown"))new MessageContent(activity,ui).render(body,value.optString("text"),false);else new MessageContent(activity,ui).code(body,value.optString("text"),name);scroll.addView(body);content.addView(scroll);}else content.addView(ui.text("此文件可保存，或使用已安装的应用打开。",NativeUi.BODY,NativeUi.MUTED));
                });
            }catch(Exception e){activity.runOnUiThread(()->{if(!closed[0])content.addView(ui.text("预览失败："+e.getMessage(),NativeUi.LABEL,NativeUi.RED));});}});
        dialog.setOnDismissListener(d->{closed[0]=true;if(activeDialog==dialog){activeDialog=null;activeStatus=null;}if(web[0]!=null)web[0].destroy();if(media[0]!=null)media[0].stopPlayback();worker.execute(()->{if(pdf[0]!=null)pdf[0].close();});worker.shutdown();if(bitmap[0]!=null){bitmap[0].recycle();bitmap[0]=null;}});
        return dialog;
    }

    static void openWith(Activity activity,JSONObject value,boolean share){
        new Thread(()->{try{
            String name=value.optString("name","文件").replaceAll("[\\\\/:*?\"<>|]","_");
            if(blocked(name))throw new IOException("此格式不能在手机端打开");
            File root=new File(activity.getCacheDir(),"previews");File folder=value.has("localPath")?new File(value.getString("localPath")).getParentFile():new File(root,java.util.UUID.randomUUID().toString());folder.mkdirs();
            File file=new File(folder,name);if(!file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("缓存路径无效");if(!value.has("localPath")){byte[] data=Base64.decode(value.getString("data"),Base64.DEFAULT);try(OutputStream out=new FileOutputStream(file)){out.write(data);}}
            Uri uri=Uri.parse("content://"+activity.getPackageName()+".previews/"+folder.getName()+"/"+Uri.encode(name));
            String mime=mimeFor(name,value.optString("mime","application/octet-stream"));
            activity.runOnUiThread(()->{if(activity.isFinishing()||activity.isDestroyed())return;try{
                Intent intent=share?new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri):new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newUri(activity.getContentResolver(),name,uri));activity.startActivity(Intent.createChooser(intent,share?"分享或保存原文件":"选择应用打开原文件"));
            }catch(Exception e){Toast.makeText(activity,"没有可打开此文件的应用",Toast.LENGTH_LONG).show();}});
        }catch(Exception e){activity.runOnUiThread(()->Toast.makeText(activity,e.getMessage(),Toast.LENGTH_LONG).show());}},"preview-share").start();
    }
    private static String mimeFor(String name,String mime){
        if(mime!=null&&!mime.equals("application/octet-stream"))return mime;
        String lower=name==null?"":name.toLowerCase(java.util.Locale.ROOT);
        if(lower.endsWith(".docx"))return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if(lower.endsWith(".pptx"))return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if(lower.endsWith(".xlsx"))return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if(lower.endsWith(".doc"))return "application/msword";if(lower.endsWith(".ppt"))return "application/vnd.ms-powerpoint";if(lower.endsWith(".xls"))return "application/vnd.ms-excel";
        if(lower.endsWith(".txt"))return "text/plain";if(lower.endsWith(".md"))return "text/markdown";return mime==null?"application/octet-stream":mime;
    }
    private static boolean isOffice(String name,String mime){String lower=name==null?"":name.toLowerCase(java.util.Locale.ROOT);return lower.endsWith(".docx")||lower.endsWith(".pptx")||lower.endsWith(".xlsx")||mime.contains("openxmlformats");}
    private static boolean isSupported(String name,String mime){
        if(mime.startsWith("image/")||mime.equals("application/pdf")||mime.startsWith("text/")||mime.equals("text/html")||isOffice(name,mime))return true;
        return false;
    }
    private static boolean blocked(String name){return name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(exe|msi|dll|bat|cmd|ps1|com|scr)$");}
    /** Extract readable text from modern Office packages for a fast offline preview. */
    private static String officeText(File file,String name){
        String lower=name.toLowerCase(java.util.Locale.ROOT);StringBuilder all=new StringBuilder();
        try(java.util.zip.ZipFile zip=new java.util.zip.ZipFile(file)){
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries=zip.entries();int selected=0,total=0;
            while(entries.hasMoreElements()&&selected<64&&total<8*1024*1024&&all.length()<300000){
                java.util.zip.ZipEntry entry=entries.nextElement();String path=entry.getName().toLowerCase(java.util.Locale.ROOT);
                boolean wanted=lower.endsWith(".docx")?path.equals("word/document.xml"):lower.endsWith(".pptx")?path.matches("ppt/slides/slide[0-9]+\\.xml"):path.equals("xl/sharedstrings.xml")||path.matches("xl/worksheets/sheet[0-9]+\\.xml");if(!wanted)continue;selected++;
                ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
                try(InputStream input=zip.getInputStream(entry)){while(out.size()<1024*1024&&(n=input.read(buffer,0,Math.min(buffer.length,1024*1024-out.size())))!=-1)out.write(buffer,0,n);}total+=out.size();
                org.xmlpull.v1.XmlPullParser parser=android.util.Xml.newPullParser();parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);parser.setInput(new ByteArrayInputStream(out.toByteArray()),"UTF-8");
                try{for(int event=parser.next();event!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT&&all.length()<300000;event=parser.next()){
                    if(event==org.xmlpull.v1.XmlPullParser.TEXT){String text=parser.getText();int left=300000-all.length();all.append(text,0,Math.min(left,text.length()));}
                    else if(event==org.xmlpull.v1.XmlPullParser.END_TAG&&java.util.Arrays.asList("p","br","row","si").contains(parser.getName()))all.append('\n');
                }}catch(org.xmlpull.v1.XmlPullParserException truncated){/* Keep the readable prefix of oversized XML. */}
                all.append("\n\n");
            }
        }
        catch(Exception ignored){}
        String result=all.toString();return result.length()>300000?result.substring(0,300000)+"…":result;
    }
    private static final class ZoomImage extends ImageView{
        private final Matrix matrix=new Matrix();private final ScaleGestureDetector scale;private final GestureDetector taps;private float x,y;
        ZoomImage(Activity activity){super(activity);setScaleType(ScaleType.MATRIX);
            scale=new ScaleGestureDetector(activity,new ScaleGestureDetector.SimpleOnScaleGestureListener(){public boolean onScale(ScaleGestureDetector detector){matrix.postScale(detector.getScaleFactor(),detector.getScaleFactor(),detector.getFocusX(),detector.getFocusY());setImageMatrix(matrix);return true;}});
            taps=new GestureDetector(activity,new GestureDetector.SimpleOnGestureListener(){public boolean onDoubleTap(MotionEvent e){fit();return true;}});
        }
        @Override public void setImageDrawable(android.graphics.drawable.Drawable drawable){super.setImageDrawable(drawable);post(this::fit);}
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);fit();}
        private void fit(){android.graphics.drawable.Drawable image=getDrawable();if(image==null||getWidth()==0||getHeight()==0)return;float width=Math.max(1,image.getIntrinsicWidth()),height=Math.max(1,image.getIntrinsicHeight()),factor=Math.min(getWidth()/width,getHeight()/height);matrix.reset();matrix.postScale(factor,factor);matrix.postTranslate((getWidth()-width*factor)/2,(getHeight()-height*factor)/2);setImageMatrix(matrix);}
        @Override public boolean onTouchEvent(MotionEvent e){scale.onTouchEvent(e);taps.onTouchEvent(e);if(e.getActionMasked()==MotionEvent.ACTION_DOWN){x=e.getX();y=e.getY();return true;}if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&!scale.isInProgress()&&e.getPointerCount()==1){matrix.postTranslate(e.getX()-x,e.getY()-y);setImageMatrix(matrix);}x=e.getX();y=e.getY();return true;}
    }

}
