plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.poolaim.overlay"; compileSdk = 35
    defaultConfig { applicationId = "com.poolaim.overlay"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1" }
}


dependencies { implementation("org.pytorch:pytorch_android_lite:1.13.1") }
