plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.namufarm"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.namufarm.original"
        minSdk = 24
        targetSdk = 36
        versionCode = 31
        versionName = "1.1.28"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "PAYMENT_ACCOUNT_TEXT", "\"농협 328-12-035671 / 오진웅\"")
        buildConfigField("String", "WEB_REQUEST_SCRIPT_URL", "\"https://script.google.com/macros/s/AKfycbzY7JKzXYmS9APLNFDt15k8dv09zb4EXKFHOOv_T1vpFmkr6TLwbqGznGLgpd2cIVgD/exec\"")

    }

    testOptions { unitTests.isIncludeAndroidResources = true }

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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    ksp("androidx.room:room-compiler:$room_version")
    // 🚨 화면 이동(내비게이션)을 위한 라이브러리 추가!
    implementation("androidx.navigation:navigation-compose:2.7.7")
    // 🚨 다양한 아이콘(뒤로가기, 톱니바퀴 등)을 쓰기 위한 라이브러리!
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
}

// 파일 제일 밑바닥 빈 공간에 추가!
base {
    archivesName.set("NamuFarm")
}








