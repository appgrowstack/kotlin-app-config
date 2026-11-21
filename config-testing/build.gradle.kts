import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// アプリのテストで使う偽の物（取得の口・記録の受け手）
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

dependencies {
    api(project(":config-core"))
}

publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
            artifactId = "config-testing"
        }
    }
}
