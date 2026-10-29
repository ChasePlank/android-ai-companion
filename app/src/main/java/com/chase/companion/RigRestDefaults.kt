package com.chase.companion

import android.opengl.Matrix
import com.google.android.filament.utils.ModelViewer

/** User-specified offsets relative to original local transforms, not absolute Euler angles. */
object RigRestDefaults {
    const val HEAD = "Head_jnt_06"
    const val HEAD_DOWN_DEGREES = 10f
    const val EYES_UP_DEGREES = 10f
    val bodyNames = setOf("L_Shoulder_jnt_028", "R_Shoulder_jnt_042", "TailB_jnt_063")
    private val restNames = bodyNames + setOf(HEAD, FacialCalibration.LEFT_EYE, FacialCalibration.RIGHT_EYE)

    fun offsets(name: String?): FloatArray = when (name) {
        "L_Shoulder_jnt_028", "R_Shoulder_jnt_042" -> floatArrayOf(-72f, 0f, 0f)
        "TailB_jnt_063" -> floatArrayOf(-90f, 0f, 0f)
        HEAD -> floatArrayOf(0f, HEAD_DOWN_DEGREES, 0f)
        FacialCalibration.LEFT_EYE, FacialCalibration.RIGHT_EYE -> floatArrayOf(-EYES_UP_DEGREES, 0f, 0f)
        FacialCalibration.JAW -> floatArrayOf(0f, FacialCalibration.JAW_CLOSED, 0f)
        else -> floatArrayOf(0f, 0f, 0f)
    }

    /** Call once on a new overlay asset, before IdleFacialAnimator caches its joint bases.
     * Head down is positive local Y; eye up is negative local X on both eyes.
     * The right eye's separate horizontal gaze inversion stays in IdleFacialAnimator.
     * Jaw has already been calibrated separately. Preview uses the same offsets by name.
     */
    fun applyBody(viewer: ModelViewer): List<String> {
        val asset = viewer.asset ?: return restNames.toList()
        val missing = restNames.toMutableSet()
        val tm = viewer.engine.transformManager
        for (entity in asset.entities) {
            val name = asset.getName(entity) ?: continue
            if (name !in restNames) continue
            val instance = tm.getInstance(entity)
            if (instance == 0) continue
            val base = FloatArray(16).also { tm.getTransform(instance, it) }
            val xyz = offsets(name)
            if (xyz[0] != 0f) Matrix.rotateM(base, 0, xyz[0], 1f, 0f, 0f)
            if (xyz[1] != 0f) Matrix.rotateM(base, 0, xyz[1], 0f, 1f, 0f)
            if (xyz[2] != 0f) Matrix.rotateM(base, 0, xyz[2], 0f, 0f, 1f)
            tm.setTransform(instance, base)
            missing.remove(name)
        }
        return missing.toList()
    }
}
