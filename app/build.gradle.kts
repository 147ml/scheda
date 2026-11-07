plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.scheda.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.scheda.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 30
        versionName = "3.5.2"

        ndk {
            // 发布包只带 arm64-v8a（x86_64 仅供模拟器调试）。
            // 需要在 x86_64 模拟器上调试时加：-PSCHEDA_X86=true
            abiFilters += if (providers.gradleProperty("SCHEDA_X86").orNull == "true")
                listOf("arm64-v8a", "x86_64") else listOf("arm64-v8a")
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

    // 签名密钥不入库：有 release.keystore 就用正式签名，没有则退回 debug 签名，
    // 保证任何人 clone 下来都能直接编译（正式密钥只在本机/CI 私密环境提供）。
    val releaseKeystore = rootProject.file("release.keystore")
    signingConfigs {
        if (releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = (providers.gradleProperty("SCHEDA_STORE_PASSWORD")
                    .orElse(System.getenv("SCHEDA_STORE_PASSWORD") ?: "")).get()
                keyAlias = (providers.gradleProperty("SCHEDA_KEY_ALIAS")
                    .orElse(System.getenv("SCHEDA_KEY_ALIAS") ?: "147ml")).get()
                keyPassword = (providers.gradleProperty("SCHEDA_KEY_PASSWORD")
                    .orElse(System.getenv("SCHEDA_KEY_PASSWORD") ?: "")).get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (releaseKeystore.exists()) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
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

    // 液态玻璃效果（Kyant0 Backdrop，Apache 2.0）——NudgePad 微调键用
    implementation("io.github.kyant0:backdrop:1.0.6")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.0")

    implementation("androidx.documentfile:documentfile:1.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
