plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.scheda.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.scheda.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = (providers.gradleProperty("SCHEDA_STORE_PASSWORD")
                .orElse(System.getenv("SCHEDA_STORE_PASSWORD") ?: "scheda")).get()
            keyAlias = "147ml"
            keyPassword = (providers.gradleProperty("SCHEDA_KEY_PASSWORD")
                .orElse(System.getenv("SCHEDA_KEY_PASSWORD") ?: "scheda")).get()
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
    }

    // APK 文件名带版本号
    applicationVariants.all {
        outputs.all {
            val variant = this
            if (variant is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                variant.outputFileName = "Schedav${versionName}.apk"
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")

    // JSON 序列化
    implementation("com.google.code.gson:gson:2.11.0")

    // 协程（viewModelScope）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.0")

    // SAF 文件操作
    implementation("androidx.documentfile:documentfile:1.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
