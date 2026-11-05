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
