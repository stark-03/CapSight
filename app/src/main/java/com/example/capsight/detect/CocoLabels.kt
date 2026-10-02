package com.example.capsight.detect

/** COCO class names in YOLOv8 output order, taken from the model's own names table. */
object CocoLabels {
    val NAMES: List<String> = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
        "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
        "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush",
    )

    const val PERSON = 0

    /**
     * Obstacle classes from reference/blenav/detector.py (OBSTACLE_CLASS_IDS), CLAUDE.md §7.3.
     * Outdoor classes (car, bus, dog, ...) are an open decision - they need size priors first.
     */
    val OBSTACLES: Set<Int> = setOf(1, 13, 24, 26, 28, 39, 41, 56, 57, 58, 59, 60, 62, 63, 66, 73, 75)

    /** Everything the navigation pipeline uses: person plus the obstacles. */
    val NAVIGATION: Set<Int> = OBSTACLES + PERSON

    fun name(classId: Int): String = NAMES.getOrElse(classId) { "class $classId" }
}
