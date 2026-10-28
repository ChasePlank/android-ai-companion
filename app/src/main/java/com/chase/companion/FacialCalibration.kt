package com.chase.companion

/** Phone-tested offsets in degrees relative to original local bone transforms.
 * Pure calibration math; not yet wired into the preview or floating overlay.
 */
object FacialCalibration {
    const val JAW = "Jaw_jnt_07"
    const val LEFT_UPPER_LID = "L_UpperEyelid_jnt_010"
    const val RIGHT_UPPER_LID = "R_UpperEyelid_jnt_014"
    const val LEFT_LOWER_LID = "L_LowerEyelid_jnt_011"
    const val RIGHT_LOWER_LID = "R_LowerEyelid_jnt_015"
    const val LEFT_EYE = "L_Eye_jnt_09"
    const val RIGHT_EYE = "R_Eye_jnt_013"
    const val JAW_AXIS = 1
    const val EYELID_AXIS = 0
    const val JAW_CLOSED = -8f
    const val JAW_OPEN = 20f
    const val UPPER_LID_CLOSED = 50f
    const val LOWER_LID_HALF = -10f
    const val LOWER_LID_CLOSED = -50f

    data class BlinkPose(val upperXDegrees: Float, val lowerXDegrees: Float)
    data class GazePose(val xDegrees: Float, val yDegrees: Float)

    private fun unit(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    fun jawDegrees(openness: Float): Float =
        JAW_CLOSED + (JAW_OPEN - JAW_CLOSED) * unit(openness)

    /** One shared amount drives both sides. Lower lids do less early work. */
    fun blinkPose(closure: Float): BlinkPose {
        val amount = unit(closure)
        val lower = if (amount <= 0.5f) {
            LOWER_LID_HALF * (amount * 2f)
        } else {
            LOWER_LID_HALF + (LOWER_LID_CLOSED - LOWER_LID_HALF) * ((amount - 0.5f) * 2f)
        }
        return BlinkPose(UPPER_LID_CLOSED * amount, lower)
    }

    /** Avatar perspective: positive right maps to negative Y; positive up to negative X.
     * Both eyes receive this same pose. Angular limits must be calibrated separately.
     */
    fun gazePose(rightDegrees: Float, upDegrees: Float): GazePose {
        val right = if (rightDegrees.isFinite()) rightDegrees else 0f
        val up = if (upDegrees.isFinite()) upDegrees else 0f
        return GazePose(-up, -right)
    }
}
