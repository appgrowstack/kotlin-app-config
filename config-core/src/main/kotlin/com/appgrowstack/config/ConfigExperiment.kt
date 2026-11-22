package com.appgrowstack.config

/** The variant a person got: `unit` (for reading only) and the variant's index (from 0). */
public data class ConfigVariantResult(val unit: Double, val index: Int)

/**
 * The experiment math, the same as the server and the Swift SDK (schema/cases/experiment).
 * `hash32` is FNV-1a over UTF-16 code units with the murmur3 finalizer.
 */
public object ConfigExperiment {
    private const val TWO_32 = 0x1_0000_0000L

    public fun hash32(input: String): Long {
        var h = 0x811c9dc5.toInt()
        for (c in input) {
            h = h xor c.code
            h *= 0x01000193
        }
        h = h xor (h ushr 16)
        h *= 0x85ebca6b.toInt()
        h = h xor (h ushr 13)
        h *= 0xc2b2ae35.toInt()
        h = h xor (h ushr 16)
        return h.toLong() and 0xFFFF_FFFFL
    }

    public fun variantKey(project: String, person: String, experiment: String): String = "$project:$person:$experiment:variant"

    /** The share of people in the product-wide holdout (R10). */
    public const val GLOBAL_HOLDOUT: Double = 0.05

    /**
     * Whether the person is in the product-wide holdout (R10):
     * `hash32("<project>:<person>:global_holdout") / 2^32 < 0.05`. The owner's device never is.
     */
    public fun inGlobalHoldout(project: String, person: String, internal: Boolean): Boolean =
        !internal && hash32("$project:$person:global_holdout").toDouble() / TWO_32 < GLOBAL_HOLDOUT

    /**
     * The chance of the assigned variant: its weight over the sum (the owner's device: 1 for the first).
     * Rounded to 6 places, as the shared cases and the server compare.
     */
    public fun propensity(weights: List<Int>, index: Int, internal: Boolean): Double {
        if (internal) return if (index == 0) 1.0 else 0.0
        val total = weights.sum()
        if (total <= 0 || index !in weights.indices) return 0.0
        return Math.round(weights[index].toDouble() / total * 1_000_000) / 1_000_000.0
    }

    /**
     * The first variant `i` with `h × (sum of weights) < (weights up to i) × 2^32`, compared as whole
     * numbers. The owner's and testers' devices always get the first variant.
     */
    public fun variant(project: String, person: String, experiment: String, weights: List<Int>, internal: Boolean): ConfigVariantResult {
        require(weights.size in 2..4 && weights.all { it in 1..1000 }) { "weights: 2 to 4 whole numbers from 1 to 1000" }
        val h = hash32(variantKey(project, person, experiment))
        val unit = h.toDouble() / TWO_32
        if (internal) return ConfigVariantResult(unit, 0)
        val total = weights.sum().toLong()
        var cum = 0L
        for ((i, w) in weights.withIndex()) {
            cum += w
            if (h * total < cum * TWO_32) return ConfigVariantResult(unit, i)
        }
        return ConfigVariantResult(unit, weights.size - 1)
    }
}
