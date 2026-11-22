package com.appgrowstack.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/** A flag that could not be read (its shape, a value's type or a condition). */
public class ConfigInvalidFlag(message: String) : Exception(message)

internal class ParsedVariant(val name: String, val weight: Int, val value: JsonElement)
internal class ParsedExperiment(
    val id: String,
    val variants: List<ParsedVariant>,
    val startsAt: Instant,
    val endsAt: Instant?,
    val appVersion: Map<String, ConfigVersionRange>?,
)
internal class ParsedRule(val condition: ConfigCondition?, val appVersion: Map<String, ConfigVersionRange>?, val value: JsonElement)
internal class ParsedFlag(val type: ConfigFlagType, val default: JsonElement, val rules: List<ParsedRule>, val experiment: ParsedExperiment?)

/** A condition on device values: one comparison, or `all` / `any` of conditions (3 levels at most). */
internal sealed interface ConfigCondition {
    data class All(val children: List<ConfigCondition>) : ConfigCondition
    data class AnyOf(val children: List<ConfigCondition>) : ConfigCondition
    data class Leaf(val fact: String, val op: String, val value: JsonElement) : ConfigCondition
}

/** Reading flags and checking rules. The same answers as the TypeScript and Swift SDKs (schema/cases/config). */
public object ConfigRules {
    private const val MAX_DEPTH = 3
    private const val STRING_MAX = 2000
    private const val MAX_SAFE = 9_007_199_254_740_991.0
    private val NAME = Regex("^[a-z0-9_]{1,40}$")
    private val EXPERIMENT_ID = Regex("^[a-z0-9-]{3,40}$")
    private val OPERATORS = setOf("eq", "ne", "in", "not_in", "gte", "lte", "exists")
    private val FLAG_KEYS = setOf("type", "default", "rules", "experiment")

    /** The keys a rule may have. `requires` and `exclude_app_versions` are for core's resolving; the device does not read them. */
    private val RULE_KEYS = setOf("when", "value", "requires", "exclude_app_versions", "app_version")

    /** Whether [value] fits [type]. An int is a whole number (written `3` or `3.0`). */
    public fun fits(type: ConfigFlagType, value: JsonElement): Boolean = when (type) {
        ConfigFlagType.BOOL -> value.bool() != null
        ConfigFlagType.INT -> value.number()?.let { it == Math.floor(it) && Math.abs(it) <= MAX_SAFE } == true
        ConfigFlagType.DOUBLE -> value.number() != null
        ConfigFlagType.STRING -> value.string()?.let { it.length <= STRING_MAX } == true
        ConfigFlagType.JSON -> isJsonValue(value)
    }

    private fun invalid(): Nothing = throw ConfigInvalidFlag("invalid")

    internal fun parseFlag(json: JsonElement): ParsedFlag {
        if (json !is JsonObject) invalid()
        val type = json["type"].string()?.let(ConfigFlagType::of) ?: invalid()
        val default = json["default"] ?: invalid()
        if (!fits(type, default)) invalid()
        if (json.keys.any { it !in FLAG_KEYS }) invalid()
        val rules = mutableListOf<ParsedRule>()
        json["rules"]?.let { r ->
            if (r !is JsonArray || r.size > 30) invalid()
            for (rule in r) {
                if (rule !is JsonObject || rule.keys.any { it !in RULE_KEYS }) invalid()
                val value = rule["value"] ?: invalid()
                if (!fits(type, value)) invalid()
                if ("when" !in rule && "app_version" !in rule) invalid()
                val condition = rule["when"]?.let { parseCondition(it, 0) }
                rules += ParsedRule(condition, appVersionOf(rule), value)
            }
        }
        return ParsedFlag(type, default, rules, json["experiment"]?.let { parseExperiment(type, it) })
    }

    private fun parseExperiment(type: ConfigFlagType, json: JsonElement): ParsedExperiment {
        if (json !is JsonObject) invalid()
        val id = json["id"].string()?.takeIf { EXPERIMENT_ID.matches(it) } ?: invalid()
        val variants = json["variants"] as? JsonArray ?: invalid()
        if (variants.size !in 2..4) invalid()
        val parsed = variants.map { v ->
            if (v !is JsonObject) invalid()
            val name = v["name"].string()?.takeIf { NAME.matches(it) } ?: invalid()
            val w = v["weight"].number() ?: invalid()
            if (w != Math.floor(w) || w < 1 || w > 1000) invalid()
            val value = v["value"] ?: invalid()
            if (!fits(type, value)) invalid()
            ParsedVariant(name, w.toInt(), value)
        }
        if (parsed.map { it.name }.toSet().size != parsed.size) invalid()
        val metric = json["metric"] as? JsonObject ?: invalid()
        if (metric["event"].string() == null || metric["within_hours"].number() == null) invalid()
        val startsAt = json["starts_at"].string()?.let(::parseTime) ?: invalid()
        val end = json["ends_at"] ?: invalid()
        val endsAt = if (end is JsonNull) null else end.string()?.let(::parseTime) ?: invalid()
        return ParsedExperiment(id, parsed, startsAt, endsAt, appVersionOf(json))
    }

