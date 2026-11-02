import hashlib
import pathlib
import re
import shutil
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
VARIANTS = [
    ('bonfie (1).glb', 'Bonfie - variant 1'),
    ('bonfie_nsfw_blender.glb', 'Bonfie - Blender variant'),
    ('classic_chiku.glb', 'Chiku - classic'),
    ('chiku_nude.glb', 'Chiku - alternate'),
    ('fredina_cally3d.glb', 'Fredina - Cally3D'),
    ('fredina_nude_fnia.glb', 'Fredina - FNIA alternate'),
    ('glamrock_chica.glb', 'Glamrock Chica'),
    ('km_diane_foxington_bikini_2.glb', 'Diane Foxington'),
    ('marie_blender.glb', 'Marie - Blender variant'),
    ('marie_nsfw.glb', 'Marie - alternate'),
]
BASE = [('roxanne_wolf_security_breach.glb', 'Roxanne'), ('fexa_blush_alt_nude.glb', 'Fexa'), ('loona.glb', 'Loona')]
for name, _ in BASE + VARIANTS:
    if not (ROOT / name).is_file(): raise RuntimeError('Missing selected model: ' + name)
if '--verify-apk' in sys.argv:
    with zipfile.ZipFile(ROOT / 'app/build/outputs/apk/debug/app-debug.apk') as apk:
        for name, _ in BASE + VARIANTS:
            entry = 'assets/' + ('inspection/' if (name, _) in VARIANTS else '') + name
            expected = hashlib.sha256((ROOT / name).read_bytes()).digest()
            if hashlib.sha256(apk.read(entry)).digest() != expected: raise RuntimeError('APK model mismatch: ' + name)
    print('Verified all 13 selected GLBs in APK; no movement or rendering validation implied.')
else:
    dest = ROOT / 'app/src/main/assets/inspection'
    dest.mkdir(parents=True, exist_ok=True)
    for name, _ in VARIANTS: shutil.copyfile(ROOT / name, dest / name)
    p = ROOT / 'app/src/main/java/com/chase/companion/AlongsideActivity.kt'
    s = p.read_text()
    entries = BASE + [('inspection/' + name, label) for name, label in VARIANTS]
    import json
    for field, values in [('files', [name for name, _ in entries]), ('names', [label for _, label in entries])]:
        replacement = 'private val ' + field + ' = listOf(' + ', '.join(json.dumps(v) for v in values) + ')'
        s, count = re.subn(r'private val ' + field + r' = listOf\([^\n]*\)', lambda m: replacement, s)
        if count != 1: raise RuntimeError('Unexpected avatar list: ' + field)
    old = '.setItems(names.toTypedArray()) { _, i -> chooseCharacter(i, next) }'
    new = '.setItems((if (next in listOf("Node Control", "Defaults", "Animation Test", "Toggles", "Full Rig Inspection", "Character Settings")) names else names.take(3)).toTypedArray()) { _, i -> chooseCharacter(i, next) }'
    if old in s:
        if s.count(old) != 1: raise RuntimeError('Ambiguous character selector')
        s = s.replace(old, new, 1)
    elif new not in s: raise RuntimeError('Integrated character selector missing; run apply_companion_behavior.py first')
    p.write_text(s)
    print('Staged 10 inspection-only variants; original indices and per-file calibration keys preserved.')
