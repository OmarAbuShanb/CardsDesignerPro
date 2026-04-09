plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.anonymous.cardsdesignerpro"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.anonymous.cardsdesignerpro"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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
        // Required for java.time APIs used by Apache POI on older API levels
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Core desugaring (for java.time on API < 26)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)

    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // CSV / Excel
    implementation(libs.opencsv)
    implementation(libs.poi.ooxml)

    // QR Code generation — custom-qr-generator (powered by ZXing internally, adds shape/logo/color support)
    implementation(libs.custom.qr.generator)

    // Color picker
    implementation(libs.colorpickerview)

    // PDF viewer
//    implementation(libs.android.pdf.viewer)

    implementation (libs.mhiew.android.pdf.viewer)

    // Image loading
    implementation(libs.coil)
    
    // SVG Parsing
    implementation("com.caverock:androidsvg-aar:1.4")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}