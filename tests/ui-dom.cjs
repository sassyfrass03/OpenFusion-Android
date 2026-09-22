const {JSDOM}=require('jsdom');
const fs=require('node:fs');
const assert=require('node:assert/strict');
const base=require('node:path').resolve(__dirname,'../app/src/main/assets/ui')+'/';
let settings={server:'custom',endpoint:'api.ffretribution.net',address:'',version:'',package:'com.winlator',windowsFolder:'D:\\OpenFusion',graphics:'dx9',width:960,height:540,fps:30,container:1,proxy:true,direct:false,borderless:false,muteMusic:false,muteUiSounds:false};
let signedIn=true,taps=0,calls=[],population=23,holdStatus=false,heldStatus=[];
const pause=()=>new Promise(resolve=>setTimeout(resolve,10));
function load(){
 const dom=new JSDOM(fs.readFileSync(base+'index.html','utf8'),{runScripts:'outside-only',url:'https://launcher.openfusion.invalid/'});
 const w=dom.window;w.scrollTo=()=>{};
 w.Android={uiTap(){taps++},setAudioMuted(music,effects){settings.muteMusic=music;settings.muteUiSounds=effects;},call(raw){
  const request=JSON.parse(raw);calls.push(request);let result={};
  if(request.action==='serverStatus'&&holdStatus){heldStatus.push(()=>w.nativeReply({id:request.id,result:{reachable:true,player_count:999}}));return;}
  switch(request.action){
   case 'state':result={settings:{...settings},versions:[{name:'Retro',uuid:'retro'}],folder:'Download/OpenFusion',installed:true,proxyRunning:false,logs:[],android:15,apps:[{label:'Winlator',package:'com.winlator',direct:true}],signedIn,username:'Sass'};break;
   case 'save':settings={...settings,...request.data};result=settings;break;
   case 'serverStatus':result={reachable:true,player_count:population};break;
   case 'serverInfo':result={versions:[{name:'Retro',uuid:'retro'}],server_name:'Retribution'};break;
   case 'prepare':result={message:'Launch prepared.'};break;
   case 'logout':signedIn=false;break;
  }
  queueMicrotask(()=>w.nativeReply({id:request.id,result}));
 }};
 w.eval(fs.readFileSync(base+'app.js','utf8'));
 return dom;
}
(async()=>{
 let dom=load();await pause();let d=dom.window.document;let by=id=>d.getElementById(id);
 assert.equal(by('playerCount').textContent,'23 players online');assert(by('playerDot').classList.contains('players'));
 assert.equal(by('signedAs').textContent,'Signed in as Sass');assert.equal(by('loginFields').hidden,true);
 d.querySelector('[data-tab="setup"]').click();assert.equal(by('setup').hidden,false);
 const prior=taps;by('muteMusic').parentElement.click();
 assert.equal(settings.muteMusic,true);assert.equal(settings.muteUiSounds,false);assert.equal(taps,prior+1);
 by('muteUiSounds').click();assert.equal(settings.muteUiSounds,true);
 const quiet=taps;by('save').click();await pause();assert.equal(taps,quiet);
 dom.window.close();dom=load();await pause();d=dom.window.document;
 assert.equal(by('muteMusic').checked,true);assert.equal(by('borderless').checked,false);assert.equal(by('muteUiSounds').checked,true);
 by('muteMusic').click();by('borderless').click();assert.equal(settings.muteMusic,false);assert.equal(by('borderless').checked,true);assert.equal(settings.muteUiSounds,true);by('save').click();await pause();assert.equal(settings.borderless,true);
 dom.window.close();dom=load();await pause();d=dom.window.document;assert.equal(by('borderless').checked,true);
 by('muteUiSounds').click();assert.equal(settings.muteUiSounds,false);
 const resumed=taps;d.querySelector('[data-tab="play"]').click();assert.equal(taps,resumed+1);
 by('refresh').click();await pause();assert.equal(by('serverStatus').textContent,'Retribution');
 population=0;by('refresh').click();await pause();assert.equal(by('playerCount').textContent,'0 players online');
 population=null;by('refresh').click();await pause();assert.equal(by('playerCount').textContent,'Players: unavailable');assert(!by('playerDot').classList.contains('players'));
 by('launch').click();await pause();assert(calls.some(x=>x.action==='prepare'&&x.data.open===true));
 assert.equal(by('launchResult').textContent,'Launch prepared.');
 by('logout').click();await pause();assert.equal(by('loginFields').hidden,false);
 assert.equal(by('accountStatus').textContent,'Signed out');
 const ids=[...d.querySelectorAll('[id]')].map(e=>e.id);assert.equal(ids.length,new Set(ids).size);
 dom.window.close();
 holdStatus=true;dom=load();await pause();d=dom.window.document;
 by('endpoint').value='another.example.com';by('endpoint').dispatchEvent(new dom.window.Event('input'));
 for(const respond of heldStatus)respond();await pause();
 assert.equal(by('playerCount').textContent,'Players: —','ignore a late count from the previous endpoint');
 holdStatus=false;population=12;by('refresh').click();await pause();assert.equal(by('playerCount').textContent,'12 players online');
 by('bootScreen').click();assert.equal(by('bootScreen').hidden,true,'startup can be skipped');
 dom.window.close();
 const result={passed:true,environment:'jsdom with a simulated Android bridge; no visual rendering or device audio playback',checks:['hydration and signed-in/out controls','Play/Setup tab navigation','one tap request per label activation','independent audio preferences saved immediately','mute flags restored on reload','muted taps suppressed and unmuted taps resumed','server check and launch bridge actions retained','native borderless setting saved and restored','player count including real zero and unavailable','late server results discarded','skippable startup','unique element IDs']};
 console.log(JSON.stringify(result,null,2));
})().catch(e=>{console.error(e);process.exitCode=1});
