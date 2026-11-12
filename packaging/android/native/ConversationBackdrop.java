package io.github.abyxs.codingremote;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import java.io.*;

/** Private, bounded image copies, decoded only when a page backdrop is created. */
final class ConversationBackdrop extends Drawable {
    private final Bitmap bitmap;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final int opacity;
    private ConversationBackdrop(Bitmap image,int alpha){bitmap=image;opacity=alpha;}
    static File file(Context context,String page){return new File(context.getFilesDir(),"background-"+page+".jpg");}
    static boolean has(Context context,String page){return file(context,page).isFile();}
    static Drawable load(Context context,String page){
        Bitmap image=BitmapFactory.decodeFile(file(context,page).getPath());
        if(image==null)return new android.graphics.drawable.ColorDrawable(NativeUi.CANVAS);
        return new ConversationBackdrop(image,Math.max(0,Math.min(100,context.getSharedPreferences("ui",0).getInt("backgroundOpacity_"+page,25))));
    }
    static void save(Context context,String page,Uri uri)throws IOException{
        File temp=new File(context.getCacheDir(),"background-import");
        try{
            try(InputStream source=context.getContentResolver().openInputStream(uri);OutputStream target=new FileOutputStream(temp)){
                if(source==null)throw new IOException("无法读取图片");byte[] buffer=new byte[32768];int size=0,n;
                while((n=source.read(buffer))!=-1){size+=n;if(size>30*1024*1024)throw new IOException("请选择小于 30 MB 的图片");target.write(buffer,0,n);}
            }
            BitmapFactory.Options info=new BitmapFactory.Options();info.inJustDecodeBounds=true;BitmapFactory.decodeFile(temp.getPath(),info);
            if(info.outWidth<=0||info.outHeight<=0)throw new IOException("无法识别这张图片");
            info.inJustDecodeBounds=false;info.inSampleSize=1;while(Math.max(info.outWidth,info.outHeight)/info.inSampleSize>1920)info.inSampleSize*=2;
            Bitmap image=BitmapFactory.decodeFile(temp.getPath(),info);if(image==null)throw new IOException("图片解码失败");
            File staging=new File(context.getFilesDir(),"background-"+page+".tmp");
            try(OutputStream target=new FileOutputStream(staging)){if(!image.compress(Bitmap.CompressFormat.JPEG,90,target))throw new IOException("图片保存失败");}finally{image.recycle();}
            java.nio.file.Files.move(staging.toPath(),file(context,page).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }finally{temp.delete();}
    }
    @Override public void draw(Canvas canvas){
        Rect bounds=getBounds();canvas.drawColor(NativeUi.CANVAS);if(bitmap==null)return;
        float scale=Math.max(bounds.width()/(float)bitmap.getWidth(),bounds.height()/(float)bitmap.getHeight());
        float width=bitmap.getWidth()*scale,height=bitmap.getHeight()*scale;
        paint.setAlpha(Math.round(opacity*2.55f));canvas.save();canvas.clipRect(bounds);
        canvas.drawBitmap(bitmap,null,new RectF(bounds.centerX()-width/2,bounds.centerY()-height/2,bounds.centerX()+width/2,bounds.centerY()+height/2),paint);canvas.restore();
    }
    @Override public void setAlpha(int alpha){}@Override public void setColorFilter(ColorFilter filter){}@Override public int getOpacity(){return PixelFormat.OPAQUE;}
}
