"""Convert the exact listening-approved baseline; no Android or identity claim."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import traceback
import urllib.request

OUT = Path('voice-sample')
UPSTREAM = '81eed5e8f68b6bed1789f682fe78cdd324495afc'
MODEL_SHA = 'a35a143a9314f80252163581eab9b643987e35ff982027f11e609e3daeb55889'
APPROVED_BASELINE_SHA = 'de8acd0cbe94d72d8e3d0780368ac53aacfcf99e40581f2848748a465cfda56f'
ASSET_REV = 'e6d0c1a17da07c33557852f9dfa2bd44cc75737d'
ASSET_BASE = 'https://huggingface.co/lj1995/VoiceConversionWebUI/resolve/' + ASSET_REV + '/hubert_base/'
ASSETS = {
    'config.json': ('git', '6f40f9bd2481c05c03d59f2b36f016fedc386187', 1024 * 1024),
    'preprocessor_config.json': ('git', '3cb012fae1b439d92a815fec1c09fa0ce19fd6bf', 1024 * 1024),
    'pytorch_model.bin': ('sha256', 'cc8c20f4b90a520757260197a3ff2505705a7adbd20ad9eeaa4e1a9b38442ef5', 200 * 1024 * 1024),
}
SENTENCE = 'Hey there. This is a short voice conversion test.'


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def prepare_baseline(directory, destination):
    source = Path(directory) / 'baseline.wav'
    manifest_path = Path(directory) / 'baseline-manifest.json'
    if not 44 < source.stat().st_size < 2000000:
        raise ValueError('Unexpected approved baseline size')
    metadata = json.loads(manifest_path.read_text(encoding='utf-8'))
    actual = digest(source)
    if actual != APPROVED_BASELINE_SHA or metadata.get('wav_sha256') != APPROVED_BASELINE_SHA:
        raise ValueError('Baseline differs from the exact WAV approved by the user')
    expected = {'success': True, 'sentence': SENTENCE, 'voice': 'en_US-lessac-medium', 'voice_revision': '5041a7a49ae5b3a0f30e6837769eb03fdbb231bd', 'sample_rate': 22050, 'channels': 1}
    if any(metadata.get(key) != value for key, value in expected.items()):
        raise ValueError('Approved baseline metadata mismatch')
    shutil.copyfile(source, destination)
    if digest(destination) != actual:
        raise ValueError('Copied baseline checksum mismatch')
    shutil.copyfile(manifest_path, destination.with_name('approved-baseline-manifest.json'))
    card = Path(directory) / 'VOICE_MODEL_CARD.txt'
    if card.is_file():
        shutil.copyfile(card, destination.with_name('VOICE_MODEL_CARD.txt'))
    return {'baseline_voice': 'Piper en_US-lessac-medium; exact user-approved WAV', 'baseline_sha256': actual, 'baseline_listening_approved': True, 'baseline_regenerated': False, 'source_baseline_run_id': os.environ.get('SOURCE_BASELINE_RUN_ID')}


def download_assets(directory):
    hashes = {}
    for name, (kind, expected, limit) in ASSETS.items():
        path = directory / name
        request = urllib.request.Request(ASSET_BASE + name, headers={'User-Agent': 'RoxySentenceTest/1.0'})
        count = 0
        with urllib.request.urlopen(request, timeout=120) as source, path.open('wb') as target:
            if not source.geturl().startswith('https://'):
                raise ValueError('Non-HTTPS encoder asset rejected')
            for block in iter(lambda: source.read(1024 * 1024), b''):
                count += len(block)
                if count > limit:
                    raise ValueError('Encoder asset size limit exceeded')
                target.write(block)
        if kind == 'git':
            data = path.read_bytes()
            actual = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        else:
            actual = digest(path)
        if actual != expected:
            raise ValueError('Pinned encoder checksum mismatch: ' + name)
        hashes[name] = {'checksum_kind': kind, 'checksum': actual, 'bytes': count}
    return hashes


def coarse_pitch(f0):
    import numpy as np
    mel = 1127 * np.log1p(f0 / 700)
    low, high = 1127 * np.log1p(50 / 700), 1127 * np.log1p(1100 / 700)
    positive = mel > 0
    mel[positive] = (mel[positive] - low) * 254 / (high - low) + 1
    return np.rint(np.clip(mel, 1, 255)).astype(np.int64)


def convert(args, report):
    import numpy as np
    import onnxruntime as ort
    import parselmouth
    import torch
    from scipy import signal
    from scipy.io import wavfile
    from torch import nn
    from torch.nn import functional as F
    from transformers import HubertConfig, HubertModel

    torch.set_num_threads(2)
    torch.manual_seed(2026)
    np.testing.assert_array_equal(coarse_pitch(np.array([0., 50., 1100.])), [1, 1, 255])
    if not 0 <= args.speaker_id < 109 or not -12 <= args.pitch_shift <= 12:
        raise ValueError('Speaker must be 0..108 and pitch shift -12..12 semitones')
    upstream = Path(args.upstream).resolve()
    revision = subprocess.check_output(['git', '-C', str(upstream), 'rev-parse', 'HEAD'], text=True).strip()
    if revision != UPSTREAM:
        raise ValueError('Unexpected RVC source revision')
    sys.path.insert(0, str(upstream))
    from infer.module.models import SineGen

    report['stage'] = 'verify_saved_onnx'
    model_path = Path(args.model_dir) / 'roxanne-synthesizer.onnx'
    manifest = json.loads((Path(args.model_dir) / 'manifest.json').read_text(encoding='utf-8'))
    if digest(model_path) != MODEL_SHA or manifest.get('onnx_sha256') != MODEL_SHA:
        raise ValueError('Model differs from the successful export supplied in the conversation')
    for name, value in {'sample_rate': 40000, 'content_channels': 768, 'latent_channels': 192, 'samples_per_feature_frame': 400, 'speaker_count': 109, 'upstream_commit': UPSTREAM}.items():
        if manifest.get(name) != value:
            raise ValueError('Unexpected input contract: ' + name)
    report.update(model_sha256=MODEL_SHA, source_export_run_id=os.environ.get('SOURCE_EXPORT_RUN_ID'), speaker_id=args.speaker_id, pitch_shift_semitones=args.pitch_shift)

    report['stage'] = 'verify_listening_approved_baseline'
    baseline = OUT / 'baseline.wav'
    report.update(prepare_baseline(args.baseline_dir, baseline))
    sr, pcm = wavfile.read(baseline)
    if sr != 22050 or pcm.dtype != np.int16 or pcm.ndim != 1 or not 0.5 <= len(pcm) / sr <= 8:
        raise ValueError('Baseline must be the approved short mono 16-bit 22050-Hz speech')
    audio = pcm.astype(np.float32) / 32768
    divisor = math.gcd(int(sr), 16000)
    audio = signal.resample_poly(audio, 16000 // divisor, int(sr) // divisor).astype(np.float32)
    if not np.isfinite(audio).all() or np.max(np.abs(audio)) < 1e-4:
        raise ValueError('Invalid baseline audio')
    scale = max(1., float(np.max(np.abs(audio))) / 0.95)
    audio /= scale
    b, a = signal.butter(5, 48, btype='high', fs=16000)
    audio = signal.filtfilt(b, a, audio).astype(np.float32)
    padded = np.pad(audio, (16000, 16000), mode='reflect')
    report.update(sentence=SENTENCE, baseline_seconds=len(pcm) / sr)

    report['stage'] = 'verified_content_encoder_download_and_load'
    with tempfile.TemporaryDirectory(prefix='roxy-content-') as temporary:
        directory = Path(temporary)
        report['content_encoder_assets'] = download_assets(directory)
        preprocessing = json.loads((directory / 'preprocessor_config.json').read_text())
        if preprocessing.get('sampling_rate') != 16000 or preprocessing.get('do_normalize') is not False:
            raise ValueError('Unexpected pinned encoder preprocessing')
        config = HubertConfig.from_json_file(str(directory / 'config.json'))
        if config.hidden_size != 768 or config.num_hidden_layers != 12:
            raise ValueError('Unexpected content encoder dimensions')
        config._attn_implementation = 'eager'

        class ContentEncoder(HubertModel):
            def __init__(self, configuration):
                super().__init__(configuration)
                self.final_proj = nn.Linear(configuration.hidden_size, configuration.classifier_proj_size)

        # No remote model code, additional safe globals or unsafe pickle fallback.
        weights = torch.load(directory / 'pytorch_model.bin', map_location='cpu', weights_only=True)
        if not isinstance(weights, dict) or not 0 < len(weights) <= 2000:
            raise ValueError('Unexpected encoder state dictionary')
        total = 0
        for key, value in weights.items():
            if not isinstance(key, str) or not isinstance(value, torch.Tensor) or value.layout != torch.strided:
                raise ValueError('Encoder weights must be named dense tensors')
            total += value.numel()
            if total > 120000000 or not torch.isfinite(value).all().item():
                raise ValueError('Encoder tensor limits exceeded or nonfinite weights')
        encoder = ContentEncoder(config).float().eval()
        encoder.load_state_dict(weights, strict=True)
        del weights

    processing_started = time.perf_counter()
    report['stage'] = 'content_features'
    with torch.no_grad():
        features = encoder(torch.from_numpy(padded)[None], output_hidden_states=False, return_dict=True).last_hidden_state
        features = F.interpolate(features.transpose(1, 2), scale_factor=2, mode='nearest').transpose(1, 2)
    del encoder
    frames = min(len(padded) // 160, int(features.shape[1]))
    phone = features[:, :frames].contiguous().numpy().astype(np.float32)
    del features
    if frames <= 200 or frames > 1000 or phone.shape != (1, frames, 768) or not np.isfinite(phone).all():
        raise ValueError('Invalid sentence features')

    report['stage'] = 'pitch_and_excitation'
    p_len = len(padded) // 160
    raw_f0 = parselmouth.Sound(padded, 16000).to_pitch_ac(time_step=0.01, voicing_threshold=0.6, pitch_floor=50, pitch_ceiling=1100).selected_array['frequency']
    if len(raw_f0) > p_len:
        raise ValueError('Unexpected pitch frame alignment')
    left = (p_len - len(raw_f0) + 1) // 2
    raw_f0 = np.pad(raw_f0, (left, p_len - len(raw_f0) - left))[:frames]
    voiced = raw_f0 > 0
    if not voiced.any():
        raise ValueError('No voiced pitch detected in baseline sentence')
    # Match this pinned pipeline's interpolation of unvoiced pitch before coarse mapping.
    f0 = raw_f0.copy()
    f0[~voiced] = np.interp(np.flatnonzero(~voiced), np.flatnonzero(voiced), f0[voiced])
    f0 *= 2 ** (args.pitch_shift / 12)
    pitch = coarse_pitch(f0)[None]
    with torch.no_grad():
        excitation = SineGen(40000)(torch.from_numpy(f0.astype(np.float32))[None], 400)[0].numpy()
    rng = np.random.default_rng(2026)
    feed = {'phone': phone, 'lengths': np.array([frames], np.int64), 'pitch': pitch, 'speaker': np.array([args.speaker_id], np.int64), 'latent_noise': rng.standard_normal((1, 192, frames)).astype(np.float32), 'excitation': excitation.astype(np.float32)}
    report.update(frames=frames, raw_voiced_fraction=float(voiced.mean()), pitch_method='Parselmouth autocorrelation; pinned pipeline UV interpolation', f0_voiced_median_hz=float(np.median(raw_f0[voiced])), retrieval_index_used=False, retrieval_status='Disabled: index matching has not been validated')

    report['stage'] = 'onnx_sentence_inference'
    options = ort.SessionOptions()
    options.intra_op_num_threads, options.inter_op_num_threads = 2, 1
    session = ort.InferenceSession(str(model_path), sess_options=options, providers=['CPUExecutionProvider'])
    if {item.name for item in session.get_inputs()} != set(feed):
        raise ValueError('Unexpected ONNX input signature')
    started = time.perf_counter()
    result = session.run(['audio'], feed)[0]
    report['synthesizer_seconds'] = time.perf_counter() - started
    if result.shape != (1, 1, frames * 400) or not np.isfinite(result).all():
        raise ValueError('Invalid converted output')
    result = result[0, 0, 40000:-40000]
    peak = float(np.max(np.abs(result)))
    rms = float(np.sqrt(np.mean(result.astype(np.float64) ** 2)))
    if len(result) < 20000 or not np.isfinite(rms) or rms < 1e-5:
        raise ValueError('Converted output is empty or effectively silent')
    gain = min(1., 0.99 / max(peak, 1e-12))
    output = OUT / ('candidate_sid' + str(args.speaker_id) + '_key' + str(args.pitch_shift) + '.wav')
    wavfile.write(output, 40000, np.rint(np.clip(result * gain, -1, 1) * 32767).astype(np.int16))
    report.update(success=True, stage='candidate_wav_created', output_file=output.name, output_sha256=digest(output), output_seconds=len(result) / 40000, raw_output_peak=peak, raw_output_rms=rms, output_gain=gain, feature_pitch_and_synthesis_seconds=time.perf_counter() - processing_started)


def save_report(report):
    OUT.mkdir(exist_ok=True)
    (OUT / 'sentence-report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    lines = ['ROXY CANDIDATE SENTENCE TEST', 'Result: ' + ('CANDIDATE WAV CREATED' if report.get('success') else 'NOT COMPLETED'), 'Stage: ' + report.get('stage', 'unknown')]
    for key in ['sentence', 'speaker_id', 'pitch_shift_semitones', 'baseline_voice', 'baseline_sha256', 'baseline_listening_approved', 'baseline_regenerated', 'source_baseline_run_id', 'baseline_seconds', 'frames', 'raw_voiced_fraction', 'output_file', 'output_seconds', 'synthesizer_seconds', 'feature_pitch_and_synthesis_seconds', 'wall_seconds', 'error']:
        if key in report:
            lines.append(key + ': ' + str(report[key]))
    lines += ['Speaker identity is unconfirmed. Numerical success does not verify Roxanne likeness or intelligibility.', 'No retrieval index, Android changes or phone-speed claim. Source WAV is checksum-pinned to the listening-approved baseline; standard conversion preprocessing is unchanged. Start playback at low volume.']
    text = '\n'.join(lines) + '\n'
    (OUT / 'sentence-report.txt').write_text(text, encoding='utf-8')
    print(text)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as target:
            target.write('## Candidate sentence test\n\n```text\n' + text + '```\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--initialize', action='store_true')
    parser.add_argument('--model-dir', default='voice-model')
    parser.add_argument('--upstream', default='vendor-rvc')
    parser.add_argument('--baseline-dir', default='approved-baseline')
    parser.add_argument('--speaker-id', type=int, default=int(os.environ.get('SPEAKER_ID', '0')))
    parser.add_argument('--pitch-shift', type=int, default=int(os.environ.get('PITCH_SHIFT', '0')))
    args = parser.parse_args()
    report = {'success': False, 'stage': 'not_started_check_dependency_or_artifact_step', 'android_app_changed': False, 'speaker_identity_confirmed': False, 'model_sha256_expected': MODEL_SHA, 'baseline_sha256_expected': APPROVED_BASELINE_SHA, 'upstream_commit': UPSTREAM, 'content_asset_revision': ASSET_REV}
    if args.initialize:
        save_report(report)
        return
    started = time.perf_counter()
    try:
        OUT.mkdir(exist_ok=True)
        convert(args, report)
    except Exception as error:
        report.update(success=False, error=type(error).__name__ + ': ' + str(error)[:2400], traceback=traceback.format_exc()[-6000:])
        for path in OUT.glob('candidate_*.wav'):
            path.unlink(missing_ok=True)
    finally:
        report['wall_seconds'] = time.perf_counter() - started
        save_report(report)
    if not report['success']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
