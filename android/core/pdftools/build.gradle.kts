plugins {
    alias(libs.plugins.flowfolio.android.library)
    alias(libs.plugins.flowfolio.hilt)
}

android {
    testOptions { unitTests.isIncludeAndroidResources = true }
    namespace = "com.flowfolio.core.pdftools"
}

dependencies {
    // PDFBox ported to Android (Apache-2.0): the engine behind merge, split, rotate, protect, watermark and the rest.
    implementation(libs.pdfbox.android)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
