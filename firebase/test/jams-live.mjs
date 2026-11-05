// Explicit live smoke test; creates two temporary anonymous users and removes its room/users.
import {readFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {initializeApp, deleteApp} from 'firebase/app';
import {getAuth, signInAnonymously, deleteUser} from 'firebase/auth';
import {getDatabase, ref, set, get, runTransaction, remove, goOffline, serverTimestamp} from 'firebase/database';
import assert from 'node:assert/strict';
if (!process.argv.includes('--live')) throw new Error('Pass --live to exercise the configured Firebase project');
const raw = JSON.parse(readFileSync(new URL('../../android/app/google-services.json', import.meta.url)));
const client = raw.client.find(c => c.client_info.android_client_info.package_name === 'com.musicvault.app');
const options = {apiKey:client.api_key[0].current_key,appId:client.client_info.mobilesdk_app_id,
  projectId:raw.project_info.project_id,databaseURL:raw.project_info.firebase_url};
const apps = [initializeApp(options,'smoke-a-'+randomUUID()),initializeApp(options,'smoke-b-'+randomUUID())];
const users = [];
const dbs = apps.map(app => getDatabase(app));
const code = randomUUID().replaceAll('-','');
const path = `rooms/${code}`;
const short = String(Math.floor(Math.random()*1_000_000)).padStart(6,'0');
const timer = setTimeout(() => {console.error('Live JAMS test timed out');process.exit(1);},60_000);
try {
  for (const app of apps) users.push((await signInAnonymously(getAuth(app))).user);
  assert.notEqual(users[0].uid, users[1].uid);
  const now = Date.now();
  await set(ref(dbs[0],path),{meta:{hostUid:users[0].uid,expiresAt:now+3_600_000,shortCode:short,protocolVersion:2},members:{[users[0].uid]:true},
    playback:{songKey:'',playing:false,positionMs:0,effectiveAt:now,revision:0}});
  await set(ref(dbs[0],`roomCodes/${short}`),{roomId:code,ownerUid:users[0].uid,expiresAt:now+3_600_000});
  await set(ref(dbs[1],`roomLookups/${users[1].uid}`),{code:short,requestedAt:serverTimestamp()});
  assert.equal((await get(ref(dbs[1],`roomCodes/${short}`))).val().roomId,code);
  await set(ref(dbs[1],`${path}/members/${users[1].uid}`),true);
  await set(ref(dbs[1],`${path}/queue/song1`),{videoId:'QAGZJg_OGVU',title:'How can we go back to being friends',artist:'Money House',durationMs:134000,addedBy:users[1].uid});
  await set(ref(dbs[0],`${path}/ready/${users[0].uid}`),'song1');
  await set(ref(dbs[1],`${path}/ready/${users[1].uid}`),'song1');
  assert.equal(Object.keys((await get(ref(dbs[0],`${path}/ready`))).val()).length,2);
  for (const [peer,playing,position] of [[0,true,0],[1,false,10_000],[1,true,30_000],[0,false,31_000]]) {
    const result = await runTransaction(ref(dbs[peer],`${path}/playback`),current => current ?
      {songKey:'song1',playing,positionMs:position,effectiveAt:Date.now(),revision:current.revision+1,buffering:false,commandId:randomUUID(),action:playing?'play':'pause'}:current,{applyLocally:false});
    assert(result.committed);
    const other = (await get(ref(dbs[1-peer],`${path}/playback`))).val();
    assert.equal(other.playing,playing);
    assert.equal(other.positionMs,position);
  }
  console.log('PASS: two distinct anonymous UIDs; six digit lookup; readiness; shared queue; both users play/pause/seek; revisions replicated');
} finally {
  try {if (users[0]) { await remove(ref(dbs[0],`roomCodes/${short}`)); await remove(ref(dbs[0],path)); }} finally {
    for (const user of users) await deleteUser(user);
    dbs.forEach(goOffline);
    for (const app of apps) await deleteApp(app);
    clearTimeout(timer);
  }
}
