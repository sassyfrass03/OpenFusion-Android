package dev.openfusion.launcher;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import org.json.JSONObject;

public final class AssetProxyService extends Service {
    static volatile String address="",lastError="";
    static volatile CountDownLatch ready=new CountDownLatch(1);
    private ProxyServer server;
    private byte[] lastGameCookieHash;
    static final String STOP="dev.openfusion.launcher.STOP_PROXY";
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        try{
            NotificationManager manager=getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("assets","OpenFusion game bridge",NotificationManager.IMPORTANCE_LOW));
            PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,AssetProxyService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification note=new Notification.Builder(this,"assets").setSmallIcon(R.drawable.ic_status).setContentTitle("OpenFusion game bridge")
                .setContentText("Keep active while FusionFall runs in Winlator.").setOngoing(true).setContentIntent(open)
                .addAction(new Notification.Action.Builder(null,"Stop bridge",stop).build()).build();
            if(Build.VERSION.SDK_INT>=29)startForeground(27,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(27,note);
            if(server!=null)server.close();
            server=null;
            String profile=intent.getStringExtra("profile");
            if(profile==null || !profile.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Missing game profile");
            SharedPreferences identities=getSharedPreferences("game-proxy-identities",MODE_PRIVATE);
            int port=identities.getInt(profile+":port",0);
            String token=identities.getString(profile+":token",null);
            if(token==null)token=java.util.UUID.randomUUID().toString();
            String authEndpoint=intent.getStringExtra("authEndpoint");
            String refresh=intent.getStringExtra("refreshToken");
            ProxyServer.AuthProvider authProvider=null;
            if(authEndpoint!=null&&!authEndpoint.isEmpty()&&refresh!=null&&!refresh.isEmpty()){
                final String ep=authEndpoint,refreshToken=refresh;
                authProvider=()->freshGameLogin(ep,refreshToken);
            }
            server=new ProxyServer(intent.getStringExtra("assets"),intent.getStringExtra("main"),port,token,message->lastError=message,authProvider);
            // Save before handing the URL to Unity. Never silently choose a new
            // URL on a port conflict: WebPlayerPrefs are keyed by this URL.
            if(!identities.edit().putInt(profile+":port",server.port()).putString(profile+":token",token).commit()){
                server.close();server=null;throw new java.io.IOException("Could not save game profile");
            }
            address=server.url();lastError="";
        }catch(Exception e){lastError=e instanceof java.net.BindException?"The saved game bridge port is busy. Close the other game session or restart your phone, then press Play again. Your saved settings were kept.":"Could not start game bridge: "+e.getClass().getSimpleName();address="";stopSelf();}
        finally{ready.countDown();}
        return START_NOT_STICKY;
    }

    private synchronized String freshGameLogin(String endpoint,String refresh) throws Exception {
        JSONObject cookie=null;String username=null,value=null;byte[] hash=null;
        for(int attempt=0;attempt<3;attempt++){
            JSONObject session=new JSONObject(HttpApi.request(endpoint+"/auth/session","POST",null,refresh));
            cookie=new JSONObject(HttpApi.request(endpoint+"/cookie","POST",null,session.getString("session_token")));
            if(cookie.getLong("expires")<=System.currentTimeMillis()/1000L)throw new java.io.IOException("Game login cookie already expired");
            username=cookie.getString("username");value=cookie.getString("cookie");
            if(username.isEmpty()||username.length()>256||value.isEmpty()||value.length()>8192)throw new java.io.IOException("Invalid game login response");
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            md.update(username.getBytes(StandardCharsets.UTF_8));md.update((byte)0);hash=md.digest(value.getBytes(StandardCharsets.UTF_8));
            if(lastGameCookieHash==null||!Arrays.equals(lastGameCookieHash,hash))break;
            if(attempt<2)Thread.sleep(350L*(attempt+1));
        }
        if(lastGameCookieHash!=null&&Arrays.equals(lastGameCookieHash,hash))throw new java.io.IOException("Endpoint repeated a one-shot game login cookie");
        lastGameCookieHash=hash;
        lastError="";
        return "username="+URLEncoder.encode(username,StandardCharsets.UTF_8.name())+"&cookie="+URLEncoder.encode(value,StandardCharsets.UTF_8.name());
    }
    @Override public void onTimeout(int startId,int fgsType){lastError="Android stopped the game bridge after its time limit. Press Play again to restart it.";stopSelf();}
    @Override public void onDestroy(){
        if(server!=null)try{server.close();}catch(Exception ignored){}address="";stopForeground(true);super.onDestroy();
    }
}
