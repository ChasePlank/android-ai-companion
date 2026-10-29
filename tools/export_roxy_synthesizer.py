"""Restricted checkpoint load and experimental deterministic RVC neural-core export.
Not a full speech-conversion pipeline, Android integration, or voice-quality test.
"""
import argparse
import hashlib
import json
import math
import os
import pathlib
import platform
import shutil
import subprocess
import sys
import tempfile
import time
import traceback
import urllib.request
import zipfile

from inspect_roxy_voice import URL, EXPECTED_SHA256, MAX_DOWNLOAD

UPSTREAM_COMMIT = '81eed5e8f68b6bed1789f682fe78cdd324495afc'
CHECKPOINT_SHA256 = '33e8e07a58135a04c6e6128d77cf7744efb4ac52413f4244bd5b1228e32adfc5'
OUT = pathlib.Path('voice-export')

def digest_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()

def acquire_checkpoint(directory, report):
    archive_path = directory / 'voice.zip'
    request = urllib.request.Request(URL, headers={'User-Agent': 'RoxyVoiceExportValidation/1.0'})
    total = 0
    with urllib.request.urlopen(request, timeout=60) as source, archive_path.open('wb') as target:
        if not source.geturl().startswith('https://'):
            raise ValueError('Non-HTTPS download rejected')
        while True:
            chunk = source.read(1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if total > MAX_DOWNLOAD:
                raise ValueError('Archive download limit exceeded')
            target.write(chunk)
    actual = digest_file(archive_path)
    report['archive_sha256'] = actual
    if actual != EXPECTED_SHA256:
        raise ValueError('Archive checksum does not match the previously inspected candidate')
    destination = directory / 'RoxanneWolf.pth'
    with zipfile.ZipFile(archive_path) as archive:
        entries = archive.infolist()
        if len(entries) > 1000:
            raise ValueError('Too many ZIP entries')
        report['index_files_present_not_validated'] = [entry.filename for entry in entries if entry.filename.lower().endswith('.index')]
        matches = [entry for entry in entries if entry.filename == 'RoxanneWolf.pth']
        if len(matches) != 1:
            raise ValueError('Expected exactly one RoxanneWolf.pth entry')
        entry = matches[0]
        if entry.flag_bits & 1 or not 0 < entry.file_size <= 192 * 1024 * 1024:
            raise ValueError('Encrypted or oversized checkpoint')
        written = 0
        with archive.open(entry) as source, destination.open('wb') as target:
            while True:
                chunk = source.read(1024 * 1024)
                if not chunk:
                    break
                written += len(chunk)
                if written > 192 * 1024 * 1024:
                    raise ValueError('Checkpoint read limit exceeded')
                target.write(chunk)
    actual = digest_file(destination)
    report['checkpoint_sha256'] = actual
    if actual != CHECKPOINT_SHA256:
        raise ValueError('Checkpoint checksum differs from the user-provided inspection report')
    return destination

def primitive_config(value, depth=0):
    if depth > 4:
        raise ValueError('Configuration nesting limit exceeded')
    if type(value) in (int, float):
        if not math.isfinite(value) or abs(value) > 1000000:
            raise ValueError('Invalid numeric configuration')
        return value
    if isinstance(value, str) and len(value) < 80:
        return value
    if isinstance(value, (list, tuple)) and len(value) <= 64:
        return [primitive_config(item, depth + 1) for item in value]
    raise ValueError('Unsupported configuration type')

def export_core(upstream, checkpoint_path, report):
    import numpy as np
    import onnx
    import onnxruntime as ort
    import scipy
    import torch
    from torch import nn
    from torch.nn import functional as F

    torch.set_num_threads(2)
    report['packages'] = {'python': platform.python_version(), 'torch': torch.__version__, 'numpy': np.__version__, 'scipy': scipy.__version__, 'onnx': onnx.__version__, 'onnxruntime': ort.__version__}
    report['stage'] = 'restricted_weights_load'
    # Deliberately no safe-global additions or weights_only=False fallback.
    checkpoint = torch.load(checkpoint_path, map_location='cpu', weights_only=True)
    if not isinstance(checkpoint, dict):
        raise ValueError('Checkpoint must contain a dictionary')
    config = primitive_config(checkpoint.get('config'))
    report['profile'] = {'version': checkpoint.get('version'), 'f0': checkpoint.get('f0'), 'sr': checkpoint.get('sr')}
    report['config'] = config
    if checkpoint.get('version') != 'v2' or checkpoint.get('f0') != 1 or checkpoint.get('sr') != '40k':
        raise ValueError('Only the inspected v2/f0/40k profile is supported in this test')
    if not isinstance(config, list) or len(config) != 18 or config[-1] not in (40000, '40k'):
        raise ValueError('Expected the standard 18-field 40-kHz RVC configuration')
    weights = checkpoint.get('weight')
    if not isinstance(weights, dict) or not 0 < len(weights) <= 5000:
        raise ValueError('Missing or oversized weight dictionary')
    total = 0
    for key, tensor in weights.items():
        if not isinstance(key, str) or not isinstance(tensor, torch.Tensor) or tensor.layout != torch.strided:
            raise ValueError('Expected named dense tensors only')
        total += tensor.numel()
        if total > 100000000 or not torch.isfinite(tensor).all().item():
            raise ValueError('Tensor size limit exceeded or nonfinite weights')
    embedding = weights.get('emb_g.weight')
    phone_embedding = weights.get('enc_p.emb_phone.weight')
    if embedding is None or embedding.ndim != 2 or not 0 < embedding.shape[0] <= 1024:
        raise ValueError('Invalid speaker embedding')
    if phone_embedding is None or phone_embedding.ndim != 2 or phone_embedding.shape[1] != 768:
        raise ValueError('Expected 768-channel RVC-v2 content features')
    config[-3] = int(embedding.shape[0])
    report['speaker_count'] = int(embedding.shape[0])
    report['parameter_elements'] = total
    report['stage'] = 'construct_pinned_synthesizer'
    sys.path.insert(0, str(upstream))
    from infer.module.models import SynthesizerTrnMs768NSFsid

    model = SynthesizerTrnMs768NSFsid(*config, is_half=False)
    del model.enc_q
    inference_weights = {key: value for key, value in weights.items() if not key.startswith('enc_q.')}
    model.load_state_dict(inference_weights, strict=True)
    model.float().eval()
    model.remove_weight_norm()
    upp = int(model.dec.upp)
    if upp != 400:
        raise ValueError('Expected 400 output samples per feature frame for this 40-kHz test')
    report['upsample_factor'] = upp

    class Core(nn.Module):
        def __init__(self, synthesizer):
            super().__init__()
            self.model = synthesizer

        def forward(self, phone, lengths, pitch, speaker, latent_noise, excitation):
            m = self.model
            g = m.emb_g(speaker).unsqueeze(-1)
            mean, logs, mask = m.enc_p(phone, pitch, lengths)
            z = (mean + torch.exp(logs) * latent_noise * 0.66666) * mask
            # Normalize upstream reverse-flow returns: coupling and Flip may return tuples.
            for layer in reversed(m.flow.flows):
                value = layer.forward(z, mask, g=g, reverse=True)
                z = value[0] if isinstance(value, tuple) else value
            d = m.dec
            source = d.m_source.l_tanh(d.m_source.l_linear(excitation)).transpose(1, 2)
            x = d.conv_pre(z * mask) + d.cond(g)
            for i, up in enumerate(d.ups):
                x = up(F.leaky_relu(x, d.lrelu_slope))
                x = x + d.noise_convs[i](source)
                combined = d.resblocks[i * d.num_kernels](x)
                for j in range(1, d.num_kernels):
                    combined = combined + d.resblocks[i * d.num_kernels + j](x)
                x = combined / d.num_kernels
            return torch.tanh(d.conv_post(F.leaky_relu(x)))

    core = Core(model).eval()
    inter_channels = int(model.inter_channels)
    generator = torch.Generator().manual_seed(2026)
    def inputs(frames, voiced):
        return (
            torch.randn(1, frames, 768, generator=generator) * 0.1,
            torch.tensor([frames], dtype=torch.long),
            torch.full((1, frames), 120 if voiced else 1, dtype=torch.long),
            torch.zeros(1, dtype=torch.long),
            torch.randn(1, inter_channels, frames, generator=generator),
            torch.randn(1, frames * upp, 1, generator=generator) * 0.01,
        )

    names = ['phone', 'lengths', 'pitch', 'speaker', 'latent_noise', 'excitation']
    sample = inputs(32, True)
    temporary_model = OUT / 'pending-core.onnx'
    report['stage'] = 'onnx_export'
    with torch.no_grad():
        torch.onnx.export(core, sample, str(temporary_model), input_names=names, output_names=['audio'],
            dynamic_axes={'phone': {1: 'frames'}, 'pitch': {1: 'frames'}, 'latent_noise': {2: 'frames'}, 'excitation': {1: 'samples'}, 'audio': {2: 'samples'}},
            opset_version=17, dynamo=False, do_constant_folding=True)
    report['stage'] = 'onnx_check_and_cpu_parity'
    onnx.checker.check_model(str(temporary_model), full_check=True)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2; options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(temporary_model), sess_options=options, providers=['CPUExecutionProvider'])
    if {item.name for item in session.get_inputs()} != set(names):
        raise ValueError('Exported model input signature changed unexpectedly')
    tests = []
    report['tests'] = tests
    for frames, voiced in [(32, True), (51, False), (80, True)]:
        values = inputs(frames, voiced)
        with torch.no_grad():
            reference = core(*values).cpu().numpy()
        feed = {name: value.cpu().numpy() for name, value in zip(names, values)}
        started = time.perf_counter()
        actual = session.run(['audio'], feed)[0]
        elapsed = time.perf_counter() - started
        expected_shape = (1, 1, frames * upp)
        if reference.shape != expected_shape or actual.shape != expected_shape or not np.isfinite(actual).all():
            raise ValueError('Invalid output shape or nonfinite ONNX audio')
        np.testing.assert_allclose(actual, reference, rtol=0.001, atol=0.001)
        tests.append({'frames': frames, 'pitch_case': 'voiced' if voiced else 'unvoiced', 'shape': list(actual.shape), 'max_absolute_error': float(np.abs(actual - reference).max()), 'cpu_runner_seconds': elapsed, 'passed': True})
    final_model = OUT / 'roxanne-synthesizer.onnx'
    temporary_model.replace(final_model)
    report['model_bytes'] = final_model.stat().st_size
    report['model_sha256'] = digest_file(final_model)
    report['stage'] = 'core_validation_passed'
    report['success'] = True
    manifest = {
        'component': 'RVC-v2 pitch-guided neural synthesizer core only', 'sample_rate': 40000, 'batch_size': 1, 'content_channels': 768,
        'latent_channels': inter_channels, 'samples_per_feature_frame': upp, 'speaker_count': report['speaker_count'], 'opset': 17,
        'input_order': names, 'onnx_sha256': report['model_sha256'], 'checkpoint_sha256': CHECKPOINT_SHA256, 'upstream_commit': UPSTREAM_COMMIT,
        'input_contract': {'phone': 'float32 [1,T,768]', 'lengths': 'int64 [1], actual frame count', 'pitch': 'int64 [1,T], RVC coarse pitch bins', 'speaker': 'int64 [1], valid speaker ID', 'latent_noise': 'float32 [1,latent_channels,T], standard-normal noise', 'excitation': 'float32 [1,T*400,1], RVC SineGen harmonic/noise excitation BEFORE learned source linear/tanh'},
        'missing_components': ['Speech content encoder', 'Pitch extraction and coarse-bin mapping', 'Compatible SineGen excitation generation', 'Optional retrieval-index inference', 'Audio preprocessing/chunking', 'Android runtime integration'],
        'validation_scope': 'Deterministic wrapper versus ONNX Runtime CPU on synthetic tensors at three lengths. Not full upstream pipeline parity, speech quality, a converted sentence, or S25 Ultra performance.',
        'provenance': 'Uses the same hash-verified candidate as inspection; exact catalog outbound relationship remains unconfirmed.'
    }
    (OUT / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    shutil.copyfile(upstream / 'LICENSE', OUT / 'RVC-LICENSE')

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--upstream', required=True)
    args = parser.parse_args()
    OUT.mkdir(exist_ok=True)
    report = {'success': False, 'stage': 'starting', 'upstream_commit': UPSTREAM_COMMIT, 'restricted_weights_only': True, 'android_app_changed': False, 'speech_converted': False}
    try:
        upstream = pathlib.Path(args.upstream).resolve()
        actual_commit = subprocess.check_output(['git', '-C', str(upstream), 'rev-parse', 'HEAD'], text=True).strip()
        if actual_commit != UPSTREAM_COMMIT:
            raise ValueError('Upstream checkout is not the pinned revision')
        report['stage'] = 'download_and_hash_verification'
        with tempfile.TemporaryDirectory(prefix='roxy-export-') as temporary:
            checkpoint = acquire_checkpoint(pathlib.Path(temporary), report)
            export_core(upstream, checkpoint, report)
    except Exception as error:
        report['error'] = type(error).__name__ + ': ' + str(error)[:3000]
        report['traceback'] = traceback.format_exc()[-6000:]
        (OUT / 'roxanne-synthesizer.onnx').unlink(missing_ok=True)
        (OUT / 'manifest.json').unlink(missing_ok=True)
    finally:
        (OUT / 'pending-core.onnx').unlink(missing_ok=True)
        (OUT / 'roxy-export-report.json').write_text(json.dumps(report, indent=2, default=str), encoding='utf-8')
        lines = ['ROXY VOICE EXPORT TEST', 'Result: ' + ('CORE VALIDATION PASSED' if report['success'] else 'FAILED'), 'Stage: ' + report['stage'], 'This is not a converted sentence or an Android voice integration.']
        if 'profile' in report:
            lines.append('Profile: ' + json.dumps(report['profile']))
        if 'config' in report:
            lines.append('Config: ' + json.dumps(report['config']))
        for key in ['speaker_count', 'upsample_factor', 'model_bytes', 'model_sha256']:
            if key in report:
                lines.append(key + ': ' + str(report[key]))
        for test in report.get('tests', []):
            lines.append('Synthetic test: ' + json.dumps(test))
        if 'error' in report:
            lines.append('Error: ' + report['error'])
        lines.append('Still required: content encoder, pitch/excitation pipeline, retrieval validation, real sentence test, and on-device integration/performance testing.')
        text = '\n'.join(lines) + '\n'
        (OUT / 'roxy-export-report.txt').write_text(text, encoding='utf-8')
        print(text)
        summary = os.environ.get('GITHUB_STEP_SUMMARY')
        if summary:
            with open(summary, 'a', encoding='utf-8') as output:
                output.write('## Roxy synthesizer export test\n\n```text\n' + text + '```\n')
    if not report['success']:
        raise SystemExit(1)

if __name__ == '__main__':
    main()
