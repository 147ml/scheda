plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.scheda.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.scheda.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.8"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    chaquopy {
        defaultConfig {
            buildPython("python")
            version = "3.11"
            pip {
                install("ezdxf")
                install("numpy")
            }
        }
    }

    // 仅在设置了 SCHEDA_STORE_PASSWORD 环境变量时才启用个人 release 签名。
    // 未设置时可正常编译（debug 用默认签名，release 用 Android 默认签名），方便开源用户直接构建。
    val hasReleaseKey = System.getenv("SCHEDA_STORE_PASSWORD") != null
    if (hasReleaseKey) {
        signingConfigs {
            create("release") {
                storeFile = file("../release.keystore")
                storePassword = (providers.gradleProperty("SCHEDA_STORE_PASSWORD")
                    .orElse(System.getenv("SCHEDA_STORE_PASSWORD") ?: "")).get()
                keyAlias = "147ml"
                keyPassword = (providers.gradleProperty("SCHEDA_KEY_PASSWORD")
                    .orElse(System.getenv("SCHEDA_KEY_PASSWORD") ?: "")).get()
            }
        }
    }

    buildTypes {
        debug {
            // 有个人签名时用个人签名，否则回退默认 debug 签名
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = false
    }

    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md")
        }
    }
}

base {
    archivesName = "Schedav${android.defaultConfig.versionName}"
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

    implementation("com.google.code.gson:gson:2.11.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.0")

    implementation("androidx.documentfile:documentfile:1.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
