package com.chase.companion
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class NodeAnimationPlayerTest {
    private fun pose()=JSONObject().put("nodes",JSONObject().put("blink",JSONArray(listOf(0,0,0))).put("head",JSONArray(listOf(0,0,0))).put("jaw",JSONArray(listOf(0,0,0))))
    private fun animation(vararg nodes: String)=JSONObject().put("owned",JSONArray(nodes.toList())).put("speed",1.0).put("poses",JSONArray().put(JSONObject().put("pose",pose()).put("move",1.0).put("hold",1.0).put("local",1.0)))
    @Test fun overlappingAnimationWaitsForBothOwners() {
        val p=NodeAnimationPlayer({pose()},{pose()},{})
        p.request("A",animation("blink")); p.request("C",animation("head")); p.request("B",animation("blink","head")); p.request("D",animation("jaw")); p.tick(1000000000L)
        assertEquals(setOf("A","C","D"),p.activeIds()); p.tick(5100000000L); assertEquals(setOf("B"),p.activeIds())
    }
    @Test fun cancellationOnlyResetsOwnedNodes() {
        val applied=mutableListOf<JSONObject>(); val p=NodeAnimationPlayer({pose()},{pose()},{applied.add(it)})
        p.request("A",animation("blink")); p.tick(1000000000L); p.cancelAll()
        assertEquals(setOf("blink"),applied.last().getJSONObject("nodes").keys().asSequence().toSet()); assertFalse(applied.last().has("camera")); assertFalse(p.running())
    }
    @Test fun cancelOneDoesNotResetAnother() {
        val applied=mutableListOf<JSONObject>(); val p=NodeAnimationPlayer({pose()},{pose()},{applied.add(it)})
        p.request("A",animation("blink")); p.request("C",animation("head")); p.tick(1000000000L); p.cancel("A")
        assertEquals(setOf("C"),p.activeIds()); assertEquals(setOf("blink"),applied.last().getJSONObject("nodes").keys().asSequence().toSet())
    }
    @Test fun localSpeedScalesTransitionButNotHold() {
        val p=NodeAnimationPlayer({pose()},{pose()},{})
        val a=animation("blink").put("speed",2.0); a.getJSONArray("poses").getJSONObject(0).put("local",2.0)
        p.request("A",a); p.tick(1000000000L); p.tick(2700000000L); assertTrue(p.running()); p.tick(2800000000L); assertFalse(p.running())
    }
}
