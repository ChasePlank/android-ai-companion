# Calibrated head idle motion

This revision extends the existing IdleFacialAnimator with Head_jnt_06. It supersedes the earlier no-head-movement statement in FLOATING_OVERLAY.md. Blink, eye limits, jaw offset, preview, chat and signing settings are unchanged.

## User-confirmed local directions

- X: negative tilts her left; positive tilts her right.
- Y: negative looks up; positive looks down.
- Z: negative turns her right; positive turns her left.
- Maximum offsets: -30 to +30 degrees on each axis, relative to the original local head transform.

## Procedural behavior

Eyes start a glance first; head movement starts 180-350 ms later and eases over 550-1200 ms. Most turns/nods follow 15-35% of the eye target, with a small +/-3 degree tilt. A proposed 12% larger-movement choice follows 55-80%, with tilt up to +/-8. All head outputs are clamped to +/-30, not driven to the maximum every time. Center gaze targets also return the head toward its original pose. The existing varied gaze holds provide head holds. These timings and motion choices are not yet phone-tested.

The original local head transform is cached once; offsets never accumulate. Pause/Resume and long-frame-gap reset now restore the head as well as eyes/lids, preserving the jaw's calibrated local offset. A missing head disables head motion and appears in the existing missing-joint warning; gaze and blinking can still work.

No breathing/body animation, speech jaw motion, gaze stabilization or exact world-space look-at solver is included. Eye offsets remain relative to the moving head; combined extreme poses and camera cropping require phone verification. No build result is claimed for this revision.

## Phone checks

Confirm directions, subtlety, combined eye/head appearance, head/neck deformation and whether ears/hair leave the chest-up crop. Test Pause and Resume, lock/unlock, and closing/reopening the overlay. A passing CI build confirms compilation only.
