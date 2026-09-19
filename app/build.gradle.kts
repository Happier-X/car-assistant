

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.carassistant"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.carassistant"
        minSdk = 26
        // 关键设计决策：targetSdk 故意锁在 28。
        // Android 10 (API 29) 起，targetSdk >= 29 的应用调用
        // WifiManager.setWifiEnabled() 会被系统无条件拒绝。降到 28 可让
        // 该方法在 Android 10/11/12/13 车机上继续生效 —— 这是无 root 场景
        // 下唯一能真正「静默打开 WiFi」的合法途径。
        // 车机是侧载场景，不走 Google Play，因此不受 targetSdk 政策约束。
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            // 用 debug keystore 保证签名稳定，方便车机上覆盖升级
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 刻意不加 applicationIdSuffix：包名必须与 release 一致，
            // 否则 adb 授予的 WRITE_SECURE_SETTINGS 在 debug 包上不生效，
            // 而这条权限正是「无 root 开 WiFi」的关键后路。
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
        isCoreLibraryDesugaringEnabled = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // 只用 Icons 这个入口对象，具体图标在 CarIcons.kt 里自绘
    implementation(libs.androidx.compose.material.icons.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
}