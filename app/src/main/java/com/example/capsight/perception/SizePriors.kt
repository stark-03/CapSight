package com.example.capsight.perception

/**
 * Real-world object sizes, copied exactly from reference/blenav/egocentric.py (CLAUDE.md §7.3),
 * plus the outdoor classes added on 7 Oct 2026.
 *
 * These are size *priors*: a real chair is 0.75-1.05 m tall, so every distance derived from
 * them carries roughly 25% error. That is the price of monocular distance without a depth sensor.
 */
object SizePriors {
    /** Typical heights in metres. */
    val HEIGHTS_M: Map<String, Double> = mapOf(
        "person" to 1.70,
        "bicycle" to 1.05,
        "bench" to 0.90,
        "backpack" to 0.50,
        "handbag" to 0.30,
        "suitcase" to 0.70,
        "bottle" to 0.25,
        "cup" to 0.12,
        "chair" to 0.90,
        "couch" to 0.85,
        "potted plant" to 0.60,
        "bed" to 0.60,
        "dining table" to 0.75,
        "tv" to 0.60,
        "laptop" to 0.25,
        "keyboard" to 0.03,
        "book" to 0.05,
        "vase" to 0.30,
        // Outdoor classes - not from Phase 2 (user decision 7 Oct 2026).
        "car" to 1.50,
        "motorcycle" to 1.10,
        "bus" to 3.00,
        "truck" to 3.00,
        "dog" to 0.55,
    )

    /** Typical widths, used when the height cannot be trusted (truncated boxes). */
    val WIDTHS_M: Map<String, Double> = mapOf(
        "person" to 0.45, // shoulders
        "bicycle" to 1.60, // seen side-on
        "bench" to 1.50,
        "backpack" to 0.35,
        "handbag" to 0.35,
        "suitcase" to 0.45,
        "bottle" to 0.08,
        "cup" to 0.09,
        "chair" to 0.50,
        "couch" to 1.90,
        "potted plant" to 0.40,
        "bed" to 1.50,
        "dining table" to 1.40,
        "tv" to 1.00,
        "laptop" to 0.35,
        "keyboard" to 0.45,
        "book" to 0.15,
        "vase" to 0.20,
        // Outdoor widths are FRONT/BACK views. Seen side-on these vehicles and dogs look wider, so
        // the width-based estimate comes out nearer than the truth - the safe direction of error.
        "car" to 1.80,
        "motorcycle" to 0.80,
        "bus" to 2.50,
        "truck" to 2.50,
        "dog" to 0.30,
    )

    /**
     * Flat desk objects (FR-OBS-03): a keyboard is 3 cm tall, so two pixels of box error swing
     * its distance by metres, and they sit on desks, not in the walking corridor.
     */
    val UNRELIABLE_SMALL: Set<String> = setOf("keyboard", "book", "cup", "laptop")

    /**
     * How much wider than expected a box must look before its height is distrusted. Loose
     * enough for odd poses, tight enough to catch a person whose legs are hidden by a desk.
     */
    const val TRUNCATION_ASPECT_FACTOR = 1.6

    /** How close to the frame edge a box must come to count as cut off. */
    const val EDGE_PX = 2.0
}
