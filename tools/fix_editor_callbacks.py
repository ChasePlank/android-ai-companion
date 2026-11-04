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

gradle = ROOT / 'app/build.gradle'
source = gradle.read_text()
old = "tasks.named('assembleDebug').configure { dependsOn('testDebugUnitTest') }"
new = "tasks.configureEach { task ->\n    if (task.name == 'assembleDebug') {\n        task.dependsOn('testDebugUnitTest')\n    }\n}"
if source.count(old) != 1:
    raise RuntimeError('Expected exactly one debug assembly test dependency; refusing an unsafe replacement.')
gradle.write_text(source.replace(old, new, 1))
print('Deferred assembleDebug configuration until the Android task is available; unit-test dependency retained.')

activity = ROOT / 'app/src/main/java/com/chase/companion/AlongsideActivity.kt'
source = activity.read_text()
lines = source.splitlines(keepends=True)
rows = [i for i, line in enumerate(lines) if 'button(panel,"Default home model")' in line]
old = '}.setNegativeButton("Cancel",null).show() }'
if len(rows) != 1 or lines[rows[0]].count(old) != 1:
    raise RuntimeError('Expected exactly one generated home-model button; refusing an unsafe replacement.')
lines[rows[0]] = lines[rows[0]].replace(old, old + ';', 1)
activity.write_text(''.join(lines))
print('Separated the generated home-model button from the following theme declaration.')
