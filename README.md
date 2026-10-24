# Android AI Companion

Private, incremental Android companion project. Test device supplied by owner: Samsung Galaxy S25 Ultra 256 GB, Android 17, One UI 9.0. Device compatibility has not been verified.

## Milestone 0: diagnostic shell
Implemented in source: text input with explicitly labeled diagnostic echoes, Android speech-recognition activity handoff, Android text-to-speech, draggable rectangular overlay with hide controls. This is NOT yet an AI assistant or 3D avatar. No model, memory database, device automation, wake word, or remote API is implemented.

## Build and install
Open GitHub Actions, select Build diagnostic APK, and open the latest successful run. Download the companion-diagnostic-debug artifact, extract its ZIP, and install app-debug.apk on your own test phone. Android may require allowing installs from your chosen file manager. Do not disable device protections globally. GitHub-hosted Actions usage may count against your private-repository allowance; check your billing settings. The workflow runs on pushes to main and can also be started manually. No build has been verified at source creation time.

For a local build, install JDK 17, Android SDK 35 and Gradle 8.11.1, set ANDROID_HOME or create local.properties with sdk.dir, then run `gradle :app:assembleDebug`. A Gradle wrapper is not included yet.

## Phone test
1. Open app and send a typed diagnostic message.
2. Confirm spoken output, or report missing voice data.
3. Tap to speak; report whether the provider opens and returns transcription.
4. Grant display-over-apps permission; return and show overlay.
5. Open another non-sensitive app and drag the overlay.
6. Hold without moving for over 700 ms and release to dismiss, or use Hide overlay in the companion app.
7. Report crashes, build errors, and whether the overlay survives normal app switching.

## Privacy and limitations
The app itself has no internet permission and stores no transcripts persistently. Your separately installed speech recognition and TTS providers may use network services; offline operation is NOT guaranteed. There is no continuous microphone capture. Overlay is a process-lifetime diagnostic, not a foreground service; Android may terminate it. Some apps may suppress overlays. The overlay is a placeholder rectangle, not a 3D character, and does not yet open chat. Debug APK is for testing only.

## Next milestones
- Kotlin/native local GGUF backend with streamed responses and cancellation; select Qwen size after measuring available RAM and latency.
- Replace diagnostic echoes with real inference; never silently substitute fake responses.
- Local, user-editable memory and personality.
- Animated GLB avatar with clearly licensed assets and mouth animation.
- Compliant foreground lifecycle and explicit microphone controls.
- Narrow, permission-gated device actions; confirmation for consequential actions.

Do not commit API keys, signing keys, personal conversations, or downloaded model weights.
