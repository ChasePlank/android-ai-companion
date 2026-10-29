"""Baseline-only speed/punctuation experiment; no voice conversion."""
import argparse
import csv
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import wave

OUT = Path('calm-baselines')
PINS = {
    'RoxyAreYouLost.ogg.ogx': '7e6d1365c1530fc3b706d790f571897026f85077',
    'tools/generate_roxy_baseline.py': '55cf86c133417af27d477f41d49fa7e39fe8c090',
}
VARIANTS = [
    ('01_control_question', 'Are you lost?', 1.0),
    ('02_slower_question', 'Are you lost?', 1.35),
    ('03_slowest_question', 'Are you lost?', 1.5),
    ('04_slower_period', 'Are you lost.', 1.35),
]


def verify(path, expected):
    data = path.read_bytes()
    actual = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
    if actual != expected:
        raise ValueError('Reviewed input/helper changed: ' + str(path))
    return hashlib.sha256(data).hexdigest()


def energy_span(samples, rate):
    import numpy as np
    values = np.asarray(samples, dtype=np.float64).reshape(-1)
    window = max(1, int(round(rate * 0.02)))
    envelopes = np.array([np.sqrt(np.mean(values[i:i+window] ** 2)) for i in range(0, len(values), window)])
    active = np.flatnonzero(envelopes > max(0.0001, float(envelopes.max()) * 0.05))
    if not len(active):
        return None
    start = float(active[0] * window / rate)
    end = min(len(values) / rate, float((active[-1] + 1) * window / rate))
    return {'start_seconds': start, 'end_seconds': end, 'span_seconds': end-start}


def measure(path, label):
    import numpy as np
    import parselmouth
    sound = parselmouth.Sound(str(path))
    if not 0.5 <= sound.duration <= 8 or not np.isfinite(sound.values).all():
        raise ValueError('Invalid audio: ' + label)
    rms = float(np.sqrt(np.mean(sound.values ** 2)))
    if rms < 1e-5:
        raise ValueError('Silent audio: ' + label)
    pitch = sound.to_pitch_ac(time_step=0.01, voicing_threshold=0.6, pitch_floor=50, pitch_ceiling=1100)
    f0 = pitch.selected_array['frequency']
    voiced = f0[f0 > 0]
    with (OUT / (label + '-pitch.csv')).open('w', newline='', encoding='utf-8') as target:
        writer = csv.writer(target)
        writer.writerow(['seconds', 'f0_hz', 'voiced'])
        writer.writerows((float(t), float(f), bool(f > 0)) for t, f in zip(pitch.xs(), f0))
    return {'file': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'seconds': float(sound.duration), 'sample_rate': float(sound.sampling_frequency), 'rms': rms, 'approximate_energy_activity': energy_span(sound.values, int(sound.sampling_frequency)), 'voiced_f0_percentiles_10_50_90_hz': [float(v) for v in np.percentile(voiced, [10, 50, 90])] if len(voiced) else None, 'listening_approved': False}


