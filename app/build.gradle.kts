plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.terrasync.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.terrasync.rust"
        minSdk = 24
        targetSdk = 34
        val tagVer = (project.findProperty("appVersionName") as String?)
            ?: System.getenv("GITHUB_REF_NAME")?.removePrefix("v")?.takeIf { it.matches(Regex("\\d+\\.\\d+\\.\\d+.*")) }
            ?: "3.13.5"
        val baseNum = tagVer.substringBefore("-").split(".").mapNotNull { it.toIntOrNull() }
            .let { if (it.size >= 3) it[0] * 10000 + it[1] * 100 + it[2] else 20700 }
        val preOffset = if (!tagVer.contains("-")) {
            99
        } else {
            val pre = tagVer.substringAfter("-").lowercase()
            val tier = when {
                pre.startsWith("rc") -> 60
                pre.startsWith("beta") -> 30
                pre.startsWith("alpha") -> 10
                else -> 1
            }
            val num = Regex("\\d+").find(pre)?.value?.toIntOrNull()?.coerceIn(0, 29) ?: 0
            (tier + num).coerceIn(1, 98)
        }
        val computedCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull()
            ?: (baseNum * 100 + preOffset)
        versionCode = computedCode
        versionName = tagVer
    }

    val relStoreFile = (project.findProperty("RELEASE_STORE_FILE") as String?)
        ?: System.getenv("RELEASE_STORE_FILE")
    val relStorePassword = (project.findProperty("RELEASE_STORE_PASSWORD") as String?)
        ?: System.getenv("RELEASE_STORE_PASSWORD")
    val relKeyAlias = (project.findProperty("RELEASE_KEY_ALIAS") as String?)
        ?: System.getenv("RELEASE_KEY_ALIAS")
    val relKeyPassword = (project.findProperty("RELEASE_KEY_PASSWORD") as String?)
        ?: System.getenv("RELEASE_KEY_PASSWORD")
    val hasReleaseSigning = !relStoreFile.isNullOrBlank() &&
        !relStorePassword.isNullOrBlank() &&
        !relKeyAlias.isNullOrBlank() &&
        !relKeyPassword.isNullOrBlank() &&
        file(relStoreFile).exists()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(relStoreFile!!)
                storeType = "PKCS12"
                storePassword = relStorePassword
                keyAlias = relKeyAlias
                keyPassword = relKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = (project.findProperty("unitTestMaxHeap") as String?) ?: "512m"
                it.testLogging.showStandardStreams = true
                it.testLogging.events(
                    org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED,
                    org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
                    org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED
                )
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("androidx.car.app:app:1.4.0")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14")
    testImplementation("androidx.test:core:1.6.1")
}
