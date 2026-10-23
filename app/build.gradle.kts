plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.baiyun.pet"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.baiyun.pet"
        minSdk = 24          // 需求：最低 Android 7.0
        targetSdk = 34       // 需求：Android 14
        versionCode = 12
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 固定签名：本地和 CI 用同一把 key，版本更新才能直接覆盖安装、保留设置。
    // 这是自用签名（密码也在仓库里），别拿去上架应用商店。
    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("keystore/baiyun-release.p12")
            storePassword = "baiyunpet2024"
            keyAlias = "baiyun"
            keyPassword = "baiyunpet2024"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            // 关键：debug 包也用固定签名，否则 CI 每次都换新 key，装不上旧版本
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    // 聊天气泡里的消息列表
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // DeepSeek 聊天接口
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 无障碍节点遍历放到 IO 线程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 本地 OCR（中文文字识别，离线）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
    implementation("com.google.code.gson:gson:2.10.1")
}
