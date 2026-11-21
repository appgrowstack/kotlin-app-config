package com.appgrowstack.config

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppConfigTest {
    private val clock = Clock.fixed(Instant.parse("2026-12-10T03:00:00Z"), ZoneOffset.UTC)
    private val facts = mapOf("platform" to JsonPrimitive("android"), "lang" to JsonPrimitive("ja"))

    private fun set(version: Int, headline: String) = """
        {"schema_version":1,"project":"reading-memory","version":$version,"published_at":1,"flags":{
          "paywall_headline":{"type":"string","default":"$headline","rules":[{"when":{"fact":"lang","eq":"en"},"value":"Keep every book"}]},
          "max_free_books":{"type":"int","default":5},
          "paywall_bullets":{"type":"json","default":["a","b"]}
        }}
    """.trimIndent()

    @Test
    fun versionMatchesGradleProperties() {
        val properties = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "gradle.properties") }
            .first { it.isFile && it.readText().contains(Regex("(?m)^version=")) }
        val version = Regex("(?m)^version=(.+)$").find(properties.readText())!!.groupValues[1].trim()
        assertEquals(version, ConfigSdk.VERSION)
        assertEquals("kotlin-app-config", ConfigSdk.NAME)
    }

    @Test
    fun readsGiveTheAppDefaultUntilStart() {
        val config = AppConfig(source = BundledConfigSetSource { set(1, "x") }, person = { "p" }, facts = { facts })
        assertEquals("app", config.string("paywall_headline", "app"))
        assertEquals(0, config.setVersion)
    }

    @Test
    fun theBundledSetIsUsedFirstAndAFetchedSetFromTheNextLaunch() = runTest {
        val storage = InMemoryConfigStorage()
        val events = mutableListOf<ConfigEvent>()
        val source = ConfigSetSource { etag -> if (etag == "\"v2\"") ConfigFetchResult.NotModified else ConfigFetchResult.Fetched(set(2, "new"), "\"v2\"") }
        val launch1 = AppConfig(source, storage, bundled = { set(1, "bundled") }, person = { "p" }, facts = { facts }, clock = clock, record = { events += it })
        launch1.start()
        assertEquals("bundled", launch1.string("paywall_headline", "app"))
        assertTrue(launch1.refresh())
        // The set does not change while the app runs
        assertEquals("bundled", launch1.string("paywall_headline", "app"))
        assertFalse(launch1.refresh())

        val launch2 = AppConfig(source, storage, bundled = { set(1, "bundled") }, person = { "p" }, facts = { facts }, clock = clock)
        launch2.start()
        assertEquals(2, launch2.setVersion)
        assertEquals("new", launch2.string("paywall_headline", "app"))
        assertEquals(5L, launch2.int("max_free_books", 1))
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), launch2.json("paywall_bullets", JsonArray(emptyList())))
        // A different type is the app's default and is reported once
        assertEquals(true, launch1.bool("max_free_books", true))
        assertEquals(1, events.count { it.name == ConfigSession.REJECTED })
    }

    @Test
    fun aBrokenFetchedSetIsNotStored() = runTest {
        val storage = InMemoryConfigStorage(set(1, "kept") to "\"v1\"")
        val config = AppConfig(ConfigSetSource { ConfigFetchResult.Fetched("{not json", "\"v9\"") }, storage, person = { "p" }, facts = { facts })
        assertFalse(config.refresh())
        assertEquals("\"v1\"", storage.load()!!.second)
    }

    @Test
    fun theBundledSourceGivesItsTextOnce() = runTest {
        val bundled = BundledConfigSetSource { set(1, "x") }
        val first = bundled.fetch(null) as ConfigFetchResult.Fetched
        assertEquals(bundled.etag, first.etag)
        assertEquals(ConfigFetchResult.NotModified, bundled.fetch(bundled.etag))
    }
}
