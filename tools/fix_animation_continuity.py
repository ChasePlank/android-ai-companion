from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
J = ROOT / 'app/src/main/java/com/chase/companion'
def once(s, old, new):
    if s.count(old) != 1:
        raise RuntimeError('Continuity anchor changed: ' + old[:100])
    return s.replace(old, new, 1)
p = J / 'NodeAnimationPlayer.kt'
s = p.read_text()
s = once(s, 'val hold=frame.optDouble("hold",0.0).coerceAtLeast(0.0);', 'val hold=if(run.request.animation.optBoolean("useHolds",false)) frame.optDouble("hold",0.0).coerceAtLeast(0.0) else 0.0;')
s = once(s, 'apply(blend(from,to,t*t*(3-2*t)))', 'apply(blend(from,to,t))')
p.write_text(s)
p = J / 'TestingFeatures.kt'
s = p.read_text()
s = once(s, '.put("hold", if (locked) 0.0 else 1.0)', '.put("hold", 0.0)')
s = once(s, 'number("Hold seconds", item.optDouble("hold", 1.0))', 'number("Hold seconds", item.optDouble("hold", 0.0))')
anchor = '        button(p, "Hold")'
control = '''        p.addView(Switch(a).apply {
            text = "Pause at poses (use Hold)"
            isChecked = draft.optBoolean("useHolds", false)
            setOnCheckedChangeListener { _, enabled -> draft.put("useHolds", enabled); dirty = true }
        })
'''
s = once(s, anchor, control + anchor)
p.write_text(s)
p = ROOT / 'app/src/test/java/com/chase/companion/NodeAnimationPlayerTest.kt'
s = p.read_text()
s = once(s, '.put("speed",1.0).put("poses",', '.put("speed",1.0).put("useHolds",true).put("poses",')
test = '''    @Test fun seamlessPlaybackDoesNotStopAtPoseBoundary() {
        val applied = mutableListOf<JSONObject>()
        val p = NodeAnimationPlayer({pose()},{pose()},{applied.add(it)})
        val a = animation("blink")
        a.remove("useHolds")
        val frames = a.getJSONArray("poses")
        val first = frames.getJSONObject(0)
        first.getJSONObject("pose").getJSONObject("nodes").put("blink",JSONArray(listOf(10,0,0)))
        val second = JSONObject(first.toString())
        second.getJSONObject("pose").getJSONObject("nodes").put("blink",JSONArray(listOf(20,0,0)))
        frames.put(second)
        p.request("A",a); p.tick(1000000000L)
        fun angle() = applied.last().getJSONObject("nodes").getJSONArray("blink").getDouble(0)
        p.tick(2250000000L); assertEquals(2.5,angle(),0.00001)
        p.tick(3010000000L); assertEquals(10.1,angle(),0.00001)
        p.tick(3500000000L); assertEquals(15.0,angle(),0.00001)
    }
'''
if not s.rstrip().endswith('}'):
    raise RuntimeError('Test class ending changed')
s = s.rstrip()[:-1] + test + '}\n'
p.write_text(s)
print('Continuous interpolation enabled; new holds default to zero, saved holds preserved and opt-in. Added pose-boundary regression test; existing explicit-hold timing test retained.')
