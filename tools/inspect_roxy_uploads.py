"""Static GLB comparison and reference-audio measurement; not an app change."""
import argparse
from collections import Counter
import csv
import hashlib
import json
import math
import os
from pathlib import Path
import struct
import subprocess

OUT = Path('upload-inspection')
INPUTS = {
    'RoxyAreYouLost.ogg.ogx': '7e6d1365c1530fc3b706d790f571897026f85077',
    'Roxanne_Wolf_Toggle.glb': '7078feeb428961e752cbbadf0efdef379de554fc',
    'roxanne_wolf_security_breach.glb': 'af52d8427cdddd5e4659573509449fe2d9703465',
}


def verify(path, expected):
    size = path.stat().st_size
    if not 0 < size <= 64 * 1024 * 1024:
        raise ValueError('Input size limit: ' + path.name)
    data = path.read_bytes()
    blob = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
    if blob != expected:
        raise ValueError('Input changed since upload inspection was prepared: ' + path.name)
    return {'bytes': size, 'git_blob_sha1': blob, 'sha256': hashlib.sha256(data).hexdigest()}


def inspect_glb(path, label):
    size = path.stat().st_size
    with path.open('rb') as source:
        magic, version, declared = struct.unpack('<4sII', source.read(12))
        length, kind = struct.unpack('<II', source.read(8))
        if magic != b'glTF' or version != 2 or declared != size or kind != 0x4E4F534A or not 0 < length <= min(8 * 1024 * 1024, size - 20):
            raise ValueError('Invalid or unsupported GLB header: ' + path.name)
        gltf = json.loads(source.read(length).decode('utf-8'))
    if gltf.get('asset', {}).get('version') != '2.0':
        raise ValueError('Expected glTF 2.0')
    nodes = gltf.get('nodes', [])
    if not isinstance(nodes, list) or len(nodes) > 20000:
        raise ValueError('Node-count limit exceeded')
    def name(index):
        if not isinstance(index, int) or not 0 <= index < len(nodes):
            raise ValueError('Invalid node index')
        return nodes[index].get('name') or ('#unnamed-' + str(index))
    parents = {i: [] for i in range(len(nodes))}
    for i, node in enumerate(nodes):
        for child in node.get('children', []):
            name(child)
            parents[child].append(i)
    joints = {index for skin in gltf.get('skins', []) for index in skin.get('joints', [])}
    for index in joints:
        name(index)
    rows = []
    for i, node in enumerate(nodes):
        rows.append({'index': i, 'name': name(i), 'parents': '|'.join(name(p) for p in parents[i]), 'is_joint': i in joints, 'mesh': node.get('mesh'), 'translation': node.get('translation', [0, 0, 0]), 'rotation': node.get('rotation', [0, 0, 0, 1]), 'scale': node.get('scale', [1, 1, 1]), 'matrix': node.get('matrix')})
    if rows:
        with (OUT / (label + '-nodes.csv')).open('w', newline='', encoding='utf-8') as target:
            writer = csv.DictWriter(target, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)
    counts = Counter(node.get('name') for node in nodes if node.get('name'))
    unique = {row['name']: row for row in rows if counts.get(row['name']) == 1}
    primitives = [primitive for mesh in gltf.get('meshes', []) for primitive in mesh.get('primitives', [])]
    animations = gltf.get('animations', [])
    targets = sorted({name(channel['target']['node']) for animation in animations for channel in animation.get('channels', []) if 'node' in channel.get('target', {})})
    external = [item['uri'] for key in ('buffers', 'images') for item in gltf.get(key, []) if item.get('uri') and not item['uri'].startswith('data:')]
    summary = {'file': path.name, 'nodes': len(nodes), 'meshes': len(gltf.get('meshes', [])), 'skins': len(gltf.get('skins', [])), 'joint_nodes': len(joints), 'joint_names': sorted(name(i) for i in joints), 'duplicate_names': sorted(key for key, count in counts.items() if count > 1), 'animation_names': [animation.get('name', '(unnamed)') for animation in animations], 'animation_target_nodes': targets, 'primitives': len(primitives), 'morph_targets_per_primitive': [len(p.get('targets', [])) for p in primitives], 'materials': len(gltf.get('materials', [])), 'images': len(gltf.get('images', [])), 'external_resource_uris': external, 'extensions_used': gltf.get('extensionsUsed', []), 'name_matches_not_anatomy_verified': [row['name'] for row in rows if any(term in row['name'].lower() for term in ('jaw', 'head', 'eye', 'lid', 'blink'))]}
    return summary, unique