def generate(report):
    report['stage'] = 'verify_reference_and_asset_loader'
    report['reviewed_inputs_sha256'] = {name: verify(Path(name), pin) for name, pin in PINS.items()}
    import generate_roxy_baseline as neural
    import onnxruntime as ort
    from piper import PiperVoice, SynthesisConfig

    report['packages'] = {package: importlib.metadata.version(package) for package in ('piper-tts', 'onnxruntime', 'numpy', 'praat-parselmouth')}
    report['stage'] = 'decode_reference'
    subprocess.run(['ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error', '-i', 'RoxyAreYouLost.ogg.ogx', '-map', '0:a:0', '-vn', '-ac', '1', '-c:a', 'pcm_s16le', '-y', str(OUT / '00_reference.wav')], check=True, timeout=60, capture_output=True, text=True)
    report['clips'] = {'00_reference': measure(OUT / '00_reference.wav', '00_reference')}
    report['stage'] = 'verify_piper_assets'
    with tempfile.TemporaryDirectory(prefix='roxy-calm-') as temporary:
        directory = Path(temporary)
        report['piper_assets'] = neural.acquire(directory)
        config = json.loads((directory / 'en_US-lessac-medium.onnx.json').read_text())
        if config.get('audio', {}).get('sample_rate') != 22050:
            raise ValueError('Unexpected baseline sample rate')
        report['voice_inference_defaults'] = config.get('inference', {})
        shutil.copyfile(directory / 'MODEL_CARD', OUT / 'VOICE_MODEL_CARD.txt')
        for label, text, scale in VARIANTS:
            report['stage'] = 'synthesize_' + label
            # Fresh session and same requested seed for each variant; no noise-setting changes.
            ort.set_seed(2026)
            voice = PiperVoice.load(str(directory / 'en_US-lessac-medium.onnx'), use_cuda=False)
            synthesis = SynthesisConfig(length_scale=scale, normalize_audio=True, volume=1.0)
            pending = directory / (label + '.wav')
            with wave.open(str(pending), 'wb') as target:
                voice.synthesize_wav(text, target, syn_config=synthesis)
            del voice
            neural.validate_wave(pending)
            final = OUT / (label + '.wav')
            shutil.copyfile(pending, final)
            values = measure(final, label)
            values.update(text=text, length_scale=scale, requested_onnx_seed=2026, noise_parameters='voice defaults; unchanged', generated_baseline=True)
            report['clips'][label] = values
    report.update(success=True, stage='baselines_created_listening_required', voice='en_US-lessac-medium', voice_revision=neural.REV)
    (OUT / 'calm-baseline-manifest.json').write_text(json.dumps(report, indent=2), encoding='utf-8')


def save(report):
    (OUT / 'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    lines = ['ROXY CALM BASELINE COMPARISON', 'Result: ' + ('FOUR OPTIONS CREATED - LISTENING REQUIRED' if report.get('success') else 'NOT COMPLETED'), 'Stage: ' + report['stage'], 'Target: slower calm delivery; no claim that accent or ending intonation is matched.']
    for label, values in report.get('clips', {}).items():
        lines.append(label + ': ' + json.dumps({key: values[key] for key in ('file', 'seconds', 'approximate_energy_activity', 'voiced_f0_percentiles_10_50_90_hz', 'sha256')}))
    if report.get('error'):
        lines.append('Error: ' + report['error'])
    lines += ['01: question at length scale 1.0 (new control). 02: question at 1.35. 03: question at 1.50. 04: period at 1.35.', 'Compare 01/02/03 for pace, then 02/04 for the punctuation experiment. A period is not guaranteed to remove the final rise.', 'Energy activity is only a 20-ms RMS threshold estimate, not word alignment; quiet consonants may be missed.', 'No RVC, pitch shifting, waveform time stretching, inserted silence, avatar changes or Android changes. All new baselines await listening approval.']
    text = '\n'.join(lines) + '\n'
    (OUT / 'report.txt').write_text(text, encoding='utf-8')
    print(text)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as target:
            target.write('## Calm baseline comparison\n\n```text\n' + text + '```\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--initialize', action='store_true')
    args = parser.parse_args()
    OUT.mkdir(exist_ok=True)
    report = {'success': False, 'stage': 'not_started_check_dependencies', 'baseline_listening_approved': False, 'rvc_used': False, 'android_changed': False, 'avatars_changed': False}
    if args.initialize:
        save(report)
        return
    try:
        generate(report)
    except Exception as error:
        report.update(success=False, error=type(error).__name__ + ': ' + str(error)[:2000])
        if isinstance(error, subprocess.CalledProcessError):
            report['command_stderr'] = (error.stderr or '')[-2000:]
        (OUT / 'calm-baseline-manifest.json').unlink(missing_ok=True)
    finally:
        save(report)
    if not report['success']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
