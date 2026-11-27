plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val bundledMapAssets = layout.buildDirectory.dir("generated/offlineMapAssets")
val mapArchive = rootProject.layout.projectDirectory.file("delivery/UAE-map.zip")
val stageOfflineMap by tasks.registering(Sync::class) {
    inputs.file(mapArchive)
    from(mapArchive)
    into(bundledMapAssets)
}

android {
    namespace = "ae.fieldguide"
    compileSdk = 35
    defaultConfig {
        applicationId = "ae.fieldguide.personal"
        minSdk = 30
        targetSdk = 35
        versionCode = 28
        versionName = "1.24-beta.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    sourceSets["main"].assets.srcDir(bundledMapAssets)
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        jniLibs.useLegacyPackaging = true // ggml selects a CPU backend from Android's native library directory.
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF","META-INF/DEPENDENCIES","META-INF/LICENSE.md","META-INF/NOTICE.md")
    }
}
tasks.named("preBuild") { dependsOn(stageOfflineMap) }

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("com.caverock:androidsvg:1.4")
    implementation("org.mapsforge:mapsforge-map-android:0.25.0")
    implementation("org.mapsforge:mapsforge-map-reader:0.25.0")
    implementation("org.mapsforge:mapsforge-themes:0.25.0")
}
