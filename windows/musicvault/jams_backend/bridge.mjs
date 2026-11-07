import {readFileSync, writeFileSync, mkdirSync, renameSync, chmodSync} from 'node:fs';
import {join} from 'node:path';
import {randomUUID, randomInt} from 'node:crypto';
import {createInterface} from 'node:readline';
import {initializeApp} from 'firebase/app';
import {initializeAuth, signInAnonymously} from 'firebase/auth';
import {getDatabase, ref, set, get, update, remove, onValue, onDisconnect, runTransaction, serverTimestamp, goOffline} from 'firebase/database';

// JSON lines are the only stdout protocol. Authentication material never leaves this process.
const emit = (event, data) => process.stdout.write(JSON.stringify({event, data})+'\n');
const config = JSON.parse(readFileSync(new URL('./firebase-client.json', import.meta.url)));
const app = initializeApp(config), db = getDatabase(app);
const folder = process.argv[2];
mkdirSync(folder, {recursive:true, mode:0o700});
const identityFile = join(folder, 'identity.json');
let identity;
try { identity = JSON.parse(readFileSync(identityFile)); } catch { identity = {}; }
if (!/^[a-f0-9-]{36}$/.test(identity.installationUid || '')) identity.installationUid = randomUUID();
function save() {
  writeFileSync(identityFile+'.tmp', JSON.stringify(identity), {mode:0o600});
  renameSync(identityFile+'.tmp', identityFile); chmodSync(identityFile, 0o600);
}
save();
let uid, room = null, roomData = null, unsubscribe = null, offset = 0, presence = [];
const now = () => Date.now()+offset;
const path = suffix => ref(db, `rooms/${room}/${suffix}`);
function parseLink(raw, kind='jam') {
  if (kind==='jam' && /^[a-f0-9]{32}$/.test(raw)) return raw;
  const u = new URL(raw);
  if (u.username || u.password || u.port || u.search || u.hash) throw Error('Invitación inválida');
  const parts = u.protocol==='https:' && u.hostname==='musicvault-jams-donatelatrump.web.app'
    ? u.pathname.split('/').filter(Boolean) : u.protocol==='musicvault:' ? [u.hostname,...u.pathname.split('/').filter(Boolean)] : [];
  if (parts.length!==2 || parts[0]!==kind || !/^[a-f0-9]{32}$/.test(parts[1])) throw Error('Invitación inválida');
  return parts[1];
}
class FilePersistence {
  constructor() {this.type='LOCAL';}
  async _isAvailable() {return true;}
  async _get(key) {return identity.sessions?.[key] ?? null;}
  async _set(key,value) {identity.sessions ??= {};identity.sessions[key]=value;save();}
  async _remove(key) {delete identity.sessions?.[key];save();}
  _addListener() {}
  _removeListener() {}
}
const auth = initializeAuth(app,{persistence:FilePersistence});
async function login() {
  await auth.authStateReady();
  if(!auth.currentUser)await signInAnonymously(auth);
  uid=auth.currentUser.uid;
  await set(ref(db,`installations/${uid}`),{installationUid:identity.installationUid});
  onValue(ref(db,'.info/serverTimeOffset'),s=>{offset=s.val()||0;});
  onValue(ref(db,'.info/connected'),async s=>{
    emit('connected',s.val()===true);
    if(s.val() && room) try {await registerPresence();} catch(e){emit('error',e.message);}
  });
  for(const name of ['friends','friendRequests','roomInvites'])
    onValue(ref(db,`${name}/${uid}`),s=>emit(name,s.val()||{}),e=>emit('error',e.message));
  emit('identity',{uid,installationUid:identity.installationUid});
}
async function registerPresence() {
  if(!room)return;
  await onDisconnect(path(`members/${uid}`)).set(false);
  await onDisconnect(path(`ready/${uid}`)).remove();
  await set(path(`members/${uid}`),true);
}
async function enter(code) {
  if(room)throw Error('Sal de la sala actual primero.');
  await set(ref(db,`rooms/${code}/members/${uid}`),true);
  room=code; await registerPresence();
  unsubscribe=onValue(ref(db,`rooms/${code}`),s=>{
    roomData=s.val(); emit('room',{code, value:roomData, serverNow:now()});
  },e=>{unsubscribe?.();room=null;roomData=null;emit('room',null);emit('error','La sala se cerr? o caduc?.');});
}
async function leave() {
  if(!room)return;
  await remove(path(`ready/${uid}`)); await set(path(`members/${uid}`),false);
  await onDisconnect(path(`members/${uid}`)).cancel(); await onDisconnect(path(`ready/${uid}`)).cancel();
  unsubscribe?.(); room=null;roomData=null;emit('room',null);
}
async function playback(changes, expected) {
  if(!room)throw Error('Entra a una sala.');
  await runTransaction(path('playback'),p=>{
    if(!p || (expected!==undefined && p.revision!==expected))return;
    return {...p,...changes,effectiveAt:now()+(changes.action==='ready'?180:0),revision:p.revision+1,commandId:randomUUID()};
  },{applyLocally:false});
}
async function command(c) {
  switch(c.action) {
    case 'create': {
      if(room)throw Error('Sal de la sala actual primero.');
      for(let i=0;i<8;i++) {
        const code=randomUUID().replaceAll('-',''), short=String(randomInt(1000000)).padStart(6,'0'), expiresAt=now()+86340000;
        await set(ref(db,`rooms/${code}`),{meta:{hostUid:uid,expiresAt,shortCode:short,protocolVersion:2},members:{[uid]:true},playback:{songKey:'',playing:false,positionMs:0,effectiveAt:now(),revision:0}});
        try {await set(ref(db,`roomCodes/${short}`),{roomId:code,ownerUid:uid,expiresAt});}
        catch(e) {await remove(ref(db,`rooms/${code}`));continue;}
        await enter(code);return;
      } throw Error('No se pudo reservar código.');
    }
    case 'join': {
      let code=c.value.trim();
      if(/^\d{6}$/.test(code)) {
        await set(ref(db,`roomLookups/${uid}`),{code,requestedAt:serverTimestamp()});
        code=(await get(ref(db,`roomCodes/${code}`))).val()?.roomId;
        if(!code)throw Error('Código no disponible.');
      } else code=parseLink(code);
      return enter(code);
    }
    case 'leave': return leave();
    case 'close': {
      if(roomData?.meta.hostUid!==uid)throw Error('Solo el creador puede cerrar la sala.');
      await remove(ref(db,`roomCodes/${roomData.meta.shortCode}`));
      unsubscribe?.();
      await remove(ref(db,`rooms/${room}`)); room=null;roomData=null;emit('room',null);return;
    }
    case 'add': {
      if(!room)throw Error('Entra a una sala.');
      if(Object.keys(roomData?.queue||{}).length>=100)throw Error('La cola admite 100 canciones.');
      const key=String(now()).padStart(13,'0')+randomUUID().replaceAll('-','');
      await set(path(`queue/${key}`),{...c.song,addedBy:uid});
      if(c.play)await playback({songKey:key,playing:true,positionMs:0,buffering:true,action:'select'});
      return;
    }
    case 'playback': return playback(c.value,c.expected);
    case 'ready': return set(path(`ready/${uid}`),c.key);
    case 'removeSong': {
      if(roomData?.playback.songKey!==c.key)return remove(path(`queue/${c.key}`));
      const keys=Object.keys(roomData.queue||{}).sort(), index=keys.indexOf(c.key);
      const next=keys[index+1] || keys.find(k=>k!==c.key) || '';
      return update(ref(db,`rooms/${room}`),{[`queue/${c.key}`]:null,playback:{
        songKey:next,playing:!!next,buffering:!!next,positionMs:0,effectiveAt:now(),
        revision:roomData.playback.revision+1,commandId:randomUUID(),action:'select'}});
    }
    case 'friendLink': {
      const code=randomUUID().replaceAll('-','');
      await set(ref(db,`friendInvites/${code}`),{ownerUid:uid,expiresAt:now()+6*86400000});
      emit('link',`https://musicvault-jams-donatelatrump.web.app/friend/${code}`);return;
    }
    case 'request': {
      const code=parseLink(c.value,'friend');
      const target=(await get(ref(db,`friendInvites/${code}`))).val()?.ownerUid;
      if(!target || target===uid)throw Error('Invitación de amistad inválida.');
      return set(ref(db,`friendRequests/${target}/${uid}`),{inviteCode:code,createdAt:serverTimestamp()});
    }
    case 'requestMember': return set(ref(db,`friendRequests/${c.uid}/${uid}`),{roomCode:room,createdAt:serverTimestamp()});
    case 'accept': return update(ref(db),{[`friends/${uid}/${c.uid}`]:true,[`friends/${c.uid}/${uid}`]:true,[`friendRequests/${uid}/${c.uid}`]:null});
    case 'reject': return remove(ref(db,`friendRequests/${uid}/${c.uid}`));
    case 'removeFriend': return update(ref(db),{[`friends/${uid}/${c.uid}`]:null,[`friends/${c.uid}/${uid}`]:null});
    case 'invite': return set(ref(db,`roomInvites/${c.uid}/${uid}`),{roomCode:room,createdAt:serverTimestamp()});
    case 'dismiss': return remove(ref(db,`roomInvites/${uid}/${c.uid}`));
    case 'shutdown': await leave();goOffline(db);process.exit(0);
    default: throw Error('Acción desconocida.');
  }
}
let chain=login();
chain.catch(e=>emit('error',e.message));
createInterface({input:process.stdin}).on('line',line=>{
  chain=chain.then(()=>command(JSON.parse(line))).catch(e=>emit('error',e.message));
});
process.on('SIGTERM',()=>leave().finally(()=>process.exit()));
