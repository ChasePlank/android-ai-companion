"""Local final-word pitch experiment on an exact selected baseline; no RVC."""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import shutil
import wave

OUT = Path('final-rise')
SOURCE = Path('selected-calm')
BASELINE_SHA = '042d83110063ae6cf189325b138e7b5caf8ea96a011ead8be9e5815f5b7edc0d'
REFERENCE_SHA = 'e256b57b674321c203e30ce0c4a3cb031d18f1b9b5aaa1a6ab58f07866a42da5'


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1048576), b''):
            h.update(block)
    return h.hexdigest()


def gentler_pitch(times, frequencies, start, end, anchor, retain):
    import numpy as np
    edge = np.minimum(np.clip((times-start)/0.03, 0, 1), np.clip((end-times)/0.03, 0, 1))
    gate = 0.5 - 0.5*np.cos(np.pi*edge)
    rise = np.maximum(0, 12*np.log2(frequencies/anchor))
    cut = np.minimum(3.0, (1-retain)*rise)*gate
    return frequencies*np.exp2(-cut/12)


def align_word(audio, rate, report):
    import torch
    import torchaudio
    torch.set_num_threads(2)
    bundle = torchaudio.pipelines.WAV2VEC2_ASR_BASE_960H
    # Official TorchAudio checkpoint; explicit restricted weights load, no globals added.
    model = bundle.get_model(dl_kwargs={'weights_only': True}).eval()
    checkpoint = Path(torch.hub.get_dir()) / 'checkpoints' / Path(bundle._path).name
    report['alignment_model'] = {'bundle': 'WAV2VEC2_ASR_BASE_960H', 'checkpoint_sha256': digest(checkpoint), 'torch': torch.__version__, 'torchaudio': torchaudio.__version__, 'weights_only': True, 'checkpoint_checksum_recorded_not_pre_pinned': True}
    values = torch.from_numpy(audio.astype('float32'))[None]
    values = torchaudio.functional.resample(values, rate, bundle.sample_rate)
    with torch.inference_mode():
        logits, _ = model(values)
        emissions = logits.log_softmax(-1)
    labels = bundle.get_labels()
    ids = torch.unique_consecutive(emissions[0].argmax(-1)).tolist()
    decoded = ' '.join(''.join(labels[i] for i in ids if i != 0).replace('|', ' ').split())
    if decoded != 'ARE YOU LOST':
        raise ValueError('ASR sanity check failed; refusing guessed word boundaries: ' + decoded)
    transcript = 'ARE|YOU|LOST'
    tokens = [labels.index(letter) for letter in transcript]
    targets = torch.tensor([tokens], dtype=torch.int32)
    aligned, scores = torchaudio.functional.forced_align(emissions, targets, blank=0)
    spans = torchaudio.functional.merge_tokens(aligned[0], scores[0].exp())
    if [span.token for span in spans] != tokens:
        raise ValueError('Forced alignment token mismatch')
    seconds_per_frame = (len(audio)/rate) / emissions.shape[1]
    characters = [{'character': c, 'start': float(s.start*seconds_per_frame), 'end': float(s.end*seconds_per_frame), 'score': float(s.score)} for c, s in zip(transcript, spans)]
    start, end = characters[8]['start'], characters[-1]['end']
    confidence = sum(item['score'] for item in characters[8:]) / 4
    if not 0.15 < start < end < len(audio)/rate or not 0.1 < end-start < 0.9 or confidence < 0.3 or min(item['score'] for item in characters[8:]) < 0.05:
        raise ValueError('Final-word alignment failed conservative checks')
    alignment = {'transcript': decoded, 'characters': characters, 'lost_start_seconds': start, 'lost_end_seconds': end, 'lost_mean_token_score': confidence, 'human_boundary_verified': False, 'timing_is_model_estimate': True}
    (OUT / 'alignment.json').write_text(json.dumps(alignment, indent=2), encoding='utf-8')
    report['alignment'] = alignment
    return start, end


