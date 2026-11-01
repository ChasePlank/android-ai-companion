import hashlib
import json
import pathlib
import shutil
import tarfile
import tempfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = '8642541b255bfb5dc39cd351a178be09013a55d6'
REV = '8e42d41f70cb6c571f58c3f31bd9287b372d97cc'
REPO = 'Qwen/Qwen3-0.6B-GGUF'
REMOTE = 'Qwen3-0.6B-Q4_K_M.gguf'
source = ROOT / 'vendor/offline-llama'
assets = ROOT / 'vendor/offline-assets/offline'
assets.mkdir(parents=True, exist_ok=True)

def fetch(url, path):
    request = urllib.request.Request(url, headers={'User-Agent': 'Alongside-offline-build'})
    with urllib.request.urlopen(request, timeout=180) as response, open(path, 'wb') as out:
        shutil.copyfileobj(response, out, 1024 * 1024)

def digest(path):
    with open(path, 'rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

if not (source / '.alongside-revision').exists() or (source / '.alongside-revision').read_text() != SOURCE:
    with tempfile.TemporaryDirectory() as work:
        work = pathlib.Path(work)
        archive = work / 'source.tar.gz'
        fetch(f'https://codeload.github.com/ggml-org/llama.cpp/tar.gz/{SOURCE}', archive)
        with tarfile.open(archive) as tar:
            tar.extractall(work / 'unpacked', filter='data')
        unpacked = work / 'unpacked' / ('llama.cpp-' + SOURCE)
        assert (unpacked / 'include/llama.h').is_file()
        if source.exists():
            shutil.rmtree(source)
        shutil.move(str(unpacked), str(source))
        (source / '.alongside-revision').write_text(SOURCE)
metadata = assets / 'model.json'
model = assets / 'qwen3-0.6b-q4_k_m.gguf'
lock = json.loads(metadata.read_text()) if metadata.exists() else {}
valid = lock.get('revision') == REV and model.exists() and model.stat().st_size == lock.get('bytes') and digest(model) == lock.get('sha256')
if not valid:
    with tempfile.TemporaryDirectory() as work:
        info = pathlib.Path(work) / 'tree.json'
        fetch(f'https://huggingface.co/api/models/{REPO}/tree/{REV}', info)
        entry = next(item for item in json.loads(info.read_text()) if item['path'] == REMOTE)
        expected = entry['lfs']['oid']
        assert len(expected) == 64 and all(c in '0123456789abcdef' for c in expected)
        partial = assets / 'model.download'
        try:
            fetch(f'https://huggingface.co/{REPO}/resolve/{REV}/{REMOTE}', partial)
            assert partial.stat().st_size == entry['size'], 'Model size mismatch'
            assert digest(partial) == expected, 'Model checksum mismatch'
            with open(partial, 'rb') as stream:
                assert stream.read(4) == b'GGUF', 'Not a GGUF model'
            partial.replace(model)
        finally:
            partial.unlink(missing_ok=True)
        lock = {'file': model.name, 'sha256': expected, 'bytes': model.stat().st_size, 'repository': REPO, 'revision': REV, 'engine_commit': SOURCE, 'license': 'Apache-2.0'}
        metadata.write_text(json.dumps(lock, indent=2) + '\n')
if not (assets / 'MODEL-LICENSE.txt').exists():
    fetch('https://www.apache.org/licenses/LICENSE-2.0.txt', assets / 'MODEL-LICENSE.txt')
if not (assets / 'MODEL-CARD.md').exists():
    fetch(f'https://huggingface.co/{REPO}/resolve/{REV}/README.md', assets / 'MODEL-CARD.md')
shutil.copyfile(source / 'LICENSE', assets / 'LLAMA-LICENSE.txt')
(assets / 'NOTICE.txt').write_text(f'Alongside offline prototype\nModel: {REPO} at {REV}, Apache-2.0. Original model authors: Qwen / Alibaba Cloud.\nEngine: ggml-org/llama.cpp at {SOURCE}, MIT; see LLAMA-LICENSE.txt.\nWeights are unchanged published Q4_K_M quantization. No affiliation or endorsement is implied.\n')
p = ROOT / 'app/src/main/java/com/chase/companion/AlongsideActivity.kt'
s = p.read_text()
marker = 'CompanionCue.addSettings(this, panel, ink);'
if 'OfflineAi.show(this)' not in s:
    assert s.count(marker) == 1, 'Run the integrated companion source patch before Gradle'
    s = s.replace(marker, marker + ' if (page == "Settings") button(panel, "Test offline AI") { OfflineAi.show(this) };', 1)
    p.write_text(s)
print(f'Bundled offline model verified: {lock["bytes"]} bytes, SHA-256 {lock["sha256"]}; engine {SOURCE}', flush=True)
