package com.appgrowstack.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.time.OffsetDateTime
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs every table in `schema/cases/config` and the variant vectors in `schema/cases/experiment`
 * (copied by `scripts/sync-cases.sh`). TypeScript and Swift read the same files.
 */
class SharedCasesTest {
    companion object {
        val directory: File = File(requireNotNull(SharedCasesTest::class.java.getResource("/cases")) { "run scripts/sync-cases.sh" }.toURI())
        val config: List<File> = File(directory, "config").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }

        fun read(file: File): JsonObject = Json.parseToJsonElement(file.readText()).jsonObject
    }

    /** Numbers as doubles and keys sorted, so `3` and `3.0` compare equal (the TS test compares parsed JSON). */
    private fun canonical(e: JsonElement): String = when (e) {
        is JsonObject -> e.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "\"${it.key}\":${canonical(it.value)}" }
        is JsonArray -> e.joinToString(",", "[", "]") { canonical(it) }
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            e.isString -> "\"${e.content}\""
            e.booleanOrNull != null -> e.content
            else -> e.content.toDouble().toString()
        }
    }

    @Test
    fun thereAreCases() {
        assertTrue(config.size > 15, "${config.size} cases")
    }

    @TestFactory
    fun configCases(): List<DynamicTest> = config.map { file ->
        DynamicTest.dynamicTest(file.nameWithoutExtension) { check(read(file)) }
    }

    private fun check(c: JsonObject) {
        val facts = c.getValue("facts").jsonObject.mapValues { it.value.jsonPrimitive }
        val records = mutableListOf<ConfigEvent>()
        val session = ConfigSession(
            setText = c["set"]?.toString() ?: c["set_raw"]?.jsonPrimitive?.content,
            storedText = c["stored_set"]?.toString(),
            facts = { facts[it] },
            person = c.getValue("person_id").jsonPrimitive.content,
            internal = c.getValue("internal").jsonPrimitive.content == "true",
            now = OffsetDateTime.parse(c.getValue("now").jsonPrimitive.content).toInstant(),
            record = { records += it },
        )
        val values = c.getValue("reads").jsonArray.map { r ->
            val read = r.jsonObject
            val got = session.read(read.getValue("key").jsonPrimitive.content, ConfigFlagType.of(read.getValue("type").jsonPrimitive.content)!!, read.getValue("fallback"))
            JsonObject(mapOf("key" to JsonPrimitive(got.key), "value" to got.value, "source" to JsonPrimitive(got.source.wire), "variant" to (got.variant?.let(::JsonPrimitive) ?: JsonNull)))
        }
        val expect = c.getValue("expect").jsonObject
        assertEquals(expect.getValue("values").jsonArray.map(::canonical), values.map(::canonical), "values")
        assertEquals(
            expect.getValue("records").jsonArray.map(::canonical).sorted(),
            records.map { canonical(JsonObject(mapOf("name" to JsonPrimitive(it.name), "params" to it.params))) }.sorted(),
            "records",
        )
    }

    @Test
    fun variantVectors() {
        val vectors = read(File(directory, "experiment/variant.json")).getValue("vectors").jsonArray
        assertTrue(vectors.isNotEmpty())
        for (v in vectors.map { it.jsonObject }) {
            val input = v.getValue("input").jsonObject
            val project = input.getValue("project").jsonPrimitive.content
            val person = input.getValue("person_id").jsonPrimitive.content
            val experiment = input.getValue("experiment").jsonPrimitive.content
            assertEquals(v.getValue("key").jsonPrimitive.content, ConfigExperiment.variantKey(project, person, experiment))
            assertEquals(v.getValue("hash32").jsonPrimitive.long, ConfigExperiment.hash32(v.getValue("key").jsonPrimitive.content))
            val got = ConfigExperiment.variant(
                project, person, experiment,
                input.getValue("weights").jsonArray.map { it.jsonPrimitive.content.toInt() },
                input.getValue("internal").jsonPrimitive.content == "true",
            )
            val expect = v.getValue("expect").jsonObject
            assertEquals(expect.getValue("index").jsonPrimitive.content.toInt(), got.index, v.toString())
            assertEquals(round6(expect.getValue("unit").jsonPrimitive.content.toDouble()), round6(got.unit), v.toString())
        }
    }

    private fun round6(x: Double) = (x * 1e6).roundToLong()
}
