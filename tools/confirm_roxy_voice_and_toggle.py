"""Independent selected-voice audition and fixed-avatar static inspection."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil

OUT = Path('selected-voice')
SELECTED_SHA = '990d780fb8a2bfa12b2ee849e19a134bc5f4225d7b255769894b9326c044a116'
REFERENCE_SHA = 'e256b57b674321c203e30ce0c4a3cb031d18f1b9b5aaa1a6ab58f07866a42da5'


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1048576), b''):
            h.update(block)
    return h.hexdigest()


def verify_blob(path, expected):
    data = path.read_bytes()
    actual = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
    if actual != expected:
        raise ValueError('Reviewed file changed: ' + str(path))
    return {'bytes': len(data), 'git_blob_sha1': actual, 'sha256': hashlib.sha256(data).hexdigest()}


def voice(report):
    report['stage'] = 'verify_selected_edit_and_converter'
    verify_blob(Path('tools/test_roxy_sentence.py'), '9de9bdded07a160d8a75222598fd9e844c9d90b8')
    source_dir = Path('selected-rise')
    source = source_dir / '03_gentler_rise_keep50.wav'
    if digest(source) != SELECTED_SHA or digest(source_dir / '00_reference.wav') != REFERENCE_SHA:
        raise ValueError('Selected edited baseline or reference checksum mismatch')
    manifest_path = source_dir / 'edit-manifest.json'
    manifest = json.loads(manifest_path.read_text())
    entries = [item for item in manifest.get('edits', []) if item.get('file') == source.name and item.get('sha256') == SELECTED_SHA and item.get('nominal_positive_rise_retained') == 0.5]
    if not manifest.get('success') or len(entries) != 1 or manifest.get('source_sha256') != '042d83110063ae6cf189325b138e7b5caf8ea96a011ead8be9e5815f5b7edc0d':
        raise ValueError('Selected edit provenance mismatch')
    shutil.copyfile(source_dir / '00_reference.wav', OUT / 'reference.wav')
    shutil.copyfile(manifest_path, OUT / 'selected-edit-manifest.json')
    if (source_dir / 'VOICE_MODEL_CARD.txt').is_file():
        shutil.copyfile(source_dir / 'VOICE_MODEL_CARD.txt', OUT / 'VOICE_MODEL_CARD.txt')
    import test_roxy_sentence as core
    original = core.OUT, core.SENTENCE, core.prepare_baseline
    def selected_baseline(directory, destination):
        report['stage'] = 'verify_selected_edited_baseline'
        if Path(directory).resolve() != source_dir.resolve() or digest(source) != SELECTED_SHA:
            raise ValueError('Selected edit changed before conversion')
        shutil.copyfile(source, destination)
        if digest(destination) != SELECTED_SHA:
            raise ValueError('Copied edited baseline differs')
        return {'baseline_voice': 'Piper slow option 03 with saved keep50 final-rise edit', 'baseline_sha256': SELECTED_SHA, 'baseline_listening_approved': True, 'baseline_regenerated': False, 'selection_status': 'Tentative user preference; character-voice confirmation requested', 'delivery_finally_approved': False, 'source_rise_run_id': os.environ.get('SOURCE_RISE_RUN_ID')}
    try:
        core.OUT, core.SENTENCE, core.prepare_baseline = OUT, 'Are you lost?', selected_baseline
        settings = argparse.Namespace(model_dir='voice-model', upstream='vendor-rvc', baseline_dir=str(source_dir), speaker_id=0, pitch_shift=0)
        core.convert(settings, report)
    finally:
        core.OUT, core.SENTENCE, core.prepare_baseline = original
    report['stage'] = 'measure_listening_clips'
    import numpy as np
    import parselmouth
    report['clips'] = {}
    for label, filename in [('reference', 'reference.wav'), ('selected_baseline', 'baseline.wav'), ('converted', 'candidate_sid0_key0.wav')]:
        path = OUT / filename
        sound = parselmouth.Sound(str(path))
        if not np.isfinite(sound.values).all() or not 0.5 <= sound.duration <= 8:
            raise ValueError('Invalid listening clip: ' + label)
        f0 = sound.to_pitch_ac(time_step=0.01, voicing_threshold=0.6, pitch_floor=50, pitch_ceiling=1100).selected_array['frequency']
        voiced = f0[f0 > 0]
        report['clips'][label] = {'file': filename, 'sha256': digest(path), 'seconds': float(sound.duration), 'voiced_f0_percentiles_10_50_90_hz': [float(value) for value in np.percentile(voiced, [10, 50, 90])] if len(voiced) else None}
    report.update(success=True, stage='selected_voice_audition_created', extra_pitch_edit_applied=False, delivery_finally_approved=False)


def avatar(report):
    report['stage'] = 'verify_fixed_avatar_and_inspector'
    report['inputs'] = {
        'fixed': verify_blob(Path('Roxanne_Wolf_Toggle_fix.glb'), 'f07104f5dc2ce542ab8e7f1e7ac2705ebe627dd1'),
        'original': verify_blob(Path('roxanne_wolf_security_breach.glb'), 'af52d8427cdddd5e4659573509449fe2d9703465'),
        'inspector': verify_blob(Path('tools/inspect_roxy_uploads.py'), '80782ab160280b4f93d7dde66ff97949b81797f6'),
    }
    import inspect_roxy_uploads as inspector
    previous_out = inspector.OUT
    try:
        inspector.OUT = OUT
        report['stage'] = 'compare_fixed_rig_structure'
        original, old = inspector.inspect_glb(Path('roxanne_wolf_security_breach.glb'), 'original')
        fixed, new = inspector.inspect_glb(Path('Roxanne_Wolf_Toggle_fix.glb'), 'fixed')
    finally:
        inspector.OUT = previous_out
    common = set(old) & set(new)
    original_joints, fixed_joints = set(original['joint_names']), set(fixed['joint_names'])
    keys = ('translation', 'rotation', 'scale', 'matrix')
    report['models'] = {'original': original, 'fixed': fixed}
    report['comparison'] = {
        'shared_unique_node_names': len(common),
        'shared_joint_names': len(original_joints & fixed_joints),
        'original_joint_names_missing_in_fixed': sorted(original_joints - fixed_joints),
        'fixed_joint_names_not_in_original': sorted(fixed_joints - original_joints),
        'shared_names_with_different_parents': sorted(name for name in common if old[name]['parents'] != new[name]['parents']),
        'shared_names_with_different_local_transforms': sorted(name for name in common if any(old[name][key] != new[name][key] for key in keys)),
        'original_face_name_candidates_missing_in_fixed': sorted(set(original['name_matches_not_anatomy_verified']) - set(fixed['name_matches_not_anatomy_verified'])),
        'bind_pose_equivalence_verified': False, 'runtime_toggle_verified': False,
    }
    report.update(success=True, stage='fixed_avatar_inspected_review_required', active_avatar_changed=False, runtime_compatibility='unverified')


def save(report):
    (OUT / 'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    lines = ['ROXY SELECTED VOICE AUDITION' if report['mode'] == 'voice' else 'ROXY FIXED TOGGLE INSPECTION', 'Result: ' + ('COMPLETED - REVIEW REQUIRED' if report.get('success') else 'NOT COMPLETED'), 'Stage: ' + report['stage']]
    if report['mode'] == 'voice':
        for key in ('baseline_sha256', 'baseline_regenerated', 'selection_status', 'source_rise_run_id', 'speaker_id', 'pitch_shift_semitones', 'synthesizer_seconds', 'feature_pitch_and_synthesis_seconds'):
            if key in report:
                lines.append(key + ': ' + str(report[key]))
        for label, clip in report.get('clips', {}).items():
            lines.append(label + ': ' + json.dumps(clip))
        lines.append('Same ONNX, speaker ID 0 and zero added pitch shift. No Piper regeneration or new pitch edit. Listen for whether the gentler ending survives conversion naturally; no automatic delivery approval.')
    else:
        for label, model in report.get('models', {}).items():
            lines.append(label + ': ' + json.dumps({key: model[key] for key in ('nodes', 'meshes', 'skins', 'joint_nodes', 'animation_names', 'materials', 'images', 'morph_targets_per_primitive')}))
        comparison = report.get('comparison', {})
        if comparison:
            lines.append('Shared unique node names: ' + str(comparison['shared_unique_node_names']))
            lines.append('Shared joint names: ' + str(comparison['shared_joint_names']))
            for key in ('original_joint_names_missing_in_fixed', 'fixed_joint_names_not_in_original', 'shared_names_with_different_parents', 'shared_names_with_different_local_transforms', 'original_face_name_candidates_missing_in_fixed'):
                lines.append(key + ': count=' + str(len(comparison[key])) + '; first 20=' + json.dumps(comparison[key][:20]))
        lines.append('Static inventory only. Bone names are not proof of axis, bind-pose, deformation, facial or runtime compatibility. Active avatar unchanged; no toggle added.')
    if report.get('error'):
        lines.append('Error: ' + report['error'])
    lines.append('No Android, ONNX or existing test-file changes. Voice and avatar jobs run independently.')
    text = '\n'.join(lines) + '\n'
    (OUT / 'report.txt').write_text(text, encoding='utf-8')
    print(text)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as target:
            target.write('## ' + lines[0] + '\n\n```text\n' + text + '```\n')


def main():
    global OUT
    parser = argparse.ArgumentParser()
    parser.add_argument('--mode', choices=('voice', 'avatar'), required=True)
    parser.add_argument('--initialize', action='store_true')
    args = parser.parse_args()
    OUT = Path('selected-voice' if args.mode == 'voice' else 'fixed-toggle')
    OUT.mkdir(exist_ok=True)
    report = {'success': False, 'stage': 'not_started_check_artifact_or_dependencies', 'mode': args.mode, 'android_changed': False}
    if args.initialize:
        save(report)
        return
    try:
        (voice if args.mode == 'voice' else avatar)(report)
    except Exception as error:
        report.update(success=False, error=type(error).__name__ + ': ' + str(error)[:2400])
        if args.mode == 'voice':
            for path in OUT.glob('candidate_*.wav'):
                path.unlink(missing_ok=True)
    finally:
        save(report)
    if not report['success']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
