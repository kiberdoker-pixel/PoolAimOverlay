#!/usr/bin/env python3
"""Smoke test an unmodified Tribez XAPK on an Android emulator.
This does NOT assert gameplay success. It collects install/start evidence."""
import os,sys,zipfile,subprocess,time,json,glob

def run(*cmd,timeout=90,check=True):
    r=subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    print("$", " ".join(cmd), "\n", r.stdout[-4000:], flush=True)
    if check and r.returncode: raise RuntimeError(f"exit {r.returncode}: {cmd}")
    return r
os.makedirs("tribez-diagnostics",exist_ok=True)
source="tribez-download.xapk"
assert os.path.getsize(source)>10000000, f"download unexpectedly small: {os.path.getsize(source)}"
assert zipfile.is_zipfile(source), "download not ZIP/XAPK"
with zipfile.ZipFile(source) as z:
    names=z.namelist()
    print("download entries:", [(n,z.getinfo(n).file_size) for n in names[:35]])
    apk_names=[n for n in names if n.lower().endswith(".apk") and not n.startswith("__MACOSX")]
    assert apk_names, "no APK inside download"
    apks=[]
    for ix,n in enumerate(apk_names):
        p=f"tribez-diagnostics/split_{ix:02d}.apk"
        with open(p,"wb") as f: f.write(z.read(n))
        apks.append(p)
    print("package APK count", len(apks), "sizes", [os.path.getsize(p) for p in apks])
print("Device:")
run("adb","shell","getprop","ro.product.cpu.abilist")
run("adb","shell","getprop","ro.build.version.release")
install=run("adb","install-multiple","-r",*apks,timeout=210,check=False) if len(apks)>1 else run("adb","install","-r",apks[0],timeout=210,check=False)
open("tribez-diagnostics/install.log","w").write(install.stdout)
if install.returncode:
    print("INSTALL FAILED — check install.log")
    sys.exit(2)
launch=run("adb","shell","monkey","-p","com.gameinsight.tribez","-c","android.intent.category.LAUNCHER","1",check=False)
open("tribez-diagnostics/launch.log","w").write(launch.stdout)
time.sleep(50)
r=run("adb","logcat","-d","-v","time",timeout=40,check=False)
open("tribez-diagnostics/logcat.txt","w").write(r.stdout)
run("adb","exec-out","screencap","-p",timeout=30,check=False)
shot=subprocess.run(["adb","exec-out","screencap","-p"],capture_output=True,timeout=30)
open("tribez-diagnostics/screen.png","wb").write(shot.stdout)
active=run("adb","shell","dumpsys","activity","activities",check=False)
open("tribez-diagnostics/activities.txt","w").write(active.stdout)
pid=run("adb","shell","pidof","com.gameinsight.tribez",check=False)
print("Tribez PID",pid.stdout.strip(),"ApkCheckFail in logs:","ApkCheckFail" in r.stdout)
for line in r.stdout.splitlines():
    if any(s in line.lower() for s in ["fatal exception","apkcheckfail","gameinsight.tribez","unsatisfiedlinkerror","force finishing","signal 11"]):
        print(line[-600:])
print("RESULT:", "STARTED (process alive)" if pid.stdout.strip() else "NOT RUNNING")
sys.exit(0 if pid.stdout.strip() else 3)
