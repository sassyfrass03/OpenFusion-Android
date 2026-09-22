import dev.openfusion.launcher.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ProxyPersistenceRegression {
 private static String body(HttpURLConnection c) throws Exception {
  InputStream in=c.getResponseCode()<400?c.getInputStream():c.getErrorStream();
  try(InputStream src=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
   byte[] b=new byte[1024];int n;while((n=src.read(b))!=-1)out.write(b,0,n);
   return out.toString(StandardCharsets.UTF_8.name());
  }
 }
 public static void main(String[] args) throws Exception {
  Path state=Paths.get(args[0]);boolean restore=Files.exists(state);
  String token=restore?Files.readAllLines(state).get(1):UUID.randomUUID().toString();
  int port=restore?Integer.parseInt(Files.readAllLines(state).get(0)):0;
  String id="12345678-1234-1234-1234-123456789abc";
  String key=LaunchSpec.profileKey("https://example.org",id);
  if(!key.equals(LaunchSpec.profileKey("https://example.org",id))||key.equals(LaunchSpec.profileKey("https://other.org",id)))throw new AssertionError("profile separation");
  AtomicInteger authCalls=new AtomicInteger();
  String url;
  try(ProxyServer p=new ProxyServer("https://example.org/assets","https://example.org/main",port,token,m->{},()->{
   int n=authCalls.incrementAndGet();return "username=user"+n+"&cookie=cookie"+n;
  })){
   url=p.url();if(restore && !url.equals(Files.readAllLines(state).get(2)))throw new AssertionError("URL changed across process restart");
   HttpURLConnection wrong=(HttpURLConnection)new URL("http://127.0.0.1:"+p.port()+"/wrong-token/__auth").openConnection();wrong.setReadTimeout(2000);if(wrong.getResponseCode()!=404)throw new AssertionError("capability check");wrong.disconnect();

   HttpURLConnection first=(HttpURLConnection)new URL(url+"__auth").openConnection();first.setReadTimeout(2000);
   if(first.getResponseCode()!=200)throw new AssertionError("auth bridge status");
   if(!"no-store".equalsIgnoreCase(first.getHeaderField("Cache-Control")))throw new AssertionError("auth response must not cache");
   String a=body(first);first.disconnect();
   HttpURLConnection second=(HttpURLConnection)new URL(url+"__auth").openConnection();second.setReadTimeout(2000);
   String b=body(second);second.disconnect();
   if(a.equals(b)||authCalls.get()!=2)throw new AssertionError("each auth request must mint a fresh response");

   boolean conflict=false;try(ProxyServer other=new ProxyServer("https://example.org/","https://example.org/main",p.port(),token,m->{})){}catch(BindException expected){conflict=true;}
   if(!conflict)throw new AssertionError("busy port must fail");
   if(!restore)Files.write(state,Arrays.asList(""+p.port(),token,url));
  }
  System.out.println(restore?"PASS: stable game URL across restart; fresh auth per request; capability enforced; busy port rejected":"PASS: initial persistent identity; separate profiles; fresh auth per request; capability enforced; busy port rejected");
 }
}
