from pathlib import Path
import re
ROOT = Path(__file__).resolve().parents[1]
J = ROOT / 'app/src/main/java/com/chase/companion'
def once(s, old, new):
    if s.count(old) != 1:
        raise RuntimeError('Dispatch anchor changed: ' + old[:100])
    return s.replace(old, new, 1)
p = J / 'AlongsideActivity.kt'
s = p.read_text()
s = once(s, 'testing.tick(time, clip < 0 && page in listOf("Full Rig Inspection","Chat","Animations"))', '')
pattern = r'override\s+fun\s+doFrame\(\s*([A-Za-z_]\w*)\s*:\s*Long\s*\)\s*\{'
matches = list(re.finditer(pattern, s))
if len(matches) != 1:
    raise RuntimeError('Expected one frame callback; refusing to guess the scheduler insertion point.')
m = matches[0]
clock = m.group(1)
insertion = '\n            if (alive && resumed && ready) {\n                testing.tick(' + clock + ', clip < 0 && page in listOf("Full Rig Inspection", "Chat", "Animations"))\n            }\n'
s = s[:m.end()] + insertion + s[m.end():]
if s.count('testing.tick(') != 1:
    raise RuntimeError('Expected exactly one custom-animation update per frame.')
p.write_text(s)
p = J / 'TestingFeatures.kt'
s = p.read_text()
s = once(s, 'player.request("manual",draft)', 'player.request("manual:" + UUID.randomUUID().toString(),draft)')
s = once(s, 'val play = { player.cancel("manual"); selected=ids[n]; restore(); playDraft() }', 'val play = { selected=ids[n]; restore(); playDraft() }')
old = 'player.cancel("manual")'
if s.count(old) != 2:
    raise RuntimeError('Expected exactly two manual cancel controls.')
s = s.replace(old, 'player.cancelPrefix("manual:")')
p.write_text(s)
p = ROOT / 'app/src/test/java/com/chase/companion/NodeAnimationPlayerTest.kt'
s = p.read_text()
test = '''    @Test fun manualRequestsOverlapAndCancelWithoutStoppingAutomaticPlayback() {
        val p = NodeAnimationPlayer({pose()},{pose()},{})
        p.request("manual:first",animation("blink"))
        p.request("manual:second",animation("head"))
        p.request("auto:rotation",animation("jaw"))
        p.tick(1000000000L)
        assertEquals(setOf("manual:first","manual:second","auto:rotation"),p.activeIds())
        p.cancelPrefix("manual:")
        assertEquals(setOf("auto:rotation"),p.activeIds())
    }
'''
if not s.rstrip().endswith('}'):
    raise RuntimeError('Test class ending changed.')
p.write_text(s.rstrip()[:-1] + test + '}\n')
print('Moved saved-schedule and custom-playback updates to the ready/resumed frame callback, independent of built-in live animation. Manual requests now have unique IDs; manual cancel preserves automatic requests. Overlay idle code unchanged. Compilation and device verification required.')
