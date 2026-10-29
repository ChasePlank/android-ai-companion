"""Generate an unconverted neural baseline for human intelligibility review."""
import argparse
import array
import hashlib
import importlib.metadata
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import urllib.request
import wave

OUT = Path('voice-baseline')
TEXT = 'Hey there. This is a short voice conversion test.'
REV = '5041a7a49ae5b3a0f30e6837769eb03fdbb231bd'
BASE = 'https://huggingface.co/rhasspy/piper-voices/resolve/' + REV + '/en/en_US/lessac/medium/'
ASSETS = {
    'en_US-lessac-medium.onnx': ('sha256', '5efe09e69902187827af646e1a6e9d269dee769f9877d17b16b1b46eeaaf019f', 63201294),
    'en_US-lessac-medium.onnx.json': ('git', 'c67cea2c9a7a6501d89f7b2cdff411bc49e54a28', 4885),
    'MODEL_CARD': ('git', 'c0b9a3d60eb5b2e852aedb2a806031f85d02d40b', 351),
}


def sha256(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            h.update(block)
    return h.hexdigest()


def acquire(directory):
    verified = {}
    for name, (kind, expected, expected_bytes) in ASSETS.items():
        destination = directory / name
        request = urllib.request.Request(BASE + name, headers={'User-Agent': 'RoxyBaselineReview/1.0'})
        count = 0
        with urllib.request.urlopen(request, timeout=120) as source, destination.open('wb') as target:
            if not source.geturl().startswith('https://'):
                raise ValueError('Non-HTTPS asset rejected')
            for block in iter(lambda: source.read(1048576), b''):
                count += len(block)
                if count > expected_bytes:
                    raise ValueError('Asset exceeds pinned size: ' + name)
                target.write(block)
        if count != expected_bytes:
            raise ValueError('Asset size mismatch: ' + name)
        if kind == 'git':
            data = destination.read_bytes()
            actual = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        else:
            actual = sha256(destination)
        if actual != expected:
            raise ValueError('Asset checksum mismatch: ' + name)
        verified[name] = {'kind': kind, 'checksum': actual, 'bytes': count}
    return verified


def validate_wave(path):
    if not 44 < path.stat().st_size < 2000000:
        raise ValueError('Unexpected WAV size')
    with wave.open(str(path), 'rb') as source:
        rate, frames = source.getframerate(), source.getnframes()
        if source.getnchannels() != 1 or source.getsampwidth() != 2 or source.getcomptype() != 'NONE':
            raise ValueError('Expected mono uncompressed 16-bit PCM')
        if rate != 22050 or not 0.5 <= frames / rate <= 8:
            raise ValueError('Unexpected native sample rate or duration')
        pcm = source.readframes(frames)
    if len(pcm) != frames * 2:
        raise ValueError('Truncated PCM data')
    samples = array.array('h', pcm)
    if sys.byteorder != 'little':
        samples.byteswap()
    peak = max(abs(value) for value in samples) / 32768
    rms = math.sqrt(math.fsum((value / 32768) ** 2 for value in samples) / frames)
    if not math.isfinite(rms) or rms < 0.0001:
        raise ValueError('Baseline is effectively silent')
    return {'sample_rate': rate, 'channels': 1, 'sample_width_bytes': 2, 'frames': frames, 'seconds': frames / rate, 'peak': peak, 'rms': rms, 'full_scale_fraction': sum(abs(value) >= 32767 for value in samples) / frames, 'wav_sha256': sha256(path)}


def report_files(report):
    OUT.mkdir(exist_ok=True)
    (OUT / 'baseline-report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    lines = ['ROXY NEURAL BASELINE REVIEW', 'Result: ' + ('WAV CREATED - LISTENING REVIEW REQUIRED' if report.get('success') else 'NOT COMPLETED'), 'Stage: ' + report['stage'], 'Sentence: ' + TEXT, 'Voice: Piper en_US-lessac-medium']
    for key in ('seconds', 'sample_rate', 'channels', 'peak', 'rms', 'wav_sha256', 'error'):
        if key in report:
            lines.append(key + ': ' + str(report[key]))
    lines += ['No RVC conversion, pitch shift, resampling or Android changes.', 'Check every word and initial consonant. Signal checks do not prove intelligibility.', 'This WAV is not the character voice. Keep it unchanged if approved.']
    text = '\n'.join(lines) + '\n'
    (OUT / 'baseline-report.txt').write_text(text, encoding='utf-8')
    print(text)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as target:
            target.write('## Neural baseline review\n\n```text\n' + text + '```\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--initialize', action='store_true')
    args = parser.parse_args()
    OUT.mkdir(exist_ok=True)
    report = {'success': False, 'stage': 'not_started_check_dependency_step', 'sentence': TEXT, 'voice': 'en_US-lessac-medium', 'voice_revision': REV, 'listening_approved': False, 'rvc_used': False, 'android_changed': False}
    if args.initialize:
        report_files(report)
        return
    (OUT / 'baseline.wav').unlink(missing_ok=True)
    (OUT / 'baseline-manifest.json').unlink(missing_ok=True)
    try:
        report['piper_version'] = importlib.metadata.version('piper-tts')
        report['stage'] = 'verify_neural_voice_assets'
        with tempfile.TemporaryDirectory(prefix='roxy-baseline-') as temporary:
            directory = Path(temporary)
            report['assets'] = acquire(directory)
            configuration = json.loads((directory / 'en_US-lessac-medium.onnx.json').read_text())
            if configuration.get('audio', {}).get('sample_rate') != 22050:
                raise ValueError('Unexpected voice sample rate')
            (OUT / 'VOICE_MODEL_CARD.txt').write_bytes((directory / 'MODEL_CARD').read_bytes())
            report['stage'] = 'generate_native_wav'
            pending = directory / 'baseline.wav'
            subprocess.run([sys.executable, '-m', 'piper', '-m', str(directory / 'en_US-lessac-medium.onnx'), '-f', str(pending), '--', TEXT], check=True, timeout=120, capture_output=True, text=True)
            report['stage'] = 'validate_pcm_wav'
            report.update(validate_wave(pending))
            # Preserve exactly the WAV produced by Piper: no editing or re-encoding.
            (OUT / 'baseline.wav').write_bytes(pending.read_bytes())
        (OUT / 'sentence.txt').write_text(TEXT + '\n', encoding='utf-8')
        report.update(success=True, stage='awaiting_human_listening_review')
        (OUT / 'baseline-manifest.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    except Exception as error:
        report.update(success=False, error=type(error).__name__ + ': ' + str(error)[:1500])
        if isinstance(error, subprocess.CalledProcessError):
            report['engine_stderr'] = (error.stderr or '')[-2000:]
        (OUT / 'baseline.wav').unlink(missing_ok=True)
        (OUT / 'baseline-manifest.json').unlink(missing_ok=True)
    finally:
        report_files(report)
    if not report['success']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
