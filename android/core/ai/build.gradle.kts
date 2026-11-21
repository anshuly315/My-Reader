plugins {
    alias(libs.plugins.flowfolio.android.library)
    alias(libs.plugins.flowfolio.hilt)
}

android {
    namespace = "com.flowfolio.core.ai"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // On-device Gemma through LiteRT-LM (Apache-2.0): runs a .litertlm model file with nothing sent to a server.
    implementation(libs.litertlm.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
