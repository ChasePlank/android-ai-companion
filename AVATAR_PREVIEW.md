# Roxanne preview milestone

The launcher now opens AvatarPreviewActivity. Qwen chat and the original diagnostic/overlay controls are reachable through buttons; their source is unchanged. The floating rectangle is NOT replaced yet, and bone tests are not synchronized with speech.

## Build
Filament, gltfio and filament-utils are pinned to 1.78.1, the current GitHub release verified during preparation. AGP's built-in Kotlin support compiles the new activity; no separate Kotlin Android plugin is added. Java/Kotlin output targets 17 while the build runs on JDK 27. The existing build toolchain and workflow otherwise remain unchanged.

A preBuild dependency copies the root GLB into generated assets for APK packaging. The repository's original model is unchanged. The packaged model loads offline; no runtime model download is used. APK size and device memory use increase. Maven dependencies still require download during the build.

## Phone test
1. Install the successful workflow's APK, then launch Local AI Companion.
2. Wait for geometry and embedded textures. Report a blank view, black materials, missing parts, or errors.
3. Drag to orbit, pinch to zoom; use Reset pose and camera.
4. Select Jaw, choose a local axis, and move the slider a small amount around its center. Try the other axes if needed. Reset between tests.
5. Test eyelids and head separately, noting the axis and direction that look correct.
6. Open Qwen chat and verify a local reply, then return with Android Back. Test overlay controls separately.

Bones use original-local-transform times test rotation. No existing animation clips or morph targets are assumed. The preview does not promise realistic expressions, lip-sync, or a neutral pose different from the supplied asset. No device performance result is claimed before testing. This code has not been compiled or rendered in the chat environment; inspect CI and test on the phone.

## Attribution and rights
Asset title: Roxanne wolf security breach. Upload author: JetteBoy (https://sketchfab.com/Jetteboy).
Source: https://sketchfab.com/3d-models/roxanne-wolf-security-breach-0f47977bb2f7458fa56ac4e2c373ada9
Embedded upload license label: CC-BY-4.0, https://creativecommons.org/licenses/by/4.0/
The asset is copied into the APK unchanged; the viewer adds runtime camera and pose controls. This upload label does not independently establish permission from the underlying game character's rights holders. Preserve credit and review rights before distributing the APK or model.
