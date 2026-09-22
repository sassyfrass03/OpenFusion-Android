"""Exercise actual LauncherAudio with fake Android services (no audio hardware)."""
import argparse, subprocess, tempfile
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--ecj',required=True,type=Path);options=parser.parse_args()
root=Path(__file__).resolve().parents[1]
stubs={
'android/content/SharedPreferences.java':'''package android.content;
public interface SharedPreferences { boolean getBoolean(String k,boolean d); Editor edit(); interface Editor { Editor putBoolean(String k,boolean v); void apply(); } }''',
'android/content/Context.java':'''package android.content;
public class Context { public static final String AUDIO_SERVICE="audio"; public android.media.AudioManager manager=new android.media.AudioManager(); public Object getSystemService(String s){return manager;} public android.content.res.AssetManager getAssets(){return new android.content.res.AssetManager();} }''',
'android/content/res/AssetManager.java':'''package android.content.res; public class AssetManager { public AssetFileDescriptor openFd(String n){return new AssetFileDescriptor();} }''',
'android/content/res/AssetFileDescriptor.java':'''package android.content.res; public class AssetFileDescriptor implements AutoCloseable { public java.io.FileDescriptor getFileDescriptor(){return new java.io.FileDescriptor();} public long getStartOffset(){return 0;} public long getLength(){return 1;} public void close(){} }''',
'android/os/Handler.java':'''package android.os; public class Handler { public Handler(Looper l){} }''',
'android/os/Looper.java':'''package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }''',
'android/os/SystemClock.java':'''package android.os; public class SystemClock { private static long t; public static long uptimeMillis(){return t+=100;} }''',
'android/media/AudioAttributes.java':'''package android.media; public class AudioAttributes { public static final int USAGE_GAME=14,CONTENT_TYPE_MUSIC=2,CONTENT_TYPE_SONIFICATION=4; public static class Builder { public Builder setUsage(int x){return this;} public Builder setContentType(int x){return this;} public AudioAttributes build(){return new AudioAttributes();} } }''',
'android/media/AudioFocusRequest.java':'''package android.media; public class AudioFocusRequest { public int gain; public AudioManager.OnAudioFocusChangeListener listener; public static class Builder { private AudioFocusRequest r=new AudioFocusRequest(); public Builder(int gain){r.gain=gain;} public Builder setAudioAttributes(AudioAttributes a){return this;} public Builder setWillPauseWhenDucked(boolean b){return this;} public Builder setOnAudioFocusChangeListener(AudioManager.OnAudioFocusChangeListener l,android.os.Handler h){r.listener=l;return this;} public AudioFocusRequest build(){return r;} } }''',
'android/media/AudioManager.java':'''package android.media; public class AudioManager { public static final int AUDIOFOCUS_GAIN=1,AUDIOFOCUS_GAIN_TRANSIENT=2,AUDIOFOCUS_REQUEST_GRANTED=1; public int requests,abandons; public AudioFocusRequest last; public interface OnAudioFocusChangeListener { void onAudioFocusChange(int n); } public int requestAudioFocus(AudioFocusRequest r){last=r;requests++;return 1;} public int abandonAudioFocusRequest(AudioFocusRequest r){abandons++;return 1;} }''',
'android/media/MediaPlayer.java':'''package android.media; public class MediaPlayer { public static MediaPlayer last; public boolean playing,released; private OnPreparedListener listener; public interface OnPreparedListener{void onPrepared(MediaPlayer p);} public interface OnErrorListener{boolean onError(MediaPlayer p,int a,int b);} public MediaPlayer(){last=this;} public void setAudioAttributes(AudioAttributes a){} public void setDataSource(java.io.FileDescriptor f,long a,long b){} public void setLooping(boolean b){} public void setVolume(float a,float b){} public void setOnPreparedListener(OnPreparedListener l){listener=l;} public void setOnErrorListener(OnErrorListener l){} public void prepareAsync(){} public void completePreparation(){listener.onPrepared(this);} public void start(){if(released)throw new AssertionError();playing=true;} public void pause(){playing=false;} public boolean isPlaying(){return playing;} public void release(){released=true;playing=false;} }''',
'android/media/SoundPool.java':'''package android.media; public class SoundPool { public static int plays; public interface OnLoadCompleteListener{void onLoadComplete(SoundPool p,int sample,int status);} public void setOnLoadCompleteListener(OnLoadCompleteListener l){l.onLoadComplete(this,1,0);} public int load(android.content.res.AssetFileDescriptor f,int p){return 1;} public int play(int a,float b,float c,int d,int e,float f){plays++;return 1;} public void autoPause(){} public void release(){} public static class Builder{public Builder setMaxStreams(int n){return this;}public Builder setAudioAttributes(AudioAttributes a){return this;}public SoundPool build(){return new SoundPool();}} }''',
'AudioRegression.java':'''import dev.openfusion.launcher.LauncherAudio; import android.media.*;
public class AudioRegression {
 static class Prefs implements android.content.SharedPreferences,android.content.SharedPreferences.Editor {
 java.util.Map<String,Boolean> p=new java.util.HashMap<>(); public boolean getBoolean(String k,boolean d){return p.containsKey(k)?p.get(k):d;} public android.content.SharedPreferences.Editor edit(){return this;} public android.content.SharedPreferences.Editor putBoolean(String k,boolean v){p.put(k,v);return this;} public void apply(){} }
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
 android.content.Context c=new android.content.Context(); LauncherAudio a=new LauncherAudio(c,new Prefs());
 a.resume();a.suspendForGame();MediaPlayer.last.completePreparation();
 check(!MediaPlayer.last.playing && c.manager.requests==0); // late preparation cannot steal focus
 a.resume();check(MediaPlayer.last.playing && c.manager.requests==1);
 check(c.manager.last.gain==AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
 a.suspendForGame();check(!MediaPlayer.last.playing && c.manager.abandons>0);
 int n=SoundPool.plays;a.tap();check(SoundPool.plays==n);
 c.manager.last.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);check(!MediaPlayer.last.playing);
 a.resume();check(MediaPlayer.last.playing && c.manager.requests==2); // stale gain didn't bypass request
 a.pause();c.manager.last.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);check(!MediaPlayer.last.playing);
 a.setMuted(true,false);a.resume();check(!MediaPlayer.last.playing);
 a.setMuted(false,false);check(MediaPlayer.last.playing);a.tap();check(SoundPool.plays==n+1);
 a.release();c.manager.last.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);check(MediaPlayer.last.released&&!MediaPlayer.last.playing);
 System.out.println("PASS: actual LauncherAudio handoff, late preparation/focus callbacks, pause, mute, resume and release using Android stubs.");
 }}'''
}
with tempfile.TemporaryDirectory() as td:
    td=Path(td);sources=[]
    for name,source in stubs.items():
        p=td/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source);sources.append(str(p))
    subprocess.run(['java','-jar',str(options.ecj.resolve()),'-8','-proc:none','-d',str(td/'classes'),*sources,str(root/'app/src/main/java/dev/openfusion/launcher/LauncherAudio.java')],check=True)
    subprocess.run(['java','-cp',str(td/'classes'),'AudioRegression'],check=True)
