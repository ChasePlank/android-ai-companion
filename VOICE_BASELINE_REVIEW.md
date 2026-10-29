# Neural baseline listening review

The first eSpeak baseline was reported to have unclear words and scrambled-sounding initial consonants. A successful conversion report did not establish intelligibility. This new stage isolates the source speech before any further conversion experiment.

Actions > Generate Roxy neural baseline runs automatically when its workflow or helper changes, or manually. It generates exactly: Hey there. This is a short voice conversion test. Piper uses en_US-lessac-medium at its native 22050-Hz rate. The WAV is saved without resampling, pitch/speed changes, cropping, concatenation by our helper or re-encoding. The existing RVC export, conversion settings and Android app are not changed or run.

Download roxy-neural-baseline and listen to baseline.wav. Verify every word and its initial consonants, not just whether the voice sounds more natural. A successful workflow means the WAV passed format/non-silence checks only. Human listening approval remains false until the user explicitly confirms it is clear. Save the exact WAV and baseline-manifest.json so a later conversion test can reuse its recorded checksum rather than regenerate a different input.

Download roxy-baseline-report and paste baseline-report.txt if anything fails. Dependencies failing before synthesis leave a not-started report; consult that job step. Both artifacts expire after seven days.

The voice assets are pinned to rhasspy/piper-voices revision 5041a7a49ae5b3a0f30e6837769eb03fdbb231bd. Their exact sizes and SHA-256/Git blob checksums are checked before synthesis. The installed Piper version and package inventory are recorded. The job uses read-only repository permissions, CPU and normal Actions usage, downloading approximately 63 MB of model data plus dependencies. It sends no user recordings to a speech service and commits no model weights.

The voice model card is included, with its dataset/license references. Engine and voice/dataset terms are separate and must be reviewed before distribution or commercial use; this test is not license clearance. This is an unconverted reference voice, not Roxanne, and no quality guarantee is made before listening. Only after the baseline is approved should the same unchanged audio be used for a controlled conversion comparison.
