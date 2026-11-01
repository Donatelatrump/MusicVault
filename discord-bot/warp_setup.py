"""Install verified userspace WARP tools and test egress without starting Discord."""
import hashlib, io, os, platform, subprocess, tarfile
from pathlib import Path
from urllib.request import Request, urlopen

ASSETS = {
 'wgcf': ('https://github.com/ViRb3/wgcf/releases/download/v2.3.1/wgcf_2.3.1_linux_amd64', '4fb7a136aa470ece11d17949c7db07b040ec9c604742dc8680bcf1889f85d0c8'),
 'wireproxy': ('https://github.com/windtf/wireproxy/releases/download/v1.1.3/wireproxy_linux_amd64.tar.gz', 'e88c1d090740373fc606c1bafd81d9a5eadc642cce5667616e20e9d7a444f51c'),
}


def install():
 if platform.system()!='Linux' or platform.machine()!='x86_64':raise RuntimeError('Este instalador es para Linux x86_64.')
 os.umask(0o077)
 directory=Path('.data/warp').resolve();directory.mkdir(parents=True,exist_ok=True);directory.chmod(0o700)
 binary_dir=directory/'bin';binary_dir.mkdir(exist_ok=True)
 for name,(url,digest) in ASSETS.items():
  target=binary_dir/name
  if target.exists():continue
  print('WARP_DOWNLOAD',name,flush=True)
  with urlopen(Request(url,headers={'User-Agent':'MusicVault WARP setup'}),timeout=30) as response:data=response.read(25*1024*1024+1)
  if len(data)>25*1024*1024 or hashlib.sha256(data).hexdigest()!=digest:raise RuntimeError('Checksum inválido: '+name)
  if name=='wireproxy':
   with tarfile.open(fileobj=io.BytesIO(data),mode='r:gz') as archive:
    member=next(m for m in archive.getmembers() if Path(m.name).name=='wireproxy' and m.isfile())
    data=archive.extractfile(member).read()
  target.write_bytes(data);target.chmod(0o700)
  print('WARP_BINARY_VERIFIED',name,flush=True)
 return directory


def configure(directory):
 with (directory/'setup.log').open('ab') as log:
  if not (directory/'wgcf-account.toml').exists():
   print('WARP_REGISTER_START',flush=True)
   subprocess.run([str(directory/'bin/wgcf'),'register','--accept-tos','--name','MusicVault KataBump'],cwd=directory,stdout=log,stderr=log,check=True,timeout=45)
  if not (directory/'wgcf-profile.conf').exists():
   subprocess.run([str(directory/'bin/wgcf'),'generate','--keepalive'],cwd=directory,stdout=log,stderr=log,check=True,timeout=20)
 for name in ['wgcf-account.toml','wgcf-profile.conf']:(directory/name).chmod(0o600)
 config=directory/'wireproxy.conf'
 config.write_text('WGConfig = '+str(directory/'wgcf-profile.conf')+'\n\n[http]\nBindAddress = 127.0.0.1:40000\n')
 config.chmod(0o600)
 return config


def check(config):
 log=(config.parent/'wireproxy.log').open('ab')
 proxy=subprocess.Popen([str(config.parent/'bin/wireproxy'),'-c',str(config)],stdout=log,stderr=log)
 try:
  result=subprocess.run(['curl','--silent','--show-error','--retry','2','--retry-connrefused','--retry-delay','1','--max-time','18','--proxy','http://127.0.0.1:40000','https://www.cloudflare.com/cdn-cgi/trace'],capture_output=True,text=True,timeout=60)
  public=[line for line in result.stdout.splitlines() if line.startswith(('warp=','loc='))]
  print('WARP_TRACE',result.returncode,public,flush=True)
  if result.returncode or 'warp=on' not in public:raise RuntimeError('La conexión WARP no se confirmó. '+result.stderr[:250])
 finally:
  proxy.terminate()
  try:proxy.wait(timeout=5)
  except subprocess.TimeoutExpired:proxy.kill();proxy.wait()
  log.close()

if __name__=='__main__':
 try:
  state=install();config=configure(state);check(config)
  print('WARP_SETUP_OK',flush=True)
 except Exception as error:
  print('WARP_SETUP_ERROR',type(error).__name__,str(error)[:300],flush=True)
  # Never print setup.log: it may contain device credentials.
  raise SystemExit(1)
