[English](./README.md) | 日本語

# kotlin-app-config

Androidのアプリの値を、新しいビルドを出さずに変える。型の付いたフラグに、端末の値の規則と、人を重みの付いた案に分ける実験を書く。組は全員に同じで、値は端末が決めるので、人の値は端末から出ない。

[swift-app-config](https://github.com/appgrowstack/swift-app-config)のKotlinの側。どちらも同じ組を読み、同じ判定の表を通すので、同じ人はiOSでもAndroidでも同じ案になる。

![Kotlin](https://img.shields.io/badge/Kotlin-2.4-purple.svg)
![JVM](https://img.shields.io/badge/JVM-17-blue.svg)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)

> 開発は非公開のモノリポでしていて、ここへは片方向にコピーしている。Issueは受ける。PRは受けない。

## できること

- **型つきで読み、無ければアプリの既定。** `bool`・`int`・`double`・`string`・`json`。keyが無い・フラグが読めない・型が違うときはアプリの既定の値を返す。後の2つは`config_rejected`を1回記録する
- **端末の値の規則。** `eq`・`ne`・`in`・`not_in`・`gte`・`lte`・`exists`と`all`・`any`を、`platform`・`app_version`・`lang`・`region`・`days_since_first_seen`とアプリの値に当てる。上から最初に当たった規則を使う
- **実験。** 重みの付いた2〜4つの案を`hash32("<project>:<person>:<id>:variant")`で整数のまま割り当てる。サーバーとSwiftのSDKと同じ式。最初の案が比べる元で、本人の端末はいつも最初の案になる。`config_exposed`は起動ごとにkeyの最初の1回だけ
- **起動の間は変わらない。** 組は起動の最初に読む。後で取れた組は次の起動から使う
- **単体で動く。** `BundledConfigSetSource`がアプリに同梱した組を読む。`ConfigSetSource`を差し替えればサーバーから取れる
- **Androidに頼らない。** `config-core`はKotlin/JVM（coroutinesとkotlinx.serialization）

## はじめに

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

## 入れ方

[JitPack](https://jitpack.io/#appgrowstack/kotlin-app-config)から入れる。

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { maven("https://jitpack.io") } }
// build.gradle.kts
dependencies {
    implementation("com.github.appgrowstack.kotlin-app-config:config-core:0.1.0")
    testImplementation("com.github.appgrowstack.kotlin-app-config:config-testing:0.1.0")
}
```

| モジュール | 中身 | 頼る物 |
|---|---|---|
| `config-core` | `AppConfig`・`ConfigSession`・規則と実験の式・保存と取得の口・同梱の組 | kotlinx-coroutines・kotlinx-serialization-json |
| `config-testing` | `FakeConfigSetSource`・`ConfigEventRecorder` | `config-core` |

## 動く環境

- JVM 17+（Androidは`java.time`のためにminSdk 26か、core library desugaring）
- Kotlin 2.4+

## ライセンス

MIT（[LICENSE](LICENSE)）。
