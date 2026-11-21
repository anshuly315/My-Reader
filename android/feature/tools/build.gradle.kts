plugins {
    alias(libs.plugins.flowfolio.android.library)
    alias(libs.plugins.flowfolio.android.compose)
    alias(libs.plugins.flowfolio.hilt)
}

android {
    namespace = "com.flowfolio.feature.tools"
}

dependencies {
    implementation(projects.core.ai)
    implementation(projects.core.catalog)
    implementation(projects.core.pdftools)
    implementation(projects.core.designsystem)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
