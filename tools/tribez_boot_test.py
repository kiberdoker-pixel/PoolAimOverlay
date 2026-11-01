#!/usr/bin/env python3
"""Build and boot a minimal DEX-only single APK variant for an isolated game test."""
import os,sys,time,json,zlib,hashlib,pathlib,zipfile,subprocess
D=pathlib.Path("tribez-boot-results");D.mkdir(exist_ok=True)
SDK=os.environ.get("ANDROID_HOME","/usr/local/lib/android/sdk")
os.environ["PATH"]=f"{SDK}/build-tools/35.0.0:"+os.environ.get("PATH","")
SRC="tribez-merged-unsigned.apk"
TARGET="tribez-java-variant-unsigned.apk"
OFF=0x24968c
BEFORE=bytes.fromhex("7100283e00000a00140140651eea331004001210")
AFTER=bytes.fromhex("12100f0000000000000000000000000000000000")
def run(*args,timeout=180):
 p=subprocess.run([str(a) for a in args],capture_output=True,text=True,timeout=timeout)
 print("COMMAND",str(args)[:200],"exit",p.returncode,"stdout",p.stdout[-350:],"stderr",p.stderr[-350:],flush=True)
 return p
def patch():
 found=False
 with zipfile.ZipFile(SRC) as iz, zipfile.ZipFile(TARGET,"w",allowZip64=True) as oz:
  for info in iz.infolist():
   if info.filename.startswith("META-INF/") or info.filename=="stamp-cert-sha256":continue
   b=iz.read(info.filename)
   if info.filename=="classes3.dex":
    data=bytearray(b)
    assert data[OFF:OFF+len(BEFORE)]==BEFORE,(data[OFF:OFF+len(BEFORE)].hex(),"Unexpected DEX layout")
    data[OFF:OFF+len(AFTER)]=AFTER
    data[12:32]=hashlib.sha1(data[32:]).digest()
    data[8:12]=(zlib.adler32(data[12:])&0xffffffff).to_bytes(4,"little")
    assert len(b)==len(data)
    b=bytes(data)
    found=True
   oz.writestr(info,b,compress_type=info.compress_type,compresslevel=1)
 assert found
 with zipfile.ZipFile(TARGET) as f: assert f.testzip() is None
 print("DEX PATCH",{"offset":hex(OFF),"new_sha1":hashlib.sha1(b).hexdigest(),"variant_size":os.path.getsize(TARGET)},flush=True)
def test():
 key=str(D/"test.jks")
 r=run("keytool","-genkeypair","-noprompt","-keystore",key,"-storepass","testing123","-keypass","testing123","-alias","testing","-keyalg","RSA","-keysize","2048","-validity","10000","-dname","CN=Temporary Emulator Test")
 assert r.returncode==0
 aligned=str(D/"variant-aligned.apk")
 signed=str(D/"variant-signed.apk")
 for args in [("zipalign","-p","-f","4",TARGET,aligned),("apksigner","sign","--ks",key,"--ks-pass","pass:testing123","--key-pass","pass:testing123","--ks-key-alias","testing","--out",signed,aligned),("apksigner","verify","--verbose",signed)]:
  r=run(*args,timeout=210)
  assert r.returncode==0
 print("ABI",run("adb","shell","getprop","ro.product.cpu.abilist").stdout)
 run("adb","uninstall","com.gameinsight.tribez")
 run("adb","logcat","-c")
 install=run("adb","install","-r",signed,timeout=240)
 (D/"install.txt").write_text(install.stdout+install.stderr)
 if install.returncode:
  print("RESULT",json.dumps(dict(installed=False,reason=install.stderr)),flush=True);return
 run("adb","shell","monkey","-p","com.gameinsight.tribez","-c","android.intent.category.LAUNCHER","1")
 time.sleep(58)
 pid=run("adb","shell","pidof","com.gameinsight.tribez").stdout.strip()
 ui_dump=run("adb","shell","uiautomator","dump","/sdcard/boot-view.xml",timeout=50)
 ui=run("adb","shell","cat","/sdcard/boot-view.xml",timeout=40).stdout
 (D/"ui.xml").write_text(ui)
 image=subprocess.run(["adb","exec-out","screencap","-p"],capture_output=True,timeout=40)
 (D/"screen.png").write_bytes(image.stdout)
 log=run("adb","logcat","-d","-v","threadtime",timeout=60).stdout
 (D/"logcat.txt").write_text(log)
 errors=[l for l in log.splitlines() if any(s in l.lower() for s in ["fatal exception","fatal signal","apkcheckfail","failed to open","unsatisfiedlinkerror"])]
 d={"installed":True,"pid":pid,"reinstall_error":"reinstall" in ui.lower() or "переустановка" in ui.lower(),"ui":ui[:1500],"screenshot_bytes":len(image.stdout),"crashes":errors[-15:]}
 (D/"result.json").write_text(json.dumps(d,ensure_ascii=False,indent=2))
 print("RESULT",json.dumps(d,ensure_ascii=False),flush=True)
if __name__=="__main__":
 patch() if len(sys.argv)>1 and sys.argv[1]=="patch" else test()
