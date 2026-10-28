# Facial animation checkpoint - 2026-10-28

## Confirmed by phone testing

- The user reported successful newer-APK installation over the existing app without uninstalling. Preserve the existing persistent signing secret.
- Jaw local Y: -8 degrees closed, +20 degrees natural open. Preview endpoint buttons and calibrated rest were reported working.
- Original eye and eyelid poses are naturally open/centered.
- Eyelids rotate on local X. Both upper lids close positive; both lower lids close negative.
- Blink calibration: open upper 0 / lower 0; halfway upper +25 / lower -10; closed upper +50 / lower -50 degrees. The lower halfway value supersedes -25. Upper lids alone cannot fully cover this model's eyes; lower lids complete closure.
- Eye directions from her perspective: negative X up; negative Y her right; positive Y her left. Positive X down was inferred as the opposite of up and still needs explicit confirmation. Eye angular limits are not calibrated.
- Preview manual slider was expanded to -90 through +90 degrees at commit 61a8210.

## Saved code versus active behavior

FacialCalibration.kt stores reusable, bounded jaw/blink interpolation and gaze sign mapping. It is not yet called by the renderer. This checkpoint does not enable random blinking, eye motion, speech animation, or the 3D floating overlay. Existing preview and chat code are unchanged. No build result is claimed for this commit.

## Next implementation milestone

1. Replace the draggable text rectangle with a floating 3D Roxanne renderer. Frame chest-up, preserving head, ears, shoulders and upper chest. Check framing and transparency on the phone. Preserve a reliable hide/close control and clean up renderer resources when hidden.
2. Drive both eyes from one shared gaze target. Drive both upper lids together and both lower lids together using FacialCalibration.blinkPose. Apply offsets relative to saved original transforms, not cumulatively.
3. Add time-based blink animation with randomized waiting intervals and smooth closing/reopening. Lower-lid interpolation must pass through -10 degrees at half closure, reaching -50 only at full closure. Timing is a proposed behavior, not yet phone-tuned.
4. Add occasional small gaze shifts with holds and returns toward center; do not independently randomize the two eyes. Calibrate comfortable eye limits before choosing final amplitudes.
5. Connect existing Qwen text-to-speech to the floating jaw after overlay/idle testing. Return jaw to -8 on completion, cancellation or error. Basic talking motion is not phoneme-accurate lip-sync.

Do not regenerate signing keys, remove existing working features, or describe planned behavior as implemented.
