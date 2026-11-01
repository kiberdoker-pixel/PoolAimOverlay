#!/usr/bin/env python3
"""Read-only storage diagnostics for a fresh, unmodified The Tribez install."""
import subprocess,time,zipfile,pathlib,json
P=pathlib.Path("tribez-storage-results");P.mkdir(exist_ok=True)
def run(*args,timeout=120):
 r=subprocess.run(args,text=True,capture_output=True,timeout=timeout)
 print("COMMAND",args,"exit",r.returncode,"stdout",r.stdout[-1200:],"stderr",r.stderr[-400:],flush=True)
 return r
with zipfile.ZipFile("tribez-download.xapk") as z:
 apks=[]
 for n in z.namelist():
  if n.endswith(".apk"):
   p=P/n.split("/")[-1]
   p.write_bytes(z.read(n));apks.append(str(p))
assert len(apks)>=2
run("adb","shell","getprop","ro.product.cpu.abilist")
r=run("adb","install-multiple","-r",*apks,timeout=240)
assert r.returncode==0
run("adb","shell","monkey","-p","com.gameinsight.tribez","-c","android.intent.category.LAUNCHER","1")
time.sleep(65)
locations=[
"/sdcard/Android/data/com.gameinsight.tribez",
"/sdcard/Android/obb/com.gameinsight.tribez",
"/sdcard/Android/media/com.gameinsight.tribez",
"/data/user/0/com.gameinsight.tribez",
"/data/data/com.gameinsight.tribez",
]
results={}
for path in locations:
 r=run("adb","shell","ls","-la",path)
 results[path]={"code":r.returncode,"stdout":r.stdout[-4000:],"stderr":r.stderr[-400:]}
for sub in ["/data/user/0/com.gameinsight.tribez/files","/data/user/0/com.gameinsight.tribez/shared_prefs","/data/user/0/com.gameinsight.tribez/databases","/sdcard/Android/data/com.gameinsight.tribez/files"]:
 r=run("adb","shell","ls","-lah",sub)
 results[sub]={"code":r.returncode,"stdout":r.stdout[-4000:],"stderr":r.stderr[-400:]}
results["run_as"]=run("adb","shell","run-as","com.gameinsight.tribez","ls","-la").stdout
results["adb_root"]=run("adb","root").stdout
r=run("adb","shell","find","/data/user/0/com.gameinsight.tribez","-maxdepth","3","-type","f",timeout=45)
results["internal_files"]={"code":r.returncode,"stdout":r.stdout[-10000:],"stderr":r.stderr[-300:]}
r=run("adb","shell","find","/sdcard/Android/data/com.gameinsight.tribez","-maxdepth","4","-type","f",timeout=45)
results["external_files"]={"code":r.returncode,"stdout":r.stdout[-10000:],"stderr":r.stderr[-300:]}
(P/"storage_report.json").write_text(json.dumps(results,indent=2,ensure_ascii=False))
print("SUMMARY PATHS",json.dumps({k:(v.get("code"),v.get("stdout","")[:1500]) if isinstance(v,dict) else v[:250] for k,v in results.items()},ensure_ascii=False),flush=True)
