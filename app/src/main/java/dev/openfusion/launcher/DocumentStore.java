package dev.openfusion.launcher;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;

final class DocumentStore {
    private final ContentResolver resolver;
    private final Uri tree;
    private final Map<String,Uri> directories=new HashMap<>();
    DocumentStore(Context c,String uri) throws IOException {
        if(uri==null || uri.isEmpty())throw new IOException("Choose your OpenFusion folder in Setup first.");
        resolver=c.getContentResolver();tree=Uri.parse(uri);
        directories.put("",DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree)));
    }
    private Uri child(Uri parent,String name) throws Exception {
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent));
        try(Cursor cur=resolver.query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
            if(cur==null)throw new IOException("Folder permission was lost. Choose the folder again.");
            while(cur.moveToNext())if(name.equals(cur.getString(1)))return DocumentsContract.buildDocumentUriUsingTree(tree,cur.getString(0));
        }
        return null;
    }
    static String safePath(String name) throws IOException {
        if(name.startsWith("/") || name.contains("\\") || name.contains(":"))throw new IOException("Unsafe archive path.");
        for(String s:name.split("/"))if(s.equals("..") || s.equals(".") || s.isEmpty())throw new IOException("Unsafe archive path.");
        return name;
    }
    private Uri directory(String path,boolean create) throws Exception {
        if(directories.containsKey(path))return directories.get(path);
        int sep=path.lastIndexOf('/');String parent=sep<0?"":path.substring(0,sep);String name=path.substring(sep+1);
        Uri p=directory(parent,create);if(p==null)return null;
        Uri d=child(p,name);
        if(d==null && create)d=DocumentsContract.createDocument(resolver,p,DocumentsContract.Document.MIME_TYPE_DIR,name);
        if(d!=null)directories.put(path,d);return d;
    }
    Uri find(String path) throws Exception {
        safePath(path);int sep=path.lastIndexOf('/');Uri parent=directory(sep<0?"":path.substring(0,sep),false);
        return parent==null?null:child(parent,path.substring(sep+1));
    }
    OutputStream open(String path) throws Exception {
        safePath(path);int sep=path.lastIndexOf('/');Uri parent=directory(sep<0?"":path.substring(0,sep),true);
        String name=path.substring(sep+1);Uri file=child(parent,name);
        if(file==null)file=DocumentsContract.createDocument(resolver,parent,"application/octet-stream",name);
        if(file==null)throw new IOException("Could not create " + name);
        OutputStream out=resolver.openOutputStream(file,"wt");if(out==null)throw new IOException("Cannot write " + name);return out;
    }
    void write(String path,String value) throws Exception {try(OutputStream out=open(path)){out.write(value.getBytes("UTF-8"));}}
    boolean exists(String path) throws Exception {return find(path)!=null;}
    void delete(String path) throws Exception {Uri u=find(path);if(u!=null)DocumentsContract.deleteDocument(resolver,u);}
    String filesystemPath(){
        if(!"com.android.externalstorage.documents".equals(tree.getAuthority()))return "";
        String id=DocumentsContract.getTreeDocumentId(tree);int colon=id.indexOf(':');if(colon<0)return "";
        String volume=id.substring(0,colon);return ("primary".equals(volume)?"/storage/emulated/0":"/storage/"+volume)+"/"+id.substring(colon+1);
    }
    void validateRunner() throws Exception {
        // The executable comes from the APK during preparation. Validate the
        // Unity runtime here; an old ffrunner.exe is no longer required.
        for(String path:new String[]{"loader/npUnity3D32.dll","player/fusion-2.x.x/webplayer_win.dll","mono/fusion-2.x.x/mono-1-vc.dll","mono/fusion-2.x.x/Data/lib/mscorlib.dll"})
            if(!exists(path))throw new IOException("Runner is incomplete: missing " + path + ". Use Install runner in Setup.");
    }
}
