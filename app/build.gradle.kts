import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// РџРѕРґРїРёСЃСЊ СЂРµР»РёР·Р° (Р­С‚Р°Рї 7): РїР°СЂР°РјРµС‚СЂС‹ РІ keystore.properties СЂСЏРґРѕРј СЃ settings.gradle.kts.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.notessync.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.notessync.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 83
        versionName = "1.10.60"
        resourceConfigurations += listOf("ru", "en")
    }

    signingConfigs {
        create("release") {
            storeFile = keystoreProperties.getProperty("storeFile")?.let { rootProject.file(it) }
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Cloud releases are signed after compilation with a pinned certificate.
            // Without local credentials produce an unsigned APK, never an alternate key.
            if (keystoreProperties.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // Р•РґРёРЅР°СЏ РїРѕСЃС‚РѕСЏРЅРЅР°СЏ РїРѕРґРїРёСЃСЊ РґР»СЏ РІСЃРµС… РІС‹РґР°РІР°РµРјС‹С… СЃР±РѕСЂРѕРє: РѕР±РЅРѕРІР»РµРЅРёСЏ РЅРµ
            // РґРѕР»Р¶РЅС‹ РєРѕРЅС„Р»РёРєС‚РѕРІР°С‚СЊ РёР·-Р·Р° РІСЂРµРјРµРЅРЅРѕРіРѕ debug-РєР»СЋС‡Р° РїРµСЃРѕС‡РЅРёС†С‹.
            if (keystoreProperties.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Both UI languages are available offline, including installations from an App Bundle.
    bundle { language { enableSplit = false } }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Native Robolectric/Compose share process clocks; isolate classes without skipping assertions.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    forkEvery = 1L
    // The equivalent official Central endpoint avoids the repo1 HTTP429 seen in stage85 CI.
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
}

// Р­РєСЃРїРѕСЂС‚ СЃС…РµРјС‹ Room РІ app/schemas вЂ” РїРѕР·РІРѕР»СЏРµС‚ РѕС‚СЃР»РµР¶РёРІР°С‚СЊ РјРёРіСЂР°С†РёРё Р‘Р” РІ git.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment:1.8.5")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // QR-РєРѕРґ (Р­С‚Р°Рї 4): РіРµРЅРµСЂР°С†РёСЏ + СЃРєР°РЅРёСЂРѕРІР°РЅРёРµ РєР°РјРµСЂРѕР№.
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

