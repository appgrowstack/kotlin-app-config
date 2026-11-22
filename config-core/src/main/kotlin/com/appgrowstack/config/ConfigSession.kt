package com.appgrowstack.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime

/** The type a flag is declared with and read as. */
public enum class ConfigFlagType(public val wire: String) {
    BOOL("bool"), INT("int"), DOUBLE("double"), STRING("string"), JSON("json");

    public companion object {
        public fun of(wire: String): ConfigFlagType? = entries.firstOrNull { it.wire == wire }
    }
}

/** Where a value came from, in the order the rules are checked. */
public enum class ConfigValueSource(public val wire: String) {
    FALLBACK("fallback"), RULE("rule"), EXPERIMENT("experiment"),

    /** The person is in the product-wide holdout (R10): the experiment's first variant, not recorded. */
    GLOBAL_HOLDOUT("global_holdout"),
    DEFAULT("default"),
}

/** One read: the value, where it came from and, for an experiment, the variant's name. */
public data class ConfigRead(
    val key: String,
    val value: JsonElement,
    val source: ConfigValueSource,
    val variant: String?,
)

/**
 * An event for the app's analytics: `config_exposed` (key, experiment, variant, set_version, propensity, policy_version) or
 * `config_rejected` (key, reason, set_version). The SDK sends nothing itself.
 */
public data class ConfigEvent(val name: String, val params: JsonObject)

/**
 * One launch. The set is read once and does not change while the app runs, so a screen never sees a
 * value change under it. A set fetched later is used from the next launch.
 *
 * @param setText the set to use (as fetched or stored).
 * @param storedText the set to use when [setText] can't be used (broken or too new).
 * @param facts device values for the rules (`platform`, `app_version`, `lang`, … and the app's own).
 * @param person the person id the variants are assigned by.
 * @param internal the owner's or a tester's device: always the first variant.
 */