def run(report):
    import numpy as np
    import parselmouth
    from parselmouth.praat import call
    from scipy.io import wavfile
    baseline = SOURCE / '03_slowest_question.wav'
    reference = SOURCE / '00_reference.wav'
    report['stage'] = 'verify_exact_selected_clip'
    if digest(baseline) != BASELINE_SHA or digest(reference) != REFERENCE_SHA:
        raise ValueError('Selected baseline or reference checksum mismatch')
    manifest = json.loads((SOURCE / 'calm-baseline-manifest.json').read_text())
    selected = manifest.get('clips', {}).get('03_slowest_question', {})
    if selected.get('sha256') != BASELINE_SHA or selected.get('text') != 'Are you lost?' or selected.get('length_scale') != 1.5:
        raise ValueError('Selected baseline metadata mismatch')
    rate, pcm = wavfile.read(baseline)
    if rate != 22050 or pcm.dtype != np.int16 or pcm.ndim != 1 or not 0.5 < len(pcm)/rate < 8:
        raise ValueError('Unexpected selected baseline PCM')
    original = pcm.astype(np.float64)/32768
    shutil.copyfile(baseline, OUT / '01_selected_original.wav')
    shutil.copyfile(reference, OUT / '00_reference.wav')
    card = SOURCE / 'VOICE_MODEL_CARD.txt'
    if card.is_file():
        shutil.copyfile(card, OUT / 'VOICE_MODEL_CARD.txt')
    report.update(source_sha256=BASELINE_SHA, source_calm_run_id=os.environ.get('SOURCE_CALM_RUN_ID'), original_seconds=len(pcm)/rate, baseline_regenerated=False)
    report['stage'] = 'estimate_final_word_boundaries'
    start, end = align_word(original, rate, report)
    report['stage'] = 'inspect_local_pitch_tier'
    sound = parselmouth.Sound(original, sampling_frequency=rate)
    manipulation = call(sound, 'To Manipulation', 0.01, 50, 600)
    tier = call(manipulation, 'Extract pitch tier')
    count = int(call(tier, 'Get number of points'))
    times = np.array([call(tier, 'Get time from index', i) for i in range(1, count+1)], dtype=float)
    frequencies = np.array([call(tier, 'Get value at index', i) for i in range(1, count+1)], dtype=float)
    local = frequencies[(times >= start+0.01) & (times <= end-0.01)]
    if len(local) < 5 or not np.isfinite(frequencies).all() or np.any(frequencies <= 0):
        raise ValueError('Insufficient reliable final-word pitch points')
    if np.max(np.abs(12*np.diff(np.log2(local)))) > 9:
        raise ValueError('Possible pitch-tracking octave jump; refusing automatic edit')
    anchor = float(np.median(local[:3]))
    rise = float(np.max(12*np.log2(local/anchor)))
    if rise < 0.5:
        raise ValueError('No clear positive rise above local onset anchor; inspect pitch before editing')
    report.update(local_onset_anchor_hz=anchor, maximum_local_rise_semitones=rise)
    i0, i1 = int(np.ceil(start*rate)), int(np.floor(end*rate))
    positions = np.arange(i0, i1)/rate
    edge = np.minimum(np.clip((positions-start)/0.03, 0, 1), np.clip((end-positions)/0.03, 0, 1))
    blend = 0.5 - 0.5*np.cos(np.pi*edge)
    report['edits'] = []
    curves = []
    for retain, filename in [(0.75, '02_gentler_rise_keep75.wav'), (0.5, '03_gentler_rise_keep50.wav')]:
        report['stage'] = 'resynthesize_' + filename
        adjusted = gentler_pitch(times, frequencies, start, end, anchor, retain)
        manipulation = call(sound, 'To Manipulation', 0.01, 50, 600)
        tier = call(manipulation, 'Extract pitch tier')
        call(tier, 'Remove points between', 0, sound.duration)
        for t, f in zip(times, adjusted):
            call(tier, 'Add point', float(t), float(f))
        call([tier, manipulation], 'Replace pitch tier')
        resynthesis = call(manipulation, 'Get resynthesis (overlap-add)').values[0]
        if resynthesis.shape != original.shape or not np.isfinite(resynthesis).all():
            raise ValueError('Resynthesis changed duration or produced nonfinite samples')
        edited = pcm.copy()
        region = resynthesis[i0:i1].copy()
        local_gain = min(1., 0.999 / max(float(np.max(np.abs(region))), 1e-12))
        mixed = original[i0:i1]*(1-blend) + region*local_gain*blend
        edited[i0:i1] = np.clip(np.rint(mixed*32768), -32768, 32767).astype(np.int16)
        if not np.array_equal(edited[:i0], pcm[:i0]) or not np.array_equal(edited[i1:], pcm[i1:]) or len(edited) != len(pcm):
            raise ValueError('Outside-region sample preservation failed')
        if np.array_equal(edited, pcm):
            raise ValueError('Pitch edit produced no sample change')
        destination = OUT / filename
        with wave.open(str(destination), 'wb') as target:
            target.setparams((1, 2, rate, 0, 'NONE', 'not compressed'))
            target.writeframes(edited.astype('<i2').tobytes())
        check_rate, check = wavfile.read(destination)
        if check_rate != rate or not np.array_equal(check, edited):
            raise ValueError('Saved edit PCM verification failed')
        report['edits'].append({'file': filename, 'sha256': digest(destination), 'nominal_positive_rise_retained': retain, 'maximum_cut_semitones': 3.0, 'word_region_gain': local_gain, 'sample_count': len(edited), 'outside_estimated_word_samples_identical': True, 'duration_unchanged': True, 'listening_approved': False})
        curves.extend((filename, float(t), float(a), float(b)) for t, a, b in zip(times, frequencies, adjusted))
    with (OUT / 'pitch-edits.csv').open('w', newline='', encoding='utf-8') as target:
        writer = csv.writer(target)
        writer.writerow(['variant', 'seconds', 'original_tier_hz', 'requested_tier_hz'])
        writer.writerows(curves)
    report.update(success=True, stage='localized_variants_created_listening_required')
    (OUT / 'edit-manifest.json').write_text(json.dumps(report, indent=2), encoding='utf-8')


