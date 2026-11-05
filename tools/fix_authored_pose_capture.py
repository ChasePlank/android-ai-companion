from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
J = ROOT / 'app/src/main/java/com/chase/companion'
def once(s, old, new):
    if s.count(old) != 1:
        raise RuntimeError('Authored-pose anchor changed: ' + old[:100])
    return s.replace(old, new, 1)
p = J / 'TestingFeatures.kt'
s = p.read_text()
s = once(s, '    private var editorLoaded = false', '    private val authoredPose = AuthoredPoseState()\n    private var editorPreview: String? = null\n    private var editorLoaded = false')
s = once(s, 'draft.put("poses", editable); cursor = 0; dirty = false', 'draft.put("poses", editable); authoredPose.reset(); editorPreview=null; cursor = 0; dirty = false')
s = once(s, 'selected = ""; draft = fresh(); cursor = 0;', 'selected = ""; draft = fresh(); authoredPose.reset(); editorPreview=null; cursor = 0;')
s = once(s, 'private fun commitPose() { ensurePose(); ownership(); draft.getJSONArray("poses").getJSONObject(cursor).put("pose",player.sparse(capture(),owners())); dirty=true }', 'private fun commitPose(force: Boolean = false) { ensurePose(); ownership(); if (authoredPose.commit(draft.getJSONArray("poses").getJSONObject(cursor), { player.sparse(capture(),owners()) }, force)) dirty=true }')
s = once(s, '{ stop(); commitPose(); Toast.makeText(a,"Pose ${cursor+1} set"', '{ stop(); commitPose(true); Toast.makeText(a,"Pose ${cursor+1} set"')
s = once(s, 'draft.put("owned",JSONArray((owners()+node).toList())); dirty=true', 'draft.put("owned",JSONArray((owners()+node).toList())); authoredPose.edited(); dirty=true')
s = once(s, 'poses.put(entry(capture()))', 'poses.put(entry(poses.getJSONObject(cursor).getJSONObject("pose")))')
s = once(s, 'poses.remove(cursor); ensurePose(); dirty=true;', 'poses.remove(cursor); authoredPose.reset(); ensurePose(); dirty=true;')
s = once(s, 'player.request("manual:" + UUID.randomUUID().toString(),draft)', 'val id = "manual:" + UUID.randomUUID().toString(); if (player.request(id,draft) && editorLoaded) editorPreview=id')
s = once(s, 'player.tick(time); if(!enabled) return player.running()', '''player.tick(time)
        editorPreview?.let { id ->
            if (!player.contains(id)) {
                editorPreview=null
                if (editorLoaded && !authoredPose.changed) { ensurePose(); apply(draft.getJSONArray("poses").getJSONObject(cursor).getJSONObject("pose")) }
            }
        }
        if(!enabled) return player.running()''')
p.write_text(s)
(J / 'AuthoredPoseState.kt').write_text('''package com.chase.companion
import org.json.JSONObject
internal class AuthoredPoseState {
    var changed = false
        private set
    fun edited() { changed=true }
    fun reset() { changed=false }
    fun commit(frame: JSONObject, capture: () -> JSONObject, force: Boolean = false): Boolean {
        if (!changed && !force) return false
        val snapshot = JSONObject(capture().toString())
        frame.put("pose",snapshot)
        changed=false
        return true
    }
}
''')
tests = ROOT / 'app/src/test/java/com/chase/companion'
(tests / 'AuthoredPoseStateTest.kt').write_text('''package com.chase.companion
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class AuthoredPoseStateTest {
    @Test fun previewDefaultDoesNotOverwriteFirstPose() {
        val state=AuthoredPoseState()
        val first=JSONObject().put("pose",JSONObject().put("angle",25))
        var captured=false
        assertFalse(state.commit(first,{ captured=true; JSONObject().put("angle",0) }))
        assertFalse(captured)
        assertEquals(25,first.getJSONObject("pose").getInt("angle"))
    }
    @Test fun realEditCommitsOnceButLaterDefaultIsIgnored() {
        val state=AuthoredPoseState()
        val first=JSONObject()
        state.edited()
        assertTrue(state.commit(first,{ JSONObject().put("angle",25) }))
        assertFalse(state.changed)
        assertFalse(state.commit(first,{ JSONObject().put("angle",0) }))
        assertEquals(25,first.getJSONObject("pose").getInt("angle"))
    }
    @Test fun explicitSetPoseCanCaptureDefaultAndResetClearsPendingEdit() {
        val state=AuthoredPoseState()
        val first=JSONObject()
        assertTrue(state.commit(first,{ JSONObject().put("angle",0) },true))
        assertEquals(0,first.getJSONObject("pose").getInt("angle"))
        state.edited(); state.reset()
        assertFalse(state.commit(first,{ JSONObject().put("angle",25) }))
    }
}
''')
p = tests / 'NodeAnimationPlayerTest.kt'
s = p.read_text()
test = '''    @Test fun defaultEndpointsPrecedeAndFollowAllAuthoredPoses() {
        val applied=mutableListOf<JSONObject>()
        val p=NodeAnimationPlayer({pose()},{pose()},{applied.add(it)})
        fun frame(angle: Int, locked: Boolean)=JSONObject().put("pose",pose().apply { getJSONObject("nodes").put("blink",JSONArray(listOf(angle,0,0))) }).put("move",1.0).put("hold",0.0).put("local",1.0).put("locked",locked)
        val frames=JSONArray().put(frame(0,true)).put(frame(10,false)).put(frame(20,false)).put(frame(0,true))
        val a=animation("blink").put("useHolds",false).put("poses",frames)
        p.request("A",a); p.tick(1000000000L)
        fun angle()=applied.last().getJSONObject("nodes").getJSONArray("blink").getDouble(0)
        p.tick(1500000000L); assertEquals(0.0,angle(),0.00001)
        p.tick(2500000000L); assertEquals(5.0,angle(),0.00001)
        p.tick(3500000000L); assertEquals(15.0,angle(),0.00001)
        p.tick(4500000000L); assertEquals(10.0,angle(),0.00001)
        p.tick(5000000000L); assertEquals(0.0,angle(),0.00001)
        assertEquals(10,frames.getJSONObject(1).getJSONObject("pose").getJSONObject("nodes").getJSONArray("blink").getInt(0))
    }
'''
if not s.rstrip().endswith('}'):
    raise RuntimeError('Test class ending changed.')
p.write_text(s.rstrip()[:-1] + test + '}\n')
print('Guarded automatic pose capture against preview/default state, restored selected authored pose after editor preview, preserved explicit Set pose and default endpoints. Added capture and endpoint regression tests. Build and device verification required.')
