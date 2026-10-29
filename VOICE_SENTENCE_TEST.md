# Convert the exact listening-approved baseline

The user approved the clearer Piper baseline for conversion and supplied SHA-256 de8acd0cbe94d72d8e3d0780368ac53aacfcf99e40581f2848748a465cfda56f. It is the 2.856054-second mono 22050-Hz WAV saying: Hey there. This is a short voice conversion test.

Actions > Test Roxy candidate sentence now retrieves saved baseline artifacts from successful runs of baseline commit cb9570a2713815c28145c0165c98fbb6c19f988e and selects only a WAV matching that exact checksum. It does not regenerate Piper speech, invoke eSpeak or silently substitute another input. It refuses to proceed if matching artifacts have expired or are unavailable. The baseline checksum and metadata are checked again by the conversion script.

The initial push test keeps speaker ID 0 and pitch shift 0, the same ONNX checksum, content encoder, pitch extraction/bin mapping, excitation, random seeds and conversion preprocessing. The input WAV changes; the conversion algorithm does not. Standard in-memory 16-kHz resampling, level handling, high-pass filtering and reflection padding still apply, exactly as in the first conversion test. The uploaded baseline.wav itself is copied byte-for-byte and unchanged. No ONNX re-export, Android changes or retrieval-index use is introduced. Speaker ID 0 remains unconfirmed.

Open the new run and download roxy-sentence-report; paste sentence-report.txt. On success, roxy-candidate-sentence contains the exact approved baseline.wav and candidate_sid0_key0.wav with default settings. Listen at low volume and compare intelligibility first, then voice character. A created WAV is not a likeness guarantee or phone benchmark.

The copied approved-baseline-manifest.json is preserved unchanged: its original listening_approved=false reflects generation before human review. The conversion report separately records baseline_listening_approved=true for the exact checksum approved in this conversation, plus source run ID and baseline_regenerated=false. It does not rewrite the historical manifest.

The saved validated synthesizer is still required from successful export commit b4717ca074d66b0e2913384546edfb4c9431ca67. Hash guards, restricted encoder weight loading and finite/shape checks remain active. Artifacts last seven days. Read-only repository/action permissions, CPU/network downloads, normal Actions usage and the 30-minute timeout are unchanged. Manual runs retain optional candidate-speaker and pitch controls for later experiments; use defaults for this comparison. Existing provenance and licensing limitations remain unresolved.
