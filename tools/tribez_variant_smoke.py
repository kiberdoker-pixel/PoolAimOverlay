#!/usr/bin/env python3
"""Compare unmodified, re-signed, and native signature-probe Tribez APKs on emulator."""
import os, sys, subprocess, time, json, zipfile, shutil, struct
from pathlib import Path

OUT=Path("tribez-variants")
OUT.mkdir(exist_ok=True)
def run(*args,timeout=120,check=False):
    v=subprocess.run(list(map(str,args)),capture_output=True,text=True,timeout=timeout)
    print("$",*args,"ret",v.returncode,v.stdout[-800:].replace("\n"," | "),v.stderr[-500:].replace("\n"," | "),flush=True)
    if check and v.returncode: raise RuntimeError("failed: "+" ".join(map(str,args)))
    return v
def raw(*args,timeout=35):
    return subprocess.run(list(map(str,args)),capture_output=True,timeout=timeout)
def extract():
    with zipfile.ZipFile("tribez-download.xapk") as z:
        names=[n for n in z.namelist() if n.endswith(".apk")]
        print("SOURCE APKS",[(n,z.getinfo(n).file_size) for n in names],flush=True)
        assert len(names)>=2
        p=[]
        for i,n in enumerate(names):
            q=OUT/("original_"+str(i)+".apk")
            with open(q,"wb") as f:f.write(z.read(n))
            p.append((n,q))
        return p
def stage(name,apks):
    print("===== TEST",name,"=====",flush=True)
    run("adb","uninstall","com.gameinsight.tribez")
    run("adb","logcat","-c")
    r=run("adb","install-multiple","-r",*apks,timeout=220)
    (OUT/(name+"_install.txt")).write_text(r.stdout+"\n"+r.stderr)
    if r.returncode!=0:
        print("INSTALL FAILURE:",name,flush=True)
        return {"stage":name,"install":False,"reason":r.stdout+r.stderr}
    x=run("adb","shell","monkey","-p","com.gameinsight.tribez","-c","android.intent.category.LAUNCHER","1")
    time.sleep(35)
    log=run("adb","logcat","-d","-v","threadtime",timeout=45)
    (OUT/(name+"_logcat.txt")).write_text(log.stdout)
    pic=raw("adb","exec-out","screencap","-p",timeout=25)
    (OUT/(name+"_screen.png")).write_bytes(pic.stdout)
    ui=run("adb","shell","uiautomator","dump","/sdcard/tribez_ui.xml",timeout=35)
    ux=run("adb","shell","cat","/sdcard/tribez_ui.xml",timeout=25)
    (OUT/(name+"_ui.xml")).write_text(ux.stdout)
    p=run("adb","shell","pidof","com.gameinsight.tribez")
    error_terms=["Обнаружена ошибка","reinstall","ApkCheckFail","error detected"]
    dialog=[s for s in error_terms if s.casefold() in ux.stdout.casefold()]
    crashes=[line for line in log.stdout.splitlines() if any(t in line.lower() for t in ["fatal exception","fatal signal","unsatisfiedlinkerror","apcheckfail","apkcheckfail"])]
    (OUT/(name+"_crashes.txt")).write_text("\n".join(crashes[-50:]))
    res={"stage":name,"install":True,"pid":p.stdout.strip(),"dialog_terms":dialog,"crash_lines":len(crashes),"screenshot_bytes":len(pic.stdout)}
    print("STAGE RESULT",json.dumps(res,ensure_ascii=False),flush=True)
    return res
def keygen():
    keystore=str(OUT/"temp-test.jks")
    run("keytool","-genkeypair","-noprompt","-keystore",keystore,"-storepass","testing123","-keypass","testing123","-alias","testing","-keyalg","RSA","-keysize","2048","-validity","10000","-dname","CN=Tribez Test, O=Diagnostics",check=True)
    return keystore
def sign(src,dst,keystore):
    aligned=OUT/("aligned_"+dst.name)
    run("zipalign","-p","-f","4",src,aligned,timeout=90,check=True)
    run("apksigner","sign","--ks",keystore,"--ks-pass","pass:testing123","--key-pass","pass:testing123","--ks-key-alias","testing","--out",dst,aligned,timeout=90,check=True)
    run("apksigner","verify","--verbose",dst,timeout=45,check=True)
def patch_arm64(src,dst):
    path="lib/arm64-v8a/libTheTribezLibJNI.so"
    with zipfile.ZipFile(src) as zin,zipfile.ZipFile(dst,"w",allowZip64=False) as zout:
        for info in zin.infolist():
            payload=zin.read(info.filename)
            if info.filename==path:
                payload=bytearray(payload)
                assert payload[0x258dbb0:0x258dbb4]==bytes.fromhex("ff0301d1"),"unexpected native library bytes"
                for i,word in enumerate([0x528ca800,0x72bd43c0,0xd65f03c0]):
                    struct.pack_into("<I",payload,0x258dbb0+i*4,word)
                payload=bytes(payload)
                print("patched GetBuildSignature to original 0xea1e6540",flush=True)
            zi=zipfile.ZipInfo(info.filename,info.date_time)
            zi.compress_type=info.compress_type
            zi.external_attr=info.external_attr
            zi.create_system=info.create_system
            zout.writestr(zi,payload)
    with zipfile.ZipFile(dst) as chk: assert chk.testzip() is None
def main():
    original=extract()
    results=[]
    print("DEVICE ABI:",run("adb","shell","getprop","ro.product.cpu.abilist").stdout.strip(),flush=True)
    results.append(stage("A_original",[p for n,p in original]))
    key=keygen()
    resigned=[]
    for n,p in original:
        output=OUT/(p.stem+"_resigned.apk")
        sign(p,output,key)
        resigned.append((n,output))
    results.append(stage("B_resigned",[p for n,p in resigned]))
    native=[(n,p) for n,p in original if "arm64" in n]
    assert len(native)==1, "expected one ARM64 split"
    name,src=native[0]
    patched=OUT/"patched_unsigned.apk"
    patch_arm64(src,patched)
    signed=OUT/"patched_signed.apk"
    sign(patched,signed,key)
    swapped=[signed if n==name else p for n,p in resigned]
    results.append(stage("C_native_signature_probe",swapped))
    (OUT/"SUMMARY.json").write_text(json.dumps(results,ensure_ascii=False,indent=2))
    print("FINAL DIAGNOSTICS",json.dumps(results,ensure_ascii=False,indent=2))
if __name__=="__main__":
    main()
