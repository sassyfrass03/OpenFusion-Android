package dev.openfusion.launcher;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

public final class HttpApi {
    public static final String USER_AGENT = "OpenFusionAndroid/0.4.3";
    private HttpApi() {}
    public static String request(String url, String method, JSONObject body, String token) throws Exception {
        if (!url.startsWith("https://")) throw new IOException("Account API connections require HTTPS.");
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(25000); c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method); c.setRequestProperty("Accept","application/json"); c.setRequestProperty("User-Agent",USER_AGENT);
        if (token != null) c.setRequestProperty("Authorization","Bearer " + token);
        try {
            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(data.length);
                try(OutputStream out=c.getOutputStream()) { out.write(data); }
            } else if ("POST".equals(method)) { c.setDoOutput(true);c.setFixedLengthStreamingMode(0);c.getOutputStream().close(); }
            int code=c.getResponseCode();
            if (code==401) throw new IOException("Sign-in expired or username/password incorrect. Sign in again.");
            if (code==403) throw new IOException("The server refused this request (403). Check the account or server access.");
            if (code<200 || code>=300) throw new IOException("Server returned HTTP " + code + ". Try again or check the server address.");
            try(InputStream in=c.getInputStream()) {return new String(read(in,8*1024*1024),StandardCharsets.UTF_8);}
        } finally {c.disconnect();}
    }
    public static byte[] read(InputStream in,int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[16384];int n;
        while((n=in.read(buf))!=-1){if(out.size()+n>limit)throw new IOException("Response exceeds size limit.");out.write(buf,0,n);}
        return out.toByteArray();
    }
    public static JSONObject get(String url) throws Exception {return new JSONObject(request(url,"GET",null,null));}
    /** Public status requests have short timeouts and only same-origin HTTPS redirects. */
    public static JSONObject getPublic(String url) throws Exception {
        URL original=new URL(url), current=original;
        if(!"https".equals(original.getProtocol()))throw new IOException("Server status requires HTTPS.");
        for(int redirect=0;redirect<4;redirect++){
            HttpURLConnection c=(HttpURLConnection)current.openConnection();
            c.setConnectTimeout(4000);c.setReadTimeout(4000);c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent",USER_AGENT);
            try{
                int code=c.getResponseCode();
                if(code==301||code==302||code==303||code==307||code==308){
                    String location=c.getHeaderField("Location");if(location==null)throw new IOException("Missing redirect destination.");
                    URL next=new URL(current,location);
                    int firstPort=original.getPort()==-1?443:original.getPort(),nextPort=next.getPort()==-1?443:next.getPort();
                    if(!"https".equals(next.getProtocol())||!original.getHost().equalsIgnoreCase(next.getHost())||firstPort!=nextPort||next.getUserInfo()!=null)
                        throw new IOException("Server status redirect changed origin.");
                    current=next;continue;
                }
                if(code<200||code>=300)throw new IOException("Server returned HTTP "+code+".");
                try(InputStream in=c.getInputStream()){return new JSONObject(new String(read(in,1024*1024),StandardCharsets.UTF_8));}
            }finally{c.disconnect();}
        }
        throw new IOException("Too many server redirects.");
    }
    public static String refreshToken(String body) throws Exception {
        String s=body.trim();
        if(s.startsWith("\"")) s=(String)new JSONTokener(s).nextValue();
        if(s.isEmpty() || s.length()>8192 || s.matches("(?s).*[\\r\\n].*")) throw new IOException("Invalid authentication response.");
        return s;
    }
}
