plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish") version "0.34.0"
}

group = "com.zapiack"
version = "0.2.0"

// Built with JDK 17 but for Java 11 bytecode: it runs on older JVMs and Android toolchains, and Java 17's `sealed` marker in a class file
// is not understood by the Javadoc generator the Maven Central upload needs.
kotlin {
    jvmToolchain(17)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    // The WebSocket. Apps nearly always have OkHttp already; this is the one library Realtime needs.
    api("com.squareup.okhttp3:okhttp:5.1.0")
    // Calls: the steps of a negotiation are suspend functions.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    // Android ships org.json; it is only needed here to compile and to run the tests on a plain JVM.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    // The plain-JVM org.json forgets the order of an object's keys (Android's keeps it); the golden file is read with one that does not.
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates("com.zapiack", "zapiack-realtime", version.toString())

    pom {
        name.set("Zapiack Realtime")
        description.set("Live messages over one WebSocket for Android and the JVM: channels, presence, signals, resume after a dropped connection; and the call protocol client that the Zapiack Android library builds voice and video on.")
        url.set("https://github.com/zapiack-hq/zapiack-android")
        licenses { license { name.set("MIT License"); url.set("https://opensource.org/licenses/MIT") } }
        developers { developer { id.set("zapiack"); name.set("Zapiack"); email.set("support@zapiack.com") } }
        scm {
            url.set("https://github.com/zapiack-hq/zapiack-android")
            connection.set("scm:git:https://github.com/zapiack-hq/zapiack-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/zapiack-hq/zapiack-android.git")
        }
    }
}
