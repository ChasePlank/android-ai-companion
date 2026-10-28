# First floating 3D overlay milestone

The overlay controls now request a process-scoped 3D Roxanne window instead of the diagnostic text bubble. The original avatar preview and Qwen chat are unchanged. Existing signing settings are unchanged.

## Included

- Bundled offline GLB rendering through a non-opaque TextureView, transparent clear color and translucent Filament view. Transparency still needs phone verification.
- Initial chest-up camera framing based on normalized model height. Crop, facing direction, ears, hair and shoulders need visual tuning on the phone.
- Draggable title bar, explicit close button, and the existing Hide overlay control. Movement is clamped to current display metrics. Transparent areas within the window may still intercept touches.
- Calibrated jaw rest at -8 degrees local Y. No embedded animations autoplay.
- Roughly 30 Hz render scheduling while the display is on and interactive. Closing removes frame callbacks and releases custom lighting before ModelViewer's detach listener destroys its engine.
- Asset loading on a worker thread; Filament operations remain on the main thread. Late asset results are ignored after close.

## Not included yet

Random blinking, shared gaze animation and speech-to-jaw integration are not enabled by this milestone. FacialCalibration.kt remains the source for the next animation stage. This is not a foreground service and does not promise survival across process termination, reboot or background restrictions. Rotation/inset behavior and device performance remain untested.

## Later phone test

1. Update the installed app without uninstalling, using the verified new APK.
2. Open Overlay controls, grant display-over-apps permission and tap Show floating Roxanne.
3. Check loading, textures, lighting, transparent background and chest-up framing.
4. Drag by the title, open Qwen chat, then switch to another app. Check focus and touch behavior.
5. Close with the X button, reopen, and test Hide overlay. Repeat to catch resource-lifecycle issues.
6. Lock/unlock and rotate; report disappearance, errors, bad positioning, heat or stutter.

A passing GitHub build is only compilation verification; no phone rendering or performance result is claimed here.
