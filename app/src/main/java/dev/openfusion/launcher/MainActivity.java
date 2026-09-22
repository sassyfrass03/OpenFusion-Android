package dev.openfusion.launcher;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final String ORIGIN="https://launcher.openfusion.invalid/";
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private final ExecutorService statusWork=Executors.newSingleThreadExecutor();
    private final Map<String,String> temporaryTokens=new HashMap<>();
    private final List<String> logs=Collections.synchronizedList(new ArrayList<>());
    private WebView web;
    private SharedPreferences prefs;
    private TokenVault vault;
    private LauncherAudio audio;
    private JSONArray bundledVersions;
    private String folderRequest;
    private volatile boolean destroyed;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        prefs=getSharedPreferences("launcher",MODE_PRIVATE);vault=new TokenVault(this);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        audio=new LauncherAudio(this,prefs);
        try{bundledVersions=new JSONArray(asset("versions.json"));}catch(Exception e){bundledVersions=new JSONArray();}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(2,17,36));
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets x=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(x.left,x.top,x.right,x.bottom);}
            else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        try{web=new WebView(this);}catch(Throwable e){TextView message=new TextView(this);message.setText("Android System WebView is unavailable. Enable or update Android System WebView or Chrome, then reopen OpenFusion.");message.setPadding(30,60,30,30);root.addView(message);setContentView(root);return;}
        WebSettings ws=web.getSettings();ws.setJavaScriptEnabled(true);ws.setDomStorageEnabled(false);ws.setAllowFileAccess(false);ws.setAllowContentAccess(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);ws.setJavaScriptCanOpenWindowsAutomatically(false);ws.setSupportMultipleWindows(false);
        WebView.setWebContentsDebuggingEnabled(false);web.setBackgroundColor(Color.rgb(2,17,36));
        web.setSoundEffectsEnabled(false);
        web.addJavascriptInterface(new Bridge(),"Android");
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest req){
                String url=req.getUrl().toString();
                try{
                    if(!url.startsWith(ORIGIN))return blocked();
                    String file=url.substring(ORIGIN.length());if(file.isEmpty())file="index.html";
                    if(!Arrays.asList("index.html","app.css","app.js","logo.png","launcher-art.jpg","landscape-art.png","boot-logo.png").contains(file))return blocked();
                    String mime=file.endsWith(".html")?"text/html":file.endsWith(".css")?"text/css":file.endsWith(".js")?"application/javascript":file.endsWith(".jpg")?"image/jpeg":"image/png";
                    Map<String,String> headers=new HashMap<>();headers.put("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'");
                    headers.put("X-Content-Type-Options","nosniff");
                    return new WebResourceResponse(mime,"UTF-8",200,"OK",headers,getAssets().open("ui/"+file));
                }catch(Exception e){return blocked();}
            }
        });
        root.addView(web,new LinearLayout.LayoutParams(-1,-1));setContentView(root);web.loadUrl(ORIGIN);
    }
    private WebResourceResponse blocked(){return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",null,new ByteArrayInputStream(new byte[0]));}
    private String asset(String path) throws Exception {try(InputStream in=getAssets().open(path)){return new String(HttpApi.read(in,1024*1024),StandardCharsets.UTF_8);}}
    private void emit(JSONObject result){if(!destroyed&&web!=null)runOnUiThread(()->{if(!destroyed)web.evaluateJavascript("window.nativeReply("+result.toString()+")",null);});}
    private void reply(String id,Object data,String error){try{JSONObject out=new JSONObject().put("id",id);if(error==null)out.put("result",data==null?JSONObject.NULL:data);else out.put("error",error);emit(out);}catch(Exception ignored){}}
    private void log(String message){
        String line=new java.text.SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())+"  "+message;
        logs.add(line);if(logs.size()>100)logs.remove(0);
        try{emit(new JSONObject().put("event","log").put("message",line));}catch(Exception ignored){}
    }
    public final class Bridge {
        @JavascriptInterface public void uiTap(){runOnUiThread(()->{if(!destroyed&&audio!=null)audio.tap();});}
        @JavascriptInterface public void setAudioMuted(boolean music,boolean effects){runOnUiThread(()->{if(!destroyed&&audio!=null)audio.setMuted(music,effects);});}
        @JavascriptInterface public void call(String input){
            try{
                if(input.length()>32768)throw new Exception("Request too large");
                JSONObject call=new JSONObject(input);String id=call.getString("id"),action=call.getString("action");
                if(!id.matches("[0-9]{1,12}"))return;
                JSONObject data=call.optJSONObject("data");if(data==null)data=new JSONObject();final JSONObject payload=data;
                if(action.equals("chooseFolder")){
                    runOnUiThread(()->{
                        if(folderRequest!=null){reply(id,null,"The folder picker is already open.");return;}folderRequest=id;
                        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                        try{startActivityForResult(i,100);}catch(Exception e){folderRequest=null;reply(id,null,"No Android folder picker is available.");}
                    });return;
                }
                (action.equals("serverStatus")?statusWork:work).execute(()->{
                    try{reply(id,dispatch(action,payload),null);}
                    catch(Exception e){String message=e.getMessage();if(message==null)message=e.getClass().getSimpleName();
                        // Never log raw server bodies, passwords, refresh tokens or generated command lines.
                        log(action+": "+message);reply(id,null,message);}
                });
            }catch(Exception ignored){}
        }
    }
    private JSONObject settings() throws Exception {
        JSONObject s=new JSONObject(prefs.getString("settings","{}"));
        if(LaunchSpec.migrateWindowSettings(s))prefs.edit().putString("settings",s.toString()).apply();
        if(!s.has("server"))s.put("server","original");if(!s.has("endpoint"))s.put("endpoint","api.dexlabs.systems");
        if(!s.has("width"))s.put("width",960);if(!s.has("height"))s.put("height",540);if(!s.has("fps"))s.put("fps",30);
        if(!s.has("windowsFolder"))s.put("windowsFolder","D:\\OpenFusion");if(!s.has("proxy"))s.put("proxy",true);
        if(!s.has("container"))s.put("container",1);if(!s.has("direct"))s.put("direct",false);
        if(!s.has("graphics"))s.put("graphics","dx9");if(!s.has("borderless"))s.put("borderless",false);if(!s.has("controllerBridge"))s.put("controllerBridge",true);
        s.put("muteMusic",prefs.getBoolean("muteMusic",false)).put("muteUiSounds",prefs.getBoolean("muteUiSounds",false));return s;
    }
    private String endpointFor(JSONObject s) throws Exception {
        String selected=s.optString("server","original");
        return LaunchSpec.endpoint(selected.equals("original")?"api.dexlabs.systems":selected.equals("academy")?"api.dexlabs.systems/academy":s.optString("endpoint"));
    }
    private String token(String endpoint) throws Exception {String t=temporaryTokens.get(endpoint);return t!=null?t:vault.get(endpoint);}
    private DocumentStore documents() throws Exception {return new DocumentStore(this,prefs.getString("tree",""));}
    private Object dispatch(String action,JSONObject data) throws Exception {
        switch(action){
            case "state":return state();
            case "save":{
                JSONObject s=settings();for(String k:new String[]{"server","endpoint","address","version","windowsFolder","package","container","width","height","fps","graphics","proxy","direct","borderless","controllerBridge"})if(data.has(k))s.put(k,data.get(k));
                LaunchSpec.bounded(s,"width",960,640,3840);LaunchSpec.bounded(s,"height",540,360,2160);LaunchSpec.bounded(s,"fps",30,15,120);LaunchSpec.bounded(s,"container",1,1,999);
                LaunchSpec.windowsFolder(s.getString("windowsFolder"));
                prefs.edit().putString("settings",s.toString()).apply();return s;
            }
            case "serverInfo":return serverInfo();
            case "serverStatus":return ServerStatus.fetch(data.optString("server").equals("simple")?null:endpointFor(data));
            case "login":{
                String ep=endpointFor(settings());JSONObject credentials=new JSONObject().put("username",data.getString("username")).put("password",data.getString("password"));
                if(credentials.getString("username").isEmpty()||credentials.getString("password").isEmpty())throw new Exception("Enter your username and password.");
                log("Signing in over HTTPS…");
                String refresh=HttpApi.refreshToken(HttpApi.request(ep+"/auth","POST",credentials,null));
                JSONObject session=new JSONObject(HttpApi.request(ep+"/auth/session","POST",null,refresh));
                temporaryTokens.put(ep,refresh);if(data.optBoolean("remember"))vault.put(ep,refresh);else vault.remove(ep);
                String name=session.optString("username",data.getString("username"));prefs.edit().putString("user:"+ep,name).apply();
                log("Signed in.");return new JSONObject().put("username",name);
            }
            case "register":{
                String ep=endpointFor(settings());JSONObject body=new JSONObject().put("username",data.getString("username")).put("password",data.getString("password"));
                if(!data.optString("email").isEmpty())body.put("email",data.getString("email"));
                HttpApi.request(ep+"/account/register","POST",body,null);log("Registration accepted by the server. Check your email if required, then sign in.");return true;
            }
            case "logout":{
                String ep=endpointFor(settings());temporaryTokens.remove(ep);vault.remove(ep);prefs.edit().remove("user:"+ep).apply();
                try{documents().delete("Launch_OpenFusion.cmd");}catch(Exception ignored){}
                log("Signed out and cleared the pending launch.");return true;
            }
            case "install":{
                RuntimeInstaller.install(getCacheDir(),documents(),m->log(m));prefs.edit().putBoolean("installed",true).apply();return true;
            }
            case "checkRunner":{documents().validateRunner();log("Required runner files found.");return true;}
            case "prepare":return prepare(data.optBoolean("open",true));
            case "openWinlator":return openWinlator(settings(),false);
            case "stopProxy":stopService(new Intent(this,AssetProxyService.class));log("Game download service stopped.");return true;
            case "copyLog":{
                String summary="OpenFusion Android 0.4.11-stable-runtime-detection\nAndroid "+Build.VERSION.RELEASE+" (SDK "+Build.VERSION.SDK_INT+")\n";
                synchronized(logs){summary+=String.join("\n",logs);}
                if(!AssetProxyService.lastError.isEmpty())summary+="\n"+AssetProxyService.lastError;
                final String text=summary;runOnUiThread(()->getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("OpenFusion diagnostics",text)));return true;
            }
            case "notifyPermission":runOnUiThread(()->{if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},101);});return true;
            case "website":runOnUiThread(()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://openfusion.dev/"))));return true;
            default:throw new Exception("Unknown launcher action.");
        }
    }
    private JSONObject state() throws Exception {
        JSONObject s=settings(),out=new JSONObject().put("settings",s).put("versions",bundledVersions).put("apps",runtimeApps())
            .put("folder",prefs.getString("folderLabel","")).put("proxyRunning",!AssetProxyService.address.isEmpty()).put("proxyError",AssetProxyService.lastError)
            .put("android",Build.VERSION.RELEASE).put("installed",prefs.getBoolean("installed",false));
        boolean signed=false;String username="";
        if(!s.optString("server").equals("simple"))try{String ep=endpointFor(s);signed=token(ep)!=null;username=prefs.getString("user:"+ep,"");}catch(Exception e){log("Saved sign-in unavailable. Sign in again.");}
        out.put("signedIn",signed).put("username",username);
        synchronized(logs){out.put("logs",new JSONArray(logs));}
        return out;
    }
    private boolean compatibleRuntimeApp(String pkg,String label){
        String p=(pkg==null?"":pkg).toLowerCase(Locale.ROOT);
        String l=(label==null?"":label).toLowerCase(Locale.ROOT);
        // Prefer the visible launcher label. Some Android Windows runtimes use
        // performance-spoof package IDs, so accepting those IDs by themselves
        // can accidentally list unrelated apps. Known normal package IDs are
        // accepted as a fallback when the label is less descriptive.
        String[] names={"winlator","ludashi","gamenative","game native","winnative","win native",
            "gamehub","game hub","bannerlator","bannerhub","banner hub"};
        for(String name:names)if(l.contains(name))return true;
        return p.equals("app.gamenative")||p.equals("gamehub.lite")||p.equals("gamehub.org")||
            p.equals("com.xiaoji.egggame")||p.equals("com.winlator.banner")||p.equals("banner.hub")||
            p.equals("com.winlator")||p.startsWith("com.winlator.");
    }
    private JSONArray runtimeApps() throws Exception {
        Intent query=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);JSONArray apps=new JSONArray();Set<String> seen=new HashSet<>();
        ArrayList<JSONObject> found=new ArrayList<>();
        for(ResolveInfo r:getPackageManager().queryIntentActivities(query,0)){
            String pkg=r.activityInfo.packageName,label=r.loadLabel(getPackageManager()).toString();
            if(compatibleRuntimeApp(pkg,label)&&seen.add(pkg))found.add(new JSONObject().put("package",pkg).put("label",label).put("direct",exportedDisplay(pkg)!=null));
        }
        Collections.sort(found,(a,b)->a.optString("label").compareToIgnoreCase(b.optString("label")));
        for(JSONObject app:found)apps.put(app);
        return apps;
    }
    private ComponentName exportedDisplay(String pkg){
        try{PackageInfo info=getPackageManager().getPackageInfo(pkg,PackageManager.GET_ACTIVITIES);
            if(info.activities!=null)for(ActivityInfo a:info.activities)if(a.name.endsWith(".XServerDisplayActivity")&&a.exported&&a.enabled&&a.permission==null)return new ComponentName(pkg,a.name);
        }catch(Exception ignored){}return null;
    }
    private JSONObject serverInfo() throws Exception {
        JSONObject s=settings();if(s.optString("server").equals("simple"))return new JSONObject().put("server_name","Direct server").put("versions",bundledVersions).put("secure_apis_enabled",false);
        String ep=endpointFor(s);log("Checking server…");JSONObject info=HttpApi.getPublic(ep);JSONArray ids=supported(info),choices=new JSONArray();
        for(int i=0;i<ids.length();i++){String id=ids.getString(i);JSONObject v=bundled(id);choices.put(new JSONObject().put("uuid",id).put("name",v==null?id:v.optString("name",id)));}
        info.put("versions",choices);log("Server responded: "+info.optString("server_name","OpenFusion"));return info;
    }
    private JSONArray supported(JSONObject info) throws Exception {
        JSONArray ids=info.optJSONArray("game_versions");if(ids==null){ids=new JSONArray();String id=info.optString("game_version","");if(!id.isEmpty())ids.put(id);}
        if(ids.length()==0)throw new Exception("The server did not advertise a game build.");for(int i=0;i<ids.length();i++)UUID.fromString(ids.getString(i));return ids;
    }
    private JSONObject bundled(String id) throws Exception {for(int i=0;i<bundledVersions.length();i++){JSONObject v=bundledVersions.getJSONObject(i);if(id.equals(v.getString("uuid")))return v;}return null;}
    private JSONObject version(String ep,String id) throws Exception {
        UUID.fromString(id);JSONObject v=bundled(id);if(v!=null)return v;
        if(ep==null)throw new Exception("Select one of the included game builds.");
        try{v=HttpApi.get(ep+"/versions/"+id);}catch(Exception first){v=HttpApi.get(ep+"/versions/"+id+".json");}
        if(!id.equals(v.getString("uuid")))throw new Exception("The server returned a different game build.");return v;
    }
    private void copyAssetToStore(DocumentStore store,String assetPath,String target) throws Exception {
        try(InputStream in=getAssets().open(assetPath);OutputStream out=store.open(target)){
            byte[] buffer=new byte[65536];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
        }
    }
    private void cleanupOldControllerFilters(DocumentStore store) throws Exception {
        String[] xinput={"xinput1_3.dll","xinput1_4.dll","xinput9_1_0.dll"};
        String[] dirs={"","loader/","player/fusion-2.x.x/"};
        boolean oldXinput=store.exists("OF-XINPUT-BLOCKER.txt");
        boolean oldInput=store.exists("OF-INPUT-FILTER.txt");
        if(oldXinput||oldInput){
            for(String dir:dirs)for(String name:xinput)store.delete(dir+name);
            if(oldInput)for(String dir:dirs)store.delete(dir+"dinput8.dll");
            store.delete("OF-XINPUT-BLOCKER.txt");
            store.delete("OF-INPUT-FILTER.txt");
            log("Removed the older injected controller filter DLLs.");
        }
    }
    private JSONObject prepare(boolean open) throws Exception {
        JSONObject s=settings();DocumentStore store=documents();store.validateRunner();LaunchSpec.windowsFolder(s.getString("windowsFolder"));
        copyAssetToStore(store,"runner/"+LaunchSpec.RUNNER,LaunchSpec.RUNNER);
        cleanupOldControllerFilters(store);
        // Keep DXVK out of exclusive fullscreen; FFRunner handles Unity window promotion
        // until Present. The latter is specifically useful when old D3D9 software
        // destroys/recreates its presentation surface during mode changes. Do not
        // force a present interval here; 0.4.8's vsync override did not improve
        // Android browser-mode pacing on this title.
        store.write("openfusion-dxvk.conf","d3d9.enableDialogMode = True\n");
        store.write("FFRUNNER-CUSTOM-LICENSE.txt",asset("runner/LICENSE.txt"));
        String ep=null,id=s.optString("version"),address,name="FusionFall";JSONObject info=null;
        if(s.optString("server").equals("simple")){address=s.optString("address");if(id.isEmpty())throw new Exception("Select the build your direct server uses.");}
        else{
            ep=endpointFor(s);info=HttpApi.get(ep);JSONArray ids=supported(info);boolean found=false;
            for(int i=0;i<ids.length();i++)if(ids.getString(i).equals(id))found=true;
            if(!found)id=ids.getString(0);address=info.getString("login_address");name=info.optString("server_name",name);
        }
        JSONObject v=version(ep,id),spec=new JSONObject().put("version",id).put("address",LaunchSpec.resolveAddress(address)).put("name",name);
        String assets=LaunchSpec.webUrl(v.getString("asset_url")),main=LaunchSpec.webUrl(v.optString("main_file_url",assets.replaceAll("/+$","")+"/main.unity3d"));
        String refresh=null;
        if(ep!=null){
            spec.put("endpoint",ep).put("loader",info.optBoolean("custom_loading_screen"));refresh=token(ep);
            if(info.optBoolean("secure_apis_enabled",true)&&refresh==null)throw new Exception("Sign in before preparing this server.");
        }
        boolean proxyEnabled=s.optBoolean("proxy",true);
        if(proxyEnabled){
            // The official CDN supports HTTPS; Android performs TLS for the old Unity player.
            if(new java.net.URL(assets).getHost().equals("cdn.dexlabs.systems"))assets=assets.replaceFirst("^http:","https:");
            if(new java.net.URL(main).getHost().equals("cdn.dexlabs.systems"))main=main.replaceFirst("^http:","https:");
        }
        if(proxyEnabled||refresh!=null){
            AssetProxyService.ready=new CountDownLatch(1);AssetProxyService.address="";
            Intent service=new Intent(this,AssetProxyService.class).putExtra("assets",assets).putExtra("main",main)
                .putExtra("profile",LaunchSpec.profileKey(ep==null?"direct:"+s.optString("address"):ep,id));
            if(refresh!=null)service.putExtra("authEndpoint",ep).putExtra("refreshToken",refresh);
            startForegroundService(service);
            if(!AssetProxyService.ready.await(8,TimeUnit.SECONDS)||AssetProxyService.address.isEmpty())throw new Exception(AssetProxyService.lastError.isEmpty()?"Could not start the Android game bridge. Return to the launcher and try again.":AssetProxyService.lastError);
            String bridge=AssetProxyService.address;
            if(refresh!=null)spec.put("authUrl",bridge+"__auth");
            if(proxyEnabled){assets=bridge;main=bridge+"__main.unity3d";}
        }
        spec.put("assets",assets.replaceAll("/+$","")+"/").put("main",main);
        String script=LaunchSpec.script(spec,s);
        store.write("OpenFusion-Android.desktop",LaunchSpec.desktop(s.getString("windowsFolder")));
        store.write("Launch_OpenFusion.cmd",script);
        // Release launcher audio before a manually or automatically opened game
        // starts, including split-screen where onPause may not be delivered yet.
        FutureTask<Void> silence=new FutureTask<>(()->{if(audio!=null)audio.suspendForGame();return null;});
        runOnUiThread(silence);silence.get(5,TimeUnit.SECONDS);
        log("Launch prepared for "+LaunchSpec.RUNNER+" (1.9.0 stable baseline + auth hotfix + Unity log diagnostic). "+(s.optBoolean("borderless",false)?"Borderless window":"Windowed")+(s.optBoolean("controllerBridge",true)?"; controller → keyboard/mouse on":"; controller adapter off")+"; top-level Unity browser renderer test + controller keyboard/mouse mode; game log: ffrunner.log.");
        JSONObject result=new JSONObject().put("prepared",true).put("file","Launch_OpenFusion.cmd").put("version",v.optString("name",id)).put("opened",false);
        if(open){JSONObject launch=openWinlator(s,true);result.put("opened",launch.optBoolean("opened")).put("direct",launch.optBoolean("direct")).put("message",launch.optString("message"));}
        else result.put("message","Updated Launch_OpenFusion.cmd is ready and starts "+LaunchSpec.RUNNER+". Open it inside your container.");
        return result;
    }
    private JSONObject openWinlator(JSONObject settings,boolean prepared) throws Exception {
        String pkg=settings.optString("package");
        if(pkg.isEmpty()){JSONArray apps=runtimeApps();if(apps.length()==1)pkg=apps.getJSONObject(0).getString("package");}
        if(pkg.isEmpty())return new JSONObject().put("opened",false).put("message","Choose your Windows runtime app in Setup. The prepared launch file can also be opened manually inside the selected runtime.");
        final String selected=pkg;FutureTask<JSONObject> task=new FutureTask<>(()->{
            JSONObject out=new JSONObject().put("opened",false).put("direct",false);
            if(prepared&&settings.optBoolean("direct")){
                ComponentName component=exportedDisplay(selected);String path=documents().filesystemPath();
                if(component!=null&&!path.isEmpty())try{
                    startActivity(new Intent().setComponent(component).putExtra("container_id",settings.optInt("container",1)).putExtra("shortcut_path",path+"/OpenFusion-Android.desktop"));
                    log("Sent the launch request to the selected runtime. Game startup is not confirmed.");return out.put("opened",true).put("direct",true).put("message","Launch request sent to Winlator.");
                }catch(Exception e){log("Direct launch was unavailable. Opening the selected runtime instead.");}
            }
            Intent launch=getPackageManager().getLaunchIntentForPackage(selected);
            if(launch==null)return out.put("message","Cannot open that runtime app. Check the app selection in Setup.");
            try{startActivity(launch);return out.put("opened",true).put("message","In your Windows runtime, start the container and open Launch_OpenFusion.cmd in the selected OpenFusion folder.");}
            catch(Exception e){return out.put("message","Android could not open that runtime app. Open it manually and run Launch_OpenFusion.cmd.");}
        });runOnUiThread(task);return task.get(15,TimeUnit.SECONDS);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=100||folderRequest==null)return;String id=folderRequest;folderRequest=null;
        if(result!=RESULT_OK||data==null||data.getData()==null){reply(id,new JSONObject(),null);return;}
        Uri uri=data.getData();
        try{
            int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);getContentResolver().takePersistableUriPermission(uri,flags);
            DocumentStore store=new DocumentStore(this,uri.toString());String path=store.filesystemPath();
            String label=path.isEmpty()?android.provider.DocumentsContract.getTreeDocumentId(uri):path;
            prefs.edit().putString("tree",uri.toString()).putString("folderLabel",label).putBoolean("installed",false).apply();
            JSONObject s=settings();if(path.startsWith("/storage/emulated/0/Download/")){s.put("windowsFolder","D:\\"+path.substring("/storage/emulated/0/Download/".length()).replace('/', '\\'));prefs.edit().putString("settings",s.toString()).apply();}
            log("Game folder selected.");reply(id,new JSONObject().put("folder",label).put("windowsFolder",s.getString("windowsFolder")),null);
        }catch(Exception e){reply(id,null,"Could not keep permission to that folder. Choose a folder in internal storage, such as Download/OpenFusion.");}
    }
    @Override public void onBackPressed(){if(web!=null)web.evaluateJavascript("window.androidBack && window.androidBack()",null);else super.onBackPressed();}
    @Override protected void onResume(){super.onResume();if(web!=null)web.onResume();if(audio!=null)audio.resume();}
    @Override protected void onPause(){if(audio!=null)audio.pause();if(web!=null)web.onPause();super.onPause();}
    @Override public void onDestroy(){destroyed=true;work.shutdown();statusWork.shutdownNow();if(audio!=null)audio.release();if(web!=null){web.removeJavascriptInterface("Android");web.destroy();}super.onDestroy();}
}
