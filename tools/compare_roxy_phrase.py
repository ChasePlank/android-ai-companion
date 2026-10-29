"""Same-phrase listening experiment, not accent transfer or app integration."""
import argparse
import csv
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

OUT = Path('phrase-comparison')
PHRASE = 'Are you lost?'
PINS = {
    'RoxyAreYouLost.ogg.ogx': '7e6d1365c1530fc3b706d790f571897026f85077',
    'tools/test_roxy_sentence.py': '9de9bdded07a160d8a75222598fd9e844c9d90b8',
    'tools/generate_roxy_baseline.py': '55cf86c133417af27d477f41d49fa7e39fe8c090',
}


def verify(path, expected):
    data = path.read_bytes()
    actual = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
    if actual != expected:
        raise ValueError('Reviewed input/helper changed: ' + str(path))
    return hashlib.sha256(data).hexdigest()


def convert_phrase(args, report, core, source, expected_sha):
    original = core.OUT, core.SENTENCE, core.prepare_baseline
    def use_phrase(directory, destination):
        report['stage'] = 'verify_new_phrase_baseline'
        if Path(directory).resolve() != source.parent.resolve() or core.digest(source) != expected_sha:
            raise ValueError('Generated phrase baseline changed before conversion')
        shutil.copyfile(source, destination)
        if core.digest(destination) != expected_sha:
            raise ValueError('Phrase baseline copy mismatch')
        return {'baseline_voice': 'Piper en_US-lessac-medium; newly generated phrase', 'baseline_sha256': expected_sha, 'baseline_listening_approved': False, 'baseline_regenerated': True}
    try:
        core.OUT, core.SENTENCE, core.prepare_baseline = OUT, PHRASE, use_phrase
        settings = argparse.Namespace(model_dir=args.model_dir, upstream=args.upstream, baseline_dir=str(source.parent), speaker_id=0, pitch_shift=0)
        core.convert(settings, report)
    finally:
        core.OUT, core.SENTENCE, core.prepare_baseline = original


def measure(path, label):
    import numpy as np
    import parselmouth
    sound = parselmouth.Sound(str(path))
    if not 0.5 <= sound.duration <= 8 or not np.isfinite(sound.values).all():
        raise ValueError('Invalid comparison audio: ' + label)
    rms = float(np.sqrt(np.mean(sound.values ** 2)))
    if rms < 1e-5:
        raise ValueError('Silent comparison audio: ' + label)
    pitch = sound.to_pitch_ac(time_step=0.01, voicing_threshold=0.6, pitch_floor=50, pitch_ceiling=1100)
    f0 = pitch.selected_array['frequency']
    voiced = f0[f0 > 0]
    with (OUT / (label + '-pitch.csv')).open('w', newline='', encoding='utf-8') as target:
        writer = csv.writer(target)
        writer.writerow(['seconds', 'f0_hz', 'voiced'])
        writer.writerows((float(t), float(f), bool(f > 0)) for t, f in zip(pitch.xs(), f0))
    return {'seconds': float(sound.duration), 'sample_rate': float(sound.sampling_frequency), 'rms': rms, 'voiced_fraction_unpadded': float(np.mean(f0 > 0)) if len(f0) else 0, 'f0_percentiles_10_50_90_hz': [float(v) for v in np.percentile(voiced, [10, 50, 90])] if len(voiced) else None}


