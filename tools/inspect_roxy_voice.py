"""Download one pinned candidate ZIP; inspect metadata without unpickling or model execution."""
import hashlib
import io
import json
import os
import pathlib
import pickletools
import tempfile
import urllib.request
import zipfile

URL = 'https://huggingface.co/juuxn/RVCModels/resolve/main/RoxanneWolf.zip'
EXPECTED_SHA256 = '99b2139f18d7375ebc463b40b1356ba1ca67bbe28978eeea7f4cd3b4a2e02ac6'
MAX_DOWNLOAD = 128 * 1024 * 1024
MAX_CHECKPOINT = 192 * 1024 * 1024
MAX_METADATA = 8 * 1024 * 1024
REPORT_PATH = pathlib.Path('voice-inspection.json')
KEYS = {'config', 'version', 'f0', 'sr', 'info', 'epoch', 'iteration', 'name'}

def inspect_pickle_bytes(data):
    result = {'method': 'pickletools static opcode inspection only', 'metadata_windows_are_not_fully_decoded_values': True}
    windows, names, globals_seen = {}, [], []
    pending, remaining = None, 0
    count = 0
    try:
        for opcode, arg, position in pickletools.genops(data):
            count += 1
            if count > 200000:
                result['scan_limit_reached'] = True
                break
            if isinstance(arg, str):
                if opcode.name == 'GLOBAL' and len(globals_seen) < 80:
                    globals_seen.append(arg[:300])
                if arg.endswith(('.weight', '.bias', '.weight_g', '.weight_v')) and len(names) < 80:
                    names.append(arg[:300])
                if arg in KEYS and arg not in windows:
                    pending, remaining = arg, 64
                    windows[arg] = []
                    continue
            if pending is not None and remaining > 0:
                scalar = arg if isinstance(arg, (str, int, float, bool)) or arg is None else '<binary or structured argument omitted>'
                if isinstance(scalar, str):
                    scalar = scalar[:400]
                windows[pending].append({'op': opcode.name, 'arg': scalar})
                remaining -= 1
                if remaining == 0:
                    pending = None
    except Exception as error:
        result['scan_error'] = type(error).__name__ + ': ' + str(error)[:400]
    result.update(opcodes_scanned=count, metadata_opcode_windows=windows, sample_parameter_names=names, global_references_not_executed=globals_seen)
    return result

def inspect_checkpoint(data):
    stream = io.BytesIO(data)
    if zipfile.is_zipfile(stream):
        with zipfile.ZipFile(stream) as checkpoint:
            entries = checkpoint.infolist()
            if len(entries) > 20000:
                return {'error': 'Too many checkpoint entries'}
            metadata = [entry for entry in entries if entry.filename.endswith('/data.pkl') or entry.filename == 'data.pkl']
            result = {'container': 'ZIP checkpoint', 'entry_count': len(entries), 'pickle_metadata_entries': [entry.filename for entry in metadata]}
            if len(metadata) == 1 and metadata[0].file_size <= MAX_METADATA:
                with checkpoint.open(metadata[0]) as source:
                    payload = source.read(MAX_METADATA + 1)
                if len(payload) <= MAX_METADATA:
                    result['static_metadata'] = inspect_pickle_bytes(payload)
                else:
                    result['error'] = 'Metadata read limit exceeded'
            else:
                result['note'] = 'Expected one bounded data.pkl; no model loading attempted'
            return result
    return {'container': 'Legacy or unknown checkpoint', 'note': 'Only the first pickle record is scanned; legacy tensor storage and later records are not decoded', 'static_metadata': inspect_pickle_bytes(data[:MAX_METADATA])}

