from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
path = ROOT / 'app/src/main/java/com/chase/companion/TestingFeatures.kt'
source = path.read_text()
old = 'editPage?.invoke()'
new = 'editPage?.invoke(); Unit'
if source.count(new) == 2 and source.count(old) == 2:
    print('Both editor callbacks already return non-null Unit.')
elif source.count(old) == 2 and new not in source:
    updated = source.replace(old, new)
    assert updated.count(new) == 2
    path.write_text(updated)
    print('Fixed both editor-opening callbacks: explicit Unit after optional invocation.')
else:
    raise RuntimeError('Expected exactly two editor-opening callbacks; refusing an unsafe replacement.')

import runpy
runpy.run_path(str(ROOT / 'tools/apply_node_animation_revision.py'), run_name='__main__')
