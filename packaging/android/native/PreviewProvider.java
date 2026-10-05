package io.github.abyxs.codingremote;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Read-only, non-exported provider. Access requires an explicit per-file URI grant. */
public final class PreviewProvider extends ContentProvider {
    public boolean onCreate(){return true;}
    private File file(Uri uri)throws FileNotFoundException{try{File root=new File(getContext().getCacheDir(),"previews").getCanonicalFile();File result=new File(root,uri.getPath().substring(1)).getCanonicalFile();if(!result.getPath().startsWith(root.getPath()+File.separator)||!result.isFile())throw new FileNotFoundException();return result;}catch(IOException e){throw new FileNotFoundException();}}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("read only");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    @Override public String getType(Uri uri){String extension=android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString());String mime=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);return mime==null?"application/octet-stream":mime;}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){try{File f=file(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns);Object[] row=new Object[columns.length];for(int i=0;i<columns.length;i++)row[i]=columns[i].equals(OpenableColumns.DISPLAY_NAME)?f.getName():columns[i].equals(OpenableColumns.SIZE)?f.length():null;cursor.addRow(row);return cursor;}catch(FileNotFoundException e){return null;}}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String s,String[] a){throw new UnsupportedOperationException();}
}
