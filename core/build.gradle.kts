plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "com.zapiack"
version = "0.1.0"

kotlin { jvmToolchain(17) }

dependencies {
    // Android ships org.json; it is only needed here to compile and to run the tests on a plain JVM.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

java { withSourcesJar() }

publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
            artifactId = "zapiack-core"
                pom {
                    name.set("Zapiack core")
                    description.set("The platform-independent core of Zapiack for Android.")
                    url.set("https://github.com/zapiack-hq/zapiack-android")
                    scm { url.set("https://github.com/zapiack-hq/zapiack-android"); connection.set("scm:git:https://github.com/zapiack-hq/zapiack-android.git") }
                    licenses { license { name.set("MIT License"); url.set("https://opensource.org/licenses/MIT") } }
                    developers { developer { name.set("Zapiack"); email.set("support@zapiack.com") } }
                }
        }
    }
}
