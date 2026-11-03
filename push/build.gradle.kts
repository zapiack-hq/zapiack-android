plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}

group = "com.zapiack"
version = "0.1.0"

android {
    namespace = "com.zapiack.push"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    api(project(":core"))
    // The app already has Firebase set up; this is the part of it the SDK talks to.
    api("com.google.firebase:firebase-messaging:24.1.0")
    implementation("androidx.core:core-ktx:1.15.0")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "zapiack-android"
                pom {
                    name.set("Zapiack for Android")
                    description.set("Zapiack for Android apps: push notifications today, with more of Zapiack to come in the same library.")
                    url.set("https://github.com/zapiack-hq/zapiack-android")
                    scm { url.set("https://github.com/zapiack-hq/zapiack-android"); connection.set("scm:git:https://github.com/zapiack-hq/zapiack-android.git") }
                    licenses { license { name.set("MIT License"); url.set("https://opensource.org/licenses/MIT") } }
                    developers { developer { name.set("Zapiack"); email.set("support@zapiack.com") } }
                }
            }
        }
    }
}