public class ConfigSession(
    setText: String?,
    storedText: String? = null,
    private val facts: (String) -> JsonPrimitive?,
    private val person: String,
    private val internal: Boolean,
    private val now: Instant,
    private val record: (ConfigEvent) -> Unit = {},
) {
    private val sent = HashSet<String>()
    private val exposed = HashSet<String>()
    private val parsed = HashMap<String, Result<ParsedFlag>>()
    private val set: LoadedSet?

    init {
        set = load(setText, storedText)
    }

    /** The version of the set in use (0 when there is none). */
    public val setVersion: Int get() = set?.version ?: 0

    private fun emit(event: ConfigEvent) {
        val p = event.params
        val id = if (event.name == EXPOSED) "e:${p.str("key")}" else "r:${p.str("key")}:${p.str("reason")}:${p["set_version"]}"
        if (!sent.add(id)) return
        record(event)
    }

    private fun rejected(key: String, reason: String, version: Int) = emit(
        ConfigEvent(REJECTED, buildJsonObject { put("key", key); put("reason", reason); put("set_version", version) }),
    )

    private fun load(setText: String?, storedText: String?): LoadedSet? {
        if (setText == null) return asSet(storedText?.let(::parseOrNull))
        val json = parseOrNull(setText)
        if (json !is JsonObject) {
            rejected(SET, "parse_error", 0)
            return asSet(storedText?.let(::parseOrNull))
        }
        val schemaVersion = json["schema_version"].number()
        if (schemaVersion != SCHEMA_VERSION.toDouble()) {
            val v = json["version"].number()
            rejected(SET, "schema_too_new", if (v != null && v == Math.floor(v)) v.toInt() else 0)
            return asSet(storedText?.let(::parseOrNull))
        }
        return asSet(json) ?: run {
            rejected(SET, "parse_error", 0)
            asSet(storedText?.let(::parseOrNull))
        }
    }

    private fun flag(key: String): Result<ParsedFlag>? {
        val s = set ?: return null
        val json = s.flags[key] ?: return null
        return parsed.getOrPut(key) { runCatching { ConfigRules.parseFlag(json) } }
    }

    /** Reads [key] as [type], with the app's [fallback] (design 4: fallback → rule → experiment → default). */
    @Synchronized
    public fun read(key: String, type: ConfigFlagType, fallback: JsonElement): ConfigRead {
        val result = flag(key) ?: return ConfigRead(key, fallback, ConfigValueSource.FALLBACK, null)
        val version = set!!.version
        val f = result.getOrNull()
        if (f == null) {
            rejected(key, "invalid", version)
            return ConfigRead(key, fallback, ConfigValueSource.FALLBACK, null)
        }
        if (f.type != type) {
            rejected(key, "type_mismatch", version)
            return ConfigRead(key, fallback, ConfigValueSource.FALLBACK, null)
        }
        for (rule in f.rules) {
            if (ConfigRules.matches(rule.condition, facts)) return ConfigRead(key, rule.value, ConfigValueSource.RULE, null)
        }
        val e = f.experiment
        if (e != null && !now.isBefore(e.startsAt) && (e.endsAt == null || now.isBefore(e.endsAt))) {
            // The product-wide holdout (R10): the first variant (today's value), with no `config_exposed`.
            if (set.globalHoldout && ConfigExperiment.inGlobalHoldout(set.project, person, internal)) {
                return ConfigRead(key, e.variants[0].value, ConfigValueSource.GLOBAL_HOLDOUT, null)
            }
            val weights = e.variants.map { it.weight }
            val index = ConfigExperiment.variant(set.project, person, e.id, weights, internal).index
            val chosen = e.variants[index]
            if (exposed.add(key)) {
                val propensity = ConfigExperiment.propensity(weights, index, internal)
                emit(ConfigEvent(EXPOSED, buildJsonObject {
                    put("key", key); put("experiment", e.id); put("variant", chosen.name); put("set_version", version)
                    put("propensity", propensity); put("policy_version", version)
                }))
            }
            return ConfigRead(key, chosen.value, ConfigValueSource.EXPERIMENT, chosen.name)
        }
        return ConfigRead(key, f.default, ConfigValueSource.DEFAULT, null)
    }

    internal class LoadedSet(val project: String, val version: Int, val flags: JsonObject, val globalHoldout: Boolean)

    public companion object {
        public const val SCHEMA_VERSION: Int = 1
        public const val EXPOSED: String = "config_exposed"
        public const val REJECTED: String = "config_rejected"
        private const val SET = "_set"
        private val json = Json { isLenient = false }

        internal fun parseOrNull(text: String): JsonElement? = try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            null
        }

        /** A set usable as is: schema version 1, a project, a version and flags. */
        internal fun asSet(element: JsonElement?): LoadedSet? {
            if (element !is JsonObject) return null
            if (element["schema_version"].number() != SCHEMA_VERSION.toDouble()) return null
            val project = (element["project"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val version = element["version"].number() ?: return null
            val flags = element["flags"] as? JsonObject ?: return null
            return LoadedSet(project, version.toInt(), flags, globalHoldout = element["global_holdout"].bool() == true)
        }

        /** True when [text] is a set this SDK can use (so a fetched set is worth keeping). */
        public fun isUsable(text: String): Boolean = asSet(parseOrNull(text)) != null
    }
}

private fun JsonObject.str(name: String): String = (this[name] as? JsonPrimitive)?.content ?: ""

/** A JSON number (not a string, boolean or null) as a double. */
internal fun JsonElement?.number(): Double? {
    val p = this as? JsonPrimitive ?: return null
    if (p.isString || p is JsonNull) return null
    if (p.content == "true" || p.content == "false") return null
    return p.content.toDoubleOrNull()?.takeIf { it.isFinite() }
}

internal fun JsonElement?.bool(): Boolean? {
    val p = this as? JsonPrimitive ?: return null
    if (p.isString || p is JsonNull) return null
    return when (p.content) { "true" -> true; "false" -> false; else -> null }
}

internal fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun parseTime(text: String): Instant? = try {
    OffsetDateTime.parse(text).toInstant()
} catch (_: Exception) {
    null
}

internal fun isJsonValue(e: JsonElement): Boolean = e is JsonObject || e is JsonArray
