plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.poolaim.overlay"; compileSdk = 35
    defaultConfig {
        applicationId = "com.poolaim.overlay"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}


dependencies {
    // Legacy screenshot probe only; the live recognizer never calls this model.
    implementation("org.pytorch:pytorch_android_lite:1.13.1")
    implementation("org.opencv:opencv:4.10.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
