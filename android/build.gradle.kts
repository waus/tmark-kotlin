plugins { id("com.android.library"); id("org.jetbrains.kotlin.plugin.compose"); id("com.vanniktech.maven.publish") }
android {
    namespace = "app.tmark.android"
    compileSdk = 35
    defaultConfig { minSdk = 23; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(project(":core"))
    api("com.google.android.material:material:1.14.0")
    implementation("androidx.compose.foundation:foundation:1.8.1")
    implementation("androidx.compose.ui:ui:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime:2.9.4")
    implementation("androidx.savedstate:savedstate:1.3.3")
    implementation("com.airbnb.android:lottie:6.7.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
mavenPublishing {
    coordinates(group.toString(), "android", version.toString())
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent || providers.gradleProperty("signing.secretKeyRingFile").isPresent) {
        signAllPublications()
    }
    pom {
        name = "TMark Android"
        description = "Native Android Views for TMark documents"
        url = "https://github.com/waus/tmark-kotlin"
        licenses { license { name = "MIT License"; url = "https://opensource.org/license/mit"; distribution = "repo" } }
        developers { developer { id = "waus"; name = "Waus"; url = "https://github.com/waus" } }
        scm {
            url = "https://github.com/waus/tmark-kotlin"
            connection = "scm:git:https://github.com/waus/tmark-kotlin.git"
            developerConnection = "scm:git:ssh://git@github.com/waus/tmark-kotlin.git"
        }
    }
}
