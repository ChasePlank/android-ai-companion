# Roxy synthesizer export and validation

This stage attempts a real ONNX export of the verified Roxanne checkpoint's neural synthesizer core. It does not modify the Android app, create a spoken sentence, implement RVC on Android, or connect the character voice to replies.

## What runs

The separate **Export Roxy voice synthesizer** Actions workflow runs automatically when its YAML or export script changes, or manually from Actions. It uses read-only repository permissions, no persisted checkout credentials, a CPU runner and a 30-minute timeout. It downloads PyTorch CPU and ONNX dependencies and records their installed versions. It checks out official RVC source at `81eed5e8f68b6bed1789f682fe78cdd324495afc`. Runtime/package downloads can consume normal Actions usage.

The same archive hash and checkpoint hash from the completed inspection are checked before model loading. Exact correspondence between the candidate archive and the AIVoices outbound download remains unconfirmed; this stage does not resolve attribution, license permissions or index matching.

Unlike static inspection, this stage actually loads tensor weights and executes the reviewed/pinned synthesizer implementation on synthetic inputs. Loading uses `torch.load(..., weights_only=True)`, with no additional globals or unsafe fallback. This reduces arbitrary-code risk; it is not a security guarantee and does not prevent resource exhaustion or every possible library vulnerability. Do not feed it a different checkpoint by bypassing the hash checks.

## Export boundary

Inputs are 768-channel content features, lengths, coarse pitch, speaker ID, latent noise and precomputed SineGen excitation. The graph includes the text encoder, reverse coupling flow and learned NSF decoder. Reverse-flow tuple returns are normalized by taking the tensor element. Excitation is supplied before the learned source linear/tanh operation. This explicit boundary removes internal randomness from the exported core so numerical comparison is meaningful.

Synthetic tests compare the PyTorch wrapper with ONNX Runtime CPU for three different frame lengths and require finite, correctly shaped output with absolute/relative tolerance 0.001. They do not verify the complete upstream audio pipeline, Roxy's perceptual voice quality or phone speed. Synthetic noise outputs are deliberately not offered as a character-voice sample.

## Reports

Open Actions > **Export Roxy voice synthesizer**, not the APK build. Download artifact **roxy-export-report** and open `roxy-export-report.txt`. Paste that short text into the conversation. Full configuration is decoded automatically; no opcode copying is needed.

Only if validation succeeds is **roxy-synthesizer-onnx** uploaded, containing the ONNX core, its input contract/hash manifest and upstream RVC license. If export fails, the report explains the stage/error; no failed model is published as validated. Dependency failures are visible in the job log and leave a short not-started report. Artifacts expire after seven days. No checkpoint weights are committed to the repository.

## Remaining work

A compatible local content encoder, pitch extraction/bin mapping, excitation generation, preprocessing/chunking and retrieval handling are still required before converting a baseline TTS sentence. After a real sentence works, benchmark the full pipeline on the S25 Ultra and connect its playback to the already working overlay and mouth animation. No performance or voice-quality claim is made by this export job.
