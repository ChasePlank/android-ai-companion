"""Gentle rising lost contour and localized word stretch, one RVC audition."""
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


def lost_curve(times, hz, start, end, anchor):
    import numpy as np
    mask = (times >= start) & (times <= end)
    selected = times[mask]
    if len(selected) < 5 or selected[-1]-selected[0] < 0.04:
        raise ValueError('Insufficient local pitch points')
    u = np.clip((selected-selected[0])/(selected[-1]-selected[0]), 0, 1)
    target = anchor*np.exp2((1.5*(u*u*(3-2*u)))/12)
    entry = np.clip((selected-start)/0.03, 0, 1)
    gate = 0.5-0.5*np.cos(np.pi*entry)
    result = hz.copy()
    result[mask] = hz[mask]*np.exp2(gate*np.log2(target/hz[mask]))
    return result


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
    if abs(anchor-metadata['local_onset_anchor_hz']) > 1:
        raise ValueError('Local pitch analysis differs unexpectedly')
    adjusted = lost_curve(times, hz, start, end, anchor)
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
    blend = 0.5-0.5*np.cos(np.pi*edge)
    gain = min(1., 0.999/max(float(np.max(np.abs(resynthesized[i0:i1]))), 1e-12))
    mixed = audio[i0:i1]*(1-blend) + resynthesized[i0:i1]*gain*blend
    edited = pcm.copy()
    edited[i0:i1] = np.clip(np.rint(mixed*32768), -32768, 32767).astype(np.int16)
    if not np.array_equal(edited[:i0], pcm[:i0]) or not np.array_equal(edited[i1:], pcm[i1:]):
        raise ValueError('Local pitch edit changed samples outside estimated lost region')
    if np.array_equal(edited, pcm):
        raise ValueError('Local pitch edit had no effect')
    word_end = min(end+0.08, len(pcm)/sr-0.05)
    if not end < word_end < len(pcm)/sr:
        raise ValueError('Insufficient room to preserve the final-consonant margin')
    filters = (
        f'[0:a]asplit=3[p0][w0][s0];[p0]atrim=end={start:.12f},asetpts=PTS-STARTPTS[p];'
        f'[w0]atrim=start={start:.12f}:end={word_end:.12f},asetpts=PTS-STARTPTS,atempo=0.8[w];'
        f'[s0]atrim=start={word_end:.12f},asetpts=PTS-STARTPTS[s];'
        '[p][w]acrossfade=d=0.01:c1=tri:c2=tri[pw];'
        '[pw][s]acrossfade=d=0.01:c1=tri:c2=tri,atempo=0.909090909091[out]'
    )
    shutil.copyfile(reference, OUT / 'reference.wav')
    report.update(original_baseline_sha256=ORIGINAL_SHA, reused_alignment=alignment, local_pitch_curve='smooth rise over voiced lost points', requested_word_rise_semitones=1.5, local_onset_anchor_hz=anchor, requested_word_duration_multiplier=1.25, final_consonant_margin_seconds=word_end-end, requested_global_duration_multiplier=1.10, crossfade_seconds=0.01, local_word_gain=gain, piper_regenerated=False, edits_stacked=False)
    with tempfile.TemporaryDirectory(prefix='roxy-lost-') as temporary:
        directory = Path(temporary)
        pitch_path = directory / 'local-pitch.wav'
        stretched = directory / 'stretched.wav'
        wavfile.write(pitch_path, sr, edited)
        report['stage'] = 'lengthen_lost_with_consonant_margin_and_keep_global_slowdown'
        subprocess.run(['ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error', '-i', str(pitch_path), '-filter_complex', filters, '-map', '[out]', '-c:a', 'pcm_s16le', '-y', str(stretched)], check=True, timeout=60, capture_output=True, text=True)
        rate, values = wavfile.read(stretched)
        if rate != sr or values.dtype != np.int16 or values.ndim != 1 or not 1.10 <= len(values)/len(pcm) <= 1.35:
            raise ValueError('Word/global stretch differs unexpectedly from requested duration')
        report.update(actual_baseline_duration_multiplier=len(values)/len(pcm), original_seconds=len(pcm)/sr, stretched_baseline_seconds=len(values)/sr, prepared_baseline_sha256=digest(stretched))
        import test_roxy_sentence as core
        previous = core.OUT, core.SENTENCE, core.prepare_baseline
        def prepared_baseline(source_dir, destination):
            if Path(source_dir).resolve() != directory.resolve() or digest(stretched) != report['prepared_baseline_sha256']:
                raise ValueError('Prepared baseline changed before conversion')
            shutil.copyfile(stretched, destination)
            if digest(destination) != report['prepared_baseline_sha256']:
                raise ValueError('Prepared baseline copy mismatch')
            return {'baseline_voice': 'Original slow Piper clip, gentle rising lost curve and localized word stretch', 'baseline_sha256': report['prepared_baseline_sha256'], 'baseline_regenerated': False, 'baseline_derived': True, 'baseline_listening_approved': False}
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
    lines = ['ROXY LOST-DELIVERY AUDITION', 'Result: ' + ('REFERENCE AND CANDIDATE CREATED' if report.get('success') else 'NOT COMPLETED'), 'Stage: ' + report['stage']]
    for key in ('requested_word_rise_semitones', 'requested_word_duration_multiplier', 'final_consonant_margin_seconds', 'requested_global_duration_multiplier', 'actual_baseline_duration_multiplier', 'stretched_baseline_seconds', 'candidate_seconds', 'candidate_sha256', 'error'):
        if key in report:
            lines.append(key + ': ' + str(report[key]))
    lines += ['Two listening files only: reference.wav and candidate.wav.', 'This replaces the previous 25-percent contour with a gentle requested +1.5-semitone rise through voiced lost. It does not stack pitch edits.', 'The estimated lost interval plus an 80-ms margin is stretched with atempo=0.8; 10-ms crossfades smooth joins, then the prior global slowdown is applied. Actual durations may differ slightly.', 'The margin is a safeguard, not verified phoneme alignment. Listen for the complete final consonants, natural joins and a slight upward movement; synthesis is not guaranteed accurate.', 'Same ONNX, speaker ID 0 and zero added RVC pitch shift. No Piper regeneration, app changes or avatar changes.']
    text = '\n'.join(lines) + '\n'
    (OUT / 'report.txt').write_text(text, encoding='utf-8')
    print(text)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as target:
            target.write('## Localized lost-delivery audition\n\n```text\n' + text + '```\n')


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
