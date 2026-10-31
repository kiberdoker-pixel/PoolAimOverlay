#!/usr/bin/env python3
"""Verify single APK merged from Tribez XAPK in Android 11, then test signature patch."""
import zipfile, subprocess, os, time, pathlib, json, struct, sys
os.environ["PATH"]=os.path.join(os.environ.get("ANDROID_HOME","/usr/local/lib/android/sdk"),"build-tools","35.0.0")+os.pathsep+os.environ.get("PATH","")
D=pathlib.Path("tribez-merged-diagnostics")
D.mkdir(exist_ok=True)
def run(args,timeout=120):
    p=subprocess.run(args,text=True,capture_output=True,timeout=timeout)
    print("CMD",args,"return",p.returncode,"stdout",p.stdout[-400:],"stderr",p.stderr[-300:],flush=True)
    return p
def sign(unsigned, final, keystore):
    aligned=str(D/("aligned_"+pathlib.Path(final).stem+".apk"))
    p=run(["zipalign","-p","-f","4",unsigned,aligned],timeout=130)
    if p.returncode:raise RuntimeError("zipalign failed: "+p.stderr)
    p=run(["apksigner","sign","--ks",keystore,"--ks-pass","pass:testing123","--key-pass","pass:testing123","--ks-key-alias","testing","--out",final,aligned],timeout=130)
    if p.returncode:raise RuntimeError("sign failed: "+p.stderr)
    p=run(["apksigner","verify","--verbose",final])
    if p.returncode:raise RuntimeError("verify failed: "+p.stderr)
def install_test(name,apk):
    run(["adb","uninstall","com.gameinsight.tribez"])
    run(["adb","logcat","-c"])
    x=run(["adb","install","-r",apk],timeout=230)
    (D/(name+"_install.txt")).write_text(x.stdout+"\n"+x.stderr)
    if x.returncode:
        return dict(name=name,install=False,why=x.stdout+x.stderr)
    run(["adb","shell","monkey","-p","com.gameinsight.tribez","-c","android.intent.category.LAUNCHER","1"])
    time.sleep(48)
    log=run(["adb","logcat","-d","-v","threadtime"],timeout=45)
    (D/(name+"_log.txt")).write_text(log.stdout)
    pic=subprocess.run(["adb","exec-out","screencap","-p"],capture_output=True,timeout=35)
    (D/(name+"_screen.png")).write_bytes(pic.stdout)
    dump=run(["adb","shell","uiautomator","dump","/sdcard/view.xml"],timeout=45)
    ui=run(["adb","shell","cat","/sdcard/view.xml"],timeout=45)
    (D/(name+"_ui.xml")).write_text(ui.stdout)
    p=run(["adb","shell","pidof","com.gameinsight.tribez"])
    msgs=[z for z in ["Обнаружена ошибка","Требуется переустановка","ApkCheckFail","reinstall"] if z.lower() in (log.stdout+"\n"+ui.stdout).lower()]
    crashes=[l for l in log.stdout.splitlines() if any(s in l.lower() for s in ["fatal exception","fatal signal","unsatisfiedlinkerror","apkcheckfail","validation_apk_fail"])]
    summary=dict(name=name,install=True,pid=p.stdout.strip(),issues=msgs,crashes=crashes[-10:],screenshot_bytes=len(pic.stdout),ui_xml_length=len(ui.stdout))
    print("SINGLE-APK TEST RESULT",json.dumps(summary,ensure_ascii=False),flush=True)
    return summary
def patch(source,dest):
    original=b'\xff\x03\x01\xd1'
    seek=0x258dbb0
    found=False
    with zipfile.ZipFile(source) as inp,zipfile.ZipFile(dest,"w") as out:
        for item in inp.infolist():
            data=inp.read(item.filename)
            if item.filename=="lib/arm64-v8a/libTheTribezLibJNI.so":
                x=bytearray(data)
                assert x[seek:seek+4]==original,("native signature bytes differ",x[seek:seek+4].hex())
                for n,v in enumerate([0x528ca800,0x72bd43c0,0xd65f03c0]):
                    struct.pack_into("<I",x,seek+4*n,v)
                data=bytes(x)
                found=True
            if item.filename.startswith("META-INF/") or item.filename=="stamp-cert-sha256":continue
            a=zipfile.ZipInfo(item.filename,item.date_time)
            a.compress_type=item.compress_type
            a.external_attr=item.external_attr
            out.writestr(a,data)
    assert found,"native library not found"
    print("patched original build certificate hash inside native library",flush=True)
def main():
    print("DEVICE ABI",run(["adb","shell","getprop","ro.product.cpu.abilist"]).stdout,flush=True)
    key=str(D/"test.jks")
    x=run(["keytool","-genkeypair","-noprompt","-keystore",key,"-storepass","testing123","-keypass","testing123","-alias","testing","-keyalg","RSA","-keysize","2048","-validity","10000","-dname","CN=Android smoke test,O=Diagnostics"])
    if x.returncode:raise RuntimeError(x.stderr)
    results=[]
    sign("tribez-merged-unsigned.apk",str(D/"merged_signed.apk"),key)
    results.append(install_test("A_original_merged",str(D/"merged_signed.apk")))
    patch("tribez-merged-unsigned.apk",str(D/"patched_unsigned.apk"))
    sign(str(D/"patched_unsigned.apk"),str(D/"merged_patched_signed.apk"),key)
    results.append(install_test("B_merged_signature_mimic",str(D/"merged_patched_signed.apk")))
    (D/"SUMMARY.json").write_text(json.dumps(results,ensure_ascii=False,indent=2))
    print("FINAL SUMMARY",json.dumps(results,ensure_ascii=False,indent=2),flush=True)
if __name__=="__main__":
    main()
