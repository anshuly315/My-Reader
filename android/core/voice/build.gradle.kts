plugins {
    alias(libs.plugins.flowfolio.android.library)
}

android {
    namespace = "com.flowfolio.core.voice"
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
