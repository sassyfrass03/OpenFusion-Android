'use strict';
const $=id=>document.getElementById(id);
const pending=new Map();let sequence=0,working=false,appState=null,toastTimer;
function rpc(action,data={}){
  return new Promise((resolve,reject)=>{
    if(!window.Android||typeof Android.call!=='function'){reject(new Error('Open this launcher inside the Android APK.'));return;}
    const id=String(++sequence);pending.set(id,{resolve,reject});
    try{Android.call(JSON.stringify({id,action,data}));}catch(e){pending.delete(id);reject(e);}
  });
}
window.nativeReply=payload=>{
  if(payload.event==='log'){
    const log=$('log');if(log.textContent==='Starting launcher…')log.textContent='';
    log.textContent+=(log.textContent?'\n':'')+payload.message;log.scrollTop=log.scrollHeight;
    if(working)$('busyText').textContent=payload.message.replace(/^\d\d:\d\d:\d\d\s+/,'');return;
  }
  const p=pending.get(payload.id);if(!p)return;pending.delete(payload.id);
  if(payload.error)p.reject(new Error(payload.error));else p.resolve(payload.result);
};
function toast(message,error=false){clearTimeout(toastTimer);$('notice').textContent=message;$('notice').classList.toggle('error',error);$('notice').hidden=false;toastTimer=setTimeout(()=>$('notice').hidden=true,error?14000:7000);}
function tab(name){document.querySelectorAll('.page').forEach(x=>x.hidden=x.id!==name);document.querySelectorAll('.tab').forEach(x=>{const selected=x.dataset.tab===name;x.classList.toggle('active',selected);x.setAttribute('aria-selected',String(selected));});window.scrollTo(0,0);document.querySelector('main').scrollTop=0;}
window.androidBack=()=>{tab('play');};
async function task(label,fn){
  if(working)return;working=true;$('busyText').textContent=label;$('busy').hidden=false;$('notice').hidden=true;
  document.querySelectorAll('button:not(#bootScreen),input,select,summary').forEach(e=>e.disabled=true);
  try{await fn();}catch(e){toast(e.message||String(e),true);}finally{working=false;$('busy').hidden=true;document.querySelectorAll('button:not(#bootScreen),input,select,summary').forEach(e=>e.disabled=false);}
}
const textFields=['server','endpoint','address','version','package','windowsFolder','graphics'];
const numberFields=['width','height','fps','container'];
const checkFields=['proxy','direct','borderless','controllerBridge','muteMusic','muteUiSounds'];
function config(){const s={};for(const k of textFields)s[k]=$(k).value.trim();for(const k of numberFields)s[k]=Number($(k).value);for(const k of checkFields)s[k]=$(k).checked;return s;}
async function save(){const s=config();await rpc('save',s);if(appState)appState.settings=s;updateSettingsLabels();return s;}
function updateSettingsLabels(){
  $('resolutionLabel').textContent=`${$('borderless').checked?'Borderless · ':''}${$('width').value} × ${$('height').value} · ${$('fps').value} FPS cap`;
  $('shortcutArgs').textContent=`/d /c "${$('windowsFolder').value.replace(/[\\/]+$/,'')}\\Launch_OpenFusion.cmd"`;
  $('containerRow').hidden=!$('direct').checked;
  const selected=(appState?.apps||[]).find(a=>a.package===$('package').value);
  $('directSupport').textContent=selected?.direct?'This build exposes the reference launch activity. Direct startup is experimental; confirm the container ID.':'This build does not expose the reference launch activity, or has not been detected. Use the launch file inside the selected runtime.';
}
function updateServerFields(){
  const s=$('server').value;$('customEndpoint').hidden=s!=='custom';$('directAddress').hidden=s!=='simple';$('account').hidden=s==='simple';
}
function setAccount(signed,user=''){
  $('loginFields').hidden=signed;$('signedIn').hidden=!signed;$('accountStatus').textContent=signed?'Signed in':'Signed out';$('accountStatus').classList.toggle('is-signed-in',signed);$('signedAs').textContent='Signed in as '+user;
  if(user)$('username').value=user;
}
function versions(items,selected='',automatic=true){
  $('version').replaceChildren();if(automatic)$('version').add(new Option("Automatic · use the server's build",''));
  for(const v of items)$('version').add(new Option(v.name||v.uuid,v.uuid));
  if([...$('version').options].some(x=>x.value===selected))$('version').value=selected;
}
function renderApps(apps){
  $('runtime').replaceChildren(new Option(apps.length?'Select an installed app':'No compatible runtime app detected',''));
  for(const a of apps)$('runtime').add(new Option(a.label,a.package));
  if(apps.some(a=>a.package===$('package').value))$('runtime').value=$('package').value;
  else if(apps.length===1&&!$('package').value){$('runtime').value=apps[0].package;$('package').value=apps[0].package;}
  updateSettingsLabels();
}
async function hydrate(){
  appState=await rpc('state');const s=appState.settings;versions(appState.versions,s.version,s.server!=='simple');
  for(const k of textFields)if(k!=='version'&&s[k]!==undefined)$(k).value=s[k];for(const k of numberFields)if(s[k]!==undefined)$(k).value=s[k];for(const k of checkFields)if(s[k]!==undefined)$(k).checked=s[k];
  $('folder').textContent=appState.folder||'No folder selected';$('setupNote').hidden=!!appState.folder&&!!appState.installed;
  $('runnerStatus').textContent=appState.installed?'Runner installed. Files are checked again before launch.':'Existing complete portable installations can also be used.';
  $('proxyStatus').textContent=(appState.proxyRunning?'Game download service active.':'Game download service inactive.')+(appState.proxyError?' '+appState.proxyError:'');
  $('log').textContent=appState.logs.length?appState.logs.join('\n'):`OpenFusion Android 0.4.11-stable-auth-logdiag\nAndroid ${appState.android}\nLauncher ready.`;
  renderApps(appState.apps);setAccount(appState.signedIn,appState.username);updateServerFields();updateSettingsLabels();
}
document.querySelectorAll('[data-tab]').forEach(b=>b.addEventListener('click',()=>tab(b.dataset.tab)));
document.querySelectorAll('[data-goto]').forEach(b=>b.addEventListener('click',()=>tab(b.dataset.goto)));
let statusRequest=false,statusEpoch=0,statusTimer,lastServerName='';
const serverKey=()=>JSON.stringify([$('server').value,$('endpoint').value.trim(),$('address').value.trim()]);
function resetServerStatus(){
  statusEpoch++;lastServerName='';$('serverDot').className='dot';$('serverStatus').textContent='Server not checked';
  $('playerDot').className='dot';$('playerCount').textContent='Players: —';$('playerIndicator').title='Current players reported by the selected server';
}
function showStatus(info){
  const count=info.player_count;
  const valid=info.reachable && typeof count==='number' && Number.isSafeInteger(count) && count>=0;
  $('serverDot').className='dot'+(info.direct?'':info.reachable?' online':' offline');
  $('serverStatus').textContent=info.direct?'Direct server · status unavailable':info.reachable?(info.server_name||lastServerName||'Server online'):'Server unreachable';
  $('playerDot').className='dot'+(valid?' players':'');
  $('playerCount').textContent=valid?`${count.toLocaleString()} ${count===1?'player':'players'} online`:'Players: unavailable';
  $('playerIndicator').title=valid?'Updated '+new Date().toLocaleTimeString():'The server did not provide a current player count.';
}
async function refreshStatus(force=false){
  if(statusRequest||(!force&&(working||document.hidden)))return;
  const key=serverKey(),epoch=statusEpoch;statusRequest=true;
  try{const info=await rpc('serverStatus',{server:$('server').value,endpoint:$('endpoint').value.trim()});
    if(key===serverKey()&&epoch===statusEpoch)showStatus(info);
  }catch(e){if(key===serverKey()&&epoch===statusEpoch)showStatus({reachable:false});}
  finally{statusRequest=false;if(key!==serverKey()||epoch!==statusEpoch){if(!document.hidden)void refreshStatus();}}
}
function startStatusPolling(){clearInterval(statusTimer);statusTimer=setInterval(()=>void refreshStatus(),30000);}
document.addEventListener('visibilitychange',()=>{
  if(document.hidden){clearInterval(statusTimer);statusEpoch++;}
  else{resetServerStatus();void refreshStatus();startStatusPolling();}
});
window.addEventListener('pagehide',()=>clearInterval(statusTimer));
$('endpoint').addEventListener('input',resetServerStatus);$('address').addEventListener('input',resetServerStatus);
$('server').addEventListener('change',()=>{resetServerStatus();void task('Changing server…',async()=>{
  $('version').value='';$('password').value='';await save();await hydrate();void refreshStatus(true);
});});
$('endpoint').addEventListener('change',()=>task('Saving server…',async()=>{resetServerStatus();await save();setAccount(false);$('password').value='';void refreshStatus(true);}));
$('refresh').addEventListener('click',()=>task('Checking server…',async()=>{
  await save();$('serverDot').className='dot';$('serverStatus').textContent='Checking…';
  try{const info=await rpc('serverInfo');versions(info.versions,$('version').value,$('server').value!=='simple');lastServerName=info.server_name||'Server online';
    $('serverStatus').textContent=lastServerName;await refreshStatus(true);}
  catch(e){showStatus({reachable:false});throw e;}
}));
$('login').addEventListener('click',()=>task('Signing in…',async()=>{
  await save();const password=$('password').value;$('password').value='';const r=await rpc('login',{username:$('username').value.trim(),password,remember:$('remember').checked});setAccount(true,r.username);toast('Signed in.');
}));
$('register').addEventListener('click',()=>task('Registering account…',async()=>{
  await save();const password=$('password').value;$('password').value='';await rpc('register',{username:$('username').value.trim(),password,email:$('email').value.trim()});toast('Registration accepted. Check your email if required, then sign in.');
}));
$('logout').addEventListener('click',()=>task('Signing out…',async()=>{await save();await rpc('logout');setAccount(false);$('launchResult').hidden=true;}));
$('chooseFolder').addEventListener('click',()=>task('Choose a game folder…',async()=>{await save();const r=await rpc('chooseFolder');if(r.folder){$('folder').textContent=r.folder;$('windowsFolder').value=r.windowsFolder;appState.folder=r.folder;$('runnerStatus').textContent='Install or check the runner in this folder.';updateSettingsLabels();}}));
$('install').addEventListener('click',()=>task('Installing runner…',async()=>{await save();await rpc('install');$('runnerStatus').textContent='Runner installed and checked.';$('setupNote').hidden=true;toast('Runner ready. Choose your Winlator app below.');}));
$('checkRunner').addEventListener('click',()=>task('Checking runner files…',async()=>{await rpc('checkRunner');$('runnerStatus').textContent='Required runner files found.';$('setupNote').hidden=true;toast('Runner files look complete.');}));
$('runtime').addEventListener('change',()=>{$('package').value=$('runtime').value;updateSettingsLabels();});
$('borderless').addEventListener('change',updateSettingsLabels);
$('package').addEventListener('change',updateSettingsLabels);$('direct').addEventListener('change',updateSettingsLabels);
$('rescan').addEventListener('click',()=>task('Looking for Windows runtimes…',async()=>{appState.apps=(await rpc('state')).apps;renderApps(appState.apps);toast(appState.apps.length+' compatible app(s) detected.');}));
$('save').addEventListener('click',()=>task('Saving settings…',async()=>{await save();toast('Settings saved.');}));
$('openWinlator').addEventListener('click',()=>task('Opening Winlator…',async()=>{await save();const r=await rpc('openWinlator');toast(r.opened?'Runtime opened.':r.message,!r.opened);}));
async function launch(open){
  await save();$('launchResult').hidden=true;if($('proxy').checked)await rpc('notifyPermission');
  const result=await rpc('prepare',{open});$('launchResult').hidden=false;$('launchResult').textContent=result.message||'Launch prepared.';
  $('proxyStatus').textContent=$('proxy').checked?'Game download service active. Keep this app running.':'Game download service inactive.';
}
$('launch').addEventListener('click',()=>task('Preparing FusionFall…',()=>launch(true)));
$('prepareOnly').addEventListener('click',()=>task('Preparing launch file…',()=>launch(false)));
$('copyLog').addEventListener('click',()=>task('Copying diagnostics…',async()=>{await rpc('copyLog');toast('Launcher diagnostics copied.');}));
$('stopProxy').addEventListener('click',()=>task('Stopping game downloads…',async()=>{await rpc('stopProxy');$('proxyStatus').textContent='Game download service stopped.';toast('Downloads stopped. Prepare again before playing.');}));
$('website').addEventListener('click',()=>task('Opening website…',()=>rpc('website')));
// Capture before handlers disable a control for work. Label activation generates one
// input click; listening only to controls avoids playing the effect twice.
document.addEventListener('click',event=>{
  const control=event.target.closest('button,input,select,summary,a[href],[role="button"]');
  if(!control||control.disabled||working||$('muteUiSounds').checked)return;
  if(window.Android&&typeof Android.uiTap==='function')Android.uiTap();
},true);
for(const key of ['muteMusic','muteUiSounds'])$(key).addEventListener('change',()=>{
  const music=$('muteMusic').checked,effects=$('muteUiSounds').checked;
  if(window.Android&&typeof Android.setAudioMuted==='function'){
    Android.setAudioMuted(music,effects);
    if(appState){appState.settings.muteMusic=music;appState.settings.muteUiSounds=effects;}
    $('audioStatus').textContent='Saved. Music '+(music?'off':'on')+' · UI sounds '+(effects?'off':'on')+'.';
  }else $('audioStatus').textContent='Audio playback is available in the Android app.';
});
const boot=$('bootScreen');
function finishBoot(){boot.hidden=true;}
boot.addEventListener('click',finishBoot);
if(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches)finishBoot();
else setTimeout(finishBoot,1900);
task('Loading launcher…',async()=>{await hydrate();void refreshStatus(true);startStatusPolling();});
