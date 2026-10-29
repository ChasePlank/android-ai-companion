"""Validate and install dynamic gather equivalents for RVC relative attention, then export.
Pinned upstream files and all model weights remain unchanged. Adapter applies in this process only.
"""
import argparse
import hashlib
import json
import os
import pathlib
import runpy
import subprocess
import sys
import traceback

UPSTREAM_COMMIT = '81eed5e8f68b6bed1789f682fe78cdd324495afc'
LENGTHS = (1, 2, 10, 11, 12, 32, 51, 80)
OUT = pathlib.Path('voice-export')

def install_and_validate(upstream, metadata):
    import torch
    torch.set_num_threads(2)
    sys.path.insert(0, str(upstream))
    from infer.module.attentions import MultiHeadAttention

    def relative_embeddings(self, embeddings, length):
        offsets = torch.arange(2 * length - 1, device=embeddings.device, dtype=torch.long) - length + 1
        indices = (offsets + self.window_size).clamp(0, 2 * self.window_size)
        selected = torch.index_select(embeddings, 1, indices)
        valid = (offsets >= -self.window_size) & (offsets <= self.window_size)
        return torch.where(valid[None, :, None], selected, torch.zeros_like(selected))

    def relative_to_absolute(self, x):
        length = x.size(2)
        positions = torch.arange(length, device=x.device, dtype=torch.long)
        indices = positions[None, :] - positions[:, None] + length - 1
        indices = indices[None, None, :, :].expand(x.size(0), x.size(1), -1, -1)
        return torch.gather(x, 3, indices)

    def absolute_to_relative(self, x):
        length = x.size(2)
        rows = torch.arange(length, device=x.device, dtype=torch.long)[:, None]
        offsets = torch.arange(2 * length - 1, device=x.device, dtype=torch.long)[None, :]
        columns = offsets + rows - length + 1
        valid = (columns >= 0) & (columns < length)
        safe = torch.where(valid, columns, torch.zeros_like(columns))
        indices = safe[None, None, :, :].expand(x.size(0), x.size(1), -1, -1)
        selected = torch.gather(x, 3, indices)
        return torch.where(valid[None, None, :, :], selected, torch.zeros_like(selected))

    replacements = {
        '_get_relative_embeddings': relative_embeddings,
        '_relative_position_to_absolute_position': relative_to_absolute,
        '_absolute_position_to_relative_position': absolute_to_relative,
    }
    originals = {name: getattr(MultiHeadAttention, name) for name in replacements}
    generator = torch.Generator().manual_seed(2026)
    cases = []
    with torch.no_grad():
        for shared in (True, False):
            for length in LENGTHS:
                module = MultiHeadAttention(8, 8, 2, window_size=10, heads_share=shared).eval()
                relative = torch.randn(1, 2, length, 2 * length - 1, generator=generator)
                absolute = torch.randn(1, 2, length, length, generator=generator)
                comparisons = [
                    (originals['_get_relative_embeddings'](module, module.emb_rel_k, length), relative_embeddings(module, module.emb_rel_k, length)),
                    (originals['_relative_position_to_absolute_position'](module, relative), relative_to_absolute(module, relative)),
                    (originals['_absolute_position_to_relative_position'](module, absolute), absolute_to_relative(module, absolute)),
                ]
                for expected, actual in comparisons:
                    torch.testing.assert_close(actual, expected, rtol=0, atol=0)
                query = torch.randn(1, 8, length, generator=generator)
                mask = torch.ones(1, 1, length, length)
                reference = module(query, query, mask).clone()
                cases.append((module, query, mask, reference))
        # Apply only after every reference output has been computed with original methods.
        for name, replacement in replacements.items():
            setattr(MultiHeadAttention, name, replacement)
        for module, query, mask, reference in cases:
            actual = module(query, query, mask)
            torch.testing.assert_close(actual, reference, rtol=0, atol=0)
    metadata.update(
        lengths_tested=list(LENGTHS), heads_share_cases=[True, False],
        exact_transform_comparisons=len(cases) * 3, exact_full_attention_comparisons=len(cases),
        eager_equivalence_passed=True,
    )

