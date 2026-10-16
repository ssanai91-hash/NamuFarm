import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.reader(Charsets.UTF_8).use { load(it) }
    }
}

fun localProperty(name: String, defaultValue: String = ""): String {
    return localProperties.getProperty(name, defaultValue)
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

// Release signing belongs to the build environment, never the repository or APK.
val signingNames = listOf("NAMUFARM_KEYSTORE", "NAMUFARM_STORE_PASSWORD", "NAMUFARM_KEY_ALIAS", "NAMUFARM_KEY_PASSWORD")
val releaseSigning = signingNames.associateWith { providers.environmentVariable(it).orNull.orEmpty() }
val hasReleaseSigning = releaseSigning.values.all { it.isNotBlank() }

android {
    signingConfigs {
        if (hasReleaseSigning) create("sellerRelease") {
            storeFile = file(releaseSigning.getValue("NAMUFARM_KEYSTORE"))
            storePassword = releaseSigning.getValue("NAMUFARM_STORE_PASSWORD")
            keyAlias = releaseSigning.getValue("NAMUFARM_KEY_ALIAS")
            keyPassword = releaseSigning.getValue("NAMUFARM_KEY_PASSWORD")
        }
    }
    namespace = "com.example.namufarm"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        listOf("WEB_REQUEST_SCRIPT_URL", "FIREBASE_APPLICATION_ID", "FIREBASE_API_KEY", "FIREBASE_PROJECT_ID", "FIREBASE_SENDER_ID").forEach { name ->
            buildConfigField("String", name, "\"${localProperty(name, if(name=="WEB_REQUEST_SCRIPT_URL") "https://script.google.com/macros/s/AKfycbzY7JKzXYmS9APLNFDt15k8dv09zb4EXKFHOOv_T1vpFmkr6TLwbqGznGLgpd2cIVgD/exec" else "")}\"")
        }

        applicationId = "com.example.namufarm"
        minSdk = 24
        targetSdk = 36
        versionCode = 6
        versionName = "1.1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "JUSO_API_KEY",
            "\"${localProperty("JUSO_API_KEY")}\""
        )

        buildConfigField(
            "String",
            "GOOGLE_SCRIPT_URL",
            "\"${localProperty("GOOGLE_SCRIPT_URL", "https://example.com/")}\""
        )

        buildConfigField(
            "String",
            "PAYMENT_ACCOUNT_TEXT",
            "\"농협 328-12-035671 / 오진웅\""
        )
    }

    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("sellerRelease")
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.firebase:firebase-messaging:24.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:3.14.9")
    testImplementation("com.squareup.okhttp3:okhttp-tls:3.14.9")
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.50.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
}

base {
    archivesName.set("NamuFarm")
}

// Fail closed: release tasks must never silently produce unsigned or debug-signed APKs.
val validateSellerSigning by tasks.registering {
    doLast {
        check(hasReleaseSigning) { "Configure the persistent NamuFarm release keystore before building a release." }
        check(file(releaseSigning.getValue("NAMUFARM_KEYSTORE")).isFile) { "The release keystore is missing." }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(validateSellerSigning) }



