package com.chase.companion

import android.opengl.Matrix
import com.google.android.filament.utils.ModelViewer
import kotlin.random.Random

/** User-calibrated joint directions/limits; procedural timing and appearance need phone testing. */
class IdleFacialAnimator(private val viewer: ModelViewer) {
    private data class Joint(val instance: Int, val base: FloatArray)
    private val joints = mutableMapOf<String, Joint>()
    private val upper = listOf(FacialCalibration.LEFT_UPPER_LID, FacialCalibration.RIGHT_UPPER_LID)
    private val lower = listOf(FacialCalibration.LEFT_LOWER_LID, FacialCalibration.RIGHT_LOWER_LID)
    private val eyes = listOf(FacialCalibration.LEFT_EYE, FacialCalibration.RIGHT_EYE)
    private val head = "Head_jnt_06"
    private val wanted = upper + lower + eyes + head
    private val rotation = FloatArray(16)
    private val result = FloatArray(16)
    private val random = Random.Default
    private var lastTime = 0L
    private var clock = 0f
    private var blinkStart = -1f
    private var nextBlink = between(2.5f, 6.5f)
    private var closing = 0.09f
    private var hold = 0.025f
    private var opening = 0.15f
    private var isSecondBlink = false
    private var nextGaze = between(1f, 2.5f)
    private var gazeStart = -1f
    private var gazeDuration = 0.2f
    private var fromRight = 0f
    private var fromUp = 0f
    private var targetRight = 0f
    private var targetUp = 0f
    private var currentRight = 0f
    private var currentUp = 0f
    private val headCurrent = FloatArray(3)
    private val headFrom = FloatArray(3)
    private val headTarget = FloatArray(3)
    private var headStart = -1f
    private var headDuration = 0.8f
    var enabled = true
        private set

    init {
        val asset = viewer.asset
        if (asset != null) {
            val tm = viewer.engine.transformManager
            for (entity in asset.entities) {
                val name = asset.getName(entity) ?: continue
                val instance = tm.getInstance(entity)
                if (name in wanted && instance != 0) {
                    joints[name] = Joint(instance, FloatArray(16).also { tm.getTransform(instance, it) })
                }
            }
        }
    }