def write_reports(metadata, adapter_error, exit_code):
    path = OUT / 'roxy-export-report.json'
    try:
        report = json.loads(path.read_text(encoding='utf-8')) if path.exists() else {'success': False, 'stage': 'attention_adapter'}
    except Exception:
        report = {'success': False, 'stage': 'attention_adapter'}
    report['attention_export_adapter'] = metadata
    if adapter_error is not None:
        report['success'] = False
        report['adapter_error'] = adapter_error
    if exit_code != 0 or report.get('error'):
        report['success'] = False
    if not report.get('success'):
        exit_code = 1
    path.write_text(json.dumps(report, indent=2, default=str), encoding='utf-8')
    if exit_code == 0:
        manifest_path = OUT / 'manifest.json'
        if not manifest_path.exists():
            raise ValueError('Validated export manifest missing')
        manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
        manifest['source_adapters'] = [metadata]
        manifest_path.write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    lines = ['ROXY VOICE EXPORT TEST', 'Result: ' + ('CORE VALIDATION PASSED' if report.get('success') else 'FAILED'), 'Stage: ' + report.get('stage', 'unknown'), 'Attention adapter: ' + metadata['id'], 'Exact attention equivalence passed: ' + str(metadata.get('eager_equivalence_passed', False))]
    if 'profile' in report:
        lines.append('Profile: ' + json.dumps(report['profile']))
    if 'config' in report:
        lines.append('Config: ' + json.dumps(report['config']))
    for key in ['speaker_count', 'upsample_factor', 'model_bytes', 'model_sha256']:
        if key in report:
            lines.append(key + ': ' + str(report[key]))
    for test in report.get('tests', []):
        lines.append('Synthetic test: ' + json.dumps(test))
    if report.get('error'):
        lines.append('Error: ' + report['error'])
    if adapter_error:
        lines.append('Attention adapter error: ' + adapter_error.splitlines()[-1])
    lines.append('Still not a converted sentence, voice-quality test or Android integration. Model weights, checksum guards and full-core ONNX parity tests are unchanged.')
    text = '\n'.join(lines) + '\n'
    (OUT / 'roxy-export-report.txt').write_text(text, encoding='utf-8')
    print(text)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as output:
            output.write('## Dynamic attention export validation\n\n```text\n' + text + '```\n')
    return exit_code

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--upstream', required=True)
    args = parser.parse_args()
    OUT.mkdir(exist_ok=True)
    metadata = {
        'id': 'rvc-relative-attention-gather-v1',
        'adapter_sha256': hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),
        'upstream_commit': UPSTREAM_COMMIT,
        'reason': 'Avoid Python int(length), max branches and fixed padding in traced relative attention',
        'weights_changed': False,
    }
    exit_code, adapter_error = 0, None
    try:
        upstream = pathlib.Path(args.upstream).resolve()
        actual_commit = subprocess.check_output(['git', '-C', str(upstream), 'rev-parse', 'HEAD'], text=True).strip()
        if actual_commit != UPSTREAM_COMMIT:
            raise ValueError('Unexpected upstream revision')
        install_and_validate(upstream, metadata)
        script = pathlib.Path(__file__).resolve().with_name('export_roxy_synthesizer.py')
        sys.argv = [str(script), '--upstream', str(upstream)]
        runpy.run_path(str(script), run_name='__main__')
    except SystemExit as error:
        exit_code = error.code if isinstance(error.code, int) else 1
    except Exception:
        exit_code = 1
        adapter_error = traceback.format_exc()[-6000:]
    exit_code = write_reports(metadata, adapter_error, exit_code)
    if exit_code != 0:
        raise SystemExit(exit_code)

if __name__ == '__main__':
    main()