def inspect_audio(path):
    import numpy as np
    import parselmouth
    raw = subprocess.check_output(['ffprobe', '-v', 'error', '-show_format', '-show_streams', '-of', 'json', str(path)], text=True, timeout=30)
    metadata = json.loads(raw)
    streams = [item for item in metadata.get('streams', []) if item.get('codec_type') == 'audio']
    if len(streams) != 1:
        raise ValueError('Expected one reference audio stream')
    stream = streams[0]
    duration = float(metadata.get('format', {}).get('duration', stream.get('duration', 'nan')))
    if not math.isfinite(duration) or not 0 < duration <= 30:
        raise ValueError('Reference duration outside 0..30 seconds')
    rate = int(stream.get('sample_rate', 0))
    if not 8000 <= rate <= 192000:
        raise ValueError('Unexpected reference sample rate')
    pending = OUT / 'pending-reference.wav'
    subprocess.run(['ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error', '-i', str(path), '-map', '0:a:0', '-vn', '-ac', '1', '-c:a', 'pcm_s16le', '-t', '31', '-y', str(pending)], check=True, timeout=60)
    sound = parselmouth.Sound(str(pending))
    if abs(sound.duration - duration) > 0.1 or not np.isfinite(sound.values).all():
        raise ValueError('Unexpected decoded reference duration or samples')
    rms = float(np.sqrt(np.mean(sound.values ** 2)))
    if rms < 1e-5:
        raise ValueError('Reference is effectively silent')
    pitch = sound.to_pitch_ac(time_step=0.01, voicing_threshold=0.6, pitch_floor=50, pitch_ceiling=1100)
    f0 = pitch.selected_array['frequency']
    voiced = f0[f0 > 0]
    with (OUT / 'reference-pitch.csv').open('w', newline='', encoding='utf-8') as target:
        writer = csv.writer(target)
        writer.writerow(['seconds', 'f0_hz', 'voiced'])
        writer.writerows((float(t), float(f), bool(f > 0)) for t, f in zip(pitch.xs(), f0))
    final = OUT / 'reference-are-you-lost.wav'
    pending.replace(final)
    return {'file': path.name, 'codec': stream.get('codec_name'), 'source_sample_rate': rate, 'source_channels': stream.get('channels'), 'decoded_seconds': float(sound.duration), 'analysis_wav': final.name, 'analysis_wav_sha256': hashlib.sha256(final.read_bytes()).hexdigest(), 'analysis_wav_channels': 1, 'rms': rms, 'voiced_fraction_unpadded': float(np.mean(f0 > 0)) if len(f0) else 0, 'voiced_f0_percentiles_10_50_90_hz': [float(v) for v in np.percentile(voiced, [10, 50, 90])] if len(voiced) else None, 'measurement_method': 'Parselmouth autocorrelation, 10-ms step, 50..1100 Hz; no unvoiced interpolation', 'accent_or_pronunciation_inferred': False}


def save(report):
    (OUT / 'inspection.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    lines = ['ROXY UPLOAD INSPECTION', 'Result: ' + ('INSPECTION COMPLETED' if report.get('success') else 'NOT COMPLETED'), 'Stage: ' + report['stage']]
    if 'audio' in report:
        a = report['audio']
        lines += ['Reference duration: ' + str(a['decoded_seconds']), 'Reference codec: ' + str(a['codec']), 'Voiced F0 percentiles (Hz): ' + str(a['voiced_f0_percentiles_10_50_90_hz'])]
    for label, model in report.get('models', {}).items():
        lines.append(label + ': ' + json.dumps({key: model[key] for key in ('nodes', 'meshes', 'skins', 'joint_nodes', 'animation_names', 'materials', 'images')}))
        lines.append(label + ' name matches (not verified anatomy): ' + ', '.join(model['name_matches_not_anatomy_verified'][:40]))
    if 'comparison' in report:
        lines.append('Structural comparison: ' + json.dumps(report['comparison']))
    if report.get('error'):
        lines.append('Error: ' + report['error'])
    lines += ['Not an accent-learning step. Pitch/timing measurements cannot establish pronunciation or a complete speaking style.', 'Not a toggle implementation or compatibility guarantee. Bind-pose data, axes, deformation, materials and animation behavior still need validation.', 'No app, model, source audio or conversion changes.']
    text = '\n'.join(lines) + '\n'
    (OUT / 'inspection.txt').write_text(text, encoding='utf-8')
    print(text)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as target:
            target.write('## Uploaded reference and avatar inspection\n\n```text\n' + text + '```\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--initialize', action='store_true')
    args = parser.parse_args()
    OUT.mkdir(exist_ok=True)
    report = {'success': False, 'stage': 'not_started_check_dependency_step', 'android_changed': False, 'toggle_ready': False}
    if args.initialize:
        save(report)
        return
    try:
        report['stage'] = 'verify_uploaded_inputs'
        report['inputs'] = {name: verify(Path(name), expected) for name, expected in INPUTS.items()}
        report['stage'] = 'reference_audio_decode_and_measure'
        report['audio'] = inspect_audio(Path('RoxyAreYouLost.ogg.ogx'))
        report['stage'] = 'static_avatar_comparison'
        original, old = inspect_glb(Path('roxanne_wolf_security_breach.glb'), 'original')
        alternate, new = inspect_glb(Path('Roxanne_Wolf_Toggle.glb'), 'alternate')
        common = set(old) & set(new)
        transform_keys = ('translation', 'rotation', 'scale', 'matrix')
        report['models'] = {'original': original, 'alternate': alternate}
        report['comparison'] = {'shared_unique_node_names': len(common), 'original_unique_names_missing_in_alternate': sorted(set(old) - set(new)), 'alternate_unique_names_not_in_original': sorted(set(new) - set(old)), 'shared_names_with_different_named_parents': sorted(name for name in common if old[name]['parents'] != new[name]['parents']), 'shared_names_with_different_local_transforms': sorted(name for name in common if any(old[name][key] != new[name][key] for key in transform_keys)), 'bind_pose_equivalence_verified': False, 'runtime_toggle_verified': False}
        report.update(success=True, stage='inspection_complete_review_required')
    except Exception as error:
        report['error'] = type(error).__name__ + ': ' + str(error)[:2000]
    finally:
        (OUT / 'pending-reference.wav').unlink(missing_ok=True)
        save(report)
    if not report['success']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
