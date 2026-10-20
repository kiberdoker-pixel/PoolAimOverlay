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

# The live path must not fall back to the legacy model or LineDetector.
source=root/'app/src/main/java/com/poolaim/overlay'
for name in ('CaptureService.kt', 'VisualLinesOverlay.kt'):
    text=(source/name).read_text()
    for legacy in ('LineDetector', 'PatchClassifier', 'PytorchLiteAdapter', 'AutoBallProposals'):
        assert legacy not in text, f'{name}: live recognition depends on {legacy}'
assert (source/'recognition/OpenCvGuideRecognizer.kt').is_file(), 'Missing independent recognizer'
assert (source/'recognition/GuideStabilityGate.kt').is_file(), 'Missing confidence gate'
assert (root/'app/src/main/assets/licenses/opencv-4.10.0-LICENSE.txt').is_file(), 'Missing OpenCV license'
print('PREFLIGHT OK: independent live recognizer and bundled OpenCV license')
