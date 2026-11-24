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
    // Android ships org.json; it is only needed here to compile and to run the tests on a plain JVM.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

mavenPublishing {
    // Uploads to Maven Central through the Central Portal. The upload is left for a person to release from the portal.
    publishToMavenCentral()
    // Signed only when a signing key is supplied (see PUBLISHING.md), so building and testing locally needs none.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates("com.zapiack", "zapiack-core", version.toString())

    pom {
        name.set("Zapiack core")
        description.set("The platform-independent core of Zapiack for Android.")
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
