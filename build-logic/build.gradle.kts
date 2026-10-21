plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.android.gradlePlugin)
    implementation(libs.compose.gradlePlugin)
    implementation(libs.compose.compiler.gradlePlugin)
    implementation(libs.detekt.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kover.gradlePlugin)
    implementation(libs.ktlint.gradlePlugin)
}
