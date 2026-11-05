// Opt-in live test for two temporary anonymous users; all test data is removed.
import {readFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
import {initializeApp,deleteApp} from 'firebase/app';
import {getAuth,signInAnonymously,deleteUser} from 'firebase/auth';
import {getDatabase,ref,set,get,update,remove,goOffline} from 'firebase/database';
if (!process.argv.includes('--live')) throw new Error('Pass --live to run against the configured project');
const config=JSON.parse(readFileSync(new URL('../../android/app/google-services.json',import.meta.url)));
const client=config.client.find(c=>c.client_info.android_client_info.package_name==='com.musicvault.app');
const options={apiKey:client.api_key[0].current_key,appId:client.client_info.mobilesdk_app_id,projectId:config.project_info.project_id,databaseURL:config.project_info.firebase_url};
const apps=[initializeApp(options,'friends-a-'+randomUUID()),initializeApp(options,'friends-b-'+randomUUID())];
const dbs=apps.map(app=>getDatabase(app));
const users=[];
const code=randomUUID().replaceAll('-',''), room=randomUUID().replaceAll('-','');
let inviteCreated=false,roomCreated=false;
const timer=setTimeout(()=>{console.error('Live friend test timed out');process.exit(1);},60_000);
try {
  for (const app of apps) users.push((await signInAnonymously(getAuth(app))).user);
  const [a,b]=users.map(user=>user.uid);
  await set(ref(dbs[0],`friendInvites/${code}`),{ownerUid:a,expiresAt:Date.now()+86400000});inviteCreated=true;
  await set(ref(dbs[1],`friendRequests/${a}/${b}`),{inviteCode:code,createdAt:Date.now()});
  assert((await get(ref(dbs[0],`friendRequests/${a}/${b}`))).exists());
  await update(ref(dbs[0]),{[`friends/${a}/${b}`]:true,[`friends/${b}/${a}`]:true,[`friendRequests/${a}/${b}`]:null});
  assert.equal((await get(ref(dbs[1],`friends/${b}/${a}`))).val(),true);
  await set(ref(dbs[0],`rooms/${room}`),{meta:{hostUid:a,expiresAt:Date.now()+3600000},members:{[a]:true},playback:{songKey:'',playing:false,positionMs:0,effectiveAt:Date.now(),revision:0}});roomCreated=true;
  await set(ref(dbs[0],`roomInvites/${b}/${a}`),{roomCode:room,createdAt:Date.now()});
  assert.equal((await get(ref(dbs[1],`roomInvites/${b}/${a}/roomCode`))).val(),room);
  await set(ref(dbs[1],`rooms/${room}/members/${b}`),true);
  assert((await get(ref(dbs[1],`rooms/${room}`))).exists());
  console.log('PASS: friend link → request → reciprocal acceptance → private contact list → room invitation → joining');
} finally {
  if (users.length===2) {
    const [a,b]=users.map(user=>user.uid);
    await update(ref(dbs[0]),{[`friends/${a}/${b}`]:null,[`friends/${b}/${a}`]:null,[`friendRequests/${a}/${b}`]:null,[`roomInvites/${b}/${a}`]:null});
    if(roomCreated) await remove(ref(dbs[0],`rooms/${room}`));
    if(inviteCreated) await remove(ref(dbs[0],`friendInvites/${code}`));
  }
  for (const user of users) await deleteUser(user);
  dbs.forEach(goOffline);
  for (const app of apps) await deleteApp(app);
  clearTimeout(timer);
}
