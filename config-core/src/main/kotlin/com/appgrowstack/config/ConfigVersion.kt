package com.appgrowstack.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A version as three numbers. Missing parts count as zero (`3.10` is `3.10.0`). */
internal data class ConfigVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ConfigVersion> {
    override fun compareTo(other: ConfigVersion): Int =
        compareValuesBy(this, other, ConfigVersion::major, ConfigVersion::minor, ConfigVersion::patch)

    companion object {
        /** The device's version: one to four parts, the first three read as digits (a fourth is ignored). */
        fun parse(text: String): ConfigVersion? {
            val parts = text.split(".")
            if (parts.size !in 1..4) return null
            val numbers = parts.take(3).map { part ->
                if (part.isEmpty() || !part.all { it in '0'..'9' }) return null
                part.toIntOrNull() ?: return null
            }.toMutableList()
            while (numbers.size < 3) numbers.add(0)
            return ConfigVersion(numbers[0], numbers[1], numbers[2])
        }

        /** A version as a set writes it: two or three groups of digits (`3.3`, `3.3.0`). */
        fun parseAppVersion(text: String): ConfigVersion? =
            if (text.split(".").size in 2..3) parse(text) else null
    }
}

/** One OS's range resolved by core: [min] or later, and none of [exclude]. */
internal data class ConfigVersionRange(val min: ConfigVersion, val exclude: List<ConfigVersion>) {
    operator fun contains(version: ConfigVersion): Boolean = version >= min && exclude.none { it == version }

    companion object {
        /** `{ "<os>": { "min": …, "exclude": [ … ] } }`, or `null` when any part does not read. Other keys in a range are ignored. */
        fun parseAll(json: JsonElement): Map<String, ConfigVersionRange>? {
            if (json !is JsonObject) return null
            return json.mapValues { (_, value) -> parse(value) ?: return null }
        }

        private fun parse(json: JsonElement): ConfigVersionRange? {
            if (json !is JsonObject) return null
            val min = json["min"].string()?.let(ConfigVersion::parseAppVersion) ?: return null
            val items = json["exclude"] as? JsonArray ?: return null
            val exclude = items.map { it.string()?.let(ConfigVersion::parseAppVersion) ?: return null }
            return ConfigVersionRange(min, exclude)
        }
    }
}
