# Qwen speech-to-jaw integration (push 1 of 4)

Qwen chat now assigns a unique utterance ID to each spoken reply. UtteranceProgressListener starts the process-local jaw gate on onStart and clears it on completion, stop or error. Queue rejection, explicit cancellation, starting recognition, replacing speech and activity destruction also clear the matching gate. Late callbacks cannot change a newer ID. No cloud/model endpoint or signing settings changed.

The floating animator reads the gate on its existing render callback and generates bounded stylized jaw motion. Its jaw base is captured after the overlay applies the -8 degree closed calibration; relative motion is 0 to +28 degrees, yielding -8 to +20 degrees local Y relative to the original jaw. Fresh transforms avoid cumulative drift. No movement occurs merely while Qwen is generating or speech is queued. Mouth closure is applied on the next active render frame after the gate clears. Missing jaw is reported by the existing overlay warning.

Blink, gaze and head motion continue during speech. The idle pause button pauses head/eyes/lids only; speech jaw remains active. Closing the overlay destroys the renderer but does not stop chat speech. Reopening captures fresh transforms and reads current playback state.

This is playback-gated procedural talking, not audio-amplitude analysis, phoneme lip-sync or silence detection inside a spoken sentence. TTS provider callback timing and visual synchronization need phone testing. Diagnostic echoes in MainActivity are not connected by this push. No background service or guarantee of process survival is added.

Not included here: spoken-animation test button, framing controls or rig search/filter. Those remain separate pushes. No successful compilation or phone result is claimed for this revision.

## Later phone checks

Show the overlay, open Qwen chat and request a short reply. Confirm the mouth remains closed during generation, moves during audible speech and returns to -8 when done. Test Stop request / speech, interrupt with a new request, start recognition, clear the conversation and leave/destroy chat. Check blinking/head motion continue, idle pause does not freeze a speaking jaw, and old callbacks cannot reopen a stopped mouth.
