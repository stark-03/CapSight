package com.example.capsight.detect

/**
 * Classes of the separate hazard detector (ml/hazards/, FR-DET-02/04). Their class ids are shifted
 * by [ID_OFFSET] so they never collide with COCO ids (COCO person is 0, hazard door would be too).
 */
object HazardLabels {
    const val MODEL_ASSET = "hazards_320_fp32.tflite"
    const val ID_OFFSET = 100

    val NAMES: List<String> = listOf("door", "stairs", "pole", "sign", "pothole")

    /** Stairs go to the P1 "Caution. Stairs ahead." rule, not the obstacle rules. */
    const val STAIRS = "stairs"

    fun isHazard(classId: Int) = classId >= ID_OFFSET
}
