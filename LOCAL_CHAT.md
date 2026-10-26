# Local Qwen chat integration

Server supplied by owner: http://127.0.0.1:12434. Model: qwen2.5-0.5b-instruct-q4_k_m.

Launcher now opens LocalChatActivity. The original tested MainActivity remains accessible through Overlay / diagnostic controls; its replies remain diagnostic echoes, not AI. Return with Android Back to use real chat. Existing overlay implementation is unchanged.

Chat sends POST /v1/chat/completions with Bearer anything, stream=false and max_tokens=256. No model download, cloud endpoint, redirect following or fallback is implemented. Cleartext HTTP is allowed only for domain 127.0.0.1 in the network security configuration. INTERNET permission is needed for loopback sockets; it is not a promise that other installed voice providers work offline. This is a client for the separate server app, not embedded Qwen inference.

Keep the local server running with your model selected. Install the new CI artifact as an update where the signing key permits; GitHub debug signing keys may differ between runners, so Android may require uninstalling the old diagnostic package first. This app has no persistent conversation storage. The new integration has not been device-tested at commit time.

Test typed chat first, then a follow-up question, voice input and spoken answers. Errors show HTTP status/body excerpts or exception details. A model-not-found error means we must use the server's exact registered model ID, not guess a different one.

Recent completed exchanges are retained in RAM (maximum six pairs) and included up to a 4000-character history budget; this is not a token-accurate context budget and long inputs may still exceed the model context. Conversation is lost on activity recreation. Clear conversation removes it. Replies are displayed when complete, not token-streamed. Stop closes the client request and suppresses stale results; it does not guarantee immediate server-side inference cancellation. Read timeout is 180 seconds. No 3D avatar, durable memory, wake word or device actions are added.
