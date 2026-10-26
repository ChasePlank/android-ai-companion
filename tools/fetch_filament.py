import hashlib
import os
import pathlib
import urllib.request
import zipfile

VERSION = '1.75.0'
FILES = {
    'filament-v1.75.0-android.aar': 'cfda4dc400147daf9d7433aee71034b644bf01e928a5bc2fe8f84f53e569d73e',
    'gltfio-v1.75.0-android.aar': '1db729e7e3b372eb00f09cf20a03e6a6ea539a74f640faeb15e3ecaeeba25dc3',
    'filament-utils-v1.75.0-android.aar': '3a2c64c15ebaf6e8db55a7c53f19e04af53f585622ffade7e585189d6e5508cf',
}
DEST = pathlib.Path(__file__).resolve().parents[1] / 'vendor' / 'filament'
DEST.mkdir(parents=True, exist_ok=True)

def report(message):
    print(message, flush=True)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a') as stream:
            stream.write(message + '\n\n')

for name, expected in FILES.items():
    target = DEST / name
    if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest() != expected:
        url = f'https://github.com/google/filament/releases/download/v{VERSION}/{name}'
        request = urllib.request.Request(url, headers={'User-Agent': 'android-ai-companion-build'})
        with urllib.request.urlopen(request, timeout=120) as response:
            data = response.read()
        actual = hashlib.sha256(data).hexdigest()
        if actual != expected:
            report(f'Checksum mismatch for {name}: {actual}')
            raise RuntimeError('Filament download failed verification')
        target.write_bytes(data)
    report(f'Verified {name}')
    with zipfile.ZipFile(target) as archive:
        if 'classes.jar' not in archive.namelist():
            raise RuntimeError(f'Missing classes.jar in {name}')
        metadata = 'META-INF/com/android/build/gradle/aar-metadata.properties'
        if metadata in archive.namelist():
            text = archive.read(metadata).decode()
            report(f'{name} requirements:\n```text\n{text}\n```')
            properties = dict(line.split('=', 1) for line in text.splitlines() if '=' in line and not line.lstrip().startswith('#'))
            minimum = int(properties.get('minCompileSdk', '1').strip())
            if minimum > 36:
                report(f'Cannot use {name}: requires API {minimum}, app compiles against API 36.')
                raise RuntimeError('AAR SDK requirement exceeds compileSdk; checks remain enabled')
