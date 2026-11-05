import assert from "node:assert/strict";
import { before, after, beforeEach, test } from 'node:test';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { ref, set, get, remove, runTransaction } from 'firebase/database';
let env;
const code = 'a'.repeat(32);
const room = () => `rooms/${code}`;
const client = uid => env.authenticatedContext(uid).database();
const playback = (revision, playing = false) => ({ songKey: '', playing, positionMs: 0, effectiveAt: Date.now(), revision });
async function create() {
  return set(ref(client('host'), room()), {
    meta: {hostUid: 'host', expiresAt: Date.now() + 3_600_000}, members: {host: true}, playback: playback(0)
  });
}
before(async () => { env = await initializeTestEnvironment({ projectId: 'demo-musicvault', database: {
  host: '127.0.0.1', port: 9000, rules: readFileSync(new URL('../database.rules.json', import.meta.url), 'utf8')
} }); });
after(async () => { await env?.cleanup(); });
beforeEach(async () => { await env.clearDatabase(); });
test('only the owner can read or write their installation UID', async () => {
  await assertSucceeds(set(ref(client('host'), 'installations/host'), {installationUid: '01234567-89ab-cdef-0123-456789abcdef'}));
  await assertFails(get(ref(client('other'), 'installations/host')));
  await assertFails(set(ref(client('other'), 'installations/host'), {installationUid: '01234567-89ab-cdef-0123-456789abcdef'}));
});
test('room invitations grant membership without permitting room listing', async () => {
  await assertSucceeds(create());
  await assertFails(get(ref(client('guest'), room())));
  await assertFails(get(ref(client('host'), 'rooms')));
  await assertSucceeds(set(ref(client('guest'), `${room()}/members/guest`), true));
  await assertSucceeds(get(ref(client('guest'), room())));
  await assertFails(set(ref(client('guest'), `${room()}/members/another`), true));
});
test('every member can pause, resume and seek using an increasing revision', async () => {
  await create();
  await set(ref(client('guest'), `${room()}/members/guest`), true);
  await set(ref(client('guest'), `${room()}/queue/song1`), {videoId:'QTzZqdJ-oXw',title:'Friends',artist:'Test',durationMs:104000,addedBy:'guest'});
  await assertSucceeds(set(ref(client('host'), `${room()}/playback`), {...playback(1, true),songKey:'song1'}));
  await assertSucceeds(set(ref(client('guest'), `${room()}/playback`), {...playback(2, false),songKey:'song1',positionMs:10000}));
  await assertSucceeds(set(ref(client('guest'), `${room()}/playback`), {...playback(3, true),songKey:'song1',positionMs:30000}));
  await assertFails(set(ref(client('host'), `${room()}/playback`), {...playback(2),songKey:'song1'}));
  await assertFails(set(ref(client('outsider'), `${room()}/playback`), {...playback(4),songKey:'song1'}));
});
test('members cannot replace the host or close the room', async () => {
  await create();
  await set(ref(client('guest'), `${room()}/members/guest`), true);
  await assertFails(set(ref(client('guest'), `${room()}/meta/hostUid`), 'guest'));
  await assertFails(remove(ref(client('guest'), room())));
  await assertSucceeds(remove(ref(client('host'), room())));
});
test('members cannot inject audio URLs, arbitrary private fields or invalid videos', async () => {
  await create();
  const song = {videoId:'QTzZqdJ-oXw',title:'Friends',artist:'Test',durationMs:104000,addedBy:'host'};
  await assertFails(set(ref(client('host'), `${room()}/queue/song1`), {...song, audioUri:'https://private.example/audio'}));
  await assertFails(set(ref(client('host'), `${room()}/queue/song1`), {...song,videoId:'bad'}));
  await assertSucceeds(set(ref(client('host'), `${room()}/queue/song1`), song));
});

