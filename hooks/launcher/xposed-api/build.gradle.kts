plugins {
    id("com.android.library")
}

android {
    namespace = "de.robv.xposed.api"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 23
    }
}