def compare(args, report):
    report['stage'] = 'verify_reviewed_inputs_and_helpers'
    report['reviewed_inputs_sha256'] = {name: verify(Path(name), pin) for name, pin in PINS.items()}
    import generate_roxy_baseline as neural
    import test_roxy_sentence as core

    report['stage'] = 'decode_reference'
    subprocess.run(['ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error', '-i', 'RoxyAreYouLost.ogg.ogx', '-map', '0:a:0', '-vn', '-ac', '1', '-c:a', 'pcm_s16le', '-y', str(OUT / 'reference.wav')], check=True, timeout=60, capture_output=True, text=True)
    report['stage'] = 'generate_new_same_phrase_baseline'
    source_dir = OUT / 'source'
    source_dir.mkdir(exist_ok=True)
    source = source_dir / 'baseline.wav'
    with tempfile.TemporaryDirectory(prefix='roxy-phrase-') as temporary:
        directory = Path(temporary)
        report['piper_assets'] = neural.acquire(directory)
        configuration = json.loads((directory / 'en_US-lessac-medium.onnx.json').read_text())
        if configuration.get('audio', {}).get('sample_rate') != 22050:
            raise ValueError('Unexpected pinned baseline sample rate')
        pending = directory / 'baseline.wav'
        subprocess.run([sys.executable, '-m', 'piper', '-m', str(directory / 'en_US-lessac-medium.onnx'), '-f', str(pending), '--', PHRASE], check=True, timeout=120, capture_output=True, text=True)
        baseline = neural.validate_wave(pending)
        shutil.copyfile(pending, source)
        shutil.copyfile(directory / 'MODEL_CARD', OUT / 'VOICE_MODEL_CARD.txt')
    report['piper_version'] = importlib.metadata.version('piper-tts')
    inputs = {'sentence': PHRASE, 'voice': 'en_US-lessac-medium', 'voice_revision': neural.REV, 'baseline_sha256': baseline['wav_sha256'], 'baseline_seconds': baseline['seconds'], 'baseline_listening_approved': False, 'piper_version': report['piper_version'], 'piper_assets': report['piper_assets']}
    (OUT / 'phrase-input.json').write_text(json.dumps(inputs, indent=2), encoding='utf-8')
    convert_phrase(args, report, core, source, baseline['wav_sha256'])
    report['stage'] = 'measure_comparison_clips'
    report['clips'] = {label: measure(OUT / filename, label) for label, filename in [('reference', 'reference.wav'), ('baseline', 'baseline.wav'), ('candidate', 'candidate_sid0_key0.wav')]}
    report.update(success=True, stage='comparison_created_listening_required', baseline_listening_approved=False, accent_transferred=False, reference_used_to_condition_synthesis=False)


def save(report):
    (OUT / 'phrase-report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    lines = ['ROXY SAME-PHRASE COMPARISON', 'Result: ' + ('THREE CLIPS CREATED - LISTENING REQUIRED' if report.get('success') else 'NOT COMPLETED'), 'Stage: ' + report['stage'], 'Phrase: ' + PHRASE, 'Speaker ID: 0 (still unconfirmed)', 'Pitch shift: 0 semitones', 'New phrase baseline listening-approved: False']
    for label, values in report.get('clips', {}).items():
        lines.append(label + ': ' + json.dumps(values))
    for key in ('baseline_sha256', 'synthesizer_seconds', 'feature_pitch_and_synthesis_seconds', 'wall_seconds', 'error'):
        if key in report:
            lines.append(key + ': ' + str(report[key]))
    lines += ['Listen to baseline.wav first. If its words are unclear, do not judge conversion pronunciation from this test.', 'reference.wav is a decoded comparison clip, not a conditioning input. No pitch/rhythm matching, time stretching or accent transfer was applied.', 'No app, avatar, existing test files or ONNX changes. Pitch summaries are descriptive, not an accent score.']
    text = '\n'.join(lines) + '\n'
    (OUT / 'phrase-report.txt').write_text(text, encoding='utf-8')
    print(text)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as target:
            target.write('## Same-phrase listening comparison\n\n```text\n' + text + '```\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--initialize', action='store_true')
    parser.add_argument('--model-dir', default='voice-model')
    parser.add_argument('--upstream', default='vendor-rvc')
    args = parser.parse_args()
    OUT.mkdir(exist_ok=True)
    report = {'success': False, 'stage': 'not_started_check_artifact_or_dependencies', 'sentence': PHRASE, 'android_changed': False, 'avatars_changed': False, 'baseline_listening_approved': False}
    if args.initialize:
        save(report)
        return
    started = time.perf_counter()
    try:
        compare(args, report)
    except Exception as error:
        report.update(success=False, error=type(error).__name__ + ': ' + str(error)[:2000])
        if isinstance(error, subprocess.CalledProcessError):
            report['command_stderr'] = (error.stderr or '')[-2000:]
        for path in OUT.glob('candidate_*.wav'):
            path.unlink(missing_ok=True)
    finally:
        report['wall_seconds'] = time.perf_counter() - started
        save(report)
    if not report['success']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