def save(report):
    (OUT / 'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    lines = ['ROXY FINAL-RISE BASELINE TEST', 'Result: ' + ('TWO EDITS CREATED - LISTENING REQUIRED' if report.get('success') else 'NOT COMPLETED'), 'Stage: ' + report['stage']]
    for key in ('source_sha256', 'source_calm_run_id', 'original_seconds', 'baseline_regenerated', 'local_onset_anchor_hz', 'maximum_local_rise_semitones', 'error'):
        if key in report:
            lines.append(key + ': ' + str(report[key]))
    if 'alignment' in report:
        lines.append('Estimated lost region: ' + str(report['alignment']['lost_start_seconds']) + '..' + str(report['alignment']['lost_end_seconds']) + ' seconds; not human-verified')
    for edit in report.get('edits', []):
        lines.append('Edit: ' + json.dumps(edit))
    lines += ['01 is the exact selected baseline. 02 nominally retains 75% of positive local pitch excursion; 03 retains 50%, with a 3-semitone cut cap and smooth transitions.', 'Positive rise is reduced relative to estimated word-onset pitch, not flattened globally. Actual audible contour and alignment require listening.', 'Samples outside the estimated word region and total sample count are preserved. Local resynthesis can introduce artifacts; any local peak attenuation is recorded.', 'No Piper regeneration, RVC conversion, tempo change, inserted silence, Android changes or avatar changes.']
    text = '\n'.join(lines) + '\n'
    (OUT / 'report.txt').write_text(text, encoding='utf-8')
    print(text)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as target:
            target.write('## Local final-rise experiment\n\n```text\n' + text + '```\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--initialize', action='store_true')
    args = parser.parse_args()
    OUT.mkdir(exist_ok=True)
    report = {'success': False, 'stage': 'not_started_check_artifact_or_dependencies', 'rvc_used': False, 'android_changed': False, 'avatars_changed': False}
    if args.initialize:
        save(report)
        return
    try:
        run(report)
    except Exception as error:
        report.update(success=False, error=type(error).__name__ + ': ' + str(error)[:2400])
        (OUT / 'edit-manifest.json').unlink(missing_ok=True)
    finally:
        save(report)
    if not report['success']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
