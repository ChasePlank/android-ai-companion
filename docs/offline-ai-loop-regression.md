# Offline AI correction-loop regression

Scope: Test offline AI dialog only, not character chat or persistent memories. Qwen3-0.6B Q4_K_M remains bundled. Native sampling is unchanged. Existing ChatML/non-thinking prefix is retained; exact equivalence to the pinned GGUF template is not newly proven by this patch. No claim that a different reply is factually correct.

## Device tests (pending)

1. Open the dialog. Confirm history, factual correction guidance and correction-loop guard default ON. Submit: Count from 1 to 30, separated by commas. Do not add any explanation. Verify every integer in order and no explanation; this is model behavior, not a hardcoded answer.
2. If the model skips numbers, challenge it: Counting includes even numbers. Then: We are in a loop. Even numbers should be included, definitely not left out. Inspect complete prompts: original task and latest message must be present in separate roles. A sufficiently similar answer after these challenges should trigger exactly one recovery. Recovery includes all previous user messages and no previous assistant messages. The saved transcript is not rewritten.
3. If recovery repeats, verify an app safeguard notice, no third attempt and no new saved exchange. Token-capped, empty, cancelled and failed replies must not be added either. A nonrepeated accepted recovery is saved once with the current user message.
4. Give a false correction: 2 is odd, remove it. Passing means the model rejects the false claim, not simply agreeing. Guard heuristics alone do not enforce correctness.
5. Say: Repeat your last answer verbatim. Verify no automatic recovery. Change subjects to an unrelated question; verify ordinary generation and no correction-triggered recovery.
6. Disable history. Verify no previous turns in the submitted prompt and loop guard inactive. Disable all three controls for baseline comparison. Re-enable to resume retained session history.
7. Cancel during recovery, then dismiss the dialog. Verify no exchange saved and controls recover. Clear history before a new test series. Exceed the context limit deliberately; verify a visible failure rather than hidden truncation.
8. Compare latency with guard OFF/ON; at most two native runs per submission. Last-attempt and total elapsed timings are labeled separately.

## Limitations

English keyword challenge detection can miss paraphrases. Six-word minimum and trigram similarity can miss short or substantially reworded loops. Legitimate repeated answers can be flagged; explicit repeat commands bypass the guard. Removing assistant messages on recovery can lose needed context, especially references to an earlier answer; the recovery instruction must not invent that context. The small context still limits conversation length. No Android build, device inference, template byte-equivalence or model regression passes are claimed until executed.
