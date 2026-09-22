package dev.openfusion.launcher;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Loopback-only, capability-protected streaming proxy for the legacy Unity player. */
public final class ProxyServer implements Closeable {
    public interface Listener { void error(String message); }
    public interface AuthProvider { String fresh() throws Exception; }
    private final ServerSocket socket;
    private final ThreadPoolExecutor clients = new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(24));
    private final String base, main, secret;
    private final Listener listener;
    private final AuthProvider authProvider;
    private volatile boolean closed;
    public ProxyServer(String assets,String mainUrl,int port,String token,Listener listener) throws Exception {
        this(assets,mainUrl,port,token,listener,null);
    }
    public ProxyServer(String assets,String mainUrl,int port,String token,Listener listener,AuthProvider authProvider) throws Exception {
        if(port<0 || port>65535)throw new IllegalArgumentException("Invalid saved proxy port");
        secret=UUID.fromString(token).toString();
        base=LaunchSpec.webUrl(assets).replaceAll("/+$","")+"/";main=LaunchSpec.webUrl(mainUrl);this.listener=listener;this.authProvider=authProvider;
        socket=new ServerSocket();
        try {socket.setReuseAddress(true);socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),16);}
        catch(Exception e){socket.close();throw e;}
        Thread t=new Thread(()->accept(),"fusion-asset-proxy");t.setDaemon(true);t.start();
    }
    public int port(){return socket.getLocalPort();}
    public String url(){return "http://127.0.0.1:"+socket.getLocalPort()+"/"+secret+"/";}
    private void accept(){
        while(!closed)try{
            final Socket s=socket.accept();s.setSoTimeout(15000);
            try{clients.execute(()->serve(s));}catch(RejectedExecutionException e){s.close();}
        }catch(IOException e){if(!closed)listener.error("Asset proxy stopped unexpectedly.");}
    }
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();int c;
        while((c=in.read())!=-1 && c!='\n'){if(b.size()>=8192)throw new IOException("Long HTTP header");if(c!='\r')b.write(c);}
        return b.toString("ISO-8859-1");
    }
    private static void error(OutputStream out,int code,String text) throws IOException {
        byte[] body=text.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 "+code+" Error\r\nContent-Type: text/plain\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);
    }
    private HttpURLConnection upstream(String address,String method,String range) throws Exception {
        URL next=new URL(address);
        for(int count=0;count<5;count++){
            HttpURLConnection c=(HttpURLConnection)next.openConnection();c.setRequestMethod(method);c.setConnectTimeout(15000);c.setReadTimeout(45000);
            c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent",HttpApi.USER_AGENT);c.setRequestProperty("Accept-Encoding","identity");
            if(range!=null)c.setRequestProperty("Range",range);
            int code=c.getResponseCode();
            if(code==301 || code==302 || code==303 || code==307 || code==308){
                String location=c.getHeaderField("Location");c.disconnect();
                if(location==null)throw new IOException("Missing redirect location");
                URL target=new URL(next,location);LaunchSpec.webUrl(target.toString());
                if("https".equals(next.getProtocol())&&!"https".equals(target.getProtocol()))throw new IOException("Refusing HTTPS downgrade");
                next=target;continue;
            }
            return c;
        }
        throw new IOException("Too many asset redirects");
    }
    private void serve(Socket s){
        boolean sent=false;HttpURLConnection c=null;
        try(Socket client=s){
            InputStream in=client.getInputStream();OutputStream out=client.getOutputStream();
            String[] request=line(in).split(" ");
            if(request.length!=3){error(out,400,"Bad request");return;}
            String method=request[0];if(!method.equals("GET")&&!method.equals("HEAD")){error(out,405,"GET or HEAD only");return;}
            String path=request[1],prefix="/"+secret+"/";
            if(!path.startsWith(prefix)){error(out,404,"Not found");return;}
            String relative=path.substring(prefix.length());
            String decoded=URLDecoder.decode(relative,"UTF-8");
            String plain=decoded.split("\\?",2)[0];
            if(plain.contains("\\")||plain.contains(":")||plain.startsWith("/")||Arrays.asList(plain.split("/")).contains("..")){
                error(out,400,"Invalid asset path");return;
            }
            String range=null;int headers=0;String header;
            while(!(header=line(in)).isEmpty()){
                if(++headers>50)throw new IOException("Too many headers");
                if(header.toLowerCase(Locale.ROOT).startsWith("range:"))range=header.substring(6).trim();
            }
            if(relative.equals("__auth")){
                if(!method.equals("GET")){error(out,405,"GET only");return;}
                if(authProvider==null){error(out,404,"Not found");return;}
                try{
                    byte[] body=authProvider.fresh().getBytes(StandardCharsets.UTF_8);
                    if(body.length>16384)throw new IOException("Authentication response too large");
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/x-www-form-urlencoded; charset=utf-8\r\nCache-Control: no-store\r\nPragma: no-cache\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(body);sent=true;return;
                }catch(Exception e){
                    listener.error("Fresh game login failed. Return to the launcher and sign in again if this continues.");
                    error(out,503,"Fresh game login failed");sent=true;return;
                }
            }
            c=upstream(relative.equals("__main.unity3d")?main:base+relative,method,range);
            int code=c.getResponseCode();StringBuilder h=new StringBuilder("HTTP/1.1 "+code+" Response\r\nConnection: close\r\n");
            for(String name:new String[]{"Content-Type","Content-Length","Content-Range","Accept-Ranges","Last-Modified","ETag"}){
                String value=c.getHeaderField(name);if(value!=null&&!value.contains("\r")&&!value.contains("\n"))h.append(name).append(": ").append(value).append("\r\n");
            }
            out.write(h.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1));sent=true;
            if(!method.equals("HEAD")){
                InputStream remote=code<400?c.getInputStream():c.getErrorStream();
                if(remote!=null)try(InputStream data=remote){byte[] buf=new byte[65536];int n;while(!closed&&(n=data.read(buf))!=-1)out.write(buf,0,n);}
            }
            if(code>=400)listener.error("An asset download returned HTTP "+code+".");
        }catch(Exception e){
            if(!closed){listener.error("An asset request failed: "+e.getClass().getSimpleName());
                if(!sent)try{error(s.getOutputStream(),502,"Asset download failed");}catch(Exception ignored){}
            }
        }finally{if(c!=null)c.disconnect();}
    }
    @Override public void close() throws IOException {closed=true;socket.close();clients.shutdownNow();}
}
