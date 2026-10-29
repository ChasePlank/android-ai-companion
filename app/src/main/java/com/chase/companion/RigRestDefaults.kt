package com.chase.companion

import android.opengl.Matrix
import com.google.android.filament.utils.ModelViewer

/** User-specified offsets relative to original local transforms, not absolute Euler angles. */
object RigRestDefaults {
    val bodyNames = setOf("L_Shoulder_jnt_028", "R_Shoulder_jnt_042", "TailB_jnt_063")
    fun offsets(name: String?): FloatArray = when (name) {
        "L_Shoulder_jnt_028", "R_Shoulder_jnt_042" -> floatArrayOf(-72f, 0f, 0f)
        "TailB_jnt_063" -> floatArrayOf(-90f, 0f, 0f)
        FacialCalibration.JAW -> floatArrayOf(0f, FacialCalibration.JAW_CLOSED, 0f)
        else -> floatArrayOf(0f, 0f, 0f)
    }

    /** Call once on a newly loaded overlay asset. Jaw has already been calibrated separately. */
    fun applyBody(viewer: ModelViewer): List<String> {
        val asset = viewer.asset ?: return bodyNames.toList()
        val missing = bodyNames.toMutableSet()
        val tm = viewer.engine.transformManager
        for (entity in asset.entities) {
            val name = asset.getName(entity) ?: continue
            if (name !in bodyNames) continue
            val instance = tm.getInstance(entity)
            if (instance == 0) continue
            val base = FloatArray(16).also { tm.getTransform(instance, it) }
            Matrix.rotateM(base, 0, offsets(name)[0], 1f, 0f, 0f)
            tm.setTransform(instance, base)
            missing.remove(name)
        }
        return missing.toList()
    }
}
