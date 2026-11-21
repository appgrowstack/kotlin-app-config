package com.appgrowstack.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.security.MessageDigest
import java.time.Clock

// MARK: fetching

public sealed interface ConfigFetchResult {
    /** A set (the JSON text) and its ETag. */
    public data class Fetched(val text: String, val etag: String?) : ConfigFetchResult

    /** The set on hand is current (HTTP 304). */
    public data object NotModified : ConfigFetchResult
}

/** Where sets come from: the bundled set, or a connector that fetches them from a server. */
public fun interface ConfigSetSource {
    public suspend fun fetch(etag: String?): ConfigFetchResult
}

/** A fetch that got neither a set nor "not modified". */
public class ConfigSetFetchFailure(public val status: Int) : Exception("config set fetch failed: HTTP $status")

/** The set shipped in the app. The ETag is the text's hash, so the same text is given once. */
public class BundledConfigSetSource(private val read: () -> String) : ConfigSetSource {
    private val text: String by lazy(read)
    public val etag: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        "\"bundled-" + digest.take(8).joinToString("") { "%02x".format(it) } + "\""
    }

    override suspend fun fetch(etag: String?): ConfigFetchResult =
        if (etag == this.etag) ConfigFetchResult.NotModified else ConfigFetchResult.Fetched(text, this.etag)
}

// MARK: storage

/** The last set fetched and its ETag, kept between launches. */
public interface ConfigStorage {
    public suspend fun load(): Pair<String, String?>?
    public suspend fun save(text: String, etag: String?)
}

/** Files under `<directory>/config/`. Pass `context.filesDir`. */
public class FileConfigStorage(directory: File) : ConfigStorage {
    public val directory: File = File(directory, "config")

    override suspend fun load(): Pair<String, String?>? = withContext(Dispatchers.IO) {
        val set = File(directory, "set.json").takeIf { it.exists() }?.readText() ?: return@withContext null
        set to File(directory, "etag").takeIf { it.exists() }?.readText()
    }

    override suspend fun save(text: String, etag: String?): Unit = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val temp = File(directory, "set.json.tmp")
        temp.writeText(text)
        if (!temp.renameTo(File(directory, "set.json"))) {
            File(directory, "set.json").writeText(text)
            temp.delete()
        }
        if (etag == null) File(directory, "etag").delete() else File(directory, "etag").writeText(etag)
    }
}

/** In memory (tests and previews). */
public class InMemoryConfigStorage(private var stored: Pair<String, String?>? = null) : ConfigStorage {
    private val lock = Mutex()
    override suspend fun load(): Pair<String, String?>? = lock.withLock { stored }
    override suspend fun save(text: String, etag: String?): Unit = lock.withLock { stored = text to etag }
}

// MARK: the engine

/**
 * Typed flags from a set, fixed for the launch.
 *
 * Call [start] once at launch (it reads the stored set, or the bundled one), then [refresh] whenever
 * you like: a set fetched by [refresh] is stored and used from the next launch. Until [start] has
 * run, every read gives the app's default.
 *
 * @param source where new sets come from (the connector, or [BundledConfigSetSource]).
 * @param bundled the set shipped in the app, used when nothing usable is stored.
 * @param facts device values for the rules: `platform`, `app_version`, `build`, `channel`, `lang`,
 *   `region`, `days_since_first_seen` and the app's own (`config/manifest.json` `facts`).
 * @param record receives `config_exposed` and `config_rejected` for the app's analytics.
 */
public class AppConfig(
    private val source: ConfigSetSource,
    private val storage: ConfigStorage = InMemoryConfigStorage(),
    private val bundled: (() -> String)? = null,
    private val person: () -> String,
    private val facts: () -> Map<String, JsonPrimitive>,
    private val isInternalDevice: () -> Boolean = { false },
    private val clock: Clock = Clock.systemUTC(),
    private val record: (ConfigEvent) -> Unit = {},
) {
    @Volatile
    private var session: ConfigSession? = null

    /** Reads the set for this launch. Calling it again keeps the first one. */
    public suspend fun start() {
        if (session != null) return
        val stored = storage.load()?.first
        val bundledText = bundled?.invoke()
        val current = facts()
        session = ConfigSession(
            setText = stored ?: bundledText,
            storedText = if (stored != null) bundledText else null,
            facts = { current[it] },
            person = person(),
            internal = isInternalDevice(),
            now = clock.instant(),
            record = record,
        )
    }

    /** Fetches a new set and stores it for the next launch. True when a new set was stored. */
    public suspend fun refresh(): Boolean {
        val etag = storage.load()?.second
        return when (val result = source.fetch(etag)) {
            ConfigFetchResult.NotModified -> false
            is ConfigFetchResult.Fetched -> {
                // A broken or too new set is not stored: the set on hand stays for the next launch.
                if (!ConfigSession.isUsable(result.text)) return false
                storage.save(result.text, result.etag)
                true
            }
        }
    }

    /** The version of the set this launch uses (0 when there is none or [start] has not run). */
    public val setVersion: Int get() = session?.setVersion ?: 0

    public fun read(key: String, type: ConfigFlagType, default: JsonElement): ConfigRead =
        session?.read(key, type, default) ?: ConfigRead(key, default, ConfigValueSource.FALLBACK, null)

    public fun bool(key: String, default: Boolean): Boolean =
        (read(key, ConfigFlagType.BOOL, JsonPrimitive(default)).value as JsonPrimitive).content == "true"

    public fun int(key: String, default: Long): Long =
        (read(key, ConfigFlagType.INT, JsonPrimitive(default)).value as JsonPrimitive).double.toLong()

    public fun double(key: String, default: Double): Double =
        (read(key, ConfigFlagType.DOUBLE, JsonPrimitive(default)).value as JsonPrimitive).doubleOrNull ?: default

    public fun string(key: String, default: String): String =
        (read(key, ConfigFlagType.STRING, JsonPrimitive(default)).value as JsonPrimitive).content

    /** An object or array. Decode it with kotlinx.serialization (`Json.decodeFromJsonElement`). */
    public fun json(key: String, default: JsonElement): JsonElement = read(key, ConfigFlagType.JSON, default).value
}