const friendCode = 'b'.repeat(32);
async function friendship() {
  await set(ref(client('host'), `friendInvites/${friendCode}`), {ownerUid:'host',expiresAt:Date.now()+86400000});
  await set(ref(client('guest'), 'friendRequests/host/guest'), {inviteCode:friendCode,createdAt:Date.now()});
  const {update} = await import('firebase/database');
  await update(ref(client('host')), {'friends/host/guest':true,'friends/guest/host':true,'friendRequests/host/guest':null});
}
test('a shared friend link creates a request that only its recipient may accept', async () => {
  await set(ref(client('host'), `friendInvites/${friendCode}`), {ownerUid:'host',expiresAt:Date.now()+86400000});
  await assertSucceeds(set(ref(client('guest'), 'friendRequests/host/guest'), {inviteCode:friendCode,createdAt:Date.now()}));
  await assertFails(set(ref(client('guest'), 'friends/guest/host'), true));
  const {update} = await import('firebase/database');
  await assertSucceeds(update(ref(client('host')), {'friends/host/guest':true,'friends/guest/host':true,'friendRequests/host/guest':null}));
  await assertSucceeds(get(ref(client('guest'), 'friends/guest')));
  await assertFails(get(ref(client('outsider'), 'friends/guest')));
});
test('arbitrary strangers cannot add friends or inspect friend requests', async () => {
  await assertFails(set(ref(client('outsider'), 'friends/host/outsider'), true));
  await assertFails(set(ref(client('outsider'), 'friendRequests/host/outsider'), {inviteCode:'f'.repeat(32),createdAt:Date.now()}));
  await assertFails(get(ref(client('outsider'), 'friendRequests/host')));
  await assertFails(get(ref(client('host'), 'friendInvites')));
});
test('room participants can send friend requests without a public user directory', async () => {
  await create();
  await set(ref(client('guest'), `${room()}/members/guest`), true);
  await assertSucceeds(set(ref(client('guest'), 'friendRequests/host/guest'), {roomCode:code,createdAt:Date.now()}));
  await assertFails(set(ref(client('outsider'), 'friendRequests/host/outsider'), {roomCode:code,createdAt:Date.now()}));
});
test('only accepted friends currently in a room may send room invitations', async () => {
  await create();
  await friendship();
  await assertSucceeds(set(ref(client('host'), 'roomInvites/guest/host'), {roomCode:code,createdAt:Date.now()}));
  await assertSucceeds(get(ref(client('guest'), 'roomInvites/guest')));
  await assertFails(get(ref(client('outsider'), 'roomInvites/guest')));
  await assertFails(set(ref(client('outsider'), 'roomInvites/guest/outsider'), {roomCode:code,createdAt:Date.now()}));
});
test('removing a friend removes both sides and prevents new room invitations', async () => {
  await create();
  await friendship();
  const {update} = await import('firebase/database');
  await assertSucceeds(update(ref(client('guest')), {'friends/host/guest':null,'friends/guest/host':null}));
  await assertFails(set(ref(client('host'), 'roomInvites/guest/host'), {roomCode:code,createdAt:Date.now()}));
});

test('six digit codes resolve privately, reject collisions and expire with the room', async () => {
  const expiry=Date.now()+3600000;
  await set(ref(client('host'),room()), {meta:{hostUid:'host',expiresAt:expiry,shortCode:'012345',protocolVersion:2},members:{host:true},playback:playback(0)});
  const directory={roomId:code,ownerUid:'host',expiresAt:expiry};
  await assertSucceeds(set(ref(client('host'),'roomCodes/012345'),directory));
  await assertFails(set(ref(client('host'),'roomCodes/012345'),directory));
  await assertFails(get(ref(client('guest'),'roomCodes')));
  await assertFails(get(ref(client('guest'),'roomCodes/012345')));
  await assertSucceeds(set(ref(client('guest'),'roomLookups/guest'),{code:'012345',requestedAt:Date.now()}));
  assert.equal((await assertSucceeds(get(ref(client('guest'),'roomCodes/012345')))).val().roomId,code);
  await assertFails(set(ref(client('guest'),'roomLookups/guest'),{code:'123456',requestedAt:Date.now()}));
  await assertFails(remove(ref(client('guest'),'roomLookups/guest')));
  await assertFails(remove(ref(client('guest'),'roomCodes/012345')));
  await assertSucceeds(remove(ref(client('host'),'roomCodes/012345')));
});
test('members publish only their own readiness and share buffered playback',async()=>{
  await create();
  await set(ref(client('guest'),`${room()}/members/guest`),true);
  await assertSucceeds(set(ref(client('host'),`${room()}/queue/song1`),{videoId:'QTzZqdJ-oXw',title:'Friends',artist:'Test',durationMs:104000,addedBy:'host',sourceTrackId:'deezer:123'}));
  await assertSucceeds(set(ref(client('host'),`${room()}/playback`),{...playback(1,true),songKey:'song1',buffering:true,commandId:'command-1',action:'select'}));
  await assertSucceeds(set(ref(client('guest'),`${room()}/ready/guest`),'song1'));
  await assertFails(set(ref(client('host'),`${room()}/ready/guest`),'song1'));
  await assertFails(set(ref(client('guest'),`${room()}/ready/guest`),'missing'));
  await assertSucceeds(remove(ref(client('guest'),`${room()}/ready/guest`)));
});
