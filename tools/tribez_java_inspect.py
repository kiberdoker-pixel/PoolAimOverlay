#!/usr/bin/env python3
import pathlib,glob,re,os
root=pathlib.Path("tribez-jadx/sources")
names=[]
for p in root.rglob("*.java"):
    s=p.read_text(errors="replace")
    if ("ApkCheckFail" in s or "validation_apk_fail" in s or "getSigningCertificateHistory" in s or "GetBuildSignature" in s or "GetInstallerPackageName" in s or ("Signature" in s and "TheTribezActivity" in str(p))):
        names.append((str(p),s))
print("CANDIDATE COUNT",len(names),flush=True)
for path,s in names:
    print("\n===== CLASS",path,"=====",flush=True)
    lines=s.splitlines()
    indexes=[i for i,l in enumerate(lines) if any(term in l for term in ["ApkCheckFail","validation_apk_fail","getSigningCertificateHistory","GetBuildSignature","GetInstallerPackageName","getPackageInfo","getInstallerPackageName","getSignatures"])]
    for i in indexes[:32]:
        start=max(0,i-8);end=min(len(lines),i+12)
        print(f"-- line {i+1}: context--")
        for j in range(start,end):
            print(f"{j+1:04d}: {lines[j][:250]}")
    if "TheTribezActivity" in path:
        for i,l in enumerate(lines):
            if any(y in l for y in ["void c0(", " ApkCheckFail", "void onCreate(", "String getSignature"]):
                print("ACTIVITY EXTRA line",i+1,l[:350])
print("DONE")

for p in root.rglob("GameCoreActivity.java"):
 print("\\n===== GAMECORE ACTIVITY FULL INTERESTING LINES =====")
 lines=p.read_text(errors="replace").splitlines()
 for i,line in enumerate(lines):
  if any(t in line for t in ["c0()", " c0(", "getSignature", "Signature", "native ", "getPackageInfo", "getInstaller", "onCreate", "onResume", "PackageManager", "getPackageName", "Integrity", "validate", "validateApk"]):
   print("\\n---line",i+1,"---")
   for j in range(max(0,i-8),min(len(lines),i+17)):
    print(f"{j+1:04d}: {lines[j][:300]}")
