import subprocess,tempfile,json,threading,queue,time,urllib.request,os
from pathlib import Path
import sys
if '--live' not in sys.argv:raise SystemExit('Usa --live para probar dos cuentas temporales en Firebase.')
backend=Path(__file__).resolve().parents[1]/'musicvault/jams_backend/bridge.mjs'
backend=Path(os.environ.get('MUSICVAULT_JAMS_BRIDGE',backend)).resolve()
node=os.environ.get('MUSICVAULT_JAMS_NODE','node')
class Peer:
 def __init__(self,folder):
  self.folder=Path(folder);self.q=queue.Queue();self.events=[]
  self.p=subprocess.Popen([node,str(backend),folder],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
  threading.Thread(target=lambda:[self.q.put(json.loads(line)) for line in self.p.stdout],daemon=True).start()
 def send(self,action,**kw):self.p.stdin.write(json.dumps(dict(action=action,**kw))+'\n');self.p.stdin.flush()
 def wait(self,name,predicate=lambda d:True):
  end=time.time()+30
  while time.time()<end:
   try:e=self.q.get(timeout=1)
   except queue.Empty:
    if self.p.poll() is not None:raise RuntimeError(self.p.stderr.read())
    continue
   self.events.append(e)
   if e['event']=='error':raise RuntimeError(e['data'])
   if e['event']==name and predicate(e['data']):return e['data']
  raise TimeoutError(name)
 def stop(self):
  if self.p.poll() is None:
   self.send('shutdown')
   try:self.p.wait(timeout=8)
   except subprocess.TimeoutExpired:self.p.kill()
 def cleanup(self):
  identity=json.loads((self.folder/'identity.json').read_text())
  for session in identity.get('sessions',{}).values():
   if not isinstance(session,dict) or 'stsTokenManager' not in session:continue
   token=session['stsTokenManager']['accessToken']; uid=session['uid']
   config=json.loads((backend.parent/'firebase-client.json').read_text())
   req=urllib.request.Request(config['databaseURL']+'/installations/'+uid+'.json?auth='+token,method='DELETE')
   urllib.request.urlopen(req,timeout=15).read()
   req=urllib.request.Request('https://identitytoolkit.googleapis.com/v1/accounts:delete?key='+config['apiKey'],data=json.dumps({'idToken':token}).encode(),headers={'Content-Type':'application/json'})
   urllib.request.urlopen(req,timeout=15).read()
with tempfile.TemporaryDirectory() as a,tempfile.TemporaryDirectory() as b:
 peers=[Peer(a),Peer(b)];host,guest=peers
 try:
  ids=[p.wait('identity') for p in peers];assert ids[0]['uid']!=ids[1]['uid']
  host.send('create');room=host.wait('room',lambda d:d and d['value']);code=room['code'];short=room['value']['meta']['shortCode']
  guest.send('join',value=short);guest.wait('room',lambda d:d and len(d['value']['members'])==2)
  host.send('add',song={'videoId':'QAGZJg_OGVU','title':'Test','artist':'Test','durationMs':134000},play=True)
  r=guest.wait('room',lambda d:bool(d and d['value'].get('queue')));key=next(iter(r['value']['queue']))
  guest.send('playback',value={'songKey':key,'playing':False,'positionMs':12345,'buffering':False,'action':'pause'})
  host.wait('room',lambda d:d and d['value']['playback']['positionMs']==12345)
  host.send('requestMember',uid=ids[1]['uid']);guest.wait('friendRequests',lambda d:ids[0]['uid'] in d)
  guest.send('accept',uid=ids[0]['uid']);host.wait('friends',lambda d:ids[1]['uid'] in d)
  host.send('invite',uid=ids[1]['uid']);guest.wait('roomInvites',lambda d:ids[0]['uid'] in d)
  guest.send('dismiss',uid=ids[0]['uid']);guest.wait('roomInvites',lambda d:not d)
  guest.send('removeFriend',uid=ids[0]['uid']);host.wait('friends',lambda d:not d)
  host.send('close');host.wait('room',lambda d:d is None)
  print('PASS: two anonymous clients; room creation; six-digit join; shared queue; guest controls; friendship acceptance; room invitation; cleanup')
 finally:
  for p in peers:p.stop()

 # Restart the first installation and verify both IDs persist.
 again=Peer(a)
 try:assert again.wait('identity')==ids[0];print('PASS: stable installation UUID and anonymous auth UID after restart')
 finally:
  again.stop()
  for p in peers:p.cleanup()