    /** A rule's or the experiment's `app_version` (resolved by core), or `null` when it has none (no filter by version). */
    private fun appVersionOf(item: JsonObject): Map<String, ConfigVersionRange>? {
        val json = item["app_version"] ?: return null
        return ConfigVersionRange.parseAll(json) ?: invalid()
    }

    /**
     * Whether the device's `platform` and `app_version` fall in [range]. No range fits everyone; a device
     * without those values, with a version that does not read, or on an OS the range lacks does not fit.
     */
    internal fun versionFits(range: Map<String, ConfigVersionRange>?, facts: (String) -> JsonPrimitive?): Boolean {
        if (range == null) return true
        val platform = facts("platform").string() ?: return false
        val text = facts("app_version").string() ?: return false
        val r = range[platform] ?: return false
        val version = ConfigVersion.parse(text) ?: return false
        return version in r
    }

    private fun isScalar(e: JsonElement): Boolean = e is JsonPrimitive && e !is JsonNull && (e.isString || e.bool() != null || e.number() != null)

    private fun kind(e: JsonElement): String = when {
        e is JsonPrimitive && e.isString -> "string"
        e.bool() != null -> "bool"
        else -> "number"
    }

    internal fun parseCondition(json: JsonElement, depth: Int): ConfigCondition {
        if (json !is JsonObject || json.isEmpty()) invalid()
        val only = json.keys.singleOrNull()
        if (only == "all" || only == "any") {
            val items = json.getValue(only) as? JsonArray ?: invalid()
            if (depth >= MAX_DEPTH || items.isEmpty()) invalid()
            val children = items.map { parseCondition(it, depth + 1) }
            return if (only == "all") ConfigCondition.All(children) else ConfigCondition.AnyOf(children)
        }
        val fact = json["fact"].string()
        val op = json.keys.firstOrNull { it != "fact" }
        if (json.size != 2 || fact == null || op == null) invalid()
        if (op !in OPERATORS) invalid()
        val value = json.getValue(op)
        when (op) {
            "eq", "ne" -> if (!isScalar(value)) invalid()
            "in", "not_in" -> {
                if (value !is JsonArray || value.size !in 1..50 || !value.all(::isScalar)) invalid()
                if (value.map(::kind).toSet().size != 1) invalid()
            }
            "gte", "lte" -> if (value.number() == null) invalid()
            else -> if (value.bool() == null) invalid()
        }
        return ConfigCondition.Leaf(fact, op, value)
    }

    /** `null` when the types differ (a comparison that never matches). */
    private fun equals(a: JsonElement, b: JsonElement): Boolean? {
        val ka = kind(a)
        if (ka != kind(b)) return null
        return when (ka) {
            "number" -> a.number() == b.number()
            "bool" -> a.bool() == b.bool()
            else -> a.string() == b.string()
        }
    }

    private fun leaf(op: String, want: JsonElement, value: JsonPrimitive?): Boolean {
        if (value == null || value is JsonNull) return op == "exists" && want.bool() == false
        return when (op) {
            "eq" -> equals(value, want) == true
            "ne" -> equals(value, want) == false
            "in", "not_in" -> {
                val list = want as JsonArray
                if (kind(list.first()) != kind(value)) return false
                val hit = list.any { equals(value, it) == true }
                if (op == "in") hit else !hit
            }
            "gte" -> value.number()?.let { it >= want.number()!! } == true
            "lte" -> value.number()?.let { it <= want.number()!! } == true
            else -> want.bool() == true
        }
    }

    internal fun matches(condition: ConfigCondition, facts: (String) -> JsonPrimitive?): Boolean = when (condition) {
        is ConfigCondition.All -> condition.children.all { matches(it, facts) }
        is ConfigCondition.AnyOf -> condition.children.any { matches(it, facts) }
        is ConfigCondition.Leaf -> leaf(condition.op, condition.value, facts(condition.fact))
    }
}
