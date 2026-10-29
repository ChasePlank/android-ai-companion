# Standalone candidate sentence test

This separate workflow tests one actual generated speech sentence through the previously validated ONNX core. It does not modify the Android app, export a new synthesizer or establish speaker identity.

## Run and listen

Open Actions > Test Roxy candidate sentence. A push changing its workflow/script starts the default test automatically. Manual runs accept a candidate speaker ID (0..108) and pitch shift (-12..12 semitones). Defaults are ID 0 and no shift; ID 0 is not confirmed to be Roxanne.

The job finds a successful export from commit b4717ca074d66b0e2913384546edfb4c9431ca67, downloads its roxy-synthesizer-onnx artifact and verifies the exact ONNX SHA-256 supplied in the successful report. It fails rather than rebuilding or substituting a model if that artifact is absent, expired or mismatched. Your locally saved model remains untouched.

The baseline sentence is: Hey there. This is a short voice conversion test. Basic offline espeak-ng produces baseline.wav. The script uses the upstream project's pinned content encoder with checked Git blob IDs/SHA-256, restricted torch.load(weights_only=True), strict state loading, 16-kHz preprocessing, final-layer 768-channel features and nearest-neighbor doubling. Pitch uses Parselmouth autocorrelation and this pinned pipeline's unvoiced-pitch interpolation and bin mapping. Excitation comes directly from the pinned SineGen. Retrieval is deliberately disabled because index matching remains unverified.

Download roxy-sentence-report and paste sentence-report.txt. If conversion succeeds, roxy-candidate-sentence contains baseline.wav and candidate_sid0_key0.wav with default settings. Listen at low volume, compare intelligibility and voice character, and report whether it sounds recognizable, robotic, distorted or silent. A created WAV only means signal checks passed, not that likeness passed.

The job uses CPU, network downloads and normal Actions usage, with read-only repository/action permissions and a 30-minute limit. Only generated speech is used; no microphone audio or personal recording is uploaded. A package inventory and source/model hashes support reproducibility. Reports/artifacts expire after seven days. Dependency/artifact failures leave a not-started report and must be diagnosed from the corresponding job step.

The elementary baseline, pitch method, unconfirmed speaker and omitted retrieval can limit quality. This is not a production conversation voice, full upstream pipeline parity, on-device performance measurement or Android integration. Existing model provenance/licensing uncertainties remain unresolved.
