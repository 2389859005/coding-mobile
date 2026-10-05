package io.github.abyxs.codingremote;

import android.content.Context;
import java.io.*;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Bounded, app-private snapshots; never causes a Desktop open or a message send. */
final class ConversationCache {
    private static File file(Context c,String key)throws Exception{
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(key.getBytes("UTF-8"));StringBuilder name=new StringBuilder();for(byte b:hash)name.append(String.format("%02x",b&255));File dir=new File(c.getCacheDir(),"conversations");dir.mkdirs();return new File(dir,name+".json");
    }
    static JSONObject read(Context c,String key){try{File f=file(c,key);if(f.length()>2*1024*1024)return null;try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new JSONObject(out.toString("UTF-8"));}}catch(Exception e){return null;}}
    static synchronized void write(Context c,String key,JSONObject value){try{File f=file(c,key),tmp=new File(f+".tmp");byte[] bytes=value.toString().getBytes("UTF-8");if(bytes.length>2*1024*1024)return;try(OutputStream out=new FileOutputStream(tmp)){out.write(bytes);}if(!tmp.renameTo(f))tmp.delete();File[] all=f.getParentFile().listFiles((d,n)->n.endsWith(".json"));if(all!=null&&all.length>12){java.util.Arrays.sort(all,(a,b)->Long.compare(b.lastModified(),a.lastModified()));for(int i=12;i<all.length;i++)all[i].delete();}}catch(Exception ignored){}}
    static void clear(Context c){File d=new File(c.getCacheDir(),"conversations");File[] files=d.listFiles();if(files!=null)for(File f:files)if(f.isFile())f.delete();}
}
