"""Quarter local rise, small pitch-preserving slowdown, one RVC audition."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

OUT = Path('quarter-rise')
SOURCE = Path('rise-source')
ORIGINAL_SHA = '042d83110063ae6cf189325b138e7b5caf8ea96a011ead8be9e5815f5b7edc0d'
REFERENCE_SHA = 'e256b57b674321c203e30ce0c4a3cb031d18f1b9b5aaa1a6ab58f07866a42da5'


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1048576), b''):
            h.update(block)
    return h.hexdigest()


def quarter_curve(times, hz, start, end, anchor):
    import numpy as np
    edge = np.minimum(np.clip((times-start)/0.03, 0, 1), np.clip((end-times)/0.03, 0, 1))
    gate = 0.5 - 0.5*np.cos(np.pi*edge)
    rise = np.maximum(0, 12*np.log2(hz/anchor))
    return hz*np.exp2(-np.minimum(9.0, 0.75*rise)*gate/12)


def audition(report):
    import numpy as np
    import parselmouth
    from parselmouth.praat import call
    from scipy.io import wavfile
    report['stage'] = 'verify_original_clip_and_alignment_provenance'
    original_path = SOURCE / '01_selected_original.wav'
    reference = SOURCE / '00_reference.wav'
    if digest(original_path) != ORIGINAL_SHA or digest(reference) != REFERENCE_SHA:
        raise ValueError('Original slow clip or reference checksum mismatch')
    metadata = json.loads((SOURCE / 'edit-manifest.json').read_text())
    if not metadata.get('success') or metadata.get('source_sha256') != ORIGINAL_SHA:
        raise ValueError('Prior alignment provenance mismatch')
    alignment = metadata['alignment']
    start, end = alignment['lost_start_seconds'], alignment['lost_end_seconds']
    if alignment.get('transcript') != 'ARE YOU LOST' or abs(start-0.4841931526709044) > 1e-8 or abs(end-0.8675127318687037) > 1e-8:
        raise ValueError('Unexpected prior word alignment')
    helper = Path('tools/test_roxy_sentence.py').read_bytes()
    if hashlib.sha1(b'blob ' + str(len(helper)).encode() + b'\0' + helper).hexdigest() != '9de9bdded07a160d8a75222598fd9e844c9d90b8':
        raise ValueError('Reviewed converter changed')
    sr, pcm = wavfile.read(original_path)
    if sr != 22050 or pcm.dtype != np.int16 or pcm.ndim != 1:
        raise ValueError('Unexpected original PCM')
    audio = pcm.astype(np.float64)/32768
    sound = parselmouth.Sound(audio, sampling_frequency=sr)
    manipulation = call(sound, 'To Manipulation', 0.01, 50, 600)
    tier = call(manipulation, 'Extract pitch tier')
    count = int(call(tier, 'Get number of points'))
    times = np.array([call(tier, 'Get time from index', i) for i in range(1, count+1)], dtype=float)
    hz = np.array([call(tier, 'Get value at index', i) for i in range(1, count+1)], dtype=float)
    local = hz[(times >= start+0.01) & (times <= end-0.01)]
    if len(local) < 5 or not np.isfinite(hz).all() or np.any(hz <= 0) or np.max(np.abs(12*np.diff(np.log2(local)))) > 9:
        raise ValueError('Unreliable local pitch tier')
    anchor = float(np.median(local[:3]))
    rise = float(np.max(12*np.log2(local/anchor)))
    if not 0.5 <= rise <= 12 or abs(anchor-metadata['local_onset_anchor_hz']) > 1:
        raise ValueError('Local pitch analysis differs unexpectedly')
    adjusted = quarter_curve(times, hz, start, end, anchor)
    call(tier, 'Remove points between', 0, sound.duration)
    for t, frequency in zip(times, adjusted):
        call(tier, 'Add point', float(t), float(frequency))
    call([tier, manipulation], 'Replace pitch tier')
    resynthesized = call(manipulation, 'Get resynthesis (overlap-add)').values[0]
    if resynthesized.shape != audio.shape or not np.isfinite(resynthesized).all():
        raise ValueError('Pitch edit changed duration or produced nonfinite audio')
    i0, i1 = int(np.ceil(start*sr)), int(np.floor(end*sr))
    positions = np.arange(i0, i1)/sr
    edge = np.minimum(np.clip((positions-start)/0.03, 0, 1), np.clip((end-positions)/0.03, 0, 1))
    blend = 0.5 - 0.5*np.cos(np.pi*edge)
    gain = min(1., 0.999/max(float(np.max(np.abs(resynthesized[i0:i1]))), 1e-12))
    mixed = audio[i0:i1]*(1-blend) + resynthesized[i0:i1]*gain*blend
    edited = pcm.copy()
    edited[i0:i1] = np.clip(np.rint(mixed*32768), -32768, 32767).astype(np.int16)
    if not np.array_equal(edited[:i0], pcm[:i0]) or not np.array_equal(edited[i1:], pcm[i1:]):
        raise ValueError('Local edit changed samples outside estimated lost region')
    if np.array_equal(edited, pcm):
        raise ValueError('Local edit had no effect')
    shutil.copyfile(reference, OUT / 'reference.wav')
    report.update(original_baseline_sha256=ORIGINAL_SHA, reused_alignment=alignment, nominal_positive_rise_retained=0.25, pitch_cut_cap_semitones=9.0, prior_three_semitone_cap_removed=True, local_word_gain=gain, requested_duration_multiplier=1.10, piper_regenerated=False)
    with tempfile.TemporaryDirectory(prefix='roxy-quarter-') as temporary:
        directory = Path(temporary)
        pitch_path = directory / 'local-pitch.wav'
        stretched = directory / 'stretched.wav'
        wavfile.write(pitch_path, sr, edited)
        report['stage'] = 'stretch_baseline_about_ten_percent'
        subprocess.run(['ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error', '-i', str(pitch_path), '-af', 'atempo=0.909090909091', '-c:a', 'pcm_s16le', '-y', str(stretched)], check=True, timeout=60, capture_output=True, text=True)
        rate, values = wavfile.read(stretched)
        if rate != sr or values.dtype != np.int16 or values.ndim != 1 or not 1.04 <= len(values)/len(pcm) <= 1.16:
            raise ValueError('Tempo stretch differs unexpectedly from requested duration')
        report.update(actual_baseline_duration_multiplier=len(values)/len(pcm), original_seconds=len(pcm)/sr, stretched_baseline_seconds=len(values)/sr, prepared_baseline_sha256=digest(stretched))
        import test_roxy_sentence as core
        previous = core.OUT, core.SENTENCE, core.prepare_baseline
        def prepared_baseline(source_dir, destination):
            if Path(source_dir).resolve() != directory.resolve() or digest(stretched) != report['prepared_baseline_sha256']:
                raise ValueError('Prepared baseline changed before conversion')
            shutil.copyfile(stretched, destination)
            if digest(destination) != report['prepared_baseline_sha256']:
                raise ValueError('Prepared baseline copy mismatch')
            return {'baseline_voice': 'Existing slow Piper clip, quarter local rise and about 10 percent longer', 'baseline_sha256': report['prepared_baseline_sha256'], 'baseline_regenerated': False, 'baseline_derived': True, 'baseline_listening_approved': False}
        try:
            core.OUT, core.SENTENCE, core.prepare_baseline = OUT, 'Are you lost?', prepared_baseline
            settings = argparse.Namespace(model_dir='voice-model', upstream='vendor-rvc', baseline_dir=str(directory), speaker_id=0, pitch_shift=0)
            core.convert(settings, report)
        finally:
            core.OUT, core.SENTENCE, core.prepare_baseline = previous
    candidate = OUT / 'candidate.wav'
    (OUT / 'candidate_sid0_key0.wav').replace(candidate)
    result = parselmouth.Sound(str(candidate))
    if not np.isfinite(result.values).all():
        raise ValueError('Nonfinite converted candidate')
    report.update(success=True, stage='reference_and_candidate_created', output_file='candidate.wav', candidate_sha256=digest(candidate), candidate_seconds=float(result.duration), listening_approved=False)
    (OUT / 'baseline.wav').unlink(missing_ok=True)


def save(report):
    (OUT / 'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    lines = ['ROXY QUARTER-RISE AUDITION', 'Result: ' + ('REFERENCE AND CANDIDATE CREATED' if report.get('success') else 'NOT COMPLETED'), 'Stage: ' + report['stage']]
    for key in ('nominal_positive_rise_retained', 'pitch_cut_cap_semitones', 'requested_duration_multiplier', 'actual_baseline_duration_multiplier', 'stretched_baseline_seconds', 'candidate_seconds', 'candidate_sha256', 'error'):
        if key in report:
            lines.append(key + ': ' + str(report[key]))
    lines += ['Two listening files only: reference.wav and candidate.wav.', '25 percent describes the requested positive local pitch excursion, with smooth boundaries and a 9-semitone cut safeguard; not a quarter of the whole voice pitch.', 'Tempo slowdown is applied to the edited baseline, not added silence. Converted timing and pitch still require listening; no accuracy guarantee.', 'Same ONNX, speaker ID 0 and zero added RVC pitch shift. No Piper regeneration, app changes or avatar changes.']
    text = '\n'.join(lines) + '\n'
    (OUT / 'report.txt').write_text(text, encoding='utf-8')
    print(text)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as target:
            target.write('## Quarter-rise audition\n\n```text\n' + text + '```\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--initialize', action='store_true')
    args = parser.parse_args()
    OUT.mkdir(exist_ok=True)
    report = {'success': False, 'stage': 'not_started_check_artifacts_or_dependencies', 'android_changed': False, 'avatars_changed': False}
    if args.initialize:
        save(report)
        return
    try:
        audition(report)
    except Exception as error:
        report.update(success=False, error=type(error).__name__ + ': ' + str(error)[:2400])
        if isinstance(error, subprocess.CalledProcessError):
            report['command_stderr'] = (error.stderr or '')[-2000:]
        for path in OUT.glob('candidate*.wav'):
            path.unlink(missing_ok=True)
    finally:
        save(report)
    if not report['success']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
