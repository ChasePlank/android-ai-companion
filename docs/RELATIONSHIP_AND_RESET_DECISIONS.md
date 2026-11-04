# Approved relationship and animation reset decisions

Status: approved design decisions from the November 3, 2026 conversation. This commit records requirements only; it does not implement runtime behavior or claim build or test completion.

## Scope and precedence

These decisions clarify the existing behavior specification. Preserve unrelated requirements and character guides. Developer overrides must remain separate from real relationship progression. Persistence of an override does not authorize overwriting the real score.

## Relationship levels

| Threshold | Level |
| --- | --- |
| 0 | Hate |
| 50 | Dislike |
| 100 | Acquaintance |
| 200 | Teammate |
| 300 | Friend |
| 400 | Trusted Companion |
| 500 | Close Friend |
| 600 | Best Friend |
| 700 | Confidant |
| 800 | Partner |
| 900 | Sweetheart |
| 1000 | Lover |

Positive levels use fixed ranges: Acquaintance 100-199, Teammate 200-299, Friend 300-399, Trusted Companion 400-499, Close Friend 500-599, Best Friend 600-699, Confidant 700-799, Partner 800-899, Sweetheart 900-999, and Lover 1000-1099.

Hate and Dislike retain the previously agreed direction-dependent transitions. Their transition algorithm is not specified in this conversation; the thresholds above must not be treated as a replacement algorithm.

## Neutral messages and hidden progression

- Neutral messages earn +1, including repeated neutral messages.
- Do not add a neutral-message repetition limit.
- The user is not shown the level table, score ranges, or prize. Level-change messages remain required as described below.

## Developer override lifecycle

- An active developer relationship override persists across app closure and restart.
- Under ordinary permitted access, enabling or disabling it is a manual toggle.
- Blocking developer-mode access automatically switches the override off and restores the real relationship score as the effective score.
- Keep real progression separate from the persisted test override.

## Relationship transition messages

- When a user regains a previously held level, show a message indicating that they are back at that level, rather than a first-time congratulations message.
- Retain the previously agreed warning on downward crossings. This conversation does not specify the warning's exact wording.

## Final animation reset

- A final reset recenters both the camera and the model's nodes to their saved defaults.
- Normal completion performs the reset smoothly.
- Interruption performs the reset instantly.
- Resetting must not overwrite saved camera defaults, node defaults, calibration, or joint limits.
- This conversation does not specify a smooth-reset duration or easing curve.

## Acceptance checks for subsequent implementation

- Verify the approved level names and positive score boundaries, including Trusted Companion at 400 and Lover through 1099.
- Verify repeated neutral messages continue to receive +1.
- Verify ordinary user interfaces do not expose the progression table, score ranges, or prize while still delivering required transition messages.
- Verify an enabled developer override survives closure and restart without replacing the real score.
- Verify blocking developer access disables the override and restores the real effective score.
- Verify regaining a level emits a return-to-level message.
- Verify normal completion smoothly resets both camera and nodes.
- Verify interruption instantly resets both camera and nodes.
- Verify resets leave saved defaults, calibration, and limits unchanged.

Recovery rates, compliment penalties, animation sequencing, speed stacking, and exact direction-dependent transitions were referenced but not defined in this conversation. This checkpoint does not invent or change them.
