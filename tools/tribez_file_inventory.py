#!/usr/bin/env python3
"""Classify app files after first launch. No edits to application data."""
import subprocess,time,zipfile,pathlib,os,json
D=pathlib.Path("storage-report");D.mkdir(exist_ok=True)
def run(*args,timeout=180):
 r=subprocess.run(args,text=True,capture_output=True,timeout=timeout)
 print("CMD",str(args)[:250],"exit",r.returncode,r.stdout[-700:],r.stderr[-200:],flush=True)
 return r
with zipfile.ZipFile("tribez.xapk") as z:
 splits=[]
 for i,n in enumerate(z.namelist()):
  if n.endswith(".apk"):
   p=D/f"source_{i}.apk";p.write_bytes(z.read(n));splits.append(str(p))
assert len(splits)>=2
print("ABI",run("adb","shell","getprop","ro.product.cpu.abilist").stdout,flush=True)
assert run("adb","install-multiple",*splits,timeout=240).returncode==0
run("adb","shell","monkey","-p","com.gameinsight.tribez","-c","android.intent.category.LAUNCHER","1")
time.sleep(85)
root=run("adb","root",timeout=30)
print("ADBD ROOT",root.stdout,flush=True)
base="/data/user/0/com.gameinsight.tribez"
paths=run("adb","shell","find",base,"-type","f",timeout=90)
allpaths=paths.stdout.splitlines()
print("TOTAL INTERNAL FILES",len(allpaths))
nonresources=[p for p in allpaths if not any(sub in p for sub in ["/files/shaders/","/files/Sounds/","/app_webview/","/cache/WebView","/oat/","/code_cache","/files/assets/","/files/rawSoundsDirectory/"])]
print("CANDIDATES",len(nonresources),flush=True)
record=[]
for p in nonresources[:240]:
 info=run("adb","shell","stat","-c","%s %n",p,timeout=12)
 if info.returncode==0: record.append(info.stdout.strip())
print("CANDIDATE FILES",json.dumps(record,ensure_ascii=False),flush=True)
for path in [base+"/files",base+"/shared_prefs",base+"/databases",base+"/no_backup",base+"/cache","/sdcard/Android/data/com.gameinsight.tribez/files"]:
 r=run("adb","shell","du","-ah","-d","2",path,timeout=30)
 print("TREE",path,r.stdout[:3500],flush=True)
report={"total":len(allpaths),"candidates":record}
(D/"file_report.json").write_text(json.dumps(report,indent=2,ensure_ascii=False))
