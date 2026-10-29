# Selected voice confirmation and fixed-avatar inspection

Actions > Confirm selected Roxy voice and fixed avatar runs two independent jobs, automatically on changes to its new workflow/helper or manually. Neither modifies the Android app, ONNX, active avatar or existing test files. Artifacts expire after seven days.

## Voice audition

The user tentatively selected 03_gentler_rise_keep50.wav for a character-voice confirmation. The job retrieves the saved final-rise artifact from commit 9dcaa110a801ab3c4c8b06df9ea193411252a3c2 and requires SHA-256 990d780fb8a2bfa12b2ee849e19a134bc5f4225d7b255769894b9326c044a116. It checks the original edit manifest and reference checksum and refuses to regenerate or substitute audio. The keep50 name describes the earlier capped pitch edit, not a guarantee that the whole rise was halved.

The reviewed converter function is reused with a test-only baseline adapter and output directory, restoring module globals afterward. The same validated ONNX retains its existing hash and input-contract checks. Speaker ID remains 0 and added pitch shift remains 0. Standard preprocessing, content extraction, pitch mapping, excitation, random seeds and retrieval-disabled inference are unchanged. No further pitch edit or Piper synthesis occurs. Tentative listening selection does not mean final delivery approval.

Download roxy-selected-voice-report and paste report.txt. If successful, roxy-selected-voice-audio contains the exact baseline.wav, candidate_sid0_key0.wav, reference.wav and preserved edit manifest/model card. Compare whether the reduced rise sounds natural after conversion while retaining the voice you recognized. Numeric completion is not an accent, likeness, naturalness or phone-performance guarantee. The job uses CPU/dependency downloads, normal Actions usage and a 30-minute limit.

## Fixed avatar

The new file Roxanne_Wolf_Toggle_fix.glb has observed Git blob f07104f5dc2ce542ab8e7f1e7ac2705ebe627dd1. It is inspected rather than assumed repaired. The separate standard-library-only job compares it with the original rig using the reviewed GLB inspector, producing original/fixed node CSVs, full JSON inventory and compact report. It records joint-name overlap, missing face-name candidates, named hierarchy and local-transform differences, morph targets, materials and resources.

Download roxy-fixed-toggle-report and paste report.txt. Static similarity does not establish inverse-bind matrices, deformation, axis calibration, visual rendering, animation behavior or toggle safety. No selector is added and the original remains active. This job can finish independently if voice conversion fails. It uses read-only contents permission and a 10-minute limit. Full inspection limitations and existing licensing/provenance uncertainties remain in effect.
