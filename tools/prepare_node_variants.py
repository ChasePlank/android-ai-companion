import hashlib
import json
import pathlib
import re
import struct
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
DIANE = 'km_diane_foxington_bikini_2.glb'
VARIANTS = [
    ('bonfie (1).glb', 'Bonfie'),
    ('bonfie_nsfw_blender.glb', 'naked Bonfie'),
    ('classic_chiku.glb', 'Chiku'),
    ('chiku_nude.glb', 'naked Chiku'),
    ('fredina_cally3d.glb', 'Fredina'),
    ('fredina_nude_fnia.glb', 'naked Fredina'),
    ('glamrock_chica.glb', 'Glamrock Chica'),
    (DIANE, 'Diane Foxington'),
    ('marie_blender.glb', 'Marie'),
    ('marie_nsfw.glb', 'naked Marie'),
]
BASE = [('roxanne_wolf_security_breach.glb', 'Roxanne'), ('fexa_blush_alt_nude.glb', 'Fexa'), ('loona.glb', 'Loona')]

def packaged(name):
    data = (ROOT / name).read_bytes()
    if name != DIANE: return data, None
    magic, version, length = struct.unpack_from('<4sII', data)
    if magic != b'glTF' or version != 2 or length != len(data): raise RuntimeError('Invalid Diane GLB header')
    chunks = []; pos = 12
    while pos < length:
        if pos + 8 > length: raise RuntimeError('Truncated Diane chunk header')
        size, kind = struct.unpack_from('<II', data, pos); pos += 8
        if size % 4 or pos + size > length: raise RuntimeError('Invalid Diane chunk')
        chunks.append((kind, data[pos:pos+size])); pos += size
    if not chunks or chunks[0][0] != 0x4E4F534A: raise RuntimeError('Missing Diane JSON')
    g = json.loads(chunks[0][1]); nodes = g.get('nodes', [])
    if not nodes: raise RuntimeError('No Diane nodes')
    original = json.loads(json.dumps(g))
    parents = {}
    for i, node in enumerate(nodes):
        for child in node.get('children', []):
            if type(child) is not int or not 0 <= child < len(nodes): raise RuntimeError('Invalid Diane child index')
            parents.setdefault(child, []).append(i)
    mapping = {'source_sha256': hashlib.sha256(data).hexdigest(), 'nodes': []}
    for i, node in enumerate(nodes):
        label = 'Node %04d' % i
        mapping['nodes'].append({'index': i, 'label': label, 'original_name': node.get('name'), 'parents': parents.get(i, [])})
        node['name'] = label
    restored = json.loads(json.dumps(g))
    for i, node in enumerate(restored['nodes']):
        if 'name' in original['nodes'][i]: node['name'] = original['nodes'][i]['name']
        else: node.pop('name', None)
    if restored != original: raise RuntimeError('Unexpected non-name metadata change')
    payload = json.dumps(g, ensure_ascii=True, separators=(',', ':')).encode('utf-8')
    payload += b' ' * (-len(payload) % 4)
    chunks[0] = (chunks[0][0], payload)
    body = b''.join(struct.pack('<II', len(value), kind) + value for kind, value in chunks)
    result = struct.pack('<4sII', b'glTF', 2, 12 + len(body)) + body
    mapping['packaged_sha256'] = hashlib.sha256(result).hexdigest()
    return result, mapping

for name, _ in BASE + VARIANTS:
    if not (ROOT / name).is_file(): raise RuntimeError('Missing selected model: ' + name)
if '--verify-apk' in sys.argv:
    with zipfile.ZipFile(ROOT / 'app/build/outputs/apk/debug/app-debug.apk') as apk:
        for name, label in BASE + VARIANTS:
            entry = 'assets/' + ('inspection/' if name in {n for n, _ in VARIANTS} else '') + name
            expected, mapping = packaged(name)
            if hashlib.sha256(apk.read(entry)).digest() != hashlib.sha256(expected).digest(): raise RuntimeError('APK model mismatch: ' + name)
            if mapping is not None and json.loads(apk.read('assets/inspection/diane-node-map.json')) != mapping: raise RuntimeError('Diane node-map mismatch')
    print('Verified 13 packaged GLBs, including deterministic name-only Diane copy and node map.')
else:
    dest = ROOT / 'app/src/main/assets/inspection'; dest.mkdir(parents=True, exist_ok=True)
    for name, label in VARIANTS:
        data, mapping = packaged(name); (dest / name).write_bytes(data)
        if mapping is not None: (dest / 'diane-node-map.json').write_text(json.dumps(mapping, indent=2))
    p = ROOT / 'app/src/main/java/com/chase/companion/AlongsideActivity.kt'; s = p.read_text()
    entries = BASE + [('inspection/' + name, label) for name, label in VARIANTS]
    for field, values in [('files', [name for name, _ in entries]), ('names', [label for _, label in entries])]:
        replacement = 'private val ' + field + ' = listOf(' + ', '.join(json.dumps(v) for v in values) + ')'
        s, count = re.subn(r'private val ' + field + r' = listOf\([^\n]*\)', lambda m: replacement, s)
        if count != 1: raise RuntimeError('Unexpected avatar list: ' + field)
    old = '.setItems(names.toTypedArray()) { _, i -> chooseCharacter(i, next) }'
    new = '.setItems((if (next in listOf("Node Control", "Defaults", "Animation Test", "Toggles", "Full Rig Inspection", "Character Settings")) names else names.take(3)).toTypedArray()) { _, i -> chooseCharacter(i, next) }'
    if old in s:
        if s.count(old) != 1: raise RuntimeError('Ambiguous character selector')
        s = s.replace(old, new, 1)
    elif new not in s: raise RuntimeError('Integrated character selector missing')
    def replace_once(old, new):
        global s
        if s.count(old) != 1: raise RuntimeError('Manual mapping source anchor changed: ' + old[:80])
        s = s.replace(old, new, 1)
    if 'private fun nodeDisplayName(' not in s:
        replace_once('    private fun nodePage() {', '''    private fun nodeDisplayName(key: String): String {
        val alias = profile.optJSONObject("nodeAliases")?.optString(key, "")?.trim().orEmpty()
        return if (alias.isEmpty()) key else "$key · $alias"
    }
    private fun nodePage() {''')
        replace_once('(this, android.R.layout.simple_spinner_dropdown_item, values)', '(this, android.R.layout.simple_spinner_dropdown_item, values.map { nodeDisplayName(it) })')
        replace_once('button(panel, "Set selected node default")', '''button(panel, "Name selected node / edit alias") { model {
            val key = selectedNode
            check(key in entities) { "Select a node first" }
            val input = EditText(this).apply {
                hint = "Example: Head, Left elbow, Jaw"
                setSingleLine(true)
                setText(profile.optJSONObject("nodeAliases")?.optString(key, "") ?: "")
            }
            AlertDialog.Builder(this).setTitle("Alias for $key").setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save") { _, _ -> model {
                    val alias = input.text.toString().trim()
                    check(alias.length <= 80) { "Alias must be 80 characters or fewer" }
                    if (alias.isEmpty()) section("nodeAliases").remove(key) else section("nodeAliases").put(key, alias)
                    save("node alias saved; limits and rest pose unchanged")
                    renderPage()
                } }.show()
        } }
        button(panel, "Set selected node default")''')
    p.write_text(s)
    print('Staged base/naked labels and node aliases; Diane source indices encoded as ASCII node names. Uploaded files unchanged; old Diane calibration is not migrated.')
