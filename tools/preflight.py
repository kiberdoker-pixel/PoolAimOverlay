#!/usr/bin/env python3
from pathlib import Path
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
m=root/'app/src/main/AndroidManifest.xml'
tree=ET.parse(m)
android='{http://schemas.android.com/apk/res/android}'
perms=[e.get(android+'name') for e in tree.getroot().findall('uses-permission')]
assert len(perms)==len(set(perms)), 'Duplicate Android permissions'
for name in ('android.permission.SYSTEM_ALERT_WINDOW','android.permission.FOREGROUND_SERVICE','android.permission.FOREGROUND_SERVICE_SPECIAL_USE'):
    assert name in perms, 'Missing '+name
model=root/'app/src/main/assets/aimx_patch_classifier_mobile.ptl'
assert model.is_file() and model.stat().st_size>1000, 'Missing or empty neural network'
for name in ('MainActivity.kt','AimService.kt','ModelProbeActivity.kt','PatchClassifier.kt','PytorchLiteAdapter.kt'):
    assert (root/'app/src/main/java/com/poolaim/overlay'/name).is_file(), 'Missing '+name
print('PREFLIGHT OK: XML, permissions, model, Kotlin source files')
