# Dynamic-length attention export correction

The original RVC source converts sequence lengths to Python integers in relative-position padding/reshaping. Legacy ONNX tracing can freeze those values at the export example's 32 frames. A 51-frame test then needs 50 padding elements but receives 31, matching the reported reshape mismatch. This is an export compatibility issue, not evidence that the voice weights are corrupted.

The export workflow now runs through `tools/export_roxy_with_attention_fix.py`. It replaces three relative-attention indexing methods in process memory with gather-based equivalents: embedding selection, relative-to-absolute indexing and absolute-to-relative indexing. The pinned upstream checkout and all checkpoint weights remain unchanged.

Before exporting, the adapter compares each transform exactly against the original PyTorch method for lengths 1, 2, 10, 11, 12, 32, 51 and 80, with shared and separate head embeddings. It also computes full attention reference outputs before installing the adapter, then requires exact equality after installation. Failure prevents model export.

The original checkpoint hashes, restricted weight loading, dynamic ONNX export and full synthesizer PyTorch/ONNX Runtime tests at 32, 51 and 80 frames remain in place. The adapter version/hash and its validation results are added to the report and successful model manifest. The workflow does not publish the ONNX artifact unless the whole job succeeds. No fixed-length workaround or relaxed numerical tolerance is introduced.

A local NumPy check of the indexing formulas matched the reference operations at all eight lengths. That does not replace the workflow's PyTorch and real-checkpoint ONNX validation; the corrected export remains unverified until the job completes.

Use the same separate **Export Roxy voice synthesizer** workflow and the small **roxy-export-report** artifact. Paste `roxy-export-report.txt`; no opcode blocks or APK installation are needed. The Android app, overlay, listening behavior and diagnostic speech are untouched. This is still a synthesizer-core export stage, not a completed voice-conversion pipeline.
