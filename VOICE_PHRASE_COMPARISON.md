# Same-phrase voice delivery comparison

Actions > Compare Roxy phrase delivery creates three separate clips of Are you lost?: the uploaded reference decoded to reference.wav, a new Piper baseline.wav, and candidate_sid0_key0.wav converted from that new baseline. It runs automatically when its new workflow/helper changes, or manually.

Download roxy-phrase-report and paste phrase-report.txt. On success, roxy-phrase-comparison contains the three clips, per-clip unpadded pitch CSVs, new phrase-input.json and the voice model card. Listen to the baseline first and check every word before judging the candidate against the reference. This newly generated phrase has not been listening-approved; approval of the earlier sentence is not transferred to it. Artifacts expire after seven days.

The job reuses the pinned Piper voice asset loader and existing conversion function. A test-only input adapter temporarily supplies the new phrase and its exact generated WAV checksum, records listening approval as false and restores module globals afterward. The original converter and baseline-review files are not edited; their reviewed Git blob identities are checked before use. The original long-sentence checksum approval remains unchanged.

The same previously validated ONNX is retrieved without rebuilding and retains its existing hash/input-contract checks. Speaker ID is fixed to 0 and pitch shift to 0; content features, standard preprocessing, pitch extraction, excitation, random seeds and retrieval-disabled behavior use the existing converter. Speaker identity remains unconfirmed. No app or avatar changes are included.

The reference is an A/B listening target, not an input conditioning synthesis. No accent learning, reference-pitch substitution, rhythm alignment, pitch shifting or time stretching is added. The pitch measurements are descriptions, not objective accent/likeness scores. Decoding the reference to mono PCM does not establish that the assistant has heard it. This test establishes a controlled same-text comparison, not an improvement guarantee.

The source reference and helper versions are checksum-pinned. Piper/encoder downloads retain their existing checks and restricted weight loading. Read-only repository/action permissions, CPU/network downloads, normal Actions usage and a 30-minute timeout apply. A package inventory is recorded. Existing voice, reference and avatar provenance/license limitations are not resolved by this test.