def inspect_archive(path):
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if len(entries) > 1000:
            raise ValueError('Archive inventory limit exceeded')
        if sum(entry.file_size for entry in entries) > 512 * 1024 * 1024:
            raise ValueError('Archive uncompressed-size limit exceeded')
        inventory = [{'name': entry.filename, 'bytes': entry.file_size, 'compressed_bytes': entry.compress_size, 'encrypted': bool(entry.flag_bits & 1)} for entry in entries]
        checkpoints, indexes = [], []
        for entry in entries:
            if entry.is_dir():
                continue
            suffix = pathlib.PurePosixPath(entry.filename).suffix.lower()
            if suffix == '.index':
                indexes.append({'name': entry.filename, 'bytes': entry.file_size, 'note': 'Presence only; retrieval index not loaded or matched'})
            if suffix not in ('.pth', '.pt'):
                continue
            row = {'name': entry.filename, 'bytes': entry.file_size}
            if len(checkpoints) >= 8:
                raise ValueError('Checkpoint count limit exceeded')
            if entry.flag_bits & 1 or entry.file_size > MAX_CHECKPOINT:
                row['note'] = 'Skipped encrypted or oversized checkpoint'
            else:
                try:
                    with archive.open(entry) as source:
                        data = source.read(MAX_CHECKPOINT + 1)
                    if len(data) > MAX_CHECKPOINT:
                        raise ValueError('Checkpoint read limit exceeded')
                    row['sha256'] = hashlib.sha256(data).hexdigest()
                    row['inspection'] = inspect_checkpoint(data)
                except Exception as error:
                    row['inspection_error'] = type(error).__name__ + ': ' + str(error)[:400]
            checkpoints.append(row)
        return {'archive_entries': inventory, 'checkpoints': checkpoints, 'index_files': indexes}

def main():
    report = {
        'source_url': URL,
        'catalog_listing': 'https://aivoices.gg/models/roxanne-wolf-roxannewolf-zip-4ba1fd84-4ba1fd84',
        'source_relationship': 'Candidate with matching filename; exact catalog outbound target has not been confirmed',
        'expected_archive_sha256': EXPECTED_SHA256,
        'model_executed': False,
        'unpickling_performed': False,
        'limits': 'Structural/static metadata inspection only. Not malware clearance, license verification, full tensor validation, inference compatibility or a speed/quality test.'
    }
    failed = False
    try:
        with tempfile.TemporaryDirectory(prefix='roxy-inspection-') as temporary:
            path = pathlib.Path(temporary) / 'RoxanneWolf.zip'
            digest, total = hashlib.sha256(), 0
            request = urllib.request.Request(URL, headers={'User-Agent': 'RoxyVoiceStaticInspection/1.0'})
            with urllib.request.urlopen(request, timeout=60) as response, path.open('wb') as output:
                if not response.geturl().startswith('https://'):
                    raise ValueError('Non-HTTPS download destination rejected')
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > MAX_DOWNLOAD:
                        raise ValueError('Download size limit exceeded')
                    output.write(chunk); digest.update(chunk)
            actual = digest.hexdigest()
            report.update(archive_bytes=total, archive_sha256=actual, archive_sha256_matches=(actual == EXPECTED_SHA256))
            if actual != EXPECTED_SHA256:
                raise ValueError('Published archive SHA-256 mismatch; refusing to inspect different bytes')
            report.update(inspect_archive(path))
    except Exception as error:
        failed = True
        report['error'] = type(error).__name__ + ': ' + str(error)[:1000]
    REPORT_PATH.write_text(json.dumps(report, indent=2, ensure_ascii=True), encoding='utf-8')
    print(json.dumps(report, indent=2, ensure_ascii=True))
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as output:
            output.write('# Roxy voice static inspection\n\nCandidate provenance is not yet confirmed. No checkpoint code was executed.\n\nDownload the small `roxy-voice-inspection` artifact and share `voice-inspection.json`.\n')
            output.write('\nStatus: ' + ('failed; see report error' if failed else 'structural inspection completed') + '\n')
    if failed:
        raise SystemExit(1)

if __name__ == '__main__':
    main()
