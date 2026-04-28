plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.anonymous.cardsdesignerpro.keygen"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.anonymous.cardsdesignerpro.keygen"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
}
