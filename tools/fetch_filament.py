import hashlib
import pathlib
import urllib.request
import zipfile

VERSION = '1.78.1'
FILES = {
    'filament-v1.78.1-android.aar': '457ff0102860b6dbc2483899f2fd9b4b6f99cfb56715d5aa9dca5915b474b63a',
    'gltfio-v1.78.1-android.aar': '15b4204235268c7ffcd7e68d83a7d6e550f0bb7cb518a1edfad020e5c84a32fc',
    'filament-utils-v1.78.1-android.aar': '225a79013b5e37d3d132dda50de772834221d5de6bd0a59704156424303596d6',
}
DEST = pathlib.Path(__file__).resolve().parents[1] / 'vendor' / 'filament'
DEST.mkdir(parents=True, exist_ok=True)
for name, expected in FILES.items():
    target = DEST / name
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == expected:
        print('Verified cached artifact:', name)
    else:
        url = f'https://github.com/google/filament/releases/download/v{VERSION}/{name}'
        request = urllib.request.Request(url, headers={'User-Agent': 'android-ai-companion-build'})
        with urllib.request.urlopen(request, timeout=120) as response:
            data = response.read()
        actual = hashlib.sha256(data).hexdigest()
        if actual != expected:
            raise RuntimeError(f'Checksum mismatch for {name}: {actual}')
        target.write_bytes(data)
        print('Downloaded and verified:', name, len(data), 'bytes')
    with zipfile.ZipFile(target) as archive:
        metadata = 'META-INF/com/android/build/gradle/aar-metadata.properties'
        if metadata in archive.namelist():
            print(name, 'AAR requirements:\n', archive.read(metadata).decode())
        if 'classes.jar' not in archive.namelist():
            raise RuntimeError(f'Missing classes.jar in {name}')
