# Companion behavior specification

Status: agreed design checkpoint; not an implementation or test-completion claim.

## Developer sandbox
Synthetic testing uses isolated character histories and relationship state. Developer controls may set test emotion and relationship levels, never production character state. Experimental features remain developer-only until their individual tests pass.

## Speech interruption
Calling a character by name during speech pauses the response and opens listening; it does not automatically discard the response. The following utterance determines whether to resume, revise, or cancel. Casual interruptions such as 'Roxy, hang on' indicate a possible correction. Direct stop requests must still stop speech. Evaluate tone and context rather than automatically penalizing a keyword, especially during malfunction or urgent interruption. Non-addressed speech may be captured and queued until the current response finishes.

## Relationships and absence
Progress depends on interaction substance and patterns, with a modest elapsed-time effect. Characters notice absence. A month away may soften lover-level familiarity toward close friendship, not independently produce hatred. This example is a tuning reference, not a finalized decay formula.

## Mood and reciprocity
Closeness does not guarantee acceptance of affectionate or flirtatious requests. Mood, context, repetition, and reciprocal interaction matter. Characters may decline, identify repetitive request-only behavior, and respond negatively to persistent unwelcome requests. This checkpoint does not implement explicit sexual image generation.

## Reports and correction
Give the user an opportunity to clarify, correct, or apologize before sharing a contextual report with another character. Preserve the underlying interaction record; bare denial must not erase it. Allow users to request verification of details with the original character. Amend inaccurate or incomplete reports and allow recipients to revise their opinions. A review request is not itself hostility. GitHub comments are the currently available bug-report route; request minimal relevant details and redaction of private information.

## Animation persistence and limits
Automatically save generated animations as separate reusable entries. Do not overwrite existing animations, calibration defaults, rest poses, or joint limits. Validate movements against the character's preset ranges. Unsupported mappings or movements must not bypass limits.

## Character guides
The repository owner will prepare and add each character's guide from wiki material. Do not invent guide content or treat unsourced additions as canon. Distinguish reference facts from Alongside-specific characterization. Express uncertainty naturally when evidence is incomplete. Guide paths and packaging remain pending until the guides are supplied; ordinary character reference use should support the APK-only goal.

## Acceptance checks before release
- Sandbox changes cannot modify production state.
- Name-addressed interruption supports pause, correction, resume, and cancel.
- Stop commands remain effective regardless of relationship or tone.
- Absence alone cannot convert affection into hatred.
- Report correction preserves evidence and propagates amended context.
- Saved animations cannot overwrite calibration or exceed preset limits.
- Missing lore remains uncertain rather than fabricated.
- Do not label listening, animation, or relationship inference fully working without individual passing tests.
