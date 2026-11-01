"""Validate YouTube extraction and actual audio decoding through WARP, without Discord."""
import os, re, socket, subprocess, threading, time
from pathlib import Path
from musicvault_discord.runtime import prepare_runtime
prepare_runtime()
state=Path('.data/warp').resolve()
proxy_url='http://127.0.0.1:40000'
with (state/'wireproxy.log').open('wb') as log:
 process=subprocess.Popen([str(state/'bin/wireproxy'),'-c',str(state/'wireproxy.conf')],stdout=log,stderr=log)
 try:
  for attempt in range(20):
   try:
    with socket.create_connection(('127.0.0.1',40000),timeout=1):break
   except OSError:time.sleep(0.2)
  trace=subprocess.run(['curl','--silent','--show-error','--max-time','20','--proxy',proxy_url,'https://www.cloudflare.com/cdn-cgi/trace'],capture_output=True,text=True,timeout=25)
  print('WARP_MEDIA_TRACE',[x for x in trace.stdout.splitlines() if x.startswith(('warp=','loc='))],flush=True)
  if 'warp=on' not in trace.stdout:raise RuntimeError('WARP no confirmado')
  os.environ.update({key:proxy_url for key in ('http_proxy','https_proxy','HTTP_PROXY','HTTPS_PROXY')})
  from musicvault_discord.provider import MusicProvider
  provider=MusicProvider();cancel=threading.Event()
  track=provider.search('https://youtu.be/Mmw-PpWhKFg',cancel)
  print('WARP_MEDIA_RESOLVE_START',flush=True)
  url=provider.resolve(track,cancel)
  print('WARP_MEDIA_RESOLVED',flush=True)
  result=subprocess.run([os.environ['MUSICVAULT_FFMPEG'],'-nostdin','-hide_banner','-loglevel','error','-http_proxy',proxy_url,'-rw_timeout','15000000','-i',url,'-map','0:a:0','-t','1','-vn','-f','null','-'],capture_output=True,timeout=35)
  print('WARP_MEDIA_DECODE',result.returncode,flush=True)
  if result.returncode:
   detail=re.sub(r'https?://\S+','[URL]',result.stderr.decode(errors='replace'))[-1200:]
   print('WARP_MEDIA_FFMPEG_ERROR',detail,flush=True)
  else:print('WARP_MEDIA_OK',flush=True)
 except Exception as error:
  print('WARP_MEDIA_ERROR',type(error).__name__,re.sub(r'https?://\S+','[URL]',str(error))[-1800:],flush=True)
 finally:
  process.terminate()
  try:process.wait(timeout=5)
  except subprocess.TimeoutExpired:process.kill();process.wait()
