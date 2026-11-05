// Explicit visual-test peer. Creates temporary invitations, auto-accepts the test peer,
// and removes its room/contact data when /tmp/musicvault-jams-ui-stop is created.
import {readFileSync,writeFileSync,existsSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';
import {initializeApp,deleteApp} from 'firebase/app';
import {getAuth,signInAnonymously,deleteUser} from 'firebase/auth';
import {getDatabase,ref,set,get,update,remove,onValue,goOffline,runTransaction} from 'firebase/database';
if (!process.argv.includes('--live')) throw new Error('Pass --live for the disposable Firebase peer');
const config=JSON.parse(readFileSync(new URL('../../android/app/google-services.json',import.meta.url)));
const client=config.client.find(c=>c.client_info.android_client_info.package_name==='com.musicvault.app');
const app=initializeApp({apiKey:client.api_key[0].current_key,appId:client.client_info.mobilesdk_app_id,projectId:config.project_info.project_id,databaseURL:config.project_info.firebase_url},'visual-'+randomUUID());
const db=getDatabase(app),user=(await signInAnonymously(getAuth(app))).user;
const room=randomUUID().replaceAll('-',''),invite=randomUUID().replaceAll('-','');
const short=String(Math.floor(Math.random()*1_000_000)).padStart(6,'0');
const expiry=Date.now()+3600000;
const peers=new Set();
let playbackUnsubscribe=()=>{};
let unsubscribe=()=>{};
try {
 await set(ref(db,`rooms/${room}`),{meta:{hostUid:user.uid,expiresAt:expiry,shortCode:short,protocolVersion:2},members:{[user.uid]:true},playback:{songKey:'',playing:false,positionMs:0,effectiveAt:Date.now(),revision:0}});
 await set(ref(db,`roomCodes/${short}`),{roomId:room,ownerUid:user.uid,expiresAt:expiry});
 playbackUnsubscribe=onValue(ref(db,`rooms/${room}`),async snapshot=>{
  const state=snapshot.val(),p=state?.playback;if(!p?.songKey)return;
  if(state.ready?.[user.uid]!==p.songKey)await set(ref(db,`rooms/${room}/ready/${user.uid}`),p.songKey);
  console.log(`Peer observed ${p.action||"state"}, playing=${p.playing}, position=${p.positionMs}`);
  const members=Object.keys(state.members||{}).filter(uid=>state.members[uid]).sort();
  if(p.buffering&&members[0]===user.uid&&members.every(uid=>state.ready?.[uid]===p.songKey))
   await runTransaction(ref(db,`rooms/${room}/playback`),current=>current?.revision===p.revision?{...current,buffering:false,effectiveAt:Date.now()+180,revision:current.revision+1,commandId:randomUUID(),action:'ready'}:undefined,{applyLocally:false});
 });
 await set(ref(db,`friendInvites/${invite}`),{ownerUid:user.uid,expiresAt:Date.now()+86400000});
 writeFileSync('/tmp/musicvault-jams-ui-links.json',JSON.stringify({shortCode:short,roomUrl:`https://musicvault-jams-donatelatrump.web.app/jam/${room}`,friendUrl:`https://musicvault-jams-donatelatrump.web.app/friend/${invite}`}));
 unsubscribe=onValue(ref(db,`friendRequests/${user.uid}`),async snapshot=>{
  for(const child of Object.values(snapshot.val()||{})) if(!child.inviteCode) return;
  for(const peer of Object.keys(snapshot.val()||{})) {
   if(peers.has(peer)) continue;peers.add(peer);
   await update(ref(db),{[`friends/${user.uid}/${peer}`]:true,[`friends/${peer}/${user.uid}`]:true,[`friendRequests/${user.uid}/${peer}`]:null});
   await set(ref(db,`roomInvites/${peer}/${user.uid}`),{roomCode:room,createdAt:Date.now()});
   console.log('Visual test peer accepted contact and sent a room invitation');
  }
 });
 console.log('Disposable room and friend invitations ready');
 const until=Date.now()+15*60*1000;
 while(!existsSync('/tmp/musicvault-jams-ui-stop') && Date.now()<until) await delay(500);
} finally {
 unsubscribe();playbackUnsubscribe();
 for(const peer of peers) await update(ref(db),{[`friends/${user.uid}/${peer}`]:null,[`friends/${peer}/${user.uid}`]:null,[`roomInvites/${peer}/${user.uid}`]:null,[`friendRequests/${user.uid}/${peer}`]:null});
 await remove(ref(db,`roomCodes/${short}`));
 await remove(ref(db,`rooms/${room}`));await remove(ref(db,`friendInvites/${invite}`));
 await deleteUser(user);goOffline(db);await deleteApp(app);console.log('Disposable peer cleaned up');
}
