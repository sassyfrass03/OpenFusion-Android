package dev.openfusion.launcher;

import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

final class RuntimeInstaller {
    static final String URL="https://github.com/OpenFusionProject/OpenFusionLauncher/releases/download/2.2.4/OpenFusionLauncher-Windows-Portable.zip";
    static final String HASH="0531865ca0dfc59a6615684249469583defa87ab4b339b53c7bf57aeb7ecb3cf";
    interface Progress{void update(String message);}
    static void install(File cache,DocumentStore store,Progress progress) throws Exception{
        File zip=new File(cache,"openfusion-runtime-2.2.4.zip");
        try{
            progress.update("Downloading official runner 2.2.4 (15 MB)…");
            HttpURLConnection c=null;java.net.URL next=new java.net.URL(URL);
            for(int n=0;n<6;n++){
                c=(HttpURLConnection)next.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(20000);c.setReadTimeout(45000);c.setRequestProperty("User-Agent",HttpApi.USER_AGENT);
                int code=c.getResponseCode();
                if(code>=300&&code<400){String loc=c.getHeaderField("Location");c.disconnect();if(loc==null)throw new IOException("Invalid runtime redirect.");next=new java.net.URL(next,loc);if(!next.getProtocol().equals("https"))throw new IOException("Runtime download must stay on HTTPS.");continue;}
                if(code!=200){c.disconnect();throw new IOException("Runtime download returned HTTP "+code);}
                break;
            }
            if(c==null||c.getResponseCode()!=200)throw new IOException("Runtime download redirect limit.");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(zip)){
                byte[] buffer=new byte[65536];int count;long total=0,last=0;
                while((count=in.read(buffer))!=-1){total+=count;if(total>40L*1024*1024)throw new IOException("Unexpected runtime archive size.");digest.update(buffer,0,count);out.write(buffer,0,count);
                    if(total-last>2*1024*1024){progress.update("Downloading runner: "+(total/1024/1024)+" MB / 15 MB");last=total;}}
            }finally{c.disconnect();}
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
            if(!HASH.equals(hex.toString()))throw new IOException("Runtime checksum did not match. No runtime files were installed.");
            progress.update("Installing runner and Unity support files…");
            try(ZipInputStream in=new ZipInputStream(new FileInputStream(zip))){
                ZipEntry entry;long total=0;
                while((entry=in.getNextEntry())!=null){String name=entry.getName();if(entry.isDirectory())continue;DocumentStore.safePath(name);
                    if(!(name.equals("ffrunner.exe")||name.equals("d3d9_vulkan.dll")||name.startsWith("loader/")||name.startsWith("mono/")||name.startsWith("player/")||name.startsWith("assets/")))continue;
                    try(OutputStream out=store.open(name)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){total+=n;if(total>100L*1024*1024)throw new IOException("Extracted runtime is too large.");out.write(b,0,n);}}
                }
            }
            store.validateRunner();store.write("ANDROID-RUNNER-SOURCE.txt","Runtime files installed from the official OpenFusionLauncher 2.2.4 release.\n"+URL+"\nSHA-256: "+HASH+"\nThe Windows launcher is not used.\n");
            progress.update("Runner installed and checked.");
        }finally{zip.delete();}
    }
}
