plugins {
    id("com.android.library")
    kotlin("android")
    id("com.vanniktech.maven.publish") version "0.34.0"
}

group = "com.zapiack"
version = "0.2.0"

android {
    namespace = "com.zapiack.push"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

}

dependencies {
    api(project(":core"))
    // Zapiack for Android brings every product: Realtime comes with push.
    api(project(":realtime"))
    // Calls: the phone's WebRTC engine (Google's libwebrtc, built for Android). The call protocol itself is Zapiack's own, in :realtime.
    api("io.github.webrtc-sdk:android:150.7871.04")
    // The app already has Firebase set up; this is the part of it the SDK talks to.
    api("com.google.firebase:firebase-messaging:24.1.0")
    implementation("androidx.core:core-ktx:1.15.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(kotlin("test"))
}

mavenPublishing {
    publishToMavenCentral()
    // Signed only when a signing key is supplied (see PUBLISHING.md), so building and testing locally needs none.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates("com.zapiack", "zapiack-android", version.toString())

    pom {
        name.set("Zapiack for Android")
        description.set("Zapiack for Android apps: push notifications, live messaging and voice and video calls in one library.")
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
