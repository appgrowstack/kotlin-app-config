package com.appgrowstack.config.testing

import com.appgrowstack.config.ConfigEvent
import com.appgrowstack.config.ConfigFetchResult
import com.appgrowstack.config.ConfigSetSource

/** Answers fetches in order (the last answer repeats) and keeps the ETags it was asked with. */
public class FakeConfigSetSource(vararg answers: ConfigFetchResult) : ConfigSetSource {
    private val queue = ArrayDeque(answers.toList())
    public val asked: MutableList<String?> = mutableListOf()

    override suspend fun fetch(etag: String?): ConfigFetchResult {
        asked += etag
        return if (queue.size > 1) queue.removeFirst() else queue.firstOrNull() ?: ConfigFetchResult.NotModified
    }
}

/** Keeps the events `AppConfig` sends, to check them in tests (`record = recorder::record`). */
public class ConfigEventRecorder {
    public val events: MutableList<ConfigEvent> = mutableListOf()

    public fun record(event: ConfigEvent) {
        events += event
    }

    public fun named(name: String): List<ConfigEvent> = events.filter { it.name == name }
}
