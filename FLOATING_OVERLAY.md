# Floating 3D overlay and coordinated idle face

The overlay controls request a process-scoped chest-up Roxanne window. The avatar preview, Qwen chat and persistent signing setup are unchanged.

## Implemented in this revision

- Existing bundled GLB, chest-up camera estimate, non-opaque TextureView, translucent rendering, draggable title, close and Hide controls remain. Crop/transparency still need phone testing.
- Both eyes share one gaze target, with smooth transitions, varied holds and probabilistic returns to center. User-supplied local eye limits: Y -20 degrees (her right) to +30 (her left); X -20 (up) to +20 (down). The gaze helper takes avatar-right/up amounts and negates them, so targetRight samples -30 to +20 and targetUp samples -20 to +20. Random targets are inside the limits, not forced to the endpoints. Combined animation appearance still needs phone testing.
- Both upper lids and both lower lids share one blink amount. Upper X: 0 / +25 / +50 degrees at open / halfway / closed. Lower X: 0 / -10 / -50. All lid offsets return to original pose after each blink.
- Random waits of 2.5-6.5 seconds, closing 80-120 ms, closed hold 15-35 ms and reopening 130-200 ms. A 12% optional second blink uses a 140-280 ms gap; no chains beyond a double blink. These are proposed animation parameters, not claims of physiological accuracy or phone-tested appearance.
- Pause restores original eyes/eyelids without changing jaw rest; Resume restarts the idle schedule. Long frame gaps reset idle state instead of fast-forwarding through missed blinks.
- Original local transforms are cached once, then multiplied by fresh offsets each frame to avoid cumulative rotation drift. Missing eye pair disables gaze; missing any lid disables the whole blink group, with a warning.
- Procedural animation runs in the existing render callback before bone-matrix updates; no separate repeating animation timers survive overlay close.

## Still pending

No speech-driven jaw, head movement, breathing/body movement or foreground service is included. Jaw remains -8 degrees local Y. Overlay survival across process termination/reboot is not promised. Transparent window areas may intercept touch. Rotation, lifecycle, performance, blink shape and eye movement need phone testing. No build result is claimed for this revision.

## Later phone test

1. Install the new verified APK over the existing app.
2. Show floating Roxanne and check crop, textures, transparency and resting jaw.
3. Watch both eyes: they must move in the same visual direction and not roll or cross.
4. Watch a minute of blinking: upper lids do most early movement, lower lids pass -10 at halfway and reach -50 only at full closure. Check both sides meet without gaps or clipping.
5. Pause: eyes/lids should return to original pose, with jaw still closed. Resume and test again.
6. Drag, close, reopen, switch apps, lock/unlock and rotate. Report stutter, heat, leaks or failures.

A passing GitHub build only verifies compilation, not rendering or natural appearance.
