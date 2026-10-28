# Full-rig preview controls

The preview no longer filters to eight face/head names. It lists every loaded asset entity with a valid transform, including unnamed nodes and duplicate names distinguished by entity ID. The GLB file is unchanged. This exposes existing skeleton, hair and other transform nodes; it does not add bones or guarantee each node is a deforming joint. Node names are not automatically classified as anatomy.

Each node has independent stored X/Y/Z offsets, combined in X-then-Y-then-Z matrix construction relative to its original local transform. Changing node or axis updates the slider and angle readout without changing the pose. The range stays -90 to +90 degrees. The face nodes sort first, remaining nodes alphabetically. No search/filter is included yet.

Reset selected restores only the chosen node, preserving the jaw's -8 degree rest when that node is the jaw. Stop / calibrated rest and Reset pose and camera reload the complete original rig plus calibrated jaw rest. Jaw open/closed shortcuts remain. Clips can overwrite manual poses; Stop before clean calibration.

Use small offsets first on untested torso, arm or hair controls. Parent rotations can move many child parts. Body/hair automatic movement is not enabled. The floating overlay, its idle animator, chat and signing settings are unchanged. No compile or phone result is claimed for this revision.

Later phone test: verify the expanded node count; find arm, shoulder, torso and hair nodes by their actual names and visible response; check each axis; switch away/back and confirm angles remain correct; test combined XYZ offsets and both reset controls. Report useful names, signed direction and comfortable limits for future body/hair animation.
