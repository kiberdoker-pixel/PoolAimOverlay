#!/usr/bin/env python3
"""Compare original unmodified split game native stability to signed merged variant."""
import os,time,pathlib,zipfile,subprocess,json
D=pathlib.Path("tribez-original-long-results");D.mkdir(exist_ok=True)
def cmd(*args,timeout=180):
 p=subprocess.run([str(x) for x in args],capture_output=True,text=True,timeout=timeout)
 print("COMMAND",args,"exit",p.returncode,p.stdout[-200:],p.stderr[-200:],flush=True)
 return p
with zipfile.ZipFile("tribez-download.xapk") as z:
 paths=[]
 for k,n in enumerate(z.namelist()):
  if n.endswith(".apk"):
   f=D/f"split_{k}.apk";f.write_bytes(z.read(n));paths.append(str(f))
assert len(paths)>1
assert cmd("adb","install-multiple","-r",*paths,timeout=250).returncode==0
cmd("adb","logcat","-c")
cmd("adb","shell","monkey","-p","com.gameinsight.tribez","-c","android.intent.category.LAUNCHER","1")
results=[]
for t in [65,100,155,205]:
 time.sleep(t-(results[-1]["seconds"] if results else 0))
 pid=cmd("adb","shell","pidof","com.gameinsight.tribez").stdout.strip()
 img=subprocess.run(["adb","exec-out","screencap","-p"],capture_output=True,timeout=30)
 (D/f"original_{t}s.png").write_bytes(img.stdout)
 results.append(dict(seconds=t,pid=pid,screen_bytes=len(img.stdout)))
 print("ORIGINAL TIMELINE",json.dumps(results[-1]),flush=True)
log=cmd("adb","logcat","-d","-v","threadtime",timeout=75).stdout
(D/"logcat.txt").write_text(log)
results.append(dict(crashes=[x for x in log.splitlines() if any(k in x.lower() for k in ["fatal signal","fatal exception","crash_dump"])][-15:]))
(D/"results.json").write_text(json.dumps(results,indent=2))
print("ORIGINAL RESULT",json.dumps(results),flush=True)
