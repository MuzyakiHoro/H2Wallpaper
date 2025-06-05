plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.h2wallpaper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.h2wallpaper"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose) // 如果你确实用到了 Activity Compose 集成
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation("androidx.compose.material3:material3")

    // 我们添加的库 (如果 libs.versions.toml 中没有定义，先这样写)
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4") // ConstraintLayout 仍然需要，因为 activity_main.xml 用了它
    implementation("androidx.appcompat:appcompat:1.6.1") // 你可以检查是否有更新的稳定版本
    implementation("androidx.viewpager2:viewpager2:1.1.0") // 或更新的稳定版本 (1.1.0-beta02, 1.1.0等)
    implementation("androidx.preference:preference-ktx:1.2.1") // 使用最新的稳定版本
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0") // 使用最新的稳定版本
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation(libs.androidx.runtime.livedata) // 使用最新的稳定版本
    implementation("com.google.android.material:material:1.12.0") // 或者更新的稳定版本
    implementation("androidx.compose.material:material-icons-core:1.6.7") // 或最新版本
    implementation("androidx.compose.material:material-icons-extended:1.6.7") // 或最新版本


    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}