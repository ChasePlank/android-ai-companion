# Roxy hands-free: experimental first stage

This is NOT the finished 24/7 assistant and does NOT implement RVC character-voice inference. It adds an activity-independent microphone foreground service, strict on-device recognizer selection, wake-prefixed command routing, local Qwen requests and optional ordinary offline TTS with existing jaw animation. Existing avatar appearance, framing, culling workaround and gestures are unchanged.

## Run

1. Open Overlay controls > Hands-free assistant (local test).
2. Keep the existing Qwen server and model running on the phone at 127.0.0.1:12434.
3. Start the test while the activity is visible and grant microphone permission. Allow notifications for the Stop listening action.
4. Hide the avatar, then say `Roxy, hello` or `Roxanne, hello`. The avatar should appear and the local reply should be visible in the test screen.
5. Say `Roxy, hide` or `Roxanne, hide yourself`. This must hide the avatar without disabling listening or generating a Qwen reply.
6. Say a name alone, pause for recognition to finish, then ask within 12 seconds. Also test a name and prompt in one utterance.
7. Background the activity or remove its task from Recents and repeat. Screen-off and Samsung battery-management behavior require device testing.
8. Say `Roxy, stop listening`, use the notification action, or Stop in the test screen. Verify microphone access ends.

Ordinary offline TTS is an explicitly labeled diagnostic opt-in, OFF by default. It is not Roxy's voice. Without it, generated replies remain text-only. Imported .pth/.index files are not executed. Third-party voice engine offline claims still need airplane-mode testing.

## Limits and privacy

- Sessions stop automatically after 20 minutes. Repeated Android SpeechRecognizer sessions are not an efficient, continuous wake-word engine; expect recognition gaps and possible battery drain. Production 24/7 listening needs a dedicated local wake detector and device testing.
- On-device recognizer availability and installed en-US support are checked or surfaced as errors. No generic/cloud ASR fallback is used. Errors and stalled recognition stop the test rather than claiming it is healthy.
- No boot receiver, default-assistant role, sticky microphone restart, or force-stop recovery is implemented. No permanent wake lock is added.
- No microphone recordings are saved. Pre-trigger recognition results are discarded. Only wake-triggered prompts go to the loopback Qwen endpoint. The last prompt/reply and bounded conversation are held in process memory. The app does not control the local server's own logging.
- Recognition is paused during Qwen generation and diagnostic speech; wake commands cannot interrupt those phases in this version.
- Existing tap-to-speak diagnostic/chat activities use their previous system recognition flow and are separate from this on-device service test. Avoid using other microphone or TTS test screens concurrently.
- All recognition, overlay and TTS lifecycle calls run on the main thread; network work runs on a worker. Stop destroys recognition, stops speech, releases jaw state and cancels the in-flight connection.

Build, recognition accuracy, battery use and screen-off operation have not been verified on the S25 Ultra. Do not describe this stage as finished Roxy voice or reliable 24/7 availability.
