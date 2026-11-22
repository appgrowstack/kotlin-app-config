# kotlin-app-config

Change values in your Android app without shipping a new build: typed flags with rules on device
values and experiments that split people into weighted variants. The set is the same for everyone
and the device picks the value, so no personal data leaves the device.

The Kotlin side of [swift-app-config](https://github.com/appgrowstack/swift-app-config). Both read
the same sets and pass the same decision tables, so a person gets the same variant on iOS and
Android.

![Kotlin](https://img.shields.io/badge/Kotlin-2.4-purple.svg)
![JVM](https://img.shields.io/badge/JVM-17-blue.svg)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)

> Developed in a private monorepo and copied here one way. Issues are welcome; pull requests are
> not accepted.

## Features

- **Typed reads with your default.** `bool`, `int`, `double`, `string` and `json`. A missing key, a
  broken flag or a different type gives your default; the last two are reported once as
  `config_rejected`
- **Rules on device values.** `eq`, `ne`, `in`, `not_in`, `gte`, `lte`, `exists` with `all` / `any`,
  on `platform`, `lang`, `region`, `days_since_first_seen` and your own values. The first rule that
  matches wins
- **Versions resolved for you.** A rule or an experiment states what it `requires` (feature keys),
  not version ranges. Core resolves it per OS into `app_version`: `{ "min": "2.4.0", "exclude": ["2.5.0"] }`.
  The device only checks its `platform` and `app_version` against that range: a rule outside it is
  skipped, and an experiment outside it gives the default value with no `config_exposed`. A rule
  needs `when`, `app_version` or both
- **Experiments.** 2 to 4 weighted variants, assigned by `hash32("<project>:<person>:<id>:variant")`
  in whole numbers, the same as the server and the Swift SDK. The first variant is the baseline and
  your own devices always get it. `config_exposed` (with `propensity`, the chance of the variant, and
  `policy_version`, the set's version) is sent once per key per launch. When the set has
  `global_holdout: true`, a person with `hash32("<project>:<person>:global_holdout") / 2³² < 0.05`
  gets the first variant (source `GLOBAL_HOLDOUT`) and sends no `config_exposed`
- **Stable while the app runs.** The set is read at launch; a set fetched later is used from the next
  launch
- **Works on its own.** `BundledConfigSetSource` reads the set shipped in the app. Any
  `ConfigSetSource` can fetch it from a server instead
- **No Android dependency.** `config-core` is Kotlin/JVM (coroutines and kotlinx.serialization)

## Quick Start

```kotlin
val config = AppConfig(
    source = BundledConfigSetSource { assets.open("config.json").bufferedReader().use { it.readText() } },
    storage = FileConfigStorage(context.filesDir),
    bundled = { assets.open("config.json").bufferedReader().use { it.readText() } },
    person = { installId },
    facts = { mapOf("platform" to JsonPrimitive("android"), "lang" to JsonPrimitive(lang), "is_premium" to JsonPrimitive(premium)) },
    isInternalDevice = { BuildConfig.DEBUG },
    record = { event -> analytics.track(event.name, event.params) },
)

lifecycleScope.launch {
    config.start()      // the set for this launch
    config.refresh()    // a newer set, used from the next launch
}

val hard = config.bool("paywall_hard", default = false)
val headline = config.string("paywall_headline", default = getString(R.string.paywall_headline))
```

## Installation

From [JitPack](https://jitpack.io/#appgrowstack/kotlin-app-config):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { maven("https://jitpack.io") } }
// build.gradle.kts
dependencies {
    implementation("com.github.appgrowstack.kotlin-app-config:config-core:0.2.0")
    testImplementation("com.github.appgrowstack.kotlin-app-config:config-testing:0.2.0")
}
```

| Module | Contents | Depends on |
|---|---|---|
| `config-core` | `AppConfig`, `ConfigSession`, rules and experiment math, storage and fetch ports, the bundled source | kotlinx-coroutines, kotlinx-serialization-json |
| `config-testing` | `FakeConfigSetSource`, `ConfigEventRecorder` | `config-core` |

## Requirements

- JVM 17+ (Android: minSdk 26 for `java.time`, or core library desugaring)
- Kotlin 2.4+

## License

MIT — see [LICENSE](LICENSE).