    val missingJoints: List<String> get() = wanted.filter { it !in joints }
    private val lidsAvailable get() = (upper + lower).all { it in joints }
    private val eyesAvailable get() = eyes.all { it in joints }
    private val headAvailable get() = head in joints
    private fun between(low: Float, high: Float) = low + random.nextFloat() * (high - low)
    private fun smooth(value: Float): Float { val t = value.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    fun setIdleEnabled(value: Boolean) {
        enabled = value
        reset()
    }

    /** Restore original head/eye/lid transforms without touching the calibrated jaw offset. */
    private fun reset() {
        val tm = viewer.engine.transformManager
        joints.values.forEach { tm.setTransform(it.instance, it.base) }
        lastTime = 0L; clock = 0f; blinkStart = -1f; isSecondBlink = false
        nextBlink = between(2.5f, 6.5f); nextGaze = between(1f, 2.5f); gazeStart = -1f
        fromRight = 0f; fromUp = 0f; targetRight = 0f; targetUp = 0f; currentRight = 0f; currentUp = 0f
        headCurrent.fill(0f); headFrom.fill(0f); headTarget.fill(0f); headStart = -1f
    }

    fun update(time: Long) {
        if (!enabled) { lastTime = 0L; return }
        if (lastTime == 0L) { lastTime = time; return }
        val delta = (time - lastTime).toDouble() / 1_000_000_000.0
        if (delta < 0.0 || delta > 0.5) { reset(); lastTime = time; return }
        lastTime = time
        clock += delta.toFloat()
        if (lidsAvailable) updateBlink()
        if (eyesAvailable || headAvailable) updateGaze()
        if (headAvailable) updateHead()
    }

    private fun updateBlink() {
        if (blinkStart < 0f && clock >= nextBlink) {
            blinkStart = clock
            closing = between(0.08f, 0.12f); hold = between(0.015f, 0.035f); opening = between(0.13f, 0.20f)
        }
        var closure = 0f
        if (blinkStart >= 0f) {
            val elapsed = clock - blinkStart
            closure = when {
                elapsed < closing -> smooth(elapsed / closing)
                elapsed < closing + hold -> 1f
                elapsed < closing + hold + opening -> 1f - smooth((elapsed - closing - hold) / opening)
                else -> {
                    blinkStart = -1f
                    val doubleBlink = !isSecondBlink && random.nextFloat() < 0.12f
                    isSecondBlink = doubleBlink
                    nextBlink = clock + if (doubleBlink) between(0.14f, 0.28f) else between(2.5f, 6.5f)
                    0f
                }
            }
        }
        val pose = FacialCalibration.blinkPose(closure)
        upper.forEach { apply(it, pose.upperXDegrees, 0f) }
        lower.forEach { apply(it, pose.lowerXDegrees, 0f) }
    }

    private fun updateGaze() {
        if (gazeStart < 0f && clock >= nextGaze) {
            fromRight = currentRight; fromUp = currentUp
            if (random.nextFloat() < 0.45f) { targetRight = 0f; targetUp = 0f }
            else { targetRight = between(-30f, 20f); targetUp = between(-20f, 20f) }
            gazeDuration = between(0.14f, 0.28f); gazeStart = clock
            if (headAvailable) scheduleHead()
        }
        if (gazeStart >= 0f) {
            val t = ((clock - gazeStart) / gazeDuration).coerceIn(0f, 1f)
            val amount = smooth(t)
            currentRight = fromRight + (targetRight - fromRight) * amount
            currentUp = fromUp + (targetUp - fromUp) * amount
            if (t >= 1f) { gazeStart = -1f; nextGaze = clock + between(1.5f, 4.5f) }
        }
        if (eyesAvailable) {
            val pose = FacialCalibration.gazePose(currentRight, currentUp)
            eyes.forEach { apply(it, pose.xDegrees, pose.yDegrees) }
        }
    }

    private fun scheduleHead() {
        headCurrent.copyInto(headFrom)
        if (targetRight == 0f && targetUp == 0f) { headTarget.fill(0f) }
        else {
            val larger = random.nextFloat() < 0.12f
            val follow = if (larger) between(0.55f, 0.8f) else between(0.15f, 0.35f)
            val tilt = if (larger) 8f else 3f
            // Local X: negative left tilt. Y: negative up. Z: negative right turn.
            headTarget[0] = between(-tilt, tilt).coerceIn(-30f, 30f)
            headTarget[1] = (-targetUp * follow).coerceIn(-30f, 30f)
            headTarget[2] = (-targetRight * follow).coerceIn(-30f, 30f)
        }
        headStart = clock + between(0.18f, 0.35f)
        headDuration = between(0.55f, 1.2f)
    }

    private fun updateHead() {
        if (headStart >= 0f && clock >= headStart) {
            val t = ((clock - headStart) / headDuration).coerceIn(0f, 1f)
            val amount = smooth(t)
            for (i in 0..2) headCurrent[i] = (headFrom[i] + (headTarget[i] - headFrom[i]) * amount).coerceIn(-30f, 30f)
            if (t >= 1f) headStart = -1f
        }
        apply(head, headCurrent[0], headCurrent[1], headCurrent[2])
    }

    private fun apply(name: String, xDegrees: Float, yDegrees: Float, zDegrees: Float = 0f) {
        val joint = joints[name] ?: return
        Matrix.setIdentityM(rotation, 0)
        Matrix.rotateM(rotation, 0, xDegrees, 1f, 0f, 0f)
        Matrix.rotateM(rotation, 0, yDegrees, 0f, 1f, 0f)
        Matrix.rotateM(rotation, 0, zDegrees, 0f, 0f, 1f)
        Matrix.multiplyMM(result, 0, joint.base, 0, rotation, 0)
        viewer.engine.transformManager.setTransform(joint.instance, result)
    }
}
