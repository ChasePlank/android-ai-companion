package com.chase.companion

import com.google.android.filament.utils.Manipulator
import org.json.JSONArray
import org.json.JSONObject

internal object PreviewCameraDefaults {
    private fun json(v: FloatArray) = JSONArray(v.map { it.toDouble() })
    private fun vector(o: JSONObject, key: String): FloatArray {
        val a = o.getJSONArray(key)
        require(a.length() == 3) { "Invalid saved camera vector" }
        return FloatArray(3) { a.getDouble(it).toFloat() }.also { v -> require(v.all { it.isFinite() }) { "Invalid saved camera values" } }
    }
    private fun validate(eye: FloatArray, target: FloatArray, up: FloatArray) {
        require(listOf(eye, target, up).all { v -> v.size == 3 && v.all { it.isFinite() } })
        val d = DoubleArray(3) { target[it].toDouble() - eye[it] }
        val cross = doubleArrayOf(d[1] * up[2] - d[2] * up[1], d[2] * up[0] - d[0] * up[2], d[0] * up[1] - d[1] * up[0])
        require(d.sumOf { it * it } > 1e-12 && cross.sumOf { it * it } > 1e-12) { "Invalid camera direction" }
    }
    fun capture(controller: Manipulator): JSONObject {
        check(controller.mode == Manipulator.Mode.ORBIT) { "Preview camera must use orbit mode" }
        val eye = FloatArray(3); val target = FloatArray(3); val up = FloatArray(3)
        controller.getLookAt(eye, target, up)
        validate(eye, target, up)
        return JSONObject().put("version", 1).put("eye", json(eye)).put("target", json(target)).put("up", json(up))
    }
    fun restore(controller: Manipulator, saved: JSONObject?): Boolean {
        if (saved == null) return false
        require(saved.optInt("version") == 1) { "Unsupported saved camera format" }
        check(controller.mode == Manipulator.Mode.ORBIT)
        val eye = vector(saved, "eye"); val target = vector(saved, "target"); val up = vector(saved, "up")
        validate(eye, target, up)
        val restored = Manipulator.Builder()
            .targetPosition(target[0], target[1], target[2])
            .orbitHomePosition(eye[0], eye[1], eye[2])
            .upVector(up[0], up[1], up[2])
            .build(Manipulator.Mode.ORBIT)
        controller.jumpToBookmark(restored.homeBookmark)
        return true
    }
}
