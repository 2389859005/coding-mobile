package io.github.abyxs.codingremote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import java.io.*;
import org.json.JSONObject;

final class ImageAttachment {
    static JSONObject prepare(Context context,Uri uri)throws Exception {
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        try(InputStream in=context.getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,options);}
        if(options.outWidth<1||options.outHeight<1)throw new IOException("无法读取图片");
        options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>1800)options.inSampleSize*=2;
        options.inJustDecodeBounds=false;Bitmap original;
        try(InputStream in=context.getContentResolver().openInputStream(uri)){original=BitmapFactory.decodeStream(in,null,options);}
        if(original==null)throw new IOException("无法读取图片");
        Bitmap bitmap=original;
        try {
            // Respect camera orientation without loading the full-resolution image.
            try(android.os.ParcelFileDescriptor fd=context.getContentResolver().openFileDescriptor(uri,"r")){
                android.media.ExifInterface exif=new android.media.ExifInterface(fd.getFileDescriptor());int rotation=exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1);android.graphics.Matrix matrix=new android.graphics.Matrix();
                if(rotation==3)matrix.postRotate(180);else if(rotation==6)matrix.postRotate(90);else if(rotation==8)matrix.postRotate(270);else if(rotation==2)matrix.postScale(-1,1);else if(rotation==4)matrix.postScale(1,-1);else if(rotation==5){matrix.postRotate(90);matrix.postScale(-1,1);}else if(rotation==7){matrix.postRotate(270);matrix.postScale(-1,1);}
                if(rotation>1)bitmap=Bitmap.createBitmap(original,0,0,original.getWidth(),original.getHeight(),matrix,true);
            }catch(Exception ignored){}
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,88,bytes);if(bytes.size()>3*1024*1024){bytes.reset();bitmap.compress(Bitmap.CompressFormat.JPEG,68,bytes);}if(bytes.size()>3*1024*1024)throw new IOException("图片过大，请裁剪后重试");
            Bitmap thumb=Bitmap.createScaledBitmap(bitmap,Math.max(1,bitmap.getWidth()*100/Math.max(bitmap.getWidth(),bitmap.getHeight())),Math.max(1,bitmap.getHeight()*100/Math.max(bitmap.getWidth(),bitmap.getHeight())),true);ByteArrayOutputStream small=new ByteArrayOutputStream();thumb.compress(Bitmap.CompressFormat.JPEG,70,small);if(thumb!=bitmap)thumb.recycle();
            return new JSONObject().put("name","图片.jpg").put("mime","image/jpeg").put("data",Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP)).put("thumbnail",Base64.encodeToString(small.toByteArray(),Base64.NO_WRAP));
        }finally{if(bitmap!=original)bitmap.recycle();original.recycle();}
    }
}
