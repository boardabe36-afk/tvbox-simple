import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 加载 keystore 凭证。
 *
 * 优先级：
 * 1. 环境变量 TVBOX_KEYSTORE_PATH / TVBOX_KEYSTORE_PASS / TVBOX_KEY_ALIAS
 * 2. 项目根目录的 keystore.properties 文件（不入 git）
 *
 * 未配置时 release 包不签名；debug 包不受影响。
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        load(keystorePropsFile.inputStream())
    }
}
val releaseStoreFile: String = (System.getenv("TVBOX_KEYSTORE_PATH")
    ?: keystoreProps.getProperty("storeFile", ""))
val releaseStorePassword: String = (System.getenv("TVBOX_KEYSTORE_PASS")
    ?: keystoreProps.getProperty("storePassword", ""))
val releaseKeyAlias: String = (System.getenv("TVBOX_KEY_ALIAS")
    ?: keystoreProps.getProperty("keyAlias", ""))
val releaseKeyPassword: String = (System.getenv("TVBOX_KEY_PASS")
    ?: keystoreProps.getProperty("keyPassword", ""))

android {
    namespace = "com.simple.tvbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.simple.tvbox"
        minSdk = 21
        targetSdk = 34
        versionCode = 12
        versionName = "1.0.11"

        // Leanback 必需
        manifestPlaceholders["leanbackBanner"] = "@drawable/app_icon"
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile.isNotBlank() && rootProject.file(releaseStoreFile).exists()) {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseStoreFile.isNotBlank() && rootProject.file(releaseStoreFile).exists()) {
                signingConfig = signingConfigs.getByName("release")
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
        viewBinding = true
    }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.8.0")

    // Android TV Leanback UI 库
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.leanback:leanback-preference:1.0.0")

    // Material 组件（设置页用）
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // 协程 + 网络
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // JSON 解析（org.json 内置，但用 Gson 更方便）
    implementation("com.google.code.gson:gson:2.10.1")

    // 播放器（Media3 / ExoPlayer）
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    // QR 码生成（局域网扫码入口）
    implementation("com.google.zxing:core:3.5.3")

    // JVM 单元测试（纯 JVM 模块）
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
